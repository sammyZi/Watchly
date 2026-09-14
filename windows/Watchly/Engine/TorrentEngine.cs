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
            AutoSaveLoadDhtCache = true,
            AutoSaveLoadFastResume = true,
            AutoSaveLoadMagnetLinkMetadata = true,
            FastResumeMode = FastResumeMode.BestEffort,
            // Incomplete files carry a suffix until they finish, so nothing half
            // written ever looks like a finished episode in the downloads folder.
            UsePartialFiles = true,
            HttpStreamingPrefix = $"http://127.0.0.1:{FreePort()}/",
            MaximumConnections = 300,
            MaximumHalfOpenConnections = 16,
        };
        var app = AppSettings.Load();
        settings.MaximumDownloadRate = app.MaxDownloadKbps * 1024;
        settings.MaximumUploadRate = app.MaxUploadKbps * 1024;
        engine = new ClientEngine(settings.ToSettings());
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

    public TorrentManager? Find(string hashHex) =>
        engine.Torrents.FirstOrDefault(m => Hex(m.InfoHashes) == hashHex);

    /// <summary>
    /// Resolve a magnet, .torrent URL or local .torrent into its file list.
    /// Metadata already seen is read back from disk — no swarm needed twice.
    /// </summary>
    public async Task<(Torrent Torrent, TorrentMeta Meta)> Inspect(string uri, TimeSpan timeout, CancellationToken ct = default)
    {
        Torrent torrent;
        if (uri.StartsWith("magnet:", StringComparison.OrdinalIgnoreCase))
        {
            var link = MagnetLink.Parse(uri);
            var hex = Hex(link.InfoHashes);
            var cached = Path.Combine(MetaDir, hex + ".torrent");
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
    public async Task<TorrentManager> Manager(Torrent torrent, string saveDir)
    {
        var existing = Find(Hex(torrent.InfoHashes));
        if (existing is not null) return existing;
        Directory.CreateDirectory(saveDir);
        var settings = new TorrentSettingsBuilder { CreateContainingDirectory = false }.ToSettings();
        return await engine.AddStreamingAsync(torrent, saveDir, settings);
    }

    public async Task Remove(TorrentManager manager, bool deleteFiles)
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
            Debug.WriteLine($"[engine] remove failed: {e.Message}");
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

    public static async Task<StreamSession> Start(Torrent torrent, int fileIndex, bool deleteFilesAfter, CancellationToken ct = default)
    {
        var engine = TorrentEngine.Instance;
        var hex = torrent.InfoHashes.V1OrV2.ToHex().ToLowerInvariant();
        var borrowed = engine.Find(hex) is not null;
        var manager = await engine.Manager(torrent, Path.Combine(Paths.StreamCache, hex));
        var meta = TorrentEngine.MetaOf(torrent);
        var file = manager.Files[fileIndex];

        if (!borrowed)
        {
            // Only the file being watched, plus the subtitles sitting next to it —
            // they are tiny, and VLC picks them up as soon as they land.
            var folder = Pick.ParentDir(meta.Files[fileIndex].Path, null);
            for (var i = 0; i < manager.Files.Count; i++)
            {
                var info = meta.Files[i];
                var wanted = i == fileIndex || (info.IsSubtitle && info.Size < 2_000_000 && Pick.ParentDir(info.Path, null).StartsWith(folder));
                await manager.SetFilePriorityAsync(manager.Files[i], wanted ? Priority.Normal : Priority.DoNotDownload);
            }
        }
        else if (file.Priority == Priority.DoNotDownload)
        {
            await manager.SetFilePriorityAsync(file, Priority.Normal);
        }

        if (manager.State is TorrentState.Stopped or TorrentState.Paused or TorrentState.Error) await manager.StartAsync();
        var http = await manager.StreamProvider!.CreateHttpStreamAsync(file, false, ct);
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
