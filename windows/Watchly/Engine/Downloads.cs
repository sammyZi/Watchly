using System.Collections.ObjectModel;
using System.IO;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using MonoTorrent;
using MonoTorrent.Client;
using Watchly.Core;

namespace Watchly.Engine;

/// <summary>An unfinished transfer to pick up after the app is closed. See src/lib/downloadJob.ts.</summary>
public sealed record DownloadJob
{
    public string Uri { get; init; } = "";
    public List<int> FileIndices { get; init; } = new();
    /// <summary>Full list so skipped episodes still show after a restart.</summary>
    public List<int>? QueueIndices { get; init; }
    public List<int>? SavedIndices { get; init; }
    public int? Season { get; init; }
    /// <summary>Resolve the file on start: an SxxEyy tag, or "best" for the feature.</summary>
    public string? Auto { get; init; }
    /// <summary>Every file in the torrent — a torrent added by hand, BitTorrent-style.</summary>
    public bool All { get; init; }
    public string? Title { get; init; }
    public string? TitleName { get; init; }
    public string? Poster { get; init; }
    public string? Imdb { get; init; }
    public string? Kind { get; init; }
    public bool Paused { get; init; }

    [System.Text.Json.Serialization.JsonIgnore]
    public string Label => TitleName ?? Title ?? "Download";
}

public sealed partial class QueueFile : ObservableObject
{
    public required int Index { get; init; }
    public required string Name { get; init; }
    public required long Size { get; init; }

    /// <summary>Off | Queued | Downloading | Done</summary>
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(IsOff), nameof(IsDone), nameof(IsActive), nameof(Detail))]
    string state = "Queued";

    [ObservableProperty] [NotifyPropertyChangedFor(nameof(Detail))]
    double percent;

    public bool IsOff => State == "Off";
    public bool IsDone => State == "Done";
    public bool IsActive => State == "Downloading";

    public string Mark
    {
        get
        {
            var n = Search.ParseEpisode(Name)?.Episode ?? Search.ParseAbsolute(Name);
            return (n ?? Index + 1).ToString("00");
        }
    }

    public string Title
    {
        get
        {
            var at = Search.ParseEpisode(Name);
            if (at is null) return Search.ParseAbsolute(Name) is int n ? $"Episode {n}" : Path.GetFileNameWithoutExtension(Name);
            return at.Value.Season == 0 ? $"Special {at.Value.Episode}" : $"Episode {at.Value.Episode}";
        }
    }

    public string Detail => State switch
    {
        "Off" => $"Skipped · {Format.Bytes(Size)}",
        "Done" => $"Done · {Format.Bytes(Size)}",
        "Downloading" => $"{Math.Round(Percent)}% · {Format.Bytes(Size)}",
        _ => Format.Bytes(Size),
    };
}

public sealed partial class ActiveDownload : ObservableObject
{
    public DownloadJob Job { get; set; }
    internal TorrentManager? Manager { get; set; }
    internal TorrentMeta? Meta { get; set; }
    internal HashSet<int> Wanted { get; } = new();
    internal HashSet<int> SavedSet { get; } = new();
    internal string SaveDir { get; set; } = "";

    public ActiveDownload(DownloadJob job)
    {
        Job = job;
        headline = job.Label;
        foreach (var i in job.FileIndices) Wanted.Add(i);
        foreach (var i in job.SavedIndices ?? new()) SavedSet.Add(i);
    }

    public ObservableCollection<QueueFile> Queue { get; } = new();

    [ObservableProperty] string headline;
    [ObservableProperty] string stage = "Getting the file list…";
    [ObservableProperty] string counts = "";
    [ObservableProperty] string sizeLine = "";
    /// <summary>Why it stopped — the card shows it with Retry instead of the row vanishing.</summary>
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(HasError))] string? error;
    public bool HasError => Error is not null;
    [ObservableProperty] string etaLine = "";
    [ObservableProperty] string swarmLine = "";
    [ObservableProperty] double overall;
    [ObservableProperty] bool paused;
    [ObservableProperty] bool starting = true;
    [ObservableProperty] bool seeding;
    [ObservableProperty] bool queueOpen = true;
    [ObservableProperty] bool hasQueue;

    public string? Poster => Job.Poster;
}

