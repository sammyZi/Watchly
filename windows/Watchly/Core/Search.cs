using System.Diagnostics;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>A torrent release from one of the indexes. See src/lib/search.ts.</summary>
public sealed record Hit
{
    /// <summary>The info hash — the one id every index agrees on.</summary>
    public required string Id { get; init; }
    public required string Name { get; init; }
    public required string Magnet { get; init; }
    public int Seeders { get; init; }
    public int Leechers { get; init; }
    public long Size { get; init; }
    public string? Quality { get; init; }
    /// <summary>movie | tv | other</summary>
    public string Kind { get; init; } = "other";
    /// <summary>episode | season | complete</summary>
    public string Scope { get; init; } = "episode";
    public string? Imdb { get; init; }
    /// <summary>Torrentio's size is the one file it found, even for a season pack.</summary>
    public bool PerFileSize { get; init; }
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
    const string Api = "https://apibay.org/q.php";
    const string Csv = "https://torrents-csv.com/service/search";
    const string Torrentio = "https://torrentio.strem.fun/stream";
    /// <summary>Same stream format as Torrentio, a different (larger) index.</summary>
    const string TorrentsDb = "https://torrentsdb.com/stream";
    /// <summary>yts.mx no longer resolves on some networks; the mirrors serve the same API.</summary>
    static readonly string[] YtsHosts = { "https://yts.mx", "https://yts.lt", "https://yts.am", "https://movies-api.accel.li" };
    /// <summary>One search across many trackers (The Pirate Bay, 1337x, RuTracker, YTS, …) — reachable where apibay is blocked.</summary>
    const string Knaben = "https://api.knaben.org/v1";
    const string Bitsearch = "https://bitsearch.to/api/v1/search";
    const string TheRarbg = "https://therarbg.com/get-posts/keywords:";
    const string Nyaa = "https://nyaa.si/?page=rss&c=1_2&f=0&q=";
    const string Eztv = "https://eztv.tf/api/get-torrents";
    const int FastMs = 2000;

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

    /// <summary>"Show (01-24) [Batch]", "Show - 01~12": the episodes an anime batch claims.</summary>
    public static (int From, int To)? EpisodeSpan(string name)
    {
        var m = Regex.Match(name, @"(?<![\w.])(\d{1,4})\s*[-~–]\s*(\d{1,4})(?![\w.])", I);
        if (!m.Success) return null;
        var (from, to) = (int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value));
        return from < to && to < 1900 ? (from, to) : null;
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

