using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using MonoTorrent;
using MonoTorrent.Client;
using MonoTorrent.Streaming;
using Watchly.Core;

namespace Watchly.Engine;

/// <summary>Swarm numbers for one torrent, the same shape the phone's native module reports.</summary>
public sealed record Progress(double Percent, double Speed, double UploadSpeed, int Seeds, int Peers, string State, bool Paused);

/// <summary>
/// The one MonoTorrent client for the whole app — streaming and downloads share
/// it, so a download already running can be watched without a second swarm.
///
/// Every torrent is added in streaming mode: MonoTorrent's streaming picker
/// fetches around wherever a reader is, and falls back to ordinary rarest-first
/// for everything else, which is exactly what a download wants too.
/// </summary>
public sealed class TorrentEngine
{
    static readonly string MetaDir = Path.Combine(Paths.EngineCache, "torrents");
    public static TorrentEngine Instance { get; } = new();

    readonly ClientEngine engine;

    TorrentEngine()
    {
        Directory.CreateDirectory(Paths.EngineCache);
        Directory.CreateDirectory(MetaDir);
        var settings = new EngineSettingsBuilder
        {
            CacheDirectory = Paths.EngineCache,
            AllowPortForwarding = true,
            AllowLocalPeerDiscovery = true,
            AutoSaveLoadDhtCache = true,
            AutoSaveLoadFastResume = true,
            AutoSaveLoadMagnetLinkMetadata = true,
            FastResumeMode = FastResumeMode.BestEffort,
            // 128 MB RAM piece buffer for high-throughput streaming
            DiskCacheBytes = 128 * 1024 * 1024,
            UsePartialFiles = true,
            HttpStreamingPrefix = $"http://127.0.0.1:{FreePort()}/",
            MaximumConnections = 600,
            MaximumHalfOpenConnections = 120,
            ConnectionTimeout = TimeSpan.FromSeconds(6),
        };
        var app = AppSettings.Load();
        if (app.MaxDownloadKbps > 0) settings.MaximumDownloadRate = app.MaxDownloadKbps * 1024;
        if (app.MaxUploadKbps > 0) settings.MaximumUploadRate = app.MaxUploadKbps * 1024;
        engine = new ClientEngine(settings.ToSettings());
        Log.Info("engine", $"ClientEngine initialized on {settings.HttpStreamingPrefix} (DiskCache: 128MB, MaxConn: 600, HalfOpen: 120)");
    }

    static int FreePort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    public async Task ApplyLimits(AppSettings app)
    {
        var builder = new EngineSettingsBuilder(engine.Settings)
        {
            MaximumDownloadRate = app.MaxDownloadKbps * 1024,
            MaximumUploadRate = app.MaxUploadKbps * 1024,
        };
        await engine.UpdateSettingsAsync(builder.ToSettings());
    }

    static string Hex(InfoHashes hashes) => hashes.V1OrV2.ToHex().ToLowerInvariant();

    /// <summary>
    /// Torrents being stopped and removed, by hash. Closing the player removes its
    /// torrent in the background; playing or downloading the same one again in
    /// those seconds must not pick up the dying manager (it ends Stopped, then
    /// unregistered, and the new stream or download sits at "Starting…" forever).
    /// </summary>
    readonly System.Collections.Concurrent.ConcurrentDictionary<string, Task> leaving = new();

    /// <summary>Completes once no removal of this torrent is still in flight.</summary>
    public Task Settled(string hashHex) => leaving.TryGetValue(hashHex, out var t) ? t : Task.CompletedTask;

    public TorrentManager? Find(string hashHex) => leaving.ContainsKey(hashHex)
        ? null
        : engine.Torrents.FirstOrDefault(m => Hex(m.InfoHashes) == hashHex);

