using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using MonoTorrent;
using Watchly.Controls;
using Watchly.Core;
using Watchly.Engine;
using Watchly.Views;

namespace Watchly;

/// <summary>Commands any card anywhere can raise; the window handles them.</summary>
public static class Cmd
{
    public static readonly RoutedUICommand OpenTitle = new("Open title", nameof(OpenTitle), typeof(Cmd));
    public static readonly RoutedUICommand OpenShelf = new("Open shelf", nameof(OpenShelf), typeof(Cmd));
    public static readonly RoutedUICommand PlayResume = new("Continue watching", nameof(PlayResume), typeof(Cmd));
}

public sealed record OpenOptions
{
    /// <summary>Pick the file without asking: an SxxEyy tag, or "best" for the feature.</summary>
    public string? Auto { get; init; }
    /// <summary>Ignore the stored resume point.</summary>
    public bool KeepProgress { get; init; }
    public string? Imdb { get; init; }
    public string? Kind { get; init; }
    public string? Poster { get; init; }
    public string? TitleName { get; init; }
    public int? Season { get; init; }
}

/// <summary>Everything the player needs for one file — a torrent file or one already on disk.</summary>
public sealed record PlaybackRequest
{
    public required string Title { get; init; }
    public string? MetaTitle { get; init; }
    public string? Imdb { get; init; }
    public string? Kind { get; init; }
    public double ResumeAt { get; init; }
    public Torrent? Torrent { get; init; }
    public int FileIndex { get; init; } = -1;
    public string? HistoryUri { get; init; }
    public List<SavedSub> SavedSubs { get; init; } = new();
    public string? LocalPath { get; init; }
    public (string Name, Func<Task> Go)? Next { get; init; }
}

/// <summary>
/// The shell — what src/App.tsx's ShellApp is on the phone: tabs, the pages over
/// them, and the flow from a picked release to a playing file or a download.
/// </summary>
public partial class MainWindow : Window
{
    public static MainWindow Shell { get; private set; } = null!;

    public AppSettings Settings { get; private set; } = AppSettings.Load();
    public List<HistoryItem> HistoryList { get; private set; } = History.Load();
    public List<SavedTitle> SavedList { get; private set; } = Saved.Load();
    public Dictionary<string, string> Links { get; private set; } = EpisodeLinks.Load();

    /// <summary>History, saved lists or episode links changed. Raised on the UI thread.</summary>
    public event Action? LibraryChanged;
    public event Action? SettingsChanged;
    public event Action? OpeningChanged;

    static readonly (string Key, string Label, string Icon)[] TabList =
    {
        ("home", "Home", "home"),
        ("search", "Search", "search"),
        ("history", "Library", "clock"),
        ("downloads", "Downloads", "download"),
        ("settings", "Settings", "sliders"),
    };

    readonly Dictionary<string, FrameworkElement> tabViews = new();
    readonly Dictionary<string, (TextBlock Label, Icon Icon)> tabParts = new();
    string tab = "home";

    public MainWindow(string? openOnStart)
    {
        Shell = this;
        InitializeComponent();
        BuildTabBar();
        ShowTab("home");

        History.Changed += items => Dispatcher.BeginInvoke(() => { HistoryList = items; LibraryChanged?.Invoke(); });
        Saved.Changed += items => Dispatcher.BeginInvoke(() => { SavedList = items; LibraryChanged?.Invoke(); });
        Net.OnlineChanged += () => Dispatcher.BeginInvoke(() => Offline.Visibility = Net.IsOnline ? Visibility.Collapsed : Visibility.Visible);
        Downloads.Instance.Failed += message => Dispatcher.BeginInvoke(() => Toast(message, true));

        Loaded += (_, _) =>
        {
            Downloads.Instance.Restore();
            _ = BackfillPosters();
            if (openOnStart is not null) OpenIncoming(openOnStart);
        };
        SourceInitialized += (_, _) => Theme.TitleBar(this);
        Theme.Changed += () => Theme.TitleBar(this);
        Drop += OnDropSource;
        PreviewKeyDown += OnKey;
        MouseDown += (_, e) =>
        {
            if (e.ChangedButton == MouseButton.XButton1) { GoBack(); e.Handled = true; }
        };
    }

