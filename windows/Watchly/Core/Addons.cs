using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>One resource an addon serves, for these types and ids starting so; null prefixes is any id.</summary>
public sealed record AddonResource(string Name, List<string> Types, List<string>? IdPrefixes);

/// <summary>
/// A Stremio-protocol addon, added by its manifest link (tv/core Addons.kt). Watchly ships none: the
/// streams and subtitles it plays come from these — public addons, a debrid service's, or the viewer's
/// own media server. <see cref="Url"/> can hold the viewer's own config (a debrid key), so the UI shows
/// <see cref="Host"/> only.
/// </summary>
public sealed record Addon(string Url, string Name, List<AddonResource> Resources, bool On, string Manifest)
{
    public string Host => System.Uri.TryCreate(Url, UriKind.Absolute, out var u) ? u.Host : "?";

    public bool Serves(string resource, string type, string id) => On && Resources.Any(r =>
        r.Name == resource && r.Types.Contains(type) && (r.IdPrefixes is null || r.IdPrefixes.Any(id.StartsWith)));

    /// <summary>/{resource}/{type}/{id}.json — the protocol's one shape of request. Quiet: an addon being down is not "you are offline".</summary>
    public Task<JsonNode?> Get(string resource, string type, string id, CancellationToken ct = default) =>
        Net.Json($"{Url}/{resource}/{type}/{System.Uri.EscapeDataString(id)}.json", ct, true);

    /// <summary>What it offers, for its row in Settings.</summary>
    public string Offers => string.Join(" · ", Resources.Select(r => r.Name).Distinct().Select(n => n == "stream" ? "streams" : n));
}

/// <summary>
/// The viewer's addons, in their order: the order streams are asked for and listed in. Kept for
/// every profile, encrypted for this Windows account like the other keys (<see cref="Secrets"/>) —
/// an addon's link can carry a debrid key.
/// </summary>
public static class Addons
{
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

    static List<string> Strings(JsonNode? node) =>
        node is JsonArray a ? a.Select(x => x is JsonValue v && v.TryGetValue<string>(out var s) ? s : null).OfType<string>().ToList() : new();

    /// <summary>A manifest as an addon; null when it isn't one (no name, or nothing it serves).</summary>
    public static Addon? ToAddon(string url, JsonNode? m, bool on = true)
    {
        if (m is not JsonObject o || o["name"] is not JsonValue nv || !nv.TryGetValue<string>(out var name) || name.Length == 0) return null;
        if (o["resources"] is not JsonArray rows) return null;
        var types = Strings(o["types"]);
        var prefixes = o.ContainsKey("idPrefixes") ? Strings(o["idPrefixes"]) : null;
        var resources = new List<AddonResource>();
        foreach (var r in rows)
        {
            if (r is JsonValue v && v.TryGetValue<string>(out var plain)) resources.Add(new(plain, types, prefixes));
            else if (r is JsonObject ro && ro["name"] is JsonValue rn && rn.TryGetValue<string>(out var named))
                resources.Add(new(named, ro.ContainsKey("types") ? Strings(ro["types"]) : types, ro.ContainsKey("idPrefixes") ? Strings(ro["idPrefixes"]) : prefixes));
        }
        return resources.Count == 0 ? null : new Addon(url, name, resources, on, o.ToJsonString());
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
    }

    /// <summary>Fetches the manifest and keeps the addon; one already here is refreshed in place. Throws with a message to show.</summary>
    public static async Task<Addon> Add(string raw)
    {
        var url = Base(raw) ?? throw new InvalidOperationException("That isn't an addon link");
        var host = new System.Uri(url).Host;
        // Our own messages: the link can carry a key, and a failure's own message could name it.
        JsonNode? body;
        try { body = await Net.Json($"{url}/manifest.json", default, true); }
        catch { throw new InvalidOperationException($"Couldn't reach {host}"); }
        var addon = ToAddon(url, body) ?? throw new InvalidOperationException("Not an addon manifest");
        var now = List;
        Save(now.Any(a => a.Url == url) ? now.Select(a => a.Url == url ? addon with { On = a.On } : a).ToList() : now.Append(addon).ToList());
        return addon;
    }

    public static void Toggle(string url) => Save(List.Select(a => a.Url == url ? a with { On = !a.On } : a).ToList());

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

    public static List<Addon> Serving(string resource, string type, string id) => List.Where(a => a.Serves(resource, type, id)).ToList();

    /// <summary>Any addon that can find streams at all: without one, there is nothing to play but links.</summary>
    public static bool HasStreams => List.Any(a => a.On && a.Resources.Any(r => r.Name == "stream"));
}
