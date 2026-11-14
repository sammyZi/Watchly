using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using Watchly.Controls;
using Watchly.Core;
using Watchly.Engine;

namespace Watchly.Views;

/// <summary>
/// One title, laid out as the phone shows it held sideways and as the TV does (mobile
/// Detail.kt): the backdrop on the right, the logo, facts and Play down the left, the
/// actions under them, then the episodes of a series and the releases to play.
/// </summary>
public sealed class DetailView : UserControl, IDisposable
{
    static readonly string[] Qualities = { "4K", "1080p", "720p", "CAM" };
    const int EpisodePage = 10;
    /// <summary>Past this the list is noise; the best are at the top.</summary>
    const int MaxSources = 60;
    /// <summary>The words' column beside the backdrop, and the lists under it.</summary>
    const double Column = 460, ListWidth = 720;

    readonly MainWindow shell = MainWindow.Shell;
    readonly ScrollViewer scroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    readonly StackPanel content = new() { HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(32, 72, 32, 40) };
    readonly Border backdrop = new();
    readonly DispatcherTimer searchDelay = new() { Interval = TimeSpan.FromMilliseconds(280) };

    Title title;
    List<Hit>? sources;
    string? sourceKey;
    Episode? picked;
    int? season;
    bool wholeSeason;
    string? wanted;
    /// <summary>The releases' language; null is any.</summary>
    Lang? lang;
    bool plotOpen;
    bool saving;
    /// <summary>An episode's download button asked before its releases were in: keep the best once they are.</summary>
    string? error;
    string? backdropUrl;
    CancellationTokenSource? searching;
    int searchGen;
    FrameworkElement? sourcesAnchor;
    /// <summary>Rating, cast and what's alike. Without a key or a connection the page is as it was.</summary>
    TmdbTitle? tmdb;
    WatchOrder? inOrder;

    public Title Current => title;

