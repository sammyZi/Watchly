using System.Diagnostics;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>One stream an addon offered: a torrent, or a link that plays as it is (tv/core Search.kt).</summary>
public sealed record Hit
{
    /// <summary>The info hash for a torrent, so two addons' copies are one row; else the link.</summary>
    public required string Id { get; init; }
    public required string Name { get; init; }
    /// <summary>A magnet, or the http(s) link itself — a debrid service's, or the viewer's own server's.</summary>
    public required string Uri { get; init; }
    public int Seeders { get; init; }
    public int Leechers { get; init; }
    public long Size { get; init; }
    public string? Quality { get; init; }
    /// <summary>movie | tv | other</summary>
    public string Kind { get; init; } = "other";
    /// <summary>episode | season | complete</summary>
    public string Scope { get; init; } = "episode";
    /// <summary>An addon's size is the one file it found, even for a season pack.</summary>
    public bool PerFileSize { get; init; }
    /// <summary>The addon it came from, by name.</summary>
    public string Addon { get; init; } = "";
    /// <summary>Not ours to play: a YouTube video or a page the addon sends to the browser.</summary>
    public bool External { get; init; }
    /// <summary>What the link's server wants with every request (an addon's <c>proxyHeaders</c>).</summary>
    public Dictionary<string, string> Headers { get; init; } = new();
    /// <summary>The file the addon meant, by the torrent's own numbering — for when names can't say.</summary>
    public int? FileIdx { get; init; }
    /// <summary>The manifest link of the addon it came from: a debrid link only plays while that addon (and its key) is still added.</summary>
    public string AddonUrl { get; init; } = "";

    /// <summary>Plays straight from its link: no swarm, no seeders to count, nothing to download.</summary>
    public bool Direct => !External && Search.IsDirectLink(Uri);
    /// <summary>A torrent: the only kind the download engine can keep.</summary>
    public bool IsTorrent => !Direct && !External;
}

/// <summary>A language to ask releases in: its name goes in the query, <see cref="Match"/> is how a release name says it (tv/core LANGS).</summary>
public sealed record Lang(string Code, string Name, Regex Match)
{
    static Lang Of(string code, string name, string words) => new(code, name, new Regex($@"\b({words})\b", RegexOptions.IgnoreCase));

    public static readonly Lang[] All =
    {
        Of("EN", "English", "english|eng"),
        Of("ES", "Spanish", "spanish|español|espanol|spa|castellano|latino"),
        Of("FR", "French", "french|français|francais|fre|fra"),
        Of("DE", "German", "german|deutsch|ger|deu"),
        Of("IT", "Italian", "italian|italiano|ita"),
        Of("PT", "Portuguese", "portuguese|português|portugues|por"),
        Of("BR_PT", "Portuguese (Brazil)", @"brazil\w*|pt-br|pob"),
        Of("RU", "Russian", "russian|rus"),
        Of("AR", "Arabic", "arabic|ara"),
        Of("HI", "Hindi", "hindi|hin"),
        Of("TR", "Turkish", "turkish|tur"),
        Of("NL", "Dutch", "dutch|nederlands|dut|nld"),
        Of("PL", "Polish", "polish|polski|pol"),
        // ISO 639-2 has old codes for these too (scr, scc), still in some files' track names.
        Of("HR", "Croatian", "croatian|hrvatski|hrv|scr"),
        Of("SR", "Serbian", "serbian|srpski|српски|srp|scc"),
        Of("BS", "Bosnian", "bosnian|bosanski|bos"),
        Of("JA", "Japanese", "japanese|jpn"),
        Of("KO", "Korean", "korean|kor"),
        Of("ZH", "Chinese", "chinese|mandarin|chi|zho"),
        Of("ID", "Indonesian", "indonesian|ind"),
        Of("VI", "Vietnamese", "vietnamese|vie"),
        Of("TH", "Thai", "thai|tha"),
        Of("FA", "Persian", "persian|farsi|fas"),
        Of("HE", "Hebrew", "hebrew|heb"),
        Of("EL", "Greek", "greek|gre|ell"),
        Of("SV", "Swedish", "swedish|swe"),
        Of("RO", "Romanian", "romanian|rum|ron"),
        Of("TA", "Tamil", "tamil|tam"),
        Of("TE", "Telugu", "telugu|tel"),
        Of("ML", "Malayalam", "malayalam|mal"),
        Of("BN", "Bengali", "bengali|bangla|ben"),
    };

