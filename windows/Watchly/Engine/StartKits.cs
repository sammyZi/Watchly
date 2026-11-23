using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using Watchly.Core;

namespace Watchly.Engine;

/// <summary>
/// What a film closed part-way leaves in the stream cache so it opens again at once — mobile StartKits.kt:
/// its header, its index at the end and about a minute either side of where it was left, the rest of the
/// file zeroed out of the sparse file, and MonoTorrent's fast-resume file rewritten to own just those pieces.
/// Without them a second play waited on the swarm for all three again before the first picture.
/// One per torrent, by info-hash: its folder is the stream cache's <c>hex</c>.
/// </summary>
public static class StartKits
{
    /// <summary>Left unplayed this long, a kept start goes. Calibration knob.</summary>
    static readonly TimeSpan MaxAge = TimeSpan.FromDays(14);
    /// <summary>All kept starts together stay under this; the longest unplayed go first. Calibration knob.</summary>
    const long Cap = 1024L * 1024 * 1024;
    /// <summary>Closed past this much of the file, it was watched: nothing is kept.</summary>
    public const double Watched = 0.95;

    const long Mb = 1024 * 1024;
    const long HeadBytes = 8 * Mb, AheadBytes = 24 * Mb;
    /// <summary>
    /// Behind by time, within these: a resume opens at the index's keyframe before its spot — on the phone
    /// 15-21MB back on an 8MB-piece film (~40s of it) — and a replay waited on that one piece.
    /// </summary>
    const double BehindSeconds = 60;
    const long BehindMin = 16 * Mb, BehindMax = 64 * Mb;

    public sealed record Kit(string Hash, long Bytes, DateTime SavedAt);

    static string Index => Path.Combine(Paths.StreamCache, "starts.json");
    static readonly object Gate = new();

    /// <summary>
    /// The pieces of a torrent to keep, and the byte ranges of the file to zero: every piece it has but those
    /// of the file outside its header, the stretch about [ratio] and its end (where the index usually is).
    /// </summary>
    public static (List<(long From, long To)> Cuts, List<int> Kept) Plan(long fileStart, long fileLen, long pieceLen, int pieceCount, double ratio, double bytesPerSecond, Func<int, bool> have)
    {
        var at = (long)(fileLen * ratio);
        var behind = Math.Clamp((long)(bytesPerSecond * BehindSeconds), BehindMin, BehindMax);
        var tail = Math.Clamp(fileLen / 200, 2 * Mb, 16 * Mb);
        (long From, long To)[] keep = [(0, Math.Min(HeadBytes, fileLen)), (Math.Max(0, at - behind), Math.Min(fileLen, at + AheadBytes)), (Math.Max(0, fileLen - tail), fileLen)];

        var first = (int)(fileStart / pieceLen);
        var last = (int)((fileStart + fileLen - 1) / pieceLen);
        // A piece's bytes within this file.
        (long From, long To) Span(int p) => (Math.Max(0, p * pieceLen - fileStart), Math.Min(fileLen, (p + 1) * pieceLen - fileStart));
        bool Cut(int p)
        {
            var (from, to) = Span(p);
            return p >= first && p <= last && !keep.Any(k => from < k.To && k.From < to);
        }

        var cuts = new List<(long, long)>();
        int? run = null;
        for (var p = first; p <= last + 1; p++)
        {
            var cut = p <= last && Cut(p);
            if (cut && run is null) run = p;
            if (!cut && run is int r)
            {
                cuts.Add((Span(r).From, Span(p - 1).To));
                run = null;
            }
        }
        var kept = Enumerable.Range(0, pieceCount).Where(p => have(p) && !Cut(p)).ToList();
        return (cuts, kept);
    }

    public static void Put(string hash, long bytes)
    {
        lock (Gate)
        {
            var kits = Load().Where(k => k.Hash != hash).Append(new Kit(hash, bytes, DateTime.UtcNow)).ToList();
            Save(kits);
        }
        Prune();
    }

    /// <summary>Too old, or over the cap counting from the newest: their folders, fast-resume files and entries go.</summary>
    public static void Prune()
    {
        lock (Gate)
        {
            var kits = Load();
            long total = 0;
            var gone = kits.OrderByDescending(k => k.SavedAt).Where(k =>
            {
                total += k.Bytes;
                return DateTime.UtcNow - k.SavedAt > MaxAge || total > Cap || !Directory.Exists(Path.Combine(Paths.StreamCache, k.Hash));
            }).ToList();
            if (gone.Count == 0) return;
            foreach (var k in gone)
            {
                try { Directory.Delete(Path.Combine(Paths.StreamCache, k.Hash), true); } catch { }
                try { if (ResumeFile(k.Hash) is { } f) File.Delete(f); } catch { }
                Log.Info("start", $"kept start of {k.Hash} gone ({(DateTime.UtcNow - k.SavedAt).TotalDays:0} days unplayed)");
            }
            Save(kits.Except(gone).ToList());
        }
    }

    /// <summary>The kept starts left after <see cref="Prune"/>, by their folder's name (the hash, lower case).</summary>
    public static HashSet<string> Hashes()
    {
        lock (Gate) return Load().Select(k => k.Hash.ToLowerInvariant()).ToHashSet();
    }

    /// <summary>MonoTorrent's fast-resume file for a hash, once the engine has said where those live.</summary>
    public static Func<string, string?> ResumeFile = _ => null;

    static List<Kit> Load()
    {
        try { return JsonSerializer.Deserialize<List<Kit>>(File.ReadAllText(Index)) ?? new(); }
        catch { return new(); }
    }

    static void Save(List<Kit> kits)
    {
        try
        {
            Directory.CreateDirectory(Paths.StreamCache);
            File.WriteAllText(Index, JsonSerializer.Serialize(kits));
        }
        catch { }
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool DeviceIoControl(Microsoft.Win32.SafeHandles.SafeFileHandle handle, uint code, ref ZeroData inBuffer, int inSize, IntPtr outBuffer, int outSize, out int returned, IntPtr overlapped);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool DeviceIoControl(Microsoft.Win32.SafeHandles.SafeFileHandle handle, uint code, IntPtr inBuffer, int inSize, IntPtr outBuffer, int outSize, out int returned, IntPtr overlapped);

    [StructLayout(LayoutKind.Sequential)]
    struct ZeroData { public long FileOffset; public long BeyondFinalZero; }

    /// <summary>The ranges zeroed, and on a sparse file (made so first) freed from the disk. False if any wasn't.</summary>
    public static bool Zero(string path, List<(long From, long To)> ranges)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete);
        DeviceIoControl(stream.SafeFileHandle, 0x900C4 /* FSCTL_SET_SPARSE */, IntPtr.Zero, 0, IntPtr.Zero, 0, out _, IntPtr.Zero);
        var ok = true;
        foreach (var (from, to) in ranges)
        {
            var z = new ZeroData { FileOffset = from, BeyondFinalZero = to };
            ok &= DeviceIoControl(stream.SafeFileHandle, 0x980C8 /* FSCTL_SET_ZERO_DATA */, ref z, Marshal.SizeOf<ZeroData>(), IntPtr.Zero, 0, out _, IntPtr.Zero);
        }
        return ok;
    }
}
