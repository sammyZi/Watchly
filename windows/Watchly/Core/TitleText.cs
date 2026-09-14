using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>src/lib/title.ts and src/lib/magnet.ts.</summary>
public static class TitleText
{
    /// <summary>Human-readable label for a magnet link, torrent URL, or local .torrent path.</summary>
    public static string TitleOf(string uri)
    {
        var dn = Regex.Match(uri, "[?&]dn=([^&]*)");
        if (dn.Success) return SafeDecode(dn.Groups[1].Value.Replace('+', ' '));

        var hash = Regex.Match(uri, "btih:([a-z0-9]+)", RegexOptions.IgnoreCase);
        if (hash.Success) return hash.Groups[1].Value[..Math.Min(16, hash.Groups[1].Value.Length)];

        var file = uri.Split('/', '\\').LastOrDefault()?.Split('?')[0];
        return string.IsNullOrEmpty(file) ? uri : SafeDecode(file);
    }

    /// <summary>Stable hue (0-359) for a name, so a tile keeps its colour between launches.</summary>
    public static int HueOf(string name)
    {
        var hash = 0;
        foreach (var ch in name) hash = (hash * 31 + ch) % 360;
        return hash;
    }

    static readonly Regex Scaffolding = new(
        @"\b(?:19\d{2}|20\d{2}|s\d{1,2}e\d{1,2}|season|complete|\d{3,4}p|2160p|4k|bluray|brrip|bdrip|web[ -]?dl|webrip|hdtv|dvdrip|x26[45]|h ?26[45]|hevc|aac|ac3|dts)\b",
        RegexOptions.IgnoreCase);

    /// <summary>"The.Matrix.1999.1080p.BluRay.x264-GRP" -> "The Matrix".</summary>
    public static string SearchableName(string name)
    {
        var s = Regex.Replace(name, @"\.[a-z0-9]{2,4}$", "", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"\[[^\]]*\]", " ");
        s = Regex.Replace(s, "[._]+", " ");
        s = Scaffolding.Split(s)[0];
        s = Regex.Replace(s, @"[(\[{].*$", "");
        return Regex.Replace(s, @"\s+", " ").Trim();
    }

    public static string? YearOf(string name)
    {
        var m = Regex.Match(name, @"\b(19\d{2}|20\d{2})\b");
        return m.Success ? m.Groups[1].Value : null;
    }

    public static bool IsSeries(string name) =>
        Regex.IsMatch(name, @"\bs\d{1,2}\s?e\d{1,2}\b|\b\d{1,2}x\d{2}\b|\bseason\b|\bcomplete series\b", RegexOptions.IgnoreCase);

    static string SafeDecode(string s)
    {
        try { return Uri.UnescapeDataString(s); } catch { return s; }
    }

    public static readonly string[] Trackers =
    {
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.coppersurfer.tk:6969/announce",
        "udp://tracker.leechers-paradise.org:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "http://tracker.openbittorrent.com:80/announce",
    };

    /// <summary>Bare magnet links crawl on DHT alone; attach well-known open trackers.</summary>
    public static string WithTrackers(string uri)
    {
        if (!uri.StartsWith("magnet:", StringComparison.OrdinalIgnoreCase)) return uri;
        var have = Regex.Matches(uri, "[?&]tr=([^&]+)").Select(m => SafeDecode(m.Groups[1].Value)).ToHashSet();
        var missing = Trackers.Where(t => !have.Contains(t)).ToList();
        return missing.Count == 0 ? uri : uri + string.Concat(missing.Select(t => "&tr=" + Uri.EscapeDataString(t)));
    }

    public static bool IsTorrentSource(string url)
    {
        var lower = url.ToLowerInvariant();
        if (lower.StartsWith("magnet:")) return true;
        if (lower.EndsWith(".torrent") && System.IO.File.Exists(url)) return true;
        return Regex.IsMatch(lower, "^(file|https?):") && lower.Split('?')[0].EndsWith(".torrent");
    }

    public static string? HashOf(string uri)
    {
        var m = Regex.Match(uri, "btih:([a-f0-9]{40})", RegexOptions.IgnoreCase);
        return m.Success ? m.Groups[1].Value.ToLowerInvariant() : null;
    }
}
