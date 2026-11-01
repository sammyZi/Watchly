using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>Where the viewer stopped, on any device: <see cref="Progress"/> is 0–100, <see cref="Seconds"/> the spot itself when known. <see cref="Ep"/> is null for a film.</summary>
public sealed record StremioPaused(string Imdb, string Title, string? Year, (int Season, int Episode)? Ep, double Progress, double? Seconds = null)
{
    public string Kind => Ep is null ? "movie" : "tv";
}

/// <summary>A title in the viewer's Stremio library: <see cref="Last"/> is "S01E05" for a show, <see cref="At"/> an ISO time, newest sorting first.</summary>
public sealed record StremioSeen(string Imdb, string Title, string? Year, string Kind, string At, string? Last);

/// <summary>What Stremio knows: films watched through, episodes, what was left part-way, and the library itself (newest first).</summary>
public sealed record StremioLibrary(
    HashSet<string> Films,
    Dictionary<string, HashSet<(int Season, int Episode)>> Episodes,
    List<StremioPaused> Paused,
    List<StremioSeen> Seen)
{
    public static readonly StremioLibrary Empty = new(new(), new(), new(), new());

    public double? PausedAt(string imdb, (int, int)? ep) => Paused.FirstOrDefault(p => p.Imdb == imdb && p.Ep == ep)?.Progress;

    public double? PausedSeconds(string imdb, (int, int)? ep) => Paused.FirstOrDefault(p => p.Imdb == imdb && p.Ep == ep)?.Seconds;

    /// <summary>Stremio's titles that <paramref name="items"/> never played — a reinstall, another device — paused ones first.</summary>
    public List<StremioSeen> NotIn(IEnumerable<HistoryItem> items)
    {
        var here = items.Select(i => i.Imdb).Where(i => i is not null).ToHashSet();
        var left = Paused.Select(p => new StremioSeen(p.Imdb, p.Title, p.Year, p.Kind, "", p.Ep is { } e ? $"S{e.Season:00}E{e.Episode:00}" : null));
        return left.Concat(Seen).Where(s => !here.Contains(s.Imdb)).DistinctBy(s => s.Imdb).ToList();
    }
}

/// <summary>
/// The viewer's Stremio account (tv/core Stremio.kt): sign in by a code approved on link.stremio.com
/// (no password here), then the library, where films were left and what was finished come and go both
/// ways, and the account's addons come across. The key is per profile, encrypted with DPAPI like the
/// SubDL key; the guest's stays in memory.
/// </summary>
public static class Stremio
{
    const string Api = "https://api.strem.io/api";
    const string LinkApi = "https://link.stremio.com/api";
    /// <summary>How far in counts as watched.</summary>
    public const double WatchedAt = 0.8;
    /// <summary>Per profile: My List titles already put in the account's library, so one removed there stays removed.</summary>
    const string ListSentKey = "stremio-list-sent";

    /// <summary>Signed in or out, or the library moved. Raised on whichever thread did it.</summary>
    public static event Action? Changed;

    public static bool Connected => key is not null;
    /// <summary>The account's email, for "Signed in as".</summary>
    public static string? Email { get; private set; }
    public static StremioLibrary Library { get; private set; } = StremioLibrary.Empty;

    /// <summary>What the viewer is shown to sign in: <see cref="Code"/>, and its page <see cref="Link"/> on link.stremio.com.</summary>
    public sealed record DeviceCode(string Code, string Link, int ExpiresIn, int Interval);

    static string profile = "";
    static bool guest;
    static string? key;

    static string FileOf(string id) => Path.Combine(Store.Root, $"stremio_{(id.Length > 0 ? id : "owner")}.bin");

    // ── Parsing (StremioTest.kt) ────────────────────────────────────────────

