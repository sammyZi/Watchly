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
        new("Trending Movies", "movie"),
        new("Popular Series", "tv"),
        new("Anime", "tv", Meta.AnimeGenre),
    };

    /// <summary>A poster on Home's rows, and the gap between rows (mobile Home.kt).</summary>
    const double CardHeight = 210, RowGap = 22;

    readonly MainWindow shell = MainWindow.Shell;
    readonly ScrollViewer scroll;
    readonly StackPanel page = new() { Margin = new Thickness(0, 0, 0, 32) };
    readonly Grid hero = new() { Height = 480 };
    /// <summary>The wordmark: a fade over the hero, a solid bar once the rows scroll under it.</summary>
    readonly Border header = new() { VerticalAlignment = VerticalAlignment.Top, Padding = new Thickness(24, 14, 24, 14) };
    readonly StackPanel personal = new();
    readonly StackPanel shelves = new();
    readonly Dictionary<string, List<Title>> loaded = new();

    const string TasteKey = "home-taste";
    /// <summary>The genres you watch (see Meta.Taste), from last time until this session's is worked out.</summary>
    Dictionary<string, double> taste = Store.LoadOwn(TasteKey, new Dictionary<string, double>());

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
        scroll.ScrollChanged += (_, _) =>
        {
            FillVisible();
            Header();
        };
        scroll.SizeChanged += (_, _) => FillVisible();
        Loaded += (_, _) => Dispatcher.BeginInvoke(FillVisible, DispatcherPriority.Loaded);
        // The billboard fills most of the window, as it does a phone held sideways.
        SizeChanged += (_, e) => { if (heroTitles.Count > 0) hero.Height = HeroHeight(e.NewSize.Height); };

        page.Children.Add(hero);
        page.Children.Add(personal);
        page.Children.Add(shelves);
        header.Child = Ui.Text("WATCHLY", "TitleText", "Accent", 20, FontWeights.Black);
        Header();
        Content = new Grid { Children = { scroll, header } };

        foreach (var s in AllShelves())
            if (Meta.PeekCatalog(s.Kind, s.Genre) is { } ready) loaded[s.Key] = KidsMode.Only(ready);
        // A kids profile's rows filtered again once the family list is in.
        KidsMode.Changed += () => Dispatcher.BeginInvoke(() =>
        {
            if (!KidsMode.On) return;
            loaded.Clear();
            shelfControls.Clear();
            Refresh();
        });

        shell.LibraryChanged += Refresh;
        shell.SettingsChanged += Refresh;
        Net.OnlineChanged += () => Dispatcher.BeginInvoke(() => { if (Net.IsOnline) Refresh(); });
        // The player checkpoints history every few seconds; a hidden page must not
        // rebuild seven shelves each time. It catches up when it is shown again.
        IsVisibleChanged += (_, _) => { if (IsVisible && dirty) Refresh(); };
        Refresh();
    }

    bool dirty;
    bool solid;

    void Header()
    {
        var scrolled = scroll.VerticalOffset > 240;
        if (scrolled == solid && header.Background is not null) return;
        solid = scrolled;
        if (scrolled) header.SetResourceReference(Border.BackgroundProperty, "Bg");
        // To transparent black: Colors.Transparent is transparent white, and the fade through it reads as a grey band.
        else header.Background = new LinearGradientBrush(Color.FromArgb(0x99, 0, 0, 0), Color.FromArgb(0, 0, 0, 0), 90);
    }

    static double HeroHeight(double page) => Math.Max(420, page * 0.82);

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

    /// <summary>A kids profile's rows: family films and series, and the animation among them.</summary>
    static readonly Shelf[] KidsShelves = { new("Family films", "movie", "Family"), new("Family series", "tv", "Family"), new("Animation", "movie", "Animation") };

    List<Shelf> AllShelves()
    {
        if (KidsMode.On) return KidsShelves.ToList();
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
            try { Store.SaveOwn(TasteKey, taste); } catch { }
            if (mine != version) return;
            var next = genres.Select((g, i) => new Shelf(i == 0 ? $"Because you watched {g.Genre.ToLowerInvariant()}" : $"More {g.Genre.ToLowerInvariant()}", g.Kind, g.Genre)).ToList();
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
                var titles = KidsMode.Only(t.Result);
                if (titles.Count == 0) return;
                loaded[shelf.Key] = titles;
                var watched = shell.HistoryList.Select(h => h.Imdb).Where(i => i is not null).ToHashSet();
                if (shelfControls.TryGetValue(shelf.Key, out var pair) && pair.Body.Content is FrameworkElement { Tag: Loading })
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
            body.Content = Ui.Rail(Today(shelf, raw, watched), "RailCard", CardHeight);
        }
        if (NearScreen(container))
        {
            Build();
            return;
        }
        // Not a Loading grid: this shelf has its titles and only waits to be seen.
        body.Content = new Grid { Height = CardHeight };
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
        // One card per title: its newest started release or episode, while part-watched.
        var resumable = History.LatestPerTitle(shell.HistoryList).Where(InProgress).Select(item =>
        {
            var index = item.LastFileIndex!.Value;
            var duration = item.DurationOf(index);
            var position = item.PositionOf(index) ?? 0;
            return new ResumeTile(FromHistory(item), item, index, duration > 0 ? position / duration.Value * 100 : 0, ResumeStamp(item));
        }).ToList();
        // Left part-way somewhere this PC never saw — another device, before a reinstall: Simkl kept the spot.
        resumable.AddRange(KidsMode.Only(Simkl.Library.Paused.Where(p => shell.HistoryList.All(h => h.Imdb != p.Imdb)), p => new Title { Imdb = p.Imdb }).DistinctBy(p => p.Imdb).Take(10).Select(p =>
            new ResumeTile(
                new Title { Imdb = p.Imdb, Kind = p.Kind, Name = p.Title, Year = p.Year, Poster = $"https://images.metahub.space/poster/medium/{p.Imdb}/img" },
                new HistoryItem { Uri = $"simkl:{p.Imdb}", Title = p.Title, Imdb = p.Imdb, Kind = p.Kind },
                0, p.Progress,
                string.Join(" · ", new[] { p.Ep is { } e ? $"S{e.Season}:E{e.Episode}" : null, $"{(int)p.Progress}%" }.Where(s => s is not null)))));
        if (resumable.Count > 0) personal.Children.Add(Section("Continue Watching", () => shell.ShowTab("history"), resumable, "ResumeCard"));

        var later = Saved.InList(shell.SavedList, "later").Select(s => s.AsTitle()).ToList();
        if (later.Count > 0) personal.Children.Add(Section("My List", null, later, "RailCard"));

        var favorites = Saved.InList(shell.SavedList, "favorite").Select(s => s.AsTitle()).ToList();
        if (favorites.Count > 0) personal.Children.Add(Section("Liked", null, favorites, "RailCard"));
    }

    static FrameworkElement Section(string title, Action? seeAll, System.Collections.IEnumerable items, string template) => new StackPanel
    {
        Margin = new Thickness(0, RowGap, 0, 0),
        Children = { Ui.Head(title, seeAll), Ui.Rail(items, template, CardHeight) },
    };

    /// <summary>A row still waiting for its titles: grey posters where they will be.</summary>
    static FrameworkElement Placeholders()
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(24, 0, 0, 0), Height = CardHeight, Tag = Loading };
        for (var i = 0; i < 8; i++)
            row.Children.Add(Ui.Brushed(new Border { Width = 140, Margin = new Thickness(0, 0, 10, 0), CornerRadius = new CornerRadius(6) }, "Surface"));
        return row;
    }

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
            foreach (var shelf in all)
            {
                var body = new ContentControl();
                var captured = shelf;
                var head = Ui.Head(shelf.Heading, () => MainWindow.Shell.OpenShelfPage(captured));
                var container = new StackPanel
                {
                    Margin = new Thickness(0, RowGap, 0, 0),
                    Tag = shelf.Genre ?? shelf.Heading,
                    Children = { head, body },
                };

                body.Content = Placeholders();
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
                if (shelfControls.TryGetValue(shelf.Key, out var pair) && pair.Body.Content is FrameworkElement { Tag: Loading })
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
        var titles = Recommend.ForToday(source, heroFrom.Genre, taste, watched, onePerGenre: false).Take(6).ToList();
        var key = string.Join("|", titles.Select(t => t.Imdb)) + Theme.IsLight;
        if (key == heroKey) return;
        heroKey = key;
        heroTitles = titles;
        heroPage = 0;

        heroTimer?.Stop();
        if (titles.Count > 1)
        {
            // Moves on by itself, and waits again after every turn by hand (mobile Home.kt Hero).
            heroTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };
            heroTimer.Tick += (_, _) => { if (IsVisible) ShowHeroPage((heroPage + 1) % heroTitles.Count); };
            heroTimer.Start();
        }
        ShowHeroPage(0);
    }

    /// <summary>The full entries of hero titles: a shelf row carries no logo or runtime.</summary>
    readonly Dictionary<string, Title> heroFull = new();

    void Turn(int to)
    {
        ShowHeroPage((to + heroTitles.Count) % heroTitles.Count);
        heroTimer?.Stop();
        heroTimer?.Start();
    }

    void ShowHeroPage(int index)
    {
        hero.Children.Clear();
        if (heroTitles.Count == 0)
        {
            hero.Height = 220;
            hero.Children.Add(Ui.Spinner());
            return;
        }
        hero.Height = HeroHeight(ActualHeight);
        var again = heroPage == index && hero.Tag as string == heroTitles[index].Imdb;
        heroPage = index;
        var title = heroFull.GetValueOrDefault(heroTitles[index].Imdb) ?? heroTitles[index];
        hero.Tag = title.Imdb;
        if (!heroFull.ContainsKey(title.Imdb))
        {
            _ = Meta.LoadTitle(title.Imdb, title.Kind).ContinueWith(t => Dispatcher.BeginInvoke(() =>
            {
                if (t.Result is not { } full) return;
                heroFull[full.Imdb] = full;
                if (heroTitles.ElementAtOrDefault(heroPage)?.Imdb == full.Imdb) ShowHeroPage(heroPage);
            }), TaskScheduler.Default);
        }

        // The TV's billboard, as the phone shows it held sideways: the backdrop on the right,
        // faded into the page at its left and foot, and the words down the left beside it.
        var art = new Grid { Cursor = Cursors.Hand };
        Grid.SetColumn(art, 1);
        if (ImageUrl.Load(title.Backdrop ?? $"https://images.metahub.space/background/medium/{title.Imdb}/img", 1920) is { } image)
            art.Children.Add(new Border { Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top } });
        art.Children.Add(Ui.Brushed(new Border(), "FadeToBg"));
        art.Children.Add(Ui.Brushed(new Border(), "FadeLeft"));
        // Clicking anywhere on the art opens the title, like the phone's full-page Pressable.
        art.MouseLeftButtonUp += (_, _) => shell.OpenTitle(title);
        var stage = new Grid { ColumnDefinitions = { new ColumnDefinition { Width = new GridLength(28, GridUnitType.Star) }, new ColumnDefinition { Width = new GridLength(72, GridUnitType.Star) } }, Children = { art } };
        hero.Children.Add(stage);
        if (!again) art.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(450)));

        var mark = Ui.TitleMark(title, 88, 340, 34);
        var facts = Ui.Facts(title);
        facts.Margin = new Thickness(0, 12, 0, 0);
        var words = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Left,
            MaxWidth = 420,
            Margin = new Thickness(32, 40, 0, 0),
            Children = { mark, facts },
        };
        if (title.Plot is not null)
        {
            var plot = Ui.Text(title.Plot, "Body", "Text");
            plot.Opacity = 0.78;
            plot.MaxHeight = 66;
            plot.TextTrimming = TextTrimming.WordEllipsis;
            plot.Margin = new Thickness(0, 8, 0, 0);
            words.Children.Add(plot);
        }
        var inList = Saved.OnList(shell.SavedList, title.Imdb, "later");
        var list = Ui.Action(inList ? "In My List" : "My List", inList ? "check" : "plus", () => { shell.ToggleSaved(title, "later"); ShowHeroPage(heroPage); }, width: 132);
        list.Margin = new Thickness(10, 0, 0, 0);
        words.Children.Add(new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Margin = new Thickness(0, 16, 0, 0),
            Children = { Ui.Action("Watch", "play", () => shell.OpenTitle(title), primary: true, width: 132), list },
        });
        hero.Children.Add(words);

        var dots = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 8) };
        for (var i = 0; i < heroTitles.Count; i++)
        {
            var at = i;
            var dot = Ui.Brushed(new Border { Height = 4, Width = i == index ? 16 : 6, CornerRadius = new CornerRadius(2), Margin = new Thickness(3, 0, 3, 0), Cursor = Cursors.Hand }, i == index ? "Text" : "Faint");
            dot.MouseLeftButtonUp += (_, e) => { e.Handled = true; Turn(at); };
            dots.Children.Add(dot);
        }
        hero.Children.Add(dots);

        // The phone swipes; a mouse gets arrows, at the foot of the art.
        if (heroTitles.Count > 1)
        {
            var prev = Ui.Round("chevron-left", () => Turn(heroPage - 1), "Text", "Tint", 40, 20);
            var next = Ui.Round("chevron-right", () => Turn(heroPage + 1), "Text", "Tint", 40, 20);
            prev.HorizontalAlignment = next.HorizontalAlignment = HorizontalAlignment.Right;
            prev.VerticalAlignment = next.VerticalAlignment = VerticalAlignment.Bottom;
            prev.Margin = new Thickness(0, 0, 80, 24);
            next.Margin = new Thickness(0, 0, 32, 24);
            hero.Children.Add(prev);
            hero.Children.Add(next);
        }
    }
}
