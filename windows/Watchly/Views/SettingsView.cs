using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>src/screens/SettingsScreen.tsx, plus what a desktop download client needs.</summary>
public sealed class SettingsView : UserControl
{
    readonly MainWindow shell = MainWindow.Shell;
    readonly StackPanel page = new() { Margin = new Thickness(24, 8, 24, 120), MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };

    static readonly (int Kbps, string Label)[] Limits = { (0, "Unlimited"), (1024, "1 MB/s"), (5 * 1024, "5 MB/s"), (10 * 1024, "10 MB/s") };

    public SettingsView()
    {
        var (root, _, body) = Ui.Screen("Settings", "sliders");
        body.Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = page };
        Content = root;
        shell.SettingsChanged += Render;
        shell.LibraryChanged += () => { if (IsVisible) Render(); };
        IsVisibleChanged += (_, _) => { if (IsVisible) Render(); };
        Render();
    }

    AppSettings S => shell.Settings;

    void Set(AppSettings next) => shell.ChangeSettings(next);

    void Render()
    {
        page.Children.Clear();

        page.Children.Add(Group("Appearance",
            ChoiceRow("sun", "Theme", "Match Windows, or lock light or dark",
                new[] { ("system", "System"), ("light", "Light"), ("dark", "Dark") }, S.ThemeMode, v => Set(S with { ThemeMode = v }))));

        page.Children.Add(Note("hard-drive", "How storage works here",
            "A stream keeps the pieces you watch in a cache and clears it when you stop, unless you turn that off below. " +
            "Downloads go to your downloads folder, resume where they left off after a restart, and play in VLC's engine — every format and audio track is supported."));

        var genres = new WrapPanel { Margin = new Thickness(16, 0, 16, 16) };
        foreach (var g in Meta.Genres)
        {
            var on = S.Genres.Contains(g);
            var chip = Ui.Chip(g, on, () => Set(S with { Genres = on ? S.Genres.Where(x => x != g).ToList() : S.Genres.Append(g).ToList() }), style: "ChoiceChip");
            chip.Margin = new Thickness(0, 0, 8, 8);
            genres.Children.Add(chip);
        }
        page.Children.Add(Group("Your genres", new StackPanel
        {
            Children =
            {
                Row("heart", "Favourite genres", S.Genres.Count > 0 ? $"{S.Genres.Count} chosen — these lead the home page" : "Pick a few and the home page opens with them"),
                genres,
            },
        }));

        page.Children.Add(Group("Startup speed",
            Row("radio", "Add public trackers", "Bare magnet links resolve much faster than over DHT alone",
                Toggle(S.AddTrackers, v => Set(S with { AddTrackers = v })))));

        var folder = Ui.Button(Ui.Text("Change", "Label", "Accent", 13, FontWeights.SemiBold), () =>
        {
            var dialog = new OpenFolderDialog { Title = "Where should downloads go?", InitialDirectory = Directory.Exists(S.DownloadDir) ? S.DownloadDir : null };
            if (dialog.ShowDialog() == true) Set(S with { DownloadFolder = dialog.FolderName });
        });
        var openFolder = Ui.Button(Ui.Text("Open", "Label", "Muted", 13), () =>
        {
            Directory.CreateDirectory(S.DownloadDir);
            Process.Start(new ProcessStartInfo("explorer.exe", $"\"{S.DownloadDir}\"") { UseShellExecute = true });
        });
        openFolder.Margin = new Thickness(0, 0, 16, 0);
        page.Children.Add(Group("Downloads", new StackPanel
        {
            Children =
            {
                Row("folder", "Download folder", S.DownloadDir, new StackPanel { Orientation = Orientation.Horizontal, Children = { openFolder, folder } }),
                Divider(),
                ChoiceRow("download", "Download speed limit", "Leave room for everything else on the connection",
                    Limits.Select(l => (l.Kbps.ToString(), l.Label)).ToArray(), S.MaxDownloadKbps.ToString(), v => Set(S with { MaxDownloadKbps = int.Parse(v) })),
                Divider(),
                ChoiceRow("upload", "Upload speed limit", "What the swarm gets back while you download",
                    Limits.Select(l => (l.Kbps.ToString(), l.Label)).ToArray(), S.MaxUploadKbps.ToString(), v => Set(S with { MaxUploadKbps = int.Parse(v) })),
                Divider(),
                Row("upload", "Keep seeding when done", "Share a finished download until you stop it on the Downloads tab",
                    Toggle(S.SeedAfterDownload, v => Set(S with { SeedAfterDownload = v }))),
            },
        }));

        var clear = Ui.Button(Row("rotate-ccw", "Clear watch history", shell.HistoryList.Count > 0 ? $"{shell.HistoryList.Count} item(s) stored" : "Nothing stored",
            Ui.Glyph("chevron-right", 18, "Faint"), danger: shell.HistoryList.Count > 0), shell.AskClearHistory);
        clear.IsEnabled = shell.HistoryList.Count > 0;
        var clearMeta = Ui.Button(Row("layers", "Clear artwork and title cache", "Posters, plots and shelves load fresh next time",
            Ui.Glyph("chevron-right", 18, "Faint")), () =>
        {
            Meta.ClearCache();
            shell.Toast("Title cache cleared");
        });
        var (savedTorrents, savedBytes) = Engine.TorrentEngine.SavedFileListsSize();
        var clearTorrents = Ui.Button(Row("file", "Clear saved torrent info",
            savedTorrents > 0
                ? $"{savedTorrents} torrent file list{(savedTorrents == 1 ? "" : "s")} saved ({Format.Bytes(savedBytes)}) — they open without waiting on the swarm"
                : "Nothing saved — torrents you open are remembered here so they open faster next time",
            Ui.Glyph("chevron-right", 18, "Faint")), () => shell.Confirm(
                "Clear saved torrent info?",
                "Torrents will need to fetch their file lists from the swarm again the next time you open them. Watch history and downloads stay.",
                "Clear",
                () =>
                {
                    var cleared = Engine.TorrentEngine.ClearSavedFileLists();
                    shell.Toast(cleared > 0 ? "Saved torrent info cleared" : "Nothing to clear");
                    Render();
                },
                "trash-2"));
        clearTorrents.IsEnabled = savedTorrents > 0;
        page.Children.Add(Group("Storage", new StackPanel
        {
            Children =
            {
                Row("trash", "Delete files after stopping", "Keep the cache small; turn off to re-watch without re-downloading",
                    Toggle(S.RemoveFilesAfterStop, v => Set(S with { RemoveFilesAfterStop = v }))),
                Divider(),
                clearTorrents,
                Divider(),
                clearMeta,
                Divider(),
                clear,
            },
        }));

        var footer = Ui.Text("Watchly · movies and series, ready when you are. You are responsible for what you watch and download.", "Meta", "Faint", 12);
        footer.TextWrapping = TextWrapping.Wrap;
        footer.TextAlignment = TextAlignment.Center;
        footer.Margin = new Thickness(16, 8, 16, 0);
        page.Children.Add(footer);
    }

    static FrameworkElement Group(string title, UIElement content)
    {
        var heading = Ui.Text(title.ToUpperInvariant(), "Overline");
        heading.Margin = new Thickness(0, 0, 0, 12);
        var card = Ui.Card(content, "Surface", 18, new Thickness(16, 0, 16, 0));
        return new StackPanel { Margin = new Thickness(0, 0, 0, 24), Children = { heading, card } };
    }

    static FrameworkElement Note(string icon, string title, string text)
    {
        var mark = Ui.Brushed(new Border { Width = 30, Height = 30, CornerRadius = new CornerRadius(8), Child = new Grid { Children = { Ui.Glyph(icon, 15, "Accent") } }, Margin = new Thickness(0, 0, 12, 0) }, "AccentSoft");
        var body = Ui.Text(text, "Meta", "Muted", 12);
        body.TextWrapping = TextWrapping.Wrap;
        body.LineHeight = 18;
        body.Margin = new Thickness(0, 8, 0, 0);
        var card = Ui.Card(new StackPanel
        {
            Children =
            {
                new StackPanel { Orientation = Orientation.Horizontal, Children = { mark, Ui.Text(title, "Label", "Text", 14, FontWeights.SemiBold) } },
                body,
            },
        }, "Surface", 18, new Thickness(16));
        ((FrameworkElement)((StackPanel)((StackPanel)card.Child).Children[0]).Children[1]).VerticalAlignment = VerticalAlignment.Center;
        card.Margin = new Thickness(0, 0, 0, 24);
        return card;
    }

    static FrameworkElement Row(string icon, string title, string? subtitle, UIElement? trailing = null, bool danger = false)
    {
        var mark = Ui.Brushed(new Border { Width = 34, Height = 34, CornerRadius = new CornerRadius(12), Child = new Grid { Children = { Ui.Glyph(icon, 16, danger ? "Danger" : "Accent") } }, Margin = new Thickness(0, 0, 12, 0) }, danger ? "DangerSoft" : "AccentSoft");
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { Ui.Text(title, "TitleText", danger ? "Danger" : "Text") } };
        if (subtitle is not null)
        {
            var sub = Ui.Text(subtitle, "Meta");
            sub.TextWrapping = TextWrapping.Wrap;
            text.Children.Add(sub);
        }
        var dock = new DockPanel { Margin = new Thickness(0, 16, 0, 16) };
        DockPanel.SetDock(mark, Dock.Left);
        dock.Children.Add(mark);
        if (trailing is FrameworkElement t)
        {
            t.VerticalAlignment = VerticalAlignment.Center;
            t.Margin = new Thickness(12, 0, 0, 0);
            DockPanel.SetDock(t, Dock.Right);
            dock.Children.Add(t);
        }
        dock.Children.Add(text);
        return dock;
    }

    static FrameworkElement ChoiceRow(string icon, string title, string subtitle, (string Value, string Label)[] choices, string current, Action<string> pick)
    {
        var chips = new WrapPanel { Margin = new Thickness(46, 0, 0, 16) };
        foreach (var (value, label) in choices)
        {
            var chip = Ui.Chip(label, value == current, () => pick(value), style: "ChoiceChip");
            chip.Margin = new Thickness(0, 0, 8, 8);
            chips.Children.Add(chip);
        }
        return new StackPanel { Children = { Row(icon, title, subtitle), chips } };
    }

    static CheckBox Toggle(bool value, Action<bool> change)
    {
        var box = new CheckBox { Style = (Style)Application.Current.FindResource("Switch"), IsChecked = value };
        box.Click += (_, _) => change(box.IsChecked == true);
        return box;
    }

    static FrameworkElement Divider() => Ui.Brushed(new Border { Height = 1, Margin = new Thickness(46, 0, 0, 0) }, "Line");
}
