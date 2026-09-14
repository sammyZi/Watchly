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

    static Regex? EpisodePattern(string tag)
    {
        var m = Regex.Match(tag, @"^s(\d+)e(\d+)$", RegexOptions.IgnoreCase);
        if (!m.Success) return null;
        var season = int.Parse(m.Groups[1].Value);
        var episode = int.Parse(m.Groups[2].Value);
        return new Regex($@"(?:s0*{season}[ ._-]*e0*{episode}|\b0*{season}x0*{episode})\b", RegexOptions.IgnoreCase);
    }

    static TorrentFileInfo Biggest(IEnumerable<TorrentFileInfo> files) => files.Aggregate((a, b) => b.Size > a.Size ? b : a);

    /// <summary>The episode matching <paramref name="tag"/>, else the biggest video. Null when there is no video.</summary>
    public static int? PickFile(IEnumerable<TorrentFileInfo> files, string? tag = null)
    {
        var videos = files.Where(f => f.IsVideo && !Junk.IsMatch(f.Name)).ToList();
        if (videos.Count == 0) return null;
        if (!string.IsNullOrEmpty(tag))
        {
            var wanted = EpisodePattern(tag);
            var match = wanted is null ? new() : videos.Where(f => wanted.IsMatch(f.Name)).ToList();
            if (match.Count > 0) return Biggest(match).Index;
            if (videos.Count > 1) return null;
        }
        return Biggest(videos).Index;
    }

    /// <summary>The episode that follows the one playing, inside the same torrent.</summary>
    public static TorrentFileInfo? NextEpisode(IEnumerable<TorrentFileInfo> files, int index)
    {
        var list = files.ToList();
        var playing = list.FirstOrDefault(f => f.Index == index);
        if (playing is null || Search.ParseEpisode(playing.Name) is null) return null;
        var ordered = list
            .Where(f => f.IsVideo && !Junk.IsMatch(f.Name))
            .Select(f => (File: f, At: Search.ParseEpisode(f.Name)))
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
        var ea = Search.ParseEpisode(a.Name) ?? Search.ParseEpisode(a.Path);
        var eb = Search.ParseEpisode(b.Name) ?? Search.ParseEpisode(b.Path);
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
