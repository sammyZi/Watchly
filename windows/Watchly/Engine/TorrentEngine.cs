using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using MonoTorrent;
using MonoTorrent.BEncoding;
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

    /// <summary>The DHT cache is read when the first torrent starts; adding one waits for it (briefly).</summary>
    readonly Task dhtSeeded;

    async Task DhtReady()
    {
        KeepDhtAlive();
        try { await dhtSeeded.WaitAsync(TimeSpan.FromSeconds(3)); } catch { }
    }

    TorrentEngine()
    {
        Directory.CreateDirectory(Paths.EngineCache);
        Directory.CreateDirectory(MetaDir);
        var settings = new EngineSettingsBuilder
        {
            CacheDirectory = Paths.EngineCache,
            AllowPortForwarding = true,
            // Off: it broadcasts the hash of every torrent you watch to the whole local network
            // (office, hostel, café Wi-Fi), where anyone listening can see what it is.
            AllowLocalPeerDiscovery = false,
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
        // One known port, so a router rule can forward it (random each launch otherwise).
        var port = PeerPort();
        settings.ListenEndPoints = new Dictionary<string, IPEndPoint>
        {
            ["ipv4"] = new(IPAddress.Any, port),
            ["ipv6"] = new(IPAddress.IPv6Any, port),
        };
        settings.DhtEndPoint = new IPEndPoint(IPAddress.Any, port);
        var app = AppSettings.Load();
        if (app.MaxDownloadKbps > 0) settings.MaximumDownloadRate = app.MaxDownloadKbps * 1024;
        if (app.MaxUploadKbps > 0) settings.MaximumUploadRate = app.MaxUploadKbps * 1024;
        dhtSeeded = Task.Run(SeedDhtNodes);
        engine = new ClientEngine(settings.ToSettings());
        HookDht();
        Log.Info("engine", $"ClientEngine initialized on {settings.HttpStreamingPrefix} (DiskCache: 128MB, MaxConn: 600, HalfOpen: 120)");
    }

    /// <summary>
    /// A torrent asks DHT for peers as it starts, before DHT has joined the network, and then not
    /// again for a long while: ask again for every torrent the moment DHT is ready. Every state is
    /// logged, so a DHT that never comes up says so. Again after a restart: MonoTorrent replaces
    /// engine.Dht then, and the old one's events stop.
    /// </summary>
    void HookDht()
    {
        var dht = engine.Dht;
        dht.StateChanged += (_, _) =>
        {
            Log.Torrent($"DHT {dht.State} with {dht.NodeCount} nodes");
            if (dht.State != MonoTorrent.Dht.DhtState.Ready) return;
            foreach (var manager in engine.Torrents.ToList())
                _ = manager.DhtAnnounceAsync();
        };
    }

    int watchingDht;

    /// <summary>
    /// DHT finds most of a thin swarm's peers — a Rutracker release had 4 seeders on the trackers.
    /// Once in a session MonoTorrent's never came up (no UDP socket on the peer port, no "DHT ready"),
    /// and every torrent made do with tracker peers: 60 s and no file list. While torrents run, a DHT
    /// not ready after 15 s is restarted — a fresh listener and engine, from the saved nodes — three
    /// times at most. Calibration knob: 15 s is long past the usual second it takes to come up.
    /// </summary>
    void KeepDhtAlive()
    {
        if (Interlocked.Exchange(ref watchingDht, 1) == 1) return;
        _ = Task.Run(async () =>
        {
            for (var restarts = 0; restarts < 3;)
            {
                await Task.Delay(TimeSpan.FromSeconds(15));
                if (engine.Dht.State == MonoTorrent.Dht.DhtState.Ready || engine.Torrents.Count == 0) continue;
                restarts++;
                Log.Warn("torrent", $"DHT {engine.Dht.State} with {engine.Dht.NodeCount} nodes after 15 s — restarting it ({restarts}/3)");
                try
                {
                    // Off and on again: MonoTorrent builds a new listener and engine only when the endpoint changes.
                    var endpoint = engine.Settings.DhtEndPoint;
                    if (endpoint is null) return;
                    await engine.UpdateSettingsAsync(new EngineSettingsBuilder(engine.Settings) { DhtEndPoint = null }.ToSettings());
                    await engine.UpdateSettingsAsync(new EngineSettingsBuilder(engine.Settings) { DhtEndPoint = endpoint }.ToSettings());
                    HookDht();
                }
                catch (Exception e)
                {
                    Log.Warn("torrent", $"DHT restart failed: {e.Message}");
                }
            }
        });
    }

    /// <summary>
    /// DHT finds peers without trackers — often most of a torrent's swarm. MonoTorrent joins it
    /// only through router.bittorrent.com, which doesn't answer on some networks (measured here:
    /// no reply; DHT stayed at 0 nodes, so every torrent got tracker peers only). It also starts
    /// from the nodes it saved last time, so when that cache is empty this writes bootstrap
    /// servers into it — compact node entries: a 20-byte id (any), IPv4 address, port.
    ///
    /// MonoTorrent 3.0.2 saves its nodes as a bencoded list of those entries but loads only the
    /// raw entries, so its own save never loads (measured: reloaded, 0 nodes) — every launch after
    /// a good one started with DHT dead. The save is flattened back here, bootstrap servers added.
    /// </summary>
    static async Task SeedDhtNodes()
    {
        const int Compact = 26;
        var cache = Path.Combine(Paths.EngineCache, "dht_nodes.cache");
        try
        {
            var entries = new List<byte>();
            if (File.Exists(cache))
            {
                var bytes = await File.ReadAllBytesAsync(cache);
                if (bytes.Length > 0 && bytes[0] == 'l')
                {
                    try
                    {
                        foreach (var node in BEncodedValue.Decode<BEncodedList>(bytes).OfType<BEncodedString>())
                            if (node.Span.Length == Compact) entries.AddRange(node.Span.ToArray());
                    }
                    catch { }
                }
                else if (bytes.Length % Compact == 0) entries.AddRange(bytes);
            }
            var saved = entries.Count / Compact;
            (string Host, int Port)[] routers =
            {
                ("dht.transmissionbt.com", 6881), ("dht.libtorrent.org", 25401), ("router.bittorrent.com", 6881),
                ("router.utorrent.com", 6881), ("dht.aelitis.com", 6881), ("router.bitcomet.com", 6881),
            };
            var lookups = await Task.WhenAll(routers.Select(async r =>
            {
                try { return (r.Port, Addresses: await Dns.GetHostAddressesAsync(r.Host)); }
                catch { return (r.Port, Addresses: Array.Empty<IPAddress>()); }
            }));
            foreach (var (port, addresses) in lookups)
            {
                foreach (var address in addresses.Where(a => a.AddressFamily == AddressFamily.InterNetwork).Take(4))
                {
                    entries.AddRange(System.Security.Cryptography.RandomNumberGenerator.GetBytes(20));
                    entries.AddRange(address.GetAddressBytes());
                    entries.Add((byte)(port >> 8));
                    entries.Add((byte)port);
                }
            }
            if (entries.Count == 0) return;
            await File.WriteAllBytesAsync(cache, entries.ToArray());
            Log.Torrent($"DHT: {saved} saved nodes + {entries.Count / Compact - saved} bootstrap nodes");
        }
        catch (Exception e)
        {
            Log.Warn("engine", $"DHT bootstrap seed failed: {e.Message}");
        }
    }

    /// <summary>The port forwarded on the router for incoming peers (TCP and UDP).</summary>
    // ponytail: fixed constant; add a Settings field if someone needs a different port.
    const int ForwardedPort = 51413;

    /// <summary>
    /// <see cref="ForwardedPort"/>, unless something else holds it (another torrent client, or
    /// a second Watchly) — then any free port, so the engine still starts, just unforwarded.
    /// </summary>
    static int PeerPort()
    {
        try
        {
            var tcp = new TcpListener(IPAddress.Any, ForwardedPort);
            tcp.Start();
            tcp.Stop();
            using var udp = new UdpClient(ForwardedPort);
            return ForwardedPort;
        }
        catch (SocketException)
        {
            Log.Warn("engine", $"port {ForwardedPort} is in use; incoming peers can't reach this session");
            return 0;
        }
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
            if (!File.Exists(cached) && leaving.ContainsKey(hex))
            {
                Log.Torrent($"inspect {hex}: waiting for the last copy to close");
                await Settled(hex);
            }
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
                var sw = Stopwatch.StartNew();
                Log.Torrent($"inspect {hex}: fetching metadata");
                try
                {
                    torrent = await FetchMetadata(link, hex, cached, uri).WaitAsync(timeout, ct);
                    Log.Torrent($"inspect {hex}: metadata in {sw.ElapsedMilliseconds}ms");
                }
                catch (Exception e) when (e is TimeoutException or OperationCanceledException && !ct.IsCancellationRequested)
                {
                    Log.Warn("torrent", $"inspect {hex}: no metadata after {sw.ElapsedMilliseconds}ms");
                    throw new TimeoutException("No peers answered with this torrent's file list. Try a release with more seeders.");
                }
            }
        }
        else if (uri.StartsWith("http", StringComparison.OrdinalIgnoreCase))
        {
            // Cached by address too: the hash is only known once the file is fetched.
            var byUrl = Path.Combine(MetaDir, "url-" + Convert.ToHexString(System.Security.Cryptography.SHA1.HashData(System.Text.Encoding.UTF8.GetBytes(uri))) + ".torrent");
            if (File.Exists(byUrl) && Torrent.TryLoad(byUrl, out var fromDisk))
            {
                torrent = fromDisk;
            }
            else
            {
                var bytes = await Net.Http.GetByteArrayAsync(uri, ct);
                torrent = Torrent.Load(bytes);
                await File.WriteAllBytesAsync(Path.Combine(MetaDir, Hex(torrent.InfoHashes) + ".torrent"), bytes, CancellationToken.None);
                await File.WriteAllBytesAsync(byUrl, bytes, CancellationToken.None);
            }
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
            await DhtReady();
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
                    await AttachWindow(manager);
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

    /// <summary>
    /// File lists saved from every torrent opened (streamed, downloaded or just looked at),
    /// so opening one again skips asking the swarm. Kept until cleared in Settings.
    /// </summary>
    static IEnumerable<FileInfo> SavedFileLists() => new[] { MetaDir, Path.Combine(Paths.EngineCache, "metadata") }
        .Where(Directory.Exists)
        .SelectMany(dir => new DirectoryInfo(dir).EnumerateFiles("*.torrent"));

    public static (int Torrents, long Bytes) SavedFileListsSize()
    {
        var files = SavedFileLists().ToList();
        return (files.Count(f => !f.Name.StartsWith("url-") && f.DirectoryName == MetaDir), files.Sum(f => f.Length));
    }

    /// <summary>Forget saved file lists. Torrents open now keep working; the next open asks the swarm again.</summary>
    public static int ClearSavedFileLists()
    {
        var cleared = 0;
        foreach (var file in SavedFileLists())
        {
            try { file.Delete(); cleared++; } catch { }
        }
        return cleared;
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
        var hex = Hex(torrent.InfoHashes);
        await Settled(hex);
        // A file-list fetch for this torrent still holds its registration.
        if (Fetching(hex) is { } fetch) try { await fetch; } catch { }

        // A fetched magnet saving somewhere else (a download, not the stream cache)
        // can't be moved while it runs; start that one afresh.
        if (Find(hex) is { } probe && IsProbe(hex) && !SamePath(probe.SavePath, saveDir) && Claim(hex))
            await Remove(probe, false);

        TorrentManager manager;
        await DhtReady();
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
                await AttachWindow(manager);
            }
        }
        finally
        {
            registering.Release();
        }
        await AddTrackersAsync(manager, sourceUri);
        return manager;
    }

    /// <summary>Each manager's picker, put in while it is still stopped (the only time MonoTorrent allows it).</summary>
    readonly System.Runtime.CompilerServices.ConditionalWeakTable<TorrentManager, WindowedRequester> windows = new();

    async Task AttachWindow(TorrentManager manager)
    {
        var window = new WindowedRequester();
        await manager.ChangePickerAsync(window);
        windows.AddOrUpdate(manager, window);
    }

    public WindowedRequester? WindowOf(TorrentManager manager) => windows.TryGetValue(manager, out var w) ? w : null;

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
            if (window is not null) window.Open = borrowed || wholeFile;
        }
    }
    bool borrowed, wholeFile;

    /// <summary>The viewer's choice in the player: the whole file, or only the next five minutes.</summary>
    public bool WholeFile
    {
        get => wholeFile;
        set
        {
            wholeFile = value;
            if (window is not null) window.Open = borrowed || wholeFile;
        }
    }
    readonly WindowedRequester? window;

    /// <summary>The one stream open right now, if any.</summary>
    public static StreamSession? Current { get; private set; }
    readonly IHttpStream http;
    bool deleteFiles;
    /// <summary>A stopped download's unfinished copy of the watched file, deleted once the player lets go of it.</summary>
    string? leftover;

    /// <summary>
    /// The download sharing this torrent has gone (finished or stopped): the stream owns it
    /// again and removes it when the player closes. Files are deleted then only if they are
    /// in the stream cache — a download folder holds what the download already saved.
    /// </summary>
    public void TakeBack(bool dropWatchedFile)
    {
        var inCache = Path.GetFullPath(Manager.SavePath)
            .StartsWith(Path.GetFullPath(Paths.StreamCache), StringComparison.OrdinalIgnoreCase);
        if (!inCache)
        {
            deleteFiles = false;
            if (dropWatchedFile) leftover = Manager.Files[FileIndex].DownloadIncompleteFullPath;
        }
        Borrowed = false;
    }

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
            throw new InvalidOperationException("That file isn't a video, so it isn't downloaded — torrents are a common way malware spreads.");
        var hex = torrent.InfoHashes.V1OrV2.ToHex().ToLowerInvariant();
        await engine.Settled(hex);
        // Running because the file list was just fetched is ours to take; running for a download is borrowed.
        var borrowed = engine.Find(hex) is not null && !engine.Claim(hex);
        var manager = await engine.Manager(torrent, Path.Combine(Paths.StreamCache, hex), sourceUri);
        var meta = TorrentEngine.MetaOf(torrent);
        var file = manager.Files[fileIndex];
        // This video's own subtitles only — a season pack has one per episode, and attaching them all
        // filled the list with every other episode's. Tiny, so at Highest they are there in seconds.
        var subs = Pick.SubtitlesFor(meta.Files, fileIndex).Where(i => meta.Files[i].Size < 10_000_000).ToHashSet();

        if (!borrowed)
        {
            for (var i = 0; i < manager.Files.Count; i++)
                await manager.SetFilePriorityAsync(manager.Files[i], i == fileIndex ? Priority.Normal : subs.Contains(i) ? Priority.Highest : Priority.DoNotDownload);
        }
        else
        {
            if (file.Priority == Priority.DoNotDownload)
                await manager.SetFilePriorityAsync(file, Priority.Normal);
            foreach (var i in subs.Where(i => manager.Files[i].Priority != Priority.Highest))
                await manager.SetFilePriorityAsync(manager.Files[i], Priority.Highest);
        }

        // Our own torrent: fetch only the window around the playhead, never the whole film.
        // (Its picker was put in when it was added — MonoTorrent only swaps pickers while stopped.)
        var window = borrowed ? null : engine.WindowOf(manager);
        window?.Watch(fileIndex);
        engine.WindowOf(manager)?.ReadFrom(file);
        if (manager.State is TorrentState.Stopped or TorrentState.Paused or TorrentState.Error) await manager.StartAsync();
        if (!borrowed) _ = CreateSparseWhenDownloading(manager, file);
        IHttpStream http;
        try
        {
            http = await manager.StreamProvider!.CreateHttpStreamAsync(file, false, ct);
        }
        catch when (!borrowed)
        {
            // Left (or failed) before a session existed to own the torrent: nothing
            // else would ever remove it, and it would download its window until exit.
            await engine.Remove(manager, deleteFilesAfter);
            throw;
        }
        Log.Stream($"Streaming HTTP ready at {http.FullUri} for '{file.Path}'");
        return Current = new StreamSession(manager, meta, fileIndex, http, borrowed, deleteFilesAfter, window);
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
    static extern bool DeviceIoControl(Microsoft.Win32.SafeHandles.SafeFileHandle handle, uint code, IntPtr inBuffer, int inSize, IntPtr outBuffer, int outSize, out int returned, IntPtr overlapped);

    /// <summary>
    /// MonoTorrent creates a file on its first write by preallocating the whole length (38 GB for
    /// a remux, just to watch it) and, when two cache flushes race there, fails with "access
    /// denied" and loses those blocks. Made here first, sparse, it only ever opens an existing file.
    /// </summary>
    /// <summary>
    /// Not before the start: a file already on disk makes MonoTorrent hash every piece of it
    /// before connecting to anyone (a new stream sat at "Hashing", 0 peers). Once it is
    /// downloading that check is past, and no piece has had time to arrive yet.
    /// </summary>
    static async Task CreateSparseWhenDownloading(TorrentManager manager, ITorrentManagerFile file)
    {
        var clock = Stopwatch.StartNew();
        while (manager.State is TorrentState.Starting or TorrentState.Hashing or TorrentState.Metadata or TorrentState.Stopped)
        {
            if (clock.Elapsed > TimeSpan.FromMinutes(2)) return;
            await Task.Delay(50);
        }
        if (manager.State is TorrentState.Downloading) CreateSparse(file);
    }

    static void CreateSparse(ITorrentManagerFile file)
    {
        if (File.Exists(file.FullPath)) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(file.FullPath)!);
            using var stream = new FileStream(file.FullPath, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete);
            DeviceIoControl(stream.SafeFileHandle, 0x900C4 /* FSCTL_SET_SPARSE */, IntPtr.Zero, 0, IntPtr.Zero, 0, out _, IntPtr.Zero);
            stream.SetLength(file.Length);
        }
        catch (Exception e)
        {
            Log.Warn("stream", $"couldn't pre-create '{file.Path}': {e.Message}");
        }
    }

    public Progress Progress => TorrentEngine.ProgressOf(Manager, FileIndex);

    /// <summary>
    /// What a buffering stream waits on, for the log: the piece the player reads (counted within the
    /// file), its size, the next few pieces (# have, . missing), and how many peers could send it.
    /// </summary>
    public string Waiting()
    {
        if (window is null || Manager.Bitfield is not { } have || Manager.Torrent is not { } torrent) return "";
        var (at, holding, sending) = window.Waiting;
        var file = Manager.Files[FileIndex];
        if (at < file.StartPieceIndex || at > file.EndPieceIndex) return $"reading piece {at}, outside this file";
        var next = string.Concat(Enumerable.Range(at, Math.Min(8, file.EndPieceIndex - at + 1)).Select(i => have[i] ? '#' : '.'));
        return $"waiting on piece {at - file.StartPieceIndex}/{file.EndPieceIndex - file.StartPieceIndex} ({Format.Bytes(torrent.PieceLength)}) next {next} · {holding} peers have it, {sending} sending";
    }

    /// <summary>The watched file's pieces: finished, and all.</summary>
    public (int Have, int Total) Pieces()
    {
        var file = Manager.Files[FileIndex];
        var total = file.EndPieceIndex - file.StartPieceIndex + 1;
        if (Manager.Bitfield is not { } have) return (0, total);
        var count = 0;
        for (var i = file.StartPieceIndex; i <= file.EndPieceIndex; i++) if (have[i]) count++;
        return (count, total);
    }

    /// <summary>The watched file's finished pieces, merged into spans of 0–1 along the file.</summary>
    public List<(double From, double To)> Downloaded()
    {
        var file = Manager.Files[FileIndex];
        var spans = new List<(double, double)>();
        if (Manager.Bitfield is not { } have) return spans;
        double count = file.EndPieceIndex - file.StartPieceIndex + 1;
        int? start = null;
        for (var i = file.StartPieceIndex; i <= file.EndPieceIndex + 1; i++)
        {
            var done = i <= file.EndPieceIndex && have[i];
            if (done && start is null) start = i;
            else if (!done && start is int s)
            {
                spans.Add(((s - file.StartPieceIndex) / count, (i - file.StartPieceIndex) / count));
                start = null;
            }
        }
        return spans;
    }

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

    /// <summary>This video's own subtitle files from the torrent (Pick.SubtitlesFor) that have finished downloading.</summary>
    public IEnumerable<string> ReadySubtitles() => Pick.SubtitlesFor(Meta.Files, FileIndex)
        .Where(i => Manager.Files[i].Priority != Priority.DoNotDownload && Manager.Files[i].BitField.AllTrue)
        .Select(i => Manager.Files[i].DownloadCompleteFullPath)
        .Where(File.Exists);

    public async ValueTask DisposeAsync()
    {
        if (Current == this) Current = null;
        try { http.Dispose(); } catch { }
        if (!Borrowed) await TorrentEngine.Instance.Remove(Manager, deleteFiles);
        if (leftover is not null) try { File.Delete(leftover); } catch { }
    }
}

