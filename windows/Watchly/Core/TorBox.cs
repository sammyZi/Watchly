using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>A file in a torrent TorBox holds; <see cref="Name"/> is its path inside the torrent. <see cref="Id"/> is TorBox's.</summary>
public sealed record TorBoxFile(int Id, string Name, long Size)
{
    public string ShortName => Name.Replace('\\', '/').Split('/')[^1];
}

/// <summary>
/// TorBox (torbox.app), a debrid service — the phone's and TV's TorBox.kt. A torrent it already has
/// cached streams from its servers over HTTPS: no peers, no buffering on a thin swarm. With the
/// viewer's API key saved, playback asks here first and falls back to Watchly's own engine when the
/// release isn't cached or TorBox fails. The key is kept like the SubDL key: DPAPI, this Windows account.
/// </summary>
public static class TorBox
{
    const string Api = "https://api.torbox.app/v1/api";
    static readonly string KeyFile = Path.Combine(Store.Root, "torbox.bin");

    static string? key;
    /// <summary>The viewer's API key, or "" when none was given.</summary>
    public static string Key => key ??= Read();
    public static bool On => Key.Length > 0;

    /// <summary>The cached set grew, or the key changed. Raised on a thread-pool thread.</summary>
    public static event Action? Changed;

    static readonly HashSet<string> cached = new(), missing = new();
    static readonly Dictionary<string, int> ids = new();
    static readonly object Gate = new();

    /// <summary>Blank forgets it.</summary>
    public static void SetKey(string value)
    {
        var k = value.Trim();
        try
        {
            if (k.Length == 0) File.Delete(KeyFile);
            else
            {
                Directory.CreateDirectory(Store.Root);
                File.WriteAllBytes(KeyFile, Secrets.Protect(Encoding.UTF8.GetBytes(k)));
            }
        }
        catch (Exception e)
        {
            Log.Warn("torbox", $"Could not store the key: {e.Message}");
        }
        key = k;
        lock (Gate) { cached.Clear(); missing.Clear(); ids.Clear(); }
        Changed?.Invoke();
    }

    static string Read()
    {
        try { return File.Exists(KeyFile) ? Encoding.UTF8.GetString(Secrets.Unprotect(File.ReadAllBytes(KeyFile))) : ""; }
        catch { return ""; }
    }

    public static bool IsCached(string hash) { lock (Gate) return cached.Contains(hash.ToLowerInvariant()); }

    /// <summary>The 40-character hex info hash of a magnet, lower case; null for anything else.</summary>
    public static string? HashOf(string uri) =>
        Regex.Match(uri, "btih:([0-9a-f]{40})(?![0-9a-f])", RegexOptions.IgnoreCase) is { Success: true } m ? m.Groups[1].Value.ToLowerInvariant() : null;

    // ── HTTP ────────────────────────────────────────────────────────────────

