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

public static class Search
{
    const string Api = "https://apibay.org/q.php";
    const string Csv = "https://torrents-csv.com/service/search";
    const string Torrentio = "https://torrentio.strem.fun/stream";
    const string Yts = "https://yts.mx/api/v2/list_movies.json";
    const string Eztv = "https://eztv.tf/api/get-torrents";
    const int FastMs = 2000;

    static void Log(string message) => Debug.WriteLine($"[releases] {message}");

    const RegexOptions I = RegexOptions.IgnoreCase;

    public static string EpisodeTag(int season, int episode) => $"S{season:00}E{episode:00}";

    public static (int Season, int Episode)? ParseEpisode(string? name)
    {
        if (string.IsNullOrEmpty(name)) return null;
        var m = Regex.Match(name, @"\bs(\d{1,2})[ ._-]*e(\d{1,2})\b", I);
        if (!m.Success) m = Regex.Match(name, @"\b(\d{1,2})x(\d{1,2})\b", I);
        return m.Success ? (int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value)) : null;
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

    static async Task<List<Hit>> FromCsv(string query, CancellationToken ct)
    {
        var body = await Net.Json($"{Csv}?q={Uri.EscapeDataString(query)}&size=50", ct, true);
        var rows = body?["torrents"] as JsonArray ?? body as JsonArray;
        return rows is null ? new() : ToCsvHits(rows);
    }

    /// <summary>apibay first; torrents-csv when the host is blocked.</summary>
    static async Task<List<Hit>> FromBay(string query, CancellationToken ct)
    {
        var hits = await Limited(Task.Run(async () =>
        {
            var body = await Net.Json($"{Api}?q={Uri.EscapeDataString(query)}&cat=200", ct, true);
            return body is JsonArray rows ? ToHits(rows) : new List<Hit>();
        }, ct), 1200, new List<Hit>());
        if (hits.Count > 0 || ct.IsCancellationRequested) return hits;
        return await Limited(FromCsv(query, ct), 2000, new List<Hit>());
    }

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

    /// <summary>First source that actually has rows.</summary>
    static async Task<List<Hit>> FirstHits(IEnumerable<Task<List<Hit>>> jobs)
    {
        var pending = jobs.ToList();
        while (pending.Count > 0)
        {
            var done = await Task.WhenAny(pending);
            pending.Remove(done);
            try
            {
                var hits = await done;
                if (hits.Count > 0) return hits;
            }
            catch
            {
                // Try the next one.
            }
        }
        return new();
    }

    static async Task<List<Hit>> FromYts(string query, CancellationToken ct)
    {
        var body = await Net.Json($"{Yts}?query_term={Uri.EscapeDataString(query)}&limit=50", ct, true);
        return body?["data"]?["movies"] is JsonArray movies ? ToYtsHits(movies) : new();
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
        var got = ReleaseTitle(release);
        if (got.Count < want.Count) return false;
        for (var i = 0; i < want.Count; i++) if (got[i] != want[i]) return false;
        return got.Skip(want.Count).All(w => w.Length <= 2);
    }

