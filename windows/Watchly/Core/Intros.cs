using System.Net;
using System.Text.Json.Nodes;

namespace Watchly.Core;

/// <summary>Where an episode's opening titles sit, in seconds. See the phone's Intro.</summary>
public sealed record Intro(double Start, double End)
{
    /// <summary>Inside the intro, with a moment's lead so the button is up as it starts.</summary>
    public bool Covers(double at) => at >= Start - 2 && at < End - 1;
}

/// <summary>
/// Community-submitted intro windows by IMDb id, from IntroDB (introdb.app) and TheIntroDB
/// (theintrodb.org), neither needing a key. Each has what the other lacks: of 90 episodes tried
/// they timed 51 and 57, and 72 between them. TheIntroDB also has films, with no season asked for.
/// </summary>
public static class IntroDb
{
    const string Base = "https://api.introdb.app";
    const string TheIntroDb = "https://api.theintrodb.org/v2/media";
    static readonly Dictionary<string, Intro> cache = new();

    static async Task<Intro?> Ask(string url, Func<string, Intro?> parse)
    {
        try
        {
            using var res = await Net.Send(url, quiet: true);
            return res.IsSuccessStatusCode ? parse(await res.Content.ReadAsStringAsync()) : null;
        }
        catch
        {
            return null;
        }
    }

    public static async Task<Intro?> Find(string imdb, int? season = null, int? episode = null)
    {
        if (!imdb.StartsWith("tt")) return null;
        var key = $"{imdb}:{season}:{episode}";
        lock (cache) if (cache.TryGetValue(key, out var known)) return known;
        var id = Uri.EscapeDataString(imdb);
        var at = season is null ? "" : $"&season={season}&episode={episode}";
        var found = (season is null ? null : await Ask($"{Base}/segments?imdb_id={id}{at}", Parse))
            ?? await Ask($"{TheIntroDb}?imdb_id={id}{at}", ParseTheIntroDb);
        if (found is not null) lock (cache) cache[key] = found;
        return found;
    }

    /// <summary>The first "intro" of a TheIntroDB answer. A start of null is the very beginning; an end of null isn't a window.</summary>
    public static Intro? ParseTheIntroDb(string json)
    {
        JsonNode? seg;
        try { seg = (JsonNode.Parse(json)?["intro"] as JsonArray)?.FirstOrDefault(); } catch { return null; }
        if (seg is not JsonObject || Num(seg["end_ms"]) is not { } end) return null;
        var start = Num(seg["start_ms"]) ?? 0;
        return start >= 0 && end > start ? new Intro(start / 1000, end / 1000) : null;
    }

    /// <summary>The "intro" segment of a /segments answer, in seconds (start_sec, else start_ms).</summary>
    public static Intro? Parse(string json)
    {
        JsonNode? seg;
        try { seg = JsonNode.Parse(json)?["intro"]; } catch { return null; }
        if (seg is not JsonObject) return null;
        double? Seconds(string name)
        {
            double? v = Num(seg[$"{name}_sec"]) ?? Num(seg[$"{name}_ms"]) / 1000;
            return v is { } d && double.IsFinite(d) ? d : null;
        }
        if (Seconds("start") is not { } start || Seconds("end") is not { } end) return null;
        return start >= 0 && end > start ? new Intro(start, end) : null;
    }

    static double? Num(JsonNode? n)
    {
        if (n is null) return null;
        if (n is JsonValue v && v.TryGetValue<double>(out var d)) return d;
        return double.TryParse(n.ToString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out d) ? d : null;
    }
}
