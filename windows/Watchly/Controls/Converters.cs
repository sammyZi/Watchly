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
    /// Posters already decoded, newest last. A shelf recycles its cards as it scrolls;
    /// without this each card coming into view fetched its poster again and showed
    /// blank until it landed. Only small decodes (≈0.5 MB each) — not 1920px backdrops.
    /// </summary>
    static readonly Dictionary<string, ImageSource> Recent = new();
    static readonly Queue<string> RecentOrder = new();
    const int RecentMax = 200;
    const int CacheableWidth = 400;

    public static ImageSource? Load(string? url, object? width = null)
    {
        if (!History.IsRemote(url)) return null;
        var w = int.TryParse(width?.ToString(), out var parsed) && parsed > 0 ? parsed : 0;
        var key = $"{w}|{url}";
        var cacheable = w is > 0 and <= CacheableWidth;
        if (cacheable && Recent.TryGetValue(key, out var hit)) return hit;
        try
        {
            var image = new BitmapImage();
            image.BeginInit();
            image.UriSource = new Uri(url!);
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            if (w > 0) image.DecodePixelWidth = w;
            image.EndInit();
            if (cacheable)
            {
                // ponytail: FIFO, not LRU — enough to stop the flash while scrolling; LRU if eviction ever shows.
                Recent[key] = image;
                RecentOrder.Enqueue(key);
                while (RecentOrder.Count > RecentMax) Recent.Remove(RecentOrder.Dequeue());
                // A failed download must not stay cached as a blank.
                image.DownloadFailed += (_, _) => Recent.Remove(key);
            }
            return image;
        }
        catch
        {
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
