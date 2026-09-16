using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

public sealed record Episode
{
    /// <summary>Cinemeta's own "tt10986410:1:2".</summary>
    public string Id { get; init; } = "";
    public int Season { get; init; }
    public int Number { get; init; }
    public string Name { get; init; } = "";
    public string? Overview { get; init; }
    public string? Thumbnail { get; init; }
    public string? Released { get; init; }
}

/// <summary>A browsable title. See src/lib/meta.ts.</summary>
public sealed record Title
{
    public string Imdb { get; init; } = "";
    /// <summary>movie | tv</summary>
    public string Kind { get; init; } = "movie";
    public string Name { get; init; } = "";
    public string? Year { get; init; }
    public string? Rating { get; init; }
    public List<string> Genres { get; init; } = new();
    public string? Poster { get; init; }
    public string? Plot { get; init; }
    public List<string> Cast { get; init; } = new();
    public List<string> Directors { get; init; } = new();
    public string? Awards { get; init; }
    public string? Backdrop { get; init; }
    public string? Runtime { get; init; }
    public List<Episode> Episodes { get; init; } = new();
}

public sealed record Shelf(string Heading, string Kind, string? Genre = null)
{
    public string Key => $"{Kind}:{Genre}";
}

/// <summary>Title metadata from Stremio's public Cinemeta service — no API key needed.</summary>
public static class Meta
{
    const string Base = "https://v3-cinemeta.strem.io/meta";
    const string Catalog = "https://cinemeta-catalogs.strem.io/top/catalog";
    const string SearchUrl = "https://v3-cinemeta.strem.io/catalog";
    const string Anime = "https://anime-kitsu.strem.fun/catalog/series/kitsu-anime-trending.json";

    public const string AnimeGenre = "Anime";

    public static readonly string[] Genres =
    {
        AnimeGenre, "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family",
        "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western",
    };

    public static string KindOfGenre(string genre) => genre == AnimeGenre ? "tv" : "movie";

    static readonly ConcurrentDictionary<string, Task<object?>> Cache = new();
    static readonly TimeSpan Ttl = TimeSpan.FromDays(1);
    const string StorePrefix = "meta3-";

    sealed record Stored<T>(long At, T Value);

    /// <summary>Memory cache over a disk cache over the network; a stale copy beats an error.</summary>
    static async Task<T> Remember<T>(string key, Func<Task<T>> fetcher)
    {
        var storeKey = StorePrefix + key;
        var stored = Store.Load<Stored<T>?>(storeKey, null);
        if (stored is not null && Store.Now - stored.At < Ttl.TotalMilliseconds) return stored.Value;
        try
        {
            var value = await fetcher();
            try { Store.Save(storeKey, new Stored<T>(Store.Now, value)); } catch { }
            return value;
        }
        catch when (stored is not null)
        {
            return stored.Value;
        }
    }

    static Task<T> Once<T>(string key, Func<Task<T>> make, T onError)
    {
        var task = Cache.GetOrAdd(key, _ => Wrap());
        return task.ContinueWith(t => t.Result is T v ? v : onError, TaskScheduler.Default);

        async Task<object?> Wrap()
        {
            try
            {
                return await make();
            }
            catch
            {
                // A miss must not be cached as permanent — a dropped connection deserves another go.
                Cache.TryRemove(key, out _);
                return onError;
            }
        }
    }

    public static void ClearCache()
    {
        Cache.Clear();
        Settled.Clear();
        Store.RemovePrefix("meta");
    }

    public static List<Title> UniqueById(IEnumerable<Title> titles)
    {
        var seen = new HashSet<string>();
        return titles.Where(t => !string.IsNullOrEmpty(t.Imdb) && seen.Add(t.Imdb)).ToList();
    }

    public static Task<Title?> LoadTitle(string imdb, string kind) =>
        Once<Title?>($"title-{kind}-{imdb}", () => Remember($"title-{kind}-{imdb}", async () =>
        {
            using var res = await Net.Send($"{Base}/{(kind == "tv" ? "series" : "movie")}/{imdb}.json");
            if (!res.IsSuccessStatusCode) return null;
            var body = JsonNode.Parse(await res.Content.ReadAsStringAsync());
            if (body?["meta"] is not JsonObject meta) return null;
            meta["imdb_id"] = imdb;
            return ToTitles(new JsonArray(meta.DeepClone()), kind, true).FirstOrDefault();
        }), null);

    static readonly ConcurrentDictionary<string, List<Title>> Settled = new();

    public static List<Title>? PeekCatalog(string kind, string? genre) =>
        Settled.TryGetValue($"shelf-{kind}-{genre}", out var v) ? v : null;