    public static string KindOf(int category)
    {
        if (category is 201 or 202 or 204 or 207 or 209) return "movie";
        if (category is 205 or 208) return "tv";
        return "other";
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

    static string MagnetOf(string hash, string name) =>
        TitleText.WithTrackers($"magnet:?xt=urn:btih:{hash}&dn={Uri.EscapeDataString(name)}");

    static string Str(JsonNode? node) => node switch
    {
        null => "",
        JsonValue v when v.TryGetValue<string>(out var s) => s,
        _ => node.ToString(),
    };

    static double Num(JsonNode? node) =>
        double.TryParse(Str(node), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var d) ? d : 0;

    public static List<Hit> ToHits(JsonArray rows) => rows
        .Where(r => Str(r?["id"]) != "0" && IsHash(Str(r?["info_hash"])))
        .Where(r => Num(r!["category"]) is >= 200 and < 300)
        .Select(r =>
        {
            var name = Str(r!["name"]);
            var hash = Str(r["info_hash"]).ToLowerInvariant();
            var imdb = Str(r["imdb"]);
            return new Hit
            {
                Id = hash,
                Name = name,
                Magnet = MagnetOf(hash, name),
                Seeders = (int)Num(r["seeders"]),
                Leechers = (int)Num(r["leechers"]),
                Size = (long)Num(r["size"]),
                Quality = Quality(name),
                Scope = ScopeOf(name),
                Kind = KindOf((int)Num(r["category"])),
                Imdb = Regex.IsMatch(imdb, @"^tt\d+$") ? imdb : null,
            };
        })
        .ToList();

    /// <summary>Order by how well the swarm will actually serve you.</summary>
    public static List<Hit> Rank(IEnumerable<Hit> hits, string query = "")
    {
        var words = query.ToLowerInvariant().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        double Score(Hit h)
        {
            var full = words.Length > 0 && words.All(w => h.Name.ToLowerInvariant().Contains(w));
            return (h.Seeders + h.Leechers / 4.0) * (full ? 2 : 1);
        }
        return hits.OrderByDescending(Score).ToList();
    }

    /// <summary>This season's pack first (fast enough to open). Whole-series dumps last.</summary>
    public static List<Hit> RankEpisode(IEnumerable<Hit> hits, string query = "", int? season = null)
    {
        var ordered = Rank(hits, query);
        bool ThisPack(Hit h)
        {
            if (h.Seeders < 1 || h.Scope != "season") return false;
            var c = SeasonsIn(h.Name);
            return c.Count <= 1 && (season == null || c.Count == 0 || c.Contains(season.Value));
        }
        bool One(Hit h) => h.Scope == "episode" && h.Seeders >= 1;
        return ordered.Where(ThisPack)
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

    public static List<Hit> ToTorrentioHits(JsonArray streams, string kind) => streams
        .Where(s => IsHash(Str(s?["infoHash"])))
        .Select(s =>
        {
            var lines = Str(s!["title"]).Split('\n');
            var name = lines.FirstOrDefault()?.Trim();
            if (string.IsNullOrEmpty(name)) name = Str(s["name"]).Replace('\n', ' ');
            var stats = string.Join(' ', lines.Skip(1));
            var size = SizeRx.Match(stats);
            var seeds = SeedersRx.Match(stats);
            var hash = Str(s["infoHash"]).ToLowerInvariant();
            return new Hit
            {
                Id = hash,
                Name = name,
                Magnet = MagnetOf(hash, name),
                Seeders = seeds.Success ? int.Parse(seeds.Groups[1].Value) : 0,
                Leechers = 0,
                Size = size.Success
                    ? (long)Math.Round(double.Parse(size.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) * Unit(size.Groups[2].Value))
                    : 0,
                Quality = Quality(name),
                Scope = ScopeOf(name),
                Kind = kind,
                PerFileSize = true,
            };
        })
        .Where(h => h.Name.Length > 0)
        .ToList();

    public static List<Hit> ToYtsHits(JsonArray movies)
    {
        var @out = new List<Hit>();
        foreach (var movie in movies)
        {
            if (movie is null) continue;
            var title = Str(movie["title_long"]);
            if (title.Length == 0) title = Str(movie["title"]);
            var imdbCode = Str(movie["imdb_code"]);
            var imdb = Regex.IsMatch(imdbCode, @"^tt\d+$") ? imdbCode : null;
            if (movie["torrents"] is not JsonArray torrents) continue;
            foreach (var t in torrents)
            {
                var hash = Str(t?["hash"]).ToLowerInvariant();
                if (!IsHash(hash)) continue;
                var name = string.Join(' ', new[] { title, Str(t!["quality"]), Str(t["type"]) }.Where(x => x.Length > 0));
                @out.Add(new Hit
                {
                    Id = hash,
                    Name = name,
                    Magnet = MagnetOf(hash, name),
                    Seeders = (int)Num(t["seeds"]),
                    Leechers = (int)Num(t["peers"]),
                    Size = (long)Num(t["size_bytes"]),
                    Quality = Quality(name),
                    Scope = "episode",
                    Kind = "movie",
                    Imdb = imdb,
                });
            }
        }
        return @out;
    }

    public static List<Hit> ToEztvHits(JsonArray torrents) => torrents
        .Where(t => IsHash(Str(t?["hash"])))
        .Select(t =>
        {
            var name = Str(t!["title"]);
            var hash = Str(t["hash"]).ToLowerInvariant();
            return new Hit
            {
                Id = hash,
                Name = name,
                Magnet = MagnetOf(hash, name),
                Seeders = (int)Num(t["seeds"]),
                Leechers = (int)Num(t["peers"]),
                Size = (long)Num(t["size_bytes"]),
                Quality = Quality(name),
                Scope = ScopeOf(name),
                Kind = "tv",
            };
        })
        .Where(h => h.Name.Length > 0)
        .ToList();

    public static List<Hit> ToCsvHits(JsonArray rows) => rows
        .Where(r => IsHash(Str(r?["infohash"] ?? r?["info_hash"])))
        .Select(r =>
        {
            var name = Str(r!["name"]);
            var hash = Str(r["infohash"] ?? r["info_hash"]).ToLowerInvariant();
            return new Hit
            {
                Id = hash,
                Name = name,
                Magnet = MagnetOf(hash, name),
                Seeders = (int)Num(r["seeders"]),
                Leechers = (int)Num(r["leechers"]),
                Size = (long)Num(r["size_bytes"] ?? r["size"]),
                Quality = Quality(name),
                Scope = ScopeOf(name),
                Kind = ParseEpisode(name) is null ? "movie" : "tv",
            };
        })
        .Where(h => h.Name.Length > 0)
        .ToList();

    /// <summary>Same torrent from two indexes is one row — keep whichever knew more.</summary>
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

    static async Task<List<Hit>> FromTorrentio(string path, string kind, CancellationToken ct)
    {
        var body = await Net.Json($"{Torrentio}/{path}.json", ct, true);
        return body?["streams"] is JsonArray streams ? ToTorrentioHits(streams, kind) : new();
    }

    static async Task<List<Hit>> FromTorrentsDb(string path, string kind, CancellationToken ct)
    {
        var body = await Net.Json($"{TorrentsDb}/{path}.json", ct, true);
        return body?["streams"] is JsonArray streams ? ToTorrentioHits(streams, kind) : new();
    }

    static async Task<List<Hit>> FromCsv(string query, CancellationToken ct)
    {
        var body = await Net.Json($"{Csv}?q={Uri.EscapeDataString(query)}&size=50", ct, true);
        var rows = body?["torrents"] as JsonArray ?? body as JsonArray;
        return rows is null ? new() : ToCsvHits(rows);
    }

    static async Task<List<Hit>> FromBayOnly(string query, CancellationToken ct)
    {
        var body = await Net.Json($"{Api}?q={Uri.EscapeDataString(query)}&cat=200", ct, true);
        return body is JsonArray rows ? ToHits(rows) : new List<Hit>();
    }

    /// <summary>How long one index gets in a search before it is left out of the answer.</summary>
    const int SourceMs = 4000;

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
        try
        {
            var @out = await job;
            Log($"{name} {sw.ElapsedMilliseconds}ms n={@out.Count}");
            return @out;
        }
        catch (Exception e)
        {
            Log($"{name} {sw.ElapsedMilliseconds}ms FAIL {e.Message}");
            throw;
        }
    }

    static async Task<List<Hit>> FromYts(string query, CancellationToken ct)
    {
        foreach (var host in YtsHosts)
        {
            try
            {
                var body = await Net.Json($"{host}/api/v2/list_movies.json?query_term={Uri.EscapeDataString(query)}&limit=50", ct, true);
                return body?["data"]?["movies"] is JsonArray movies ? ToYtsHits(movies) : new();
            }
            catch when (!ct.IsCancellationRequested)
            {
                // Blocked or down here: the next mirror.
            }
        }
        return new();
    }

    static Hit Row(string hash, string name, int seeders, int leechers, long size, string? kind = null) => new()
    {
        Id = hash.ToLowerInvariant(),
        Name = name,
        Magnet = MagnetOf(hash.ToLowerInvariant(), name),
        Seeders = seeders,
        Leechers = leechers,
        Size = size,
        Quality = Quality(name),
        Scope = ScopeOf(name),
        Kind = kind ?? (ParseEpisode(name) is null ? "movie" : "tv"),
    };

    /// <summary>A category these indexes use for video.</summary>
    static bool VideoCategory(string category) =>
        Regex.IsMatch(category, @"^\s*(movies?|tv|anime)\b", I);

    /// <summary>For an index with no usable category: the name reads like a video release.</summary>
    static bool LooksLikeVideo(string name) =>
        Quality(name) is not null || ParseEpisode(name) is not null ||
        Regex.IsMatch(name, @"\b(x26[45]|h\.?26[45]|hevc|xvid|web-?dl|webrip|blu-?ray|bdrip|brrip|hdtv|dvdrip|hdrip|remux|mkv|mp4|avi)\b", I);

    static async Task<List<Hit>> FromKnaben(string query, CancellationToken ct)
    {
        var request = new JsonObject
        {
            ["search_field"] = "title",
            ["query"] = query,
            ["order_by"] = "seeders",
            ["order_direction"] = "desc",
            ["size"] = 100,
            ["hide_unsafe"] = true,
            ["hide_xxx"] = true,
        };
        using var content = new System.Net.Http.StringContent(request.ToJsonString(), System.Text.Encoding.UTF8, "application/json");
        using var res = await Net.Http.PostAsync(Knaben, content, ct);
        res.EnsureSuccessStatusCode();
        var body = JsonNode.Parse(await res.Content.ReadAsStringAsync(ct));
        return body?["hits"] is JsonArray hits
            ? hits.Where(h => h is not null && IsHash(Str(h["hash"])) && VideoCategory(Str(h["category"])))
                .Select(h => Row(Str(h!["hash"]), Str(h["title"]), (int)Num(h["seeders"]), (int)Num(h["peers"]), (long)Num(h["bytes"]),
                    Str(h["category"]).StartsWith("TV", StringComparison.OrdinalIgnoreCase) ? "tv" : null))
                .ToList()
            : new();
    }

    static async Task<List<Hit>> FromBitsearch(string query, CancellationToken ct)
    {
        var body = await Net.Json($"{Bitsearch}?q={Uri.EscapeDataString(query)}", ct, true);
        return body?["results"] is JsonArray rows
            ? rows.Where(r => r is not null && IsHash(Str(r["infohash"])) && LooksLikeVideo(Str(r["title"])))
                .Select(r => Row(Str(r!["infohash"]), Str(r["title"]), (int)Num(r["seeders"]), (int)Num(r["leechers"]), (long)Num(r["size"])))
                .ToList()
            : new();
    }

    static async Task<List<Hit>> FromTheRarbg(string query, CancellationToken ct)
    {
        var body = await Net.Json($"{TheRarbg}{Uri.EscapeDataString(query)}/?format=json", ct, true);
        return body?["results"] is JsonArray rows
            ? rows.Where(r => r is not null && IsHash(Str(r["h"])) && VideoCategory(Str(r["c"])))
                .Select(r => Row(Str(r!["h"]), Str(r["n"]), (int)Num(r["se"]), (int)Num(r["le"]), (long)Num(r["s"]),
                    Str(r["c"]).Equals("TV", StringComparison.OrdinalIgnoreCase) ? "tv" : null))
                .ToList()
            : new();
    }

    /// <summary>nyaa.si, for anime: its RSS carries the info hash, seeders and a size like "1.2 GiB".</summary>
    static async Task<List<Hit>> FromNyaa(string query, CancellationToken ct)
    {
        using var res = await Net.Send(Nyaa + Uri.EscapeDataString(query), ct, true);
        res.EnsureSuccessStatusCode();
        var xml = System.Xml.Linq.XDocument.Parse(await res.Content.ReadAsStringAsync(ct));
        System.Xml.Linq.XNamespace nyaa = "https://nyaa.si/xmlns/nyaa";
        long SizeOf(string text)
        {
            var m = Regex.Match(text, @"([\d.]+)\s*([KMGT])i?B", I);
            return m.Success && double.TryParse(m.Groups[1].Value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var n)
                ? (long)(n * Unit(m.Groups[2].Value + "b"))
                : 0;
        }
        return xml.Descendants("item")
            .Select(item => (Name: (string?)item.Element("title") ?? "", Hash: (string?)item.Element(nyaa + "infoHash") ?? "",
                Seeders: (int?)item.Element(nyaa + "seeders") ?? 0, Leechers: (int?)item.Element(nyaa + "leechers") ?? 0, Size: (string?)item.Element(nyaa + "size") ?? ""))
            .Where(x => IsHash(x.Hash) && x.Name.Length > 0)
            .Select(x => Row(x.Hash, x.Name, x.Seeders, x.Leechers, SizeOf(x.Size), "tv"))
            .ToList();
    }

    static async Task<List<Hit>> FromEztv(string imdb, CancellationToken ct)
    {
        var body = await Net.Json($"{Eztv}?imdb_id={imdb.Replace("tt", "")}&limit=100", ct, true);
        return body?["torrents"] is JsonArray torrents ? ToEztvHits(torrents) : new();
    }

    static readonly Dictionary<string, Task<List<Hit>>> EztvMemo = new();

    static Task<List<Hit>> EztvFor(string imdb)
    {
        var id = imdb.Replace("tt", "");
        lock (EztvMemo)
        {
            if (!EztvMemo.TryGetValue(id, out var pending))
            {
                pending = FromEztv(imdb, default).ContinueWith(t => t.IsCompletedSuccessfully ? t.Result : new List<Hit>());
                EztvMemo[id] = pending;
            }
            return pending;
        }
    }

    /// <summary>"Dune: Part One" is released as "Dune 2021" — the part before the subtitle.</summary>
    public static string? AliasOf(string title)
    {
        var @short = Regex.Split(title, ":| - ")[0].Trim();
        return @short.Length > 0 && !@short.Equals(title.Trim(), StringComparison.OrdinalIgnoreCase) ? @short : null;
    }

    static List<string> Tokens(string s) =>
        Regex.Replace(s.ToLowerInvariant(), "[^a-z0-9]+", " ").Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();

    /// <summary>Does this release name every word of the title?</summary>
    public static bool NamesTitle(string release, string title)
    {
        var want = Tokens(title);
        if (want.Count == 0) return true;
        var got = Tokens(release).ToHashSet();
        return want.All(got.Contains);
    }

    static readonly Regex ReleaseMark = new(
        @"^(?:s\d{1,2}(?:e\d{1,3})?|e\d{1,3}|\d{1,2}x\d{2}|(?:19|20)\d{2}|season|seasons|complete|series|\d{3,4}p|4k|uhd|hdr|bluray|blu|bdrip|brrip|web|webrip|webdl|hdtv|dvdrip|dvd|x264|x265|h264|h265|hevc|avc|xvid|aac|ac3|eac3|dts|ddp?5|truehd|atmos|remux|proper|repack|extended|imax|uncut|limited|internal|multi|dual|hindi|eng|english|subbed|dubbed)$",
        I);

    public static List<string> ReleaseTitle(string release)
    {
        var @out = new List<string>();
        foreach (var word in Tokens(release))
        {
            if (ReleaseMark.IsMatch(word)) break;
            @out.Add(word);
        }
        return @out;
    }

    /// <summary>This release is *this* title, not one whose name merely contains it.</summary>
    public static bool SameTitle(string release, string title)
    {
        var want = Tokens(title);
        if (want.Count == 0) return true;
        // Anime leads with the group — "[SubsPlease] Jujutsu Kaisen - 29" — and ends the title with the episode.
        var got = ReleaseTitle(Regex.Replace(release, @"^\s*(?:\[[^\]]*\]\s*)+", ""));
        if (got.Count < want.Count) return false;
        for (var i = 0; i < want.Count; i++) if (got[i] != want[i]) return false;
        return got.Skip(want.Count).All(w => w.Length <= 2 || w.All(char.IsDigit));
    }

    /// <summary>
    /// A release naming a year names the film's (give or take one, for festival vs. release
    /// dates): "Mad Max 1979" is not Fury Road (2015). Years that are part of the title
    /// ("Blade Runner 2049") don't count, and a release with no year is given the benefit.
    /// </summary>
    public static bool FitsYear(string release, string title, string year)
    {
        if (!int.TryParse(year, out var want)) return true;
        var inTitle = Tokens(title).ToHashSet();
        var years = Tokens(release).Where(t => Regex.IsMatch(t, @"^(?:19|20)\d{2}$") && !inTitle.Contains(t)).Select(int.Parse).ToList();
        return years.Count == 0 || years.Any(y => Math.Abs(y - want) <= 1);
    }

    /// <summary>
    /// Search public indexes for video torrents. <paramref name="progress"/>, if given, gets the
    /// merged list so far each time another index answers — so results can show as they arrive.
    /// </summary>
    public static async Task<List<Hit>> SearchTorrents(
        string title, CancellationToken ct = default, string? imdb = null, string? year = null, string? suffix = null,
        Action<List<Hit>>? progress = null, Lang? lang = null)
    {
        // A language is asked for by name ("Hindi"), and only releases that say it count.
        var tail = string.Join(' ', new[] { suffix, lang?.Word, year }.Where(x => !string.IsNullOrEmpty(x)));
        var full = string.Join(' ', new[] { title, tail }.Where(x => !string.IsNullOrEmpty(x)));
        var alias = AliasOf(title);
        var sw = Stopwatch.StartNew();
        Log($"torrents start \"{full}\" imdb={imdb ?? "-"}");

        // Every index at once, each given a few seconds; the same torrent from several is one row
        // (Merge, by info hash). Asking them in turn and stopping at the first answer left most
        // releases unseen — apibay alone, unless it failed.
        List<Hit> None() => new();
        Task<List<Hit>> Ask(string source, Task<List<Hit>> job, int ms = SourceMs) => Timed(source, Limited(job, ms, None()));
        var aliasQuery = alias is null ? null : string.Join(' ', new[] { alias, tail }.Where(x => !string.IsNullOrEmpty(x)));
        // The alias ("Dune" for "Dune: Part One") catches differently named releases; with a year, only that year's.
        var jobs = new List<(Task<List<Hit>> Job, bool Alias)>
        {
            (Ask("apibay", FromBayOnly(full, ct)), false),
            (Ask("torrents-csv", FromCsv(full, ct)), false),
            (Ask("knaben", FromKnaben(full, ct)), false),
            (Ask("bitsearch", FromBitsearch(full, ct)), false),
            (Ask("therarbg", FromTheRarbg(full, ct)), false),
            (Ask("nyaa", FromNyaa(full, ct)), false),
        };
        if (aliasQuery is not null)
        {
            jobs.Add((Ask("apibay-alias", FromBayOnly(aliasQuery, ct)), true));
            jobs.Add((Ask("knaben-alias", FromKnaben(aliasQuery, ct)), true));
        }
        if (string.IsNullOrEmpty(suffix))
        {
            jobs.Add((Ask("yts", FromYts(imdb ?? full, ct)), false)); // films only — no use for an episode tag
            if (imdb is not null)
            {
                jobs.Add((Ask("torrentio", FromTorrentio($"movie/{imdb}", "movie", ct), SourceMs + 2000), false));
                jobs.Add((Ask("torrentsdb", FromTorrentsDb($"movie/{imdb}", "movie", ct), SourceMs + 2000), false));
            }
        }

        List<Hit> Combine(IEnumerable<List<Hit>> bags)
        {
            var merged = imdb is not null
                ? Merge(bags).Where(h => h.Imdb is null || h.Imdb == imdb).ToList()
                : Merge(bags);
            var found = Rank(merged, full);
            if (lang is not null) found = found.Where(lang.Says).ToList();
            if (year is not null) found = found.Where(h => FitsYear(h.Name, title, year)).ToList();
            var exact = found.Where(h => SameTitle(h.Name, title) || (alias is not null && SameTitle(h.Name, alias))).ToList();
            var named = found.Where(h => NamesTitle(h.Name, title) || (alias is not null && NamesTitle(h.Name, alias))).ToList();
            return exact.Count > 0 ? exact : named.Count > 0 ? named : found;
        }

        var arrived = new List<List<Hit>>();
        var pending = jobs.ToList();
        while (pending.Count > 0)
        {
            var done = await Task.WhenAny(pending.Select(p => p.Job));
            var which = pending.First(p => p.Job == done);
            pending.Remove(which);
            var hits = await done; // Limited: never throws
            if (which.Alias && year is not null) hits = hits.Where(h => h.Name.Contains(year)).ToList();
            arrived.Add(hits);
            if (progress is not null && hits.Count > 0 && pending.Count > 0 && !ct.IsCancellationRequested)
                progress(Combine(arrived));
        }

        var @out = Combine(arrived);
        Log($"torrents done {sw.ElapsedMilliseconds}ms n={@out.Count}");
        return @out;
    }

    static readonly Dictionary<string, Task<List<Hit>>> EpisodeMemo = new();

    /// <summary>Every release that can play one episode — including the packs.</summary>
    public static Task<List<Hit>> SearchEpisode(string show, int season, int episode, CancellationToken ct = default, string? imdb = null, int? absolute = null, Lang? lang = null)
    {
        var key = $"{imdb ?? show}:{season}:{episode}:{lang?.Code}";
        lock (EpisodeMemo)
        {
            if (EpisodeMemo.TryGetValue(key, out var cached)) return cached;
            var pending = FindEpisode(show, season, episode, ct, imdb, absolute, lang).ContinueWith(t =>
            {
                var hits = t.IsCompletedSuccessfully ? t.Result : new List<Hit>();
                if (hits.Count == 0) lock (EpisodeMemo) EpisodeMemo.Remove(key);
                return hits;
            });
            EpisodeMemo[key] = pending;
            return pending;
        }
    }

    static async Task<List<Hit>> FindEpisode(string show, int season, int episode, CancellationToken ct, string? imdb, int? absolute, Lang? lang)
    {
        bool Says(Hit h) => lang is null || lang.Says(h);
        bool Plays(Hit h)
        {
            if (!Says(h)) return false;
            if (NamesEpisode(h.Name, season, episode, absolute) is bool one) return one;
            // Filed as a film and naming no episode: "Jujutsu Kaisen 0" is not a pack of the series.
            // (Anime episodes are often filed as films too, so only the unlabelled ones go.)
            if (h.Kind == "movie") return false;
            var claims = SeasonsIn(h.Name);
            // An unlabelled anime batch says which episodes it holds.
            if (claims.Count == 0 && EpisodeSpan(h.Name) is { } span)
            {
                var n = absolute ?? episode;
                return n >= span.From && n <= span.To;
            }
            return claims.Count == 0 || claims.Contains(season);
        }

        var sw = Stopwatch.StartNew();
        var tag = EpisodeTag(season, episode);
        Log($"episode start \"{show}\" {tag} abs={absolute?.ToString() ?? "-"} imdb={imdb ?? "-"}");

        var looseJob = SearchTorrents(show, ct, imdb, suffix: tag, lang: lang);
        var packsJob = SearchTorrents(show, ct, imdb, lang: lang);
        // Anime is indexed by its running number, not S02E05.
        var animeJob = SearchTorrents(show, ct, imdb, suffix: (absolute ?? episode).ToString("00"), lang: lang);
        List<Hit> loose, packs, anime;
        try { loose = await looseJob; } catch { loose = new(); }
        try { packs = await packsJob; } catch { packs = new(); }
        try { anime = await animeJob; } catch { anime = new(); }

        var bayHits = RankEpisode(Merge(new[] { loose, anime, packs }).Where(Plays), show, season);
        if (bayHits.Count > 0)
        {
            Log($"episode done {sw.ElapsedMilliseconds}ms n={bayHits.Count} bay");
            return bayHits;
        }
        if (ct.IsCancellationRequested) return new();

        if (imdb is not null)
        {
            var tioJob = Timed("torrentio", Limited(FromTorrentio($"series/{imdb}:{season}:{episode}", "tv", ct), FastMs * 3, new List<Hit>()));
            var dbJob = Timed("torrentsdb", Limited(FromTorrentsDb($"series/{imdb}:{season}:{episode}", "tv", ct), FastMs * 3, new List<Hit>()));
            var tio = Merge(new[] { await tioJob, await dbJob }).Where(Says).ToList();
            if (tio.Count > 0) return RankEpisode(tio, show, season);
            if (ct.IsCancellationRequested) return new();
            var eztv = (await Timed("eztv", Limited(EztvFor(imdb), FastMs * 3, new List<Hit>()))).Where(Plays).ToList();
            if (eztv.Count > 0) return RankEpisode(eztv, show, season);
        }
        Log($"episode done {sw.ElapsedMilliseconds}ms n=0");
        return new();
    }
}
