using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace Watchly.Core;

/// <summary>
/// What the viewer types in that is theirs alone — their SubDL key. Encrypted with Windows' DPAPI
/// for this Windows account (the phone and TV use the Android Keystore), in a file of its own
/// beside the settings, never in them. If it ever stops decrypting it reads as unset.
/// </summary>
public static class Secrets
{
    static readonly string File = Path.Combine(Store.Root, "secrets.bin");
    static string? subdl;

    public static event Action? Changed;

    /// <summary>The viewer's SubDL key, or "" when none was given.</summary>
    public static string Subdl => subdl ??= Read();

    /// <summary>Blank forgets it.</summary>
    public static void SetSubdl(string key)
    {
        var k = key.Trim();
        try
        {
            if (k.Length == 0) System.IO.File.Delete(File);
            else
            {
                Directory.CreateDirectory(Store.Root);
                System.IO.File.WriteAllBytes(File, Protect(Encoding.UTF8.GetBytes(k)));
            }
        }
        catch (Exception e)
        {
            Log.Warn("secrets", $"Could not store the SubDL key: {e.Message}");
        }
        subdl = k;
        Changed?.Invoke();
    }

    static string Read()
    {
        try { return System.IO.File.Exists(File) ? Encoding.UTF8.GetString(Unprotect(System.IO.File.ReadAllBytes(File))) : ""; }
        catch { return ""; }
    }

    // DPAPI straight from crypt32 — ProtectedData is a NuGet package away on .NET 8.
    [StructLayout(LayoutKind.Sequential)]
    struct Blob
    {
        public int Size;
        public IntPtr Data;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);

    [DllImport("kernel32.dll")]
    static extern IntPtr LocalFree(IntPtr mem);

    /// <summary>No UI: never ask the viewer anything while encrypting.</summary>
    const int UiForbidden = 0x1;

    internal static byte[] Protect(byte[] plain) => Crypt(plain, true);

    internal static byte[] Unprotect(byte[] cipher) => Crypt(cipher, false);

    static byte[] Crypt(byte[] data, bool protect)
    {
        var handle = GCHandle.Alloc(data, GCHandleType.Pinned);
        try
        {
            var input = new Blob { Size = data.Length, Data = handle.AddrOfPinnedObject() };
            var ok = protect
                ? CryptProtectData(ref input, "Watchly", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, UiForbidden, out var output)
                : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, UiForbidden, out output);
            if (!ok) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            try
            {
                var bytes = new byte[output.Size];
                Marshal.Copy(output.Data, bytes, 0, output.Size);
                return bytes;
            }
            finally
            {
                LocalFree(output.Data);
            }
        }
        finally
        {
            handle.Free();
        }
    }
}
