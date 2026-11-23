using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>
/// tv/core Decoders.kt for this PC: whether a release's video decodes on the graphics card, as libVLC decodes it
/// (Direct3D 11 video). In software a CPU drops frames on 4K, and on AV1 without the card's help. Asked of the
/// driver once per codec. The driver doesn't say a size is too big (it said yes to 16K), so only the codec counts.
/// </summary>
public static class Decoders
{
    /// <summary>What a release's name says of its video; null when it names no codec. The phone's.</summary>
    internal sealed record Guess(string Codec, Guid Profile, int Format, bool UltraHd);

    // Direct3D's decoder profiles, and the surfaces they decode to: NV12 for 8-bit, P010 for 10-bit.
    static readonly Guid H264 = new("1b81be68-a0c7-11d3-b984-00c04f2e73c5");
    static readonly Guid Hevc = new("5b11d51b-2f4c-4452-bcc3-09f2a1160cc0");
    static readonly Guid Hevc10 = new("107af0e0-ef1a-4d19-aba8-67a163073d13");
    static readonly Guid Av1 = new("b8be4ccb-cf53-46ba-8d59-d6b8a6da5d2a");
    static readonly Guid Vp9 = new("463707f8-a1d0-4585-876d-83aa6d60b89e");
    const int Nv12 = 103, P010 = 104;

    internal static Guess? VideoOf(string release)
    {
        var r = release.ToLowerInvariant();
        bool Has(string pattern) => Regex.IsMatch(r, $"(?<![a-z0-9])({pattern})(?![a-z0-9])");
        var deep = Has("10bit|10-bit|hdr|hdr10|dv|dovi");
        var uhd = Has("2160p|4k|uhd");
        if (Has("av1")) return new("AV1", Av1, deep ? P010 : Nv12, uhd);
        if (Has(@"x265|h\.?265|hevc")) return new("HEVC", deep ? Hevc10 : Hevc, deep ? P010 : Nv12, uhd);
        if (Has(@"x264|h\.?264|avc")) return new("H.264", H264, Nv12, uhd);
        if (Has("vp9")) return new("VP9", Vp9, Nv12, uhd);
        return null;
    }

    static readonly Dictionary<(Guid, int), bool> known = new();

    /// <summary>"AV1 4K", when the release's video won't decode on the graphics card here; null when it will, or its name doesn't say.</summary>
    public static string? Choppy(string release)
    {
        if (VideoOf(release) is not { } v) return null;
        bool ok;
        lock (known)
            if (!known.TryGetValue((v.Profile, v.Format), out ok)) known[(v.Profile, v.Format)] = ok = OnCard(v.Profile, v.Format);
        return ok ? null : v.UltraHd ? $"{v.Codec} 4K" : v.Codec;
    }

    [DllImport("d3d11.dll")]
    static extern int D3D11CreateDevice(IntPtr adapter, int driverType, IntPtr software, uint flags, IntPtr levels, uint numLevels, uint sdk, out IntPtr device, out int level, out IntPtr context);

    delegate int QueryInterface(IntPtr self, ref Guid iid, out IntPtr obj);
    delegate uint Release(IntPtr self);
    [StructLayout(LayoutKind.Sequential)] struct DecoderDesc { public Guid Guid; public uint Width, Height; public int OutputFormat; }
    delegate int GetConfigCount(IntPtr self, ref DecoderDesc desc, out uint count);

    /// <summary>A COM method by its place in the object's table: ID3D11VideoDevice has no wrapper in .NET.</summary>
    static T Slot<T>(IntPtr com, int slot) where T : Delegate => Marshal.GetDelegateForFunctionPointer<T>(Marshal.ReadIntPtr(Marshal.ReadIntPtr(com), slot * IntPtr.Size));

    /// <summary>The card has a decoder for this profile: ID3D11VideoDevice.GetVideoDecoderConfigCount gives it configurations. No card, no D3D: true, nothing to warn of.</summary>
    static bool OnCard(Guid profile, int format)
    {
        try
        {
            // D3D_DRIVER_TYPE_HARDWARE, D3D11_CREATE_DEVICE_VIDEO_SUPPORT, D3D11_SDK_VERSION.
            if (D3D11CreateDevice(IntPtr.Zero, 1, IntPtr.Zero, 0x800, IntPtr.Zero, 0, 7, out var device, out _, out var context) < 0) return true;
            try
            {
                var iid = new Guid("10EC4D5B-975A-4689-B9E4-D0AAC30FE333");
                if (Slot<QueryInterface>(device, 0)(device, ref iid, out var video) < 0) return true;
                try
                {
                    var desc = new DecoderDesc { Guid = profile, Width = 1920, Height = 1080, OutputFormat = format };
                    // Slot 14: GetVideoDecoderConfigCount, after IUnknown's three and the device's eleven before it.
                    return Slot<GetConfigCount>(video, 14)(video, ref desc, out var n) >= 0 && n > 0;
                }
                finally { Slot<Release>(video, 2)(video); }
            }
            finally
            {
                if (context != IntPtr.Zero) Slot<Release>(context, 2)(context);
                Slot<Release>(device, 2)(device);
            }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException or SEHException)
        {
            return true;
        }
    }
}