    // ── Tabs ────────────────────────────────────────────────────────────────

    void BuildTabBar()
    {
        foreach (var (key, label, icon) in TabList)
        {
            var glyph = new Icon { Kind = icon, Size = 22, HorizontalAlignment = HorizontalAlignment.Center };
            var text = new TextBlock { Text = label, FontSize = 10, Margin = new Thickness(0, 3, 0, 0), HorizontalAlignment = HorizontalAlignment.Center };
            var button = new Button
            {
                Style = (Style)FindResource("Bare"),
                Content = new StackPanel { Children = { glyph, text }, VerticalAlignment = VerticalAlignment.Center },
                ToolTip = label,
            };
            button.Click += (_, _) => ShowTab(key);
            TabButtons.Children.Add(button);
            tabParts[key] = (text, glyph);
        }
    }

    public void ShowTab(string key)
    {
        tab = key;
        if (!tabViews.TryGetValue(key, out var view))
        {
            view = key switch
            {
                "home" => new HomeView(),
                "search" => new SearchView(),
                "history" => new LibraryView(),
                "downloads" => new DownloadsView(),
                _ => new SettingsView(),
            };
            tabViews[key] = view;
            Tabs.Children.Add(view);
        }
        foreach (var (k, v) in tabViews) v.Visibility = k == key ? Visibility.Visible : Visibility.Collapsed;
        foreach (var (k, parts) in tabParts)
        {
            var on = k == key;
            parts.Label.SetResourceReference(TextBlock.ForegroundProperty, on ? "Accent" : "Faint");
            parts.Label.FontWeight = on ? FontWeights.Medium : FontWeights.Normal;
            parts.Icon.SetResourceReference(Controls.Icon.ForegroundProperty, on ? "Accent" : "Faint");
        }
    }

    // ── Layers over the tabs ────────────────────────────────────────────────

    void PushLayer(FrameworkElement page)
    {
        page.SetResourceReference(BackgroundProperty, "Bg");
        Layers.Children.Add(page);
        TabDock.Visibility = Visibility.Collapsed;
        ShowTopLayerOnly();
    }

    public void CloseLayer(FrameworkElement page)
    {
        Layers.Children.Remove(page);
        (page as IDisposable)?.Dispose();
        if (Layers.Children.Count == 0) TabDock.Visibility = Visibility.Visible;
        ShowTopLayerOnly();
    }

    /// <summary>
    /// Pages are opaque, so only the top one needs drawing. The home tab and every
    /// page below used to render underneath it (25 shelves of posters behind a title
    /// page). Hidden, not collapsed: they keep their layout and scroll position.
    /// </summary>
    void ShowTopLayerOnly()
    {
        var count = Layers.Children.Count;
        for (var i = 0; i < count; i++) Layers.Children[i].Visibility = i == count - 1 ? Visibility.Visible : Visibility.Hidden;
        if (PlayerHost.Content is null || PlayerHost.Visibility != Visibility.Visible)
            Tabs.Visibility = count > 0 ? Visibility.Hidden : Visibility.Visible;
    }

    /// <summary>The title page on top, if any — up-next and saved art read from it.</summary>
    public Title? CurrentDetail => Layers.Children.OfType<DetailView>().LastOrDefault()?.Current;

    void OnOpenTitle(object sender, ExecutedRoutedEventArgs e)
    {
        if (e.Parameter is Title title) OpenTitle(title);
    }

    void OnOpenShelf(object sender, ExecutedRoutedEventArgs e)
    {
        if (e.Parameter is Shelf shelf) OpenShelfPage(shelf);
    }

    public void OpenShelfPage(Shelf shelf) => PushLayer(new CatalogView(shelf));

    void OnPlayResume(object sender, ExecutedRoutedEventArgs e)
    {
        if (e.Parameter is ResumeTile tile) PlayFile(tile.Item, tile.Index);
    }

    public void OpenTitle(Title title) => PushLayer(new DetailView(title));

    public void GoBack()
    {
        if (ConfirmLayer.Visibility == Visibility.Visible) { CloseConfirm(); return; }
        if (PlayerHost.Content is PlayerView player) { player.RequestExit(); return; }
        if (Layers.Children.Count > 0) CloseLayer((FrameworkElement)Layers.Children[^1]);
    }

