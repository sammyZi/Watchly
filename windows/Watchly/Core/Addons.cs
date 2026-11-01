using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>One resource an addon serves, for these types and ids starting so; null prefixes is any id.</summary>
public sealed record AddonResource(string Name, List<string> Types, List<string>? IdPrefixes);

/// <summary>A list an addon offers. <see cref="Required"/> needs an extra (a genre, a search) before it lists anything; <see cref="Search"/> takes a query.</summary>
public sealed record AddonCatalog(string Type, string Id, string Name, bool Required, bool Search);

/// <summary>
/// A Stremio-protocol addon, added by its manifest link (tv/core Addons.kt). Watchly ships none: the
/// streams and subtitles it plays come from these — public addons, a debrid service's, or the viewer's
/// own media server. <see cref="Url"/> can hold the viewer's own config (a debrid key), so the UI shows
/// <see cref="Host"/> only.
/// </summary>
public sealed record Addon(string Url, string Name, List<AddonResource> Resources, bool On, string Manifest, List<AddonCatalog> Catalogs)
{
    public string Host => System.Uri.TryCreate(Url, UriKind.Absolute, out var u) ? u.Host : "?";

    public bool Serves(string resource, string type, string id) => On && Resources.Any(r =>
        r.Name == resource && r.Types.Contains(type) && (r.IdPrefixes is null || r.IdPrefixes.Any(id.StartsWith)));

    /// <summary>The types it answers /meta for this id with, <paramref name="first"/> tried first.</summary>
    public List<string> MetaTypes(string id, string first) => !On ? new() : Resources
        .Where(r => r.Name == "meta" && (r.IdPrefixes is null || r.IdPrefixes.Any(id.StartsWith)))
        .SelectMany(r => r.Types).Distinct().OrderBy(t => t == first ? 0 : 1).ToList();

    /// <summary>The home row's key for one of its catalogues: a hash, so the link (and any key in it) stays out of storage keys.</summary>
    public string ShelfKey(AddonCatalog c) =>
        Addons.ShelfPrefix + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes($"{Url}|{c.Type}|{c.Id}")))[..12].ToLowerInvariant();

    /// <summary>/{resource}/{type}/{id}.json — the protocol's one shape of request. Quiet: an addon being down is not "you are offline".</summary>
    public Task<JsonNode?> Get(string resource, string type, string id, CancellationToken ct = default) =>
        Net.Json($"{Url}/{resource}/{type}/{System.Uri.EscapeDataString(id)}.json", ct, true);

    /// <summary>What it offers, for its card.</summary>
    public string Offers => string.Join(" · ", Resources.Select(r => r.Name).Concat(Catalogs.Count > 0 ? new[] { "catalog" } : Array.Empty<string>())
        .Distinct().Select(n => n == "stream" ? "streams" : n));

    /// <summary>Every type it serves or lists, its card's second line and the Addons page's pills.</summary>
    public List<string> Types => Resources.SelectMany(r => r.Types).Concat(Catalogs.Select(c => c.Type)).Distinct().ToList();

    string? Field(string key) => JsonNode.Parse(Manifest)?[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Trim().Length > 0 ? s.Trim() : null;
    public string? Logo => Field("logo") is { } l && l.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? l : null;
    public string? Version => Field("version");
    public string? Description => Field("description");
}

/// <summary>
/// The viewer's addons, in their order: the order streams are asked for and listed in. Kept for
/// every profile, encrypted for this Windows account like the other keys (<see cref="Secrets"/>) —
/// an addon's link can carry a debrid key.
/// </summary>
public static class Addons
{
    public const string ShelfPrefix = "addon:";

    static readonly string File = Path.Combine(Store.Root, "addons.bin");
    static readonly object Gate = new();
    static List<Addon>? list;

    /// <summary>Raised on whichever thread changed the list.</summary>
    public static event Action? Changed;

    public static List<Addon> List
    {
        get
        {
            lock (Gate) return list ??= Load();
        }
    }

    /// <summary>Watchly's own kind for a Stremio type: "movie" plays the id itself — films, and live "tv" channels — the rest hold videos.</summary>
    public static string KindOfType(string type) => type is "movie" or "tv" ? "movie" : "tv";

    /// <summary>"movie" → "Films": a home row's second half.</summary>
    public static string TypeLabel(string type) => type switch
    {
        "movie" => "Films",
        "series" => "Series",
        "tv" => "Live TV",
        "channel" => "Channels",
        _ => type.Length > 0 ? char.ToUpperInvariant(type[0]) + type[1..] : type,
    };

