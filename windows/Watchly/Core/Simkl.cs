using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>Where the viewer stopped, on any device: <see cref="Progress"/> is 0–100; <see cref="Ep"/> is null for a film.</summary>
public sealed record SimklPaused(string Imdb, string Title, string? Year, (int Season, int Episode)? Ep, double Progress)
{
    public string Kind => Ep is null ? "movie" : "tv";
}

/// <summary>A title on the viewer's Simkl, for the Library: <see cref="Last"/> is "S01E05" for a show.</summary>
public sealed record SimklSeen(string Imdb, string Title, string? Year, string Kind, string At, string? Last);

/// <summary>What Simkl knows the viewer has seen, by IMDb id, and what they left part-way.</summary>
public sealed record SimklLibrary(
    HashSet<string> Films,
    Dictionary<string, HashSet<(int Season, int Episode)>> Episodes,
    List<SimklPaused> Paused,
    List<SimklSeen> Seen)
{
    public static readonly SimklLibrary Empty = new(new(), new(), new(), new());

    public double? PausedAt(string imdb, (int, int)? ep) => Paused.FirstOrDefault(p => p.Imdb == imdb && p.Ep == ep)?.Progress;

    /// <summary>Simkl's titles that <paramref name="items"/> never played — a reinstall, another device — paused ones first.</summary>
    public List<SimklSeen> NotIn(IEnumerable<HistoryItem> items)
    {
        var here = items.Select(i => i.Imdb).Where(i => i is not null).ToHashSet();
        var left = Paused.Select(p => new SimklSeen(p.Imdb, p.Title, p.Year, p.Kind, "", p.Ep is { } e ? $"S{e.Season:00}E{e.Episode:00}" : null));
        return left.Concat(Seen).Where(s => !here.Contains(s.Imdb)).DistinctBy(s => s.Imdb).ToList();
    }
}

/// <summary>
/// Simkl, AUTH V2 — the same connection the phone and TV make (tv/core Simkl.kt). A device code the
/// viewer approves in the browser; tokens per profile, encrypted with DPAPI like the SubDL key; the
/// guest's stay in memory. Finished films and episodes go up as watched, part-way ones as paused,
/// and Simkl's library comes back down for the Library, Continue Watching and a series' ticks.
/// </summary>
public static class Simkl
{
    const string Api = "https://api.simkl.com";
    const long Day = 86_400_000;
    /// <summary>How far in counts as watched — Simkl's own line.</summary>
    public const double WatchedAt = 0.8;

    /// <summary>From tv/.env or mobile/.env at build time (SIMKL_CLIENT_ID); empty leaves Simkl off. Not a secret.</summary>
    public static readonly string ClientId = typeof(Simkl).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
        .FirstOrDefault(a => a.Key == "SimklClientId")?.Value?.Trim() ?? "";

    public static bool Available => ClientId.Length > 0;

    /// <summary>Connected or not, or the library moved. Raised on a thread-pool thread.</summary>
    public static event Action? Changed;

    public static bool Connected => tokens is not null;
    public static SimklLibrary Library { get; private set; } = SimklLibrary.Empty;

    sealed record Tokens(string Access, string Refresh, long ExpiresAt);

    public sealed record DeviceCode(string Device, string UserCode, string Uri, string UriComplete, int ExpiresIn, int Interval);

    static string profile = "";
    static bool guest;
    static Tokens? tokens;

    static string Params => $"client_id={Uri.EscapeDataString(ClientId)}&app-name=watchly&app-version=2";
    static string FileOf(string id) => Path.Combine(Store.Root, $"simkl_{(id.Length > 0 ? id : "owner")}.bin");

    /// <summary>On every profile switch, like History.Load.</summary>
    public static void Load(string id, bool isGuest)
    {
        profile = id;
        guest = isGuest;
        tokens = isGuest ? null : ReadTokens(id);
        Library = SimklLibrary.Empty;
        Changed?.Invoke();
        _ = Pull();
    }

