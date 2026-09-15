using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Watchly.Core;

namespace Watchly.Controls;

public sealed class BoolToVisibility : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var on = value switch
        {
            bool b => b,
            int n => n != 0,
            string s => s.Length > 0,
            null => false,
            _ => true,
        };
        if (parameter is "invert") on = !on;
        return on ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>
/// A poster URL as a decoded bitmap, downscaled to the width it is drawn at —
/// a shelf holds hundreds of posters, and full-size decodes would hold hundreds
/// of megabytes. Anything that is not a web URL is no image at all, and the
/// coloured tile behind it shows instead.
/// </summary>
public sealed class ImageUrl : IValueConverter
{
    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture) => Load(value as string, parameter);

    /// <summary>
    /// Images already asked for, newest last — a shelf recycles its cards as it scrolls,
    /// and a card coming back into view must not fetch its poster again. Posters are
    /// ≈0.5 MB decoded; the few 1920px backdrops (≈8 MB) get a much shorter list.
    /// </summary>
    static readonly Dictionary<string, ImageSource> Recent = new();
    static readonly Queue<string> Small = new(), Large = new();
    const int SmallMax = 200, LargeMax = 6, SmallWidth = 400;

    static readonly string DiskCache = System.IO.Path.Combine(Paths.LocalRoot, "images");
    static readonly SemaphoreSlim Fetching = new(6);

    /// <summary>
    /// Returns at once with an image that fills in when the picture is ready. The
    /// download, the disk cache and the decode all happen on a worker: decoding on
    /// the UI thread (what a BitmapImage with a web address does) stalled scrolling
    /// and every page with posters on it.
    /// </summary>
    public static ImageSource? Load(string? url, object? width = null)
    {
        if (!History.IsRemote(url)) return null;
        var w = int.TryParse(width?.ToString(), out var parsed) && parsed > 0 ? parsed : 0;
        var key = $"{w}|{url}";
        if (Recent.TryGetValue(key, out var hit)) return hit;

        // Empty until the bitmap lands; the brushes drawing it repaint when it does.
        var drawing = new ImageDrawing();
        var image = new DrawingImage(drawing);
        Recent[key] = image;
        var order = w is > 0 and <= SmallWidth ? Small : Large;
        order.Enqueue(key);
        while (order.Count > (order == Small ? SmallMax : LargeMax)) Recent.Remove(order.Dequeue());

        var ui = System.Windows.Threading.Dispatcher.CurrentDispatcher;
        _ = Task.Run(() => Decode(url!, w)).ContinueWith(t => ui.BeginInvoke(() =>
        {
            if (t.Result is { } bitmap)
            {
                drawing.ImageSource = bitmap;
                drawing.Rect = new Rect(0, 0, bitmap.PixelWidth, bitmap.PixelHeight);
            }
            else if (Recent.GetValueOrDefault(key) == image)
            {
                Recent.Remove(key); // failed: try again next time it is shown
            }
        }), TaskScheduler.Default);
        return image;
    }

    static async Task<BitmapSource?> Decode(string url, int width)
    {
        var file = System.IO.Path.Combine(DiskCache, System.Convert.ToHexString(System.Security.Cryptography.SHA1.HashData(System.Text.Encoding.UTF8.GetBytes(url))));
        try
        {
            byte[] bytes;
            if (System.IO.File.Exists(file))
            {
                bytes = await System.IO.File.ReadAllBytesAsync(file);
            }
            else
            {
                await Fetching.WaitAsync();
                try { bytes = await Net.Http.GetByteArrayAsync(url); }
                finally { Fetching.Release(); }
                // ponytail: never pruned — posters are ~50 KB; add a size cap if the folder ever matters.
                System.IO.Directory.CreateDirectory(DiskCache);
                // Written aside then moved: the same poster at two sizes may land at once.
                var part = $"{file}.{Guid.NewGuid():N}.part";
                await System.IO.File.WriteAllBytesAsync(part, bytes);
                System.IO.File.Move(part, file, true);
            }
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.StreamSource = new System.IO.MemoryStream(bytes);
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            if (width > 0) bitmap.DecodePixelWidth = width;
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }
        catch
        {
            // A half-written or non-image file must not keep failing from the cache.
            try { System.IO.File.Delete(file); } catch { }
            return null;
        }
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>A stable colour from a name — src/components/Thumb.tsx's hsl(hue, 34%, 74%).</summary>
public sealed class HueBrush : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var name = value as string ?? "";
        var (s, l) = parameter is "dark" ? (0.42, 0.34) : (0.34, 0.74);
        var brush = new SolidColorBrush(Hsl(TitleText.HueOf(name), s, l));
        brush.Freeze();
        return brush;
    }

    public static Color Hsl(double h, double s, double l)
    {
        var c = (1 - Math.Abs(2 * l - 1)) * s;
        var x = c * (1 - Math.Abs(h / 60 % 2 - 1));
        var m = l - c / 2;
        var (r, g, b) = h switch
        {
            < 60 => (c, x, 0.0),
            < 120 => (x, c, 0.0),
            < 180 => (0.0, c, x),
            < 240 => (0.0, x, c),
            < 300 => (x, 0.0, c),
            _ => (c, 0.0, x),
        };
        return Color.FromRgb((byte)((r + m) * 255), (byte)((g + m) * 255), (byte)((b + m) * 255));
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>
/// A corner radius no bigger than half the height. WPF stretches an oversized
/// radius into an ellipse instead of clamping it, so "999 for a pill" drew ovals.
/// </summary>
public sealed class PillRadius : IMultiValueConverter
{
    public object Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
    {
        var wanted = double.TryParse(values[0]?.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var t) ? t : 0;
        var height = values[1] is double h && h > 0 ? h : 0;
        return new CornerRadius(height > 0 ? Math.Min(wanted, height / 2) : wanted >= 999 ? 0 : wanted);
    }

    public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>" · Series" after the year for a series, nothing for a film.</summary>
public sealed class KindSuffix : IValueConverter
{
    public static readonly KindSuffix Instance = new();

    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value as string == "tv" ? " · Series" : "";

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>Initials for a cast card: "Keanu Reeves" -> "KR".</summary>
public sealed class Initials : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        string.Concat((value as string ?? "").Split(' ', StringSplitOptions.RemoveEmptyEntries).Take(2).Select(p => char.ToUpperInvariant(p[0])));

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
}
