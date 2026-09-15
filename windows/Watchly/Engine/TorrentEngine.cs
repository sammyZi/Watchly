using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using MonoTorrent;
using MonoTorrent.Client;
using MonoTorrent.PiecePicking;
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
                try
                {
                    torrent = await FetchMetadata(link, hex, cached, uri).WaitAsync(timeout, ct);
                }
                catch (Exception e) when (e is TimeoutException or OperationCanceledException && !ct.IsCancellationRequested)
                {
                    throw new TimeoutException("No peers answered with this torrent's file list. Try a release with more seeders.");
                }
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

    /// <summary>
    /// File-list fetches in flight, by hash — everyone asking for the same magnet
    /// waits on the one fetch (a second registration of a torrent is refused:
    /// "A manager for this torrent has already been registered").
    /// </summary>
    readonly Dictionary<string, Task<Torrent>> fetching = new();

    Task<Torrent>? Fetching(string hex)
    {
        lock (fetching) return fetching.GetValueOrDefault(hex);
    }

    /// <summary>
    /// Magnets fetched for their file list and not yet taken by a stream or a
    /// download, by hash. They stay connected so playing right after starts on
    /// peers already found; one nobody takes is dropped after a few minutes.
    /// </summary>
    readonly HashSet<string> probes = new();

    /// <summary>Take over a fetched magnet's manager. True if there was one to take.</summary>
    public bool Claim(string hex)
    {
        lock (probes) return probes.Remove(hex);
    }

    bool IsProbe(string hex)
    {
        lock (probes) return probes.Contains(hex);
    }

    static TorrentSettings StreamSettings() => new TorrentSettingsBuilder
    {
        CreateContainingDirectory = false,
        MaximumConnections = 250,
        AllowDht = true,
        AllowPeerExchange = true,
    }.ToSettings();

    /// <summary>
    /// Why not engine.DownloadMetadataAsync: it puts every tracker of the magnet in
    /// one tier, which MonoTorrent tries a tracker at a time — a few dead ones ate
    /// the whole timeout (The Matrix YIFY, ~800 seeders: 12 s, then 60 s and
    /// nothing). It also throws its peers away, so the stream had to find them all
    /// again. Adding the magnet itself with each tracker in its own tier announces
    /// to all of them at once (2–8 s for the same torrent) and keeps the peers.
    /// </summary>
    Task<Torrent> FetchMetadata(MagnetLink link, string hex, string cachePath, string sourceUri)
    {
        lock (fetching)
        {
            if (fetching.TryGetValue(hex, out var running)) return running;
            var task = Fetch();
            fetching[hex] = task;
            // Added before this can run, so a fetch that fails at once is still cleared.
            _ = task.ContinueWith(_ =>
            {
                lock (fetching) if (fetching.GetValueOrDefault(hex) == task) fetching.Remove(hex);
            }, TaskScheduler.Default);
            return task;
        }

        async Task<Torrent> Fetch()
        {
            TorrentManager manager;
            await registering.WaitAsync();
            try
            {
                if (Find(hex) is { } running)
                {
                    manager = running;
                }
                else
                {
                    var dir = Path.Combine(Paths.StreamCache, hex);
                    Directory.CreateDirectory(dir);
                    // Bare: the magnet's trackers are added below, one tier each.
                    manager = await engine.AddStreamingAsync(new MagnetLink(link.InfoHashes, link.Name, null, null, link.Size), dir, StreamSettings());
                    lock (probes) probes.Add(hex);
                }
            }
            finally
            {
                registering.Release();
            }

            if (IsProbe(hex))
            {
                await AddTrackersAsync(manager, sourceUri);
                if (manager.State is TorrentState.Stopped) await manager.StartAsync();
            }

            // Callers give up on their own timeouts; the shared fetch stops at the longest of them.
            var clock = Stopwatch.StartNew();
            while (!manager.HasMetadata)
            {
                if (clock.Elapsed > TimeSpan.FromSeconds(90) || !engine.Torrents.Contains(manager))
                {
                    if (Claim(hex)) await Remove(manager, false);
                    throw new OperationCanceledException();
                }
                await Task.Delay(100);
            }
            Log.Torrent($"file list for '{manager.Torrent!.Name}' in {clock.ElapsedMilliseconds} ms from {manager.OpenConnections} peers");

            if (IsProbe(hex))
            {
                // A streaming manager starts on every file once it knows them; nothing is wanted until someone picks.
                foreach (var file in manager.Files) await manager.SetFilePriorityAsync(file, Priority.DoNotDownload);
                _ = DropIfUnclaimed(hex, manager);
            }
            try
            {
                if (File.Exists(manager.MetadataPath)) File.Copy(manager.MetadataPath, cachePath, true);
            }
            catch { }
            return manager.Torrent;
        }
    }

    async Task DropIfUnclaimed(string hex, TorrentManager manager)
    {
        await Task.Delay(TimeSpan.FromMinutes(3));
        if (Claim(hex)) await Remove(manager, false);
    }

    /// <summary>One registration at a time: two callers could otherwise both find no manager and both add one.</summary>
    readonly SemaphoreSlim registering = new(1, 1);

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
        var hex = Hex(torrent.InfoHashes);
        await Settled(hex);
        // A file-list fetch for this torrent still holds its registration.
        if (Fetching(hex) is { } fetch) try { await fetch; } catch { }

        // A fetched magnet saving somewhere else (a download, not the stream cache)
        // can't be moved while it runs; start that one afresh.
        if (Find(hex) is { } probe && IsProbe(hex) && !SamePath(probe.SavePath, saveDir) && Claim(hex))
            await Remove(probe, false);

        TorrentManager manager;
        await registering.WaitAsync();
        try
        {
            if (Find(hex) is { } existing)
            {
                manager = existing;
                Claim(hex);
            }
            else
            {
                Directory.CreateDirectory(saveDir);
                manager = await engine.AddStreamingAsync(torrent, saveDir, StreamSettings());
            }
        }
        finally
        {
            registering.Release();
        }
        await AddTrackersAsync(manager, sourceUri);
        return manager;
    }

    static bool SamePath(string a, string b) =>
        string.Equals(Path.GetFullPath(a).TrimEnd('\\'), Path.GetFullPath(b).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);

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
        var paused = m.State is TorrentState.Paused or TorrentState.Stopped;
        return new Progress(
            percent,
            paused ? 0 : m.Monitor.DownloadRate, // the monitor keeps its last reading while paused
            paused ? 0 : m.Monitor.UploadRate,
            m.Peers.Seeds,
            m.Peers.Leechs,
            m.State.ToString(),
            paused);
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
    /// started for the torrent being watched takes the manager over and sets this,
    /// which also lifts the streaming window: a download wants every piece.</summary>
    public bool Borrowed
    {
        get => borrowed;
        set
        {
            borrowed = value;
            if (window is not null) window.Open = value;
        }
    }
    bool borrowed;
    readonly WindowedRequester? window;

    /// <summary>The one stream open right now, if any.</summary>
    public static StreamSession? Current { get; private set; }
    readonly IHttpStream http;
    readonly bool deleteFiles;

    StreamSession(TorrentManager manager, TorrentMeta meta, int fileIndex, IHttpStream http, bool borrowed, bool deleteFiles, WindowedRequester? window)
    {
        Manager = manager;
        Meta = meta;
        FileIndex = fileIndex;
        this.http = http;
        Url = http.FullUri;
        this.window = window;
        Borrowed = borrowed;
        this.deleteFiles = deleteFiles;
    }

    public static async Task<StreamSession> Start(Torrent torrent, int fileIndex, bool deleteFilesAfter, string? sourceUri = null, CancellationToken ct = default)
    {
        var engine = TorrentEngine.Instance;
        if (Pick.IsUnsafe(torrent.Files[fileIndex].Path))
            throw new InvalidOperationException("That file could run on your PC (a program, script or shortcut), so it isn't downloaded.");
        var hex = torrent.InfoHashes.V1OrV2.ToHex().ToLowerInvariant();
        await engine.Settled(hex);
        // Running because the file list was just fetched is ours to take; running for a download is borrowed.
        var borrowed = engine.Find(hex) is not null && !engine.Claim(hex);
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
                else if (info.IsSubtitle && info.Size < 10_000_000 && !Pick.IsUnsafe(info.Path))
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

        // Our own torrent: fetch only the window around the playhead, never the whole film.
        WindowedRequester? window = null;
        if (!borrowed)
        {
            window = new WindowedRequester(fileIndex);
            await manager.ChangePickerAsync(window);
        }
        if (manager.State is TorrentState.Stopped or TorrentState.Paused or TorrentState.Error) await manager.StartAsync();
        var http = await manager.StreamProvider!.CreateHttpStreamAsync(file, false, ct);
        Log.Stream($"Streaming HTTP ready at {http.FullUri} for '{file.Path}'");
        return Current = new StreamSession(manager, meta, fileIndex, http, borrowed, deleteFilesAfter, window);
    }

    public Progress Progress => TorrentEngine.ProgressOf(Manager, FileIndex);

    const double WindowSeconds = 5 * 60, BehindSeconds = 5;

    /// <summary>
    /// Watching, not downloading: the next five minutes past the playhead keep downloading,
    /// continuously, the window moving with playback and jumping with a seek — and nothing
    /// else of the film is fetched. Playback never waits for the five minutes. Called on
    /// the player's tick; before libVLC knows the length only what it reads is fetched.
    /// </summary>
    public Task KeepBuffer(double positionSeconds, double durationSeconds)
    {
        if (window is null || durationSeconds <= 0) return Task.CompletedTask;
        var bytesPerSecond = Manager.Files[FileIndex].Length / durationSeconds;
        var from = (long)((positionSeconds - BehindSeconds) * bytesPerSecond);
        window.SetWindow(from, from + (long)((WindowSeconds + BehindSeconds) * bytesPerSecond));
        return Task.CompletedTask;
    }

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

