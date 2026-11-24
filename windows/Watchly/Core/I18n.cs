using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>
/// core I18n: the app's words in the viewer's language. The English is the key: i18n/en.txt lists every phrase,
/// one a line, with {} where a name or number goes in; each &lt;tag&gt;.txt has the same lines in the same order,
/// translated, with {1} {2}… for those parts. The phone's and the TV's files, embedded here as they are.
/// </summary>
public static class I18n
{
    /// <summary>Offered in Settings, each in its own words.</summary>
    public static readonly (string Tag, string Name)[] Languages =
    {
        ("en", "English"), ("es", "Español"), ("pt", "Português"), ("fr", "Français"), ("de", "Deutsch"),
        ("it", "Italiano"), ("ru", "Русский"), ("tr", "Türkçe"), ("ar", "العربية"), ("hi", "हिन्दी"),
        ("id", "Bahasa Indonesia"), ("ja", "日本語"), ("ko", "한국어"), ("zh", "简体中文"),
    };

    static Dictionary<string, string> exact = new();
    static List<(Regex Form, string To)> forms = new();
    static readonly Dictionary<string, string> seen = new();

    public static string Language { get; private set; } = "en";

    /// <summary>The picked language; "" follows Windows' own, where there is a translation, else English.</summary>
    public static string Resolve(string picked)
    {
        if (picked != "") return picked;
        var system = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName;
        return Languages.Any(l => l.Tag == system) ? system : "en";
    }

    /// <summary>The name a language is shown by, in its own words.</summary>
    public static string NameOf(string tag) => Languages.FirstOrDefault(l => l.Tag == tag).Name ?? "English";

    public static void Use(string picked)
    {
        var tag = Resolve(picked);
        Load(Lines("en"), tag == "en" ? new List<string>() : Lines(tag));
        Language = tag;
    }

    static List<string> Lines(string tag)
    {
        using var stream = typeof(I18n).Assembly.GetManifestResourceStream($"i18n/{tag}.txt");
        if (stream is null) return new();
        using var reader = new StreamReader(stream);
        var lines = new List<string>();
        while (reader.ReadLine() is { } line) lines.Add(line);
        return lines;
    }

    /// <summary>[en] and its translation [to], line for line.</summary>
    internal static void Load(IList<string> en, IList<string> to)
    {
        var words = new Dictionary<string, string>();
        var templates = new List<(Regex, string, int)>();
        // A file a line short would shift every word after it; the core test keeps them level, and here nothing is shown wrong.
        if (to.Count == en.Count)
            for (var i = 0; i < en.Count; i++)
            {
                var e = en[i];
                if (e.Contains("{}"))
                    templates.Add((new Regex("^" + string.Join("(.+?)", e.Split("{}").Select(Regex.Escape)) + "$", RegexOptions.Singleline), to[i], e.Replace("{}", "").Length));
                else words[e] = to[i];
            }
        lock (seen)
        {
            exact = words;
            // Most words first: "{} of {} watched" before "{} watched", which would swallow it.
            forms = templates.OrderByDescending(t => t.Item3).Select(t => (t.Item1, t.Item2)).ToList();
            seen.Clear();
        }
    }

    public static string Tr(string s)
    {
        if (exact.Count == 0 || string.IsNullOrWhiteSpace(s)) return s;
        lock (seen) if (seen.TryGetValue(s, out var known)) return known;
        var trimmed = s.Trim();
        var result = exact.TryGetValue(s, out var w) ? w
            : exact.TryGetValue(trimmed, out var t) ? s.Replace(trimmed, t)
            : Fill(s) ?? s;
        lock (seen)
        {
            // ponytail: cleared at 3000 rather than LRU; titles pass through too, so it can't grow without bound.
            if (seen.Count > 3000) seen.Clear();
            seen[s] = result;
        }
        return result;
    }

    /// <summary>A phrase with a name or number in it; the parts that are themselves phrases ("Drama") are translated too.</summary>
    static string? Fill(string s)
    {
        // Plots and the like are long; no phrase with a gap is.
        if (s.Length > 200) return null;
        foreach (var (form, to) in forms)
        {
            var m = form.Match(s);
            if (!m.Success) continue;
            var result = to;
            for (var i = 1; i < m.Groups.Count; i++)
            {
                var part = m.Groups[i].Value;
                result = result.Replace("{" + i + "}", exact.TryGetValue(part, out var p) ? p : part);
            }
            return result;
        }
        return null;
    }
}
