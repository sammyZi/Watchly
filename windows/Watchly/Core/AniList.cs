using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Watchly.Core;

/// <summary>
/// tv/core AniList.kt: AniList (anilist.co), for anime. What is finished here marks the episode or film there,
/// and what is watched there comes back as <see cref="Library"/>, with what is being watched there as "Up next".
/// Signed in on AniList's own page, which hands the token back by a watchly://anilist link (the installer
/// registers watchly://); it lasts a year and AniList has no way to renew it.
///
/// AniList has its own ids and an entry for each season, often each half of one, where the app goes by IMDb and
/// season numbers. Fribb's anime-lists ties them together: the entry, its IMDb title and its TVDB season.
/// </summary>
public static class AniList
{
    public const string Name = "AniList", Site = "anilist.co";
    const string Api = "https://graphql.anilist.co";
    const string MappingUrl = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json";
    /// <summary>The mapping changes as new seasons air. Calibration knob.</summary>
    const int MappingDays = 7;
    /// <summary>Far past any real list; a list that never ends must not spend the minute's requests.</summary>
    const int MaxChunks = 20;
    const string QueueKey = "anilist-queue", LibraryKey = "anilist-library";

    static readonly string clientId = typeof(AniList).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().FirstOrDefault(a => a.Key == "AnilistClientId")?.Value?.Trim() ?? "";
    public static bool Available => clientId.Length > 0;
    public static string AuthorizeUrl => $"https://anilist.co/api/v2/oauth/authorize?client_id={Uri.EscapeDataString(clientId)}&response_type=token";

    sealed record Sign(string Access, long Expires, int User, string? Name);
    sealed record Entry(string Imdb, int Id, int Season, string Type)
    {
        public bool IsFilm => Type == "MOVIE";
        public bool IsSeries => Type is "TV" or "TV_SHORT" or "ONA";
    }
    sealed record Cour(int Id, int? Episodes);
    /// <summary>What is kept for the next launch: films and episodes as "s:e". What is up next comes with each launch's pull.</summary>
    sealed record SavedLibrary(List<string> Films, Dictionary<string, List<string>> Episodes);

    static string profile = "";
    /// <summary>A profile is in: a sign-in link before one (the link opened Watchly) waits for it.</summary>
    static bool loaded;
    static bool guest, flushing;
    static Sign? sign;
    static string? pending;

    public static bool Connected => sign is not null;
    public static string? User => sign?.Name;
    public static StremioLibrary Library { get; private set; } = StremioLibrary.Empty;
    /// <summary>On any thread.</summary>
    public static event Action? Changed;

    static string Slot(string id) => $"anilist-{id}";

    static AniList() => Net.OnlineChanged += () => { if (Net.IsOnline) Flush(); };

    /* ------------------------------------------------------------------ account */

    /// <summary>On every profile switch, as the trackers. After Store has the profile.</summary>
    public static void Load(string id, bool isGuest)
    {
        profile = id;
        guest = isGuest;
        loaded = true;
        sign = null;
        if (!isGuest && Secrets.Get(Slot(id)) is { } saved)
        {
            try { sign = JsonSerializer.Deserialize<Sign>(saved, Store.Json); }
            catch (JsonException) { Log.Warn("anilist", "couldn't read the saved sign-in"); }
        }
        if (sign is { } s && s.Expires <= Store.Now) sign = null;
        Library = sign is not null ? FromSaved(Store.LoadOwn<SavedLibrary?>(LibraryKey, null)) : StremioLibrary.Empty;
        Changed?.Invoke();
        Flush();
        Pull();
        if (pending is { } link)
        {
            pending = null;
            SignIn(link);
        }
    }

    static void Save(Sign? next)
    {
        sign = next;
        if (!guest) Secrets.Put(Slot(profile), next is null ? null : JsonSerializer.Serialize(next, Store.Json));
        if (next is null)
        {
            Library = StremioLibrary.Empty;
            foreach (var key in new[] { QueueKey, LibraryKey }) Store.RemoveOwn(key);
        }
        Changed?.Invoke();
    }

    public static void Forget(string id) => Secrets.Put(Slot(id), null);