    public static Task<List<Title>> LoadCatalog(string kind, string? genre = null)
    {
        var key = $"shelf-{kind}-{genre}";
        var type = kind == "tv" ? "series" : "movie";
        // Cinemeta pages by 50: top/genre=Action&skip=50.json, or top/skip=50.json without a genre.
        string Page(int skip)
        {
            var extras = new[] { genre is null ? null : "genre=" + Uri.EscapeDataString(genre), skip > 0 ? $"skip={skip}" : null }.Where(e => e is not null);
            var extra = string.Join("&", extras);
            return $"{Catalog}/{type}/top{(extra.Length > 0 ? "/" + extra : "")}.json";
        }
        return Once(key, async () =>
        {
            // Three pages (150 titles) so the day's picks have something to rotate through.
            var all = await Remember("pool-" + key, async () =>
            {
                var pages = genre == AnimeGenre ? new[] { Anime } : new[] { Page(0), Page(50), Page(100) };
                var bodies = await Task.WhenAll(pages.Select(async page => { try { return await Net.Json(page); } catch { return null; } }));
                var titles = bodies.SelectMany(b => b?["metas"] is JsonArray metas ? ToTitles(metas, kind) : new List<Title>()).ToList();
                if (titles.Count == 0) throw new InvalidOperationException("empty shelf");
                return titles;
            });
            var titles = UniqueById(all);
            if (titles.Count == 0) throw new InvalidOperationException("empty shelf");
            Settled[key] = titles;
            return titles;
        }, new List<Title>());
    }

    public static Task<List<Title>> SearchTitles(string query, string kind)
    {
        var type = kind == "tv" ? "series" : "movie";
        return Once($"find-{kind}-{query.ToLowerInvariant()}", async () =>
        {
            var body = await Net.Json($"{SearchUrl}/{type}/top/search={Uri.EscapeDataString(query)}.json");
            return body?["metas"] is JsonArray metas ? ToTitles(metas, kind) : new List<Title>();
        }, new List<Title>());
    }

    /// <summary>What a set of watched titles is mostly made of, most-watched first.</summary>
    public static List<(string Genre, string Kind)> TopGenres(IEnumerable<Title> titles, int limit = 3)
    {
        var tally = new List<(string Genre, int Seen, int Films)>();
        foreach (var title in titles)
        {
            foreach (var genre in title.Genres)
            {
                var i = tally.FindIndex(x => x.Genre == genre);
                if (i < 0) tally.Add((genre, 1, title.Kind == "movie" ? 1 : 0));
                else tally[i] = (genre, tally[i].Seen + 1, tally[i].Films + (title.Kind == "movie" ? 1 : 0));
            }
        }
        return tally.OrderByDescending(x => x.Seen).Take(limit)
            .Select(x => (x.Genre, x.Films * 2 >= x.Seen ? "movie" : "tv")).ToList();
    }

    /// <summary>Genre → 0..1: how much of what you watch is that genre, the most-watched at 1.</summary>
    public static async Task<Dictionary<string, double>> Taste(IEnumerable<(string Imdb, string Kind)> seen)
    {
        var titles = await Task.WhenAll(seen.Select(s => LoadTitle(s.Imdb, s.Kind)));
        var counts = titles.Where(t => t is not null).SelectMany(t => t!.Genres).GroupBy(g => g).ToDictionary(g => g.Key, g => (double)g.Count());
        var most = counts.Count > 0 ? counts.Values.Max() : 1;
        return counts.ToDictionary(kv => kv.Key, kv => kv.Value / most);
    }

    public static async Task<List<(string Genre, string Kind)>> WatchedGenres(IEnumerable<(string Imdb, string Kind)> seen, int limit = 3)
    {
        var titles = await Task.WhenAll(seen.Select(s => LoadTitle(s.Imdb, s.Kind)));
        return TopGenres(titles.Where(t => t is not null)!, limit);
    }

    static string Norm(string s) => Regex.Replace(s.ToLowerInvariant(), "[^a-z0-9]+", " ").Trim();

    /// <summary>The candidate that is actually the thing asked for, or nothing.</summary>
    public static Title? BestMatch(IEnumerable<Title> titles, string query, string? year)
    {
        var want = Norm(query);
        if (want.Length == 0) return null;
        var words = want.Split(' ');
        (Title Title, int Score)? best = null;
        foreach (var title in titles)
        {
            var got = Norm(title.Name);
            int score;
            if (got == want) score = 100;
            else if (got.StartsWith(want)) score = 60;
            else if (words.All(w => got.Split(' ').Contains(w))) score = 45;
            else continue;
            if (year is not null && title.Kind == "movie" && title.Year is { Length: >= 4 })
                score += title.Year[..4] == year ? 25 : -30;
            if (best is null || score > best.Value.Score) best = (title, score);
        }
        return best is { Score: >= 45 } ? best.Value.Title : null;
    }

    /// <summary>What a torrent actually is, from its name alone. Null rather than a guess.</summary>
    public static async Task<Title?> ResolveTitle(string releaseName)
    {
        var query = TitleText.SearchableName(releaseName);
        if (query.Length < 2) return null;
        var year = TitleText.YearOf(releaseName);
        var kind = TitleText.IsSeries(releaseName) ? "tv" : "movie";
        var likely = SearchTitles(query, kind);
        var other = SearchTitles(query, kind == "tv" ? "movie" : "tv");
        return BestMatch(await likely, query, year) ?? BestMatch(await other, query, year);
    }

