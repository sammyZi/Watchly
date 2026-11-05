using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json.Nodes;

namespace Watchly.Core;

public sealed record TrackerCode(string DeviceCode, string UserCode, string Link, int ExpiresIn, int Interval);

/// <summary>An answer other than 2xx, with what came with it: OAuth says why in the body.</summary>
public sealed class TrackerRefused(int code, string body) : Exception($"HTTP {code}")
{
    public int Code { get; } = code;
    public string Body { get; } = body;
}

/// <summary>
/// tv/core Trackers.kt: a watch-tracking account, signed in on its own site by a device code, one per
/// profile. What is finished here goes into a saved queue and is sent from it, so a finish with no
/// network goes the next time there is one; what is watched there comes back as <see cref="Library"/>.
/// </summary>
public sealed class Tracker
{
    const long DayMs = 24 * 60 * 60 * 1000L;

    sealed record Tokens(string Access, string? Refresh, long Expires);
    sealed record Watched(List<string> Films, Dictionary<string, List<string>> Episodes);

    public string Name { get; }
    /// <summary>For its logo.</summary>
    public string Site { get; }
    readonly string api, devicePath, tokenPath, revokePath, historyPath, activitiesPath, scope;
    /// <summary>Simkl wants the app named on every call, and takes the anime flag.</summary>
    readonly bool simkl;
    readonly Func<Func<string, Task<string>>, Task<StremioLibrary>> read;
    readonly string clientId;

    public bool Available => clientId.Length > 0;
    public bool Connected => tokens is not null;
    public StremioLibrary Library { get; private set; } = StremioLibrary.Empty;
    /// <summary>On any thread.</summary>
    public event Action? Changed;

    string profile = "";
    bool guest, flushing;
    Tokens? tokens;

    string Key => Name.ToLowerInvariant();
    string QueueKey => $"{Key}-queue";
    string LibraryKey => $"{Key}-library";
    string ActivitiesKey => $"{Key}-activities";
    string Slot(string id) => $"{Key}-{id}";

    public Tracker(string name, string site, string metadata, string api, string devicePath, string tokenPath, string revokePath, string historyPath, string activitiesPath, string scope, bool simkl, Func<Func<string, Task<string>>, Task<StremioLibrary>> read)
    {
        (Name, Site, this.api, this.devicePath, this.tokenPath, this.revokePath, this.historyPath, this.activitiesPath, this.scope, this.simkl, this.read) =
            (name, site, api, devicePath, tokenPath, revokePath, historyPath, activitiesPath, scope, simkl, read);
        clientId = typeof(Tracker).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().FirstOrDefault(a => a.Key == metadata)?.Value?.Trim() ?? "";
    }

    string Url(string path) => api + path + (simkl ? $"{(path.Contains('?') ? '&' : '?')}client_id={Uri.EscapeDataString(clientId)}&app-name=watchly&app-version=2" : "");

    static async Task<string> Ask(HttpMethod method, string url, HttpContent? body, string? token, int seconds)
    {
        using var timer = new CancellationTokenSource(TimeSpan.FromSeconds(seconds));
        using var request = new HttpRequestMessage(method, url) { Content = body };
        if (token is not null) request.Headers.Authorization = new("Bearer", token);
        using var res = await Net.Http.SendAsync(request, timer.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(timer.Token).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode) throw new TrackerRefused((int)res.StatusCode, text);
        return text;
    }

    Task<string> PostForm(string path, Dictionary<string, string> form) => Ask(HttpMethod.Post, Url(path), new FormUrlEncodedContent(form), null, 30);

    /// <summary>On every profile switch, like <see cref="Stremio.Load"/>. After Store has the profile.</summary>
    public void Load(string id, bool isGuest)
    {
        profile = id;
        guest = isGuest;
        tokens = null;
        if (!isGuest && Secrets.Get(Slot(id)) is { } saved)
        {
            try
            {
                var o = JsonNode.Parse(saved)!;
                tokens = new Tokens(o["access"]!.GetValue<string>(), o["refresh"]?.GetValue<string>(), o["expires"]!.GetValue<long>());
            }
            catch (Exception e) when (e is System.Text.Json.JsonException or InvalidOperationException or NullReferenceException)
            {
                Log.Warn(Key, "couldn't read the saved sign-in");
            }
        }
        Library = tokens is not null ? FromSaved(Store.LoadOwn<Watched?>(LibraryKey, null)) : StremioLibrary.Empty;
        Changed?.Invoke();
        Flush();
        Pull();
    }