    /// <summary>"Portuguese (Brazil)" is asked for as "Portuguese".</summary>
    public string Word => Name.Split(" (")[0];

    public bool Says(Hit h) => Match.IsMatch(h.Name);
}

public static class Search
{
    static void Log(string message) => Debug.WriteLine($"[releases] {message}");

    const RegexOptions I = RegexOptions.IgnoreCase;

    public static string EpisodeTag(int season, int episode) => $"S{season:00}E{episode:00}";

    /// <summary>
    /// What picks a file in a pack: the tag, plus the number counted through the
    /// show when that differs ("S02E05#29") — see <see cref="Pick.PickFile"/>.
    /// </summary>
    public static string PickTag(int season, int episode, int? absolute) =>
        absolute is int a && a != episode ? $"{EpisodeTag(season, episode)}#{a}" : EpisodeTag(season, episode);

    public static (int Season, int Episode)? ParseEpisode(string? name)
    {
        if (string.IsNullOrEmpty(name)) return null;
        // Up to four digits: long-running anime is released as S01E1071.
        var m = Regex.Match(name, @"\bs(\d{1,2})[ ._-]*e(\d{1,4})\b", I);
        if (!m.Success) m = Regex.Match(name, @"\b(\d{1,2})x(\d{1,2})\b", I);
        return m.Success ? (int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value)) : null;
    }

    /// <summary>
    /// The episode number of a release that names no season — how anime is
    /// released: "[SubsPlease] Jujutsu Kaisen - 29 (1080p)", "One Piece E1071",
    /// "Naruto Shippuden Episode 145". Often counted straight through the show
    /// rather than per season; see <see cref="Meta.AbsoluteNumber"/>.
    /// </summary>
    public static int? ParseAbsolute(string? name)
    {
        if (string.IsNullOrEmpty(name) || ParseEpisode(name) is not null) return null;
        var m = Regex.Match(name, @"(?:^|[\s_\]\)])-[\s_]*(\d{1,4})(?:v\d)?(?=[\s_.\[\(]|$)", I);
        if (!m.Success) m = Regex.Match(name, @"\b(?:episode|ep|e)[ ._-]?(\d{1,4})(?:v\d)?\b", I);
        if (!m.Success) m = Regex.Match(name, @"(?:^|[\s_])\[(\d{1,4})(?:v\d)?\]"); // "Jujutsu Kaisen S2 [29]"
        // "[yibis]_One_Piece_588_[720p]": a bare number right before the tags (not "Season 12 [1080p]").
        if (!m.Success) m = Regex.Match(name, @"(?<!season[\s_]*)[\s_](\d{2,4})(?:v\d)?[\s_]*(?=[\[\(]|$)", I);
        if (!m.Success) return null;
        var n = int.Parse(m.Groups[1].Value);
        return n is > 0 and < 1900 ? n : null;
    }

    const string SpanMark = @"(?:s\d{1,2}[ ._-]*e|ep?)?";

    /// <summary>"Show (01-24) [Batch]", "Show EP1144-EP1155", "Show S01E1144-E1155": the episodes an anime batch claims.</summary>
    public static (int From, int To)? EpisodeSpan(string name)
    {
        var m = Regex.Match(name, $@"(?<![\w.]){SpanMark}(\d{{1,4}})\s*[-~–]\s*{SpanMark}(\d{{1,4}})(?![\w.])", I);
        if (!m.Success) return null;
        var (from, to) = (int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value));
        return from < to && to < 1900 ? (from, to) : null;
    }

    /// <summary>
    /// Does a batch hold this episode? Null when it is no batch. Without a season label the
    /// span counts through the show; "S01E1144-E1155" is either that season's or, as season 1
    /// of a show listed in arcs, the running number.
    /// </summary>
    public static bool? SpanHolds(string name, int season, int episode, int? absolute)
    {
        if (EpisodeSpan(name) is not { } span) return null;
        bool In(int n) => n >= span.From && n <= span.To;
        var claims = SeasonsIn(name);
        if (claims.Count == 0) return In(absolute ?? episode);
        return claims.Contains(season) && In(episode) || claims.Contains(1) && absolute is int a && In(a);
    }

    /// <summary>
    /// Does release <paramref name="name"/> say it is this episode? Null when it names none (a
    /// season pack): a batch holds it or not, a single release names it or another one.
    /// </summary>
    public static bool? ReleaseIs(string name, int season, int episode, int? absolute) =>
        SpanHolds(name, season, episode, absolute) ?? NamesEpisode(name, season, episode, absolute);

    /// <summary>
    /// The addons number a long anime their own way, and can sit one off the episode list: asked
    /// for 1001, they answer with "- 1002". <paramref name="own"/> (the answers for this episode's
    /// id) keeps all but the releases naming another episode; <paramref name="near"/> (the
    /// neighbours' answers) gives those naming this one. If that leaves nothing, <paramref name="own"/> as it was.
    /// </summary>
    public static List<Hit> SameEpisode(List<Hit> own, IEnumerable<Hit> near, int season, int episode, int absolute)
    {
        var kept = Merge(new[]
        {
            own.Where(h => ReleaseIs(h.Name, season, episode, absolute) != false),
            near.Where(h => ReleaseIs(h.Name, season, episode, absolute) == true),
        });
        return kept.Count > 0 ? kept : own;
    }

    /// <summary>
    /// Is this name episode <paramref name="episode"/> of <paramref name="season"/>
    /// (<paramref name="absolute"/> counting through the show)? Null when it names no
    /// single episode. <paramref name="folder"/> is where season labels may live instead.
    /// </summary>
    public static bool? NamesEpisode(string name, int season, int episode, int? absolute, string? folder = null)
    {
        if (ParseEpisode(name) is { } at)
            return at.Season == season && at.Episode == episode || at.Season == 1 && at.Episode == absolute;
        if (ParseAbsolute(name) is not int n) return null;
        // "- 29" is the 29th episode of the show. A season label decides which season:
        // "Season 2 - 05" is the fifth of season 2 (never season 1's fifth), "S2 [29]" the 29th overall.
        var claims = SeasonsIn($"{folder} {name}");
        return claims.Count == 0 ? n == (absolute ?? episode) : claims.Contains(season) && (n == episode || n == absolute);
    }

    /// <summary>Which seasons a release name claims to hold. Empty when it names none.</summary>
    public static List<int> SeasonsIn(string name)
    {
        var @out = new SortedSet<int>();
        string[] ranges = { @"\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b", @"\bseasons?[ ._-]*(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b" };
        foreach (var pattern in ranges)
        {
            foreach (Match found in Regex.Matches(name, pattern, I))
            {
                var from = int.Parse(found.Groups[1].Value);
                var to = int.Parse(found.Groups[2].Value);
                for (var i = Math.Min(from, to); i <= Math.Max(from, to); i++) @out.Add(i);
            }
        }
        if (@out.Count > 0) return @out.ToList();

        string[] ones = { @"\bs(\d{1,2})(?=e\d{1,2}\b|\b)", @"\bseasons?[ ._-]*(\d{1,2})\b" };
        foreach (var pattern in ones)
            foreach (Match found in Regex.Matches(name, pattern, I))
                @out.Add(int.Parse(found.Groups[1].Value));
        return @out.ToList();
    }

    public static string ScopeOf(string name)
    {
        if (Regex.IsMatch(name, @"\bs\d{1,2}\s?e\d{1,2}\s*[-–]\s*e?\d{1,2}\b", I)) return "season";
        if (Regex.IsMatch(name, @"\bs\d{1,2}\s?e\d{1,2}\b|\b\d{1,2}x\d{2}\b", I)) return "episode";
        // "Show S2 [29]", "Show Season 2 - 05": one anime episode, not the season pack its label suggests.
        if (ParseAbsolute(name) is not null) return "episode";
        if (Regex.IsMatch(name, @"\bseasons?\s*\d{1,2}\s*[-–]\s*\d{1,2}\b|\ball seasons\b|\bcomplete series\b", I)) return "complete";
        var span = Regex.Match(name, @"\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b", I);
        if (span.Success && int.Parse(span.Groups[1].Value) != int.Parse(span.Groups[2].Value)) return "complete";
        if (Regex.IsMatch(name, @"\bs\d{1,2}\b|\bseason\s*\d{1,2}\b", I)) return "season";
        return "episode";
    }

    public static string? Quality(string name)
    {
        if (Regex.IsMatch(name, @"2160p|\buhd\b|\b4k\b", I)) return "4K";
        if (Regex.IsMatch(name, "1080p", I)) return "1080p";
        if (Regex.IsMatch(name, "720p", I)) return "720p";
        if (Regex.IsMatch(name, @"\bcam\b|\bhdts\b|\btelesync\b", I)) return "CAM";
        return null;
    }

    /// <summary>fast | good | weak | dead</summary>
    public static string Health(int seeders) =>
        seeders >= 100 ? "fast" : seeders >= 20 ? "good" : seeders >= 1 ? "weak" : "dead";

    static bool IsHash(string? s) => s is not null && Regex.IsMatch(s, "^[0-9a-f]{40}$", I);

    /// <summary>The addon's own trackers (its <c>sources</c>) first, then the well-known open ones.</summary>
    public static string MagnetOf(string hash, string name, IEnumerable<string>? trackers = null) =>
        TitleText.WithTrackers($"magnet:?xt=urn:btih:{hash}&dn={Uri.EscapeDataString(name)}"
            + string.Concat((trackers ?? Enumerable.Empty<string>()).Select(t => "&tr=" + Uri.EscapeDataString(t))));

    /// <summary>The info hash a debrid link names in its path (Torrentio's ".../resolve/torbox/&lt;key&gt;/&lt;hash&gt;/..."), if any.</summary>
    public static string? LinkHash(string link) =>
        link.Split('?')[0].Split('/').FirstOrDefault(IsHash)?.ToLowerInvariant();

    /// <summary>A video served over http(s), as opposed to a magnet, a .torrent or a path.</summary>
    public static bool IsDirectLink(string uri) => Regex.IsMatch(uri, "^https?://", I) && !TitleText.IsTorrentSource(uri);

    static string Str(JsonNode? node) => node switch
    {
        null => "",
        JsonValue v when v.TryGetValue<string>(out var s) => s,
        _ => node.ToString(),
    };

    static double Num(JsonNode? node) =>
        double.TryParse(Str(node), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var d) ? d : 0;

    /// <summary>Links first — they play at once — then by how well the swarm will actually serve you.</summary>
    public static List<Hit> Rank(IEnumerable<Hit> hits, string query = "")
    {
        var words = query.ToLowerInvariant().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        double Score(Hit h)
        {
            var full = words.Length > 0 && words.All(w => h.Name.ToLowerInvariant().Contains(w));
            return (h.Seeders + h.Leechers / 4.0) * (full ? 2 : 1);
        }
        // Other apps' (YouTube, a page) last.
        return hits.OrderByDescending(h => h.Direct).ThenBy(h => h.External).ThenByDescending(Score).ToList();
    }

    /// <summary>Links first, then this season's pack (fast enough to open). Whole-series dumps last.</summary>
    public static List<Hit> RankEpisode(IEnumerable<Hit> hits, string query = "", int? season = null)
    {
        var all = Rank(hits, query);
        var ordered = all.Where(h => !h.Direct).ToList();
        bool ThisPack(Hit h)
        {
            if (h.Seeders < 1 || h.Scope != "season") return false;
            var c = SeasonsIn(h.Name);
            return c.Count <= 1 && (season == null || c.Count == 0 || c.Contains(season.Value));
        }
        bool One(Hit h) => h.Scope == "episode" && h.Seeders >= 1;
        return all.Where(h => h.Direct)
            .Concat(ordered.Where(ThisPack))
            .Concat(ordered.Where(One))
            .Concat(ordered.Where(h => !ThisPack(h) && !One(h)))
            .ToList();
    }

    static readonly Regex SeedersRx = new(@"👤\s*(\d+)");
    static readonly Regex SizeRx = new(@"💾\s*([\d.]+)\s*(TB|GB|MB|KB)", I);

    static long Unit(string unit) => unit.ToLowerInvariant() switch
    {
        "kb" => 1024L,
        "mb" => 1024L * 1024,
        "gb" => 1024L * 1024 * 1024,
        "tb" => 1024L * 1024 * 1024 * 1024,
        _ => 0,
    };

    /// <summary>
    /// An addon's streams, every shape the Stremio protocol has: a torrent by <c>infoHash</c> (with its
    /// <c>fileIdx</c> and <c>sources</c> trackers), a <c>url</c> that plays as it is (with any <c>proxyHeaders</c>),
    /// and a <c>ytId</c> or <c>externalUrl</c> handed to the browser. The numbers most addons give sit inside the
    /// display text — "name\n👤 84 💾 51.3 GB ⚙️ 1337x" — and <c>behaviorHints</c> carry the file's real name and size.
    /// ponytail: archive (rarUrls…) and nzbUrl streams are skipped; few addons send them yet.
    /// </summary>
    public static List<Hit> ToStreamHits(JsonArray streams, string kind, string addon = "", string addonUrl = "")
    {
        var @out = new List<Hit>();
        foreach (var s in streams.OfType<JsonObject>())
        {
            var hash = Str(s["infoHash"]).ToLowerInvariant();
            if (!IsHash(hash)) hash = "";
            var url = Str(s["url"]);
            if (!IsDirectLink(url) && !TitleText.IsTorrentSource(url)) url = "";
            var outside = Str(s["ytId"]) is { Length: > 0 } yt ? $"https://www.youtube.com/watch?v={Uri.EscapeDataString(yt)}"
                : Str(s["externalUrl"]) is { } ext && Regex.IsMatch(ext, "^https?://", I) ? ext : "";
            if (hash.Length == 0 && url.Length == 0 && outside.Length == 0) continue;
            var hints = s["behaviorHints"] as JsonObject;
            var headers = (hints?["proxyHeaders"]?["request"] as JsonObject)?.ToDictionary(h => h.Key, h => Str(h.Value)) ?? new();
            var trackers = (s["sources"] as JsonArray ?? new JsonArray()).Select(Str).Where(t => t.StartsWith("tracker:")).Select(t => t["tracker:".Length..]);
            var fileIdx = (int)Num(s["fileIdx"] ?? JsonValue.Create(-1));
            var text = Str(s["title"]) is { Length: > 0 } t ? t : Str(s["description"]);
            var label = Str(s["name"]).Replace('\n', ' ').Trim();
            var name = Str(hints?["filename"]) is { Length: > 0 } file ? file
                : text.Split('\n')[0].Trim() is { Length: > 0 } first ? first : label;
            if (name.Length == 0) continue;
            var size = (long)Num(hints?["videoSize"]);
            if (size <= 0 && SizeRx.Match(text) is { Success: true } sm)
                size = (long)Math.Round(double.Parse(sm.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) * Unit(sm.Groups[2].Value));
            var seeds = SeedersRx.Match(text);
            @out.Add(new Hit
            {
                Id = hash.Length > 0 ? hash : url.Length > 0 ? url : outside,
                Name = name,
                Uri = hash.Length > 0 ? MagnetOf(hash, name, trackers) : url.Length > 0 ? url : outside,
                External = hash.Length == 0 && url.Length == 0,
                Headers = url.Length > 0 ? headers : new(),
                FileIdx = hash.Length > 0 && fileIdx >= 0 ? fileIdx : null,
                Seeders = seeds.Success ? int.Parse(seeds.Groups[1].Value) : 0,
                Size = Math.Max(0, size),
                Quality = Quality(name) ?? Quality($"{label} {text}"),
                Scope = ScopeOf(name),
                Kind = kind,
                PerFileSize = true,
                Addon = addon,
                AddonUrl = addonUrl,
            });
        }
        return @out;
    }

    /// <summary>A pasted magnet or http(s) video link as a stream of its own: a file on the viewer's own server, say.</summary>
    public static Hit? LinkHit(string text)
    {
        var s = text.Trim();
        if (!s.StartsWith("magnet:?", StringComparison.OrdinalIgnoreCase) && !Regex.IsMatch(s, @"^https?://\S+$", I)) return null;
        var name = TitleText.TitleOf(s);
        return new Hit { Id = TitleText.HashOf(s) ?? s, Name = name, Uri = s, Quality = Quality(name), Kind = TitleText.IsSeries(name) ? "tv" : "movie", Scope = ScopeOf(name), Addon = "Link" };
    }

    /// <summary>Same torrent from two addons is one row — keep whichever knew more.</summary>
    public static List<Hit> Merge(IEnumerable<IEnumerable<Hit>> lists)
    {
        var best = new Dictionary<string, Hit>();
        var order = new List<string>();
        foreach (var hit in lists.SelectMany(l => l))
        {
            if (!best.TryGetValue(hit.Id, out var seen))
            {
                best[hit.Id] = hit;
                order.Add(hit.Id);
                continue;
            }
            var bigger = hit.Size > seen.Size ? hit : seen;
            best[hit.Id] = (hit.Seeders > seen.Seeders ? hit : seen) with
            {
                Seeders = Math.Max(hit.Seeders, seen.Seeders),
                Size = bigger.Size,
                PerFileSize = bigger.PerFileSize,
            };
        }
        return order.Select(id => best[id]).ToList();
    }

    /// <summary>How long one addon gets in a search before it is left out of the answer.</summary>
    const int SourceMs = 12000;

    /// <summary>The job's answer, or <paramref name="fallback"/> if it fails or takes longer than <paramref name="ms"/>.</summary>
    static async Task<T> Limited<T>(Task<T> job, int ms, T fallback)
    {
        try
        {
            var done = await Task.WhenAny(job, Task.Delay(ms));
            return done == job ? await job : fallback;
        }
        catch
        {
            return fallback;
        }
    }

    static async Task<List<Hit>> Timed(string name, Task<List<Hit>> job)
    {
        var sw = Stopwatch.StartNew();
        var @out = await job;
        Log($"{name} {sw.ElapsedMilliseconds}ms n={@out.Count}");
        return @out;
    }

    /// <summary>
    /// Every enabled addon that streams <paramref name="type"/> for <paramref name="id"/>, at once;
    /// <paramref name="progress"/>, if given, hears the merged, ranked list each time another answers.
    /// One addon being unreachable is the normal case.
    /// </summary>
    static async Task<List<Hit>> FromAddons(string type, string id, CancellationToken ct, Lang? lang, Action<List<Hit>>? progress, Func<List<Hit>, List<Hit>> order)
    {
        var kind = Addons.KindOfType(type);
        var sw = Stopwatch.StartNew();
        // A language is picked by name ("Hindi"), and only streams that say it count.
        List<Hit> Combine(IEnumerable<List<Hit>> bags)
        {
            var all = order(Merge(bags));
            return lang is null ? all : all.Where(lang.Says).ToList();
        }
        async Task<List<Hit>> Ask(Addon a) =>
            (await a.Get("stream", type, id, ct))?["streams"] is JsonArray streams ? ToStreamHits(streams, kind, a.Name, a.Url) : new();

        var pending = Addons.Serving("stream", type, id).Select(a => Timed(a.Name, Limited(Ask(a), SourceMs, new List<Hit>()))).ToList();
        var arrived = new List<List<Hit>>();
        while (pending.Count > 0)
        {
            var done = await Task.WhenAny(pending);
            pending.Remove(done);
            var hits = await done; // Limited: never throws
            arrived.Add(hits);
            if (progress is not null && hits.Count > 0 && pending.Count > 0 && !ct.IsCancellationRequested)
                progress(Combine(arrived));
        }
        var @out = Combine(arrived);
        Log($"{type} {id} {sw.ElapsedMilliseconds}ms n={@out.Count}");
        return @out;
    }

    /// <summary>
    /// A film's streams from the viewer's addons. Without an id, <paramref name="title"/> is looked up by name
    /// first; a link pasted instead — a magnet, or a video on the viewer's own server — is the one stream.
    /// </summary>
    public static async Task<List<Hit>> SearchStreams(
        string title, CancellationToken ct = default, string? imdb = null, string? year = null,
        Action<List<Hit>>? progress = null, Lang? lang = null, string type = "movie")
    {
        if (LinkHit(title) is { } link) return new() { link };
        var id = imdb;
        if (id is null && await Meta.ResolveTitle(string.Join(' ', new[] { title, year }.Where(x => !string.IsNullOrEmpty(x)))) is { Kind: "movie" } found)
            id = found.Imdb;
        if (string.IsNullOrEmpty(id)) return new();
        return await FromAddons(type, id, ct, lang, progress, hits => Rank(hits, title));
    }

    static readonly Dictionary<string, Task<List<Hit>>> EpisodeMemo = new();

    // An addon added, dropped or switched answers differently.
    static Search() => Addons.Changed += () => { lock (EpisodeMemo) EpisodeMemo.Clear(); };

    /// <summary>
    /// Every stream the addons have for one episode, by the episode's own id ("tt0944947:1:2"): packs
    /// included, since a pack streams just as well — only the one file is fetched. Long anime
    /// (<paramref name="title"/> numbered through) also asks for the episodes either side, and keeps
    /// only releases numbered as this one (<see cref="SameEpisode"/>).
    /// </summary>
    public static Task<List<Hit>> SearchEpisode(string id, string show, int season, CancellationToken ct = default, Lang? lang = null, string type = "series", Title? title = null)
    {
        var key = $"{id}:{lang?.Code}";
        lock (EpisodeMemo)
        {
            if (EpisodeMemo.TryGetValue(key, out var cached)) return cached;
            var pending = Find().ContinueWith(t =>
            {
                var hits = t.IsCompletedSuccessfully ? t.Result : new List<Hit>();
                if (hits.Count == 0) lock (EpisodeMemo) EpisodeMemo.Remove(key);
                return hits;
            });
            EpisodeMemo[key] = pending;
            return pending;
        }

        async Task<List<Hit>> Find()
        {
            List<Hit> Order(List<Hit> hits) => RankEpisode(hits, show, season);
            var numbers = title is { NumberedThrough: true } && title.Episodes.Any(e => e.Id == id) ? Meta.AbsoluteNumbers(title.Episodes) : new();
            var at = numbers.FirstOrDefault(n => n.Episode.Id == id);
            if (at.Episode is null) return await FromAddons(type, id, ct, lang, null, Order);
            var near = numbers.Where(n => n.Number == at.Number - 1 || n.Number == at.Number + 1).Select(n => n.Episode.Id).Where(n => n != id).Distinct().ToList();
            var asked = await Task.WhenAll(new[] { id }.Concat(near).Select(each => FromAddons(type, each, ct, lang, null, hits => hits)));
            return Order(SameEpisode(asked[0], asked.Skip(1).SelectMany(h => h), at.Episode.Season, at.Episode.Number, at.Number));
        }
    }
}
