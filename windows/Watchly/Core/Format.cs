namespace Watchly.Core;

/// <summary>src/lib/format.ts.</summary>
public static class Format
{
    public static string Speed(double bytesPerSec) =>
        bytesPerSec > 1e6 ? $"{bytesPerSec / 1e6:0.0} MB/s" : $"{Math.Round(bytesPerSec / 1e3)} kB/s";

    /// <summary>75 -> "1:15", 3675 -> "1:01:15".</summary>
    public static string Clock(double seconds)
    {
        var t = (long)Math.Max(0, Math.Floor(double.IsFinite(seconds) ? seconds : 0));
        var h = t / 3600;
        var m = t / 60 % 60;
        var s = t % 60;
        return h > 0 ? $"{h}:{m:00}:{s:00}" : $"{m}:{s:00}";
    }

    /// <summary>Coarse "how much is left" — 640 -> "11m left".</summary>
    public static string Left(double seconds)
    {
        var mins = (long)Math.Round(Math.Max(0, seconds) / 60);
        if (mins < 1) return "almost done";
        if (mins < 60) return $"{mins}m left";
        return $"{mins / 60}h {mins % 60}m left";
    }

    public static string Ago(long ms)
    {
        var mins = (Store.Now - ms) / 60000;
        if (mins < 1) return "just now";
        if (mins < 60) return $"{mins}m ago";
        if (mins < 1440) return $"{mins / 60}h ago";
        return $"{mins / 1440}d ago";
    }

    public static string Bytes(double n)
    {
        const double k = 1024;
        if (n >= k * k * k) return $"{n / (k * k * k):0.00} GB";
        if (n >= k * k) return $"{n / (k * k):0} MB";
        if (n >= k) return $"{n / k:0} KB";
        return $"{n} B";
    }

    public static string Eta(double remainingBytes, double bytesPerSec)
    {
        if (bytesPerSec <= 0 || remainingBytes <= 0) return "—";
        var secs = (long)Math.Round(remainingBytes / bytesPerSec);
        if (secs < 60) return $"{secs}s left";
        if (secs < 3600) return $"{Math.Round(secs / 60.0)}m left";
        return $"{secs / 3600}h {Math.Round(secs % 3600 / 60.0)}m left";
    }

    /// <summary>JS localeCompare(..., { numeric: true, sensitivity: 'base' }).</summary>
    public static int Natural(string? a, string? b)
    {
        a ??= "";
        b ??= "";
        int i = 0, j = 0;
        while (i < a.Length && j < b.Length)
        {
            if (char.IsDigit(a[i]) && char.IsDigit(b[j]))
            {
                int si = i, sj = j;
                while (i < a.Length && char.IsDigit(a[i])) i++;
                while (j < b.Length && char.IsDigit(b[j])) j++;
                var na = a[si..i].TrimStart('0');
                var nb = b[sj..j].TrimStart('0');
                if (na.Length != nb.Length) return na.Length.CompareTo(nb.Length);
                var c = string.CompareOrdinal(na, nb);
                if (c != 0) return c;
            }
            else
            {
                var c = string.Compare(a[i].ToString(), b[j].ToString(), StringComparison.OrdinalIgnoreCase);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.Length - i).CompareTo(b.Length - j);
    }
}