    void OnKey(object sender, KeyEventArgs e)
    {
        if (PlayerHost.Content is PlayerView player)
        {
            player.HandleKey(e);
            return;
        }
        if (e.Key == Key.Escape || (e.Key == Key.Left && Keyboard.Modifiers == ModifierKeys.Alt) || e.Key == Key.BrowserBack)
        {
            if (Keyboard.FocusedElement is TextBox && e.Key == Key.Left) return;
            GoBack();
            e.Handled = true;
        }
    }

    // ── Library state ───────────────────────────────────────────────────────

    public void ChangeSettings(AppSettings next)
    {
        var themeChanged = next.ThemeMode != Settings.ThemeMode;
        var limitsChanged = next.MaxDownloadKbps != Settings.MaxDownloadKbps || next.MaxUploadKbps != Settings.MaxUploadKbps;
        Settings = next;
        next.Save();
        if (themeChanged) Theme.Apply(next.ThemeMode);
        if (limitsChanged) _ = TorrentEngine.Instance.ApplyLimits(next);
        SettingsChanged?.Invoke();
    }

    public void ToggleSaved(Title title, string list) => SavedList = Saved.Toggle(title, list);

    /// <summary>
    /// How far through each episode you are, by Cinemeta episode id — the join of
    /// episode -> torrent uri -> the history entry the player writes to, plus
    /// whatever "S01E02" in stored file names says — or, given the show's episodes,
    /// an anime file's running number ("- 29").
    /// </summary>
    public Dictionary<string, double> EpisodeProgress(List<Episode>? episodes = null)
    {
        var @out = new Dictionary<string, double>();
        void Record(string id, double? position, double? duration)
        {
            if (duration is not > 0) return;
            @out[id] = Math.Max(@out.GetValueOrDefault(id), Math.Min(100, (position ?? 0) / duration.Value * 100));
        }
        foreach (var (id, uri) in Links)
        {
            var entry = HistoryList.FirstOrDefault(h => h.Uri == uri);
            if (entry?.LastFileIndex is not int index) continue;
            Record(id, entry.PositionOf(index), entry.DurationOf(index));
        }
        foreach (var entry in HistoryList.Where(h => h.Kind == "tv" && h.Imdb is not null))
        {
            foreach (var file in entry.Files ?? new())
            {
                var i = episodes is { Count: > 0 } && episodes[0].Id.StartsWith(entry.Imdb + ":") ? Meta.IndexOfEpisode(episodes, file.Name, file.Path) : -1;
                if (i >= 0)
                {
                    Record(episodes![i].Id, entry.PositionOf(file.Index), entry.DurationOf(file.Index));
                    continue;
                }
                var at = Search.ParseEpisode(file.Name);
                if (at is null) continue;
                Record($"{entry.Imdb}:{at.Value.Season}:{at.Value.Episode}", entry.PositionOf(file.Index), entry.DurationOf(file.Index));
            }
        }
        return @out;
    }

    /// <summary>Where a film was left, by IMDb id.</summary>
    public (double Position, double Duration)? FilmProgress(string imdb)
    {
        foreach (var entry in HistoryList)
        {
            if (entry.Imdb != imdb || entry.LastFileIndex is not int index) continue;
            if (entry.DurationOf(index) is double duration && duration > 0) return (entry.PositionOf(index) ?? 0, duration);
        }
        return null;
    }

    readonly HashSet<string> triedArt = new();

    /// <summary>Real artwork for library rows nothing has identified yet — looked up once per torrent.</summary>
    async Task BackfillPosters()
    {
        foreach (var h in HistoryList.ToList())
        {
            if (History.IsRemote(h.Poster)) continue;
            var book = SavedList.FirstOrDefault(s => (h.Imdb is not null && s.Imdb == h.Imdb) || (h.TitleName is not null && s.Title == h.TitleName));
            if (book?.Poster is null) continue;
            History.Patch(h.Uri, x => x with { Poster = book.Poster, Imdb = x.Imdb ?? book.Imdb, Kind = x.Kind ?? book.Kind, TitleName = x.TitleName ?? book.Title });
        }
        foreach (var target in HistoryList.Where(h => !History.IsRemote(h.Poster) && triedArt.Add(h.Uri)).Take(12).ToList())
        {
            var found = target.Imdb is not null
                ? await Meta.LoadTitle(target.Imdb, target.Kind ?? "movie")
                : await Meta.ResolveTitle(target.TorrentName ?? target.Title);
            if (found?.Poster is null) continue;
            History.Patch(target.Uri, x => x with { Imdb = found.Imdb, Kind = found.Kind, Poster = found.Poster, TitleName = found.Name });
        }
    }