/// <summary>
/// MonoTorrent's streaming picker fetches where the player reads — and, alongside, the rest
/// of the torrent rarest-first, so watching quietly downloaded the whole film. This wraps it
/// with one more "ignore these pieces" bitfield covering the watched file outside a window:
/// where the player reads (plus the picker's own look-ahead) and the span the player's tick
/// sets. Everything is applied on the engine's thread, just before the picker requests.
/// </summary>
sealed class WindowedRequester : IStreamingPieceRequester
{
    /// <summary>The look-ahead StreamingPieceRequester keeps past the read position (LowPriorityCount).</summary>
    const int ReadAhead = 30;

    readonly StreamingPieceRequester inner = new();
    readonly int fileIndex;
    IPieceRequesterData? data;
    BitField? outside;

    long wantFrom = -1, wantTo = -1;
    volatile bool open;
    (int Reader, long From, long To, bool Open) applied = (-1, -2, -2, false);

    public WindowedRequester(int fileIndex) => this.fileIndex = fileIndex;

    /// <summary>Byte span of the file to keep fetching; set from any thread.</summary>
    public void SetWindow(long from, long to)
    {
        Interlocked.Exchange(ref wantFrom, from);
        Interlocked.Exchange(ref wantTo, to);
    }

    /// <summary>No window at all — a download shares the torrent.</summary>
    public bool Open { set => open = value; }

