using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>One file inside a torrent — what the engine reports and history stores.</summary>
public sealed record TorrentFileInfo
{
    public int Index { get; init; }
    /// <summary>Full path inside the torrent, folders included.</summary>
    public string Path { get; init; } = "";
    public string Name { get; init; } = "";
    public long Size { get; init; }
    public bool IsVideo { get; init; }
    public bool IsSubtitle { get; init; }
}

public sealed record TorrentMeta(string Name, List<TorrentFileInfo> Files);

/// <summary>src/lib/pick.ts — which file a viewer meant to open.</summary>
public static class Pick
{
    static readonly Regex Junk = new(@"\b(sample|trailer|extras?|featurette|behind[. _-]?the[. _-]?scenes)\b", RegexOptions.IgnoreCase);

    static readonly HashSet<string> VideoExt = new(StringComparer.OrdinalIgnoreCase)
        { ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".wmv", ".webm", ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".ogv", ".3gp", ".vob", ".divx", ".rmvb" };

    static readonly HashSet<string> SubExt = new(StringComparer.OrdinalIgnoreCase)
        { ".srt", ".ass", ".ssa", ".vtt", ".sub", ".idx", ".smi" };

    public static bool IsVideoName(string name) => VideoExt.Contains(System.IO.Path.GetExtension(name));
    public static bool IsSubtitleName(string name) => SubExt.Contains(System.IO.Path.GetExtension(name));

    /// <summary>
    /// Everything that may reach the disk from a torrent: video, audio, subtitles, cover art,
    /// release notes. An allow list, not a block list — torrents are a common way malware
    /// travels, and not only as programs ("codec.exe", "Play.lnk"): archives and disk images
    /// carry them past a filter (.zip, .rar, .iso, .vhd), web pages and macro documents run
    /// code when opened (.html, .svg, .docm). Nothing a viewer wants is any of those.
    /// </summary>
    static readonly HashSet<string> AllowedExt = new(StringComparer.OrdinalIgnoreCase)
    {
        // video
        ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".wmv", ".webm", ".ts", ".m2ts", ".mts", ".mpg", ".mpeg", ".flv", ".ogv", ".3gp", ".vob", ".divx", ".rmvb",
        // audio
        ".mka", ".mp3", ".flac", ".m4a", ".aac", ".ac3", ".eac3", ".dts", ".ogg", ".opus", ".wav",
        // subtitles
        ".srt", ".ass", ".ssa", ".vtt", ".sub", ".idx", ".smi", ".sup",
        // art and notes
        ".jpg", ".jpeg", ".png", ".webp", ".nfo", ".txt",
    };

    /// <summary>
    /// A file that must not reach the disk: any type outside <see cref="AllowedExt"/>, or a path
    /// that climbs out of the download folder ("../../Startup/x") or names a drive.
    /// </summary>
    public static bool IsUnsafe(string path)
    {
        var p = path.Replace('\\', '/');
        if (p.StartsWith('/') || p.Contains(':') || p.Split('/').Any(part => part.Trim() == "..")) return true;
        // Windows drops trailing dots and spaces, so "setup.exe. " is still an .exe.
        return !AllowedExt.Contains(System.IO.Path.GetExtension(p.TrimEnd(' ', '.')));
    }

    static TorrentFileInfo Biggest(IEnumerable<TorrentFileInfo> files) => files.Aggregate((a, b) => b.Size > a.Size ? b : a);

    /// <summary>
    /// The episode matching <paramref name="tag"/> ("S02E05", or "S02E05#29" with the
    /// number counted through the show — <see cref="Search.PickTag"/>), else the
    /// biggest video. Null when there is no video, or a pack holds no such episode.
    /// </summary>
    public static int? PickFile(IEnumerable<TorrentFileInfo> files, string? tag = null)
    {
        var videos = files.Where(f => f.IsVideo && !Junk.IsMatch(f.Name)).ToList();
        if (videos.Count == 0) return null;
        if (!string.IsNullOrEmpty(tag))
        {
            var m = Regex.Match(tag, @"^s(\d+)e(\d+)(?:#(\d+))?$", RegexOptions.IgnoreCase);
            var match = new List<TorrentFileInfo>();
            if (m.Success)
            {
                var season = int.Parse(m.Groups[1].Value);
                var episode = int.Parse(m.Groups[2].Value);
                int? absolute = m.Groups[3].Success ? int.Parse(m.Groups[3].Value) : null;
                match = videos.Where(f => Search.NamesEpisode(f.Name, season, episode, absolute, ParentDir(f.Path, f.Name)) == true).ToList();
                // A pack of one season numbered from 1 with no season label anywhere: "- 05" is the fifth.
                if (match.Count == 0 && videos.All(f => Search.SeasonsIn(f.Path).Count == 0))
                    match = videos.Where(f => Search.ParseAbsolute(f.Name) == episode).ToList();
            }
            if (match.Count > 0) return Biggest(match).Index;
            if (videos.Count > 1) return null;
        }
        return Biggest(videos).Index;
    }

