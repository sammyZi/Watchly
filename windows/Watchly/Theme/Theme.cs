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
        var caption = IsLight ? 0x00EEF2F6 : 0x00000000;        // COLORREF, 0x00BBGGRR
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
        // Before the return below: a launch in dark stops there, App.xaml having loaded Dark already.
        Application.Current.Resources["PageBg"] ??= PageBrush((Color)Application.Current.Resources["BgColor"]);
        var light =mode == "light" || (mode == "system" && SystemLight());
        var dictionaries = Application.Current.Resources.MergedDictionaries;
        if (dictionaries.Count > 0 && IsLight == light && dictionaries[0].Source is not null)
        {
            PaintAccent();
            return;
        }
        IsLight = light;
        var palette = new ResourceDictionary { Source = new Uri($"pack://application:,,,/Theme/{(light ? "Light" : "Dark")}.xaml") };
        if (dictionaries.Count == 0) dictionaries.Add(palette);
        else dictionaries[0] = palette;
        Application.Current.Resources["PageBg"] = PageBrush((Color)palette["BgColor"]);
        PaintAccent();
        Changed?.Invoke();
    }

    static bool amoled;

    /// <summary>Dark pages in pure black, for OLED screens — core Theme.kt's BLACK palette. The light theme is unchanged.</summary>
    public static void SetAmoled(bool on)
    {
        amoled = on;
        PaintAccent();
    }

    /// <summary>core's BLACK: the page black, cards only just above it, to be told apart. No grain, which would grey it.</summary>
    static void PaintBlack()
    {
        var res = Application.Current.Resources;
        var keys = new[] { "BgColor", "Bg", "Surface", "SurfaceAlt", "Line" };
        if (amoled && !IsLight)
        {
            res["BgColor"] = Colors.Black;
            res["Bg"] = Frozen(Colors.Black);
            res["Surface"] = Frozen(Color.FromRgb(0x0a, 0x0a, 0x0a));
            res["SurfaceAlt"] = Frozen(Color.FromRgb(0x16, 0x16, 0x16));
            res["Line"] = Frozen(Color.FromRgb(0x24, 0x24, 0x24));
            res["PageBg"] = Frozen(Colors.Black);
            black = true;
            return;
        }
        if (!black) return;
        black = false;
        foreach (var k in keys) res.Remove(k);
        res["PageBg"] = PageBrush((Color)res["BgColor"]);
    }

    /// <summary>The black overrides are in: taking them out puts the palette's own page back.</summary>
    static bool black;

    static int accent;

    /// <summary>The accent picked in Settings (ARGB; 0 is the phone's red), painted over the palette's own red.</summary>
    public static void SetAccent(int argb)
    {
        accent = argb;
        PaintAccent();
    }

    static Color AccentColor => accent == 0 ? Color.FromRgb(0xb8, 0x1d, 0x24) : Color.FromRgb((byte)(accent >> 16), (byte)(accent >> 8), (byte)accent);

    /// <summary>
    /// Accent, its soft tint and the text on it as app-level brushes, which win over the palette's; with the
    /// red, none, so the palette's own hand-picked ones stand. Every use is a DynamicResource: it all repaints.
    /// </summary>
    static void PaintAccent()
    {
        PaintBlack();
        var res = Application.Current.Resources;
        res["AccentGlow"] = AccentGlow();
        if (accent == 0)
        {
            res.Remove("Accent");
            res.Remove("AccentSoft");
            res.Remove("OnAccent");
            return;
        }
        var c = AccentColor;
        res["Accent"] = Frozen(c);
        res["AccentSoft"] = Frozen(IsLight ? Lerp(Colors.White, c, 0.1) : Lerp((Color)res["BgColor"], c, 0.17));
        // Dark on a pale accent (white), as core's Ink.onAccent.
        static double Lin(byte v) => v / 255.0 <= 0.04045 ? v / 255.0 / 12.92 : Math.Pow((v / 255.0 + 0.055) / 1.055, 2.4);
        var luminance = 0.2126 * Lin(c.R) + 0.7152 * Lin(c.G) + 0.0722 * Lin(c.B);
        res["OnAccent"] = Frozen(luminance > 0.5 ? Color.FromRgb(0x14, 0x14, 0x14) : Colors.White);
    }

    static SolidColorBrush Frozen(Color c)
    {
        var brush = new SolidColorBrush(c);
        brush.Freeze();
        return brush;
    }

    static Color Lerp(Color a, Color b, double t) =>
        Color.FromRgb((byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));

    /// <summary>core Theme.kt's accentGlow light: the hue turned 25° warmer, a little paler, at full brightness (orange in the red).</summary>
    static Color Warmer(Color c)
    {
        double r = c.R / 255.0, g = c.G / 255.0, b = c.B / 255.0;
        double max = Math.Max(r, Math.Max(g, b)), d = max - Math.Min(r, Math.Min(g, b));
        var h = d == 0 ? 0 : max == r ? 60 * ((g - b) / d % 6) : max == g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
        h = ((h + 25) % 360 + 360) % 360;
        var s = max == 0 ? 0 : d / max * 0.75;
        double x = s * (1 - Math.Abs(h / 60 % 2 - 1)), m = 1 - s;
        var (r1, g1, b1) = h < 60 ? (s, x, 0.0) : h < 120 ? (x, s, 0.0) : h < 180 ? (0.0, s, x) : h < 240 ? (0.0, x, s) : h < 300 ? (x, 0.0, s) : (s, 0.0, x);
        return Color.FromRgb((byte)((r1 + m) * 255), (byte)((g1 + m) * 255), (byte)((b1 + m) * 255));
    }

    /// <summary>
    /// The primary button's grainy red glow — core's Modifier.glow(accentGlow(), 0.85f, 1.1f) on the
    /// phone and TV: a deep base, the red, and a warm light rising from below the right-hand end,
    /// with film grain over it. The same in light and dark, like the accent.
    /// </summary>
    public static Brush AccentGlow()
    {
        var accent = AccentColor;
        var deep = Lerp(accent, Colors.Black, 0.45);
        var warm = Lerp(accent, Warmer(accent), 0.6);
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

    /// <summary>
    /// The page: its colour with core Theme.kt's film grain already in it, one opaque tile, so whatever
    /// lies on the page or covers it (a layer, the rail, the header) is the same surface. Under half the
    /// phone's strength: across a whole monitor the same noise read as static.
    /// </summary>
    static Brush PageBrush(Color page)
    {
        const int w = 256, h = 256;
        var px = new byte[w * h * 4];
        var random = new Random(7);
        for (var i = 0; i < px.Length; i += 4)
        {
            var noise = random.Next(2) * 255;
            var a = random.Next(23) / 255.0 * 0.45;
            px[i] = (byte)(page.B + (noise - page.B) * a);
            px[i + 1] = (byte)(page.G + (noise - page.G) * a);
            px[i + 2] = (byte)(page.R + (noise - page.R) * a);
            px[i + 3] = 255;
        }
        var tile = BitmapSource.Create(w, h, 96, 96, PixelFormats.Bgra32, null, px, w * 4);
        tile.Freeze();
        var brush = new ImageBrush(tile) { TileMode = TileMode.Tile, Stretch = Stretch.None, ViewportUnits = BrushMappingMode.Absolute, Viewport = new Rect(0, 0, w, h) };
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