    /// <summary>"stremio://host/x/manifest.json", "https://host/x/" or "host/x" → "https://host/x"; null for any other scheme.</summary>
    public static string? Base(string raw)
    {
        var s = raw.Trim();
        if (s.StartsWith("stremio://", StringComparison.OrdinalIgnoreCase)) s = "https://" + s["stremio://".Length..];
        if (!s.Contains("://")) s = "https://" + s;
        if (!Regex.IsMatch(s, "^https?://", RegexOptions.IgnoreCase)) return null;
        s = s.Split('#')[0].TrimEnd('/');
        if (s.EndsWith("/manifest.json", StringComparison.OrdinalIgnoreCase)) s = s[..^"/manifest.json".Length];
        s = s.TrimEnd('/');
        return System.Uri.TryCreate(s, UriKind.Absolute, out var u) && u.Host.Length > 0 ? s : null;
    }

    static string? Str(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    static List<string> Strings(JsonNode? node) => node is JsonArray a ? a.Select(Str).OfType<string>().ToList() : new();

    /// <summary>A manifest as an addon; null when it isn't one (no name, or nothing it serves or lists).</summary>
    public static Addon? ToAddon(string url, JsonNode? m, bool on = true)
    {
        if (m is not JsonObject o || Str(o["name"]) is not { Length: > 0 } name) return null;
        var types = Strings(o["types"]);
        var prefixes = o.ContainsKey("idPrefixes") ? Strings(o["idPrefixes"]) : null;
        var resources = new List<AddonResource>();
        foreach (var r in o["resources"] as JsonArray ?? new JsonArray())
        {
            if (Str(r) is { } plain) resources.Add(new(plain, types, prefixes));
            else if (r is JsonObject ro && Str(ro["name"]) is { } named)
                resources.Add(new(named, ro.ContainsKey("types") ? Strings(ro["types"]) : types, ro.ContainsKey("idPrefixes") ? Strings(ro["idPrefixes"]) : prefixes));
        }
        var catalogs = new List<AddonCatalog>();
        foreach (var c in (o["catalogs"] as JsonArray ?? new JsonArray()).OfType<JsonObject>())
        {
            if (Str(c["type"]) is not { } type || Str(c["id"]) is not { } id) continue;
            // Two shapes: `extra` objects, and the older `extraRequired` / `extraSupported` name lists.
            var extra = (c["extra"] as JsonArray ?? new JsonArray()).OfType<JsonObject>().ToList();
            var required = extra.Any(e => e["isRequired"] is JsonValue b && b.TryGetValue<bool>(out var yes) && yes) || Strings(c["extraRequired"]).Count > 0;
            var search = extra.Any(e => Str(e["name"]) == "search") || Strings(c["extraSupported"]).Contains("search");
            catalogs.Add(new(type, id, Str(c["name"]) ?? id, required, search));
        }
        return resources.Count == 0 && catalogs.Count == 0 ? null : new Addon(url, name, resources, on, o.ToJsonString(), catalogs);
    }

    static List<Addon> Load()
    {
        try
        {
            if (!System.IO.File.Exists(File)) return new();
            var rows = JsonNode.Parse(Encoding.UTF8.GetString(Secrets.Unprotect(System.IO.File.ReadAllBytes(File)))) as JsonArray ?? new JsonArray();
            return rows.Select(r =>
            {
                try { return ToAddon(r!["url"]!.GetValue<string>(), JsonNode.Parse(r["manifest"]!.GetValue<string>()), r["on"]?.GetValue<bool>() ?? true); }
                catch { return null; }
            }).OfType<Addon>().ToList();
        }
        catch (Exception e)
        {
            Log.Warn("addons", $"Could not read the addons: {e.Message}");
            return new();
        }
    }

    static void Save(List<Addon> next)
    {
        lock (Gate)
        {
            list = next;
            try
            {
                var rows = new JsonArray(next.Select(a => (JsonNode)new JsonObject { ["url"] = a.Url, ["on"] = a.On, ["manifest"] = a.Manifest }).ToArray());
                Directory.CreateDirectory(Store.Root);
                System.IO.File.WriteAllBytes(File, Secrets.Protect(Encoding.UTF8.GetBytes(rows.ToJsonString())));
            }
            catch (Exception e)
            {
                Log.Warn("addons", $"Could not store the addons: {e.Message}");
            }
        }
        Changed?.Invoke();
        // Added, removed or moved here: the signed-in Stremio account follows, so other devices get the same list.
        Stremio.AddonsChanged();
    }

    /// <summary>Fetches the manifest and keeps the addon; one already here is refreshed in place. Throws with a message to show.</summary>
    public static async Task<Addon> Add(string raw)
    {
        var url = Base(raw) ?? throw new InvalidOperationException("That isn't an addon link");
        var host = new System.Uri(url).Host;
        // Our own messages: the link can carry a key, and a failure's own message could name it.
        JsonNode? body;
        try
        {
            using var res = await Net.Send($"{url}/manifest.json", default, true);
            if (res.StatusCode == System.Net.HttpStatusCode.NotFound) throw new InvalidOperationException("No addon at that link — check it");
            if (!res.IsSuccessStatusCode) throw new InvalidOperationException($"{host} answered {(int)res.StatusCode}");
            body = JsonNode.Parse(await res.Content.ReadAsStringAsync());
        }
        catch (InvalidOperationException) { throw; }
        catch (System.Net.Http.HttpRequestException e) when (e.InnerException is System.Net.Sockets.SocketException { SocketErrorCode: System.Net.Sockets.SocketError.HostNotFound })
        {
            throw new InvalidOperationException($"Couldn't find {host} — check the link and your connection");
        }
        catch (TaskCanceledException) { throw new InvalidOperationException($"{host} took too long to answer"); }
        catch { throw new InvalidOperationException($"Couldn't reach {host}"); }
        var addon = ToAddon(url, body) ?? throw new InvalidOperationException("Not an addon manifest");
        var now = List;
        Save(now.Any(a => a.Url == url) ? now.Select(a => a.Url == url ? addon with { On = a.On } : a).ToList() : now.Append(addon).ToList());
        return addon;
    }

    /// <summary>Addons already read elsewhere (a Stremio account's), added after these; one here already is left be. How many were new.</summary>
    public static int Merge(IEnumerable<Addon> more)
    {
        var now = List;
        var fresh = more.Where(a => now.All(n => n.Url != a.Url)).DistinctBy(a => a.Url).ToList();
        if (fresh.Count > 0) Save(now.Concat(fresh).ToList());
        return fresh.Count;
    }

    public static void Toggle(string url) => Save(List.Select(a => a.Url == url ? a with { On = !a.On } : a).ToList());

    /// <summary>Gone here — and from the Stremio account too, as every change is (<see cref="Save"/>), so an import won't bring it back.</summary>
    public static void Remove(string url) => Save(List.Where(a => a.Url != url).ToList());

    /// <summary>One place up (−1) or down (+1).</summary>
    public static void Move(string url, int step)
    {
        var l = List.ToList();
        var at = l.FindIndex(a => a.Url == url);
        var to = at + step;
        if (at < 0 || to < 0 || to >= l.Count) return;
        var a = l[at];
        l.RemoveAt(at);
        l.Insert(to, a);
        Save(l);
    }

    /// <summary>
    /// Would <paramref name="link"/> — a stream an addon handed over, a debrid one with the viewer's key in it —
    /// still come from an addon that is added and on? By <paramref name="manifest"/> when it was kept; else
    /// (entries from before it was) an addon on the link's host whose own link holds every key-like part of
    /// the link's path. Torrentio with TorBox swapped for plain Torrentio is the same host without that key: no.
    /// </summary>
    public static bool StillServes(string link, string? manifest)
    {
        var on = List.Where(a => a.On).ToList();
        if (manifest is not null) return on.Any(a => a.Url == manifest);
        if (!System.Uri.TryCreate(link, UriKind.Absolute, out var uri)) return true;
        var keys = uri.AbsolutePath.Split('/', '=', '|', ',', ';').Where(part =>
            part.Length >= 20 && part.Any(char.IsDigit) && part.All(c => char.IsLetterOrDigit(c) || c is '-' or '_') &&
            !System.Text.RegularExpressions.Regex.IsMatch(part, "^[0-9a-fA-F]{40}$")).ToList();
        return on.Any(a => System.Uri.TryCreate(a.Url, UriKind.Absolute, out var u) && u.Host == uri.Host && keys.All(k => a.Url.Contains(k)));
    }

    public static List<Addon> Serving(string resource, string type, string id) => List.Where(a => a.Serves(resource, type, id)).ToList();

    /// <summary>Every list that shows without a genre or search picked first, as home rows.</summary>
    public static List<Shelf> Shelves() => List.Where(a => a.On).SelectMany(a =>
        a.Catalogs.Where(c => !c.Required).Select(c => new Shelf($"{c.Name} · {TypeLabel(c.Type)}", KindOfType(c.Type), a.ShelfKey(c)))).ToList();

    /// <summary>The addon and catalogue behind a home row's key.</summary>
    public static (Addon Addon, AddonCatalog Catalog)? Shelf(string key)
    {
        foreach (var a in List)
            foreach (var c in a.Catalogs)
                if (a.ShelfKey(c) == key) return (a, c);
        return null;
    }

    /// <summary>Catalogues that take a search query.</summary>
    public static List<(Addon Addon, AddonCatalog Catalog)> Searchable() =>
        List.Where(a => a.On).SelectMany(a => a.Catalogs.Where(c => c.Search).Select(c => (a, c))).ToList();

    /// <summary>Any addon that can find streams at all: without one, there is nothing to play but links.</summary>
    public static bool HasStreams => List.Any(a => a.On && a.Resources.Any(r => r.Name == "stream"));
}
