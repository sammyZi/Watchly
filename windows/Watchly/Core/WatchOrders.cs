using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>
/// A franchise — tv/core WatchOrders.kt. <see cref="Films"/> is release order; <see cref="Story"/>, when not
/// empty, the order events happen in. <see cref="FromTmdb"/>: not from the list, so its page has to credit TMDB.
/// </summary>
public sealed record WatchOrder(string Id, string Name, List<string> Films, List<string> Story, bool More, bool FromTmdb = false)
{
    public static string Poster(string imdb) => $"https://images.metahub.space/poster/medium/{imdb}/img";
    public string Art => $"https://images.metahub.space/background/medium/{Films[0]}/img";

    /// <summary>"spider man", "spiderman" and "Spider-Man" are one name: only letters and digits count.</summary>
    public bool Matches(string query)
    {
        static string Squash(string s) => string.Concat(s.Where(char.IsLetterOrDigit)).ToLowerInvariant();
        return Squash(Name).Contains(Squash(query));
    }
}

/// <summary>The hand-kept list in tv/core's watch-orders.json, the file the phone and TV read.</summary>
public static class WatchOrders
{
    static List<WatchOrder>? all;

    public static List<WatchOrder> All => all ??= Read();

    static List<WatchOrder> Read()
    {
        using var stream = typeof(WatchOrders).Assembly.GetManifestResourceStream("watch-orders.json")!;
        using var reader = new StreamReader(stream);
        return JsonNode.Parse(reader.ReadToEnd())!["orders"]!.AsArray().Select(o => new WatchOrder(
            (string)o!["id"]!, (string)o["name"]!, Strings(o["films"]), Strings(o["story"]), o["more"]?.GetValue<bool>() ?? false)).ToList();
    }

    static List<string> Strings(JsonNode? list) => list is JsonArray a ? a.Select(s => (string)s!).ToList() : new();

    /// <summary>The first order a film belongs to, for the link on its page.</summary>
    public static WatchOrder? Of(string imdb) => All.FirstOrDefault(o => o.Films.Contains(imdb));

    /// <summary>Films seen through: played here past the mark, or marked on a connected account.</summary>
    public static HashSet<string> Seen(IEnumerable<HistoryItem> history)
    {
        var seen = new HashSet<string>(Trackers.Everywhere.Films);
        foreach (var h in history)
            if (h.Imdb is { } id && h.Ratio >= Stremio.WatchedAt) seen.Add(id);
        return seen;
    }

    /// <summary>"3 films", then how far along, or that more are coming.</summary>
    public static string Line(WatchOrder order, HashSet<string> seen)
    {
        var done = order.Films.Count(seen.Contains);
        return string.Join("  ·  ", new[] { $"{order.Films.Count} films", done > 0 ? $"{done} watched" : null, order.More ? "more coming" : null }.Where(s => s is not null));
    }

    /// <summary>"19h 12m": what the order adds up to.</summary>
    public static string? TotalRuntime(IEnumerable<Title> titles)
    {
        var minutes = titles.Sum(t => Regex.Match(t.Runtime ?? "", @"(\d+)\s*min") is { Success: true } m ? int.Parse(m.Groups[1].Value) : 0);
        return minutes > 0 ? $"{minutes / 60}h {minutes % 60}m" : null;
    }
}

public sealed record CastMember(int Id, string Name, string? Role, string? Photo);

/// <summary>A title known by TMDB's id: its IMDb id, which the app goes by, is asked for only when it's opened.</summary>
public sealed record TmdbCard(int Id, string Kind, string Title, string? Poster, string? Year);

public sealed record TmdbTitle(string? Certificate, List<CastMember> Cast, List<TmdbCard> Similar);

public sealed record TmdbPerson(string Name, string? Photo, string? KnownFor, string? About, List<TmdbCard> Credits);

/// <summary>A suggestion, and the viewer's title it mostly came from.</summary>
public sealed record ForYouPick(Title Title, string Because);

public sealed record ForYouRail(string Name, List<Title> Titles);

/// <summary>The best suggestions of all, then a row for each of the viewer's titles.</summary>
public sealed record ForYou(List<ForYouPick> Picks, List<ForYouRail> Rails);