    /// <summary>Memory only for the guest.</summary>
    void Save(Tokens? next)
    {
        tokens = next;
        if (!guest) Secrets.Put(Slot(profile), next is null ? null : new JsonObject { ["access"] = next.Access, ["refresh"] = next.Refresh, ["expires"] = next.Expires }.ToJsonString());
        if (next is null)
        {
            Library = StremioLibrary.Empty;
            foreach (var key in new[] { QueueKey, LibraryKey, ActivitiesKey }) Store.RemoveOwn(key);
        }
        Changed?.Invoke();
    }

    public void Forget(string id) => Secrets.Put(Slot(id), null);

    public async Task<TrackerCode> Start()
    {
        var o = JsonNode.Parse(await PostForm(devicePath, new() { ["client_id"] = clientId, ["scope"] = scope }).ConfigureAwait(false))!;
        var link = o["verification_uri"]!.GetValue<string>();
        return new TrackerCode(o["device_code"]!.GetValue<string>(), o["user_code"]!.GetValue<string>(), o["verification_uri_complete"]?.GetValue<string>() ?? link,
            o["expires_in"]?.GetValue<int>() ?? 300, o["interval"]?.GetValue<int>() ?? 5);
    }

    static string? Error(string json)
    {
        try { return JsonNode.Parse(json)?["error"]?.GetValue<string>(); }
        catch (Exception e) when (e is System.Text.Json.JsonException or InvalidOperationException) { return null; }
    }

    static Tokens? ParseTokens(string json)
    {
        try
        {
            var o = JsonNode.Parse(json);
            return o?["access_token"]?.GetValue<string>() is { } access
                ? new Tokens(access, o["refresh_token"]?.GetValue<string>(), Store.Now + (o["expires_in"]?.GetValue<long>() ?? 7 * 24 * 3600L) * 1000)
                : null;
        }
        catch (Exception e) when (e is System.Text.Json.JsonException or InvalidOperationException) { return null; }
    }

