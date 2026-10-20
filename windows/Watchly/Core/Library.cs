using System.IO;
using System.Text.RegularExpressions;

namespace Watchly.Core;

public sealed record SavedSub(string Path, string Label);

/// <summary>One torrent in the library. See src/lib/history.ts.</summary>
public sealed record HistoryItem
{
    /// <summary>Magnet / url / local .torrent path — the identity.</summary>
    public string Uri { get; init; } = "";
    public string Title { get; init; } = "";
    /// <summary>Last opened, ms.</summary>
    public long At { get; init; }
    public string? TorrentName { get; init; }
    public List<TorrentFileInfo>? Files { get; init; }
    public int? LastFileIndex { get; init; }
    /// <summary>Resume point and duration per file index, both in seconds.</summary>
    public Dictionary<int, double>? Positions { get; init; }
    public Dictionary<int, double>? Durations { get; init; }
    public Dictionary<int, List<SavedSub>>? Subtitles { get; init; }
    public string? Imdb { get; init; }
    public string? Kind { get; init; }
    public string? Poster { get; init; }
    public string? TitleName { get; init; }

    public double? PositionOf(int index) => Positions is not null && Positions.TryGetValue(index, out var v) ? v : null;
    public double? DurationOf(int index) => Durations is not null && Durations.TryGetValue(index, out var v) ? v : null;

    /// <summary>How far into a file the stored resume point is, 0-1.</summary>
    public double ResumeRatio(int index)
    {
        var position = PositionOf(index);
        var duration = DurationOf(index);
        if (position is not > 0 || duration is not > 0) return 0;
        return Math.Clamp(position.Value / duration.Value, 0, 0.99);
    }

    [System.Text.Json.Serialization.JsonIgnore]
    public string DisplayName => TitleName ?? TorrentName ?? Title;

    /// <summary>
    /// What this entry is *of*: the title (IMDb id, else the name), so two releases of one film,
    /// or a show's episodes from several torrents, are one title. Same rule as the Kotlin apps.
    /// </summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public string TitleKey => Imdb ?? TitleName?.Split(" · ")[0].ToLowerInvariant() ?? Uri;

    /// <summary>0-1 through the last file played.</summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public double Ratio => LastFileIndex is int i && DurationOf(i) is > 0 and var d && PositionOf(i) is double p ? p / d : 0;
}

/// <summary>Watch history: every mutation is read-modify-write under one lock.</summary>
public static class History
{
    const string Key = "torrent-history";
    const int Max = 50;
    static readonly object Gate = new();

    public static event Action<List<HistoryItem>>? Changed;

    public static List<HistoryItem> Load() => Store.Load(Key, new List<HistoryItem>());

    static List<HistoryItem> Write(List<HistoryItem> items)
    {
        var capped = items.Take(Max).ToList();
        Store.Save(Key, capped);
        Changed?.Invoke(capped);
        return capped;
    }

    public static bool IsRemote(string? url) => url is not null && Regex.IsMatch(url, "^https?://", RegexOptions.IgnoreCase);

    public static string? RemoteArt(params string?[] urls) => urls.FirstOrDefault(IsRemote);

    public static string? PosterOf(HistoryItem item, IEnumerable<SavedTitle>? saved = null, IEnumerable<HistoryItem>? kin = null) =>
        RemoteArt(
            item.Poster,
            saved?.FirstOrDefault(s => s.Imdb == item.Imdb)?.Poster,
            kin?.FirstOrDefault(h => h.Uri != item.Uri && h.Imdb is not null && h.Imdb == item.Imdb)?.Poster);