/// <summary>
/// Ratings, cast, what's alike and any film's collection — tv/core WatchOrders.kt and TmdbTitle.kt. With no
/// key in the build, or no answer, every call is null and the page is as it was.
/// </summary>
public static class Tmdb
{
    const string Api = "https://api.themoviedb.org/3";
    const string Images = "https://image.tmdb.org/t/p";
    const long KeepMs = 7L * 24 * 60 * 60 * 1000;

    /// <summary>Above these a kids profile stops. A title with no rating at all goes by the genre rule, as before.</summary>
    static readonly HashSet<string> KidsCertificates = new() { "G", "PG", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG" };

    /// <summary>Talk shows and news: an actor's every guest spot, and nothing anyone is looking for.</summary>
    static readonly HashSet<int> NotShows = new() { 10767, 10763 };

    static readonly string Key = typeof(Tmdb).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().FirstOrDefault(a => a.Key == "TmdbKey")?.Value ?? "";

    static readonly Dictionary<string, TmdbTitle> Titles = new();
    static readonly Dictionary<int, TmdbPerson> People = new();

    public static bool TooOldForKids(string? certificate) => certificate is not null && !KidsCertificates.Contains(certificate);

    /// <summary>Throws on any failure, so that no answer is never kept as an answer of none.</summary>
    static async Task<JsonNode> Get(string path)
    {
        using var res = await Net.Send($"{Api}{path}{(path.Contains('?') ? '&' : '?')}api_key={Key}", quiet: true);
        res.EnsureSuccessStatusCode();
        return JsonNode.Parse(await res.Content.ReadAsStringAsync())!;
    }

    static string? Str(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) && s.Trim().Length > 0 ? s : null;
    static int Int(JsonNode? n) => n is JsonValue v && v.TryGetValue<int>(out var i) ? i : 0;
    static bool Flag(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) && b;
    static double Num(JsonNode? n) => n is JsonValue v && v.TryGetValue<double>(out var d) ? d : 0;
    static IEnumerable<JsonNode> Rows(JsonNode? n) => n is JsonArray a ? a.Where(x => x is not null)! : Array.Empty<JsonNode>();
    static string? Image(string size, string? path) => path is null ? null : $"{Images}/{size}{path}";

    static TmdbCard? CardOf(JsonNode o, string kind)
    {
        var poster = Image("w342", Str(o["poster_path"]));
        var name = Str(o["title"]) ?? Str(o["name"]);
        if (poster is null || name is null) return null;
        var date = Str(o["release_date"]) ?? Str(o["first_air_date"]);
        return new TmdbCard(Int(o["id"]), kind, name, poster, date?[..Math.Min(4, date.Length)]);
    }

    /// <summary>The US rating: the one set every title has.</summary>
    static string? CertificateOf(JsonNode o, string kind)
    {
        if (kind == "tv")
            return Str(Rows(o["content_ratings"]?["results"]).FirstOrDefault(r => Str(r["iso_3166_1"]) == "US")?["rating"]);
        var us = Rows(o["release_dates"]?["results"]).FirstOrDefault(r => Str(r["iso_3166_1"]) == "US");
        return Rows(us?["release_dates"]).Select(d => Str(d["certification"])).FirstOrDefault(c => c is not null);
    }

    static TmdbTitle ParseTitle(JsonNode o, string kind) => new(
        CertificateOf(o, kind),
        Rows(o["credits"]?["cast"]).Take(14).Select(c => new CastMember(Int(c["id"]), Str(c["name"]) ?? "", Str(c["character"]), Image("w185", Str(c["profile_path"])))).ToList(),
        Rows(o["recommendations"]?["results"]).Where(r => !Flag(r["adult"])).Select(r => CardOf(r, kind)).OfType<TmdbCard>().Take(15).ToList());

    /// <summary>A title's rating, cast and recommendations.</summary>
    public static async Task<TmdbTitle?> Title(string imdb, string kind)
    {
        if (Key.Length == 0 || !imdb.StartsWith("tt")) return null;
        if (Titles.TryGetValue(imdb, out var kept)) return kept;
        try
        {
            var tv = kind == "tv";
            var found = await Get($"/find/{imdb}?external_source=imdb_id");
            if (Rows(found[tv ? "tv_results" : "movie_results"]).FirstOrDefault() is not { } hit) return null;
            var id = Int(hit["id"]);
            var title = ParseTitle(await Get(tv ? $"/tv/{id}?append_to_response=credits,content_ratings,recommendations" : $"/movie/{id}?append_to_response=credits,release_dates,recommendations"), tv ? "tv" : "movie");
            return Titles[imdb] = title;
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or System.Text.Json.JsonException or InvalidOperationException)
        {
            return null;
        }
    }

    /// <summary>What they're in, best known first, each title once.</summary>
    public static async Task<TmdbPerson?> Person(int id)
    {
        if (Key.Length == 0) return null;
        if (People.TryGetValue(id, out var kept)) return kept;
        try
        {
            var o = await Get($"/person/{id}?append_to_response=combined_credits");
            var credits = Rows(o["combined_credits"]?["cast"])
                .Where(c => !Flag(c["adult"]) && Str(c["media_type"]) is "movie" or "tv" && !Rows(c["genre_ids"]).Any(g => NotShows.Contains(Int(g))))
                .OrderByDescending(c => Num(c["popularity"]))
                .Select(c => CardOf(c, Str(c["media_type"])!)).OfType<TmdbCard>()
                .DistinctBy(c => (c.Kind, c.Id))
                .Take(60).ToList();
            return People[id] = new TmdbPerson(Str(o["name"]) ?? "", Image("w342", Str(o["profile_path"])), Str(o["known_for_department"]), Str(o["biography"]), credits);
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or System.Text.Json.JsonException or InvalidOperationException)
        {
            return null;
        }
    }

    /// <summary>What the app knows a card by; null if TMDB has none for it, and it can't be opened.</summary>
    public static async Task<string?> ImdbOf(TmdbCard card)
    {
        try { return Str((await Get($"/{card.Kind}/{card.Id}/external_ids"))["imdb_id"]); }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or System.Text.Json.JsonException or InvalidOperationException) { return null; }
    }