    // ── Opening a torrent ───────────────────────────────────────────────────

    CancellationTokenSource? opening;
    /// <summary>stream | download | null (ask) — null only for a torrent arriving from outside.</summary>
    string? torrentMode = "stream";

    public bool IsOpening => opening is not null;

    void SetOpening(CancellationTokenSource? cts, string text = "Reading torrent…")
    {
        opening = cts;
        BusyText.Text = text;
        BusyCard.Visibility = cts is null ? Visibility.Collapsed : Visibility.Visible;
        OpeningChanged?.Invoke();
    }

    void OnCancelOpen(object sender, RoutedEventArgs e)
    {
        opening?.Cancel();
        SetOpening(null);
    }

    /// <summary>A magnet or .torrent from outside — the command line or a drop.</summary>
    void OpenIncoming(string source)
    {
        torrentMode = null;
        _ = OpenUri(source, new OpenOptions());
    }

    void OnDropSource(object sender, DragEventArgs e)
    {
        var source = e.Data.GetData(DataFormats.FileDrop) is string[] { Length: > 0 } files && files[0].EndsWith(".torrent", StringComparison.OrdinalIgnoreCase)
            ? files[0]
            : e.Data.GetData(DataFormats.UnicodeText) as string;
        if (source is not null && TitleText.IsTorrentSource(source.Trim())) OpenIncoming(source.Trim());
    }

    /// <summary>Stream a release: straight into the feature when <see cref="OpenOptions.Auto"/> says which file.</summary>
    public Task<string?> StreamUri(string uri, OpenOptions opts)
    {
        torrentMode = "stream";
        return OpenUri(uri, opts);
    }

    public async Task<string?> OpenUri(string uri, OpenOptions opts)
    {
        var ok = await Open(new HistoryItem
        {
            Uri = uri,
            Title = TitleText.TitleOf(uri),
            At = Store.Now,
            Imdb = opts.Imdb,
            Kind = opts.Kind,
            Poster = opts.Poster,
            TitleName = opts.TitleName,
        }, null, opts);
        return ok ? uri : null;
    }