    /// <summary>Insert or move-to-top, merging over whatever was already stored.</summary>
    public static List<HistoryItem> Put(HistoryItem item)
    {
        lock (Gate)
        {
            var rest = Load();
            var prev = rest.FirstOrDefault(x => x.Uri == item.Uri);
            var kin = rest.FirstOrDefault(x =>
                x.Uri != item.Uri && RemoteArt(x.Poster) is not null &&
                ((item.Imdb is not null && x.Imdb == item.Imdb) ||
                 (prev?.Imdb is not null && x.Imdb == prev.Imdb) ||
                 (item.TitleName is not null && x.TitleName == item.TitleName)));
            var merged = item with
            {
                TorrentName = item.TorrentName ?? prev?.TorrentName,
                Files = item.Files ?? prev?.Files,
                LastFileIndex = item.LastFileIndex ?? prev?.LastFileIndex,
                Positions = item.Positions ?? prev?.Positions,
                Durations = item.Durations ?? prev?.Durations,
                Subtitles = item.Subtitles ?? prev?.Subtitles,
                Poster = RemoteArt(item.Poster, prev?.Poster, kin?.Poster),
                Imdb = item.Imdb ?? prev?.Imdb ?? kin?.Imdb,
                Kind = item.Kind ?? prev?.Kind ?? kin?.Kind,
                TitleName = item.TitleName ?? prev?.TitleName ?? kin?.TitleName,
            };
            return Write(rest.Where(x => x.Uri != item.Uri).Prepend(merged).ToList());
        }
    }

    public static List<HistoryItem> Patch(string uri, Func<HistoryItem, HistoryItem> change)
    {
        lock (Gate)
        {
            return Write(Load().Select(x => x.Uri == uri ? change(x) : x).ToList());
        }
    }

    public static List<HistoryItem> SetPosition(string uri, int index, double seconds) =>
        Patch(uri, x => x with
        {
            LastFileIndex = index,
            Positions = new Dictionary<int, double>(x.Positions ?? new()) { [index] = seconds },
        });

    public static List<HistoryItem> SetDuration(string uri, int index, double seconds) =>
        Patch(uri, x => x with { Durations = new Dictionary<int, double>(x.Durations ?? new()) { [index] = seconds } });

    public static List<HistoryItem> SetFileSubs(string uri, int index, List<SavedSub> subs) =>
        Patch(uri, x => x with { Subtitles = new Dictionary<int, List<SavedSub>>(x.Subtitles ?? new()) { [index] = subs } });

    static void WipeSubs(IEnumerable<HistoryItem> items)
    {
        foreach (var path in items.SelectMany(i => i.Subtitles?.Values.SelectMany(s => s) ?? Enumerable.Empty<SavedSub>()).Select(s => s.Path))
        {
            try
            {
                // Only ever our own downloads — never a subtitle that came inside a torrent.
                if (path.StartsWith(Paths.Subtitles, StringComparison.OrdinalIgnoreCase)) File.Delete(path);
            }
            catch { }
        }
    }

    public static List<HistoryItem> Drop(string uri)
    {
        lock (Gate)
        {
            var items = Load();
            WipeSubs(items.Where(x => x.Uri == uri));
            return Write(items.Where(x => x.Uri != uri).ToList());
        }
    }

    /// <summary>
    /// One entry per title, newest first: of a title's entries the newest that was started, so a
    /// second release opened and closed at once does not hide where the first was left.
    /// </summary>
    public static List<HistoryItem> LatestPerTitle(IEnumerable<HistoryItem> items) =>
        items.OrderByDescending(x => x.At).GroupBy(x => x.TitleKey)
            .Select(g => g.FirstOrDefault(x => x.LastFileIndex is int i && x.PositionOf(i) is > 0) ?? g.First())
            .OrderByDescending(x => x.At).ToList();

    /// <summary>
    /// Where to start <paramref name="fileName"/> of <paramref name="target"/> when this release was
    /// never played: the spot another release of the title (for a series, the same episode) was
    /// left, in seconds, with its duration. By time — releases differ in bytes. Null when there is
    /// none, or it was watched through.
    /// </summary>
    public static (double At, double? Duration)? CarryOver(IEnumerable<HistoryItem> items, HistoryItem target, string fileName)
    {
        var ep = Search.ParseEpisode(fileName);
        var donor = items.Where(x => x.Uri != target.Uri && x.TitleKey == target.TitleKey).OrderByDescending(x => x.At)
            .FirstOrDefault(d => d.LastFileIndex is int i && d.PositionOf(i) is > 0 &&
                                 Search.ParseEpisode(d.Files?.FirstOrDefault(f => f.Index == i)?.Name) == ep);
        if (donor?.LastFileIndex is not int at || donor.PositionOf(at) is not double position) return null;
        var duration = donor.DurationOf(at);
        if (duration is > 0 && position >= duration.Value * 0.95) return null;
        return (position, duration);
    }

