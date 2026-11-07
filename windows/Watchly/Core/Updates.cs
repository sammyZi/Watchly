using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json.Nodes;

namespace Watchly.Core;

public sealed record AppRelease(string Version, string Url, long Size, string? Sha256);

/// <summary>
/// tv/core Updates.kt on a PC: new versions from the GitHub releases, asked at each launch, downloaded
/// here, and handed to the installer, which closes this app and asks the viewer to go ahead.
/// </summary>
public static class Updates
{
    const string Latest = "https://api.github.com/repos/sammyZi/Watchly/releases/latest";
    /// <summary>Where this project's release files are. An update from anywhere else is not offered.</summary>
    const string ReleaseFiles = "https://github.com/sammyZi/Watchly/releases/download/";
    const string SkippedKey = "update-skipped";

    public static string Version => typeof(Updates).Assembly.GetName().Version?.ToString(3) ?? "0";

    /// <summary>A version newer than this one, once found.</summary>
    public static AppRelease? Found { get; private set; }
    /// <summary>The card is put away ("Not now"); Settings still offers it.</summary>
    public static bool Hidden { get; private set; }
    /// <summary>0–1 while downloading.</summary>
    public static double? Progress { get; private set; }
    /// <summary>On any thread.</summary>
    public static event Action? Changed;

    static CancellationTokenSource? download;

    /// <summary>"v2.4.1" against "2.4.0", number by number: 2.10 is newer than 2.9.</summary>
    public static bool IsNewer(string latest, string current)
    {
        static int[] Parts(string v) => v.Trim().TrimStart('v').Split('-')[0].Split('.').Select(p => int.TryParse(p, out var n) ? n : 0).ToArray();
        int[] a = Parts(latest), b = Parts(current);
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var difference = a.ElementAtOrDefault(i) - b.ElementAtOrDefault(i);
            if (difference != 0) return difference > 0;
        }
        return false;
    }

    /// <summary>The Windows installer on GitHub's latest release, which also holds the phone's and TV's APKs. Null when a release has none.</summary>
    public static AppRelease? Parse(string json)
    {
        var o = JsonNode.Parse(json);
        var version = o?["tag_name"]?.GetValue<string>().TrimStart('v');
        var asset = (o?["assets"] as JsonArray)?.FirstOrDefault(a => a?["name"]?.GetValue<string>() is { } name && name.StartsWith("Watchly-windows-") && name.EndsWith("-setup.exe"));
        if (version is null || asset is null) return null;
        // Run as soon as it is downloaded, so: this project's own release files only, and only with the checksum GitHub states for them.
        var url = asset["browser_download_url"]!.GetValue<string>();
        var digest = asset["digest"]?.GetValue<string>();
        if (!url.StartsWith(ReleaseFiles, StringComparison.Ordinal) || digest is null) return null;
        return new AppRelease(version, url, asset["size"]?.GetValue<long>() ?? 0, digest.Replace("sha256:", ""));
    }

    static async Task<AppRelease?> Ask()
    {
        using var res = await Net.Send(Latest, default, true).ConfigureAwait(false);
        var found = Parse(await res.Content.ReadAsStringAsync().ConfigureAwait(false));
        return found is not null && IsNewer(found.Version, Version) ? found : null;
    }

    /// <summary>At launch, quietly.</summary>
    public static void Check() => _ = Task.Run(async () =>
    {
        try
        {
            if (await Ask().ConfigureAwait(false) is not { } found) return;
            Hidden = Store.Load<string?>(SkippedKey, null) == found.Version;
            Found = found;
            Changed?.Invoke();
        }
        catch (Exception e)
        {
            Log.Warn("updates", e.Message);
        }
    });

    /// <summary>From Settings: brings back a version put off with "Not now".</summary>
    public static async Task CheckNow(Action<string> say)
    {
        try
        {
            Found = await Ask();
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            say("Couldn't check for updates");
            return;
        }
        Hidden = false;
        Changed?.Invoke();
        if (Found is null) say("You're on the latest version");
    }

    public static void Skip(string version)
    {
        Store.Save(SkippedKey, version);
        Hidden = true;
        Changed?.Invoke();
    }

    public static void Cancel() => download?.Cancel();

    /// <summary>Checked against GitHub's size and SHA-256, then the installer takes over and this app closes.</summary>
    public static async Task Download(AppRelease r, Action<string> say, Action quit)
    {
        if (download is not null) return;
        using var cancel = download = new CancellationTokenSource();
        var path = Path.Combine(Path.GetTempPath(), $"Watchly-{r.Version}-setup.exe");
        Progress = 0;
        Changed?.Invoke();
        var ready = false;
        try
        {
            using (var res = await Net.Http.GetAsync(r.Url, HttpCompletionOption.ResponseHeadersRead, cancel.Token))
            {
                res.EnsureSuccessStatusCode();
                await using var input = await res.Content.ReadAsStreamAsync(cancel.Token);
                await using var output = File.Create(path);
                using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                var buffer = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = await input.ReadAsync(buffer, cancel.Token)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, n), cancel.Token);
                    sha.AppendData(buffer, 0, n);
                    done += n;
                    if (r.Size > 0)
                    {
                        Progress = (double)done / r.Size;
                        Changed?.Invoke();
                    }
                }
                var got = Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
                if ((r.Size > 0 && done != r.Size) || (r.Sha256 is not null && got != r.Sha256))
                    throw new IOException("The update didn't download properly. Try again.");
            }
            ready = true;
            Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
            quit();
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e) when (e is HttpRequestException or IOException or System.ComponentModel.Win32Exception)
        {
            say(e is System.ComponentModel.Win32Exception ? "The update needs your go-ahead to install" : e.Message);
        }
        finally
        {
            if (!ready) try { File.Delete(path); } catch (IOException) { }
            download = null;
            Progress = null;
            Changed?.Invoke();
        }
    }
}