/// <summary>
/// The download manager — what useDownloads.ts is on the phone, on top of
/// MonoTorrent. Jobs persist across launches, run side by side, can be paused,
/// and files can be skipped or put back mid-download.
/// </summary>
public sealed class Downloads
{
    public static Downloads Instance { get; } = new();

    const string Key = "torrent-active-download";

    public ObservableCollection<ActiveDownload> Live { get; } = new();

    /// <summary>Human-readable failures, for the shell to show.</summary>
    public event Action<string>? Failed;

    readonly DispatcherTimer tick;

    Downloads()
    {
        tick = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        tick.Tick += async (_, _) => await Tick();
        tick.Start();
    }

    static List<DownloadJob> ReadQueue() => Store.Load(Key, new List<DownloadJob>()).Where(j => j.Uri.Length > 0).ToList();

    static void WriteQueue(IEnumerable<DownloadJob> jobs)
    {
        var list = jobs.ToList();
        if (list.Count == 0) Store.Remove(Key);
        else Store.Save(Key, list);
    }

    void Persist() => WriteQueue(Live.Select(l => l.Job with
    {
        FileIndices = l.Wanted.Count > 0 || l.Meta is not null ? l.Wanted.ToList() : l.Job.FileIndices,
        SavedIndices = l.SavedSet.ToList(),
        Paused = l.Paused,
    }));

    /// <summary>Pick up whatever was running when the app last closed.</summary>
    public void Restore()
    {
        foreach (var job in ReadQueue()) Start(job, persist: false);
    }

    static string HashKey(string uri) => TitleText.HashOf(uri) ?? uri;

    public bool IsLive(string uri) => Live.Any(l => HashKey(l.Job.Uri) == HashKey(uri));

    public void Start(DownloadJob job, bool persist = true)
    {
        var settings = AppSettings.Load();
        var uri = settings.AddTrackers ? TitleText.WithTrackers(job.Uri) : job.Uri;
        if (IsLive(uri)) return;
        var row = new ActiveDownload(job with { Uri = uri }) { Paused = job.Paused };
        Live.Add(row);
        if (persist) Persist();
        _ = Begin(row, settings);
    }