    /// <summary>Polls until approved (true), or refused or out of time (false). <paramref name="ct"/> cancels.</summary>
    public async Task<bool> Await(TrackerCode code, CancellationToken ct)
    {
        var deadline = Store.Now + code.ExpiresIn * 1000L;
        var wait = code.Interval;
        while (Store.Now < deadline)
        {
            await Task.Delay(wait * 1000, ct).ConfigureAwait(false);
            string answer;
            try
            {
                answer = await PostForm(tokenPath, new() { ["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code", ["client_id"] = clientId, ["device_code"] = code.DeviceCode }).ConfigureAwait(false);
            }
            catch (TrackerRefused e)
            {
                switch (Error(e.Body))
                {
                    case "slow_down": wait += 5; break;
                    case "access_denied" or "expired_token" or "invalid_client" or "invalid_grant": return false;
                }
                continue;
            }
            catch (Exception e) when (e is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
            {
                continue;
            }
            if (ParseTokens(answer) is { } got)
            {
                Save(got);
                Pull();
                return true;
            }
        }
        return false;
    }

    public void Disconnect()
    {
        if (tokens is not { } gone) return;
        Save(null);
        _ = Task.Run(async () =>
        {
            try { await PostForm(revokePath, new() { ["token"] = gone.Access, ["client_id"] = clientId }).ConfigureAwait(false); }
            catch (Exception e) { Log.Warn(Key, $"revoke: {e.Message}"); }
        });
    }

    /// <summary>Tokens expire: a refused refresh means the viewer took the app away there, so it signs out here too.</summary>
    async Task<Tokens?> Refreshed()
    {
        if (tokens is not { } old) return null;
        if (old.Refresh is not { } refresh)
        {
            Save(null);
            return null;
        }
        Tokens? next;
        try
        {
            next = ParseTokens(await PostForm(tokenPath, new() { ["grant_type"] = "refresh_token", ["client_id"] = clientId, ["refresh_token"] = refresh }).ConfigureAwait(false));
        }
        catch (TrackerRefused e)
        {
            if (e.Code is 400 or 401) Save(null);
            return null;
        }
        if (next is null) return null;
        next = next with { Refresh = next.Refresh ?? refresh };
        Save(next);
        return next;
    }

    /// <summary><paramref name="call"/> with a live token, renewed first if it is nearly out or turns out refused. Null once signed out.</summary>
    async Task<string?> Authed(Func<string, Task<string>> call)
    {
        if (tokens is not { } now) return null;
        var t = now.Expires - Store.Now < DayMs ? await Refreshed().ConfigureAwait(false) : now;
        if (t is null) return null;
        try
        {
            return await call(t.Access).ConfigureAwait(false);
        }
        catch (TrackerRefused e) when (e.Code == 401)
        {
            return await Refreshed().ConfigureAwait(false) is { } again ? await call(again.Access).ConfigureAwait(false) : null;
        }
    }

    List<string> Queued() => Store.LoadOwn(QueueKey, new List<string>());

    void SetQueue(List<string> bodies)
    {
        if (bodies.Count == 0) Store.RemoveOwn(QueueKey);
        else Store.SaveOwn(QueueKey, bodies);
    }

    /// <summary>Sends the queue in order; no network or a busy service leaves the rest for next time.</summary>
    public void Flush()
    {
        if (flushing || tokens is null) return;
        flushing = true;
        _ = Task.Run(async () =>
        {
            try
            {
                var owner = profile;
                while (owner == profile && Queued().FirstOrDefault() is { } body)
                {
                    try
                    {
                        var sent = await Authed(token => Ask(HttpMethod.Post, Url(historyPath), new StringContent(body, Encoding.UTF8, "application/json"), token, 30)).ConfigureAwait(false);
                        if (sent is null) break;
                    }
                    catch (TrackerRefused e)
                    {
                        if (e.Code == 429 || e.Code >= 500) break;
                        Log.Warn(Key, $"dropped a watch it refused: {e.Code}");
                    }
                    catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
                    {
                        break;
                    }
                    SetQueue(Queued().Skip(1).ToList());
                }
            }
            finally
            {
                flushing = false;
            }
        });
    }

    /// <summary>
    /// What is watched there. Asks first whether anything moved, as both services require, then reads
    /// it all and keeps it for the next launch.
    /// </summary>
    // ponytail: a full read when anything moved, so finishing a film costs one at next launch; Simkl's date_from deltas if libraries get huge.
    public void Pull()
    {
        if (tokens is null) return;
        var owner = profile;
        _ = Task.Run(async () =>
        {
            try
            {
                if (await Authed(token => Ask(HttpMethod.Get, Url(activitiesPath), null, token, 15)).ConfigureAwait(false) is not { } activities) return;
                if (activities == Store.LoadOwn<string?>(ActivitiesKey, null)) return;
                var got = await read(async path => await Authed(token => Ask(HttpMethod.Get, Url(path), null, token, 60)).ConfigureAwait(false) ?? throw new HttpRequestException("Signed out")).ConfigureAwait(false);
                if (owner != profile || tokens is null) return;
                Library = got;
                Store.SaveOwn(LibraryKey, ToSaved(got));
                Store.SaveOwn(ActivitiesKey, activities);
                Changed?.Invoke();
            }
            catch (Exception e)
            {
                Log.Warn(Key, $"pull: {e.Message}");
            }
        });
    }

    static Watched ToSaved(StremioLibrary l) =>
        new(l.Films.ToList(), l.Episodes.ToDictionary(p => p.Key, p => p.Value.Select(e => $"{e.Season}:{e.Episode}").ToList()));

    static StremioLibrary FromSaved(Watched? w) => w is null ? StremioLibrary.Empty : StremioLibrary.Empty with
    {
        Films = w.Films.ToHashSet(),
        Episodes = w.Episodes.ToDictionary(p => p.Key, p => p.Value.Select(e => e.Split(':')).Where(e => e.Length == 2).Select(e => (int.Parse(e[0]), int.Parse(e[1]))).ToHashSet()),
    };

    /// <summary>One film or episode, watched now: the body Simkl's /sync/history and MDBList's /sync/watched both take.</summary>
    public static string HistoryBody(string imdb, (int Season, int Episode)? ep, string at, bool animeSeasons)
    {
        var ids = new JsonObject { ["imdb"] = imdb };
        if (ep is not { } e) return new JsonObject { ["movies"] = new JsonArray(new JsonObject { ["ids"] = ids, ["watched_at"] = at }) }.ToJsonString();
        var season = new JsonObject { ["number"] = e.Season, ["episodes"] = new JsonArray(new JsonObject { ["number"] = e.Episode, ["watched_at"] = at }) };
        var show = new JsonObject { ["ids"] = ids, ["seasons"] = new JsonArray(season) };
        // Has Simkl route these season numbers to its own anime entries; on a show that isn't anime it does nothing.
        if (animeSeasons) show["use_tvdb_anime_seasons"] = true;
        return new JsonObject { ["shows"] = new JsonArray(show) }.ToJsonString();
    }

    /// <summary>A film or an episode finished.</summary>
    public void Finished(HistoryItem at, (int Season, int Episode)? ep)
    {
        if (at.Imdb is not { } imdb || tokens is null) return;
        var queue = Queued();
        queue.Add(HistoryBody(imdb, ep, DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'"), simkl));
        SetQueue(queue);
        Flush();
    }
}

public static class Trackers
{
    /// <summary>Far past any real library; a cursor that never ends must not spend the day's requests.</summary>
    const int MaxPages = 30;

    public static readonly Tracker Simkl = new("Simkl", "simkl.com", "SimklClientId", "https://api.simkl.com", "/oauth2/device", "/oauth2/token", "/oauth2/revoke", "/sync/history", "/sync/activities", "media:read media:write", true, async get =>
        Plus(Plus(ParseSimkl(await get("/sync/all-items/movies/completed?extended=ids_only").ConfigureAwait(false)),
            ParseSimkl(await get("/sync/all-items/shows?extended=full&include_all_episodes=yes").ConfigureAwait(false))),
            ParseSimkl(await get("/sync/all-items/anime?extended=full_anime_seasons&include_all_episodes=yes").ConfigureAwait(false))));

    public static readonly Tracker Mdblist = new("MDBList", "mdblist.com", "MdblistClientId", "https://api.mdblist.com", "/oauth/device-authorization/", "/oauth/token/", "/oauth/revoke_token/", "/sync/watched", "/sync/last_activities", "write", false, async get =>
    {
        var all = StremioLibrary.Empty;
        foreach (var type in new[] { "movie", "episode" })
        {
            string? cursor = null;
            for (var page = 0; page < MaxPages; page++)
            {
                var (got, next) = ParseMdblist(await get($"/sync/watched?mediatype={type}&limit=1000{(cursor is null ? "" : $"&cursor={Uri.EscapeDataString(cursor)}")}").ConfigureAwait(false));
                all = Plus(all, got);
                if ((cursor = next) is null) break;
            }
        }
        return all;
    });

    public static readonly Tracker[] All = { Simkl, Mdblist };

    /// <summary>Calibration knob: how soon coming back to the window asks the accounts again.</summary>
    const long RefreshMs = 60_000;
    static long refreshedAt;

    /// <summary>core refreshWatched: coming back to the window, what another device did meanwhile. Asked only at launch, a film finished on the phone stayed part-watched here.</summary>
    public static void Refresh()
    {
        if (Store.Now - refreshedAt < RefreshMs) return;
        refreshedAt = Store.Now;
        _ = Stremio.Pull();
        foreach (var t in All) t.Pull();
    }

    static Trackers() => Net.OnlineChanged += () => { if (Net.IsOnline) foreach (var t in All) t.Flush(); };

    /// <summary>Stremio's library with what the tracking accounts say has been watched.</summary>
    public static StremioLibrary Everywhere => All.Aggregate(Stremio.Library, (all, t) => Plus(all, t.Library));

    /// <summary>Everything watched on either side; the paused and seen lists stay the first's, as only Stremio has them.</summary>
    static StremioLibrary Plus(StremioLibrary a, StremioLibrary b) => a with
    {
        Films = a.Films.Union(b.Films).ToHashSet(),
        Episodes = a.Episodes.Keys.Union(b.Episodes.Keys).ToDictionary(k => k, k =>
            a.Episodes.GetValueOrDefault(k, new()).Union(b.Episodes.GetValueOrDefault(k, new())).ToHashSet()),
    };

    static IEnumerable<JsonNode> Each(JsonNode? list) => (list as JsonArray)?.Where(n => n is not null).Select(n => n!) ?? Enumerable.Empty<JsonNode>();

    static string? Imdb(JsonNode? owner) => owner?["ids"]?["imdb"]?.GetValue<string>();

    /// <summary>Simkl's /sync/all-items, any of its types. Anime episodes carry the season numbers the rest of the app uses under tvdb.</summary>
    public static StremioLibrary ParseSimkl(string json)
    {
        JsonNode? o;
        try { o = JsonNode.Parse(json); }
        catch (System.Text.Json.JsonException) { return StremioLibrary.Empty; }
        // A list of nothing comes back as null, or an empty array, not an object.
        if (o is not JsonObject) return StremioLibrary.Empty;
        var films = new HashSet<string>();
        var episodes = new Dictionary<string, HashSet<(int, int)>>();
        foreach (var type in new[] { "movies", "shows", "anime" })
            foreach (var item in Each(o[type]))
            {
                if (Imdb(item["movie"] ?? item["show"]) is not { } imdb) continue;
                if (type == "movies" || item["anime_type"]?.GetValue<string>() == "movie")
                {
                    if (item["status"]?.GetValue<string>() == "completed") films.Add(imdb);
                    continue;
                }
                foreach (var s in Each(item["seasons"]))
                    foreach (var e in Each(s["episodes"]))
                    {
                        if (e["watched"]?.GetValue<bool>() == false) continue;
                        var tvdb = e["tvdb"];
                        var at = tvdb is not null
                            ? (tvdb["season"]?.GetValue<int>() ?? 0, tvdb["episode"]?.GetValue<int>() ?? 0)
                            : (s["number"]?.GetValue<int>() ?? 0, e["number"]?.GetValue<int>() ?? 0);
                        if (!episodes.TryGetValue(imdb, out var set)) episodes[imdb] = set = new();
                        set.Add(at);
                    }
            }
        return StremioLibrary.Empty with { Films = films, Episodes = episodes };
    }

    /// <summary>One page of MDBList's /sync/watched, and the cursor to the next.</summary>
    public static (StremioLibrary Library, string? Next) ParseMdblist(string json)
    {
        JsonNode? o;
        try { o = JsonNode.Parse(json); }
        catch (System.Text.Json.JsonException) { return (StremioLibrary.Empty, null); }
        if (o is not JsonObject) return (StremioLibrary.Empty, null);
        var films = Each(o["movies"]).Select(m => Imdb(m["movie"])).OfType<string>().ToHashSet();
        var episodes = new Dictionary<string, HashSet<(int, int)>>();
        foreach (var row in Each(o["episodes"]))
        {
            if (row["episode"] is not { } e || Imdb(e["show"]) is not { } imdb) continue;
            if (!episodes.TryGetValue(imdb, out var set)) episodes[imdb] = set = new();
            set.Add((e["season"]?.GetValue<int>() ?? 0, e["number"]?.GetValue<int>() ?? 0));
        }
        return (StremioLibrary.Empty with { Films = films, Episodes = episodes }, o["pagination"]?["next_cursor"]?.GetValue<string>());
    }
}