    public void Initialise(IPieceRequesterData torrentData, IMessageEnqueuer enqueuer, ReadOnlySpan<ReadOnlyBitField> ignoringBitfields)
    {
        data = torrentData;
        outside = new BitField(torrentData.PieceCount);
        var all = new ReadOnlyBitField[ignoringBitfields.Length + 1];
        ignoringBitfields.CopyTo(all);
        all[^1] = outside;
        inner.Initialise(torrentData, enqueuer, all);
        applied = (-1, -2, -2, false);
        Apply();
    }

    void Apply()
    {
        if (data is null || outside is null || fileIndex >= data.Files.Count) return;
        var now = (inner.HighPriorityPieceIndex, Interlocked.Read(ref wantFrom), Interlocked.Read(ref wantTo), open);
        if (now == applied) return;
        applied = now;
        var file = data.Files[fileIndex];
        int PieceAt(long offset) => data.ByteOffsetToPieceIndex(file.OffsetInTorrent + Math.Clamp(offset, 0, Math.Max(0, file.Length - 1)));
        var (reader, from, to, isOpen) = now;
        int spanFrom = to > 0 ? PieceAt(from) : int.MaxValue, spanTo = to > 0 ? PieceAt(to) : int.MinValue;
        for (var i = file.StartPieceIndex; i <= file.EndPieceIndex; i++)
            outside[i] = !(isOpen || (i >= reader && i <= reader + ReadAhead) || (i >= spanFrom && i <= spanTo));
    }

    public bool InEndgameMode => inner.InEndgameMode;

    public void AddRequests(ReadOnlySpan<(IRequester Peer, ReadOnlyBitField Available)> peers)
    {
        Apply();
        inner.AddRequests(peers);
    }

    public void AddRequests(IRequester peer, ReadOnlyBitField available, ReadOnlySpan<ReadOnlyBitField> peers)
    {
        Apply();
        inner.AddRequests(peer, available, peers);
    }

    public void SeekToPosition(ITorrentManagerFile file, long position) => inner.SeekToPosition(file, position);
    public void ReadToPosition(ITorrentManagerFile file, long position) => inner.ReadToPosition(file, position);
    public bool ValidatePiece(IRequester peer, PieceSegment pieceSegment, out bool pieceComplete, HashSet<IRequester> peersInvolved) => inner.ValidatePiece(peer, pieceSegment, out pieceComplete, peersInvolved);
    public bool IsInteresting(IRequester peer, ReadOnlyBitField bitField) => inner.IsInteresting(peer, bitField);
    public void CancelRequests(IRequester peer, int startIndex, int endIndex) => inner.CancelRequests(peer, startIndex, endIndex);
    public void RequestRejected(IRequester peer, PieceSegment pieceRequest) => inner.RequestRejected(peer, pieceRequest);
    public int CurrentRequestCount() => inner.CurrentRequestCount();
}