    async Task Begin(ActiveDownload row, AppSettings settings)
    {
        try
        {
            var (torrent, meta) = await TorrentEngine.Instance.Inspect(row.Job.Uri, TimeSpan.FromSeconds(90));
            if (!Live.Contains(row)) return;
            row.Meta = meta;

            var job = row.Job;
            var indices = job.FileIndices.Where(i => i >= 0 && i < meta.Files.Count).ToList();
            if (job.All && indices.Count == 0)
            {
                indices = meta.Files.Select(f => f.Index).ToList();
            }
            else if (job.Season is not null)
            {
                var season = Pick.SeasonFiles(meta.Files, job.Season);
                if (season.Count > 0) indices = season;
            }
            else if (indices.Count == 0 && job.Auto is not null)
            {
                if (Pick.PickFile(meta.Files, job.Auto == "best" ? null : job.Auto) is int picked) indices = new() { picked };
            }
            if (indices.Count == 0 && Pick.PickFile(meta.Files) is int fallback) indices = new() { fallback };

            // Programs, scripts and shortcuts never reach the disk, however the download was started.
            var unsafeFiles = indices.Where(i => Pick.IsUnsafe(meta.Files[i].Path)).ToList();
            if (unsafeFiles.Count > 0)
            {
                indices = indices.Except(unsafeFiles).ToList();
                var names = string.Join(", ", unsafeFiles.Take(3).Select(i => meta.Files[i].Name));
                Log.Warn("download", $"'{meta.Name}': skipped {unsafeFiles.Count} runnable/unsafe file(s): {string.Join(", ", unsafeFiles.Select(i => meta.Files[i].Path))}");
                Failed?.Invoke($"Skipped {unsafeFiles.Count} file{(unsafeFiles.Count == 1 ? "" : "s")} that aren't video, audio, subtitles or images ({names}{(unsafeFiles.Count > 3 ? "…" : "")}) — programs, archives and disk images are how malware spreads.");
            }
            if (indices.Count == 0) throw new InvalidOperationException(unsafeFiles.Count > 0 ? "This torrent holds no videos — only programs, archives or other files that aren't downloaded." : "No video in this torrent");

            var queue = job.QueueIndices is { Count: > 0 } q ? q.Where(i => i < meta.Files.Count && !Pick.IsUnsafe(meta.Files[i].Path)).ToList() : indices;
            row.Job = job with { FileIndices = indices, QueueIndices = queue };
            row.Wanted.Clear();
            foreach (var i in indices) row.Wanted.Add(i);
            if (string.IsNullOrEmpty(job.TitleName))
            {
                var cleaned = TitleText.SearchableName(job.Title ?? meta.Name);
                row.Headline = cleaned.Length > 0 ? cleaned : meta.Name;
            }

            row.Queue.Clear();
            var ordered = queue.Select(i => meta.Files[i]).ToList();
            ordered.Sort(Pick.FileOrder);
            foreach (var f in ordered) row.Queue.Add(new QueueFile { Index = f.Index, Name = f.Name, Size = f.Size });
            row.HasQueue = row.Queue.Count > 1;

            var folder = job.All
                ? meta.Name
                : job.TitleName ?? (TitleText.SearchableName(job.Title ?? meta.Name) is { Length: > 0 } s ? s : meta.Name);
            row.SaveDir = Path.Combine(settings.DownloadDir, Paths.Safe(folder));

            if (StreamSession.Current is { } watching && watching.Manager.Torrent?.InfoHashes.V1OrV2.ToHex() == torrent.InfoHashes.V1OrV2.ToHex())
                watching.Borrowed = true;

            var manager = await TorrentEngine.Instance.Manager(torrent, row.SaveDir, row.Job.Uri);
            row.Manager = manager;
            for (var i = 0; i < manager.Files.Count; i++)
            {
                var watched = StreamSession.Current?.Manager == manager && StreamSession.Current.FileIndex == i;
                var wanted = (row.Wanted.Contains(i) || watched) && !Pick.IsUnsafe(meta.Files[i].Path);
                await manager.SetFilePriorityAsync(manager.Files[i], wanted ? Priority.Normal : Priority.DoNotDownload);
            }
            if (!row.Paused && manager.State is TorrentState.Stopped or TorrentState.Paused or TorrentState.Error)
                await manager.StartAsync();
            Persist();
            Update(row);
        }
        catch (Exception e)
        {
            if (!Live.Contains(row)) return;
            Log.Error("download", $"'{row.Headline}' failed", e);
            row.Error = e is UnauthorizedAccessException or IOException
                ? $"Can't write to {(row.SaveDir.Length > 0 ? row.SaveDir : settings.DownloadDir)} — {e.Message} Change the download folder and retry."
                : e.GetBaseException().Message;
            row.Starting = false;
            row.Stage = "Couldn't start";
            Persist();
        }
    }

    /// <summary>Try a failed download again, from scratch — picking up a download folder changed since.</summary>
    public async Task Retry(ActiveDownload row)
    {
        if (row.Manager is { } old)
        {
            row.Manager = null;
            if (StreamSession.Current?.Manager != old) await TorrentEngine.Instance.Remove(old, false);
        }
        row.Error = null;
        row.Paused = false;
        row.Starting = true;
        row.Stage = "Getting the file list…";
        row.SizeLine = "";
        await Begin(row, AppSettings.Load());
    }

    bool ticking;

    async Task Tick()
    {
        // A copy into the downloads folder can outlast a tick; never run two at once.
        if (ticking) return;
        ticking = true;
        try
        {
            foreach (var row in Live.ToList())
            {
                if (row.Manager is null) continue;
                await Collect(row);
                Update(row);
            }
        }
        finally
        {
            ticking = false;
        }
    }

