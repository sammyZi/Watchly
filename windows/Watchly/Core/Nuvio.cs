using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;

namespace Watchly.Core;

/// <summary>One of a Nuvio account's profiles (up to six).</summary>
public sealed record NuvioProfile(int Index, string Name, string? Color, bool UsesPrimaryAddons);

/// <summary>A Nuvio sign-in: Supabase's tokens, the profile it watches as, and the profile whose addons it uses.</summary>
public sealed record NuvioSession(string Access, string Refresh, long Expires, string? Email, int Profile = 1, int Addons = 1, string? ProfileName = null);

/// <summary>
/// The viewer's Nuvio account (tv/core Nuvio.kt), by Nuvio's public API (nuvio.tv/docs): email and password —
/// Nuvio has no link code — then one of its profiles is picked, its addons come across, where films were left
/// and what was finished come both ways, and My List is its library. Per profile, encrypted with DPAPI like
/// Stremio's; the guest's stays in memory.
/// </summary>
public static class Nuvio
{
    const string Api = "https://api.nuvio.tv";
    /// <summary>Nuvio's publishable key, published in its API docs for public clients — not a secret.</summary>
    const string Key = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN";
    /// <summary>Nuvio's own clients' page size for the library and history, and their batch size.</summary>
    const int Page = 500;
    /// <summary>Calibration knob: a spot sent when it was left lands a moment after the play's own time.</summary>
    const long SlackMs = 30_000;

    public static event Action? Changed;

    static string profile = "";
    static bool guest;
    static NuvioSession? session;
    /// <summary>Signed in, waiting for <see cref="Choose"/>: not saved, so the screen asking stays up.</summary>
    static (string Owner, NuvioSession Session)? pending;

    public static bool Connected => session is not null;
    public static string? Email => session?.Email;
    public static string? ProfileName => session?.ProfileName;
    public static StremioLibrary Library { get; private set; } = StremioLibrary.Empty;

    // ── Parsing (NuvioTest.kt) ──────────────────────────────────────────────

