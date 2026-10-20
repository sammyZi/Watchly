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
/// IntroDB (introdb.app): community-submitted intro windows per episode, by IMDb id.
/// Reads need no key; an episode nobody has timed answers 404, which is cached like a hit.
/// </summary>
public static class IntroDb
{
    const string Base = "https://api.introdb.app";
    static readonly Dictionary<string, Intro?> cache = new();

    public static async Task<Intro?> Find(string imdb, int season, int episode)
    {
        if (!imdb.StartsWith("tt")) return null;
        var key = $"{imdb}:{season}:{episode}";
        lock (cache) if (cache.TryGetValue(key, out var known)) return known;
        Intro? found;
        try
        {
            using var res = await Net.Send($"{Base}/segments?imdb_id={Uri.EscapeDataString(imdb)}&season={season}&episode={episode}", quiet: true);
            if (res.StatusCode == HttpStatusCode.NotFound) found = null;
            else if (!res.IsSuccessStatusCode) return null;
            else found = Parse(await res.Content.ReadAsStringAsync());
        }
        catch
        {
            return null;
        }
        lock (cache) cache[key] = found;
        return found;
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