    public DetailView(Title seed)
    {
        title = seed;
        if (seed.Kind == "tv")
        {
            picked = GuessEpisode(seed.Imdb, shell.EpisodeProgress(title.Episodes));
            season = picked.Season;
        }

        // The backdrop stays put while the page scrolls over it.
        var stage = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition { Width = new GridLength(30, GridUnitType.Star) }, new ColumnDefinition { Width = new GridLength(70, GridUnitType.Star) } },
            RowDefinitions = { new RowDefinition { Height = new GridLength(90, GridUnitType.Star) }, new RowDefinition { Height = new GridLength(10, GridUnitType.Star) } },
        };
        var art = Ui.FadedArt(backdrop);
        Grid.SetColumn(art, 1);
        stage.Children.Add(art);

        scroll.Content = content;
        Ui.SmoothWheel(scroll);

        // mobile Ui.kt BackButton: round, dark, over the art.
        var back = Ui.Round("arrow-left", () => shell.CloseLayer(this), "Text", "Tint", 40, 20, "Back");
        ((Icon)back.Content).Foreground = Brushes.White;
        back.Background = new SolidColorBrush(Color.FromArgb(0x80, 0, 0, 0));
        back.HorizontalAlignment = HorizontalAlignment.Left;
        back.VerticalAlignment = VerticalAlignment.Top;
        back.Margin = new Thickness(20, 16, 0, 0);

        Content = new Grid { Children = { stage, scroll, back } };

        searchDelay.Tick += (_, _) => { searchDelay.Stop(); FindReleases(); };
        shell.LibraryChanged += OnLibrary;
        shell.OpeningChanged += Render;

        inOrder = WatchOrders.Of(seed.Imdb);
        Render();
        LoadFull();
        LoadTmdb();
        FindReleases();
    }

    async void LoadTmdb()
    {
        tmdb = await Tmdb.Title(title.Imdb, title.Kind);
        if (tmdb is not null) Render();
        if (inOrder is null && title.Kind == "movie" && await Tmdb.OrderOf(title.Imdb) is { } order)
        {
            inOrder = order;
            Render();
        }
    }

    public void Dispose()
    {
        shell.LibraryChanged -= OnLibrary;
        shell.OpeningChanged -= Render;
        searching?.Cancel();
        searchDelay.Stop();
    }

    void OnLibrary()
    {
        if (IsVisible) Render();
    }

    /// <summary>A shelf row carries no episodes or logo, so the full record is always fetched.</summary>
    async void LoadFull()
    {
        if (title.Plot is not null && title.Logo is not null && (title.Kind == "movie" || title.Episodes.Count > 0)) return;
        var full = await Meta.LoadTitle(title.Imdb, title.Kind);
        if (full is null) return;
        title = full;
        if (title.Kind == "tv" && title.Episodes.Count > 0)
        {
            var progress = shell.EpisodeProgress(title.Episodes);
            var next = title.Episodes.FirstOrDefault(e => e.Season > 0 && progress.GetValueOrDefault(e.Id) < EpisodeLinks.Watched) ?? title.Episodes[0];
            // Swap the S01E01 guess for the real next episode once Cinemeta answers.
            if (picked is null || string.IsNullOrEmpty(picked.Name))
            {
                var same = picked is not null && picked.Season == next.Season && picked.Number == next.Number;
                picked = next;
                season = next.Season;
                if (!same) FindReleases();
            }
        }
        Render();
    }

    static Episode GuessEpisode(string imdb, Dictionary<string, double> progress)
    {
        var prefix = imdb + ":";
        var rows = progress
            .Where(p => p.Key.StartsWith(prefix))
            .Select(p => (p.Key, Parts: p.Key[prefix.Length..].Split(':'), Pct: p.Value))
            .Where(r => r.Parts.Length == 2 && int.TryParse(r.Parts[0], out _) && int.TryParse(r.Parts[1], out _))
            .Select(r => (r.Key, Season: int.Parse(r.Parts[0]), Episode: int.Parse(r.Parts[1]), r.Pct))
            .OrderBy(r => r.Season).ThenBy(r => r.Episode)
            .ToList();
        var next = rows.FirstOrDefault(r => r.Pct < EpisodeLinks.Watched);
        if (next.Key is null && rows.Count > 0) next = rows[0];
        return new Episode
        {
            Id = next.Key ?? $"{imdb}:1:1",
            Season = next.Key is null ? 1 : next.Season,
            Number = next.Key is null ? 1 : next.Episode,
        };
    }

    string PickKey => (picked is not null ? $"{picked.Season}:{picked.Number}" : $"movie:{title.Imdb}") + $":{lang?.Code}";
    int? PickedAbsolute => picked is not null ? Meta.AbsoluteNumber(title.Episodes, picked.Season, picked.Number) : null;
    string? PickedTag => picked is not null ? Search.PickTag(picked.Season, picked.Number, PickedAbsolute) : null;
    /// <summary>"S2:E4", as the phone and the TV write an episode.</summary>
    static string Short(Episode ep) => $"S{ep.Season}:E{ep.Number}";

    /// <summary>Nothing found; with a language picked, that is the likely reason, so it says so.</summary>
    string NoneFound()
    {
        if (!Addons.HasStreams) return Addons.NoneYet;
        var what = picked is null ? title.Name : Short(picked);
        return lang is null ? $"No sources found for {what}" : $"No {what} in {lang.Name} — change the language and try again";
    }

    /// <summary>Releases for the selected episode (or the film), debounced across season flips.</summary>
    void FindReleases(bool debounce = false)
    {
        if (debounce && sourceKey is not null)
        {
            searchDelay.Stop();
            searchDelay.Start();
            return;
        }
        searching?.Cancel();
        var cts = new CancellationTokenSource();
        searching = cts;
        var mine = ++searchGen;
        var key = PickKey;
        error = null;
        var job = picked is not null
            ? Search.SearchEpisode(picked.Id, title.Name, picked.Season, cts.Token, lang, title.StremioType, title)
            : Search.SearchStreams(title.Name, cts.Token, title.Imdb, lang: lang, type: title.StremioType);
        job.ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            if (mine != searchGen) return;
            sources = t.IsCompletedSuccessfully ? t.Result : new List<Hit>();
            if (t.IsFaulted) error = t.Exception?.GetBaseException().Message;
            sourceKey = key;
            if (wanted is not null && !sources.Any(h => h.Quality == wanted)) wanted = null;
            Render();
        }));
    }

    // ── Render ──────────────────────────────────────────────────────────────

    static TextBlock Heading(string text, double size = 18)
    {
        var t = Ui.Text(text, "TitleText", "Text", size, FontWeights.Bold);
        t.Margin = new Thickness(0, 0, 0, 12);
        return t;
    }

    void Render()
    {
        var offset = scroll.VerticalOffset;
        content.Children.Clear();

        // Genres let it through, its rating doesn't: a kids profile stops here.
        if (KidsMode.On && Tmdb.TooOldForKids(tmdb?.Certificate))
        {
            searching?.Cancel();
            backdrop.Background = null;
            content.Children.Add(Ui.Empty("lock", "Not for this profile", $"{title.Name} is rated {tmdb!.Certificate}. Switch to a grown-up's profile to watch it."));
            return;
        }

        var url = title.Wide;
        if (url is not null && url != backdropUrl && ImageUrl.Load(url, 1920) is { } image)
        {
            backdropUrl = url;
            backdrop.Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top };
        }

        var progress = shell.EpisodeProgress(title.Episodes);
        var resume = title.Kind == "movie" ? shell.FilmProgress(title.Imdb) : null;
        var fresh = sourceKey == PickKey;
        var shown = wanted is not null ? sources?.Where(h => h.Quality == wanted).ToList() : sources;
        var best = fresh ? shown?.FirstOrDefault() : null;
        var bestTorrent = fresh ? shown?.FirstOrDefault(h => h.IsTorrent) : null;
        // A film left part-way on Stremio, never here, still says Resume: the release picked starts at that spot.
        var seen = picked is not null ? progress.GetValueOrDefault(picked.Id)
            : resume is { } r ? Math.Min(100, r.Position / r.Duration * 100)
            : Stremio.Library.PausedAt(title.Imdb, null) ?? 0;
        var canResume = seen >= 1 && seen < EpisodeLinks.Watched;
        var wait = shell.IsOpening || saving;
        var live = best is not null && !wait;

        var head = new StackPanel { Width = Column, HorizontalAlignment = HorizontalAlignment.Left };
        head.Children.Add(Ui.TitleMark(title, 80, 320));
        var facts = Ui.Facts(title);
        facts.Margin = new Thickness(0, 12, 0, 16);
        head.Children.Add(facts);
        var marks = new StackPanel { Orientation = Orientation.Horizontal };
        if (tmdb?.Certificate is { } certificate) marks.Children.Add(CertificateBadge(certificate));
        if (title.StremioType == "movie" && (seen >= EpisodeLinks.Watched || Trackers.Everywhere.Films.Contains(title.Imdb)))
            marks.Children.Add(WatchedBadge(seen >= EpisodeLinks.Watched ? shell.HistoryList.Where(h => h.Imdb == title.Imdb).Select(h => (long?)h.LastPlayed).Max() : null));
        if (marks.Children.Count > 0) head.Children.Add(marks);

        // The one thing most people want: start the healthiest release — or carry on.
        var label = wait ? saving ? "Starting download…" : "Opening…"
            : best is null ? fresh && sources is not null ? "No sources" : "Finding sources…"
            : canResume ? "Resume  ·  " + (picked is null ? "" : Short(picked) + "  ·  ") + (TimeLeft() ?? $"{Math.Round(seen)}%")
            : picked is not null ? $"Play {Short(picked)}" : "Play";
        var play = Ui.Action(label, "play", () =>
        {
            if (best is not null) Start(best, false);
            else if (fresh && sources is not null) shell.Toast(NoneFound(), true);
            else shell.Toast("Still finding sources…");
        }, primary: true);
        play.IsHitTestVisible = !wait;
        play.Opacity = live ? 1 : 0.6;
        head.Children.Add(play);
        if (canResume)
        {
            var over = Ui.Action("Start over", "rotate-ccw", () => { if (best is not null) Start(best, true); });
            over.Margin = new Thickness(0, 8, 0, 0);
            over.IsEnabled = live;
            head.Children.Add(over);
        }

        // A film's own progress; a series shows each episode's.
        if (picked is null && seen >= 1 && resume is { } res)
        {
            var line = Ui.Text($"{Format.Clock(res.Position)} of {Format.Clock(res.Duration)}" + (seen >= EpisodeLinks.Watched ? " · finished" : $" · {Format.Left(res.Duration - res.Position)}"), "Meta", "Faint", 12);
            line.Margin = new Thickness(0, 4, 0, 0);
            head.Children.Add(new StackPanel { Margin = new Thickness(0, 10, 0, 0), Children = { Ui.Bar(seen, 3), line } });
        }

        if (title.Plot is not null)
        {
            var plot = Ui.Text(title.Plot, "Body", "Text");
            plot.Opacity = 0.85;
            if (!plotOpen)
            {
                plot.MaxHeight = 88;
                plot.TextTrimming = TextTrimming.WordEllipsis;
            }
            var plotButton = Ui.Button(plot, () => { plotOpen = !plotOpen; Render(); });
            plotButton.Margin = new Thickness(0, 14, 0, 0);
            head.Children.Add(plotButton);
        }

        if (title.Directors.Count > 0)
        {
            var crew = new TextBlock { FontSize = 13, FontWeight = FontWeights.Medium, Margin = new Thickness(0, 8, 0, 0), TextWrapping = TextWrapping.Wrap };
            crew.Inlines.Add(new System.Windows.Documents.Run((title.Kind == "tv" ? "Creators" : "Director") + ": ") { Foreground = (Brush)FindResource("Faint") });
            var first = true;
            foreach (var name in title.Directors.Take(2))
            {
                if (!first) crew.Inlines.Add(new System.Windows.Documents.Run(", ") { Foreground = (Brush)FindResource("Faint") });
                first = false;
                // A link once TMDB has told us who they are; a kids profile has no actor pages.
                if (!KidsMode.On && tmdb?.Directors.FirstOrDefault(d => Tmdb.SameName(d.Name, name)) is { } person)
                {
                    var link = new System.Windows.Documents.Hyperlink(new System.Windows.Documents.Run(name)) { Foreground = (Brush)FindResource("Text"), Cursor = System.Windows.Input.Cursors.Hand };
                    link.Click += (_, _) => shell.OpenPerson(person.Id, person.Name);
                    crew.Inlines.Add(link);
                }
                else crew.Inlines.Add(new System.Windows.Documents.Run(name) { Foreground = (Brush)FindResource("Muted") });
            }
            head.Children.Add(crew);
        }

        // However many actions there are, they share the width evenly.
        var later = Saved.OnList(shell.SavedList, title.Imdb, "later");
        var liked = Saved.OnList(shell.SavedList, title.Imdb, "favorite");
        var actions = new UniformGrid { Rows = 1, Margin = new Thickness(0, 14, 0, 0) };
        actions.Children.Add(Ui.IconAction(later ? "check" : "plus", later ? "In My List" : "My List", () => { shell.ToggleSaved(title, "later"); Render(); }, later, "Good", double.NaN));
        actions.Children.Add(Ui.IconAction("heart", liked ? "Liked" : "Like", () => { shell.ToggleSaved(title, "favorite"); Render(); }, liked, "Accent", double.NaN));
        if (title.Kind != "tv")
            actions.Children.Add(Ui.IconAction("download", "Download", () =>
            {
                if (!(fresh && sources is not null)) shell.Toast("Still finding sources…");
                else if (bestTorrent is null) shell.Toast(sources.Count > 0 ? "Links stream only — there is no torrent here to download." : NoneFound());
                else
                {
                    // The releases to choose from, each with its own download button, not whichever came first.
                    shell.Toast("Pick a release to download");
                    ScrollToSources();
                }
            }, width: double.NaN));
        actions.Children.Add(LanguageAction());
        head.Children.Add(actions);

        if (tmdb?.Cast.Count > 0) head.Children.Add(PeopleRow(tmdb.Cast));
        else if (title.Cast.Count > 0) head.Children.Add(CastRow());
        if (title.Awards is not null) head.Children.Add(AwardsCard(title.Awards));
        content.Children.Add(head);

        if (inOrder is { } order) content.Children.Add(FranchiseRail(order));
        if (title.Kind == "tv") RenderEpisodes(progress);
        RenderReviews();
        if (tmdb?.Similar.Count > 0 && !KidsMode.On)
            content.Children.Add(new StackPanel { Width = ListWidth, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 26, 0, 0), Children = { Heading("More like this"), OrderUi.Strip(tmdb.Similar.Select(OrderUi.Card)) } });
        RenderSources(fresh, shown, wait);

        Dispatcher.BeginInvoke(() => scroll.ScrollToVerticalOffset(offset), DispatcherPriority.Loaded);
    }

    /// <summary>
    /// "40m left" where the film — or the picked episode — was left: the release played last here
    /// (core resumeLabel), else Stremio's spot; null when the time isn't known.
    /// </summary>
    string? TimeLeft()
    {
        (int, int)? ep = picked is null ? null : (picked.Season, picked.Number);
        var local = History.LatestPerTitle(shell.HistoryList.Where(h => h.Imdb == title.Imdb)).FirstOrDefault();
        if (local?.LastFileIndex is int i && local.Ratio is > 0.02 and < 0.95 && local.DurationOf(i) is double of && local.PositionOf(i) is double at
            && (ep is null || Search.ParseEpisode(local.Files?.FirstOrDefault(f => f.Index == i)?.Name) == ep))
            return Format.Left(of - at);
        return Stremio.Library.Paused.FirstOrDefault(p => p.Imdb == title.Imdb && p.Ep == ep) is { Seconds: double s, Progress: > 0 } paused
            ? Format.Left(s / (paused.Progress / 100) - s)
            : null;
    }

    /// <summary>The releases' language, from a menu; picking one looks again.</summary>
    Button LanguageAction()
    {
        var menu = new ContextMenu();
        foreach (var l in new Lang?[] { null }.Concat(Lang.All))
        {
            var item = new MenuItem { Header = l?.Name ?? "Any language", IsChecked = l == lang };
            item.Click += (_, _) =>
            {
                lang = l;
                FindReleases();
                Render();
            };
            menu.Items.Add(item);
        }
        Button button = null!;
        button = Ui.IconAction("languages", lang?.Name ?? "Language", () => { menu.PlacementTarget = button; menu.IsOpen = true; }, lang is not null, "Accent", double.NaN);
        button.ContextMenu = menu;
        return button;
    }

    /// <summary>core WatchedBadge: a film seen through, with the day if it was here; an account only says that it was.</summary>
    static FrameworkElement WatchedBadge(long? at)
    {
        var disc = Ui.Brushed(new Border { Width = 20, Height = 20, CornerRadius = new CornerRadius(10), Child = Ui.Glyph("check", 13, "Bg") }, "Good");
        var row = new StackPanel { Orientation = Orientation.Horizontal, Children = { disc } };
        var word = Ui.Text("Watched", "Meta", "Good", 13, FontWeights.SemiBold);
        word.Margin = new Thickness(7, 0, 0, 0);
        word.VerticalAlignment = VerticalAlignment.Center;
        row.Children.Add(word);
        if (at is { } ms)
        {
            var day = Ui.Text($"  ·  {DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime:d MMM yyyy}", "Meta", "Muted", 13);
            day.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(day);
        }
        var badge = Ui.Brushed(new Border { CornerRadius = new CornerRadius(15), Padding = new Thickness(5, 5, 12, 5), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 0, 0, 16), Child = row }, "GoodSoft");
        return badge;
    }

    /// <summary>The film's age rating, as a small outlined mark.</summary>
    static FrameworkElement CertificateBadge(string certificate)
    {
        var badge = new Border { CornerRadius = new CornerRadius(4), BorderThickness = new Thickness(1), Padding = new Thickness(6, 2, 6, 2), Margin = new Thickness(0, 0, 10, 16), VerticalAlignment = VerticalAlignment.Center, Child = Ui.Text(certificate, "Meta", "Muted", 12, FontWeights.SemiBold) };
        badge.SetResourceReference(Border.BorderBrushProperty, "Muted");
        return badge;
    }

    /// <summary>On a film's page: the franchise it belongs to, every film of it in order, opened at this one.</summary>
    FrameworkElement FranchiseRail(WatchOrder order)
    {
        var seen = WatchOrders.Seen(shell.HistoryList);
        var at = order.Films.IndexOf(title.Imdb);
        var done = order.Films.Count(seen.Contains);
        var name = Ui.Text(order.Name, "TitleText", "Text", 18, FontWeights.Bold);
        var line = Ui.Text(string.Join("  ·  ", new[] { $"Film {at + 1} of {order.Films.Count}", done > 0 ? $"{done} watched" : null }.Where(s => s is not null)), "Meta", "Faint", 12);
        var link = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Children = { Ui.Text("Watch order", "Meta"), Ui.Glyph("chevron-right", 16, "Muted") } };
        DockPanel.SetDock(link, Dock.Right);
        var head = Ui.Button(new DockPanel { Children = { link, new StackPanel { Children = { name, line } } } }, () => shell.OpenOrder(order));
        head.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        head.Margin = new Thickness(0, 0, 0, 10);
        var strip = OrderUi.Strip(order.Films.Select((id, i) => (UIElement)OrderUi.Poster(i + 1, id, id == title.Imdb, seen.Contains(id), () =>
        {
            if (id != title.Imdb) shell.OpenTitle(new Title { Imdb = id });
        })));
        strip.Loaded += (_, _) => strip.ScrollToHorizontalOffset(Math.Max(0, at - 1) * 110);
        return new StackPanel { Width = ListWidth, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 26, 0, 0), Children = { head, strip } };
    }

    /// <summary>The cast from TMDB: real photos, who each one plays, and a click through to what else they're in.</summary>
    FrameworkElement PeopleRow(List<CastMember> people)
    {
        var faces = people.Select(person =>
        {
            var face = Ui.Brushed(new Border
            {
                Width = 64,
                Height = 64,
                CornerRadius = new CornerRadius(32),
                Child = Ui.Text(string.Concat(person.Name.Split(' ', StringSplitOptions.RemoveEmptyEntries).Take(2).Select(w => char.ToUpper(w[0]))), "Label", "Muted", 15, FontWeights.Bold),
            }, "SurfaceAlt");
            ((FrameworkElement)face.Child).HorizontalAlignment = HorizontalAlignment.Center;
            ((FrameworkElement)face.Child).VerticalAlignment = VerticalAlignment.Center;
            var photo = new System.Windows.Shapes.Ellipse { Width = 64, Height = 64 };
            if (ImageUrl.Load(person.Photo, 128) is { } image) photo.Fill = new ImageBrush(image) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top };
            var name = Ui.Text(person.Name, "Meta", "Text", 12, FontWeights.SemiBold);
            name.TextWrapping = TextWrapping.Wrap;
            name.TextAlignment = TextAlignment.Center;
            name.MaxHeight = 34;
            name.Margin = new Thickness(0, 6, 0, 0);
            var column = new StackPanel { Width = 84, Children = { new Grid { Width = 64, Children = { face, photo } }, name } };
            if (person.Role is not null)
            {
                var role = Ui.Text(person.Role, "Meta", "Faint", 11);
                role.TextWrapping = TextWrapping.Wrap;
                role.TextAlignment = TextAlignment.Center;
                role.MaxHeight = 30;
                column.Children.Add(role);
            }
            var button = Ui.Button(column, () => { if (!KidsMode.On) shell.OpenPerson(person.Id, person.Name); }, "Lift");
            button.Margin = new Thickness(0, 0, 10, 0);
            button.VerticalAlignment = VerticalAlignment.Top;
            return (UIElement)button;
        });
        return new StackPanel { Margin = new Thickness(0, 18, 0, 0), Children = { Heading("Cast", 16), OrderUi.Strip(faces) } };
    }

    /// <summary>The cast as faces: a round photo over each name. Initials until the photos arrive.</summary>
    FrameworkElement CastRow()
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        var photos = new Dictionary<string, System.Windows.Shapes.Ellipse>();
        foreach (var who in title.Cast.Take(12))
        {
            var face = Ui.Brushed(new Border
            {
                Width = 64,
                Height = 64,
                CornerRadius = new CornerRadius(32),
                Child = Ui.Text((string)new Initials().Convert(who, typeof(string), null!, null!), "Label", "Muted", 15, FontWeights.Bold),
            }, "SurfaceAlt");
            ((FrameworkElement)face.Child).HorizontalAlignment = HorizontalAlignment.Center;
            ((FrameworkElement)face.Child).VerticalAlignment = VerticalAlignment.Center;
            var photo = new System.Windows.Shapes.Ellipse { Width = 64, Height = 64 };
            photos[who] = photo;
            var name = Ui.Text(who, "Meta", "Text", 12);
            name.TextWrapping = TextWrapping.Wrap;
            name.TextAlignment = TextAlignment.Center;
            name.MaxHeight = 34;
            name.Margin = new Thickness(0, 6, 0, 0);
            row.Children.Add(new StackPanel { Width = 72, Margin = new Thickness(0, 0, 14, 0), Children = { new Grid { Width = 64, Children = { face, photo } }, name } });
        }
        Meta.CastPhotos(title.Imdb, title.Cast.Take(12).ToList()).ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            foreach (var (who, url) in t.Result)
                if (photos.TryGetValue(who, out var photo) && ImageUrl.Load(url, 128) is { } image)
                    photo.Fill = new ImageBrush(image) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top };
        }), TaskScheduler.Default);

        var rail = new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, Content = row };
        Ui.PassWheel(rail);
        Ui.DragScroll(rail);
        return new StackPanel { Margin = new Thickness(0, 18, 0, 0), Children = { Heading("Cast", 16), rail } };
    }

    /// <summary>Awards as a card: a trophy, the headline prize, and the tally as two pills (core awardsOf).</summary>
    static FrameworkElement AwardsCard(string text)
    {
        var wins = System.Text.RegularExpressions.Regex.Match(text, @"(\d+) wins?", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        var noms = System.Text.RegularExpressions.Regex.Match(text, @"(\d+) nominations?", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        var headline = text.Split('.').Select(s => s.Trim()).FirstOrDefault(s => s.Length > 0 && !System.Text.RegularExpressions.Regex.IsMatch(s, @"^\d+ (wins?|nominations?)", System.Text.RegularExpressions.RegexOptions.IgnoreCase));

        var trophy = new Border
        {
            Width = 42,
            Height = 42,
            CornerRadius = new CornerRadius(21),
            Background = new SolidColorBrush(Color.FromArgb(0x29, 0xf5, 0xc5, 0x18)),
            Child = Ui.Glyph("award", 22, "Imdb"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        var words = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        var top = Ui.Text(headline ?? (wins.Success || noms.Success ? "Awards" : text), "Label", "Text", 15, FontWeights.SemiBold);
        top.TextWrapping = TextWrapping.Wrap;
        words.Children.Add(top);
        if (wins.Success || noms.Success)
        {
            var pills = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 0) };
            FrameworkElement Pill(string label, Color tint, string brush)
            {
                var b = Ui.Pill(new Border { Padding = new Thickness(10, 3, 10, 3), Margin = new Thickness(0, 0, 8, 0), Background = new SolidColorBrush(Color.FromArgb(0x24, tint.R, tint.G, tint.B)), Child = Ui.Text(label, "Meta", brush, 12, FontWeights.SemiBold) });
                return b;
            }
            if (wins.Success) pills.Children.Add(Pill($"{wins.Groups[1].Value} {(wins.Groups[1].Value == "1" ? "win" : "wins")}", Color.FromRgb(0xf5, 0xc5, 0x18), "Imdb"));
            if (noms.Success) pills.Children.Add(Pill($"{noms.Groups[1].Value} {(noms.Groups[1].Value == "1" ? "nomination" : "nominations")}", Color.FromRgb(0xb3, 0xb3, 0xb3), "Muted"));
            words.Children.Add(pills);
        }
        var dock = new DockPanel();
        DockPanel.SetDock(trophy, Dock.Left);
        dock.Children.Add(trophy);
        dock.Children.Add(words);
        var card = Ui.Card(dock, "Surface", 10, new Thickness(14));
        card.Margin = new Thickness(0, 18, 0, 0);
        return card;
    }

    /// <summary>Which hundred of a long anime is listed; null follows the picked episode.</summary>
    int? hundred;
    string numberQuery = "";

    /// <summary>
    /// Long-running anime, as the phone lists it: every episode by its number through the show,
    /// a hundred at a time, with a box to jump to a number — TVmaze's stills, Cinemeta's are 404s.
    /// </summary>
    void RenderNumbered(Dictionary<string, double> progress)
    {
        var numbered = Meta.AbsoluteNumbers(title.Episodes);
        var blocks = numbered.Chunk(100).ToList();
        var at = hundred ?? Math.Max(0, numbered.FindIndex(n => n.Episode.Id == picked?.Id) / 100);
        var block = new StackPanel { Margin = new Thickness(0, 26, 0, 0), Width = ListWidth, HorizontalAlignment = HorizontalAlignment.Left };
        block.Children.Add(Heading("Episodes"));

        var chips = new WrapPanel();
        for (var b = 0; b < blocks.Count; b++)
        {
            var captured = b;
            var chip = Ui.Chip($"{blocks[b][0].Number}–{blocks[b][^1].Number}", b == at && numberQuery.Length == 0, () => { hundred = captured; numberQuery = ""; Render(); });
            chip.Margin = new Thickness(0, 0, 8, 8);
            chips.Children.Add(chip);
        }
        block.Children.Add(chips);

        // Rows are refilled in place as a number is typed, so the box keeps its focus.
        var rows = new StackPanel();
        void Fill()
        {
            rows.Children.Clear();
            var shown = numberQuery.Length == 0 ? blocks.ElementAtOrDefault(at) ?? Array.Empty<(Episode, int)>()
                : numbered.Where(n => n.Number.ToString().StartsWith(numberQuery)).Take(100).ToArray();
            foreach (var (ep, n) in shown) rows.Children.Add(EpisodeRow(ep, progress.GetValueOrDefault(ep.Id), n));
            if (shown.Length == 0) rows.Children.Add(Ui.Text($"No episode {numberQuery}", "Meta", "Faint", 13));
        }
        var (box, input, _) = Ui.SearchField("Episode number", 44);
        box.Margin = new Thickness(0, 2, 0, 10);
        input.Text = numberQuery;
        input.PreviewTextInput += (_, e) => e.Handled = !e.Text.All(char.IsDigit) || input.Text.Length >= 4;
        input.TextChanged += (_, _) => { numberQuery = new string(input.Text.Where(char.IsDigit).ToArray()); Fill(); };
        block.Children.Add(box);
        Fill();
        block.Children.Add(rows);
        content.Children.Add(block);
    }

    void RenderEpisodes(Dictionary<string, double> progress)
    {
        if (title.NumberedThrough)
        {
            RenderNumbered(progress);
            return;
        }
        var episodes = title.Episodes;
        var seasons = episodes.Count > 0 ? episodes.Select(e => e.Season).Distinct().ToList() : season is not null ? new List<int> { season.Value } : new();
        if (seasons.Count == 0) return;
        var inSeason = episodes.Where(e => e.Season == season).ToList();
        var showsWhole = wholeSeason || (picked is not null && inSeason.FindIndex(e => e.Id == picked.Id) >= EpisodePage);
        var listed = showsWhole ? inSeason : inSeason.Take(EpisodePage).ToList();

        var block = new StackPanel { Margin = new Thickness(0, 26, 0, 0), Width = ListWidth, HorizontalAlignment = HorizontalAlignment.Left };
        block.Children.Add(Heading("Episodes"));

        if (seasons.Count > 1)
        {
            var chips = new WrapPanel();
            foreach (var s in seasons)
            {
                var captured = s;
                var chip = Ui.Chip(s == 0 ? "Specials" : $"Season {s}", s == season, () => ChooseSeason(captured));
                chip.Margin = new Thickness(0, 0, 8, 8);
                chips.Children.Add(chip);
            }
            block.Children.Add(chips);
        }

        if (season is not null)
        {
            var name = season == 0 ? "specials" : $"season {season}";
            var open = packSeason == season;
            var words = Ui.Text(saving ? "Starting download…" : $"Download {name}", "Meta", open ? "Text" : "Muted", 13, FontWeights.SemiBold);
            words.Margin = new Thickness(8, 0, 6, 0);
            var link = Ui.Button(new StackPanel { Orientation = Orientation.Horizontal, Children = { saving ? Ui.Spinner(16, "Muted") : Ui.Glyph("download", 16, "Muted"), words, Ui.Glyph(open ? "chevron-up" : "chevron-down", 14, "Faint") } }, SaveSeason, "Row");
            link.HorizontalAlignment = HorizontalAlignment.Left;
            link.Padding = new Thickness(4, 6, 6, 6);
            link.Margin = new Thickness(-4, 0, 0, 4);
            link.Tag = 6;
            link.IsEnabled = !shell.IsOpening && !saving;
            block.Children.Add(link);
            if (open) block.Children.Add(PackList(season.Value));
        }

        foreach (var ep in listed) block.Children.Add(EpisodeRow(ep, progress.GetValueOrDefault(ep.Id)));

        if (inSeason.Count > listed.Count || (wholeSeason && inSeason.Count > EpisodePage))
        {
            var less = wholeSeason && inSeason.Count > EpisodePage && inSeason.Count == listed.Count;
            var more = Ui.Action(less ? "Show fewer episodes" : $"Show all {inSeason.Count} episodes", less ? "chevron-up" : "chevron-down", () => { wholeSeason = !less; Render(); });
            more.Margin = new Thickness(0, 8, 0, 0);
            block.Children.Add(more);
        }
        content.Children.Add(block);
    }

    /// <summary>Netflix's episode row (mobile Detail.kt): the still, number and name beside it, the synopsis under both.</summary>
    FrameworkElement EpisodeRow(Episode ep, double seen, int? number = null)
    {
        var done = seen >= EpisodeLinks.Watched;
        var on = picked?.Id == ep.Id;
        var busy = on && sourceKey != PickKey;

        var still = new Grid { Width = 132, Height = 74, ClipToBounds = true };
        still.Children.Add(Ui.Art(ep.Thumbnail ?? title.Backdrop, ep.Name, 132, 74, 6, 264));
        still.Children.Add(new Border { CornerRadius = new CornerRadius(6), Background = new SolidColorBrush(Color.FromArgb((byte)(busy ? 0x80 : 0x33), 0, 0, 0)) });
        if (busy) still.Children.Add(Ui.Spinner(24, "Accent"));
        else
        {
            var mark = Ui.Glyph("play", 16, "Text");
            mark.Foreground = Brushes.White;
            still.Children.Add(new Border { Width = 32, Height = 32, CornerRadius = new CornerRadius(16), Background = new SolidColorBrush(Color.FromArgb(0x8c, 0, 0, 0)), Child = mark });
        }
        if (seen > 0 && !done)
        {
            var bar = Ui.Bar(seen, 3);
            bar.VerticalAlignment = VerticalAlignment.Bottom;
            bar.Background = new SolidColorBrush(Color.FromArgb(0x40, 0xff, 0xff, 0xff));
            still.Children.Add(bar);
        }
        else if (on && !busy) still.Children.Add(Ui.Brushed(new Border { Height = 3, VerticalAlignment = VerticalAlignment.Bottom }, "Accent"));
        if (done)
            still.Children.Add(Ui.Brushed(new Border { Width = 18, Height = 18, CornerRadius = new CornerRadius(9), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(4), Child = Ui.Glyph("check", 11, "OnAccent") }, "Accent"));

        var name = Ui.Text($"{number ?? ep.Number}. {ep.Name}", "Label", "Text", 15, FontWeights.SemiBold);
        name.TextWrapping = TextWrapping.Wrap;
        name.MaxHeight = 40;
        var when = Ui.Text(done ? "Watched" : seen > 0 ? $"{Math.Round(seen)}% watched" : ep.Released is { Length: >= 10 } rel ? rel[..10] : "", "Meta", "Faint", 12);
        when.Margin = new Thickness(0, 2, 0, 0);
        var text = new StackPanel { Margin = new Thickness(12, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center, Children = { name, when } };

        var captured = ep;
        var download = Ui.Round("download", () => DownloadEpisode(captured), "Muted", "Transparent", 40, 22, $"Download {Short(ep)}");
        download.Background = Brushes.Transparent;
        download.VerticalAlignment = VerticalAlignment.Center;

        var top = new DockPanel();
        DockPanel.SetDock(still, Dock.Left);
        DockPanel.SetDock(download, Dock.Right);
        top.Children.Add(still);
        top.Children.Add(download);
        top.Children.Add(text);
        var body = new StackPanel { Children = { top } };
        if (ep.Overview is not null)
        {
            var overview = Ui.Text(ep.Overview, "Meta", "Faint", 12);
            overview.TextWrapping = TextWrapping.Wrap;
            overview.MaxHeight = 36;
            overview.TextTrimming = TextTrimming.WordEllipsis;
            overview.Margin = new Thickness(0, 6, 0, 0);
            body.Children.Add(overview);
        }

        // Picking an episode makes it what Play plays, and lists its releases below.
        var row = Ui.Button(body, () =>
        {
            picked = captured;
            FindReleases(debounce: true);
            Render();
            ScrollToSources();
        }, "Row");
        row.Padding = new Thickness(8, 10, 8, 10);
        row.Margin = new Thickness(-8, 0, -8, 0);
        row.Tag = 8;
        if (on) row.SetResourceReference(BackgroundProperty, "TintLo");
        return row;
    }

    /// <summary>Move down to the torrents — after Render's own scroll restore, once laid out.</summary>
    void ScrollToSources() => Dispatcher.BeginInvoke(() =>
    {
        if (sourcesAnchor is null) return;
        var y = sourcesAnchor.TransformToAncestor(content).Transform(new Point(0, 0)).Y;
        scroll.ScrollToVerticalOffset(Math.Max(0, y - 20));
    }, DispatcherPriority.Loaded);

    void RenderSources(bool fresh, List<Hit>? shown, bool wait)
    {
        var block = new StackPanel { Margin = new Thickness(0, 26, 0, 0), Width = ListWidth, HorizontalAlignment = HorizontalAlignment.Left };
        sourcesAnchor = block;
        block.Children.Add(Heading(picked is null ? "Sources" : $"Sources  ·  {Short(picked)}"));

        var qualities = Qualities.Where(q => sources?.Any(h => h.Quality == q) == true).ToList();
        if (fresh && qualities.Count > 1)
        {
            var chips = new WrapPanel { Margin = new Thickness(0, 0, 0, 4) };
            foreach (var q in qualities.Prepend(null))
            {
                var captured = q;
                var chip = Ui.Chip(q ?? "All", wanted == q, () => { wanted = captured; Render(); });
                chip.Margin = new Thickness(0, 0, 8, 8);
                chips.Children.Add(chip);
            }
            block.Children.Add(chips);
        }

        FrameworkElement Note(string text, string brush = "Faint")
        {
            var t = Ui.Text(text, "Body", brush);
            t.Margin = new Thickness(0, 0, 0, 8);
            return t;
        }
        FrameworkElement Busy(string text)
        {
            var words = Ui.Text(text, "Body");
            words.Margin = new Thickness(12, 0, 0, 0);
            return new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 12), Children = { Ui.Spinner(24), words } };
        }
        if (wait) block.Children.Add(Busy(saving ? "Starting download…" : "Opening…"));
        if (sources is null || !fresh) block.Children.Add(Busy("Asking your addons…"));
        else if (error is not null) block.Children.Add(Note(error, "Danger"));
        else if (sources.Count == 0) block.Children.Add(Note(
            !Addons.HasStreams ? Addons.NoneYet
            : lang is not null ? $"Nothing found in {lang.Name}. Change the language and try again."
            : "Nothing found. Try another episode, or add another addon under Settings → Addons."));

        if (fresh && shown is not null)
        {
            // Which of these TorBox has cached: the rows say Instant once it answers.
            _ = TorBox.Check(shown.Take(MaxSources).Where(h => h.IsTorrent).Select(h => h.Id));
            foreach (var hit in shown.Take(MaxSources)) block.Children.Add(SourceRow(hit));
        }
        content.Children.Add(block);
    }

    /// <summary>One release (mobile Detail.kt Source): what it is, how big, and how well it will stream — in words.</summary>
    FrameworkElement SourceRow(Hit hit)
    {
        var tone = Ui.HealthTone(hit.Seeders);
        var dock = new DockPanel();
        if (hit.Quality is not null)
        {
            var tag = Ui.Brushed(new Border { CornerRadius = new CornerRadius(4), Padding = new Thickness(7, 3, 7, 3), Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center, Child = Ui.Text(hit.Quality, "Overline", "Text") }, "Tint");
            DockPanel.SetDock(tag, Dock.Left);
            dock.Children.Add(tag);
        }
        var captured = hit;
        // The download engine is a torrent client; a link is streamed only.
        if (hit.IsTorrent)
        {
            var download = Ui.Round("download", () => Save(captured.Uri), "Muted", "Transparent", 36, 20, "Download this release");
            download.Background = Brushes.Transparent;
            download.Margin = new Thickness(4, 0, 0, 0);
            DockPanel.SetDock(download, Dock.Right);
            dock.Children.Add(download);
        }
        var grade = hit.Direct ? Ui.Text("Direct", "Meta", "Good", 13, FontWeights.Bold)
            : hit.External ? Ui.Text("Browser", "Meta", "Muted", 13, FontWeights.Bold)
            : Ui.Text(tone.Label, "Meta", tone.Fg, 13, FontWeights.Bold);
        grade.VerticalAlignment = VerticalAlignment.Center;
        grade.Margin = new Thickness(10, 0, 0, 0);
        DockPanel.SetDock(grade, Dock.Right);
        dock.Children.Add(grade);

        var name = Ui.Text(hit.Name, "Label", "Text", 14);
        name.ToolTip = hit.Name;
        // A film's scope is "episode" only by default — it says nothing there.
        var scope = picked is null && hit.Scope == "episode" ? null : hit.Scope switch { "season" => "Season pack", "complete" => "Complete series", _ => "Episode" };
        var meta = Ui.Text(string.Join("  ·  ", new[]
        {
            scope,
            hit.Size > 0 ? Format.Bytes(TorrentBytes(hit)) : null,
            hit.IsTorrent ? $"{hit.Seeders} seeds" : null,
            hit.Addon.Length > 0 ? hit.Addon : null,
        }.Where(s => s is not null)), "Meta", "Faint", 12);
        meta.Margin = new Thickness(0, 2, 0, 0);
        var lines = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { name, meta } };
        if (hit.IsTorrent && TorBox.IsCached(hit.Id)) lines.Children.Add(Ui.Text("Instant · TorBox", "Meta", "Good", 12, FontWeights.SemiBold));
        dock.Children.Add(lines);

        var row = Ui.Button(Ui.Card(dock, "Surface", 8, new Thickness(14, 11, 8, 11)), () => Start(captured, false), "Row");
        row.Tag = 8;
        row.Margin = new Thickness(0, 0, 0, 8);
        return row;
    }

    /// <summary>Where to read what other people thought.</summary>
    void RenderReviews()
    {
        var links = new WrapPanel();
        foreach (var (label, url) in Meta.ReviewLinks(title))
        {
            var words = Ui.Text(label, "Meta", "Text", 13);
            words.VerticalAlignment = VerticalAlignment.Center;
            var mark = Ui.Glyph("external-link", 13, "Muted");
            mark.Margin = new Thickness(8, 0, 0, 0);
            mark.VerticalAlignment = VerticalAlignment.Center;
            var link = Ui.Button(new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Children = { words, mark } }, () => Ui.Open(url), "Solid", url);
            link.Tag = 18; // half the height: round ends, not an oval
            link.Height = 36;
            link.Padding = new Thickness(16, 0, 14, 0);
            link.Margin = new Thickness(0, 0, 8, 8);
            link.SetResourceReference(BackgroundProperty, "TintLo");
            links.Children.Add(link);
        }
        content.Children.Add(new StackPanel { Margin = new Thickness(0, 26, 0, 0), Width = ListWidth, HorizontalAlignment = HorizontalAlignment.Left, Children = { Heading("Reviews"), links } });
    }

    /// <summary>An addon's size is one file; a pack's torrent is that times its episodes.</summary>
    long TorrentBytes(Hit hit)
    {
        if (!hit.PerFileSize || hit.Scope == "episode" || hit.Size == 0) return hit.Size;
        var claims = Search.SeasonsIn(hit.PackName);
        var n = (claims.Count > 0
            ? title.Episodes.Where(e => claims.Contains(e.Season))
            : hit.Scope == "complete" ? title.Episodes : title.Episodes.Where(e => e.Season == season)).Count();
        return n > 1 ? hit.Size * n : hit.Size;
    }

    void ChooseSeason(int which)
    {
        season = which;
        wholeSeason = false;
        var progress = shell.EpisodeProgress(title.Episodes);
        var list = title.Episodes.Where(e => e.Season == which).ToList();
        var next = list.FirstOrDefault(e => progress.GetValueOrDefault(e.Id) < EpisodeLinks.Watched) ?? list.FirstOrDefault();
        if (next is not null)
        {
            picked = next;
            FindReleases(debounce: true);
        }
        Render();
    }

    /// <summary>An episode's Download: its releases to pick from, each with its own download button.</summary>
    void DownloadEpisode(Episode ep)
    {
        if (picked?.Id != ep.Id || sourceKey != PickKey)
        {
            picked = ep;
            FindReleases();
        }
        Render();
        shell.Toast("Pick a release to download");
        ScrollToSources();
    }

    async void Start(Hit hit, bool startOver)
    {
        var episodeId = picked?.Id;
        var local = await shell.StreamHit(hit, new OpenOptions
        {
            Auto = PickedTag ?? "best",
            KeepProgress = startOver,
            Imdb = title.Imdb,
            Kind = title.Kind,
            Poster = title.Poster,
            TitleName = title.Name,
        });
        if (episodeId is not null && local is not null) shell.LinkEpisode(episodeId, local);
    }

    void Save(string magnet)
    {
        saving = true;
        Render();
        shell.BeginDownload(new DownloadJob
        {
            Uri = magnet,
            Auto = PickedTag ?? "best",
            Imdb = title.Imdb,
            Kind = title.Kind,
            Poster = title.Poster,
            TitleName = title.Name,
            Title = title.Name,
        });
    }

    /// <summary>The season whose packs are listed under its Download link.</summary>
    int? packSeason;

    /// <summary>
    /// Download season: the season packs to choose from — quality, size and how well each is seeded —
    /// rather than the first one found. The addons list them with the picked episode's streams.
    /// Clicked again, it closes.
    /// </summary>
    void SaveSeason()
    {
        if (season is not int which) return;
        if (packSeason == which)
        {
            packSeason = null;
            Render();
            return;
        }
        packSeason = which;
        Render();
    }

    /// <summary>This season's packs among the picked episode's streams; null until they are in.</summary>
    List<Hit>? PacksOf(int which)
    {
        if (picked?.Season != which || sourceKey != PickKey || sources is null) return null;
        bool IsPack(Hit h)
        {
            if (!h.IsTorrent || h.Scope is not ("season" or "complete")) return false;
            var claims = Search.SeasonsIn(h.PackName);
            return claims.Count == 0 || claims.Contains(which);
        }
        return sources.Where(IsPack).OrderByDescending(h => h.Seeders).ToList();
    }

    /// <summary>The packs for <see cref="packSeason"/>, under its Download link.</summary>
    FrameworkElement PackList(int which)
    {
        var list = new StackPanel { Margin = new Thickness(0, 4, 0, 12) };
        var packs = PacksOf(which);
        if (packs is null)
        {
            var words = Ui.Text($"Finding season {which} packs…", "Body");
            words.Margin = new Thickness(12, 0, 0, 0);
            list.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Children = { Ui.Spinner(22), words } });
            return list;
        }
        if (packs.Count == 0)
        {
            list.Children.Add(Ui.Text(lang is null
                ? $"No pack for season {which} — download its episodes one by one below."
                : $"No season {which} pack in {lang.Name} — change the language and try again, or download its episodes one by one below.", "Body", "Faint"));
            return list;
        }
        foreach (var hit in packs.Take(MaxSources))
        {
            var tone = Ui.HealthTone(hit.Seeders);
            var dock = new DockPanel();
            if (hit.Quality is not null)
            {
                var tag = Ui.Brushed(new Border { CornerRadius = new CornerRadius(4), Padding = new Thickness(7, 3, 7, 3), Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center, Child = Ui.Text(hit.Quality, "Overline", "Text") }, "Tint");
                DockPanel.SetDock(tag, Dock.Left);
                dock.Children.Add(tag);
            }
            var go = Ui.Glyph("download", 18, "Muted");
            go.Margin = new Thickness(12, 0, 6, 0);
            DockPanel.SetDock(go, Dock.Right);
            dock.Children.Add(go);
            var grade = Ui.Text(tone.Label, "Meta", tone.Fg, 13, FontWeights.Bold);
            grade.VerticalAlignment = VerticalAlignment.Center;
            DockPanel.SetDock(grade, Dock.Right);
            dock.Children.Add(grade);
            // Under the pack's own name, not the one episode file the addon pointed into it.
            var name = Ui.Text(hit.PackName, "Label", "Text", 14);
            name.ToolTip = hit.PackName;
            var meta = Ui.Text(string.Join("  ·  ", hit.Scope == "complete" ? "Complete series" : "Season pack", Format.Bytes(TorrentBytes(hit)), $"{hit.Seeders} seeds"), "Meta", "Faint", 12);
            meta.Margin = new Thickness(0, 2, 0, 0);
            dock.Children.Add(new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { name, meta } });
            var captured = hit;
            var row = Ui.Button(Ui.Card(dock, "Surface", 8, new Thickness(14, 11, 8, 11)), () => DownloadPack(captured, which), "Row", $"Download season {which} from this release");
            row.Tag = 8;
            row.Margin = new Thickness(0, 0, 0, 8);
            list.Children.Add(row);
        }
        return list;
    }

    void DownloadPack(Hit pack, int which)
    {
        packSeason = null;
        shell.BeginDownload(new DownloadJob
        {
            Uri = pack.Uri,
            Season = which,
            Imdb = title.Imdb,
            Kind = title.Kind,
            Poster = title.Poster,
            TitleName = title.Name,
            Title = title.Name,
        });
    }
}
