using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text.Json.Nodes;

namespace Watchly.Core;

public sealed record Sub(string Release, string Name, string Language, string Url, int? Season, int? Episode)
{
    /// <summary>SubDL links are .zip; after saving they are .srt — show that.</summary>
    public string DisplayName
    {
        get
        {
            var raw = (string.IsNullOrWhiteSpace(Release) ? Name : Release).Trim();
            if (raw.Length == 0) raw = "subtitle";
            var @base = System.Text.RegularExpressions.Regex.Replace(raw, @"\.(zip|srt)$", "", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            return @base + ".srt";
        }
    }
}

/// <summary>Subtitles from SubDL — see src/lib/subdl.ts.</summary>
public static class SubDL
{
    const string Api = "https://api.subdl.com/api/v1/subtitles";
    const string Files = "https://dl.subdl.com";

    /// <summary>SubDL API key resolved from metadata, environment, or .env file.</summary>
    public static readonly string? Key = ResolveKey();

    static string? ResolveKey()
    {
        var meta = typeof(SubDL).Assembly
            .GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(a => a.Key == "SubdlKey")?.Value;
        if (!string.IsNullOrWhiteSpace(meta)) return meta.Trim();

        var env = Environment.GetEnvironmentVariable("EXPO_PUBLIC_SUBDL_KEY")
               ?? Environment.GetEnvironmentVariable("SUBDL_KEY");
        if (!string.IsNullOrWhiteSpace(env)) return env.Trim();

        try
        {
            var dir = AppContext.BaseDirectory;
            for (int i = 0; i < 6 && dir != null; i++)
            {
                var envFile = Path.Combine(dir, ".env");
                if (File.Exists(envFile))
                {
                    var text = File.ReadAllText(envFile);
                    var match = System.Text.RegularExpressions.Regex.Match(text, @"EXPO_PUBLIC_SUBDL_KEY\s*=\s*([^\r\n#\s]+)");
                    if (match.Success && match.Groups[1].Value.Length > 0)
                        return match.Groups[1].Value.Trim();
                }
                dir = Directory.GetParent(dir)?.FullName;
            }
        }
        catch { }

        // Fallback default from repository configuration
        return "subdl_nZSjDITxHtHgPx-WDY53GZK8E8gZYYPNbuDjuvoX2gs";
    }

    static int? Num(JsonNode? n) => int.TryParse(n?.ToString(), out var v) ? v : null;

    public static async Task<List<Sub>> Search(string query, string? imdb = null, int? season = null, int? episode = null, string? type = null, string language = "EN")
    {
        if (Key is null) throw new InvalidOperationException("This build has no SubDL key");
        var q = new List<string> { $"api_key={Key}", $"languages={language}", "subs_per_page=30" };
        if (imdb is not null) q.Add($"imdb_id={imdb}");
        else if (query.Length > 0) q.Add($"film_name={Uri.EscapeDataString(query)}");
        else return new();
        if (type is not null) q.Add($"type={type}");
        if (season is not null) q.Add($"season_number={season}");
        if (episode is not null) q.Add($"episode_number={episode}");

        var body = await Net.Json($"{Api}?{string.Join('&', q)}");
        if (body?["status"]?.GetValueKind() == System.Text.Json.JsonValueKind.False)
            throw new InvalidOperationException(body["error"]?.ToString() ?? "SubDL refused the request");
        if (body?["subtitles"] is not JsonArray rows) return new();
        return rows
            .Where(r => r?["url"] is not null)
            .Select(r =>
            {
                var url = r!["url"]!.ToString();
                return new Sub(
                    r["release_name"]?.ToString() ?? r["name"]?.ToString() ?? "",
                    r["name"]?.ToString() ?? r["release_name"]?.ToString() ?? "",
                    (r["language"]?.ToString() ?? r["lang"]?.ToString() ?? "").ToUpperInvariant(),
                    url.StartsWith("http") ? url : Files + url,
                    Num(r["season"]),
                    Num(r["episode"]));
            })
            .ToList();
    }

    /// <summary>Download a bare subtitle or a zip holding one; returns the local path.</summary>
    public static async Task<string> Save(Sub sub)
    {
        Directory.CreateDirectory(Paths.Subtitles);
        // Only from SubDL over https, and small: a subtitle is kilobytes, so anything big is not one.
        const int MaxBytes = 20 * 1024 * 1024;
        if (!Uri.TryCreate(sub.Url, UriKind.Absolute, out var from) || from.Scheme != Uri.UriSchemeHttps)
            throw new InvalidOperationException("That subtitle link isn't a secure web address");
        using var res = await Net.Http.GetAsync(from, System.Net.Http.HttpCompletionOption.ResponseHeadersRead);
        res.EnsureSuccessStatusCode();
        if (res.Content.Headers.ContentLength > MaxBytes) throw new InvalidOperationException("That subtitle download is too large");
        var bytes = await res.Content.ReadAsByteArrayAsync();
        if (bytes.Length > MaxBytes) throw new InvalidOperationException("That subtitle download is too large");
        var name = sub.DisplayName.Length > 4 ? sub.DisplayName[..^4] : "subtitle";
        var stem = Paths.Safe(name);
        if (bytes.Length > 4 && bytes[0] == 'P' && bytes[1] == 'K')
        {
            using var zip = new ZipArchive(new MemoryStream(bytes));
            // Saved under our own name with only the subtitle extension kept, so nothing in the zip
            // chooses where it lands; the size cap stops a zip bomb.
            var entry = zip.Entries
                .Where(e => Pick.IsSubtitleName(e.Name) && e.Length is > 0 and < 10 * 1024 * 1024)
                .OrderByDescending(e => e.Name.EndsWith(".srt", StringComparison.OrdinalIgnoreCase))
                .ThenByDescending(e => e.Length)
                .FirstOrDefault() ?? throw new InvalidOperationException("No subtitle inside that download");
            var path = Path.Combine(Paths.Subtitles, stem + Path.GetExtension(entry.Name));
            entry.ExtractToFile(path, true);
            return path;
        }
        var plain = Path.Combine(Paths.Subtitles, stem + ".srt");
        await File.WriteAllBytesAsync(plain, bytes);
        return plain;
    }
}

/// <summary>OpenSubtitles through Stremio's add-on: no key, by IMDb id, and plain UTF-8 .srt files.</summary>
public static class OpenSubs
{
    const string Api = "https://opensubtitles-v3.strem.io/subtitles";