    /// <summary>
    /// Resolve a magnet, .torrent URL or local .torrent into its file list.
    /// Metadata already seen is read back from disk — no swarm needed twice.
    /// </summary>
    public async Task<(Torrent Torrent, TorrentMeta Meta)> Inspect(string uri, TimeSpan timeout, CancellationToken ct = default)
    {
        Torrent torrent;
        if (uri.StartsWith("magnet:", StringComparison.OrdinalIgnoreCase))
        {
            var link = MagnetLink.Parse(TitleText.WithTrackers(uri));
            var hex = Hex(link.InfoHashes);
            var cached = Path.Combine(MetaDir, hex + ".torrent");
            if (!File.Exists(cached)) await Settled(hex);
            var running = Find(hex);
            if (running is { HasMetadata: true })
            {
                torrent = running.Torrent!;
            }
            else if (File.Exists(cached) && Torrent.TryLoad(cached, out var fromDisk))
            {
                torrent = fromDisk;
            }
            else
            {
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                cts.CancelAfter(timeout);
                ReadOnlyMemory<byte> data;
                try
                {
                    data = await engine.DownloadMetadataAsync(link, cts.Token);
                }
                catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                {
                    throw new TimeoutException("No peers answered with this torrent's file list. Try a release with more seeders.");
                }
                torrent = Torrent.Load(data.Span);
                await File.WriteAllBytesAsync(cached, data.ToArray(), CancellationToken.None);
            }
        }
        else if (uri.StartsWith("http", StringComparison.OrdinalIgnoreCase))
        {
            var bytes = await Net.Http.GetByteArrayAsync(uri, ct);
            torrent = Torrent.Load(bytes);
            await File.WriteAllBytesAsync(Path.Combine(MetaDir, Hex(torrent.InfoHashes) + ".torrent"), bytes, CancellationToken.None);
        }
        else
        {
            var path = uri.StartsWith("file:", StringComparison.OrdinalIgnoreCase) ? new Uri(uri).LocalPath : uri;
            torrent = await Torrent.LoadAsync(path);
        }
        return (torrent, MetaOf(torrent));
    }

    public static TorrentMeta MetaOf(Torrent torrent) => new(
        torrent.Name,
        torrent.Files.Select((f, i) => new TorrentFileInfo
        {
            Index = i,
            Path = f.Path.Replace('\\', '/'),
            Name = Path.GetFileName(f.Path),
            Size = f.Length,
            IsVideo = Pick.IsVideoName(f.Path),
            IsSubtitle = Pick.IsSubtitleName(f.Path),
        }).ToList());

    /// <summary>Add (or reuse) a manager for <paramref name="torrent"/>, saving into <paramref name="saveDir"/>.</summary>
    public async Task<TorrentManager> Manager(Torrent torrent, string saveDir, string? sourceUri = null)
    {
        await Settled(Hex(torrent.InfoHashes));
        var existing = Find(Hex(torrent.InfoHashes));
        if (existing is not null)
        {
            await AddTrackersAsync(existing, sourceUri);
            return existing;
        }
        Directory.CreateDirectory(saveDir);
        var settings = new TorrentSettingsBuilder
        {
            CreateContainingDirectory = false,
            MaximumConnections = 250,
            AllowDht = true,
            AllowPeerExchange = true,
        }.ToSettings();
        var manager = await engine.AddStreamingAsync(torrent, saveDir, settings);
        await AddTrackersAsync(manager, sourceUri);
        return manager;
    }

    public async Task AddTrackersAsync(TorrentManager manager, string? sourceUri = null)
    {
        var urls = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrEmpty(sourceUri) && sourceUri.StartsWith("magnet:", StringComparison.OrdinalIgnoreCase))
        {
            if (MagnetLink.TryParse(sourceUri, out var magnet))
            {
                foreach (var tr in magnet.AnnounceUrls) urls.Add(tr);
            }
        }
        foreach (var t in TitleText.Trackers) urls.Add(t);

        int added = 0;
        foreach (var url in urls)
        {
            if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
            {
                try { await manager.TrackerManager.AddTrackerAsync(uri); added++; } catch { }
            }
        }
        Log.Torrent($"attached {added} trackers to '{manager.Name}'");
        try { _ = manager.TrackerManager.AnnounceAsync(CancellationToken.None); } catch { }
        try { _ = manager.DhtAnnounceAsync(); } catch { }
        try { _ = manager.LocalPeerAnnounceAsync(); } catch { }
    }

    public Task Remove(TorrentManager manager, bool deleteFiles)
    {
        var hex = Hex(manager.InfoHashes);
        var task = RemoveCore(manager, deleteFiles);
        leaving[hex] = task;
        _ = task.ContinueWith(_ => leaving.TryRemove(new KeyValuePair<string, Task>(hex, task)), TaskScheduler.Default);
        return task;
    }

    async Task RemoveCore(TorrentManager manager, bool deleteFiles)
    {
        try
        {
            if (manager.State is not (TorrentState.Stopped or TorrentState.Error)) await manager.StopAsync(TimeSpan.FromSeconds(3));
        }
        catch { }
        try
        {
            await engine.RemoveAsync(manager, deleteFiles ? RemoveMode.CacheDataAndDownloadedData : RemoveMode.CacheDataOnly);
        }
        catch (Exception e)
        {
            Log.Warn("engine", $"remove failed: {e.Message}");
        }
    }

    public static Progress ProgressOf(TorrentManager m, int? fileIndex = null)
    {
        var percent = fileIndex is int i && i >= 0 && i < m.Files.Count
            ? m.Files[i].BitField.PercentComplete
            : m.PartialProgress;
        return new Progress(
            percent,
            m.Monitor.DownloadRate,
            m.Monitor.UploadRate,
            m.Peers.Seeds,
            m.Peers.Leechs,
            m.State.ToString(),
            m.State is TorrentState.Paused or TorrentState.Stopped);
    }

    public async Task Shutdown()
    {
        try
        {
            // Fast-resume data is written per torrent as it stops; jobs re-add themselves next launch.
            await engine.StopAllAsync(TimeSpan.FromSeconds(3));
        }
        catch { }
        engine.Dispose();
    }
}

