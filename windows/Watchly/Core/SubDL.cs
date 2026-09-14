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

    /// <summary>Baked in at build time from the repo's .env, like the phone app.</summary>
    public static readonly string? Key = typeof(SubDL).Assembly
        .GetCustomAttributes<AssemblyMetadataAttribute>()
        .FirstOrDefault(a => a.Key == "SubdlKey")?.Value is { Length: > 0 } k ? k : null;

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
        var bytes = await Net.Http.GetByteArrayAsync(sub.Url);
        var stem = Paths.Safe(sub.DisplayName[..^4]);
        if (bytes.Length > 4 && bytes[0] == 'P' && bytes[1] == 'K')
        {
            using var zip = new ZipArchive(new MemoryStream(bytes));
            var entry = zip.Entries
                .Where(e => Pick.IsSubtitleName(e.Name))
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
