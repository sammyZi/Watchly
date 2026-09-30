using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>A Continue-watching card: the title as a poster, plus where you stopped.</summary>
public sealed record ResumeTile(Title Title, HistoryItem Item, int Index, double Progress, string? Stamp);

/// <summary>
/// src/screens/HomeScreen.tsx: a hero of what is popular (or what you like),
/// then Continue watching, the saved lists, and genre shelves — chosen in
/// Settings first, inferred from history next, the stock charts last.
/// </summary>
public sealed class HomeView : UserControl
{
    static readonly Shelf[] Fixed =
    {
        new("Trending films", "movie"),
        new("Popular series", "tv"),
        new("Anime", "tv", Meta.AnimeGenre),
    };

    readonly MainWindow shell = MainWindow.Shell;
    readonly ScrollViewer scroll;
    readonly StackPanel page = new() { Margin = new Thickness(0, 0, 0, 120) };
    readonly Grid hero = new() { Height = 480 };
    readonly StackPanel personal = new();
    readonly StackPanel shelves = new();
    readonly Dictionary<string, List<Title>> loaded = new();

    const string TasteKey = "home-taste";
    /// <summary>The genres you watch (see Meta.Taste), from last time until this session's is worked out.</summary>
    Dictionary<string, double> taste = Store.Load(TasteKey, new Dictionary<string, double>());

    /// <summary>A shelf's titles for today — see Recommend. Genre shelves share titles out between them.</summary>
    List<Title> Today(Shelf shelf, List<Title> pool, HashSet<string?> watched) =>
        Recommend.ForToday(pool, shelf.Genre, taste, watched, onePerGenre: shelf.Genre is not null && shelf.Genre != Meta.AnimeGenre);
    readonly Dictionary<string, (FrameworkElement Container, ContentControl Body)> shelfControls = new();
    List<Shelf> picks = new();
    int version;

    public HomeView()
    {
        scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = page };
        Ui.SmoothWheel(scroll);
        // Rows are built as they come near the screen — see Fill.
        scroll.ScrollChanged += (_, _) => FillVisible();
        scroll.SizeChanged += (_, _) => FillVisible();
        Loaded += (_, _) => Dispatcher.BeginInvoke(FillVisible, DispatcherPriority.Loaded);

        page.Children.Add(hero);
        page.Children.Add(personal);
        page.Children.Add(shelves);
        Content = scroll;

        foreach (var s in AllShelves())
            if (Meta.PeekCatalog(s.Kind, s.Genre) is { } ready) loaded[s.Key] = ready;