    /// <summary>TorBox puts its reason in the body, 200 or not.</summary>
    static async Task<JsonNode> Call(HttpMethod method, string path, HttpContent? body = null, int timeoutSeconds = 12)
    {
        using var req = new HttpRequestMessage(method, Api + path) { Content = body };
        req.Headers.Authorization = new("Bearer", Key);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        using var res = await Net.Http.SendAsync(req, cts.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync().ConfigureAwait(false);
        JsonNode? o = null;
        try { o = JsonNode.Parse(text); } catch { }
        if (o?["success"]?.GetValue<bool>() == false || !res.IsSuccessStatusCode)
        {
            var why = (string?)o?["detail"] ?? (string?)o?["error"];
            throw new HttpRequestException(why ?? ((int)res.StatusCode is 401 or 403 ? "TorBox didn't accept the API key." : $"TorBox answered {(int)res.StatusCode}."));
        }
        return o ?? throw new HttpRequestException("TorBox's answer made no sense.");
    }

    /// <summary>/torrents/checkcached, as list or object: hash → its files.</summary>
    public static Dictionary<string, List<TorBoxFile>> ParseCached(JsonNode? root)
    {
        var data = root?["data"];
        var all = new Dictionary<string, List<TorBoxFile>>();
        List<TorBoxFile> Files(JsonNode? o) => (o?["files"] as JsonArray ?? new()).Select(f => new TorBoxFile(-1, (string?)f?["name"] ?? "", (long?)f?["size"] ?? 0)).ToList();
        if (data is JsonArray list)
            foreach (var o in list) { if ((string?)o?["hash"] is { } h) all[h.ToLowerInvariant()] = Files(o); }
        else if (data is JsonObject map)
            foreach (var (k, o) in map) all[((string?)o?["hash"] ?? k).ToLowerInvariant()] = Files(o);
        return all;
    }

    /// <summary>"you@example.com · Pro" — and proof the key works. Throws with TorBox's reason when it doesn't.</summary>
    public static async Task<string> Account()
    {
        var me = (await Call(HttpMethod.Get, "/user/me"))["data"];
        var plan = (int?)me?["plan"] switch { 0 => "Free", 1 => "Essential", 2 => "Pro", 3 => "Standard", _ => null };
        return string.Join("  ·  ", new[] { (string?)me?["email"], plan }.Where(s => !string.IsNullOrEmpty(s))) is { Length: > 0 } s ? s : "Connected";
    }

    /// <summary>Marks these hashes for the Instant tag, a hundred to a request. Never throws: no tag just means the swarm.</summary>
    public static async Task Check(IEnumerable<string> hashes)
    {
        if (!On) return;
        List<string> ask;
        lock (Gate) ask = hashes.Select(h => h.ToLowerInvariant()).Where(h => Regex.IsMatch(h, "^[0-9a-f]{40}$") && !cached.Contains(h) && !missing.Contains(h)).Distinct().ToList();
        foreach (var batch in ask.Chunk(100))
        {
            try
            {
                var found = ParseCached(await Call(HttpMethod.Get, $"/torrents/checkcached?hash={string.Join(",", batch)}&format=list&list_files=false"));
                lock (Gate)
                {
                    foreach (var h in batch) (found.ContainsKey(h) ? cached : missing).Add(h);
                }
                if (found.Count > 0) Changed?.Invoke();
            }
            catch (Exception e)
            {
                Log.Warn("torbox", $"checkcached: {e.Message}");
                return;
            }
        }
    }

    /// <summary>
    /// Which of <paramref name="files"/> is the one meant: same size and name first, then the only one of
    /// that size, then the name alone. TorBox and MonoTorrent can number a torrent's files differently.
    /// </summary>
    public static TorBoxFile? Match(IEnumerable<TorBoxFile> files, string path, long size)
    {
        var name = path.Replace('\\', '/').Split('/')[^1];
        var sameSize = files.Where(f => f.Size == size).ToList();
        return sameSize.FirstOrDefault(f => f.ShortName == name) ?? (sameSize.Count == 1 ? sameSize[0] : null) ?? files.FirstOrDefault(f => f.ShortName == name);
    }

    /// <summary>
    /// An HTTPS link to one file of the torrent <paramref name="uri"/>, or null when TorBox doesn't have it
    /// cached. Adds it to the account only when cached: a stream must not wait on TorBox downloading.
    /// </summary>
    public static async Task<string?> Link(string uri, string path, long size)
    {
        if (!On || HashOf(uri) is not { } hash) return null;
        if (!IsCached(hash))
        {
            var found = ParseCached(await Call(HttpMethod.Get, $"/torrents/checkcached?hash={hash}&format=list&list_files=false", timeoutSeconds: 8));
            lock (Gate) (found.ContainsKey(hash) ? cached : missing).Add(hash);
            if (!found.ContainsKey(hash)) return null;
        }
        var (id, files) = await Added(uri, hash);
        var file = Match(files, path, size) ?? throw new HttpRequestException($"TorBox has this torrent but not {Path.GetFileName(path)}.");
        var link = (string?)(await Call(HttpMethod.Get, $"/torrents/requestdl?token={Uri.EscapeDataString(Key)}&torrent_id={id}&file_id={file.Id}&zip_link=false"))["data"];
        return link is not null && link.StartsWith("http") ? link : throw new HttpRequestException("TorBox gave no link for that file.");
    }

    /// <summary>The torrent in the account, files listed and ready — added now if it isn't there.</summary>
    static async Task<(int Id, List<TorBoxFile> Files)> Added(string uri, string hash)
    {
        int? known;
        lock (Gate) known = ids.TryGetValue(hash, out var i) ? i : null;
        if (known is int k && await Mine(k) is { } ready) return (k, ready);
        int? id = null;
        try
        {
            // seed 3: don't seed from the account; no zip, so each file has its own link.
            var form = new MultipartFormDataContent
            {
                { new StringContent(uri), "magnet" }, { new StringContent("3"), "seed" },
                { new StringContent("false"), "allow_zip" }, { new StringContent("true"), "add_only_if_cached" },
            };
            id = (int?)(await Call(HttpMethod.Post, "/torrents/createtorrent", form))["data"]?["torrent_id"];
        }
        catch (Exception e)
        {
            // Already in the account is a refusal on some plans; find it there instead.
            Log.Warn("torbox", $"createtorrent: {e.Message}");
        }
        id ??= (int?)((await Call(HttpMethod.Get, "/torrents/mylist?bypass_cache=true"))["data"] as JsonArray)?
            .FirstOrDefault(t => ((string?)t?["hash"])?.ToLowerInvariant() == hash)?["id"];
        if (id is not int got) throw new HttpRequestException("TorBox wouldn't add this torrent.");
        lock (Gate) ids[hash] = got;
        // Cached is present at once, but the file list can lag the add by a moment.
        for (var n = 0; n < 15; n++)
        {
            if (await Mine(got) is { } files) return (got, files);
            await Task.Delay(1000);
        }
        throw new HttpRequestException("TorBox is still preparing this torrent. Try again in a minute.");
    }

    /// <summary>Its files once ready, else null.</summary>
    static async Task<List<TorBoxFile>?> Mine(int id)
    {
        var t = (await Call(HttpMethod.Get, $"/torrents/mylist?id={id}&bypass_cache=true"))["data"];
        if (t is JsonArray a) t = a.FirstOrDefault();
        var ready = (bool?)t?["download_present"] == true || (bool?)t?["download_finished"] == true;
        var files = (t?["files"] as JsonArray ?? new()).Select(f => new TorBoxFile((int?)f?["id"] ?? -1, (string?)f?["name"] ?? (string?)f?["short_name"] ?? "", (long?)f?["size"] ?? 0)).ToList();
        return ready && files.Count > 0 ? files : null;
    }
}
