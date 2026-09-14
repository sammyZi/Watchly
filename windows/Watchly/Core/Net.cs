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
        })
        {
            Timeout = System.Threading.Timeout.InfiniteTimeSpan,
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Watchly/1.0");
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
    /// A GET (or <paramref name="method"/>) with the 15 s cap every request here gets.
    /// <paramref name="quiet"/>: an indexer being down is not "you are offline".
    /// </summary>
    public static async Task<HttpResponseMessage> Send(
        string url, CancellationToken ct = default, bool quiet = false, HttpMethod? method = null)
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