/// <summary>
/// MonoTorrent's streaming picker fetches where the player reads — and, alongside, the rest
/// of the torrent rarest-first, so watching quietly downloaded the whole film. This wraps it
/// with one more "ignore these pieces" bitfield covering the watched file outside a window:
/// where the player reads (plus the picker's own look-ahead) and the span the player's tick
/// sets. Everything is applied on the engine's thread, just before the picker requests.
/// </summary>
public sealed class WindowedRequester : IStreamingPieceRequester
{
    /// <summary>The look-ahead the inner requester keeps past the read position.</summary>
    const int ReadAhead = FastStreamingRequester.LowPriorityCount;

    readonly FastStreamingRequester inner = new();
    IPieceRequesterData? data;
    BitField? outside;
    ReadOnlyBitField[] alreadyHave = Array.Empty<ReadOnlyBitField>();

    volatile int fileIndex = -1;
    long wantFrom = -1, wantTo = -1;
    volatile bool open;
    (int File, int Reader, long From, long To, bool Open) applied = (-1, -1, -2, -2, false);

    /// <summary>A stream starts on this file: limit it to the window from now on (no limit until then).</summary>
    public void Watch(int file)
    {
        SetWindow(-1, -1);
        open = false;
        fileIndex = file;
    }

    ITorrentManagerFile? reading;