    /// <summary>Every entry of one title — each release it was played from.</summary>
    public static List<HistoryItem> DropTitle(string key)
    {
        lock (Gate)
        {
            var items = Load();
            WipeSubs(items.Where(x => x.TitleKey == key));
            return Write(items.Where(x => x.TitleKey != key).ToList());
        }
    }

    public static List<HistoryItem> Clear()
    {
        lock (Gate)
        {
            WipeSubs(Load());
            return Write(new());
        }
    }
}

public sealed record SavedTitle
{
    public string Imdb { get; init; } = "";
    public string Kind { get; init; } = "movie";
    public string Title { get; init; } = "";
    public string? Poster { get; init; }
    public string? Year { get; init; }
    public string? Rating { get; init; }
    /// <summary>favorite | later</summary>
    public string List { get; init; } = "later";
    public long At { get; init; }

    public Title AsTitle() => new() { Imdb = Imdb, Kind = Kind, Name = Title, Poster = Poster, Year = Year, Rating = Rating };
}

/// <summary>Favourites and watch-later, both lists in one array. See src/lib/saved.ts.</summary>
public static class Saved
{
    const string Key = "saved";

    public static event Action<List<SavedTitle>>? Changed;

    public static List<SavedTitle> Load() => Store.Load(Key, new List<SavedTitle>()).OrderByDescending(s => s.At).ToList();

    public static bool OnList(IEnumerable<SavedTitle> saved, string imdb, string list) => saved.Any(s => s.Imdb == imdb && s.List == list);

    public static List<SavedTitle> InList(IEnumerable<SavedTitle> saved, string list) => saved.Where(s => s.List == list).ToList();

    public static List<SavedTitle> Toggle(Title title, string list)
    {
        var all = Load();
        var without = all.Where(s => !(s.Imdb == title.Imdb && s.List == list)).ToList();
        var next = without.Count == all.Count
            ? without.Prepend(new SavedTitle
            {
                Imdb = title.Imdb, Kind = title.Kind, Title = title.Name, Poster = title.Poster,
                Year = title.Year, Rating = title.Rating, List = list, At = Store.Now,
            }).ToList()
            : without;
        Store.Save(Key, next);
        Changed?.Invoke(next);
        return next;
    }
}

public sealed record AppSettings
{
    public bool AddTrackers { get; init; } = true;
    public bool RemoveFilesAfterStop { get; init; } = true;
    public List<string> Genres { get; init; } = new();
    /// <summary>system | light | dark</summary>
    public string ThemeMode { get; init; } = "system";
    /// <summary>Where finished downloads are kept.</summary>
    public string? DownloadFolder { get; init; }
    /// <summary>0 = unlimited, in KB/s.</summary>
    public int MaxDownloadKbps { get; init; }
    public int MaxUploadKbps { get; init; }
    /// <summary>Keep uploading a finished download until it is removed.</summary>
    public bool SeedAfterDownload { get; init; }
    public int Volume { get; init; } = 100;
    /// <summary>While streaming, fetch the whole file instead of only the next five minutes.</summary>
    public bool StreamWholeFile { get; init; }
    /// <summary>The player's picture mode ("Best fit", "Stretch", "Crop", …) and speed, kept for the next video.</summary>
    public string PlayerScale { get; init; } = "Best fit";
    public double PlaybackRate { get; init; } = 1;
    /// <summary>Jump past an episode's intro by itself, rather than offer the button.</summary>
    public bool AutoSkipIntro { get; init; }
    const string Key = "settings";

    public static AppSettings Load()
    {
        var s = Store.Load(Key, new AppSettings());
        if (s.ThemeMode is not ("system" or "light" or "dark")) s = s with { ThemeMode = "system" };
        return s with { Genres = s.Genres ?? new() };
    }

    public void Save() => Store.Save(Key, this);

    [System.Text.Json.Serialization.JsonIgnore]
    public string DownloadDir => string.IsNullOrWhiteSpace(DownloadFolder) ? Paths.DefaultDownloads : DownloadFolder!;
}