    public static void Forget(string id)
    {
        try { File.Delete(FileOf(id)); } catch { }
    }

    static Tokens? ReadTokens(string id)
    {
        try
        {
            if (!File.Exists(FileOf(id))) return null;
            var o = JsonNode.Parse(Encoding.UTF8.GetString(Secrets.Unprotect(File.ReadAllBytes(FileOf(id)))))!;
            return new Tokens((string)o["access"]!, (string)o["refresh"]!, (long)o["expiresAt"]!);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Only if <paramref name="owner"/> is still the one using the app: a refresh landing after a switch must not cross profiles.</summary>
    static void Save(Tokens? next, string owner)
    {
        if (!(guest && owner == profile))
        {
            try
            {
                if (next is null) File.Delete(FileOf(owner));
                else
                {
                    Directory.CreateDirectory(Store.Root);
                    var json = new JsonObject { ["access"] = next.Access, ["refresh"] = next.Refresh, ["expiresAt"] = next.ExpiresAt }.ToJsonString();
                    File.WriteAllBytes(FileOf(owner), Secrets.Protect(Encoding.UTF8.GetBytes(json)));
                }
            }
            catch (Exception e)
            {
                Log.Warn("simkl", $"Could not store the Simkl sign-in: {e.Message}");
            }
        }
        if (owner != profile) return;
        tokens = next;
        Changed?.Invoke();
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    static async Task<(int Status, string Body)> Call(HttpMethod method, string path, HttpContent? body = null, string? bearer = null)
    {
        using var req = new HttpRequestMessage(method, $"{Api}{path}{(path.Contains('?') ? "&" : "?")}{Params}") { Content = body };
        req.Headers.UserAgent.ParseAdd("watchly/2");
        if (bearer is not null) req.Headers.Authorization = new("Bearer", bearer);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var res = await Net.Http.SendAsync(req, cts.Token).ConfigureAwait(false);
        return ((int)res.StatusCode, await res.Content.ReadAsStringAsync().ConfigureAwait(false));
    }

    static FormUrlEncodedContent Form(params (string, string)[] fields) => new(fields.Select(f => new KeyValuePair<string, string>(f.Item1, f.Item2)));

    static StringContent JsonBody(JsonNode node) => new(node.ToJsonString(), Encoding.UTF8, "application/json");

    static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    static Tokens ParseTokens(string json, Tokens? old = null)
    {
        var o = JsonNode.Parse(json)!;
        var refresh = (string?)o["refresh_token"];
        return new((string)o["access_token"]!, string.IsNullOrEmpty(refresh) ? old?.Refresh ?? "" : refresh, Now + ((long?)o["expires_in"] ?? 7 * 86400) * 1000);
    }

    /// <summary>A call with this profile's token: refreshed a day early, or once on a 401; a refused refresh means connecting again.</summary>
    static async Task<string> Authed(string owner, HttpMethod method, string path, HttpContent? body = null)
    {
        var t = tokens ?? throw new InvalidOperationException("Not connected");
        if (t.ExpiresAt - Now < Day) t = await Refresh(t, owner).ConfigureAwait(false);
        var (status, text) = await Call(method, path, body, t.Access).ConfigureAwait(false);
        if (status == 401)
        {
            t = await Refresh(t, owner).ConfigureAwait(false);
            // A content can only be sent once: the retry builds its own.
            (status, text) = await Call(method, path, body is null ? null : new StringContent(await body.ReadAsStringAsync().ConfigureAwait(false), Encoding.UTF8, "application/json"), t.Access).ConfigureAwait(false);
        }
        if (status is < 200 or > 299) throw new HttpRequestException($"Simkl {path} returned {status}");
        return text;
    }

    static async Task<Tokens> Refresh(Tokens old, string owner)
    {
        var (status, text) = await Call(HttpMethod.Post, "/oauth2/token", Form(("grant_type", "refresh_token"), ("client_id", ClientId), ("refresh_token", old.Refresh))).ConfigureAwait(false);
        if (status is 400 or 401)
        {
            Save(null, owner);
            throw new HttpRequestException("Simkl wants you to connect again");
        }
        if (status is < 200 or > 299) throw new HttpRequestException($"Simkl refresh returned {status}");
        var fresh = ParseTokens(text, old);
        Save(fresh, owner);
        return fresh;
    }

    // ── Connecting ──────────────────────────────────────────────────────────

    public static async Task<DeviceCode> Start()
    {
        var (status, text) = await Call(HttpMethod.Post, "/oauth2/device", Form(("client_id", ClientId), ("scope", "media:read media:write"))).ConfigureAwait(false);
        if (status is < 200 or > 299) throw new HttpRequestException(status == 401 ? "This build's Simkl client ID isn't an AUTH V2 app." : $"Simkl returned {status}");
        var o = JsonNode.Parse(text)!;
        var uri = (string?)o["verification_uri"] ?? "https://simkl.com/pin";
        var code = (string)o["user_code"]!;
        return new((string)o["device_code"]!, code, uri, (string?)o["verification_uri_complete"] ?? $"{uri}?user_code={code}",
            (int?)o["expires_in"] ?? 900, (int?)o["interval"] ?? 5);
    }

    /// <summary>
    /// Polls until approved (true) or the code runs out (false). A viewer who declines looks exactly
    /// like one still deciding, so the code's lifetime is the only end; <paramref name="ct"/> cancels.
    /// </summary>
    public static async Task<bool> Await(DeviceCode code, CancellationToken ct)
    {
        var owner = profile;
        var deadline = Now + code.ExpiresIn * 1000L;
        var wait = code.Interval * 1000;
        while (Now < deadline)
        {
            await Task.Delay(wait, ct).ConfigureAwait(false);
            (int Status, string Body) poll;
            try
            {
                poll = await Call(HttpMethod.Post, "/oauth2/token", Form(("grant_type", "urn:ietf:params:oauth:grant-type:device_code"), ("client_id", ClientId), ("device_code", code.Device))).ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested)
            {
                continue;
            }
            if (poll.Status is >= 200 and <= 299)
            {
                // A scope Simkl doesn't know is quietly read-only, which would fail only at the first write.
                if (!((string?)JsonNode.Parse(poll.Body)?["scope"] ?? "").Contains("media:write")) throw new HttpRequestException("Simkl gave read-only access.");
                Save(ParseTokens(poll.Body), owner);
                if (owner == profile) _ = Pull();
                return true;
            }
            var error = (string?)(JsonNode.Parse(poll.Body.Length > 0 ? poll.Body : "{}")?["error"]) ?? "";
            switch (error)
            {
                case "authorization_pending": break;
                // The rejected poll restarts Simkl's timer, so actually wait the longer interval.
                case "slow_down": wait += 5000; break;
                case "expired_token": return false;
                case "invalid_client": throw new HttpRequestException("This build's Simkl client ID isn't an AUTH V2 app.");
                default: throw new HttpRequestException($"Simkl said no ({poll.Status}).");
            }
        }
        return false;
    }

    public static void Disconnect()
    {
        var gone = tokens;
        if (gone is null) return;
        Save(null, profile);
        Library = SimklLibrary.Empty;
        Changed?.Invoke();
        // Either half revokes the whole grant; a 200 proves nothing, so nothing waits on it.
        _ = Task.Run(async () => { try { await Call(HttpMethod.Post, "/oauth2/revoke", Form(("client_id", ClientId), ("token", gone.Refresh))); } catch { } });
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    static bool IsImdb(string? id) => id is not null && Regex.IsMatch(id, "^tt\\d+$");

    /// <summary>Films and episodes in one /sync/history body; null when nothing has an IMDb id.</summary>
    public static JsonObject? HistoryBody(IEnumerable<string> films, IReadOnlyDictionary<string, HashSet<(int Season, int Episode)>> episodes)
    {
        var movies = new JsonArray(films.Where(IsImdb).Select(f => (JsonNode)new JsonObject { ["ids"] = new JsonObject { ["imdb"] = f } }).ToArray());
        var shows = new JsonArray(episodes.Where(e => IsImdb(e.Key) && e.Value.Count > 0).Select(e => (JsonNode)new JsonObject
        {
            ["ids"] = new JsonObject { ["imdb"] = e.Key },
            ["seasons"] = new JsonArray(e.Value.GroupBy(x => x.Season).OrderBy(g => g.Key).Select(g => (JsonNode)new JsonObject
            {
                ["number"] = g.Key,
                ["episodes"] = new JsonArray(g.OrderBy(x => x.Episode).Select(x => (JsonNode)new JsonObject { ["number"] = x.Episode }).ToArray()),
            }).ToArray()),
        }).ToArray());
        if (movies.Count == 0 && shows.Count == 0) return null;
        var o = new JsonObject();
        if (movies.Count > 0) o["movies"] = movies;
        if (shows.Count > 0) o["shows"] = shows;
        return o;
    }

    static JsonObject? ScrobbleBody(string imdb, (int Season, int Episode)? ep, double progress)
    {
        if (!IsImdb(imdb)) return null;
        var o = new JsonObject { ["progress"] = Math.Round(progress, 2) };
        var ids = new JsonObject { ["ids"] = new JsonObject { ["imdb"] = imdb } };
        if (ep is { } e)
        {
            o["show"] = ids;
            o["episode"] = new JsonObject { ["season"] = e.Season, ["number"] = e.Episode };
        }
        else o["movie"] = ids;
        return o;
    }

    /// <summary>A film or an episode finished. Fire and forget: Simkl being down never troubles playback.</summary>
    public static void Watched(string? imdb, (int Season, int Episode)? ep)
    {
        if (tokens is null || imdb is null) return;
        var body = ep is { } e ? HistoryBody(Array.Empty<string>(), new Dictionary<string, HashSet<(int, int)>> { [imdb] = new() { e } }) : HistoryBody(new[] { imdb }, new Dictionary<string, HashSet<(int, int)>>());
        if (body is null) return;
        var owner = profile;
        // Here at once, as Simkl will have it: watched, and no longer paused.
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
        _ = Task.Run(async () =>
        {
            try { await Authed(owner, HttpMethod.Post, "/sync/history", JsonBody(body)); }
            catch (Exception err) { Log.Warn("simkl", $"history: {err.Message}"); }
        });
    }

    /// <summary>Left part-way: saved on Simkl as a paused playback, which any device can resume from.</summary>
    public static void Pause(string? imdb, (int Season, int Episode)? ep, double progress, string title)
    {
        if (tokens is null || imdb is null || ScrobbleBody(imdb, ep, progress) is not { } body) return;
        var owner = profile;
        Library = Library with { Paused = Library.Paused.Where(p => !(p.Imdb == imdb && p.Ep == ep)).Prepend(new SimklPaused(imdb, title, null, ep, progress)).ToList() };
        Changed?.Invoke();
        _ = Task.Run(async () =>
        {
            try { await Authed(owner, HttpMethod.Post, "/scrobble/pause", JsonBody(body)); }
            catch (Exception err) { Log.Warn("simkl", $"pause: {err.Message}"); }
        });
    }

    // ── Pulling ─────────────────────────────────────────────────────────────

    static string? ImdbOf(JsonNode? row) =>
        (row?["movie"] ?? row?["show"] ?? row?["anime"])?["ids"]?["imdb"]?.GetValue<string>() is { } id && IsImdb(id) ? id : null;

    static string? Str(JsonNode? n) => n is null ? null : n.GetValueKind() == System.Text.Json.JsonValueKind.String ? (string?)n : n.ToJsonString();

    /// <summary>/sync/all-items with extended=full: completed films, every episode recorded on a show or anime, and the titles themselves.</summary>
    public static SimklLibrary ParseLibrary(string json)
    {
        JsonNode? o;
        try { o = JsonNode.Parse(json); } catch { return SimklLibrary.Empty; }
        var films = new HashSet<string>();
        var episodes = new Dictionary<string, HashSet<(int, int)>>();
        var seen = new List<SimklSeen>();
        foreach (var row in o?["movies"]?.AsArray() ?? new JsonArray())
        {
            if ((string?)row?["status"] != "completed" || ImdbOf(row) is not { } imdb) continue;
            films.Add(imdb);
            seen.Add(new(imdb, (string?)row!["movie"]?["title"] ?? "", Str(row["movie"]?["year"]), "movie", (string?)row["last_watched_at"] ?? "", null));
        }
        foreach (var row in (o?["shows"]?.AsArray() ?? new JsonArray()).Concat(o?["anime"]?.AsArray() ?? new JsonArray()))
        {
            if (ImdbOf(row) is not { } imdb) continue;
            foreach (var s in row!["seasons"]?.AsArray() ?? new JsonArray())
                foreach (var e in s?["episodes"]?.AsArray() ?? new JsonArray())
                {
                    if (!episodes.TryGetValue(imdb, out var set)) episodes[imdb] = set = new();
                    set.Add(((int?)s!["number"] ?? 1, (int?)e?["number"] ?? 0));
                }
            var status = (string?)row["status"];
            // Plan to watch is a list, not history.
            if (status == "plantowatch" || (!episodes.ContainsKey(imdb) && status != "completed")) continue;
            var show = row["show"] ?? row["anime"];
            seen.Add(new(imdb, (string?)show?["title"] ?? "", Str(show?["year"]), "tv", (string?)row["last_watched_at"] ?? "", (string?)row["last_watched"]));
        }
        return new(films, episodes, new(), seen.OrderByDescending(s => s.At, StringComparer.Ordinal).ToList());
    }

    /// <summary>/sync/playback: newest first, IMDb titles only, since that is how Cinemeta finds them.</summary>
    public static List<SimklPaused> ParsePlayback(string json)
    {
        JsonNode? o;
        try { o = JsonNode.Parse(json); } catch { return new(); }
        var list = new List<SimklPaused>();
        foreach (var p in o as JsonArray ?? new JsonArray())
        {
            if (ImdbOf(p) is not { } imdb) continue;
            var what = p!["movie"] ?? p["show"] ?? p["anime"];
            (int, int)? ep = (string?)p["type"] == "movie" || p["episode"] is null ? null : ((int?)p["episode"]!["season"] ?? 1, (int?)p["episode"]!["number"] ?? 0);
            list.Add(new(imdb, (string?)what?["title"] ?? "", Str(what?["year"]), ep, (double?)p["progress"] ?? 0));
        }
        return list;
    }

    /// <summary>What this device has watched through (<see cref="WatchedAt"/>), by IMDb id; an episode by its file's S/E.</summary>
    public static (HashSet<string> Films, Dictionary<string, HashSet<(int, int)>> Episodes) LocalWatched(IEnumerable<HistoryItem> items)
    {
        var films = new HashSet<string>();
        var episodes = new Dictionary<string, HashSet<(int, int)>>();
        foreach (var item in items)
        {
            if (item.Imdb is not { } imdb || item.Positions is null) continue;
            foreach (var (index, at) in item.Positions)
            {
                var of = item.DurationOf(index);
                if (of is not > 0 || at / of.Value < WatchedAt) continue;
                if (item.Kind == "movie") films.Add(imdb);
                else if (item.Files?.FirstOrDefault(f => f.Index == index) is { } file && Search.ParseEpisode(file.Name) is { } ep)
                {
                    if (!episodes.TryGetValue(imdb, out var set)) episodes[imdb] = set = new();
                    set.Add(ep);
                }
            }
        }
        return (films, episodes);
    }

    /// <summary>Everything left part-way here (one per title) that Simkl has neither paused nor as watched. Newest first.</summary>
    public static List<SimklPaused> PausedBacklog(IEnumerable<HistoryItem> items, SimklLibrary simkl) =>
        History.LatestPerTitle(items).Select(item =>
        {
            if (!IsImdb(item.Imdb) || item.Ratio <= 0.02 || item.Ratio >= WatchedAt) return null;
            (int, int)? ep = null;
            if (item.Kind != "movie")
            {
                if (item.Files?.FirstOrDefault(f => f.Index == item.LastFileIndex) is not { } file || Search.ParseEpisode(file.Name) is not { } at) return null;
                ep = at;
            }
            var imdb = item.Imdb!;
            var known = simkl.PausedAt(imdb, ep) is not null || (ep is null && simkl.Films.Contains(imdb))
                || (ep is { } e && simkl.Episodes.TryGetValue(imdb, out var set) && set.Contains(e));
            return known ? null : new SimklPaused(imdb, item.DisplayName.Split(" · ")[0], null, ep, item.Ratio * 100);
        }).Where(p => p is not null).Select(p => p!).ToList();

    /// <summary>
    /// The viewer's Simkl library and paused playbacks, then whatever this device watched that Simkl
    /// lacks goes up, and every half-watched title as a paused playback — one per 21 s, as Simkl locks
    /// scrobbling for 20 s per user. Every open, so nothing needs remembering what was sent.
    /// ponytail: the whole library each time; /sync/activities + date_from deltas if a large one makes this slow.
    /// </summary>
    public static async Task Pull()
    {
        if (tokens is null) return;
        var owner = profile;
        try
        {
            var seen = await Authed(owner, HttpMethod.Get, "/sync/all-items?extended=full&include_all_episodes=yes").ConfigureAwait(false);
            var paused = await Authed(owner, HttpMethod.Get, "/sync/playback").ConfigureAwait(false);
            if (owner != profile) return;
            var library = ParseLibrary(seen) with { Paused = ParsePlayback(paused) };
            var (films, episodes) = LocalWatched(History.Load());
            var missingFilms = films.Where(f => !library.Films.Contains(f)).ToList();
            var missingEps = episodes.ToDictionary(e => e.Key, e => e.Value.Where(x => !(library.Episodes.TryGetValue(e.Key, out var s) && s.Contains(x))).ToHashSet())
                .Where(e => e.Value.Count > 0).ToDictionary(e => e.Key, e => e.Value);
            if (HistoryBody(missingFilms, missingEps) is { } body)
            {
                await Authed(owner, HttpMethod.Post, "/sync/history", JsonBody(body)).ConfigureAwait(false);
                library.Films.UnionWith(missingFilms);
                foreach (var (imdb, eps) in missingEps)
                {
                    if (!library.Episodes.TryGetValue(imdb, out var set)) library.Episodes[imdb] = set = new();
                    set.UnionWith(eps);
                }
            }
            Library = library;
            Changed?.Invoke();
            foreach (var p in PausedBacklog(History.Load(), library))
            {
                if (owner != profile || ScrobbleBody(p.Imdb, p.Ep, p.Progress) is not { } b) return;
                try
                {
                    await Authed(owner, HttpMethod.Post, "/scrobble/pause", JsonBody(b)).ConfigureAwait(false);
                    if (owner == profile)
                    {
                        Library = Library with { Paused = Library.Paused.Append(p).ToList() };
                        Changed?.Invoke();
                    }
                }
                catch (Exception e)
                {
                    Log.Warn("simkl", $"pause {p.Imdb}: {e.Message}");
                }
                await Task.Delay(21_000).ConfigureAwait(false);
            }
        }
        catch (Exception e)
        {
            Log.Warn("simkl", $"pull: {e.Message}");
        }
    }
}