    /// <summary>The token AniList hands back in a sign-in link's fragment ("…#access_token=…&amp;expires_in=…"), and when it runs out.</summary>
    internal static (string Access, long Expires)? TokenFromRedirect(string link, long now)
    {
        var hash = link.IndexOf('#');
        if (hash < 0) return null;
        var fields = link[(hash + 1)..].Split('&').Select(p => p.Split('=', 2)).Where(p => p.Length == 2).ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1]));
        if (!fields.TryGetValue("access_token", out var access) || access.Length == 0) return null;
        var seconds = fields.TryGetValue("expires_in", out var e) && long.TryParse(e, out var n) ? n : 365L * 24 * 3600;
        return (access, now + seconds * 1000);
    }

    /// <summary>A watchly://anilist#access_token=… link, back from AniList's approval page: signs in with it. False for any other link.</summary>
    public static bool SignIn(string link)
    {
        if (!link.StartsWith("watchly://anilist", StringComparison.OrdinalIgnoreCase)) return false;
        if (TokenFromRedirect(link, Store.Now) is not { } t)
        {
            Log.Warn("anilist", "sign-in: no token in the link back");
            return true;
        }
        if (!loaded) { pending = link; return true; }
        var owner = profile;
        _ = Task.Run(async () =>
        {
            try
            {
                var me = (await Gql("query{Viewer{id name}}", new JsonObject(), t.Access).ConfigureAwait(false))["Viewer"]!;
                if (owner != profile) return;
                Save(new Sign(t.Access, t.Expires, me["id"]!.GetValue<int>(), me["name"]?.GetValue<string>()));
                Log.Info("anilist", $"signed in as {User}");
                Pull();
                Flush();
            }
            catch (Exception e) { Log.Warn("anilist", $"sign-in: {e.Message}"); }
        });
        return true;
    }

    /// <summary>Signs out here. The token itself only goes from AniList's own settings, under Apps.</summary>
    public static void Disconnect() => Save(null);

    /* ------------------------------------------------------------------ the API */

    static async Task<JsonNode> Gql(string query, JsonObject vars, string? token = null)
    {
        token ??= sign?.Access;
        using var timer = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var request = new HttpRequestMessage(HttpMethod.Post, Api)
        {
            Content = new StringContent(new JsonObject { ["query"] = query, ["variables"] = vars }.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        request.Headers.Accept.ParseAdd("application/json");
        if (token is not null) request.Headers.Authorization = new("Bearer", token);
        using var res = await Net.Http.SendAsync(request, timer.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(timer.Token).ConfigureAwait(false);
        // A token taken away on AniList's side: signed out here too.
        if ((int)res.StatusCode == 401 && token is not null && token == sign?.Access) Save(null);
        if (!res.IsSuccessStatusCode) throw new TrackerRefused((int)res.StatusCode, text);
        var o = JsonNode.Parse(text)!;
        if (o["errors"] is JsonArray { Count: > 0 } errors) throw new HttpRequestException(errors[0]?["message"]?.GetValue<string>() ?? "AniList refused it");
        return o["data"]!;
    }

    /* ------------------------------------------------------------------ mapping */

    static Dictionary<string, List<Entry>> byImdb = new();
    static Dictionary<int, Entry> byId = new();
    /// <summary>Each part's episode count and start, by id, once asked: to put a season's parts in order.</summary>
    static readonly Dictionary<int, (int? Episodes, long Start)> parts = new();
    static readonly SemaphoreSlim mappingLock = new(1, 1);

    /// <summary>Kept a week as lines ("tt… id season type"); the 6MB list only comes again after that. One load at a time.</summary>
    static async Task<bool> Mapping()
    {
        await mappingLock.WaitAsync().ConfigureAwait(false);
        try
        {
            if (byImdb.Count > 0) return true;
            var cache = Path.Combine(Store.Root, "anilist-map.txt");
            var fresh = File.Exists(cache) && DateTime.UtcNow - File.GetLastWriteTimeUtc(cache) < TimeSpan.FromDays(MappingDays);
            List<Entry>? entries = fresh ? ReadLines(cache) : null;
            if (entries is null)
            {
                try
                {
                    using var timer = new CancellationTokenSource(TimeSpan.FromSeconds(120));
                    entries = ReadMapping(await Net.Http.GetStringAsync(MappingUrl, timer.Token).ConfigureAwait(false));
                    Directory.CreateDirectory(Store.Root);
                    File.WriteAllLines(cache, entries.Select(e => $"{e.Imdb} {e.Id} {e.Season} {e.Type}"));
                }
                catch (Exception e) when (e is HttpRequestException or TaskCanceledException or JsonException or IOException)
                {
                    entries = File.Exists(cache) ? ReadLines(cache) : null;
                }
            }
            if (entries is null)
            {
                Log.Warn("anilist", "mapping: couldn't load the anime list");
                return false;
            }
            byImdb = entries.GroupBy(e => e.Imdb).ToDictionary(g => g.Key, g => g.ToList());
            byId = entries.GroupBy(e => e.Id).ToDictionary(g => g.Key, g => g.First());
            Log.Info("anilist", $"mapping: {entries.Count} entries{(fresh ? ", kept" : "")}");
            return true;
        }
        finally
        {
            mappingLock.Release();
        }
    }

    static List<Entry>? ReadLines(string file)
    {
        try
        {
            return File.ReadAllLines(file).Select(l => l.Split(' ')).Where(p => p.Length == 4 && int.TryParse(p[1], out _))
                .Select(p => new Entry(p[0], int.Parse(p[1]), int.TryParse(p[2], out var s) ? s : -1, p[3])).ToList();
        }
        catch (IOException) { return null; }
    }

    /// <summary>Only the entries with an AniList id and an IMDb title.</summary>
    static List<Entry> ReadMapping(string json)
    {
        var @out = new List<Entry>();
        using var doc = JsonDocument.Parse(json);
        foreach (var e in doc.RootElement.EnumerateArray())
        {
            if (!e.TryGetProperty("anilist_id", out var idEl) || idEl.ValueKind != JsonValueKind.Number) continue;
            var type = e.TryGetProperty("type", out var t) && t.ValueKind == JsonValueKind.String ? t.GetString() ?? "" : "";
            var season = e.TryGetProperty("season", out var s) && s.ValueKind == JsonValueKind.Object && s.TryGetProperty("tvdb", out var tv) && tv.ValueKind == JsonValueKind.Number ? tv.GetInt32() : -1;
            if (!e.TryGetProperty("imdb_id", out var imdb) || type.Length == 0) continue;
            var ids = imdb.ValueKind == JsonValueKind.Array ? imdb.EnumerateArray().Where(i => i.ValueKind == JsonValueKind.String).Select(i => i.GetString()!)
                : imdb.ValueKind == JsonValueKind.String ? new[] { imdb.GetString()! } : Enumerable.Empty<string>();
            foreach (var i in ids.Where(i => i.StartsWith("tt"))) @out.Add(new Entry(i, idEl.GetInt32(), season, type));
        }
        return @out;
    }

    /// <summary>Which part of a season <paramref name="episode"/> falls in, and its number within that part. The last part takes whatever is past the others.</summary>
    static (int Id, int Episode)? CourOf(List<Cour> cours, int episode)
    {
        var offset = 0;
        for (var i = 0; i < cours.Count; i++)
        {
            var n = cours[i].Episodes;
            if (n is null || i == cours.Count - 1 || episode <= offset + n) return (cours[i].Id, Math.Max(1, episode - offset));
            offset += n.Value;
        }
        return null;
    }

    /// <summary>The season's episodes a part's progress covers: its own, counted on from the parts before it.</summary>
    static List<int> EpisodesOf(List<Cour> cours, int id, int progress)
    {
        var offset = 0;
        foreach (var c in cours)
        {
            if (c.Id == id) return Enumerable.Range(1, Math.Max(0, progress)).Select(n => offset + n).ToList();
            if (c.Episodes is not int n) return new();
            offset += n;
        }
        return new();
    }

    static List<Cour> SortedCours(string imdb, int season) =>
        byImdb.GetValueOrDefault(imdb, new()).Where(e => e.IsSeries && e.Season == season).Select(e => e.Id).Distinct()
            .OrderBy(id => parts.TryGetValue(id, out var p) ? p.Start : long.MaxValue).Select(id => new Cour(id, parts.TryGetValue(id, out var p) ? p.Episodes : null)).ToList();

    static async Task LearnParts(IEnumerable<int> ids)
    {
        foreach (var chunk in ids.Where(id => !parts.ContainsKey(id)).Distinct().Chunk(50))
        {
            var page = await Gql("query($ids:[Int]){Page(perPage:50){media(id_in:$ids){id episodes startDate{year month day}}}}", new JsonObject { ["ids"] = new JsonArray(chunk.Select(i => (JsonNode)i).ToArray()) }).ConfigureAwait(false);
            foreach (var m in (page["Page"]?["media"] as JsonArray ?? new()).OfType<JsonNode>()) parts[m["id"]!.GetValue<int>()] = (m["episodes"]?.GetValue<int>(), StartOf(m["startDate"]));
        }
    }

    static long StartOf(JsonNode? d) => d is null ? long.MaxValue : (d["year"]?.GetValue<int>() ?? 9999) * 10_000L + (d["month"]?.GetValue<int>() ?? 12) * 100 + (d["day"]?.GetValue<int>() ?? 31);

    /* ------------------------------------------------------------------ watched here → there */

    static List<string> Queued() => Store.LoadOwn(QueueKey, new List<string>());

    static void SetQueue(List<string> items)
    {
        if (items.Count == 0) Store.RemoveOwn(QueueKey);
        else Store.SaveOwn(QueueKey, items);
    }

    /// <summary>A film or an episode finished; anime only, as the mapping knows it.</summary>
    public static void Finished(HistoryItem at, (int Season, int Episode)? ep)
    {
        if (at.Imdb is not { } imdb || sign is null) return;
        var queue = Queued();
        queue.Add(new JsonObject { ["imdb"] = imdb, ["season"] = ep?.Season ?? -1, ["episode"] = ep?.Episode ?? -1 }.ToJsonString());
        SetQueue(queue);
        Flush();
    }

    static void Flush()
    {
        if (flushing || sign is null) return;
        flushing = true;
        _ = Task.Run(async () =>
        {
            try
            {
                var owner = profile;
                if (!await Mapping().ConfigureAwait(false)) return;
                while (owner == profile && sign is not null && Queued().FirstOrDefault() is { } raw)
                {
                    try
                    {
                        if (JsonNode.Parse(raw) is { } o) await Send(o["imdb"]!.GetValue<string>(), o["season"]?.GetValue<int>() ?? -1, o["episode"]?.GetValue<int>() ?? -1).ConfigureAwait(false);
                    }
                    catch (TrackerRefused e)
                    {
                        if (e.Code == 429 || e.Code >= 500) break;
                        Log.Warn("anilist", $"dropped a watch it refused: {e.Code}");
                    }
                    catch (Exception e) when (e is HttpRequestException or TaskCanceledException) { break; }
                    catch (JsonException) { }
                    SetQueue(Queued().Skip(1).ToList());
                }
            }
            finally
            {
                flushing = false;
            }
        });
    }

    /// <summary>Progress only ever goes up, and a title finished there before is left as it is: this may be a rewatch.</summary>
    static async Task Send(string imdb, int season, int episode)
    {
        int id, progress;
        if (episode < 0)
        {
            var all = byImdb.GetValueOrDefault(imdb, new());
            if ((all.FirstOrDefault(e => e.IsFilm) ?? (all.Count == 1 ? all[0] : null)) is not { } film) return;
            (id, progress) = (film.Id, 1);
        }
        else
        {
            var ids = byImdb.GetValueOrDefault(imdb, new()).Where(e => e.IsSeries && e.Season == season).Select(e => e.Id).ToList();
            if (ids.Count > 1) await LearnParts(ids).ConfigureAwait(false);
            if (CourOf(SortedCours(imdb, season), episode) is not { } at) return;
            (id, progress) = at;
        }
        var media = (await Gql("query($id:Int){Media(id:$id){episodes mediaListEntry{progress status}}}", new JsonObject { ["id"] = id }).ConfigureAwait(false))["Media"]!;
        var entry = media["mediaListEntry"];
        if (entry?["status"]?.GetValue<string>() == "COMPLETED") return;
        var total = media["episodes"]?.GetValue<int>();
        var next = Math.Max(progress, entry?["progress"]?.GetValue<int>() ?? 0);
        var status = episode < 0 || total is int t && next >= t ? "COMPLETED" : "CURRENT";
        await Gql("mutation($id:Int,$p:Int,$s:MediaListStatus){SaveMediaListEntry(mediaId:$id,progress:$p,status:$s){id}}",
            new JsonObject { ["id"] = id, ["p"] = episode < 0 ? total ?? 1 : next, ["s"] = status }).ConfigureAwait(false);
    }

    /* ------------------------------------------------------------------ watched there → here */

    /// <summary>The anime list, back as films and episodes by IMDb title and season, and what is being watched as up next.</summary>
    public static void Pull()
    {
        if (sign is not { } s || s.User == 0) return;
        var owner = profile;
        _ = Task.Run(async () =>
        {
            try
            {
                if (!await Mapping().ConfigureAwait(false)) return;
                var rows = new List<JsonNode>();
                for (var chunk = 1; chunk <= MaxChunks; chunk++)
                {
                    var c = (await Gql("query($u:Int,$c:Int){MediaListCollection(userId:$u,type:ANIME,chunk:$c,perChunk:500){hasNextChunk lists{entries{mediaId progress status updatedAt media{episodes title{english romaji} startDate{year month day}}}}}}",
                        new JsonObject { ["u"] = s.User, ["c"] = chunk }).ConfigureAwait(false))["MediaListCollection"]!;
                    foreach (var l in (c["lists"] as JsonArray ?? new()).OfType<JsonNode>()) rows.AddRange((l["entries"] as JsonArray ?? new()).OfType<JsonNode>());
                    if (c["hasNextChunk"]?.GetValue<bool>() != true) break;
                }
                foreach (var e in rows) if (e["media"] is { } m) parts[e["mediaId"]!.GetValue<int>()] = (m["episodes"]?.GetValue<int>(), StartOf(m["startDate"]));
                // The parts of a season not on the list still count toward where the others start.
                await LearnParts(rows.Select(e => byId.GetValueOrDefault(e["mediaId"]!.GetValue<int>())).OfType<Entry>().Where(a => a.IsSeries && a.Season >= 0)
                    .SelectMany(a => byImdb[a.Imdb].Where(x => x.IsSeries && x.Season == a.Season).Select(x => x.Id))).ConfigureAwait(false);

                var films = new HashSet<string>();
                var episodes = new Dictionary<string, HashSet<(int Season, int Episode)>>();
                var upNext = new List<StremioPaused>();
                var unmatched = 0;
                foreach (var e in rows)
                {
                    if (byId.GetValueOrDefault(e["mediaId"]!.GetValue<int>()) is not { } a) { unmatched++; continue; }
                    var status = e["status"]?.GetValue<string>();
                    if (a.IsFilm)
                    {
                        if (status == "COMPLETED") films.Add(a.Imdb);
                        continue;
                    }
                    if (!a.IsSeries || a.Season < 0) { unmatched++; continue; }
                    var progress = e["progress"]?.GetValue<int>() ?? 0;
                    var total = parts.TryGetValue(a.Id, out var p) ? p.Episodes : null;
                    var seen = EpisodesOf(SortedCours(a.Imdb, a.Season), a.Id, status == "COMPLETED" ? total ?? progress : progress);
                    if (!episodes.TryGetValue(a.Imdb, out var set)) episodes[a.Imdb] = set = new();
                    foreach (var n in seen) set.Add((a.Season, n));
                    if (status is "CURRENT" or "REPEATING" && progress > 0 && (total is null || progress < total)
                        && (e["media"]?["title"]?["english"]?.GetValue<string>() ?? e["media"]?["title"]?["romaji"]?.GetValue<string>()) is { } title)
                        upNext.Add(new StremioPaused(a.Imdb, title, null, (a.Season, (seen.LastOrDefault()) + 1), 0, null, (e["updatedAt"]?.GetValue<long>() ?? 0) * 1000));
                }
                if (owner != profile || sign is null) return;
                // One show, one place to carry on: its latest season being watched.
                var next = upNext.OrderByDescending(x => x.LeftAt).DistinctBy(x => x.Imdb).ToList();
                Library = StremioLibrary.Empty with { Films = films, Episodes = episodes, Paused = next };
                Store.SaveOwn(LibraryKey, new SavedLibrary(films.ToList(), episodes.ToDictionary(x => x.Key, x => x.Value.Select(v => $"{v.Season}:{v.Episode}").ToList())));
                Log.Info("anilist", $"pull: {rows.Count} on the list — {films.Count} films, {episodes.Values.Sum(x => x.Count)} episodes of {episodes.Count} series, {next.Count} being watched; {unmatched} not matched to a title");
                Changed?.Invoke();
            }
            catch (Exception e) { Log.Warn("anilist", $"pull: {e.Message}"); }
        });
    }

    static StremioLibrary FromSaved(SavedLibrary? s) => s is null ? StremioLibrary.Empty : StremioLibrary.Empty with
    {
        Films = s.Films.ToHashSet(),
        Episodes = s.Episodes.ToDictionary(p => p.Key, p => p.Value.Select(e => e.Split(':')).Where(e => e.Length == 2).Select(e => (int.Parse(e[0]), int.Parse(e[1]))).ToHashSet()),
    };
}