    static string? Str(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    static long Long(JsonNode? n) =>
        n is JsonValue && double.TryParse(n.ToJsonString().Trim('"'), NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? (long)d : 0;

    static bool Bool(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    /// <summary>Stremio's link sign-in: a short code, and its page on link.stremio.com where a signed-in viewer approves it.</summary>
    public static DeviceCode ParseLink(string json)
    {
        var o = JsonNode.Parse(json);
        var r = o?["result"] ?? o;
        var code = Str(r?["code"]) ?? throw new InvalidOperationException("Stremio sent no code");
        // ponytail: ten minutes is a guess at the code's life; the poll stops there and the viewer asks again.
        return new(code, Str(r?["link"]) ?? $"https://link.stremio.com/{code}", 600, 3);
    }

    /// <summary>The account's key once the code is approved; null while it waits (Stremio answers an error until then).</summary>
    public static string? ParseLinkRead(string json)
    {
        try { return Str(JsonNode.Parse(json)?["result"]?["authKey"]); }
        catch { return null; }
    }

    /// <summary>"tt0944947:1:2" → S1E2; null for a film's id or one that names no episode.</summary>
    public static (int Season, int Episode)? EpOfVideo(string? videoId)
    {
        var parts = videoId?.Split(':');
        if (parts is null || parts.Length < 3) return null;
        return int.TryParse(parts[^2], out var s) && int.TryParse(parts[^1], out var e) ? (s, e) : null;
    }

    static string IsoNow(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);

    /// <summary>Stremio's times, with or without the milliseconds; null for anything else.</summary>
    public static long? IsoMs(string? s) =>
        s is not null && DateTimeOffset.TryParseExact(s, new[] { "yyyy-MM-dd'T'HH:mm:ss.fff'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'" }, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var t)
            ? t.ToUnixTimeMilliseconds() : null;

    /// <summary>
    /// datastoreGet's library items. Watched through is <c>timesWatched</c> or <c>flaggedWatched</c>; part-way is a
    /// <c>timeOffset</c> short of <see cref="WatchedAt"/>; the library is what is neither <c>removed</c> nor only <c>temp</c>.
    /// ponytail: per-episode watched marks live in Stremio's <c>watched</c> bitfield (compressed); only the last
    /// episode played is read here. Decode it if episode ticks from other devices matter.
    /// </summary>
    public static StremioLibrary ParseLibrary(IEnumerable<JsonObject> items)
    {
        var films = new HashSet<string>();
        var episodes = new Dictionary<string, HashSet<(int, int)>>();
        var paused = new List<(string At, StremioPaused P)>();
        var seen = new List<StremioSeen>();
        foreach (var it in items)
        {
            if (Str(it["_id"]) is not { } id) continue;
            var kind = Addons.KindOfType(Str(it["type"]) ?? "movie");
            var state = it["state"] as JsonObject ?? new JsonObject();
            var name = Str(it["name"]) ?? "";
            var at = Str(state["lastWatched"]) ?? Str(it["_mtime"]) ?? "";
            var through = Long(state["timesWatched"]) > 0 || Long(state["flaggedWatched"]) > 0;
            var ep = kind == "tv" ? EpOfVideo(Str(state["video_id"])) : null;
            if (through && kind == "movie") films.Add(id);
            var offset = Long(state["timeOffset"]);
            var duration = Long(state["duration"]);
            var part = duration > 0 ? (double)offset / duration : 0;
            if (through && ep is { } done && part == 0)
            {
                if (!episodes.TryGetValue(id, out var set)) episodes[id] = set = new();
                set.Add(done);
            }
            if (offset > 0 && part >= 0.02 && part < WatchedAt && (kind == "movie" || ep is not null))
                paused.Add((at, new StremioPaused(id, name, null, ep, part * 100, offset / 1000.0)));
            if (!Bool(it["removed"]) && !Bool(it["temp"]))
                seen.Add(new(id, name, null, kind, at, ep is { } e ? $"S{e.Season:00}E{e.Episode:00}" : null));
        }
        return new(films, episodes,
            paused.OrderByDescending(p => p.At, StringComparer.Ordinal).Select(p => p.P).ToList(),
            seen.OrderByDescending(s => s.At, StringComparer.Ordinal).ToList());
    }

    /// <summary>
    /// A library item as Stremio's own apps read it: <paramref name="old"/> as stored with <paramref name="change"/> made
    /// to its state, or a new one with every field present and typed — an item missing one could break the viewer's
    /// library there. A new item is <c>temp</c> (in Continue Watching only) unless <paramref name="inLibrary"/> says otherwise.
    /// </summary>
    public static JsonObject LibraryItem(JsonObject? old, string id, string name, string type, string? poster, bool? inLibrary = null, long? now = null, Action<JsonObject>? change = null)
    {
        var stamp = IsoNow(now ?? Store.Now);
        var o = old?.DeepClone() as JsonObject ?? new JsonObject
        {
            ["_id"] = id, ["name"] = name, ["type"] = type, ["poster"] = poster, ["posterShape"] = "poster",
            ["removed"] = false, ["temp"] = true, ["_ctime"] = stamp,
            ["behaviorHints"] = new JsonObject { ["defaultVideoId"] = null, ["featuredVideoId"] = null, ["hasScheduledVideos"] = false },
        };
        if (o["state"] is not JsonObject state)
        {
            state = new JsonObject();
            o["state"] = state;
        }
        foreach (var k in new[] { "timeWatched", "timeOffset", "overallTimeWatched", "timesWatched", "flaggedWatched", "duration" })
            if (!state.ContainsKey(k)) state[k] = 0;
        foreach (var k in new[] { "lastWatched", "video_id", "watched" })
            if (!state.ContainsKey(k)) state[k] = null;
        if (!state.ContainsKey("noNotif")) state["noNotif"] = false;
        change?.Invoke(state);
        o["_mtime"] = stamp;
        if (inLibrary is bool on)
        {
            o["removed"] = !on;
            o["temp"] = false;
        }
        return o;
    }

    static string TypeOf(string? kind) => kind == "movie" ? "movie" : "series";

    /// <summary>
    /// What this PC played that Stremio hasn't heard of — from before signing in, or while offline: per title its
    /// latest play, past <see cref="WatchedAt"/> as watched, else its paused spot. Stremio's own item wins when it was
    /// watched since; the play's own time goes up as lastWatched, so the next open doesn't send it again.
    /// A play with no id (a pasted link) or episode can't be placed.
    /// </summary>
    public static List<JsonObject> Backlog(IEnumerable<HistoryItem> items, IReadOnlyDictionary<string, JsonObject> remote)
    {
        var @out = new List<JsonObject>();
        foreach (var item in History.LatestPerTitle(items))
        {
            if (item.Imdb is not { } id || item.LastFileIndex is not int i || item.PositionOf(i) is not double at || item.DurationOf(i) is not double of || of <= 0) continue;
            var r = at / of;
            if (r < 0.02) continue;
            var old = remote.GetValueOrDefault(id);
            if ((IsoMs(Str(old?["state"]?["lastWatched"])) ?? 0) >= item.At) continue;
            var film = item.Kind == "movie";
            (int Season, int Episode)? ep = null;
            if (!film)
            {
                if (item.Files?.FirstOrDefault(f => f.Index == i)?.Name is not { } file || Search.ParseEpisode(file) is not { } e) continue;
                ep = e;
            }
            @out.Add(LibraryItem(old, id, item.DisplayName.Split(" · ")[0], film ? "movie" : "series", History.IsRemote(item.Poster) ? item.Poster : null, now: item.At, change: s =>
            {
                s["lastWatched"] = IsoNow(item.At);
                if (ep is { } x) s["video_id"] = $"{id}:{x.Season}:{x.Episode}";
                if (r >= WatchedAt)
                {
                    s["timesWatched"] = Long(s["timesWatched"]) + 1;
                    s["flaggedWatched"] = 1;
                    s["timeOffset"] = 0;
                }
                else
                {
                    s["timeOffset"] = (long)(at * 1000);
                    s["duration"] = (long)(of * 1000);
                }
            }));
        }
        return @out;
    }

    /// <summary>Stremio's own addons Watchly has its own of, so never brings across (nor takes away).</summary>
    static readonly HashSet<string> StremioOwn = new() { "com.linvo.cinemeta", "org.stremio.local" };

    /// <summary>
    /// An entry of the account's collection that is Stremio's business, not Watchly's: protected, one Watchly has
    /// its own of, or on the viewer's own machine. Never imported here, and never removed from there.
    /// </summary>
    public static bool StremioKeeps(JsonObject entry) =>
        Bool(entry["flags"]?["protected"]) ||
        (Str(entry["manifest"]?["id"]) is { } id && StremioOwn.Contains(id)) ||
        Regex.IsMatch(Str(entry["transportUrl"]) ?? "", @"^https?://(127\.0\.0\.1|localhost)", RegexOptions.IgnoreCase);

    /// <summary>One of Watchly's addons as an entry in a Stremio account's collection.</summary>
    public static JsonObject Descriptor(Addon a) => new()
    {
        ["transportUrl"] = $"{a.Url}/manifest.json",
        ["transportName"] = "http",
        ["manifest"] = JsonNode.Parse(a.Manifest),
        ["flags"] = new JsonObject { ["official"] = false, ["protected"] = false },
    };

    /// <summary>
    /// The account's collection made to match <paramref name="mine"/>, in its order: what <see cref="StremioKeeps"/>
    /// stays first and as it was; an addon the account already had keeps its entry exactly as Stremio sent it; one
    /// it hadn't is added; the rest — removed here — go.
    /// </summary>
    public static List<JsonObject> Mirrored(IReadOnlyList<JsonObject> collection, IEnumerable<Addon> mine)
    {
        var kept = collection.Where(StremioKeeps).ToList();
        var keptBases = kept.Select(e => Addons.Base(Str(e["transportUrl"]) ?? "")).Where(b => b is not null).ToHashSet();
        var theirs = new Dictionary<string, JsonObject>();
        foreach (var e in collection.Where(e => !StremioKeeps(e)))
            if (Addons.Base(Str(e["transportUrl"]) ?? "") is { } b) theirs.TryAdd(b, e);
        return kept.Concat(mine.Where(a => !keptBases.Contains(a.Url)).Select(a => theirs.TryGetValue(a.Url, out var e) ? e : Descriptor(a))).ToList();
    }

    /// <summary>
    /// <paramref name="up"/> with My List added to the account's library: each title saved here that isn't in it —
    /// absent, removed, or only seen once (temp) — goes in, unless it was sent before (<paramref name="sent"/>):
    /// one taken out there on purpose stays out. Nothing is ever removed this way.
    /// </summary>
    public static Dictionary<string, JsonObject> WithMyList(IReadOnlyDictionary<string, JsonObject> up, IReadOnlyDictionary<string, JsonObject> remote, IEnumerable<SavedTitle> saved, ISet<string> sent)
    {
        var @out = up.ToDictionary(kv => kv.Key, kv => kv.Value);
        foreach (var s in saved)
        {
            if (s.List != "later" || sent.Contains(s.Imdb)) continue;
            var now = @out.GetValueOrDefault(s.Imdb) ?? remote.GetValueOrDefault(s.Imdb);
            if (now is not null && !Bool(now["removed"]) && !Bool(now["temp"])) continue;
            @out[s.Imdb] = LibraryItem(now, s.Imdb, s.Title, TypeOf(s.Kind), s.Poster, inLibrary: true);
        }
        return @out;
    }

    // ── The account ─────────────────────────────────────────────────────────

    /// <summary>On every profile switch, like History.Load.</summary>
    public static void Load(string id, bool isGuest)
    {
        profile = id;
        guest = isGuest;
        var stored = isGuest ? null : Read(id);
        key = stored?.Key;
        Email = stored?.Email;
        Library = StremioLibrary.Empty;
        Changed?.Invoke();
        _ = Pull();
    }

    public static void Forget(string id)
    {
        try { File.Delete(FileOf(id)); } catch { }
    }

    static (string Key, string? Email)? Read(string id)
    {
        try
        {
            if (!File.Exists(FileOf(id))) return null;
            var o = JsonNode.Parse(Encoding.UTF8.GetString(Secrets.Unprotect(File.ReadAllBytes(FileOf(id)))));
            return Str(o?["authKey"]) is { } k ? (k, Str(o?["email"])) : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Memory only for the guest watching; <paramref name="owner"/>'s own file otherwise, and live if it is the one watching.</summary>
    static void Save(string? next, string? email, string owner)
    {
        if (!(guest && owner == profile))
        {
            try
            {
                if (next is null) File.Delete(FileOf(owner));
                else
                {
                    Directory.CreateDirectory(Store.Root);
                    File.WriteAllBytes(FileOf(owner), Secrets.Protect(Encoding.UTF8.GetBytes(new JsonObject { ["authKey"] = next, ["email"] = email }.ToJsonString())));
                }
            }
            catch (Exception e)
            {
                Log.Warn("stremio", $"Could not store the sign-in: {e.Message}");
            }
        }
        if (owner != profile) return;
        key = next;
        Email = email;
        if (next is null) Library = StremioLibrary.Empty;
        Changed?.Invoke();
    }

    /// <summary>One call to Stremio's API; a session it no longer knows signs this profile out.</summary>
    static async Task<JsonNode> Call(string method, JsonObject body, string owner)
    {
        var k = (owner == profile ? key : Read(owner)?.Key) ?? throw new InvalidOperationException("Not signed in to Stremio");
        body["authKey"] = k;
        var o = await Net.PostJson($"{Api}/{method}", body).ConfigureAwait(false) ?? throw new InvalidOperationException("Stremio sent nothing");
        if (o["error"] is JsonObject e)
        {
            if (Long(e["code"]) == 1) Save(null, null, owner);
            throw new InvalidOperationException(Str(e["message"]) ?? "Stremio said no");
        }
        return o;
    }

    static HashSet<string> ListSent() => Store.LoadOwn(ListSentKey, new List<string>()).ToHashSet();

    /// <summary>
    /// The viewer's library and paused playbacks — and, up the other way, whatever this PC played that the account
    /// hasn't got (<see cref="Backlog"/>) and My List from before signing in, in one write. Every open and every
    /// sign-in, so plays from before signing in, or offline, need no remembering. Off the network, it all stays as it was.
    /// </summary>
    public static async Task Pull()
    {
        if (key is null) return;
        var owner = profile;
        try
        {
            var got = await Call("datastoreGet", new JsonObject { ["collection"] = "libraryItem", ["ids"] = new JsonArray(), ["all"] = true }, owner).ConfigureAwait(false);
            var theirs = new Dictionary<string, JsonObject>();
            foreach (var o in (got["result"] as JsonArray ?? new JsonArray()).OfType<JsonObject>())
                if (Str(o["_id"]) is { } id) theirs[id] = o;
            var played = Backlog(History.Load(), theirs).ToDictionary(o => Str(o["_id"])!, o => o);
            // My List from before signing in goes into the library once; after that, toggles go up as they happen.
            var listed = Saved.InList(Saved.Load(), "later");
            var up = WithMyList(played, theirs, listed, ListSent());
            if (owner != profile) return;
            if (up.Count > 0)
            {
                await Call("datastorePut", new JsonObject { ["collection"] = "libraryItem", ["changes"] = new JsonArray(up.Values.Select(v => (JsonNode)v.DeepClone()).ToArray()) }, owner).ConfigureAwait(false);
                Log.Info("stremio", $"sent {up.Count}: {played.Count} from this PC's history, the rest from My List");
            }
            if (owner != profile) return;
            Store.SaveOwn(ListSentKey, ListSent().Union(listed.Select(s => s.Imdb)).ToList());
            foreach (var (id, o) in up) theirs[id] = o;
            Library = ParseLibrary(theirs.Values);
            Changed?.Invoke();
        }
        catch (Exception e)
        {
            Log.Warn("stremio", $"pull: {e.Message}");
        }
    }

    public static async Task<DeviceCode> Start()
    {
        using var res = await Net.Send($"{LinkApi}/create?type=Create", default, true).ConfigureAwait(false);
        return ParseLink(await res.Content.ReadAsStringAsync().ConfigureAwait(false));
    }

    /// <summary>
    /// Polls until approved (true), or the code runs out (false); <paramref name="ct"/> cancels. Approved, the
    /// account's library is pulled and its addons come across.
    /// </summary>
    public static async Task<bool> Await(DeviceCode code, CancellationToken ct)
    {
        var owner = profile;
        var deadline = Store.Now + code.ExpiresIn * 1000L;
        while (Store.Now < deadline)
        {
            await Task.Delay(code.Interval * 1000, ct).ConfigureAwait(false);
            string? authKey;
            try
            {
                using var res = await Net.Send($"{LinkApi}/read?type=Read&code={Uri.EscapeDataString(code.Code)}", ct, true).ConfigureAwait(false);
                authKey = ParseLinkRead(await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false));
            }
            catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested)
            {
                continue;
            }
            if (authKey is null) continue;
            string? email = null;
            try { email = Str((await Net.PostJson($"{Api}/getUser", new JsonObject { ["type"] = "GetUser", ["authKey"] = authKey }, ct).ConfigureAwait(false))?["result"]?["email"]); }
            catch { }
            Save(authKey, email, owner);
            if (owner == profile)
            {
                await Pull().ConfigureAwait(false);
                try { await ImportAddons().ConfigureAwait(false); } catch (Exception e) { Log.Warn("stremio", $"import addons: {e.Message}"); }
            }
            return true;
        }
        return false;
    }

    static async Task<List<JsonObject>> Collection(string owner)
    {
        var got = await Call("addonCollectionGet", new JsonObject { ["type"] = "AddonCollectionGet", ["update"] = true }, owner).ConfigureAwait(false);
        return (got["result"]?["addons"] as JsonArray ?? throw new IOException("Stremio sent no addons")).OfType<JsonObject>().ToList();
    }

    /// <summary>
    /// The account's installed addons, added here (one already here is left be) — less what <see cref="StremioKeeps"/>.
    /// Adding them changes this list, so the account then gets this PC's too (<see cref="AddonsChanged"/>): signing in
    /// leaves both sides with everything either had. How many came across.
    /// </summary>
    public static async Task<int> ImportAddons()
    {
        var addons = new List<Addon>();
        foreach (var a in (await Collection(profile).ConfigureAwait(false)).Where(e => !StremioKeeps(e)))
            if (a["manifest"] is JsonObject manifest && Addons.Base(Str(a["transportUrl"]) ?? "") is { } url && Addons.ToAddon(url, manifest) is { } addon)
                addons.Add(addon);
        return Addons.Merge(addons);
    }

    /// <summary>
    /// The account's collection made to match this PC's addons (<see cref="Mirrored"/>) — the button, and every change
    /// here. Only what differs is written. How many of this PC's addons the account now has.
    /// </summary>
    public static async Task<int> PushAddons(string? owner = null)
    {
        owner ??= profile;
        var all = await Collection(owner).ConfigureAwait(false);
        var mine = Addons.List;
        var next = Mirrored(all, mine);
        if (!next.Select(e => Str(e["transportUrl"])).SequenceEqual(all.Select(e => Str(e["transportUrl"]))))
        {
            await Call("addonCollectionSet", new JsonObject { ["type"] = "AddonCollectionSet", ["addons"] = new JsonArray(next.Select(e => (JsonNode)e.DeepClone()).ToArray()) }, owner).ConfigureAwait(false);
            Log.Info("stremio", $"account addons now {next.Count}, {mine.Count} of them this PC's");
        }
        return mine.Count;
    }

    static CancellationTokenSource? addonsCts;

    /// <summary>
    /// This PC's list changed — added, removed, moved: the account follows, a moment later, so a burst of changes (an
    /// import) is one write. Not on open: then whichever device opened last would overwrite the rest.
    /// </summary>
    public static void AddonsChanged()
    {
        if (key is null) return;
        var owner = profile;
        addonsCts?.Cancel();
        var cts = addonsCts = new CancellationTokenSource();
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(1500, cts.Token);
                await PushAddons(owner);
            }
            catch (OperationCanceledException) { }
            catch (Exception e)
            {
                Log.Warn("stremio", $"addons: {e.Message}");
            }
        });
    }