    const int Picks = 20, PerRail = 10, SmallestRail = 4;
    const double SuggestFrom = 6;
    const long KeepForYouMs = 24 * 60 * 60 * 1000;

    /// <summary>Below 6 on IMDb, a suggestion isn't one. A title with no rating yet is new, not bad, and stays.</summary>
    static bool WorthSuggesting(Title title) => (title.Score ?? SuggestFrom) >= SuggestFrom;

    /// <summary>
    /// core planForYou. Each seed is one of the viewer's titles, newest first, with what TMDB suggests for it. A card
    /// suggested by several leads, however far down each had it; a newer title counts for more. What the picks took,
    /// the rows don't repeat, so every row is something new.
    /// </summary>
    static (List<(TmdbCard Card, string Because)> Picks, List<(string Name, List<TmdbCard> Cards)> Rails) Plan(List<(string Name, List<TmdbCard> Cards)> seeds)
    {
        var score = new Dictionary<TmdbCard, double>();
        var votes = new Dictionary<TmdbCard, int>();
        var because = new Dictionary<TmdbCard, (string Name, double Part)>();
        for (var s = 0; s < seeds.Count; s++)
        {
            var (name, cards) = seeds[s];
            for (var rank = 0; rank < cards.Count; rank++)
            {
                var card = cards[rank];
                var part = (1.0 - (double)rank / cards.Count) / (1.0 + s * 0.25);
                score[card] = score.GetValueOrDefault(card) + part;
                votes[card] = votes.GetValueOrDefault(card) + 1;
                if (part > (because.TryGetValue(card, out var best) ? best.Part : 0)) because[card] = (name, part);
            }
        }
        var picks = score.Keys.OrderByDescending(c => votes[c]).ThenByDescending(c => score[c]).Take(Picks).Select(c => (c, because[c].Name)).ToList();
        var used = picks.Select(p => p.c).ToHashSet();
        var rails = new List<(string, List<TmdbCard>)>();
        foreach (var (name, cards) in seeds)
        {
            var fresh = cards.Where(c => !used.Contains(c)).Take(PerRail).ToList();
            if (fresh.Count < SmallestRail) continue;
            used.UnionWith(fresh);
            rails.Add((name, fresh));
        }
        return (picks, rails);
    }

    sealed record KeptForYou(long At, ForYou Value);