    async Task<bool> Open(HistoryItem next, int? fileIndex, OpenOptions opts)
    {
        opening?.Cancel();
        var detail = CurrentDetail;
        HistoryList = History.Put(next with
        {
            At = Store.Now,
            Poster = next.Poster ?? detail?.Poster,
            Imdb = next.Imdb ?? detail?.Imdb,
            Kind = next.Kind ?? detail?.Kind,
            TitleName = next.TitleName ?? detail?.Name,
        });
        var fresh = HistoryList.First(h => h.Uri == next.Uri);

        var cts = new CancellationTokenSource();
        SetOpening(cts);
        Torrent torrent;
        TorrentMeta meta;
        try
        {
            var uri = Settings.AddTrackers ? TitleText.WithTrackers(fresh.Uri) : fresh.Uri;
            (torrent, meta) = await TorrentEngine.Instance.Inspect(uri, TimeSpan.FromSeconds(60), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
        catch (Exception e)
        {
            if (opening == cts) Toast(e.GetBaseException().Message, true);
            return false;
        }
        finally
        {
            if (opening == cts) SetOpening(null);
        }
        if (cts.IsCancellationRequested) return false;

        // Remember the whole torrent, not just the file that got opened.
        HistoryList = History.Patch(fresh.Uri, x => x with { TorrentName = meta.Name, Files = meta.Files });

        if (fileIndex is int index)
        {
            Play(fresh.Uri, torrent, meta, index, opts.KeepProgress);
            return true;
        }

        if (opts.Auto is not null)
        {
            var indices = opts.Season is not null ? Pick.SeasonFiles(meta.Files, opts.Season) : null;
            var chosen = indices is { Count: > 0 } ? indices[0] : Pick.PickFile(meta.Files, opts.Auto == "best" ? null : opts.Auto);
            if (chosen is int pick)
            {
                Play(fresh.Uri, torrent, meta, pick, opts.KeepProgress);
                return true;
            }
        }

        // Nothing decided which file: let the viewer choose.
        var item = HistoryList.First(h => h.Uri == fresh.Uri);
        if (torrentMode is null)
        {
            ModePickerView? picker = null;
            picker = new ModePickerView(meta,
                stream: () => { CloseLayer(picker!); torrentMode = "stream"; ShowFiles(item, torrent, meta, "stream"); },
                download: () => { CloseLayer(picker!); torrentMode = "download"; ShowFiles(item, torrent, meta, "download"); },
                cancel: () => { CloseLayer(picker!); torrentMode = "stream"; });
            PushLayer(picker);
        }
        else
        {
            ShowFiles(item, torrent, meta, torrentMode);
        }
        return true;
    }

    void ShowFiles(HistoryItem item, Torrent torrent, TorrentMeta meta, string mode)
    {
        FilesView? view = null;
        DownloadJob Job(List<int> indices) => new()
        {
            Uri = item.Uri,
            FileIndices = indices,
            QueueIndices = indices,
            Title = meta.Name,
            TitleName = item.TitleName,
            Poster = item.Poster,
            Imdb = item.Imdb,
            Kind = item.Kind,
        };
        view = new FilesView(meta, mode,
            play: file =>
            {
                if (mode == "download") { CloseLayer(view!); BeginDownload(Job(new() { file.Index })); return; }
                CloseLayer(view!);
                Play(item.Uri, torrent, meta, file.Index, false);
            },
            download: file => { CloseLayer(view!); BeginDownload(Job(new() { file.Index })); },
            downloadAll: () =>
            {
                var indices = Pick.SeasonFiles(meta.Files);
                if (indices.Count == 0) return;
                CloseLayer(view!);
                BeginDownload(Job(indices));
            },
            back: () => { CloseLayer(view!); torrentMode = "stream"; });
        PushLayer(view);
    }

    // ── Playing ─────────────────────────────────────────────────────────────

    /// <summary>A file from a history row: carry on where it was.</summary>
    public void PlayFile(HistoryItem target, int index)
    {
        var book = SavedList.FirstOrDefault(s => s.Imdb is not null && s.Imdb == target.Imdb);
        var kin = HistoryList.FirstOrDefault(h => h.Uri != target.Uri && h.Imdb == target.Imdb && h.Poster is not null);
        torrentMode = "stream";
        _ = Open(target with
        {
            Poster = History.RemoteArt(target.Poster, kin?.Poster, book?.Poster),
            Imdb = target.Imdb ?? book?.Imdb,
            Kind = target.Kind ?? book?.Kind,
            TitleName = target.TitleName ?? book?.Title,
        }, index, new OpenOptions());
    }

    /// <summary>A library row goes to its title page; a torrent nothing identifies plays what it last played.</summary>
    public async void OpenItem(HistoryItem target)
    {
        var found = target.Imdb is not null
            ? await Meta.LoadTitle(target.Imdb, target.Kind ?? "movie") ?? await Meta.ResolveTitle(target.TorrentName ?? target.Title)
            : await Meta.ResolveTitle(target.TorrentName ?? target.Title);
        if (found is not null)
        {
            OpenTitle(found);
            if (target.Imdb != found.Imdb || target.Poster is null)
                History.Patch(target.Uri, x => x with { Imdb = found.Imdb, Kind = found.Kind, Poster = found.Poster ?? x.Poster, TitleName = found.Name });
            return;
        }
        torrentMode = "stream";
        _ = Open(target, target.LastFileIndex, new OpenOptions { Auto = "best" });
    }

    void Play(string uri, Torrent torrent, TorrentMeta meta, int index, bool startOver)
    {
        var stored = HistoryList.FirstOrDefault(h => h.Uri == uri) ?? new HistoryItem { Uri = uri, Title = meta.Name };
        var detail = CurrentDetail;
        var file = meta.Files[index];
        var ratio = stored.ResumeRatio(index);
        var request = new PlaybackRequest
        {
            Title = file.Name,
            MetaTitle = detail?.Name ?? stored.TitleName,
            Imdb = detail?.Imdb ?? stored.Imdb,
            Kind = detail?.Kind ?? stored.Kind,
            ResumeAt = startOver || ratio <= 0 ? 0 : stored.PositionOf(index) ?? 0,
            Torrent = torrent,
            FileIndex = index,
            HistoryUri = uri,
            SavedSubs = stored.Subtitles?.GetValueOrDefault(index) ?? new(),
            Next = UpNext(stored, torrent, meta, index, detail),
        };
        OpenPlayer(request);
    }

    /// <summary>
    /// The episode after this one: inside this torrent first (instant), else the
    /// next episode of the title page it came from, found by searching.
    /// </summary>
    (string Name, Func<Task> Go)? UpNext(HistoryItem stored, Torrent torrent, TorrentMeta meta, int index, Title? detail)
    {
        if (Pick.NextEpisode(meta.Files, index) is { } following)
            return (following.Name, () => { Play(stored.Uri, torrent, meta, following.Index, true); return Task.CompletedTask; });

        if (detail is not { Kind: "tv" }) return null;
        var list = detail.Episodes;
        var i = Meta.IndexOfEpisode(list, meta.Files[index].Name, meta.Files[index].Path);
        if (i < 0 || i + 1 >= list.Count) return null;
        var next = list[i + 1];
        var tag = Search.EpisodeTag(next.Season, next.Number);
        var absolute = Meta.AbsoluteNumber(list, next.Season, next.Number);
        return ($"{tag} · {next.Name}", async () =>
        {
            var hits = await Search.SearchEpisode(detail.Name, next.Season, next.Number, default, detail.Imdb, absolute);
            if (hits.Count == 0)
            {
                Toast($"No releases found for {tag}", true);
                return;
            }
            var local = await StreamUri(hits[0].Magnet, new OpenOptions
            {
                Auto = Search.PickTag(next.Season, next.Number, absolute),
                KeepProgress = true,
                Imdb = detail.Imdb,
                Kind = detail.Kind,
                Poster = detail.Poster,
                TitleName = detail.Name,
            });
            if (local is not null) Links = EpisodeLinks.Link(next.Id, local);
        });
    }

    public void PlayLocal(string path, string title) =>
        OpenPlayer(new PlaybackRequest { Title = title, LocalPath = path });

    void OpenPlayer(PlaybackRequest request)
    {
        if (PlayerHost.Content is PlayerView old) _ = old.Shutdown();
        PlayerHost.Visibility = Visibility.Visible;
        var player = new PlayerView(request);
        player.Exited += async () =>
        {
            if (PlayerHost.Content == player) PlayerHost.Visibility = Visibility.Collapsed;
            Layers.Visibility = Visibility.Visible;
            TabDock.Visibility = Layers.Children.Count > 0 ? Visibility.Collapsed : Visibility.Visible;
            ShowTopLayerOnly();
            LibraryChanged?.Invoke();
            // Hidden now, removed once libVLC has let go: destroying the video
            // window while it still draws into it can block this thread.
            await player.Closed;
            if (PlayerHost.Content == player) PlayerHost.Content = null;
        };
        // The video surface is its own window; nothing underneath needs drawing.
        Tabs.Visibility = Layers.Visibility = TabDock.Visibility = Visibility.Collapsed;
        PlayerHost.Content = player;
    }

    public void SavePosition(string uri, int index, double seconds) => HistoryList = History.SetPosition(uri, index, seconds);
    public void SaveDuration(string uri, int index, double seconds) => HistoryList = History.SetDuration(uri, index, seconds);
    public void SaveSubs(string uri, int index, List<SavedSub> subs) => HistoryList = History.SetFileSubs(uri, index, subs);
    public void LinkEpisode(string id, string uri) => Links = EpisodeLinks.Link(id, uri);

    // ── Downloads ───────────────────────────────────────────────────────────

    public void BeginDownload(DownloadJob job)
    {
        foreach (var page in Layers.Children.OfType<FrameworkElement>().ToList()) CloseLayer(page);
        ShowTab("downloads");
        if (Downloads.Instance.IsLive(job.Uri)) Toast("Already downloading — see below");
        Downloads.Instance.Start(job);
    }

    public void DownloadFile(HistoryItem target, int index) => BeginDownload(new DownloadJob
    {
        Uri = target.Uri,
        FileIndices = new() { index },
        Title = target.Title,
        TitleName = target.TitleName,
        Poster = target.Poster,
        Imdb = target.Imdb,
        Kind = target.Kind,
    });

    public void DownloadSeason(HistoryItem target, List<int>? indices)
    {
        var list = indices is { Count: > 0 } ? indices : Pick.SeasonFiles(target.Files ?? new());
        if (list.Count == 0) return;
        BeginDownload(new DownloadJob
        {
            Uri = target.Uri,
            FileIndices = list,
            QueueIndices = list,
            Title = target.Title,
            TitleName = target.TitleName,
            Poster = target.Poster,
            Imdb = target.Imdb,
            Kind = target.Kind,
        });
    }

    // ── Feedback ────────────────────────────────────────────────────────────

    DispatcherTimer? toastTimer;

    public void Toast(string message, bool bad = false)
    {
        ToastText.Text = message;
        ToastBar.Background = bad ? (System.Windows.Media.Brush)FindResource("Danger") : new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromArgb(0xe6, 0x0b, 0x0e, 0x14));
        ToastBar.Visibility = Visibility.Visible;
        toastTimer?.Stop();
        toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(bad ? 5 : 3) };
        toastTimer.Tick += (_, _) => { ToastBar.Visibility = Visibility.Collapsed; toastTimer.Stop(); };
        toastTimer.Start();
    }

