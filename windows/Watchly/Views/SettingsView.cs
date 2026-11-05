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
    readonly StackPanel page = new() { Margin = new Thickness(24, 8, 24, 32) };

    static readonly (int Kbps, string Label)[] Limits = { (0, "Unlimited"), (1024, "1 MB/s"), (5 * 1024, "5 MB/s"), (10 * 1024, "10 MB/s") };

    public SettingsView()
    {
        var (root, _, body) = Ui.Screen("Settings");
        scroller = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = page };
        body.Content = scroller;
        Content = root;
        var (box, input, _) = Ui.SearchField("Search settings", 44, 10);
        box.Margin = new Thickness(0, 0, 0, 14);
        searchBox = box;
        search = input;
        search.TextChanged += (_, _) => RenderList();
        shell.SettingsChanged += Render;
        shell.LibraryChanged += () => { if (IsVisible) Render(); };
        IsVisibleChanged += (_, _) => { if (IsVisible) Render(); };
        Updates.Changed += () => Dispatcher.BeginInvoke(() => { if (IsVisible) Render(); });
        Render();
    }

    readonly ScrollViewer scroller;
    readonly Border searchBox;
    readonly TextBox search;
    /// <summary>The cards side by side, as many as the window has room for.</summary>
    readonly ColumnWrap list = new() { MinColumn = 440 };
    /// <summary>The page open, by its title; null is the list of them.</summary>
    string? current;

    AppSettings S => shell.Settings;

    void Set(AppSettings next) => shell.ChangeSettings(next);

    void Go(string? to)
    {
        current = to;
        Render();
        scroller.ScrollToTop();
    }

    /// <summary>One line of the settings list; Words are what else it answers to in the search.</summary>
    sealed record Entry(string Icon, string Title, string Hint, string Words, Action Open, string Tint = "Text")
    {
        public bool Matches(string query) => new[] { Title, Hint, Words }.Any(s => s.Contains(query, StringComparison.OrdinalIgnoreCase));
    }

    List<List<Entry>> Entries()
    {
        var groups = new List<List<Entry>>();
        if (Updates.Found is { } r)
            groups.Add(new()
            {
                Updates.Progress is { } p
                    ? new Entry("x", "Cancel the update", $"Downloading version {r.Version}  ·  {Math.Round(p * 100)}%", "", Updates.Cancel, "Accent")
                    : new Entry("download", "Update available", $"Version {r.Version}  ·  {Format.Bytes(r.Size)}", "new version upgrade", () => shell.DownloadUpdate(r), "Accent"),
            });
        groups.Add(new()
        {
            new("user", "Account", "Profile, PIN and Stremio account", "lock sign in", () => Go("Account")),
            new("users", "Switch profile", "Change to a different profile", "", shell.ShowProfiles),
        });
        groups.Add(new() { new("palette", "Appearance", "Theme and home screen genres", "light dark", () => Go("Appearance")) });
        groups.Add(new()
        {
            new("radio", "Streaming", "Trackers and how the cache works", "magnet storage", () => Go("Streaming")),
            new("play", "Playback", "Skip intros", "player", () => Go("Playback")),
            new("download", "Downloads", "Folder, speed limits and seeding", "upload", () => Go("Downloads")),
        });
        groups.Add(new()
        {
            new("link", "Integrations", "Simkl and MDBList", "tracking watched sync", () => Go("Integrations")),
            new("key", "Services", "SubDL and TorBox keys", "subtitles api", () => Go("Services")),
        });
        groups.Add(new()
        {
            new("hard-drive", "Storage", "Cache, history and saved torrent info", "clear delete", () => Go("Storage")),
            new("refresh-cw", "Check for updates", $"You have version {Updates.Version}", "new version upgrade", () => _ = Updates.CheckNow(m => shell.Toast(m))),
        });
        return groups;
    }

    /// <summary>The settings as cards of rows; searching runs them together into one card of what matches.</summary>
    void RenderList()
    {
        list.Children.Clear();
        var query = search.Text.Trim();
        var groups = Entries();
        var shown = query.Length == 0 ? groups : new List<List<Entry>> { groups.SelectMany(g => g).Where(e => e.Matches(query)).ToList() }.Where(g => g.Count > 0).ToList();
        if (shown.Count == 0) list.Children.Add(Ui.Text("No settings match", "Meta", "Faint", 13));
        foreach (var group in shown)
        {
            var rows = new StackPanel();
            foreach (var e in group) rows.Children.Add(EntryRow(e));
            var card = Ui.Card(rows, "Surface", 18, new Thickness(0, 4, 0, 4));
            card.Margin = new Thickness(0, 0, 12, 12);
            list.Children.Add(card);
        }
    }

    static FrameworkElement EntryRow(Entry e)
    {
        var icon = Ui.Brushed(new Border { Width = 38, Height = 38, CornerRadius = new CornerRadius(10), Child = Ui.Glyph(e.Icon, 18, e.Tint) }, "Tint");
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(14, 0, 0, 0), Children = { Ui.Text(e.Title, "Label", "Text", 15, FontWeights.SemiBold), Ui.Text(e.Hint, "Meta", "Faint", 13) } };
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(14, 9, 14, 9), Children = { icon, words } };
        var b = Ui.Button(row, e.Open);
        b.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        b.HorizontalAlignment = HorizontalAlignment.Stretch;
        return b;
    }

    void Render()
    {
        page.Children.Clear();
        if (current is null)
        {
            page.Children.Add(searchBox);
            page.Children.Add(list);
            RenderList();
            var version = Ui.Text($"Version {Updates.Version}", "Meta", "Faint", 12);
            version.Margin = new Thickness(4, 4, 0, 0);
            page.Children.Add(version);
            return;
        }
        var back = Ui.Round("arrow-left", () => Go(null), "Text", "Tint", 40, 20, "Back");
        var heading = Ui.Text(current, "TitleText", "Text", 22, FontWeights.Bold);
        heading.Margin = new Thickness(12, 0, 0, 0);
        heading.VerticalAlignment = VerticalAlignment.Center;
        page.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 16), Children = { back, heading } });

        switch (current)
        {
            case "Account":
                if (shell.Who is { } me) page.Children.Add(ProfileGroup(me));
                page.Children.Add(StremioGroup());
                break;
            case "Appearance": Appearance(); break;
            case "Streaming":
                page.Children.Add(Note("How storage works here",
                    "A stream keeps the pieces you watch in a cache and clears it when you stop, unless you turn that off under Storage. " +
                    "Downloads go to your downloads folder, resume where they left off after a restart, and play in VLC's engine — every format and audio track is supported."));
                page.Children.Add(Group("Startup speed",
                    Row("Add public trackers", "Bare magnet links resolve much faster than over DHT alone",
                        Toggle(S.AddTrackers, v => Set(S with { AddTrackers = v })))));
                break;
            case "Playback":
                page.Children.Add(Group("Playback",
                    Row("Skip intros automatically", "Where an episode's intro is known, jump past it instead of showing the Skip intro button. Once per episode — seek back to watch it.",
                        Toggle(S.AutoSkipIntro, v => Set(S with { AutoSkipIntro = v })))));
                break;
            case "Downloads": DownloadsPage(); break;
            case "Integrations": Integrations(); break;
            case "Services":
                page.Children.Add(SubdlGroup());
                page.Children.Add(TorBoxGroup());
                break;
            case "Storage": StoragePage(); break;
        }
    }

    /// <summary>Waiting on a tracking account's approval: the code, and how to stop waiting.</summary>
    readonly Dictionary<Tracker, (TrackerCode Code, CancellationTokenSource Stop)> connecting = new();

    void Integrations()
    {
        var hint = Ui.Text("What you finish here is marked watched on your accounts, and what they have watched shows here. You sign in on each service's own page, so your password never comes to Watchly.", "Meta", "Faint", 13);
        hint.TextWrapping = TextWrapping.Wrap;
        hint.Margin = new Thickness(0, 0, 0, 12);
        page.Children.Add(hint);
        foreach (var t in Trackers.All) page.Children.Add(TrackerRow(t));
    }

    async void Connect(Tracker t)
    {
        using var stop = new CancellationTokenSource();
        try
        {
            var code = await t.Start();
            connecting[t] = (code, stop);
            Render();
            Ui.Open(code.Link);
            if (!await t.Await(code, stop.Token)) shell.Toast("That didn't go through — connect again for a new code", true);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e) when (e is System.Net.Http.HttpRequestException or TrackerRefused or System.Text.Json.JsonException)
        {
            shell.Toast($"Couldn't reach {t.Name}", true);
        }
        finally
        {
            connecting.Remove(t);
            Render();
        }
    }

    /// <summary>A tracking account: its logo, and Connect, which opens its own page with the code filled in.</summary>
    FrameworkElement TrackerRow(Tracker t)
    {
        var waiting = connecting.TryGetValue(t, out var pending);
        // By its domain, from Google's favicon service, as the phone's and the TV's.
        var logo = new Border
        {
            Width = 38, Height = 38, CornerRadius = new CornerRadius(10),
            Background = new ImageBrush(new System.Windows.Media.Imaging.BitmapImage(new Uri($"https://www.google.com/s2/favicons?sz=64&domain={t.Site}"))) { Stretch = Stretch.Uniform },
            Margin = new Thickness(0, 0, 14, 0),
        };
        DockPanel.SetDock(logo, Dock.Left);
        UIElement? action = !t.Available ? null
            : t.Connected ? Ui.Action("Disconnect", "x", t.Disconnect)
            : waiting ? Ui.Action("Cancel", "x", () => pending.Stop.Cancel())
            : Ui.Action("Connect", "link", () => Connect(t), primary: true);
        var row = Row(t.Name, !t.Available ? "Not set up in this build" : t.Connected ? "Connected" : waiting ? "Waiting for your approval…" : "Not connected", action);
        var box = new StackPanel { Children = { new DockPanel { Children = { logo, row } } } };
        if (waiting)
        {
            var open = Ui.Button(Ui.Text("Open the page again", "Label", "Accent", 13, FontWeights.SemiBold), () => Ui.Open(pending.Code.Link));
            open.HorizontalAlignment = HorizontalAlignment.Left;
            var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(20, 0, 0, 0), Children = { Ui.Text(pending.Code.UserCode, "TitleText", "Text", 28, FontWeights.Bold), open } };
            box.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 12), Children = { Qr(pending.Code.Link), words } });
        }
        return box;
    }

    void Appearance()
    {
        page.Children.Add(Group("Appearance",
            ChoiceRow("Theme", "Match Windows, or lock light or dark",
                new[] { ("system", "System"), ("light", "Light"), ("dark", "Dark") }, S.ThemeMode, v => Set(S with { ThemeMode = v }))));

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
    }

    void DownloadsPage()
    {
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
    }

    void StoragePage()
    {
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
    /// Sign in opens Stremio's link page with the code filled in; while it waits, the code is here with its
    /// QR, to approve on a phone instead, a copy button, and Open in browser to get back to the page.
    /// </summary>
    FrameworkElement StremioGroup()
    {
        var box = new StackPanel();
        if (Stremio.Connected)
        {
            var import = Ui.Button(Ui.Text("Import addons", "Label", "Accent", 13, FontWeights.SemiBold), async () =>
            {
                shell.Toast("Bringing your addons…");
                try
                {
                    var n = await Stremio.ImportAddons();
                    shell.Toast(n == 0 ? "Every addon is here already" : $"Added {n} addon{(n == 1 ? "" : "s")} — see the Addons tab");
                }
                catch (Exception e)
                {
                    shell.Toast(e.Message, true);
                }
            });
            // The other way: the account's list made this PC's, for every device that signs in after.
            var send = Ui.Button(Ui.Text("Send my addons to Stremio", "Label", "Accent", 13, FontWeights.SemiBold), async () =>
            {
                shell.Toast("Sending your addons…");
                try
                {
                    var n = await Stremio.PushAddons();
                    shell.Toast($"Your Stremio account now has this PC's {n} addon{(n == 1 ? "" : "s")}");
                }
                catch (Exception e)
                {
                    shell.Toast(e.Message, true);
                }
            });
            send.Margin = new Thickness(20, 0, 0, 0);
            var signOut = Ui.Button(Ui.Text("Sign out", "Label", "Muted", 13), () => { Stremio.Disconnect(); Render(); });
            signOut.Margin = new Thickness(20, 0, 0, 0);
            box.Children.Add(Row($"Signed in{(Stremio.Email is { } email ? $" as {email}" : "")}",
                "Your Stremio library and where you stopped are here, My List is your library there, and what you finish is marked watched. Addons you add or remove here change on your account too.",
                new StackPanel { Orientation = Orientation.Horizontal, Children = { import, send, signOut } }));
        }
        else if (shell.StremioPending is { } code)
        {
            box.Children.Add(Ui.Text($"Sign in and approve on the page that opened — or scan this with your phone, or open {code.Link.Replace("https://", "")} on another device.", "Meta", "Faint", 12));
            var copy = Ui.Button(Ui.Glyph("copy", 18, "Muted"), () =>
            {
                try
                {
                    Clipboard.SetText(code.Code);
                    shell.Toast("Code copied");
                }
                catch (System.Runtime.InteropServices.ExternalException)
                {
                    shell.Toast("Couldn't reach the clipboard — try again", true);
                }
            }, tooltip: "Copy the code");
            copy.Margin = new Thickness(10, 0, 0, 0);
            var open = Ui.Button(Ui.Text("Open in browser", "Label", "Accent", 13, FontWeights.SemiBold), () => Ui.Open(code.Link));
            var cancel = Ui.Button(Ui.Text("Cancel", "Label", "Muted", 13), () => shell.StremioCode?.Cancel());
            cancel.Margin = new Thickness(16, 0, 0, 0);
            var words = new StackPanel
            {
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(20, 0, 0, 0),
                Children =
                {
                    new StackPanel { Orientation = Orientation.Horizontal, Children = { Ui.Text(code.Code, "TitleText", "Text", 28, FontWeights.Bold), copy } },
                    new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0), Children = { open, cancel } },
                },
            };
            box.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0), Children = { Qr(code.Link), words } });
        }
        else
        {
            box.Children.Add(Row(shell.Who?.IsGuest == true ? "Sign in to Stremio" : "Bring your Stremio library",
                shell.Who?.IsGuest == true
                    ? "Your Stremio library, Continue Watching and addons here. As Guest it's forgotten when the app closes."
                    : "Your Stremio library, Continue Watching and addons here, kept in step both ways. Sign in on Stremio's own page — your password never comes to Watchly.",
                Ui.Button(Ui.Text("Sign in to Stremio", "Label", "Accent", 13, FontWeights.SemiBold), () => _ = shell.ConnectStremio())));
        }
        return Group("Stremio account", box);
    }

    /// <summary>A link as a QR code, black on white whatever the theme, so any phone camera reads it.</summary>
    static FrameworkElement Qr(string link)
    {
        using var generator = new QRCoder.QRCodeGenerator();
        using var data = generator.CreateQrCode(link, QRCoder.QRCodeGenerator.ECCLevel.M);
        var png = new QRCoder.PngByteQRCode(data).GetGraphic(8);
        var image = new System.Windows.Media.Imaging.BitmapImage();
        image.BeginInit();
        image.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
        image.StreamSource = new MemoryStream(png);
        image.EndInit();
        image.Freeze();
        var picture = new Image { Source = image, Width = 150, Height = 150, SnapsToDevicePixels = true };
        // Square modules stay square: smoothing blurs the edges a camera looks for.
        RenderOptions.SetBitmapScalingMode(picture, BitmapScalingMode.NearestNeighbor);
        return new Border { Background = Brushes.White, CornerRadius = new CornerRadius(8), Padding = new Thickness(6), Child = picture };
    }

    /// <summary>A TorBox key: cached releases play from its servers, anything else from the swarm as before.</summary>
    FrameworkElement TorBoxGroup()
    {
        var saved = TorBox.On;
        var box = new PasswordBox { Background = Brushes.Transparent, BorderThickness = new Thickness(0), FontSize = 14, Width = 260, VerticalContentAlignment = VerticalAlignment.Center };
        box.SetResourceReference(ForegroundProperty, "Text");
        box.SetResourceReference(PasswordBox.CaretBrushProperty, "Accent");
        void Save()
        {
            if (box.Password.Trim().Length == 0) { shell.Toast("Paste your TorBox key first", true); return; }
            TorBox.SetKey(box.Password);
            shell.Toast("TorBox key saved");
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
                TorBox.SetKey("");
                shell.Toast("TorBox key removed");
                Render();
            });
            remove.Margin = new Thickness(16, 0, 0, 0);
            controls.Children.Add(remove);
        }
        var panel = new StackPanel();
        if (saved)
        {
            // The account it opens, or why it doesn't.
            var account = Ui.Text("Checking the key…", "Meta", "Muted", 12);
            account.Margin = new Thickness(0, 0, 0, 10);
            panel.Children.Add(account);
            _ = TorBox.Account().ContinueWith(t => Dispatcher.BeginInvoke(() =>
            {
                account.Text = t.IsCompletedSuccessfully ? t.Result : $"Not working: {t.Exception?.GetBaseException().Message}";
                account.SetResourceReference(TextBlock.ForegroundProperty, t.IsCompletedSuccessfully ? "Muted" : "Danger");
            }));
        }
        panel.Children.Add(Row(saved ? "API key saved" : "TorBox API key",
            saved ? "Releases TorBox has cached say Instant and play from its servers — no peers to wait for. Anything else streams from the swarm as before."
                  : "With a torbox.app API key, releases TorBox has cached play from its servers instead of from peers. Stored encrypted for this Windows account."));
        panel.Children.Add(controls);
        var get = Ui.Button(Ui.Text("Find your key at torbox.app", "Meta", "Accent", 12), () =>
            Process.Start(new ProcessStartInfo("https://torbox.app/settings") { UseShellExecute = true }));
        get.HorizontalAlignment = HorizontalAlignment.Left;
        get.Margin = new Thickness(0, 10, 0, 0);
        panel.Children.Add(get);
        return Group("TorBox", panel);
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