/// <summary>
/// One file being watched. MonoTorrent serves it over a loopback HTTP server
/// with range support, and VLC reads that like any network stream — seeking
/// moves MonoTorrent's download window with it.
/// </summary>
public sealed class StreamSession : IAsyncDisposable
{
    public TorrentManager Manager { get; }
    public int FileIndex { get; }
    public string Url { get; }
    public TorrentMeta Meta { get; }
    /// <summary>Owned by a download — leaving the player must not stop it. A download
    /// started for the torrent being watched takes the manager over and sets this.</summary>
    public bool Borrowed { get; set; }

    /// <summary>The one stream open right now, if any.</summary>
    public static StreamSession? Current { get; private set; }
    readonly IHttpStream http;
    readonly bool deleteFiles;

    StreamSession(TorrentManager manager, TorrentMeta meta, int fileIndex, IHttpStream http, bool borrowed, bool deleteFiles)
    {
        Manager = manager;
        Meta = meta;
        FileIndex = fileIndex;
        this.http = http;
        Url = http.FullUri;
        Borrowed = borrowed;
        this.deleteFiles = deleteFiles;
    }

    public static async Task<StreamSession> Start(Torrent torrent, int fileIndex, bool deleteFilesAfter, string? sourceUri = null, CancellationToken ct = default)
    {
        var engine = TorrentEngine.Instance;
        var hex = torrent.InfoHashes.V1OrV2.ToHex().ToLowerInvariant();
        await engine.Settled(hex);
        var borrowed = engine.Find(hex) is not null;
        var manager = await engine.Manager(torrent, Path.Combine(Paths.StreamCache, hex), sourceUri);
        var meta = TorrentEngine.MetaOf(torrent);
        var file = manager.Files[fileIndex];

        if (!borrowed)
        {
            // Only the file being watched, plus ALL subtitles in the torrent.
            // Subtitles are tiny (a few KB); downloading at Priority.Highest finishes them
            // in seconds so LibVLC can attach them immediately.
            for (var i = 0; i < manager.Files.Count; i++)
            {
                var info = meta.Files[i];
                if (i == fileIndex)
                {
                    await manager.SetFilePriorityAsync(manager.Files[i], Priority.Normal);
                }
                else if (info.IsSubtitle && info.Size < 10_000_000)
                {
                    await manager.SetFilePriorityAsync(manager.Files[i], Priority.Highest);
                }
                else
                {
                    await manager.SetFilePriorityAsync(manager.Files[i], Priority.DoNotDownload);
                }
            }
        }
        else
        {
            if (file.Priority == Priority.DoNotDownload)
                await manager.SetFilePriorityAsync(file, Priority.Normal);
            // Boost all subtitles in borrowed sessions as well
            for (var i = 0; i < manager.Files.Count; i++)
            {
                var info = meta.Files[i];
                if (info.IsSubtitle && info.Size < 10_000_000 && manager.Files[i].Priority != Priority.Highest)
                {
                    await manager.SetFilePriorityAsync(manager.Files[i], Priority.Highest);
                }
            }
        }

        if (manager.State is TorrentState.Stopped or TorrentState.Paused or TorrentState.Error) await manager.StartAsync();
        var http = await manager.StreamProvider!.CreateHttpStreamAsync(file, false, ct);
        Log.Stream($"Streaming HTTP ready at {http.FullUri} for '{file.Path}'");
        return Current = new StreamSession(manager, meta, fileIndex, http, borrowed, deleteFilesAfter);
    }

    public Progress Progress => TorrentEngine.ProgressOf(Manager, FileIndex);

    /// <summary>Subtitle files from this torrent that have finished downloading.</summary>
    public IEnumerable<string> ReadySubtitles() => Meta.Files
        .Where(f => f.IsSubtitle && Manager.Files[f.Index].Priority != Priority.DoNotDownload && Manager.Files[f.Index].BitField.AllTrue)
        .Select(f => Manager.Files[f.Index].DownloadCompleteFullPath)
        .Where(File.Exists);

    public async ValueTask DisposeAsync()
    {
        if (Current == this) Current = null;
        try { http.Dispose(); } catch { }
        if (!Borrowed) await TorrentEngine.Instance.Remove(Manager, deleteFiles);
    }
}