    static string? Str(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0 ? s : null;

    static long Long(JsonNode? n) =>
        n is JsonValue && double.TryParse(n.ToJsonString().Trim('"'), NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? (long)d : 0;

    static int? Int(JsonNode? n) => n is JsonValue ? (int)Long(n) : null;

    static IEnumerable<JsonObject> Rows(string json)
    {
        try { return (JsonNode.Parse(json) as JsonArray)?.OfType<JsonObject>().ToList() ?? new(); }
        catch { return new List<JsonObject>(); }
    }

    public static NuvioSession? ParseSession(string json, long now)
    {
        try
        {
            var o = JsonNode.Parse(json);
            return Str(o?["access_token"]) is { } a && Str(o?["refresh_token"]) is { } r
                ? new NuvioSession(a, r, now + (Long(o?["expires_in"]) is > 0 and var s ? s : 3600) * 1000, Str(o?["user"]?["email"]))
                : null;
        }
        catch { return null; }
    }

    public static List<NuvioProfile> ParseProfiles(string json) => Rows(json)
        .Select(o => new NuvioProfile(Int(o["profile_index"]) ?? 1, Str(o["name"]) ?? $"Profile {Int(o["profile_index"]) ?? 1}", Str(o["avatar_color_hex"]), o["uses_primary_addons"] is JsonValue v && v.TryGetValue<bool>(out var b) && b))
        .OrderBy(p => p.Index).ToList();

    /// <summary>What Nuvio's sign-in says went wrong, in its own words ("Invalid login credentials").</summary>
    static string? Error(string json)
    {
        try { var o = JsonNode.Parse(json); return Str(o?["msg"]) ?? Str(o?["error_description"]) ?? Str(o?["message"]); }
        catch { return null; }
    }

    /// <summary>
    /// Nuvio's watch progress, watched items and library as the app's own <see cref="StremioLibrary"/>. Only IMDb ids.
    /// ponytail: a title Nuvio knows only by "tmdb:…" is left out; map it through TMDB if those turn up.
    /// </summary>
    public static StremioLibrary Parse(string progress, string watched, string library)
    {
        static IEnumerable<JsonObject> Ids(string json) => Rows(json).Where(o => (Str(o["content_id"]) ?? "").StartsWith("tt"));
        var lib = Ids(library).ToList();
        var names = new Dictionary<string, string>();
        foreach (var o in lib) names.TryAdd(Str(o["content_id"])!, Str(o["name"]) ?? "");
        var films = new HashSet<string>();
        var episodes = new Dictionary<string, HashSet<(int, int)>>();
        var finished = new Dictionary<string, long>();
        foreach (var w in Ids(watched))
        {
            var id = Str(w["content_id"])!;
            (int, int)? ep = Int(w["season"]) is int s && Int(w["episode"]) is int e ? (s, e) : null;
            if (Str(w["content_type"]) == "movie") films.Add(id);
            else if (ep is { } x) (episodes.TryGetValue(id, out var set) ? set : episodes[id] = new()).Add(x);
            finished[id] = Math.Max(finished.GetValueOrDefault(id), Long(w["watched_at"]));
        }
        var paused = new List<StremioPaused>();
        foreach (var p in Ids(progress))
        {
            var id = Str(p["content_id"])!;
            var of = Long(p["duration"]);
            if (of <= 0) continue;
            var part = (double)Long(p["position"]) / of;
            if (part < 0.02 || part >= Stremio.WatchedAt) continue;
            var series = Str(p["content_type"]) != "movie";
            (int, int)? ep = Int(p["season"]) is int s && Int(p["episode"]) is int e ? (s, e) : Stremio.EpOfVideo(Str(p["video_id"]));
            if (series && ep is null) continue;
            paused.Add(new StremioPaused(id, names.GetValueOrDefault(id, ""), null, series ? ep : null, part * 100, Long(p["position"]) / 1000.0, Long(p["last_watched"])));
        }
        paused = paused.OrderByDescending(p => p.LeftAt).ToList();
        // Left part-way since it was last finished: not finished, for Continue Watching.
        foreach (var p in paused) if (finished.GetValueOrDefault(p.Imdb) < p.LeftAt) finished.Remove(p.Imdb);
        var seen = lib.Select(o => new StremioSeen(Str(o["content_id"])!, Str(o["name"]) ?? "", Str(o["release_info"]), Str(o["content_type"]) == "movie" ? "movie" : "tv",
            DateTimeOffset.FromUnixTimeMilliseconds(Long(o["added_at"])).UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture), null))
            .OrderByDescending(s => s.At, StringComparer.Ordinal).ToList();
        return new StremioLibrary(films, episodes, paused, seen) { FinishedAt = finished };
    }

    /// <summary>A watch-progress row's key, as Nuvio forms it: the id, and "_s1e2" for an episode.</summary>
    public static string ProgressKey(string imdb, (int Season, int Episode)? ep) => ep is { } e ? $"{imdb}_s{e.Season}e{e.Episode}" : imdb;

    /// <summary>
    /// What this PC played that the Nuvio profile hasn't got — a finish sent as the app was closed, a play offline or
    /// from before signing in: per title its latest play, past <see cref="Stremio.WatchedAt"/> as watched, else its
    /// spot, unless Nuvio has it as recent. A play with no IMDb id, or an episode its file doesn't name, can't be placed.
    /// </summary>
    public static (List<JsonObject> Watched, List<JsonObject> Progress) Backlog(IEnumerable<HistoryItem> items, StremioLibrary there)
    {
        var watched = new List<JsonObject>();
        var progress = new List<JsonObject>();
        foreach (var item in History.LatestPerTitle(items))
        {
            if (item.Imdb is not { } id || !id.StartsWith("tt") || item.LastFileIndex is not int i || item.PositionOf(i) is not double at || item.DurationOf(i) is not double of || of <= 0) continue;
            var r = at / of;
            if (r < 0.02) continue;
            var film = item.Kind == "movie";
            (int Season, int Episode)? ep = null;
            if (!film)
            {
                if (item.Files?.FirstOrDefault(f => f.Index == i)?.Name is not { } file || Search.ParseEpisode(file) is not { } e) continue;
                ep = e;
            }
            var played = item.PlayedAt(i);
            var row = new JsonObject { ["content_id"] = id, ["content_type"] = film ? "movie" : "series" };
            if (ep is { } x) { row["season"] = x.Season; row["episode"] = x.Episode; }
            if (r >= Stremio.WatchedAt)
            {
                if (ep is null ? there.Films.Contains(id) : there.Episodes.GetValueOrDefault(id)?.Contains(ep.Value) == true) continue;
                row["title"] = item.DisplayName.Split(" · ")[0];
                row["watched_at"] = played;
                watched.Add(row);
            }
            else
            {
                if (there.Paused.Any(p => p.Imdb == id && p.Ep == ep && p.LeftAt >= played - SlackMs)) continue;
                // Finished on another device since: this one's old spot would put it back in Continue Watching.
                if (ep is null && there.FinishedAt.GetValueOrDefault(id) >= played) continue;
                row["video_id"] = ep is { } y ? $"{id}:{y.Season}:{y.Episode}" : id;
                row["position"] = (long)(at * 1000);
                row["duration"] = (long)(of * 1000);
                row["last_watched"] = played;
                progress.Add(row);
            }
        }
        return (watched, progress);
    }

    // ── The account ─────────────────────────────────────────────────────────

    static string Slot(string id) => $"nuvio_{(id.Length > 0 ? id : "owner")}";

    static NuvioSession? Read(string id)
    {
        try
        {
            var o = Secrets.Get(Slot(id)) is { } s ? JsonNode.Parse(s) : null;
            return o is null || Str(o["access"]) is not { } a || Str(o["refresh"]) is not { } r ? null
                : new NuvioSession(a, r, Long(o["expires"]), Str(o["email"]), Int(o["profile"]) ?? 1, Int(o["addons"]) ?? 1, Str(o["profileName"]));
        }
        catch { return null; }
    }

    static NuvioSession? Current(string owner) => owner == profile ? session : Read(owner);

    /// <summary>On every profile switch, like <see cref="Stremio.Load"/>.</summary>
    public static void Load(string id, bool isGuest)
    {
        profile = id;
        guest = isGuest;
        session = isGuest ? null : Read(id);
        Library = StremioLibrary.Empty;
        Changed?.Invoke();
        _ = Pull();
    }

    public static void Forget(string id) => Secrets.Put(Slot(id), null);

    /// <summary>Memory only for the guest watching; <paramref name="owner"/>'s own entry otherwise, and live if it is the one watching.</summary>
    static void Save(NuvioSession? next, string owner)
    {
        if (!(guest && owner == profile))
            Secrets.Put(Slot(owner), next is null ? null : new JsonObject
            {
                ["access"] = next.Access, ["refresh"] = next.Refresh, ["expires"] = next.Expires, ["email"] = next.Email,
                ["profile"] = next.Profile, ["addons"] = next.Addons, ["profileName"] = next.ProfileName,
            }.ToJsonString());
        if (owner != profile) return;
        session = next;
        if (next is null) Library = StremioLibrary.Empty;
        Changed?.Invoke();
    }

    /// <summary>One request to Nuvio, with the publishable key and the session's token if any; throws with the status on a refusal.</summary>
    static async Task<string> Send(HttpMethod method, string path, JsonNode? body, NuvioSession? s)
    {
        using var timer = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var req = new HttpRequestMessage(method, Api + path);
        req.Headers.Add("apikey", Key);
        // An app's name, not the browser-like one the app's other requests carry: Nuvio's gateway answers any "Mozilla"
        // with 429 and Retry-After 600 — every sign-in failed "after too many tries" on the first one.
        req.Headers.UserAgent.ParseAdd(Agent);
        if (s is not null) req.Headers.Add("Authorization", $"Bearer {s.Access}");
        if (body is not null) req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var res = await Net.Http.SendAsync(req, timer.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(timer.Token).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode)
        {
            // Nuvio's own reply in the log (no token or password is in it): a refused sign-in said nothing there before.
            Log.Warn("nuvio", $"{method} {path.Split('?')[0]}: {(int)res.StatusCode}, retry after {res.Headers.RetryAfter?.Delta?.TotalSeconds.ToString() ?? "-"} s: {text[..Math.Min(text.Length, 300)]}");
            throw new NuvioRefused((int)res.StatusCode, text, res.Headers.RetryAfter?.Delta);
        }
        return text;
    }

    static readonly string Agent = $"Watchly/{typeof(Nuvio).Assembly.GetName().Version?.ToString(3) ?? "2"} (Windows)";

    sealed class NuvioRefused(int code, string body, TimeSpan? wait) : HttpRequestException(code == 429 ? Busy(wait) : Error(body) ?? $"Nuvio answered {code}")
    {
        public int Code { get; } = code;
    }

    /// <summary>Nuvio's "too many sign-ins": it bars the whole network for a while, so how long, rounded up to the minute.</summary>
    static string Busy(TimeSpan? wait)
    {
        var minutes = Math.Max(1, (int)Math.Ceiling((wait ?? TimeSpan.Zero).TotalMinutes));
        return $"Nuvio has paused sign-ins from your network after too many tries — try again in {minutes} minute{(minutes == 1 ? "" : "s")}.";
    }

    /// <summary>
    /// Email and password, straight to Nuvio. Throws with Nuvio's own words for a wrong password. The account's
    /// profiles come back: with one, it is chosen here; with more, the caller asks which and calls <see cref="Choose"/>.
    /// </summary>
    public static async Task<List<NuvioProfile>> SignIn(string email, string password)
    {
        var owner = profile;
        var answer = await Send(HttpMethod.Post, "/auth/v1/token?grant_type=password", new JsonObject { ["email"] = email.Trim(), ["password"] = password }, null).ConfigureAwait(false);
        var s = ParseSession(answer, Store.Now) ?? throw new HttpRequestException("Nuvio sent no session");
        s = s with { Email = s.Email ?? email.Trim() };
        var all = new List<NuvioProfile>();
        try { all = ParseProfiles(await Send(HttpMethod.Post, "/rest/v1/rpc/sync_pull_profiles", new JsonObject(), s).ConfigureAwait(false)); }
        catch (Exception e) { Log.Warn("nuvio", $"profiles: {e.Message}"); }
        pending = (owner, s);
        if (all.Count <= 1) Choose(all.FirstOrDefault() ?? new NuvioProfile(1, "Main", null, false));
        return all;
    }

    /// <summary>The account's profiles, to pick from.</summary>
    public static async Task<List<NuvioProfile>> Profiles() =>
        ParseProfiles(await Rpc("sync_pull_profiles", new JsonObject(), profile, profiled: false).ConfigureAwait(false) ?? throw new HttpRequestException("Not signed in to Nuvio"));

    /// <summary>Watch as <paramref name="p"/>: its library and where it left films are pulled, and its addons come across.</summary>
    /// <summary>Signed in, and which of the account's profiles not yet picked: nothing is saved until <see cref="Choose"/>.</summary>
    public static bool Choosing => pending?.Owner == profile;

    public static void Choose(NuvioProfile p)
    {
        var owner = profile;
        var s = (pending is { } w && w.Owner == owner ? w.Session : null) ?? Current(owner);
        pending = null;
        if (s is null) return;
        Save(s with { Profile = p.Index, Addons = p.UsesPrimaryAddons ? 1 : p.Index, ProfileName = p.Name }, owner);
        Library = StremioLibrary.Empty;
        _ = Task.Run(async () =>
        {
            await Pull();
            try { await ImportAddons(); } catch (Exception e) { Log.Warn("nuvio", $"addons: {e.Message}"); }
        });
    }

    public static void Disconnect()
    {
        var gone = session;
        if (gone is null) return;
        Save(null, profile);
        _ = Task.Run(async () => { try { await Send(HttpMethod.Post, "/auth/v1/logout", new JsonObject(), gone); } catch { } });
    }

    /// <summary>A refused refresh means the session was ended there: signed out here too.</summary>
    static async Task<NuvioSession?> Refreshed(string owner)
    {
        if (Current(owner) is not { } old) return null;
        try
        {
            var next = ParseSession(await Send(HttpMethod.Post, "/auth/v1/token?grant_type=refresh_token", new JsonObject { ["refresh_token"] = old.Refresh }, null).ConfigureAwait(false), Store.Now);
            if (next is null) return null;
            var kept = old with { Access = next.Access, Refresh = next.Refresh, Expires = next.Expires };
            Save(kept, owner);
            return kept;
        }
        catch (NuvioRefused e) when (e.Code is 400 or 401)
        {
            Save(null, owner);
            return null;
        }
    }

    /// <summary>One RPC with a live token, renewed first if nearly out or refused; <paramref name="profiled"/> ones are about the chosen Nuvio profile. Null once signed out.</summary>
    static async Task<string?> Rpc(string name, JsonObject body, string owner, bool profiled = true)
    {
        if (Current(owner) is not { } now) return null;
        var s = now.Expires - Store.Now < 60_000 ? await Refreshed(owner).ConfigureAwait(false) : now;
        if (s is null) return null;
        if (profiled) body["p_profile_id"] = s.Profile;
        try { return await Send(HttpMethod.Post, $"/rest/v1/rpc/{name}", body, s).ConfigureAwait(false); }
        catch (NuvioRefused e) when (e.Code == 401)
        {
            if (await Refreshed(owner).ConfigureAwait(false) is not { } again) return null;
            return await Send(HttpMethod.Post, $"/rest/v1/rpc/{name}", body, again).ConfigureAwait(false);
        }
    }

    /// <summary>Every page of a paged pull as one array, until a short one; null once signed out.</summary>
    static async Task<string?> AllPages(Func<int, Task<string?>> get)
    {
        var all = new JsonArray();
        for (var i = 0; i < 40; i++)
        {
            if (await get(i).ConfigureAwait(false) is not { } text) return null;
            var page = JsonNode.Parse(text) as JsonArray ?? new JsonArray();
            foreach (var n in page) all.Add(n?.DeepClone());
            if (page.Count < Page) break;
        }
        return all.ToJsonString();
    }

    /// <summary>
    /// Where films were left, what was finished and the whole library — and, up the other way, whatever this PC
    /// played that Nuvio hasn't got (<see cref="Backlog"/>). Every open and sign-in, so a finish lost to the app
    /// being closed goes the next time. Off the network, it all stays as it was.
    /// </summary>
    public static async Task Pull()
    {
        if (session is null) return;
        var owner = profile;
        try
        {
            var progress = await Rpc("sync_pull_watch_progress", new JsonObject { ["p_limit"] = 200 }, owner).ConfigureAwait(false);
            var watched = await AllPages(i => Rpc("sync_pull_watched_items", new JsonObject { ["p_page"] = i + 1, ["p_page_size"] = Page }, owner)).ConfigureAwait(false);
            var library = await AllPages(i => Rpc("sync_pull_library", new JsonObject { ["p_limit"] = Page, ["p_offset"] = i * Page }, owner)).ConfigureAwait(false);
            if (progress is null || watched is null || library is null || owner != profile) return;
            var there = Parse(progress, watched, library);
            Library = there;
            Changed?.Invoke();
            var (finished, left) = Backlog(History.Load(), there);
            foreach (var rows in finished.Chunk(Page))
            {
                await Rpc("sync_push_watched_items", new JsonObject { ["p_items"] = new JsonArray(rows.Select(r => (JsonNode)r.DeepClone()).ToArray()) }, owner).ConfigureAwait(false);
                // Finished: no longer part-way there either.
                var keys = rows.Select(r => (JsonNode)ProgressKey(Str(r["content_id"])!, Int(r["season"]) is int s && Int(r["episode"]) is int e ? (s, e) : null)).ToArray();
                await Rpc("sync_delete_watch_progress", new JsonObject { ["p_keys"] = new JsonArray(keys) }, owner).ConfigureAwait(false);
            }
            foreach (var rows in left.Chunk(Page))
                await Rpc("sync_push_watch_progress", new JsonObject { ["p_entries"] = new JsonArray(rows.Select(r => (JsonNode)r.DeepClone()).ToArray()) }, owner).ConfigureAwait(false);
            if (finished.Count + left.Count > 0) Log.Info("nuvio", $"sent from this PC's history: {finished.Count} watched, {left.Count} part-way");
        }
        catch (Exception e)
        {
            Log.Warn("nuvio", $"pull: {e.Message}");
        }
    }

    /// <summary>The chosen profile's addons, each fetched and added here; one here already is left be. How many came across.</summary>
    public static async Task<int> ImportAddons()
    {
        if (session is not { } s) throw new HttpRequestException("Not signed in to Nuvio");
        if (s.Expires - Store.Now < 60_000) s = await Refreshed(profile).ConfigureAwait(false) ?? throw new HttpRequestException("Signed out of Nuvio");
        var got = await Send(HttpMethod.Get, $"/rest/v1/addons?select=url,enabled&profile_id=eq.{s.Addons}&order=sort_order", null, s).ConfigureAwait(false);
        var added = 0;
        foreach (var row in Rows(got))
        {
            // Watchly has its own of Cinemeta, as with Stremio's.
            if (Addons.Base(Str(row["url"]) ?? "") is not { } url || url.Contains("cinemeta") || Addons.List.Any(a => a.Url == url)) continue;
            try { await Addons.Add(url).ConfigureAwait(false); added++; }
            catch (Exception e) { Log.Warn("nuvio", $"addon {new Uri(url).Host}: {e.Message}"); }
        }
        return added;
    }

    /// <summary>Fire and forget, so Nuvio being down never troubles playback.</summary>
    static void Push(string name, JsonObject body)
    {
        if (session is null) return;
        var owner = profile;
        _ = Task.Run(async () =>
        {
            try { await Rpc(name, body, owner); }
            catch (Exception e) { Log.Warn("nuvio", $"{name}: {e.Message}"); }
        });
    }

    static string TypeOf(string? kind) => kind == "movie" ? "movie" : "series";

    /// <summary>Left part-way: Nuvio keeps the spot, for Continue Watching on any device.</summary>
    public static void Pause(HistoryItem at, (int Season, int Episode)? ep, double seconds, double duration)
    {
        if (at.Imdb is not { } imdb || session is null || duration <= 0) return;
        var now = Store.Now;
        Library = Library with { Paused = Library.Paused.Where(p => !(p.Imdb == imdb && p.Ep == ep)).Prepend(new StremioPaused(imdb, at.DisplayName.Split(" · ")[0], null, ep, seconds / duration * 100, seconds, now)).ToList() };
        Changed?.Invoke();
        var entry = new JsonObject
        {
            ["content_id"] = imdb, ["content_type"] = TypeOf(at.Kind), ["video_id"] = ep is { } e ? $"{imdb}:{e.Season}:{e.Episode}" : imdb,
            ["position"] = (long)(seconds * 1000), ["duration"] = (long)(duration * 1000), ["last_watched"] = now,
        };
        if (ep is { } x) { entry["season"] = x.Season; entry["episode"] = x.Episode; }
        Push("sync_push_watch_progress", new JsonObject { ["p_entries"] = new JsonArray(entry) });
    }

    /// <summary>A film or an episode finished: watched there, and no longer part-way.</summary>
    public static void Watched(HistoryItem at, (int Season, int Episode)? ep)
    {
        if (at.Imdb is not { } imdb || session is null) return;
        var l = Library;
        var episodes = new Dictionary<string, HashSet<(int, int)>>(l.Episodes);
        if (ep is { } x) episodes[imdb] = (l.Episodes.GetValueOrDefault(imdb) ?? new()).Append(x).ToHashSet();
        Library = l with
        {
            Films = ep is null ? l.Films.Append(imdb).ToHashSet() : l.Films,
            Episodes = episodes,
            Paused = l.Paused.Where(p => !(p.Imdb == imdb && p.Ep == ep)).ToList(),
        };
        Changed?.Invoke();
        var item = new JsonObject { ["content_id"] = imdb, ["content_type"] = TypeOf(at.Kind), ["title"] = at.DisplayName.Split(" · ")[0], ["watched_at"] = Store.Now };
        if (ep is { } e) { item["season"] = e.Season; item["episode"] = e.Episode; }
        Push("sync_push_watched_items", new JsonObject { ["p_items"] = new JsonArray(item) });
        Push("sync_delete_watch_progress", new JsonObject { ["p_keys"] = new JsonArray(ProgressKey(imdb, ep)) });
    }

    /// <summary>My List here is the library there: added or taken out together.</summary>
    public static void SetInLibrary(Title title, bool on)
    {
        if (!Connected) return;
        // Here at once, as My List shows it, not at the next pull.
        var seen = Library.Seen.Where(s => s.Imdb != title.Imdb).ToList();
        if (on) seen.Insert(0, new StremioSeen(title.Imdb, title.Name, title.Year, title.Kind, DateTime.UtcNow.ToString("o"), null));
        Library = Library with { Seen = seen };
        Changed?.Invoke();
        var type = title.StremioType == "movie" ? "movie" : "series";
        if (on)
            Push("sync_push_library_items", new JsonObject
            {
                ["p_items"] = new JsonArray(new JsonObject
                {
                    ["content_id"] = title.Imdb, ["content_type"] = type, ["name"] = title.Name, ["poster"] = title.Poster,
                    ["release_info"] = title.Year, ["added_at"] = Store.Now,
                }),
            });
        else Push("sync_delete_library_items", new JsonObject { ["p_keys"] = new JsonArray(new JsonObject { ["content_id"] = title.Imdb, ["content_type"] = type }) });
    }
}