    /// <summary>Where a file sits in the show: SxxEyy, else its running number (in its folder's season, if labelled).</summary>
    static (int Season, int Episode)? EpisodeKey(TorrentFileInfo f)
    {
        if ((Search.ParseEpisode(f.Name) ?? Search.ParseEpisode(f.Path)) is { } at) return at;
        return Search.ParseAbsolute(f.Name) is int n ? (Search.SeasonsIn(f.Path).FirstOrDefault(), n) : null;
    }

    /// <summary>The episode that follows the one playing, inside the same torrent.</summary>
    public static TorrentFileInfo? NextEpisode(IEnumerable<TorrentFileInfo> files, int index)
    {
        var list = files.ToList();
        var playing = list.FirstOrDefault(f => f.Index == index);
        if (playing is null || EpisodeKey(playing) is null) return null;
        var ordered = list
            .Where(f => f.IsVideo && !Junk.IsMatch(f.Name))
            .Select(f => (File: f, At: EpisodeKey(f)))
            .Where(e => e.At is not null)
            .OrderBy(e => e.At!.Value.Season).ThenBy(e => e.At!.Value.Episode)
            .ToList();
        var at = ordered.FindIndex(e => e.File.Index == index);
        return at < 0 || at + 1 >= ordered.Count ? null : ordered[at + 1].File;
    }

    public static string ParentDir(string? path, string? name)
    {
        var p = (string.IsNullOrEmpty(path) ? name ?? "" : path).Replace('\\', '/');
        var i = p.LastIndexOf('/');
        return i > 0 ? p[..i] : "";
    }

    public static string FolderName(string dir) => dir.Split('/', StringSplitOptions.RemoveEmptyEntries).LastOrDefault() ?? dir;

    public static int FileOrder(TorrentFileInfo a, TorrentFileInfo b)
    {
        var ea = EpisodeKey(a);
        var eb = EpisodeKey(b);
        if (ea is not null && eb is not null)
        {
            var byEp = ea.Value.Season != eb.Value.Season ? ea.Value.Season.CompareTo(eb.Value.Season) : ea.Value.Episode.CompareTo(eb.Value.Episode);
            if (byEp != 0) return byEp;
        }
        var n = Format.Natural(a.Name, b.Name);
        return n != 0 ? n : Format.Natural(a.Path, b.Path);
    }

    static string CommonPrefix(List<string> dirs)
    {
        if (dirs.Count < 2) return "";
        var parts = dirs[0].Split('/');
        var n = parts.Length;
        foreach (var dir in dirs.Skip(1))
        {
            var p = dir.Split('/');
            var i = 0;
            while (i < n && i < p.Length && parts[i] == p[i]) i++;
            n = i;
        }
        return n > 0 ? string.Join('/', parts.Take(n)) : "";
    }

    static string DisplayFolder(string dir, string prefix)
    {
        if (dir.Length == 0) return "";
        var rest = prefix.Length > 0 && (dir == prefix || dir.StartsWith(prefix + "/"))
            ? dir[prefix.Length..].TrimStart('/')
            : dir;
        return rest.Length > 0 ? rest : FolderName(dir);
    }

    public sealed record Group(string Folder, string Dir, List<TorrentFileInfo> Files);

    /// <summary>Group files by their full parent path, folders and episodes in natural order.</summary>
    public static List<Group> GroupByFolder(IEnumerable<TorrentFileInfo> files)
    {
        var groups = new Dictionary<string, List<TorrentFileInfo>>();
        var order = new List<string>();
        foreach (var file in files)
        {
            var dir = ParentDir(file.Path, file.Name);
            if (!groups.TryGetValue(dir, out var list))
            {
                groups[dir] = list = new();
                order.Add(dir);
            }
            list.Add(file);
        }
        List<TorrentFileInfo> Sorted(List<TorrentFileInfo> l) { var c = l.ToList(); c.Sort(FileOrder); return c; }
        if (groups.Count <= 1)
        {
            var dir = order.FirstOrDefault() ?? "";
            return new() { new Group("", dir, Sorted(groups.GetValueOrDefault(dir) ?? files.ToList())) };
        }
        var prefix = CommonPrefix(order.Where(d => d.Length > 0).ToList());
        return order.OrderBy(d => d, Comparer<string>.Create(Format.Natural))
            .Select(dir => new Group(DisplayFolder(dir, prefix), dir, Sorted(groups[dir])))
            .ToList();
    }

    /// <summary>Video files that belong to one season — or every video when the pack does not label seasons.</summary>
    public static List<int> SeasonFiles(IEnumerable<TorrentFileInfo> files, int? season = null)
    {
        var videos = files.Where(f => f.IsVideo && !Junk.IsMatch(f.Name)).ToList();
        if (videos.Count == 0) return new();
        if (season is null) return videos.Select(f => f.Index).ToList();
        var hit = videos.Where(f =>
        {
            var at = Search.ParseEpisode(f.Name) ?? Search.ParseEpisode(f.Path);
            if (at?.Season == season) return true;
            return Search.SeasonsIn($"{f.Path} {f.Name}").Contains(season.Value);
        }).ToList();
        var chosen = (hit.Count > 0 ? hit : videos).ToList();
        chosen.Sort(FileOrder);
        return chosen.Select(f => f.Index).ToList();
    }
}