/// <summary>Episode id -> the torrent uri it was opened with. See src/lib/watched.ts.</summary>
public static class EpisodeLinks
{
    const string Key = "episodes";
    /// <summary>Anything past this counts as watched.</summary>
    public const double Watched = 92;

    public static Dictionary<string, string> Load() => Store.Load(Key, new Dictionary<string, string>());

    public static Dictionary<string, string> Link(string id, string uri)
    {
        var links = Load();
        links[id] = uri;
        Store.Save(Key, links);
        return links;
    }
}

/// <summary>What has been searched for, newest first.</summary>
public static class Searches
{
    const string Key = "searches";
    const int Max = 8;

    public static List<string> Load() => Store.Load(Key, new List<string>()).Take(Max).ToList();

    public static List<string> Remember(string query)
    {
        var q = query.Trim();
        if (q.Length == 0) return Load();
        var next = Load().Where(old => !old.Equals(q, StringComparison.OrdinalIgnoreCase)).Prepend(q).Take(Max).ToList();
        Store.Save(Key, next);
        return next;
    }

    public static List<string> Clear()
    {
        Store.Remove(Key);
        return new();
    }
}

/// <summary>A finished download on disk. See DownloadsScreen's `torrent-downloads`.</summary>
public sealed record SavedDownload
{
    public string Name { get; init; } = "";
    public long Size { get; init; }
    public long At { get; init; }
    public string Path { get; init; } = "";
    public string? Poster { get; init; }
    public string? TitleName { get; init; }
}

public static class SavedDownloads
{
    const string Key = "torrent-downloads";
    static readonly object Gate = new();

    public static event Action? Changed;

    /// <summary>Only rows whose file is still on disk — deleting it in Explorer removes it here too.</summary>
    public static List<SavedDownload> Load() => Store.Load(Key, new List<SavedDownload>()).Where(d => File.Exists(d.Path)).ToList();

    public static void Record(SavedDownload entry)
    {
        lock (Gate)
        {
            var rest = Load().Where(d => d.Path != entry.Path);
            Store.Save(Key, rest.Prepend(entry).ToList());
        }
        Changed?.Invoke();
    }

    public static void Remove(IEnumerable<string> paths)
    {
        var drop = paths.ToHashSet(StringComparer.OrdinalIgnoreCase);
        lock (Gate)
        {
            foreach (var path in drop)
            {
                try
                {
                    File.Delete(path);
                    var dir = System.IO.Path.GetDirectoryName(path);
                    // Tidy the show's folder once its last episode is gone.
                    while (dir is not null && Directory.Exists(dir) && !Directory.EnumerateFileSystemEntries(dir).Any()
                           && dir.StartsWith(AppSettings.Load().DownloadDir, StringComparison.OrdinalIgnoreCase)
                           && !dir.TrimEnd('\\').Equals(AppSettings.Load().DownloadDir.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                    {
                        Directory.Delete(dir);
                        dir = System.IO.Path.GetDirectoryName(dir);
                    }
                }
                catch { }
            }
            Store.Save(Key, Store.Load(Key, new List<SavedDownload>()).Where(d => !drop.Contains(d.Path)).ToList());
        }
        Changed?.Invoke();
    }
}

public static class Paths
{
    public static readonly string LocalRoot = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Watchly");

    /// <summary>MonoTorrent's own cache: metadata, fast resume, DHT nodes.</summary>
    public static readonly string EngineCache = System.IO.Path.Combine(LocalRoot, "engine");

    /// <summary>Pieces of whatever is being streamed. Cleared on stop unless the setting says keep.</summary>
    public static readonly string StreamCache = System.IO.Path.Combine(LocalRoot, "stream");

    /// <summary>Subtitles fetched online (OpenSubtitles, SubDL).</summary>
    public static readonly string Subtitles = System.IO.Path.Combine(LocalRoot, "subtitles");

    public static readonly string DefaultDownloads = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Watchly");

    /// <summary>A name Windows will accept as a folder or file name.</summary>
    public static string Safe(string name)
    {
        var bad = System.IO.Path.GetInvalidFileNameChars();
        var s = new string(name.Select(c => bad.Contains(c) ? ' ' : c).ToArray()).Trim().TrimEnd('.');
        return s.Length == 0 ? "Download" : s;
    }
}