    /// <summary>
    /// A new stream reads from the file's first byte. MonoTorrent's LocalStream tells the picker
    /// only about seeks that change its position, and a new stream is already at 0, so the
    /// picker stayed on piece 0 of the torrent — another episode, in a season pack — and the
    /// window held none of the watched file: 0% buffered, the swarm idle. Put it at the file.
    /// </summary>
    public void ReadFrom(ITorrentManagerFile file)
    {
        reading = file;
        inner.SeekToPosition(file, 0); // nothing before Initialise, which does it then
    }

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
        alreadyHave = ignoringBitfields.ToArray();
        var all = new ReadOnlyBitField[ignoringBitfields.Length + 1];
        ignoringBitfields.CopyTo(all);
        all[^1] = outside;
        inner.Initialise(torrentData, enqueuer, all);
        if (reading is not null && (inner.HighPriorityPieceIndex < reading.StartPieceIndex || inner.HighPriorityPieceIndex > reading.EndPieceIndex))
            inner.SeekToPosition(reading, 0);
        applied = (-1, -1, -2, -2, false);
        Apply();
    }

    /// <summary>
    /// Pieces fetched at once when the whole file is wanted. Calibration knob: too few and fast
    /// swarms idle peers that hold none of them; too many and the order scatters again.
    /// </summary>
    const int AheadPieces = 64;
    long openAt;

    void Apply()
    {
        if (data is null || outside is null) return;
        var now = (fileIndex, inner.HighPriorityPieceIndex, Interlocked.Read(ref wantFrom), Interlocked.Read(ref wantTo), open);
        // Open, the window moves as pieces finish, so it is worked out again (four times a second at most).
        if (now == applied && !(open && Environment.TickCount64 - openAt > 250)) return;
        var (file, reader, from, to, isOpen) = now;
        if (file != applied.File)
            for (var i = 0; i < outside.Length; i++) outside[i] = false;
        applied = now;
        openAt = Environment.TickCount64;
        if (file < 0 || file >= data.Files.Count) return;
        var f = data.Files[file];
        if (isOpen)
        {
            // The whole file, in order: the next missing pieces from the playhead on, then from
            // the start — not rarest-first across the film, which scattered what was on disk
            // instead of growing it ahead of playback.
            var count = f.EndPieceIndex - f.StartPieceIndex + 1;
            var start = Math.Clamp(reader, f.StartPieceIndex, f.EndPieceIndex) - f.StartPieceIndex;
            var left = AheadPieces;
            for (var n = 0; n < count; n++)
            {
                var i = f.StartPieceIndex + (start + n) % count;
                var wanted = left > 0 && !Have(i);
                if (wanted) left--;
                outside[i] = !wanted && !(i >= reader && i <= reader + ReadAhead);
            }
            return;
        }
        int PieceAt(long offset) => data.ByteOffsetToPieceIndex(f.OffsetInTorrent + Math.Clamp(offset, 0, Math.Max(0, f.Length - 1)));
        int spanFrom = to > 0 ? PieceAt(from) : int.MaxValue, spanTo = to > 0 ? PieceAt(to) : int.MinValue;
        for (var i = f.StartPieceIndex; i <= f.EndPieceIndex; i++)
            outside[i] = !((i >= reader && i <= reader + ReadAhead) || (i >= spanFrom && i <= spanTo));
    }

    bool Have(int piece)
    {
        foreach (var ignored in alreadyHave)
            if (ignored[piece]) return true;
        return false;
    }

    public bool InEndgameMode => inner.InEndgameMode;

    public void AddRequests(ReadOnlySpan<(IRequester Peer, ReadOnlyBitField Available)> peers)
    {
        Apply();
        inner.AddRequests(peers);
        // For the log while buffering: who could send the piece the player waits on.
        var at = inner.HighPriorityPieceIndex;
        int holding = 0, sending = 0;
        foreach (var (peer, available) in peers)
        {
            if (at >= available.Length || !available[at]) continue;
            holding++;
            if (!peer.IsChoking) sending++;
        }
        (WaitingOn, PeersHolding, PeersSending) = (at, holding, sending);
    }

    /// <summary>The piece the player reads, how many connected peers have it, and how many of those let us download.</summary>
    public (int WaitingOn, int PeersHolding, int PeersSending) Waiting => (WaitingOn, PeersHolding, PeersSending);
    int WaitingOn = -1, PeersHolding, PeersSending;

    public void AddRequests(IRequester peer, ReadOnlyBitField available, ReadOnlySpan<ReadOnlyBitField> peers)
    {
        Apply();
        inner.AddRequests(peer, available, peers);
    }

    public void SeekToPosition(ITorrentManagerFile file, long position) => inner.SeekToPosition(file, position);
    public void ReadToPosition(ITorrentManagerFile file, long position) => inner.ReadToPosition(file, position);
    public bool ValidatePiece(IRequester peer, PieceSegment pieceSegment, out bool pieceComplete, HashSet<IRequester> peersInvolved) => inner.ValidatePiece(peer, pieceSegment, out pieceComplete, peersInvolved);
    /// <summary>
    /// Deliberately blind to the window. MonoTorrent decides whether we are interested in a
    /// peer when its bitfield or a Have arrives, and does not ask again when our window moves;
    /// a peer judged against an old window stayed "uninteresting" (so never unchoked us, so
    /// was never asked) even once it held the very piece playback waited on — the video stuck
    /// while other peers still showed speed. Interested = has any wanted piece we lack.
    /// </summary>
    public bool IsInteresting(IRequester peer, ReadOnlyBitField bitField)
    {
        if (data is null) return inner.IsInteresting(peer, bitField);
        foreach (var file in data.Files)
        {
            if (file.Priority == Priority.DoNotDownload) continue;
            for (var i = file.StartPieceIndex; i <= file.EndPieceIndex; i++)
            {
                if (bitField[i] && !Have(i)) return true;
            }
        }
        return false;
    }
    public void CancelRequests(IRequester peer, int startIndex, int endIndex) => inner.CancelRequests(peer, startIndex, endIndex);
    public void RequestRejected(IRequester peer, PieceSegment pieceRequest) => inner.RequestRejected(peer, pieceRequest);
    public int CurrentRequestCount() => inner.CurrentRequestCount();
}
