using System.Net;
using System.Net.Http;
using System.Text.Json.Nodes;

namespace Watchly.Core;

/// <summary>
/// HttpClient plus the connectivity bookkeeping from src/lib/net.ts.
///
/// Only a request that throws before it has a status counts as offline — a 404
/// or a 503 means the network is fine and the service is not. A cheap HEAD on a
/// backoff is what says the connection is back.
/// </summary>
public static class Net
{
    const string Probe = "https://v3-cinemeta.strem.io/manifest.json";
    static readonly TimeSpan Timeout = TimeSpan.FromSeconds(15);

    public static readonly HttpClient Http = CreateClient();

    static HttpClient CreateClient()
    {
        var client = new HttpClient(new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            ConnectCallback = Connect,
        })
        {
            Timeout = System.Threading.Timeout.InfiniteTimeSpan,
        };
        // An app's name, as the phone's ("WatchlyTV/2"), never a browser's: Nuvio's sign-in answered any "Mozilla" with
        // 429 "too many tries", and SubDL's file server with 403 — no subtitle from there ever downloaded.
        client.DefaultRequestHeaders.UserAgent.ParseAdd($"Watchly/{typeof(Net).Assembly.GetName().Version?.ToString(3) ?? "2"} (Windows)");
        return client;
    }

    static bool online = true;
    static int probing;

    public static bool IsOnline => online;

    /// <summary>Raised on a thread-pool thread whenever <see cref="IsOnline"/> flips.</summary>
    public static event Action? OnlineChanged;

    static void Set(bool next)
    {
        if (next == online) return;
        online = next;
        OnlineChanged?.Invoke();
    }

    /// <summary>
    /// Hosts whose address comes from Cloudflare's DNS over HTTPS, not the network's: the network's answer led to a
    /// server that cut the connection. On this ISP TMDB's edge reset three tries in four, and the one DoH names answered
    /// every time. Kept for the app's life; added only after a cut.
    /// </summary>
    static readonly System.Collections.Concurrent.ConcurrentDictionary<string, IPAddress[]> viaDoh = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The handler's own connect, but to <see cref="viaDoh"/>'s addresses for those hosts (the usual way if they fail).</summary>
    static async ValueTask<System.IO.Stream> Connect(SocketsHttpConnectionContext context, CancellationToken ct)
    {
        if (viaDoh.TryGetValue(context.DnsEndPoint.Host, out var found))
        {
            var direct = new System.Net.Sockets.Socket(System.Net.Sockets.SocketType.Stream, System.Net.Sockets.ProtocolType.Tcp) { NoDelay = true };
            try
            {
                await direct.ConnectAsync(found, context.DnsEndPoint.Port, ct).ConfigureAwait(false);
                return new System.Net.Sockets.NetworkStream(direct, ownsSocket: true);
            }
            catch (Exception) when (!ct.IsCancellationRequested) { direct.Dispose(); }
        }
        var socket = new System.Net.Sockets.Socket(System.Net.Sockets.SocketType.Stream, System.Net.Sockets.ProtocolType.Tcp) { NoDelay = true };
        try
        {
            await socket.ConnectAsync(context.DnsEndPoint, ct).ConfigureAwait(false);
            return new System.Net.Sockets.NetworkStream(socket, ownsSocket: true);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    /// <summary>The connection was cut or refused, not answered: a reset, a TLS hang-up, a failed connect.</summary>
    static bool Cut(Exception e)
    {
        for (var x = e.InnerException; x is not null; x = x.InnerException)
            if (x is System.IO.IOException or System.Net.Sockets.SocketException) return true;
        return false;
    }

    /// <summary>Asks Cloudflare's DNS over HTTPS for <paramref name="host"/>, into <see cref="viaDoh"/>; false when it had nothing new.</summary>
    static async Task<bool> LookUpElsewhere(string host)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, $"https://cloudflare-dns.com/dns-query?name={Uri.EscapeDataString(host)}&type=A");
            req.Headers.Accept.ParseAdd("application/dns-json");
            using var timer = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            using var res = await Http.SendAsync(req, timer.Token).ConfigureAwait(false);
            var answers = JsonNode.Parse(await res.Content.ReadAsStringAsync(timer.Token).ConfigureAwait(false))?["Answer"] as JsonArray;
            var ips = (answers ?? new JsonArray()).OfType<JsonObject>()
                .Where(a => a["type"]?.GetValue<int>() == 1)
                .Select(a => IPAddress.TryParse(a["data"]?.GetValue<string>(), out var ip) ? ip : null)
                .OfType<IPAddress>().ToArray();
            if (ips.Length == 0) return false;
            viaDoh[host] = ips;
            Log.Info("net", $"{host}: connection cut; using Cloudflare DNS's address for it from now on");
            return true;
        }
        catch (Exception e)
        {
            Log.Warn("net", $"{host}: Cloudflare DNS lookup failed: {e.Message}");
            return false;
        }
    }

    /// <summary>
    /// A GET (or <paramref name="method"/>) with the 15 s cap every request here gets.
    /// <paramref name="quiet"/>: an indexer being down is not "you are offline". A GET whose connection is cut goes
    /// once more, to the address Cloudflare's DNS gives (see <see cref="viaDoh"/>).
    /// </summary>
    public static async Task<HttpResponseMessage> Send(
        string url, CancellationToken ct = default, bool quiet = false, HttpMethod? method = null)
    {
        var host = new Uri(url).Host;
        try
        {
            return await SendOnce(url, ct, quiet, method).ConfigureAwait(false);
        }
        catch (HttpRequestException e) when ((method ?? HttpMethod.Get) == HttpMethod.Get && Cut(e) && !viaDoh.ContainsKey(host))
        {
            if (!await LookUpElsewhere(host).ConfigureAwait(false)) throw;
        }
        return await SendOnce(url, ct, quiet, method).ConfigureAwait(false);
    }

    static async Task<HttpResponseMessage> SendOnce(string url, CancellationToken ct, bool quiet, HttpMethod? method)
    {
        using var timer = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timer.CancelAfter(Timeout);
        try
        {
            var res = await Http.SendAsync(new HttpRequestMessage(method ?? HttpMethod.Get, url),
                HttpCompletionOption.ResponseContentRead, timer.Token).ConfigureAwait(false);
            Set(true);
            return res;
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            if (!quiet)
            {
                Set(false);
                _ = ProbeLoop();
            }
            throw;
        }
    }

    public static async Task<JsonNode?> Json(string url, CancellationToken ct = default, bool quiet = false)
    {
        using var res = await Send(url, ct, quiet).ConfigureAwait(false);
        if (!res.IsSuccessStatusCode) throw new HttpRequestException($"{new Uri(url).Host} returned {(int)res.StatusCode}");
        var text = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        return JsonNode.Parse(text);
    }

    /// <summary>A JSON POST, its JSON answer — whatever the status, as APIs like Stremio's say what went wrong in the body. Quiet.</summary>
    public static async Task<JsonNode?> PostJson(string url, JsonNode body, CancellationToken ct = default)
    {
        using var timer = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timer.CancelAfter(TimeSpan.FromSeconds(30));
        using var content = new StringContent(body.ToJsonString(), System.Text.Encoding.UTF8, "application/json");
        using var res = await Http.PostAsync(url, content, timer.Token).ConfigureAwait(false);
        var text = await res.Content.ReadAsStringAsync(timer.Token).ConfigureAwait(false);
        try { return JsonNode.Parse(text); }
        catch { throw new HttpRequestException($"{new Uri(url).Host} returned {(int)res.StatusCode}"); }
    }

    /// <summary>Poll until something answers, then flip back. One loop at a time.</summary>
    static async Task ProbeLoop()
    {
        if (Interlocked.Exchange(ref probing, 1) == 1) return;
        try
        {
            for (var wait = 2000; ; wait = Math.Min(wait * 2, 30000))
            {
                await Task.Delay(wait).ConfigureAwait(false);
                try
                {
                    using var res = await Send(Probe, default, true, HttpMethod.Head).ConfigureAwait(false);
                    break;
                }
                catch
                {
                    // Still down.
                }
            }
            Set(true);
        }
        finally
        {
            Interlocked.Exchange(ref probing, 0);
        }
    }
}