    /// <summary>Files that finished since the last tick go to the saved list.</summary>
    async Task Collect(ActiveDownload row)
    {
        var m = row.Manager!;
        var fresh = false;
        foreach (var i in row.Wanted.ToList())
        {
            if (row.SavedSet.Contains(i) || i >= m.Files.Count) continue;
            var file = m.Files[i];
            if (!file.BitField.AllTrue || !File.Exists(file.DownloadCompleteFullPath)) continue;
            row.SavedSet.Add(i);
            fresh = true;
            var info = row.Meta!.Files[i];
            var path = file.DownloadCompleteFullPath;
            // A download that took over the torrent being watched writes into the
            // stream cache; the finished file still belongs in the downloads folder.
            if (!path.StartsWith(row.SaveDir, StringComparison.OrdinalIgnoreCase))
            {
                var target = Path.Combine(row.SaveDir, info.Path.Replace('/', Path.DirectorySeparatorChar));
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                    await Task.Run(() => File.Copy(path, target, true));
                    path = target;
                }
                catch (Exception e)
                {
                    Failed?.Invoke($"Could not copy {info.Name} to the downloads folder: {e.Message}");
                }
            }
            MarkFromInternet(path);
            if (info.IsVideo || row.Job.All)
            {
                SavedDownloads.Record(new SavedDownload
                {
                    Name = info.Name,
                    Size = info.Size,
                    At = Store.Now,
                    Path = path,
                    Poster = row.Job.Poster,
                    TitleName = row.Job.All ? null : row.Job.TitleName,
                });
            }
        }
        if (fresh) Persist();

