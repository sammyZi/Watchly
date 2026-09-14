using System.Windows;
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
        var light = mode == "light" || (mode == "system" && SystemLight());
        var dictionaries = Application.Current.Resources.MergedDictionaries;
        if (dictionaries.Count > 0 && IsLight == light && dictionaries[0].Source is not null) return;
        IsLight = light;
        var palette = new ResourceDictionary { Source = new Uri($"pack://application:,,,/Theme/{(light ? "Light" : "Dark")}.xaml") };
        if (dictionaries.Count == 0) dictionaries.Add(palette);
        else dictionaries[0] = palette;
        Changed?.Invoke();
    }
}