    Action? confirmAction;

    public void Confirm(string title, string? message, string confirmLabel, Action onConfirm, string icon = "trash-2", bool destructive = true)
    {
        ConfirmTitle.Text = title;
        ConfirmMessage.Text = message ?? "";
        ConfirmMessage.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
        ConfirmOk.Content = confirmLabel;
        ConfirmOk.Style = (Style)FindResource(destructive ? "PillDanger" : "PillAccent");
        ConfirmIcon.Kind = icon;
        ConfirmIcon.SetResourceReference(Controls.Icon.ForegroundProperty, destructive ? "Danger" : "Accent");
        ConfirmBadge.SetResourceReference(Border.BackgroundProperty, destructive ? "DangerSoft" : "AccentSoft");
        confirmAction = onConfirm;
        ConfirmLayer.Visibility = Visibility.Visible;
    }

    void CloseConfirm()
    {
        ConfirmLayer.Visibility = Visibility.Collapsed;
        confirmAction = null;
    }

    void OnConfirmOk(object sender, RoutedEventArgs e)
    {
        var action = confirmAction;
        CloseConfirm();
        action?.Invoke();
    }

    void OnConfirmCancel(object sender, RoutedEventArgs e) => CloseConfirm();
    void OnConfirmBackdrop(object sender, MouseButtonEventArgs e) => CloseConfirm();
    void OnSwallow(object sender, MouseButtonEventArgs e) => e.Handled = true;

    public void AskClearHistory() => Confirm(
        "Clear watch history?",
        $"{HistoryList.Count} item(s) and their resume points will be deleted.",
        "Clear all",
        () => HistoryList = History.Clear());

    public void AskRemove(HistoryItem item) => Confirm(
        "Remove from history?",
        item.TorrentName ?? item.Title,
        "Remove",
        () => HistoryList = History.Drop(item.Uri));

    // ── Shutdown ────────────────────────────────────────────────────────────

    bool shuttingDown;

    protected override async void OnClosing(CancelEventArgs e)
    {
        if (shuttingDown) return;
        e.Cancel = true;
        shuttingDown = true;
        Hide();
        try
        {
            if (PlayerHost.Content is PlayerView player) await player.Shutdown();
            await Downloads.Instance.StopAll();
            await TorrentEngine.Instance.Shutdown();
        }
        catch { }
        Application.Current.Shutdown();
    }
}