    public static void Disconnect()
    {
        var gone = key;
        if (gone is null) return;
        Save(null, null, profile);
        _ = Task.Run(async () => { try { await Net.PostJson($"{Api}/logout", new JsonObject { ["type"] = "Logout", ["authKey"] = gone }); } catch { } });
    }

    /// <summary>Read, change and write back one library item; fire and forget, so Stremio being down never troubles playback.</summary>
    static void Update(string id, string name, string type, string? poster, bool? inLibrary = null, Action<JsonObject>? change = null)
    {
        if (key is null) return;
        var owner = profile;
        _ = Task.Run(async () =>
        {
            try
            {
                var got = await Call("datastoreGet", new JsonObject { ["collection"] = "libraryItem", ["ids"] = new JsonArray(id), ["all"] = false }, owner);
                var old = (got["result"] as JsonArray)?.OfType<JsonObject>().FirstOrDefault();
                var item = LibraryItem(old, id, name, type, poster, inLibrary, change: change);
                await Call("datastorePut", new JsonObject { ["collection"] = "libraryItem", ["changes"] = new JsonArray(item) }, owner);
            }
            catch (Exception e)
            {
                Log.Warn("stremio", $"update: {e.Message}");
            }
        });
    }

    static string? PosterOf(HistoryItem at) => History.IsRemote(at.Poster) ? at.Poster : null;