    /// <summary>What to watch next, from TMDB's recommendations for the viewer's own titles. Kept a day, for that set of titles.</summary>
    public static async Task<ForYou> Suggest(List<(string Imdb, string Kind)> seeds)
    {
        var none = new ForYou(new(), new());
        if (Key.Length == 0 || seeds.Count == 0) return none;
        var slot = "foryou-" + string.Join("-", seeds.Select(s => s.Imdb));
        if (Store.Load<KeptForYou?>(slot, null) is { } kept && Store.Now - kept.At < KeepForYouMs) return kept.Value;
        try
        {
            var asked = await Task.WhenAll(seeds.Select(async s => ((await Meta.LoadTitle(s.Imdb, s.Kind))?.Name ?? "", (await Title(s.Imdb, s.Kind))?.Similar ?? new())));
            var plan = Plan(asked.Where(a => a.Item1.Length > 0 && a.Item2.Count > 0).ToList());
            var found = new System.Collections.Concurrent.ConcurrentDictionary<TmdbCard, Title>();
            using var gate = new SemaphoreSlim(8);
            await Task.WhenAll(plan.Picks.Select(p => p.Card).Concat(plan.Rails.SelectMany(r => r.Cards)).Distinct().Select(async card =>
            {
                await gate.WaitAsync();
                try
                {
                    if (await ImdbOf(card) is not { } imdb) return;
                    var title = await Meta.LoadTitle(imdb, card.Kind) ?? new Title { Imdb = imdb, Kind = card.Kind, Name = card.Title, Year = card.Year, Poster = card.Poster };
                    if (WorthSuggesting(title)) found[card] = title;
                }
                finally { gate.Release(); }
            }));
            var you = new ForYou(
                plan.Picks.Where(p => found.ContainsKey(p.Card)).Select(p => new ForYouPick(found[p.Card], p.Because)).ToList(),
                plan.Rails.Select(r => new ForYouRail(r.Name, r.Cards.Where(found.ContainsKey).Select(c => found[c]).ToList())).ToList());
            if (you.Picks.Count > 0) Store.Save(slot, new KeptForYou(Store.Now, you));
            return you;
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or System.Text.Json.JsonException or InvalidOperationException or IOException)
        {
            return none;
        }
    }

    sealed record Kept(long At, string? Id, string? Name, List<string>? Films, bool More);

    /// <summary>
    /// The collection a film belongs to, for what the list doesn't have. Release order only, and films only: TMDB
    /// has no collections of series. A few small calls the first time, then a week from what was kept, none included.
    /// </summary>
    public static async Task<WatchOrder?> OrderOf(string imdb)
    {
        if (Key.Length == 0 || !imdb.StartsWith("tt")) return null;
        var slot = $"tmdb-order-{imdb}";
        if (Store.Load<Kept?>(slot, null) is { } kept && Store.Now - kept.At < KeepMs)
            return kept.Id is null ? null : new WatchOrder(kept.Id, kept.Name!, kept.Films!, new(), kept.More, true);
        try
        {
            var order = await Fetch(imdb);
            Store.Save(slot, new Kept(Store.Now, order?.Id, order?.Name, order?.Films, order?.More ?? false));
            return order;
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or System.Text.Json.JsonException or InvalidOperationException or IOException)
        {
            return null;
        }
    }

    static async Task<WatchOrder?> Fetch(string imdb)
    {
        if (Rows((await Get($"/find/{imdb}?external_source=imdb_id"))["movie_results"]).FirstOrDefault() is not { } film) return null;
        if ((await Get($"/movie/{Int(film["id"])}"))["belongs_to_collection"] is not { } owner) return null;
        var collection = Int(owner["id"]);
        var set = await Get($"/collection/{collection}");
        var today = DateTime.UtcNow.ToString("yyyy-MM-dd");
        var parts = Rows(set["parts"]).ToList();
        var outNow = parts.Where(p => Str(p["release_date"]) is { } d && string.CompareOrdinal(d, today) <= 0).OrderBy(p => Str(p["release_date"]), StringComparer.Ordinal).ToList();
        var films = (await Task.WhenAll(outNow.Select(async p => Str((await Get($"/movie/{Int(p["id"])}/external_ids"))["imdb_id"])))).OfType<string>().ToList();
        if (films.Count < 2 || !films.Contains(imdb)) return null;
        var name = Str(set["name"]) ?? "";
        return new WatchOrder($"tmdb-{collection}", name.EndsWith(" Collection") ? name[..^" Collection".Length] : name, films, new(), outNow.Count < parts.Count, true);
    }
}