    static string? S(JsonNode? n)
    {
        if (n is null) return null;
        var s = n is JsonValue v && v.TryGetValue<string>(out var str) ? str : n.ToString();
        return string.IsNullOrEmpty(s) ? null : s;
    }

    public static List<Title> ToTitles(JsonArray metas, string kind, bool withEpisodes = false)
    {
        var seen = new HashSet<string>();
        var @out = new List<Title>();
        foreach (var m in metas)
        {
            if (m is null || S(m["poster"]) is null) continue;
            var id = S(m["imdb_id"]) ?? S(m["id"]) ?? "";
            if (!Regex.IsMatch(id, @"^tt\d+$") || !seen.Add(id)) continue;
            var genres = (m["genre"] ?? m["genres"]) as JsonArray;
            @out.Add(new Title
            {
                Imdb = id,
                Kind = kind,
                Name = S(m["name"]) ?? "",
                Year = S(m["year"]) ?? S(m["releaseInfo"]) ?? (S(m["released"]) is { Length: >= 4 } r ? r[..4] : null),
                Rating = S(m["imdbRating"]),
                Genres = genres?.Take(3).Select(g => S(g) ?? "").Where(g => g.Length > 0).ToList() ?? new(),
                Poster = S(m["poster"]),
                Plot = S(m["description"]),
                Cast = (m["cast"] as JsonArray)?.Select(c => S(c) ?? "").Where(c => c.Length > 0).ToList() ?? new(),
                Directors = (m["director"] as JsonArray)?.Select(c => S(c) ?? "").Where(c => c.Length > 0).ToList() ?? new(),
                Awards = S(m["awards"]),
                Backdrop = S(m["background"]),
                Runtime = S(m["runtime"]),
                Episodes = withEpisodes ? ToEpisodes(m["videos"] as JsonArray) : new(),
            });
        }
        return @out;
    }

    /// <summary>
    /// The episode's number counted straight through the show, the way anime is
    /// released: Jujutsu Kaisen S2E5 is "- 29". Specials don't count. A season whose
    /// numbers already carry on from the last one (not starting at 1) is taken as is.
    /// </summary>
    public static int? AbsoluteNumber(IEnumerable<Episode> episodes, int season, int number)
    {
        if (season <= 0) return null;
        var regular = episodes.Where(e => e.Season > 0).ToList();
        var inSeason = regular.Where(e => e.Season == season).ToList();
        if (inSeason.Count == 0) return null;
        return inSeason.Min(e => e.Number) > 1 ? number : regular.Count(e => e.Season < season) + number;
    }

    /// <summary>
    /// Which episode of <paramref name="list"/> a file is: by SxxEyy; else "- 05" in a
    /// folder labelled with one season is that season's fifth; else the running number.
    /// </summary>
    public static int IndexOfEpisode(List<Episode> list, string fileName, string path)
    {
        int i;
        if (Search.ParseEpisode(fileName) is { } at && (i = list.FindIndex(e => e.Season == at.Season && e.Number == at.Episode)) >= 0) return i;
        if ((Search.ParseAbsolute(fileName) ?? Search.ParseEpisode(fileName)?.Episode) is not int n) return -1;
        if (Search.SeasonsIn(path) is [var season] && (i = list.FindIndex(e => e.Season == season && e.Number == n)) >= 0) return i;
        return list.FindIndex(e => AbsoluteNumber(list, e.Season, e.Number) == n);
    }

    public static List<Episode> ToEpisodes(JsonArray? videos)
    {
        if (videos is null) return new();
        var @out = new List<Episode>();
        foreach (var v in videos)
        {
            if (v is null || !int.TryParse(S(v["season"]), out var season) || !int.TryParse(S(v["episode"]), out var episode)) continue;
            @out.Add(new Episode
            {
                Id = S(v["id"]) ?? $"{season}:{episode}",
                Season = season,
                Number = episode,
                Name = S(v["name"]) ?? $"Episode {episode}",
                Overview = S(v["overview"]) ?? S(v["description"]),
                Thumbnail = S(v["thumbnail"]),
                Released = S(v["released"]),
            });
        }
        return @out.OrderBy(e => e.Season).ThenBy(e => e.Number).ToList();
    }

    public static List<(string Label, string Url)> ReviewLinks(Title title)
    {
        var q = Uri.EscapeDataString($"{title.Name} {title.Year}".Trim());
        return new()
        {
            ("IMDb", $"https://www.imdb.com/title/{title.Imdb}/reviews"),
            ("Rotten Tomatoes", $"https://www.rottentomatoes.com/search?search={q}"),
            ("Metacritic", $"https://www.metacritic.com/search/{q}/"),
            ("Letterboxd", $"https://letterboxd.com/search/{q}/"),
        };
    }
}
