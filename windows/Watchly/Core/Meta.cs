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
    /// <summary>The title's logo, set over its art in place of the name when there is one.</summary>
    public string? Logo { get; init; }
    public string? Runtime { get; init; }
    public List<Episode> Episodes { get; init; } = new();
    /// <summary>Its Stremio type — "movie", "series", "channel", "tv" or an addon's own — which addons are asked by. Null is the kind's.</summary>
    public string? Type { get; init; }

    [System.Text.Json.Serialization.JsonIgnore]
    public string StremioType => Type ?? (Kind == "movie" ? "movie" : "series");

    /// <summary>A landscape picture: MetaHub carries a background for nearly every IMDb id; an addon's own ids, their poster.</summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public string? Wide => Backdrop ?? (Imdb.StartsWith("tt") ? $"https://images.metahub.space/background/medium/{Imdb}/img" : Poster);

    /// <summary>Long-running anime, listed by each episode's number through the show rather than by season.</summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public bool NumberedThrough => Genres.Any(g => g is Meta.AnimeGenre or "Animation") && Episodes.Count(e => e.Season > 0) > 100;
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
    const string StorePrefix = "meta4-";

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

    /// <summary>src/lib/meta.ts — how a shelf can be ordered; "Popular" is the order it arrived in.</summary>
    public static readonly (string Key, string Label)[] Sorts =
    {
        ("popular", "Popular"), ("rating", "Top rated"), ("year", "Newest"), ("title", "A–Z"),
    };

    /// <summary>
    /// Order a shelf. A title missing the field being sorted on goes last rather than counting
    /// as zero — unrated is unknown, not bad — and those keep the catalogue's own order.
    /// </summary>
    public static List<Title> SortTitles(IReadOnlyList<Title> titles, string sort)
    {
        static double? Number(string? value) =>
            double.TryParse(value, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out var n) ? n : null;
        Func<Title, double?> by = sort == "rating" ? t => Number(t.Rating) : t => Number(t.Year);
        return sort switch
        {
            "title" => titles.OrderBy(t => t.Name, StringComparer.CurrentCultureIgnoreCase).ToList(),
            "rating" or "year" => titles.OrderBy(t => by(t) is null).ThenByDescending(by).ToList(),
            _ => titles.ToList(),
        };
    }

    public static List<Title> UniqueById(IEnumerable<Title> titles)
    {
        var seen = new HashSet<string>();
        return titles.Where(t => !string.IsNullOrEmpty(t.Imdb) && seen.Add(t.Imdb)).ToList();
    }

    /// <summary>
    /// A title's full entry: from the viewer's meta addons first, in their order and by the types they
    /// list for the id, so an addon's own ids ("kitsu:…", a channel's) open too; Cinemeta for an IMDb id
    /// none of them knows.
    /// </summary>
    public static Task<Title?> LoadTitle(string imdb, string kind) =>
        Once<Title?>($"title-{kind}-{imdb}", () => Remember($"title-{kind}-{imdb}", async () =>
        {
            if (await FromAddons(imdb, kind) is { } mine) return mine;
            if (!imdb.StartsWith("tt")) return null;
            using var res = await Net.Send($"{Base}/{(kind == "tv" ? "series" : "movie")}/{imdb}.json");
            if (!res.IsSuccessStatusCode) return null;
            var body = JsonNode.Parse(await res.Content.ReadAsStringAsync());
            if (body?["meta"] is not JsonObject meta) return null;
            meta["imdb_id"] = imdb;
            var title = ToTitles(new JsonArray(meta.DeepClone()), kind, true).FirstOrDefault();
            return title is { NumberedThrough: true } ? await WithStills(title) : title;
        }), null);

    static async Task<Title?> FromAddons(string id, string kind)
    {
        var first = kind == "tv" ? "series" : "movie";
        foreach (var a in Addons.List)
            foreach (var type in a.MetaTypes(id, first))
            {
                JsonObject? meta;
                try { meta = (await a.Get("meta", type, id))?["meta"] as JsonObject; }
                catch { continue; }
                if (meta is null) continue;
                // Kept under the id it was asked by: History, My List and the page all know it so.
                meta["imdb_id"] = id;
                var real = S(meta["type"]) ?? type;
                if (ToTitles(new JsonArray(meta.DeepClone()), Addons.KindOfType(real), true, true, real).FirstOrDefault() is { } t) return t;
            }
        return null;
    }

    /// <summary>Every addon catalogue that takes a search, at once; one failing is normal.</summary>
    public static async Task<List<Title>> SearchAddons(string query)
    {
        var jobs = Addons.Searchable().Select(async x =>
        {
            try
            {
                var body = await Net.Json($"{x.Addon.Url}/catalog/{x.Catalog.Type}/{Uri.EscapeDataString(x.Catalog.Id)}/search={Uri.EscapeDataString(query)}.json", default, true);
                return body?["metas"] is JsonArray metas ? ToTitles(metas, Addons.KindOfType(x.Catalog.Type), anyId: true, type: x.Catalog.Type) : new List<Title>();
            }
            catch
            {
                return new List<Title>();
            }
        });
        return (await Task.WhenAll(jobs)).SelectMany(t => t).ToList();
    }

    /// <summary>
    /// Long anime: Cinemeta's episode stills are mostly 404s, TVmaze has nearly every one.
    /// TVmaze counts seasons by year and recaps as episodes, so they pair up by air date
    /// (a day either way: Cinemeta's is UTC, TVmaze's local) — 1165 of One Piece's 1179.
    /// </summary>
    static async Task<Title> WithStills(Title t)
    {
        try
        {
            if ((await Net.Json($"https://api.tvmaze.com/lookup/shows?imdb={t.Imdb}", quiet: true))?["id"] is not JsonValue id) return t;
            var byDay = ((await Net.Json($"https://api.tvmaze.com/shows/{id}/episodes", quiet: true)) as JsonArray ?? new JsonArray())
                .Where(o => S(o?["airdate"]) is not null && S(o?["image"]?["medium"]) is not null)
                .GroupBy(o => S(o!["airdate"])!, o => S(o!["image"]!["medium"])!)
                .ToDictionary(g => g.Key, g => new Queue<string>(g));
            string? Take(DateTime day) =>
                byDay.TryGetValue(day.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture), out var q) && q.Count > 0 ? q.Dequeue() : null;
            var episodes = new List<Episode>();
            foreach (var e in t.Episodes)
            {
                var aired = e.Season > 0 && e.Released is { Length: >= 10 } r &&
                            DateTime.TryParseExact(r[..10], "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out var d)
                    ? d : (DateTime?)null;
                var still = aired is { } a ? Take(a) ?? Take(a.AddDays(1)) ?? Take(a.AddDays(-1)) : null;
                episodes.Add(still is null ? e : e with { Thumbnail = still });
            }
            return t with { Episodes = episodes };
        }
        catch
        {
            return t;
        }
    }

    /// <summary>
    /// Every regular episode's number through the show, in one pass — <see cref="AbsoluteNumber"/>
    /// for the lot, for the episode list of a show a thousand episodes long.
    /// </summary>
    public static List<(Episode Episode, int Number)> AbsoluteNumbers(IEnumerable<Episode> episodes)
    {
        var last = 0;
        var @out = new List<(Episode, int)>();
        foreach (var season in episodes.Where(e => e.Season > 0 && e.Number > 0).GroupBy(e => e.Season).OrderBy(g => g.Key))
        {
            var list = season.ToList();
            var offset = list.Min(e => e.Number) > last ? 0 : last;
            last = Math.Max(last, offset + list.Max(e => e.Number));
            @out.AddRange(list.Select(e => (e, offset + e.Number)));
        }
        return @out;
    }

    /// <summary>
    /// Cast photos by name, from Wikipedia (Cinemeta has names only). One request for the
    /// whole cast. A name that isn't clearly a performer's page (a disambiguation, or another
    /// person with that name) gets no photo, and keeps its initials.
    /// </summary>
    public static Task<Dictionary<string, string>> CastPhotos(string imdb, IReadOnlyList<string> names) =>
        Once($"cast-{imdb}", () => Remember($"cast-{imdb}", async () =>
        {
            var photos = new Dictionary<string, string>();
            if (names.Count == 0) return photos;
            var titles = Uri.EscapeDataString(string.Join("|", names.Take(50)));
            var body = await Net.Json($"https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageimages|description&pithumbsize=200&titles={titles}", quiet: true);
            var query = body?["query"];
            Dictionary<string, string> Map(string key) => (query?[key] as JsonArray)?
                .Where(n => S(n?["from"]) is not null && S(n?["to"]) is not null)
                .GroupBy(n => S(n!["from"])!).ToDictionary(g => g.Key, g => S(g.First()!["to"])!) ?? new();
            var normalized = Map("normalized");
            var redirects = Map("redirects");
            var pages = (query?["pages"] as JsonObject)?.Select(p => p.Value).Where(p => S(p?["title"]) is not null)
                .GroupBy(p => S(p!["title"])!).ToDictionary(g => g.Key, g => g.First()!) ?? new();
            foreach (var name in names)
            {
                var title = normalized.GetValueOrDefault(name, name);
                title = redirects.GetValueOrDefault(title, title);
                if (pages.GetValueOrDefault(title) is not { } page) continue;
                if (!Regex.IsMatch(S(page["description"]) ?? "", @"\b(actor|actress|comedian|performer|voice|entertainer|singer|rapper|model|filmmaker|director|presenter|personality|wrestler)\b", RegexOptions.IgnoreCase)) continue;
                if (S(page["thumbnail"]?["source"]) is { } url) photos[name] = url;
            }
            return photos;
        }), new Dictionary<string, string>());

    static readonly ConcurrentDictionary<string, List<Title>> Settled = new();

    public static List<Title>? PeekCatalog(string kind, string? genre) =>
        Settled.TryGetValue($"shelf-{kind}-{genre}", out var v) ? v : null;

    /// <summary>A <paramref name="genre"/> starting <see cref="Addons.ShelfPrefix"/> is one of the viewer's addons' catalogues, by <see cref="Addons.Shelves"/>' key.</summary>
    public static Task<List<Title>> LoadCatalog(string kind, string? genre = null)
    {
        var key = $"shelf-{kind}-{genre}";
        if (genre?.StartsWith(Addons.ShelfPrefix) == true)
            return Once(key, async () =>
            {
                var titles = await Remember("pool-" + key, async () =>
                {
                    var (addon, list) = Addons.Shelf(genre) ?? throw new InvalidOperationException("addon gone");
                    var body = await Net.Json($"{addon.Url}/catalog/{list.Type}/{Uri.EscapeDataString(list.Id)}.json", default, true);
                    var found = body?["metas"] is JsonArray metas ? ToTitles(metas, Addons.KindOfType(list.Type), anyId: true, type: list.Type) : new List<Title>();
                    if (found.Count == 0) throw new InvalidOperationException("empty shelf");
                    return found;
                });
                Settled[key] = titles;
                return titles;
            }, new List<Title>());
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

    /// <summary>
    /// Rows without art are unusable on a shelf. One row per id. Episodes only from /meta.
    /// Cinemeta's own lists keep IMDb ids only; an addon's (<paramref name="anyId"/>) are whatever it uses.
    /// </summary>
    public static List<Title> ToTitles(JsonArray metas, string kind, bool withEpisodes = false, bool anyId = false, string? type = null)
    {
        var seen = new HashSet<string>();
        var @out = new List<Title>();
        foreach (var m in metas)
        {
            if (m is null || (S(m["poster"]) is null && !withEpisodes)) continue;
            var id = S(m["imdb_id"]) ?? S(m["id"]) ?? "";
            if (id.Length == 0 || (!anyId && !Regex.IsMatch(id, @"^tt\d+$")) || !seen.Add(id)) continue;
            var genres = (m["genre"] ?? m["genres"]) as JsonArray;
            var stremioType = S(m["type"]) ?? type ?? (kind == "movie" ? "movie" : "series");
            var name = S(m["name"]) ?? "";
            var episodes = withEpisodes ? ToEpisodes(m["videos"] as JsonArray) : new();
            // A type of an addon's own with no videos plays its own id, as one "episode".
            if (withEpisodes && episodes.Count == 0 && kind == "tv" && stremioType != "series")
                episodes.Add(new Episode { Id = id, Season = 1, Number = 1, Name = name });
            @out.Add(new Title
            {
                Imdb = id,
                Kind = kind,
                Type = stremioType,
                Name = name,
                Year = S(m["year"]) ?? S(m["releaseInfo"]) ?? (S(m["released"]) is { Length: >= 4 } r ? r[..4] : null),
                Rating = S(m["imdbRating"]),
                Genres = genres?.Take(3).Select(g => S(g) ?? "").Where(g => g.Length > 0).ToList() ?? new(),
                Poster = S(m["poster"]),
                Plot = S(m["description"]),
                Cast = (m["cast"] as JsonArray)?.Select(c => S(c) ?? "").Where(c => c.Length > 0).ToList() ?? new(),
                Directors = (m["director"] as JsonArray)?.Select(c => S(c) ?? "").Where(c => c.Length > 0).ToList() ?? new(),
                Awards = S(m["awards"]),
                Backdrop = S(m["background"]),
                Logo = S(m["logo"]),
                Runtime = S(m["runtime"]),
                Episodes = episodes,
            });
        }
        return @out;
    }

    /// <summary>
    /// The episode's number counted straight through the show, the way anime is
    /// released: Jujutsu Kaisen S2E5 is "- 29". Specials (and an "episode 0" inside a season)
    /// don't count. A season whose numbers already carry on past the last one's (S21 starting
    /// at 892) is taken as is.
    /// A season starting over at 1 carries on from the highest number reached before it, not
    /// from how many entries came before: the metadata of a thousand-episode show lists an
    /// episode twice here and skips one there, and counting entries put every later episode
    /// one off ("1001" fetched 1002).
    /// </summary>
    public static int? AbsoluteNumber(IEnumerable<Episode> episodes, int season, int number)
    {
        if (season <= 0 || number <= 0) return null;
        var regular = episodes.Where(e => e.Season > 0 && e.Season <= season && e.Number > 0).ToList();
        if (!regular.Any(e => e.Season == season)) return null;
        var last = 0;
        foreach (var inSeason in regular.GroupBy(e => e.Season).OrderBy(g => g.Key))
        {
            var offset = inSeason.Min(e => e.Number) > last ? 0 : last;
            if (inSeason.Key == season) return offset + number;
            last = Math.Max(last, offset + inSeason.Max(e => e.Number));
        }
        return null;
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

    /// <summary>
    /// Broadcast order. Season 0 (specials) keeps its own group. A channel's videos carry no
    /// numbers: they become one season, numbered in the addon's own order.
    /// </summary>
    public static List<Episode> ToEpisodes(JsonArray? videos)
    {
        if (videos is null) return new();
        if (!videos.Any(v => v is not null && int.TryParse(S(v["season"]), out var s) && s >= 0 && int.TryParse(S(v["episode"]), out _)))
            return videos.Where(v => S(v?["id"]) is not null).Select((v, i) => new Episode
            {
                Id = S(v!["id"])!,
                Season = 1,
                Number = i + 1,
                Name = S(v["title"]) ?? S(v["name"]) ?? $"Video {i + 1}",
                Overview = S(v["overview"]) ?? S(v["description"]),
                Thumbnail = S(v["thumbnail"]),
                Released = S(v["released"]),
            }).ToList();
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
