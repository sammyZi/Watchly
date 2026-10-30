using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>src/screens/SettingsScreen.tsx, plus what a desktop download client needs.</summary>
public sealed class SettingsView : UserControl
{
    readonly MainWindow shell = MainWindow.Shell;
    readonly StackPanel page = new() { Margin = new Thickness(24, 8, 24, 32), MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };

    static readonly (int Kbps, string Label)[] Limits = { (0, "Unlimited"), (1024, "1 MB/s"), (5 * 1024, "5 MB/s"), (10 * 1024, "10 MB/s") };

    public SettingsView()
    {
        var (root, _, body) = Ui.Screen("Settings");
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

        if (shell.Who is { } me) page.Children.Add(ProfileGroup(me));
        if (Simkl.Available) page.Children.Add(SimklGroup());

        page.Children.Add(Group("Appearance",
            ChoiceRow("Theme", "Match Windows, or lock light or dark",
                new[] { ("system", "System"), ("light", "Light"), ("dark", "Dark") }, S.ThemeMode, v => Set(S with { ThemeMode = v }))));

        page.Children.Add(Note("How storage works here",
            "A stream keeps the pieces you watch in a cache and clears it when you stop, unless you turn that off below. " +
            "Downloads go to your downloads folder, resume where they left off after a restart, and play in VLC's engine — every format and audio track is supported."));

        var genres = new WrapPanel { Margin = new Thickness(0, 0, 0, 8) };
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
                Row("Favourite genres", S.Genres.Count > 0 ? $"{S.Genres.Count} chosen — these lead the home page" : "Pick a few and the home page opens with them"),
                genres,
            },
        }));

        page.Children.Add(Group("Startup speed",
            Row("Add public trackers", "Bare magnet links resolve much faster than over DHT alone",
                Toggle(S.AddTrackers, v => Set(S with { AddTrackers = v })))));

        page.Children.Add(Group("Playback",
            Row("Skip intros automatically", "Where an episode's intro is known, jump past it instead of showing the Skip intro button. Once per episode — seek back to watch it.",
                Toggle(S.AutoSkipIntro, v => Set(S with { AutoSkipIntro = v })))));

        page.Children.Add(SubdlGroup());

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
                Row("Download folder", S.DownloadDir, new StackPanel { Orientation = Orientation.Horizontal, Children = { openFolder, folder } }),
                Divider(),
                ChoiceRow("Download speed limit", "Leave room for everything else on the connection",
                    Limits.Select(l => (l.Kbps.ToString(), l.Label)).ToArray(), S.MaxDownloadKbps.ToString(), v => Set(S with { MaxDownloadKbps = int.Parse(v) })),
                Divider(),
                ChoiceRow("Upload speed limit", "What the swarm gets back while you download",
                    Limits.Select(l => (l.Kbps.ToString(), l.Label)).ToArray(), S.MaxUploadKbps.ToString(), v => Set(S with { MaxUploadKbps = int.Parse(v) })),
                Divider(),
                Row("Keep seeding when done", "Share a finished download until you stop it on the Downloads tab",
                    Toggle(S.SeedAfterDownload, v => Set(S with { SeedAfterDownload = v }))),
            },
        }));

        var clear = Ui.Button(Row("Clear watch history", shell.HistoryList.Count > 0 ? $"{shell.HistoryList.Count} item(s) stored" : "Nothing stored",
            Ui.Glyph("chevron-right", 18, "Faint"), danger: shell.HistoryList.Count > 0), shell.AskClearHistory);
        clear.IsEnabled = shell.HistoryList.Count > 0;
        var clearMeta = Ui.Button(Row("Clear artwork and title cache", "Posters, plots and shelves load fresh next time",
            Ui.Glyph("chevron-right", 18, "Faint")), () =>
        {
            Meta.ClearCache();
            shell.Toast("Title cache cleared");
        });
        var (savedTorrents, savedBytes) = Engine.TorrentEngine.SavedFileListsSize();
        var clearTorrents = Ui.Button(Row("Clear saved torrent info",
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
                Row("Delete files after stopping", "Keep the cache small; turn off to re-watch without re-downloading",
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
        footer.Margin = new Thickness(0, 8, 0, 0);
        page.Children.Add(footer);
    }

    /// <summary>A section as the phone sets one (mobile Pages.kt Section): a bold title, then its rows — no card.</summary>
    /// <summary>The phone's and TV's SubDL key: the viewer's own, encrypted on this PC (Secrets).</summary>
    /// <summary>
    /// Connect opens Simkl's page with the code filled in; while it waits, the code is here to type on
    /// another device, with a copy button, and Open Simkl to get back to the page.
    /// </summary>
    FrameworkElement SimklGroup()
    {
        var box = new StackPanel();
        if (Simkl.Connected)
        {
            box.Children.Add(Row("Connected", "Films and episodes you watch to the end are marked watched on Simkl, and Continue Watching follows you to your phone and TV.",
                Ui.Button(Ui.Text("Disconnect", "Label", "Muted", 13), () => { Simkl.Disconnect(); Render(); })));
        }
        else if (shell.SimklPending is { } code)
        {
            box.Children.Add(Ui.Text($"Approve Watchly in the browser that just opened. Signing in? Simkl emails that account a link — open it and confirm, then press Allow. Or enter this code at {code.Uri.Replace("https://", "")} on another device.", "Meta", "Faint", 12));
            var copy = Ui.Button(Ui.Glyph("copy", 18, "Muted"), () =>
            {
                Clipboard.SetText(code.UserCode);
                shell.Toast("Code copied");
            }, tooltip: "Copy the code");
            copy.Margin = new Thickness(10, 0, 0, 0);
            var open = Ui.Button(Ui.Text("Open Simkl", "Label", "Accent", 13, FontWeights.SemiBold), () =>
                Process.Start(new ProcessStartInfo(code.UriComplete) { UseShellExecute = true }));
            open.Margin = new Thickness(20, 0, 0, 0);
            var cancel = Ui.Button(Ui.Text("Cancel", "Label", "Muted", 13), () => shell.SimklCode?.Cancel());
            cancel.Margin = new Thickness(16, 0, 0, 0);
            box.Children.Add(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Margin = new Thickness(0, 10, 0, 0),
                Children = { Ui.Text(code.UserCode, "TitleText", "Text", 28, FontWeights.Bold), copy, open, cancel },
            });
        }
        else
        {
            box.Children.Add(Row("Sync with Simkl", "Films and episodes you finish get marked watched on your Simkl account, and your Simkl history shows in the Library.",
                Ui.Button(Ui.Text("Connect Simkl", "Label", "Accent", 13, FontWeights.SemiBold), () => _ = shell.ConnectSimkl())));
        }
        return Group("Simkl", box);
    }

    FrameworkElement SubdlGroup()
    {
        var saved = Secrets.Subdl.Length > 0;
        var box = new PasswordBox { Background = Brushes.Transparent, BorderThickness = new Thickness(0), FontSize = 14, Width = 260, VerticalContentAlignment = VerticalAlignment.Center };
        box.SetResourceReference(ForegroundProperty, "Text");
        box.SetResourceReference(PasswordBox.CaretBrushProperty, "Accent");
        void Save()
        {
            if (box.Password.Trim().Length == 0) { shell.Toast("Paste your SubDL key first", true); return; }
            Secrets.SetSubdl(box.Password);
            shell.Toast("SubDL key saved");
            Render();
        }
        box.KeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Enter) Save(); };
        var save = Ui.Button(Ui.Text("Save", "Label", "Accent", 13, FontWeights.SemiBold), Save);
        save.Margin = new Thickness(14, 0, 0, 0);
        var controls = new StackPanel { Orientation = Orientation.Horizontal, Children = { Ui.Card(box, "SurfaceAlt", 6, new Thickness(10, 6, 10, 6)), save } };
        if (saved)
        {
            var remove = Ui.Button(Ui.Text("Remove", "Label", "Muted", 13), () =>
            {
                Secrets.SetSubdl("");
                shell.Toast("SubDL key removed");
                Render();
            });
            remove.Margin = new Thickness(16, 0, 0, 0);
            controls.Children.Add(remove);
        }
        var get = Ui.Button(Ui.Text("Get a free key at subdl.com", "Meta", "Accent", 12), () =>
            Process.Start(new ProcessStartInfo("https://subdl.com/panel/api") { UseShellExecute = true }));
        get.HorizontalAlignment = HorizontalAlignment.Left;
        get.Margin = new Thickness(0, 10, 0, 0);
        return Group("Subtitles", new StackPanel
        {
            Children =
            {
                Row("SubDL key",
                    saved ? "Saved, encrypted on this PC — Find subtitles searches SubDL too. Enter another to replace it."
                          : "A free key from subdl.com adds its subtitles to Find subtitles. It stays encrypted on this PC.",
                    controls),
                get,
            },
        });
    }

    /// <summary>The TV's Profile and Profile PIN sections: who is watching, switch, and a lock.</summary>
    FrameworkElement ProfileGroup(Profile me)
    {
        var face = ProfilesView.Avatar(me, 40);
        face.Margin = new Thickness(0, 0, 14, 0);
        var who = Row($"Watching as {me.Name}",
            me.IsGuest ? "Guest keeps nothing — history and My List go when the app closes." : "Library, My List and resume points belong to this profile.",
            Ui.Button(Ui.Text("Switch", "Label", "Accent", 13, FontWeights.SemiBold), shell.ShowProfiles));
        var rows = new StackPanel { Children = { new DockPanel { Children = { face, who } } } };
        DockPanel.SetDock(face, Dock.Left);
        if (!me.IsGuest)
        {
            var box = ProfilesView.PinBox();
            box.FontSize = 18;
            box.Width = 90;
            var set = Ui.Button(Ui.Text("Set PIN", "Label", "Accent", 13, FontWeights.SemiBold), () =>
            {
                if (box.Password.Length != 4) { shell.Toast("A PIN is four digits", true); return; }
                shell.ProfileEdited(Profiles.SetPin(me.Id, box.Password));
                shell.Toast($"{me.Name} now asks for a PIN");
                Render();
            });
            set.Margin = new Thickness(14, 0, 0, 0);
            var controls = new StackPanel { Orientation = Orientation.Horizontal, Children = { Ui.Card(box, "SurfaceAlt", 6, new Thickness(8, 2, 8, 2)), set } };
            if (me.Pin is not null)
            {
                var remove = Ui.Button(Ui.Text("Remove", "Label", "Muted", 13), () =>
                {
                    shell.ProfileEdited(Profiles.SetPin(me.Id, ""));
                    Render();
                });
                remove.Margin = new Thickness(16, 0, 0, 0);
                controls.Children.Add(remove);
            }
            rows.Children.Add(Divider());
            rows.Children.Add(Row("Profile PIN",
                me.Pin is not null ? "Asked for before this profile opens. Remove it to unlock." : "Four digits, asked for before this profile opens. A lock for a shared PC — not a password.",
                controls));
        }
        return Group("Profile", rows);
    }

    static FrameworkElement Group(string title, UIElement content)
    {
        var heading = Ui.Text(title, "TitleText", "Text", 18, FontWeights.Bold);
        heading.Margin = new Thickness(0, 0, 0, 4);
        return new StackPanel { Margin = new Thickness(0, 0, 0, 28), Children = { heading, content } };
    }

    static FrameworkElement Note(string title, string text)
    {
        var body = Ui.Text(text, "Meta", "Faint", 13);
        body.TextWrapping = TextWrapping.Wrap;
        body.LineHeight = 19;
        body.Margin = new Thickness(0, 6, 0, 0);
        var card = Ui.Card(new StackPanel { Children = { Ui.Text(title, "Label", "Text", 15, FontWeights.SemiBold), body } }, "Surface", 10, new Thickness(16, 14, 16, 14));
        card.Margin = new Thickness(0, 0, 0, 28);
        return card;
    }

    static FrameworkElement Row(string title, string? subtitle, UIElement? trailing = null, bool danger = false)
    {
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { Ui.Text(title, "Label", danger ? "Danger" : "Text", 15, FontWeights.SemiBold) } };
        if (subtitle is not null)
        {
            var sub = Ui.Text(subtitle, "Meta", "Faint", 13);
            sub.TextWrapping = TextWrapping.Wrap;
            sub.Margin = new Thickness(0, 3, 0, 0);
            text.Children.Add(sub);
        }
        var dock = new DockPanel { Margin = new Thickness(0, 12, 0, 12) };
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

    static FrameworkElement ChoiceRow(string title, string subtitle, (string Value, string Label)[] choices, string current, Action<string> pick)
    {
        var chips = new WrapPanel { Margin = new Thickness(0, 0, 0, 8) };
        foreach (var (value, label) in choices)
        {
            var chip = Ui.Chip(label, value == current, () => pick(value), style: "ChoiceChip");
            chip.Margin = new Thickness(0, 0, 8, 8);
            chips.Children.Add(chip);
        }
        return new StackPanel { Children = { Row(title, subtitle), chips } };
    }

    static CheckBox Toggle(bool value, Action<bool> change)
    {
        var box = new CheckBox { Style = (Style)Application.Current.FindResource("Switch"), IsChecked = value };
        box.Click += (_, _) => change(box.IsChecked == true);
        return box;
    }

    static FrameworkElement Divider() => Ui.Brushed(new Border { Height = 1 }, "Line");
}