    /// <summary>Search public indexes for video torrents.</summary>
    public static async Task<List<Hit>> SearchTorrents(
        string title, CancellationToken ct = default, string? imdb = null, string? year = null, string? suffix = null)
    {
        var tail = string.Join(' ', new[] { suffix, year }.Where(x => !string.IsNullOrEmpty(x)));
        var full = string.Join(' ', new[] { title, tail }.Where(x => !string.IsNullOrEmpty(x)));
        var alias = AliasOf(title);
        var sw = Stopwatch.StartNew();
        Log($"torrents start \"{full}\" imdb={imdb ?? "-"}");

        var primaryJob = Timed("apibay", FromBay(full, ct));
        var aliasJob = alias is not null
            ? Timed("apibay-alias", FromBay(string.Join(' ', new[] { alias, tail }.Where(x => !string.IsNullOrEmpty(x))), ct))
            : Task.FromResult(new List<Hit>());
        List<Hit> primary, aliased;
        try { primary = await primaryJob; } catch { primary = new(); }
        try { aliased = await aliasJob; } catch { aliased = new(); }

        var bags = new List<List<Hit>>
        {
            primary,
            year is not null ? aliased.Where(h => h.Name.Contains(year)).ToList() : aliased,
        };

        if (!bags.Any(b => b.Count > 0) && string.IsNullOrEmpty(suffix))
        {
            Log("torrents fallback yts/torrentio");
            var jobs = new List<Task<List<Hit>>> { Timed("yts", Limited(FromYts(imdb ?? full, ct), FastMs, new List<Hit>())) };
            if (imdb is not null)
                jobs.Add(Timed("torrentio", Limited(FromTorrentio($"movie/{imdb}", "movie", ct), FastMs * 3, new List<Hit>())));
            var fast = await FirstHits(jobs);
            if (fast.Count > 0) bags.Add(fast);
        }

        var merged = imdb is not null
            ? Merge(bags).Where(h => h.Imdb is null || h.Imdb == imdb).ToList()
            : Merge(bags);

        var found = Rank(merged, full);
        var name = alias ?? title;
        var exact = found.Where(h => SameTitle(h.Name, name)).ToList();
        var named = found.Where(h => NamesTitle(h.Name, name)).ToList();
        var @out = exact.Count > 0 ? exact : named.Count > 0 ? named : found;
        Log($"torrents done {sw.ElapsedMilliseconds}ms n={@out.Count}");
        return @out;
    }

    static readonly Dictionary<string, Task<List<Hit>>> EpisodeMemo = new();

    /// <summary>Every release that can play one episode — including the packs.</summary>
    public static Task<List<Hit>> SearchEpisode(string show, int season, int episode, CancellationToken ct = default, string? imdb = null)
    {
        var key = $"{imdb ?? show}:{season}:{episode}";
        lock (EpisodeMemo)
        {
            if (EpisodeMemo.TryGetValue(key, out var cached)) return cached;
            var pending = FindEpisode(show, season, episode, ct, imdb).ContinueWith(t =>
            {
                var hits = t.IsCompletedSuccessfully ? t.Result : new List<Hit>();
                if (hits.Count == 0) lock (EpisodeMemo) EpisodeMemo.Remove(key);
                return hits;
            });
            EpisodeMemo[key] = pending;
            return pending;
        }
    }

    static async Task<List<Hit>> FindEpisode(string show, int season, int episode, CancellationToken ct, string? imdb)
    {
        bool Plays(Hit h)
        {
            var ep = ParseEpisode(h.Name);
            if (ep is not null) return ep.Value.Season == season && ep.Value.Episode == episode;
            var claims = SeasonsIn(h.Name);
            return claims.Count == 0 || claims.Contains(season);
        }

        var sw = Stopwatch.StartNew();
        var tag = EpisodeTag(season, episode);
        Log($"episode start \"{show}\" {tag} imdb={imdb ?? "-"}");

        var looseJob = SearchTorrents(show, ct, imdb, suffix: tag);
        var packsJob = SearchTorrents(show, ct, imdb);
        List<Hit> loose, packs;
        try { loose = await looseJob; } catch { loose = new(); }
        try { packs = await packsJob; } catch { packs = new(); }

        var bayHits = RankEpisode(Merge(new[] { loose, packs }).Where(Plays), show, season);
        if (bayHits.Count > 0)
        {
            Log($"episode done {sw.ElapsedMilliseconds}ms n={bayHits.Count} bay");
            return bayHits;
        }
        if (ct.IsCancellationRequested) return new();

        if (imdb is not null)
        {
            var tio = await Timed("torrentio", Limited(FromTorrentio($"series/{imdb}:{season}:{episode}", "tv", ct), FastMs * 3, new List<Hit>()));
            if (tio.Count > 0) return RankEpisode(Merge(new[] { tio }), show, season);
            if (ct.IsCancellationRequested) return new();
            var eztv = (await Timed("eztv", Limited(EztvFor(imdb), FastMs * 3, new List<Hit>()))).Where(Plays).ToList();
            if (eztv.Count > 0) return RankEpisode(eztv, show, season);
        }
        Log($"episode done {sw.ElapsedMilliseconds}ms n=0");
        return new();
    }
}