    /// <summary>A film or an episode finished: watched once more, and no longer part-way.</summary>
    public static void Watched(HistoryItem at, (int Season, int Episode)? ep)
    {
        if (at.Imdb is not { } imdb || key is null) return;
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
        Update(imdb, at.DisplayName.Split(" · ")[0], TypeOf(at.Kind), PosterOf(at), change: s =>
        {
            s["timesWatched"] = Long(s["timesWatched"]) + 1;
            s["flaggedWatched"] = 1;
            s["timeOffset"] = 0;
            s["lastWatched"] = IsoNow(Store.Now);
            if (ep is { } e) s["video_id"] = $"{imdb}:{e.Season}:{e.Episode}";
        });
    }

    /// <summary>Left part-way: Stremio keeps the spot, and Continue Watching on any device picks it up.</summary>
    public static void Pause(HistoryItem at, (int Season, int Episode)? ep, double seconds, double duration)
    {
        if (at.Imdb is not { } imdb || key is null || duration <= 0) return;
        var title = at.DisplayName.Split(" · ")[0];
        Library = Library with { Paused = Library.Paused.Where(p => !(p.Imdb == imdb && p.Ep == ep)).Prepend(new StremioPaused(imdb, title, null, ep, seconds / duration * 100, seconds)).ToList() };
        Changed?.Invoke();
        Update(imdb, title, TypeOf(at.Kind), PosterOf(at), change: s =>
        {
            s["timeOffset"] = (long)(seconds * 1000);
            s["duration"] = (long)(duration * 1000);
            s["lastWatched"] = IsoNow(Store.Now);
            if (ep is { } e) s["video_id"] = $"{imdb}:{e.Season}:{e.Episode}";
        });
    }

    /// <summary>My List here is the library there: added or taken out together.</summary>
    public static void SetInLibrary(Title title, bool on) => Update(title.Imdb, title.Name, title.StremioType, title.Poster, inLibrary: on);
}