        if (row.Wanted.Count == 0 || !row.Wanted.All(row.SavedSet.Contains) || row.Seeding) return;
        var skipped = (row.Job.QueueIndices ?? new()).Any(i => !row.Wanted.Contains(i) && !row.SavedSet.Contains(i));
        if (skipped)
        {
            if (!row.Paused) await Pause(row);
            return;
        }
        if (AppSettings.Load().SeedAfterDownload)
        {
            row.Seeding = true;
            return;
        }
        await Finish(row, deleteIncomplete: false);
    }

    void Update(ActiveDownload row)
    {
        var m = row.Manager;
        if (m is null) return;
        var meta = row.Meta!;
        var wantedFiles = row.Wanted.Where(i => i < m.Files.Count).ToList();
        double total = wantedFiles.Sum(i => (double)meta.Files[i].Size);
        double have = wantedFiles.Sum(i => meta.Files[i].Size * (m.Files[i].BitField.PercentComplete / 100.0));
        var progress = TorrentEngine.ProgressOf(m);
        var paused = row.Paused;
        var rate = paused ? 0 : progress.Speed;

        row.Overall = total > 0 ? Math.Min(100, have / total * 100) : 0;
        if (m.State == TorrentState.Error) row.Error = m.Error?.Exception?.GetBaseException().Message ?? "Disk or network failure";
        row.Starting = row.Error is null && !paused && (m.State is TorrentState.Metadata or TorrentState.Starting or TorrentState.Hashing || (have <= 0 && rate <= 0));
        row.Stage = row.Error is not null ? "Stopped with an error"
            : paused ? "Paused"
            : row.Seeding ? "Seeding"
            : m.State switch
            {
                TorrentState.Hashing => "Checking…",
                TorrentState.Metadata or TorrentState.Starting => "Starting…",
                TorrentState.Downloading => rate > 0 ? "Downloading" : "Finding peers…",
                TorrentState.Seeding => "Finishing…",
                _ => "Starting…",
            };
        row.SizeLine = total > 0 ? $"{Format.Bytes(have)} of {Format.Bytes(total)}" + (paused ? "" : $" · {Format.Speed(rate)}") : "";
        row.EtaLine = paused || row.Overall >= 100 ? "" : Format.Eta(total - have, rate);
        row.SwarmLine = $"{progress.Seeds} seeds · {progress.Peers} peers · ↑ {Format.Speed(progress.UploadSpeed)}";

        var done = 0;
        foreach (var file in row.Queue)
        {
            if (!row.Wanted.Contains(file.Index)) { file.State = "Off"; file.Percent = 0; continue; }
            var pct = m.Files[file.Index].BitField.PercentComplete;
            file.Percent = pct;
            if (row.SavedSet.Contains(file.Index)) { file.State = "Done"; done++; }
            else file.State = pct > 0 ? "Downloading" : "Queued";
        }
        row.Counts = row.HasQueue ? $"{done} of {row.Wanted.Count}" : "";
    }

    public async Task Pause(ActiveDownload row)
    {
        row.Paused = true;
        if (row.Manager is not null) try { await row.Manager.PauseAsync(); } catch { }
        Persist();
        Update(row);
    }

    public async Task Resume(ActiveDownload row)
    {
        row.Paused = false;
        if (row.Manager is not null) try { await row.Manager.StartAsync(); } catch (Exception e) { Failed?.Invoke(e.Message); }
        Persist();
        Update(row);
    }

    /// <summary>Stop and forget. Finished files stay; partial ones are deleted.</summary>
    public Task Stop(ActiveDownload row) => Finish(row, deleteIncomplete: true);

    async Task Finish(ActiveDownload row, bool deleteIncomplete)
    {
        Live.Remove(row);
        Persist();
        var m = row.Manager;
        if (m is null) return;
        var watching = StreamSession.Current is { } s && s.Manager == m ? s : null;
        // The file being watched is still open in the player; the stream deletes it when done.
        var partials = deleteIncomplete
            ? m.Files.Where((f, i) => !row.SavedSet.Contains(i) && i != watching?.FileIndex).Select(f => f.DownloadIncompleteFullPath).ToList()
            : new List<string>();
        if (watching is null)
        {
            await TorrentEngine.Instance.Remove(m, false);
        }
        else
        {
            // Nobody else would ever remove this torrent: the download skips it because it is
            // being watched, and the stream skips it because it was borrowed. Hand it back, with
            // only the watched file (and subtitles) still wanted.
            for (var i = 0; i < m.Files.Count; i++)
                if (i != watching.FileIndex && !watching.Meta.Files[i].IsSubtitle)
                    try { await m.SetFilePriorityAsync(m.Files[i], Priority.DoNotDownload); } catch { }
            watching.TakeBack(deleteIncomplete && !row.SavedSet.Contains(watching.FileIndex));
        }
        foreach (var path in partials)
        {
            try { File.Delete(path); } catch { }
        }
        TidyEmpty(row.SaveDir);
    }

    static void TidyEmpty(string dir)
    {
        try
        {
            if (!Directory.Exists(dir)) return;
            foreach (var sub in Directory.GetDirectories(dir)) TidyEmpty(sub);
            if (!Directory.EnumerateFileSystemEntries(dir).Any()) Directory.Delete(dir);
        }
        catch { }
    }

    public async Task Skip(ActiveDownload row, int index)
    {
        row.Wanted.Remove(index);
        if (row.Manager is not null) await row.Manager.SetFilePriorityAsync(row.Manager.Files[index], Priority.DoNotDownload);
        Persist();
        Update(row);
    }

    /// <summary>
    /// Tag a finished file as downloaded from the internet (the Mark of the Web a browser
    /// adds), so Windows SmartScreen and Office warn before anything in it is opened or run.
    /// </summary>
    static void MarkFromInternet(string path)
    {
        try { File.WriteAllText(path + ":Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\n"); } catch { }
    }

    public async Task Include(ActiveDownload row, int index)
    {
        if (row.Meta is { } meta && index < meta.Files.Count && Pick.IsUnsafe(meta.Files[index].Path)) return;
        row.Wanted.Add(index);
        row.Seeding = false;
        if (row.Manager is not null)
        {
            await row.Manager.SetFilePriorityAsync(row.Manager.Files[index], Priority.Normal);
            if (row.Paused) await Resume(row);
        }
        Persist();
        Update(row);
    }

    /// <summary>The finished file for a queue row, for playing straight off disk.</summary>
    public static string? FilePath(ActiveDownload row, int index)
    {
        if (row.Manager is null || row.Meta is null || !row.SavedSet.Contains(index)) return null;
        var copied = Path.Combine(row.SaveDir, row.Meta.Files[index].Path.Replace('/', Path.DirectorySeparatorChar));
        return File.Exists(copied) ? copied : row.Manager.Files[index].DownloadCompleteFullPath;
    }

    public async Task StopAll()
    {
        tick.Stop();
        foreach (var row in Live.Where(r => r.Manager is not null))
        {
            try { await row.Manager!.StopAsync(TimeSpan.FromSeconds(2)); } catch { }
        }
    }
}
