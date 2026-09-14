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