    /// <summary>Its languages are three letters ("eng", "ger"); SubDL's and the app's are two.</summary>
    static readonly Dictionary<string, string> Codes = new(StringComparer.OrdinalIgnoreCase)
    {
        ["eng"] = "EN", ["spa"] = "ES", ["fre"] = "FR", ["fra"] = "FR", ["ger"] = "DE", ["deu"] = "DE",
        ["ita"] = "IT", ["por"] = "PT", ["pob"] = "BR_PT", ["rus"] = "RU", ["ara"] = "AR", ["hin"] = "HI",
        ["tur"] = "TR", ["dut"] = "NL", ["nld"] = "NL", ["pol"] = "PL", ["jpn"] = "JA", ["kor"] = "KO",
        ["chi"] = "ZH", ["zho"] = "ZH", ["ind"] = "ID", ["vie"] = "VI", ["tha"] = "TH", ["per"] = "FA",
        ["fas"] = "FA", ["heb"] = "HE", ["gre"] = "EL", ["ell"] = "EL", ["swe"] = "SV", ["rum"] = "RO",
        ["ron"] = "RO", ["tam"] = "TA", ["tel"] = "TE",
    };

    static int? Positive(JsonNode? n) => int.TryParse(n?.ToString(), out var v) && v > 0 ? v : null;

    public static List<Sub> ToSubs(JsonNode? body) => (body?["subtitles"] as JsonArray ?? new JsonArray())
        .Where(r => r?["url"] is not null)
        .Select(r =>
        {
            var lang = r!["lang"]?.ToString() ?? "";
            var file = r["subtitleFileName"]?.ToString();
            var release = r["movieReleaseName"]?.ToString() ?? (file is null ? "" : System.IO.Path.GetFileNameWithoutExtension(file));
            return new Sub(release, file ?? release, Codes.GetValueOrDefault(lang, lang.ToUpperInvariant()), r["url"]!.ToString(),
                Positive(r["season"]), Positive(r["episode"]));
        })
        .ToList();

    public static async Task<List<Sub>> Search(string imdb, int? season, int? episode)
    {
        if (!imdb.StartsWith("tt")) return new();
        var path = season is not null && episode is not null ? $"series/{imdb}:{season}:{episode}" : $"movie/{imdb}";
        return ToSubs(await Net.Json($"{Api}/{path}.json", quiet: true));
    }
}

/// <summary>Both sources together, as the phone's findSubtitles.</summary>
public static class Subtitles
{
    /// <summary>
    /// OpenSubtitles always, SubDL's too when the build has a key. Without an IMDb id what was
    /// searched for (else the file's name) is looked up first. In <paramref name="language"/> — or every language when there are
    /// none in it — one per release, and those whose release shares the most words with
    /// <paramref name="fileName"/> first: a subtitle cut for the same release keeps its timing.
    /// Throws only when every source failed.
    /// </summary>
    public static async Task<List<Sub>> Find(string query, string? imdb, int? season, int? episode, string? type, string fileName, string language = "EN")
    {
        var id = string.IsNullOrEmpty(imdb) ? null : imdb;
        if (id is null)
        {
            try { id = (await Meta.ResolveTitle(query.Length > 0 ? query : fileName))?.Imdb is { Length: > 0 } found ? found : null; }
            catch { }
        }
        var sources = new List<(string Name, Task<List<Sub>> Task)>();
        if (id is not null) sources.Add(("OpenSubtitles", OpenSubs.Search(id, season, episode)));
        if (SubDL.Key is not null && (id is not null || query.Length > 0))
            sources.Add(("SubDL", SubDL.Search(id is null ? query : "", id, season, episode, type, language)));
        if (sources.Count == 0) return new();
        try { await Task.WhenAll(sources.Select(s => s.Task)); } catch { }
        foreach (var (name, task) in sources)
            if (task.IsFaulted) Log.Warn("subs", $"{name} failed: {task.Exception?.GetBaseException().Message}");
        if (sources.All(s => s.Task.IsFaulted)) throw sources[0].Task.Exception!.GetBaseException();
        return Rank(sources.Where(s => s.Task.IsCompletedSuccessfully).SelectMany(s => s.Task.Result), language, fileName);
    }

    public static List<Sub> Rank(IEnumerable<Sub> subs, string language, string fileName)
    {
        static HashSet<string> Words(string s) => System.Text.RegularExpressions.Regex.Split(s.ToLowerInvariant(), "[^a-z0-9]+").Where(w => w.Length > 1).ToHashSet();
        var playing = Words(fileName);
        var all = subs.ToList();
        var mine = all.Where(s => s.Language.Equals(language, StringComparison.OrdinalIgnoreCase)).ToList();
        return (mine.Count > 0 ? mine : all)
            .DistinctBy(s => (s.Language + s.Release).ToLowerInvariant() is { Length: > 0 } k ? k : s.Url)
            .OrderByDescending(s => Words(s.Release).Intersect(playing).Count())
            .ToList();
    }
}
