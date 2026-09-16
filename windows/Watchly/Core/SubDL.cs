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
