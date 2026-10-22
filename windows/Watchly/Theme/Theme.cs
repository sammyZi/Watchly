using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

namespace Watchly;

/// <summary>Light / dark / follow Windows — src/theme.ts's ThemeProvider.</summary>
public static class Theme
{
    static string mode = "system";
    static bool listening;

    public static bool IsLight { get; private set; }

    public static event Action? Changed;

    static bool SystemLight()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int v && v == 1;
        }
        catch
        {
            return false;
        }
    }

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>
    /// The title bar in the app's colours: black in dark, white in light. Windows
    /// 11 takes an exact caption colour; Windows 10 only has the dark switch.
    /// </summary>
    public static void TitleBar(Window window)
    {
        var hwnd = new System.Windows.Interop.WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero) return;
        var dark = IsLight ? 0 : 1;
        DwmSetWindowAttribute(hwnd, 20, ref dark, sizeof(int)); // DWMWA_USE_IMMERSIVE_DARK_MODE
        var caption = IsLight ? 0x00FFFFFF : 0x00000000;        // COLORREF, 0x00BBGGRR
        DwmSetWindowAttribute(hwnd, 35, ref caption, sizeof(int)); // DWMWA_CAPTION_COLOR
        var text = IsLight ? 0x001A1412 : 0x00FAF5F3;
        DwmSetWindowAttribute(hwnd, 36, ref text, sizeof(int));    // DWMWA_TEXT_COLOR
    }

    public static void Apply(string next)
    {
        mode = next;
        if (!listening)
        {
            listening = true;
            SystemEvents.UserPreferenceChanged += (_, e) =>
            {
                if (e.Category == UserPreferenceCategory.General && mode == "system")
                    Application.Current?.Dispatcher.BeginInvoke(() => Apply("system"));
            };
        }
        Application.Current.Resources["AccentGlow"] ??= AccentGlow();
        var light = mode == "light" || (mode == "system" && SystemLight());
        var dictionaries = Application.Current.Resources.MergedDictionaries;
        if (dictionaries.Count > 0 && IsLight == light && dictionaries[0].Source is not null) return;
        IsLight = light;
        var palette = new ResourceDictionary { Source = new Uri($"pack://application:,,,/Theme/{(light ? "Light" : "Dark")}.xaml") };
        if (dictionaries.Count == 0) dictionaries.Add(palette);
        else dictionaries[0] = palette;
        Changed?.Invoke();
    }

    /// <summary>
    /// The primary button's grainy red glow — core's Modifier.glow(accentGlow(), 0.85f, 1.1f) on the
    /// phone and TV: a deep base, the red, and a warm light rising from below the right-hand end,
    /// with film grain over it. The same in light and dark, like the accent.
    /// </summary>
    public static Brush AccentGlow()
    {
        static Color Lerp(Color a, Color b, double t) =>
            Color.FromRgb((byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));
        var accent = Color.FromRgb(0xb8, 0x1d, 0x24);
        var deep = Lerp(accent, Colors.Black, 0.45);
        var warm = Lerp(accent, Color.FromRgb(0xff, 0x8a, 0x4c), 0.6);
        var all = new RectangleGeometry(new Rect(0, 0, 1, 1));
        // A circle 0.95 of the button's width across, on a button about three times as wide as tall.
        var glow = new RadialGradientBrush(new GradientStopCollection { new(warm, 0), new(accent, 0.5), new(deep, 1) })
        {
            Center = new Point(0.85, 1.1), GradientOrigin = new Point(0.85, 1.1), RadiusX = 0.95, RadiusY = 2.9,
        };
        var layers = new DrawingGroup();
        layers.Children.Add(new GeometryDrawing(new SolidColorBrush(deep), null, all));
        layers.Children.Add(new GeometryDrawing(glow, null, all));
        layers.Children.Add(new ImageDrawing(Grain(), new Rect(0, 0, 1, 1)));
        var brush = new DrawingBrush(layers) { Stretch = Stretch.Fill };
        brush.Freeze();
        return brush;
    }

    /// <summary>Fine black-and-white noise, at most 22/255 opaque — about a button's size, so barely stretched.</summary>
    static BitmapSource Grain()
    {
        const int w = 256, h = 80;
        var px = new byte[w * h * 4];
        var random = new Random(7);
        for (var i = 0; i < px.Length; i += 4)
        {
            var v = (byte)(random.Next(2) * 255);
            px[i] = px[i + 1] = px[i + 2] = v;
            px[i + 3] = (byte)random.Next(23);
        }
        var bitmap = BitmapSource.Create(w, h, 96, 96, PixelFormats.Bgra32, null, px, w * 4);
        bitmap.Freeze();
        return bitmap;
    }
}