        shell.LibraryChanged += Refresh;
        shell.SettingsChanged += Refresh;
        Net.OnlineChanged += () => Dispatcher.BeginInvoke(() => { if (Net.IsOnline) Refresh(); });
        // The player checkpoints history every few seconds; a hidden page must not
        // rebuild seven shelves each time. It catches up when it is shown again.
        IsVisibleChanged += (_, _) => { if (IsVisible && dirty) Refresh(); };
        Refresh();
    }

    bool dirty;

    static bool InProgress(HistoryItem item) => item.LastFileIndex is int i && item.ResumeRatio(i) < 0.9;

    static string? ResumeStamp(HistoryItem item)
    {
        if (item.LastFileIndex is not int index) return null;
        var file = item.Files?.FirstOrDefault(f => f.Index == index);
        var at = Search.ParseEpisode(file?.Name ?? item.Title);
        var ep = at is null ? Search.ParseAbsolute(file?.Name ?? item.Title) is int n ? $"Ep {n}" : null
            : at.Value.Season == 0 ? $"Sp {at.Value.Episode}" : $"S{at.Value.Season} E{at.Value.Episode}";
        var position = item.PositionOf(index);
        var bits = new[] { ep, position > 0 ? Format.Clock(position.Value) : null }.Where(b => b is not null);
        var stamp = string.Join("  ·  ", bits);
        return stamp.Length > 0 ? stamp : null;
    }

    Title FromHistory(HistoryItem item)
    {
        var book = shell.SavedList.FirstOrDefault(s => s.Imdb == item.Imdb);
        return new Title
        {
            Imdb = item.Imdb ?? item.Uri,
            Kind = item.Kind ?? book?.Kind ?? "movie",
            Name = item.TitleName ?? book?.Title ?? item.Title,
            Poster = History.PosterOf(item, shell.SavedList),
        };
    }

    List<Shelf> Chosen() => shell.Settings.Genres
        .Select(g => new Shelf(g == Meta.AnimeGenre ? "Anime" : g, Meta.KindOfGenre(g), g)).ToList();

    List<Shelf> AllShelves()
    {
        var chosen = Chosen();
        var taken = chosen.Select(s => s.Key).ToHashSet();
        var rest = picks.Where(p => taken.Add(p.Key)).ToList();
        var fixedList = Fixed.Where(s => taken.Add(s.Key)).ToList();

        // Include all genres from Meta.Genres
        var genreShelves = Meta.Genres
            .Select(g => new Shelf(g == Meta.AnimeGenre ? "Anime" : g, Meta.KindOfGenre(g), g))
            .Where(s => taken.Add(s.Key))
            .ToList();

        return chosen.Concat(rest).Concat(fixedList).Concat(genreShelves).ToList();
    }

    async void Refresh()
    {
        if (!IsVisible && heroKey.Length > 0)
        {
            dirty = true;
            return;
        }
        dirty = false;
        var mine = ++version;
        BuildPersonal();
        BuildShelves();
        // Before the taste lookup, not after it: that walks up to thirty titles'
        // records, and every shelf used to sit on a spinner until it finished.
        LoadMissing();

        // What is mid-way leads the taste signal, then the rest, then saved titles.
        var recent = shell.HistoryList;
        var identified = recent.Where(InProgress).Concat(recent.Where(h => !InProgress(h)))
            .Where(h => h.Imdb is not null).Select(h => (h.Imdb!, h.Kind ?? "movie"))
            .Concat(shell.SavedList.Select(s => (s.Imdb, s.Kind)))
            .Take(30).ToList();
        if (identified.Count > 0)
        {
            var genres = await Meta.WatchedGenres(identified, 4);
            if (mine != version) return;
            // Kept for the next build of the shelves; changing rails already on screen would make them jump.
            taste = await Meta.Taste(identified);
            try { Store.Save(TasteKey, taste); } catch { }
            if (mine != version) return;
            var next = genres.Select((g, i) => new Shelf(i == 0 ? "Recommended for you" : $"Because you like {g.Genre}", g.Kind, g.Genre)).ToList();
            if (!next.SequenceEqual(picks))
            {
                picks = next;
                BuildShelves();
            }
        }

        // The recommended shelves only exist now; fetch theirs too.
        LoadMissing();
    }

    readonly HashSet<string> requested = new();

    /// <summary>Fetch every shelf's catalog not already in hand, once each.</summary>
    void LoadMissing()
    {
        foreach (var shelf in AllShelves())
        {
            if (loaded.ContainsKey(shelf.Key) || !requested.Add(shelf.Key)) continue;
            _ = Meta.LoadCatalog(shelf.Kind, shelf.Genre).ContinueWith(t => Dispatcher.BeginInvoke(() =>
            {
                requested.Remove(shelf.Key);
                if (t.Result.Count == 0) return;
                loaded[shelf.Key] = t.Result;
                var watched = shell.HistoryList.Select(h => h.Imdb).Where(i => i is not null).ToHashSet();
                if (shelfControls.TryGetValue(shelf.Key, out var pair) && pair.Body.Content is Grid { Tag: Loading })
                    Fill(shelf, pair.Container, pair.Body, watched);
                BuildHero(AllShelves(), watched);
            }), TaskScheduler.Default);
        }
    }

    /// <summary>A row not yet built: what to build once it comes near the screen.</summary>
    readonly Dictionary<ContentControl, Action> waiting = new();

    /// <summary>Marks a shelf still waiting for its catalog, as against one waiting to be seen.</summary>
    const string Loading = "loading";

    /// <summary>
    /// Put a shelf's row in — now if it is on or near the screen, otherwise when it
    /// scrolls close. The page holds twenty-odd shelves, and building them all at
    /// once asked for every one of their posters at startup: some four hundred
    /// images through six download slots, first come first served. The rows the
    /// viewer was looking at — the recommended ones, added last because they wait
    /// on the taste lookup — sat on coloured tiles until everything below them had
    /// landed. A placeholder of the row's own height keeps the page from jumping.
    /// </summary>
    void Fill(Shelf shelf, FrameworkElement container, ContentControl body, HashSet<string?> watched)
    {
        if (!loaded.TryGetValue(shelf.Key, out var raw)) return;
        void Build()
        {
            waiting.Remove(body);
            body.Content = Ui.Rail(Today(shelf, raw, watched), "PosterCard", 290);
        }
        if (NearScreen(container))
        {
            Build();
            return;
        }
        // Not a Loading grid: this shelf has its titles and only waits to be seen.
        body.Content = new Grid { Height = 290 };
        waiting[body] = Build;
    }

    /// <summary>
    /// Within a screen's height of the visible part of the page. Before the first
    /// layout nothing is near — FillVisible runs as soon as there is one, and
    /// building everything up front was the whole problem.
    /// </summary>
    bool NearScreen(FrameworkElement element)
    {
        if (!element.IsLoaded || scroll.ViewportHeight <= 0 || element.ActualHeight <= 0) return false;
        try
        {
            var top = element.TransformToAncestor(scroll).Transform(new Point(0, 0)).Y;
            var margin = scroll.ViewportHeight;
            return top < scroll.ViewportHeight + margin && top + element.ActualHeight > -margin;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    void FillVisible()
    {
        foreach (var (body, build) in waiting.ToList())
            if (body.Parent is FrameworkElement container && NearScreen(container)) build();
    }

    void BuildPersonal()
    {
        personal.Children.Clear();
        var resumable = shell.HistoryList.Where(InProgress).Select(item =>
        {
            var index = item.LastFileIndex!.Value;
            var duration = item.DurationOf(index);
            var position = item.PositionOf(index) ?? 0;
            return new ResumeTile(FromHistory(item), item, index, duration > 0 ? position / duration.Value * 100 : 0, ResumeStamp(item));
        }).ToList();
        if (resumable.Count > 0) personal.Children.Add(Section("Continue watching", Ui.Hues[0], () => shell.ShowTab("history"), resumable, "ResumeCard"));

        var later = Saved.InList(shell.SavedList, "later").Select(s => s.AsTitle()).ToList();
        if (later.Count > 0) personal.Children.Add(Section("Watch later", Ui.Hues[1], null, later, "PosterCard"));

        var favorites = Saved.InList(shell.SavedList, "favorite").Select(s => s.AsTitle()).ToList();
        if (favorites.Count > 0) personal.Children.Add(Section("Favourites", Ui.Hues[2], null, favorites, "PosterCard"));
    }

    static FrameworkElement Section(string title, string hue, Action? seeAll, System.Collections.IEnumerable items, string template) => new StackPanel
    {
        Margin = new Thickness(0, 28, 0, 0),
        Children = { Ui.Head(title, hue, seeAll), Ui.Rail(items, template, 290) },
    };

    void BuildShelves()
    {
        var watched = shell.HistoryList.Select(h => h.Imdb).Where(i => i is not null).ToHashSet();
        var all = AllShelves();

        var currentKeys = shelfControls.Keys.ToList();
        var newKeys = all.Select(s => s.Key).ToList();

        if (!currentKeys.SequenceEqual(newKeys))
        {
            shelves.Children.Clear();
            shelfControls.Clear();
            waiting.Clear();
            for (var i = 0; i < all.Count; i++)
            {
                var shelf = all[i];
                var hue = Ui.Hues[(i + 1) % Ui.Hues.Length];
                var body = new ContentControl();
                var captured = shelf;
                var head = Ui.Head(shelf.Heading, hue, () => MainWindow.Shell.OpenShelfPage(captured));
                var container = new StackPanel
                {
                    Margin = new Thickness(0, 28, 0, 0),
                    Tag = shelf.Genre ?? shelf.Heading,
                    Children = { head, body },
                };

                body.Content = new Grid { Height = 225, Tag = Loading, Children = { Ui.Spinner(20, "Faint") } };
                shelves.Children.Add(container);
                shelfControls[shelf.Key] = (container, body);
                Fill(shelf, container, body, watched);
            }
        }
        else
        {
            foreach (var shelf in all)
            {
                // Only a shelf still on its spinner — a built row is a Grid too, and
                // testing for that alone rebuilt every row on each refresh.
                if (shelfControls.TryGetValue(shelf.Key, out var pair) && pair.Body.Content is Grid { Tag: Loading })
                    Fill(shelf, pair.Container, pair.Body, watched);
            }
        }

        BuildHero(all, watched);
    }

    // ── Hero ────────────────────────────────────────────────────────────────

    List<Title> heroTitles = new();
    int heroPage;
    DispatcherTimer? heroTimer;
    string heroKey = "";

    void BuildHero(List<Shelf> all, HashSet<string?> watched)
    {
        var chosen = Chosen();
        var heroShelf = chosen.FirstOrDefault() ?? picks.FirstOrDefault() ?? Fixed[0];
        var heroFrom = loaded.ContainsKey(heroShelf.Key) ? heroShelf : Fixed[0];
        var source = loaded.GetValueOrDefault(heroFrom.Key) ?? new();
        // Today's picks too, but from the whole pool (not one genre's share), so the banner changes daily.
        var titles = Recommend.ForToday(source, heroFrom.Genre, taste, watched, onePerGenre: false).Take(5).ToList();
        var eyebrow = loaded.ContainsKey(heroShelf.Key)
            ? chosen.Count > 0 ? $"in {chosen[0].Genre}" : picks.Count > 0 ? "for you" : "today"
            : "today";
        var key = string.Join("|", titles.Select(t => t.Imdb)) + eyebrow + Theme.IsLight;
        if (key == heroKey) return;
        heroKey = key;
        heroTitles = titles;
        heroPage = 0;
        heroEyebrow = eyebrow;

        heroTimer?.Stop();
        if (titles.Count > 1)
        {
            heroTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
            heroTimer.Tick += (_, _) => { if (IsVisible) ShowHeroPage((heroPage + 1) % heroTitles.Count); };
            heroTimer.Start();
        }
        ShowHeroPage(0);
    }

    string heroEyebrow = "today";

    void ShowHeroPage(int index)
    {
        hero.Children.Clear();
        if (heroTitles.Count == 0)
        {
            hero.Height = 220;
            hero.Children.Add(Ui.Spinner());
            return;
        }
        hero.Height = 480;
        heroPage = index;
        var title = heroTitles[index];

        var art = new Border { Opacity = 0 };
        if (ImageUrl.Load(title.Backdrop ?? title.Poster, 1920) is { } image)
            art.Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top };
        hero.Children.Add(art);
        art.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(450)));

        hero.Children.Add(Ui.Brushed(new Border(), "FadeToBg"));
        hero.Children.Add(new Border { Width = 760, HorizontalAlignment = HorizontalAlignment.Left, Background = (Brush)FindResource("FadeLeft") });

        var facts = string.Join("   ", new[] { title.Rating is null ? null : $"★ {title.Rating}", title.Year, string.Join(" · ", title.Genres) }.Where(s => !string.IsNullOrEmpty(s)));
        var watch = Ui.Button(new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Children = { Ui.Glyph("play", 16, "OnAccent"), Ui.Text("Watch", "Label", "OnAccent", 14, FontWeights.SemiBold) },
        }, () => shell.OpenTitle(title), "PillAccent");
        ((StackPanel)watch.Content).Children[1].SetValue(MarginProperty, new Thickness(8, 0, 0, 0));
        watch.Height = 44;
        watch.Tag = 22;
        watch.Padding = new Thickness(24, 0, 24, 0);
        watch.HorizontalAlignment = HorizontalAlignment.Left;
        watch.Margin = new Thickness(0, 12, 0, 0);

        var name = Ui.Text(title.Name, "Display", "Text", 34);
        name.TextWrapping = TextWrapping.Wrap;
        name.MaxWidth = 640;
        // Left, not the default stretch — a capped width on a stretched block centres it.
        name.HorizontalAlignment = HorizontalAlignment.Left;
        var body = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(40, 0, 40, 56),
            Children =
            {
                Ui.Text($"#{index + 1} {heroEyebrow}".ToUpperInvariant(), "Overline", "Accent", 12, FontWeights.Bold),
                name,
                Ui.Text(facts, "Meta", "Accent", 13),
                watch,
            },
        };
        name.Margin = new Thickness(0, 6, 0, 6);
        hero.Children.Add(body);

        // Clicking anywhere on the art opens the title, like the phone's full-page Pressable.
        art.Cursor = Cursors.Hand;
        art.MouseLeftButtonUp += (_, _) => shell.OpenTitle(title);

        var dots = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 20) };
        for (var i = 0; i < heroTitles.Count; i++)
        {
            var at = i;
            var dot = Ui.Brushed(new Border { Height = 6, Width = i == index ? 18 : 6, CornerRadius = new CornerRadius(3), Margin = new Thickness(3, 0, 3, 0), Opacity = i == index ? 1 : 0.45, Cursor = Cursors.Hand }, i == index ? "Accent" : "Faint");
            dot.MouseLeftButtonUp += (_, e) => { e.Handled = true; ShowHeroPage(at); heroTimer?.Stop(); heroTimer?.Start(); };
            dots.Children.Add(dot);
        }
        hero.Children.Add(dots);

        if (heroTitles.Count > 1)
        {
            var prev = Ui.Round("chevron-left", () => { ShowHeroPage((heroPage + heroTitles.Count - 1) % heroTitles.Count); heroTimer?.Stop(); heroTimer?.Start(); }, "Text", "SurfaceAlt", 40, 20);
            var next = Ui.Round("chevron-right", () => { ShowHeroPage((heroPage + 1) % heroTitles.Count); heroTimer?.Stop(); heroTimer?.Start(); }, "Text", "SurfaceAlt", 40, 20);
            prev.HorizontalAlignment = HorizontalAlignment.Right;
            next.HorizontalAlignment = HorizontalAlignment.Right;
            prev.VerticalAlignment = next.VerticalAlignment = VerticalAlignment.Bottom;
            prev.Margin = new Thickness(0, 0, 88, 56);
            next.Margin = new Thickness(0, 0, 40, 56);
            prev.Opacity = next.Opacity = 0.85;
            hero.Children.Add(prev);
            hero.Children.Add(next);
        }
    }
}
