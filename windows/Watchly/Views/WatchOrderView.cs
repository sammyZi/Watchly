using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>What the watch-order pages, the home rail and a title's page share — mobile WatchOrder.kt and TmdbUi.kt.</summary>
public static class OrderUi
{
    const double Round = 10;

    public static TextBlock White(string text, string style, double size, FontWeight? weight = null, double alpha = 1)
    {
        var t = Ui.Text(text, style, null, size, weight);
        t.Foreground = Brushes.White;
        t.Opacity = alpha;
        return t;
    }

    public static Border Layer(Brush brush) => new() { CornerRadius = new CornerRadius(Round), Background = brush };

    static LinearGradientBrush Fade(Point from, Point to, params (byte Alpha, double At)[] stops)
    {
        var brush = new LinearGradientBrush { StartPoint = from, EndPoint = to };
        foreach (var (alpha, at) in stops) brush.GradientStops.Add(new GradientStop(Color.FromArgb(alpha, 0, 0, 0), at));
        brush.Freeze();
        return brush;
    }

    public static readonly Brush FootFade = Fade(new Point(0, 0), new Point(0, 1), (0, 0.3), (0xEB, 1));
    static readonly Brush SideFade = Fade(new Point(0, 0), new Point(1, 0), (0xF0, 0), (0xB8, 0.55), (0x40, 1));
    static readonly Brush PosterFade = Fade(new Point(0, 0), new Point(0, 1), (0, 0.55), (0xD9, 1));

    /// <summary>A row the wheel passes through, dragged like a shelf.</summary>
    public static ScrollViewer Strip(IEnumerable<UIElement> items)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var item in items) row.Children.Add(item);
        var strip = new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, Content = row };
        Ui.PassWheel(strip);
        Ui.DragScroll(strip);
        return strip;
    }

    /// <summary>A small label over a film's art.</summary>
    public static Border Tag(string text, Brush background, Brush words)
    {
        var label = Ui.Text(text, "Overline", null, 11);
        label.Foreground = words;
        return new Border { CornerRadius = new CornerRadius(5), Padding = new Thickness(7, 3, 7, 3), Background = background, Child = label, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };
    }

    /// <summary>The first posters, fanned like cards in a hand; the first film lies on top.</summary>
    static FrameworkElement Fanned(WatchOrder order)
    {
        var spread = new[] { 0.0, -10, 10 };
        var hand = new Grid { Width = 124, Height = 110, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 6, 0) };
        var count = Math.Min(3, order.Films.Count);
        for (var i = count - 1; i >= 0; i--)
        {
            var art = Ui.Art(WatchOrder.Poster(order.Films[i]), order.Name, 62, 93, 5, 124);
            art.RenderTransformOrigin = new Point(0.5, 0.5);
            art.RenderTransform = new TransformGroup { Children = { new RotateTransform(spread[i]), new TranslateTransform(spread[i] * 2.6, i == 0 ? 0 : 5) } };
            hand.Children.Add(art);
        }
        return hand;
    }

    /// <summary>A banner: the franchise's art darkened to the left under its name, its posters on the right.</summary>
    public static Button Banner(WatchOrder order, HashSet<string> seen, double width = double.NaN)
    {
        var done = order.Films.Count(seen.Contains);
        var box = new Grid { Height = 132, Width = width };
        box.Children.Add(Layer(Brushes.Black));
        if (ImageUrl.Load(order.Art, 640) is { } art) box.Children.Add(Layer(new ImageBrush(art) { Stretch = Stretch.UniformToFill }));
        box.Children.Add(Layer(SideFade));
        var name = White(order.Name, "TitleText", 17, FontWeights.Bold);
        name.TextWrapping = TextWrapping.Wrap;
        name.MaxHeight = 46;
        var line = White(WatchOrders.Line(order, seen), "Meta", 12, alpha: 0.72);
        line.Margin = new Thickness(0, 4, 0, 0);
        box.Children.Add(new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(16, 0, 134, 0), Children = { name, line } });
        box.Children.Add(Fanned(order));
        if (done > 0)
        {
            var bar = Ui.Bar(done * 100.0 / order.Films.Count);
            bar.VerticalAlignment = VerticalAlignment.Bottom;
            bar.Margin = new Thickness(Round, 0, Round, 0);
            box.Children.Add(bar);
        }
        var button = Ui.Button(box, () => MainWindow.Shell.OpenOrder(order));
        button.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        return button;
    }

    /// <summary>A film's place in its order, as a poster: its number on it, a tick once seen, a ring on the one open now.</summary>
    public static Button Poster(int number, string imdb, bool here, bool watched, Action onClick)
    {
        var box = new Grid { Width = 100, Height = 150 };
        box.Children.Add(Ui.Art(WatchOrder.Poster(imdb), $"Film {number}", 100, 150, Round));
        box.Children.Add(Layer(PosterFade));
        if (watched && !here) box.Children.Add(Layer(new SolidColorBrush(Color.FromArgb(0x66, 0, 0, 0))));
        var digits = White($"{number}", "TitleText", 22, FontWeights.Black);
        digits.VerticalAlignment = VerticalAlignment.Bottom;
        digits.Margin = new Thickness(8, 0, 0, 4);
        box.Children.Add(digits);
        if (watched)
        {
            var tick = Ui.Brushed(new Border { Width = 20, Height = 20, CornerRadius = new CornerRadius(10), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(6), Child = Ui.Glyph("check", 13, "Bg") }, "Good");
            box.Children.Add(tick);
        }
        if (here) box.Children.Add(new Border { CornerRadius = new CornerRadius(Round), BorderBrush = Brushes.White, BorderThickness = new Thickness(2) });
        var button = Ui.Button(box, onClick);
        button.Margin = new Thickness(0, 0, 10, 0);
        return button;
    }

    /// <summary>A number large and outlined, as Netflix's Top 10 sets its ranks; WPF text has no outline of its own.</summary>
    public static Path Outlined(string digits, double size, Visual here)
    {
        var text = new FormattedText(digits, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.Black, FontStretches.Normal), size, Brushes.White, VisualTreeHelper.GetDpi(here).PixelsPerDip);
        return new Path { Data = text.BuildGeometry(new Point()), Stroke = new SolidColorBrush(Color.FromArgb(0xC0, 0xff, 0xff, 0xff)), StrokeThickness = 1.6 };
    }

    /// <summary>One suggestion, big: its backdrop and logo, its IMDb rating, and which of the viewer's titles it came from.</summary>
    public static Button PickCard(ForYouPick pick)
    {
        var t = pick.Title;
        var box = new Grid { Width = 310, Height = 174 };
        box.Children.Add(Layer(Brushes.Black));
        if (ImageUrl.Load(t.Wide, 640) is { } art) box.Children.Add(Layer(new ImageBrush(art) { Stretch = Stretch.UniformToFill }));
        box.Children.Add(Layer(FootFade));
        var mark = Ui.TitleMark(t, 42, 270, 18, Brushes.White);
        mark.Margin = new Thickness(0, 0, 0, 6);
        var why = new StackPanel { Orientation = Orientation.Horizontal };
        if (t.Rating is not null)
        {
            var imdb = new ContentPresenter { Content = t, ContentTemplate = (DataTemplate)Application.Current.FindResource("ImdbMark"), Margin = new Thickness(-6, -6, 2, -6) };
            why.Children.Add(imdb);
        }
        var line = White($"Because you watched {pick.Because}", "Meta", 12, alpha: 0.78);
        line.VerticalAlignment = VerticalAlignment.Center;
        line.MaxWidth = 210;
        why.Children.Add(line);
        box.Children.Add(new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(12), Children = { mark, why } });
        var button = Ui.Button(box, () => MainWindow.Shell.OpenTitle(t), tooltip: t.Name);
        button.Margin = new Thickness(0, 0, 10, 0);
        return button;
    }

    /// <summary>A chart's poster, standing on its rank.</summary>
    public static FrameworkElement Ranked(int rank, Title title, Visual here)
    {
        var digits = Outlined($"{rank}", 92, here);
        digits.HorizontalAlignment = HorizontalAlignment.Left;
        digits.VerticalAlignment = VerticalAlignment.Bottom;
        var card = new ContentPresenter { Content = title, ContentTemplate = (DataTemplate)Application.Current.FindResource("RailCard"), Margin = new Thickness(rank < 10 ? 44 : 84, 0, 0, 0) };
        return new Grid { Height = 210, Children = { digits, card } };
    }

    /// <summary>A TMDB title: opening it asks TMDB for its IMDb id first, the one thing the rest of the app goes by.</summary>
    public static Button Card(TmdbCard card)
    {
        var name = Ui.Text(card.Title, "Meta", "Text", 13, FontWeights.SemiBold);
        name.Margin = new Thickness(0, 6, 0, 0);
        var column = new StackPanel { Width = 140, Children = { Ui.Art(card.Poster, card.Title, 140, 210, 6), name } };
        if (card.Year is not null) column.Children.Add(Ui.Text(card.Year, "Meta", "Faint", 12));
        var button = Ui.Button(column, () => Open(card), tooltip: card.Title);
        button.Margin = new Thickness(0, 0, 10, 0);
        button.VerticalAlignment = VerticalAlignment.Top;
        return button;
    }

    static async void Open(TmdbCard card)
    {
        if (await Tmdb.ImdbOf(card) is { } imdb) MainWindow.Shell.OpenTitle(new Title { Imdb = imdb, Kind = card.Kind, Name = card.Title, Year = card.Year, Poster = card.Poster });
        else MainWindow.Shell.Toast($"Couldn't open {card.Title}", true);
    }
}

/// <summary>Every franchise, with a box to find one.</summary>
public sealed class WatchOrdersView : UserControl
{
    public WatchOrdersView()
    {
        var (root, _, body) = Ui.Screen("Watch orders", () => MainWindow.Shell.CloseLayer(this));
        var seen = WatchOrders.Seen(MainWindow.Shell.HistoryList);
        var note = Ui.Text($"{WatchOrders.All.Count} franchises, in the right order", "Meta", "Faint", 13);
        note.Margin = new Thickness(24, 0, 24, 12);
        var (box, input, _) = Ui.SearchField("Find a watch order", 44);
        box.Margin = new Thickness(24, 0, 24, 14);
        box.MaxWidth = 560;
        box.HorizontalAlignment = HorizontalAlignment.Left;
        box.Width = 560;
        var grid = new ColumnWrap { MinColumn = 360 };
        void Show()
        {
            grid.Children.Clear();
            foreach (var order in WatchOrders.All.Where(o => o.Matches(input.Text)))
            {
                var banner = OrderUi.Banner(order, seen);
                banner.Margin = new Thickness(0, 0, 12, 12);
                grid.Children.Add(banner);
            }
        }
        input.TextChanged += (_, _) => Show();
        Show();
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(24, 0, 12, 32), Content = grid };
        Ui.SmoothWheel(scroll);
        var page = new DockPanel();
        DockPanel.SetDock(note, Dock.Top);
        DockPanel.SetDock(box, Dock.Top);
        page.Children.Add(note);
        page.Children.Add(box);
        page.Children.Add(scroll);
        body.Content = page;
        Content = root;
    }
}

/// <summary>One franchise: art for the film up next, how far along, and every film, as a film.</summary>
public sealed class WatchOrderView : UserControl, IDisposable
{
    readonly MainWindow shell = MainWindow.Shell;
    readonly WatchOrder order;
    readonly ScrollViewer scroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    readonly StackPanel content = new() { Margin = new Thickness(32, 72, 32, 40) };
    readonly Border backdrop = new();
    Dictionary<string, Title> loaded = new();
    bool byStory;
    string? backdropUrl;

    public WatchOrderView(WatchOrder order)
    {
        this.order = order;
        var stage = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition { Width = new GridLength(30, GridUnitType.Star) }, new ColumnDefinition { Width = new GridLength(70, GridUnitType.Star) } },
            RowDefinitions = { new RowDefinition { Height = new GridLength(60, GridUnitType.Star) }, new RowDefinition { Height = new GridLength(40, GridUnitType.Star) } },
        };
        var art = Ui.FadedArt(backdrop);
        Grid.SetColumn(art, 1);
        stage.Children.Add(art);
        scroll.Content = content;
        Ui.SmoothWheel(scroll);
        var back = Ui.Round("arrow-left", () => shell.CloseLayer(this), "Text", "Tint", 40, 20, "Back");
        ((Icon)back.Content).Foreground = Brushes.White;
        back.Background = new SolidColorBrush(Color.FromArgb(0x80, 0, 0, 0));
        back.HorizontalAlignment = HorizontalAlignment.Left;
        back.VerticalAlignment = VerticalAlignment.Top;
        back.Margin = new Thickness(20, 16, 0, 0);
        Content = new Grid { Children = { stage, scroll, back } };

        shell.LibraryChanged += Render;
        Render();
        Load();
    }

    public void Dispose() => shell.LibraryChanged -= Render;

    /// <summary>By id, so changing the order needs no second load. A film Cinemeta won't answer for is a bare card.</summary>
    async void Load()
    {
        var found = await Task.WhenAll(order.Films.Select(id => Meta.LoadTitle(id, "movie")));
        loaded = found.OfType<Title>().GroupBy(t => t.Imdb).ToDictionary(g => g.Key, g => g.First());
        Render();
    }

    void Render()
    {
        var offset = scroll.VerticalOffset;
        content.Children.Clear();
        var seen = WatchOrders.Seen(shell.HistoryList);
        var ids = byStory && order.Story.Count > 0 ? order.Story : order.Films;
        var next = ids.FirstOrDefault(id => !seen.Contains(id));
        var done = ids.Count(seen.Contains);

        var url = loaded.GetValueOrDefault(next ?? ids[0])?.Wide ?? order.Art;
        if (url != backdropUrl && ImageUrl.Load(url, 1920) is { } image)
        {
            backdropUrl = url;
            backdrop.Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top };
        }

        var head = new StackPanel { Width = 460, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 0, 0, 22) };
        var name = Ui.Text(order.Name, "Display", "Text", 30);
        name.TextWrapping = TextWrapping.Wrap;
        head.Children.Add(name);
        var line = Ui.Text(string.Join("  ·  ", new[] { $"{ids.Count} films", WatchOrders.TotalRuntime(loaded.Values), order.More ? "more coming" : null }.Where(s => s is not null)), "Meta", "Muted", 13);
        line.Margin = new Thickness(0, 6, 0, 0);
        head.Children.Add(line);
        if (done > 0)
        {
            var bar = Ui.Bar(done * 100.0 / ids.Count);
            bar.Margin = new Thickness(0, 14, 0, 0);
            head.Children.Add(bar);
            var progress = Ui.Text(next is null ? "All watched" : $"{done} of {ids.Count} watched", "Meta", next is null ? "Good" : "Faint", 12);
            progress.Margin = new Thickness(0, 6, 0, 0);
            head.Children.Add(progress);
        }
        if (order.Story.Count > 0)
        {
            var chips = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
            var release = Ui.Chip("Release order", !byStory, () => { byStory = false; Render(); });
            release.Margin = new Thickness(0, 0, 8, 0);
            chips.Children.Add(release);
            chips.Children.Add(Ui.Chip("Story order", byStory, () => { byStory = true; Render(); }));
            head.Children.Add(chips);
        }
        if (next is not null && loaded.TryGetValue(next, out var up))
        {
            var play = Ui.Action($"Next up: {up.Name}", "play", () => shell.OpenTitle(up), primary: true);
            play.Margin = new Thickness(0, 14, 0, 0);
            head.Children.Add(play);
        }
        content.Children.Add(head);

        var films = new ColumnWrap { MinColumn = 380 };
        for (var i = 0; i < ids.Count; i++)
        {
            var card = FilmCard(i + 1, loaded.GetValueOrDefault(ids[i]) ?? new Title { Imdb = ids[i] }, seen.Contains(ids[i]), ids[i] == next);
            card.Margin = new Thickness(0, 0, 14, 14);
            films.Children.Add(card);
        }
        content.Children.Add(films);

        // TMDB's terms ask for these words wherever its data shows.
        if (order.FromTmdb)
        {
            var credit = Ui.Text("Collection data from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.", "Meta", "Faint", 11);
            credit.TextWrapping = TextWrapping.Wrap;
            content.Children.Add(credit);
        }
        Dispatcher.BeginInvoke(() => scroll.ScrollToVerticalOffset(offset), System.Windows.Threading.DispatcherPriority.Loaded);
    }

    /// <summary>One film, as a film: its backdrop, its own title logo, its number. All on art, so white in either theme.</summary>
    Button FilmCard(int number, Title title, bool watched, bool next)
    {
        var box = new Grid { Height = 208 };
        box.Children.Add(OrderUi.Layer(Brushes.Black));
        if (ImageUrl.Load(title.Wide, 760) is { } art) box.Children.Add(OrderUi.Layer(new ImageBrush(art) { Stretch = Stretch.UniformToFill }));
        box.Children.Add(OrderUi.Layer(OrderUi.FootFade));
        if (watched) box.Children.Add(OrderUi.Layer(new SolidColorBrush(Color.FromArgb(0x73, 0, 0, 0))));
        var rank = OrderUi.Outlined($"{number}", 64, this);
        rank.HorizontalAlignment = HorizontalAlignment.Right;
        rank.VerticalAlignment = VerticalAlignment.Top;
        rank.Margin = new Thickness(0, 2, 14, 0);
        box.Children.Add(rank);
        // No red: the art carries the colour, and the page's one red is the Next up button.
        var tag = next ? OrderUi.Tag("UP NEXT", new SolidColorBrush(Color.FromArgb(0x8C, 0, 0, 0)), Brushes.White)
            : watched ? OrderUi.Tag("WATCHED", (Brush)FindResource("Good"), Brushes.Black) : null;
        if (tag is not null)
        {
            tag.Margin = new Thickness(12);
            box.Children.Add(tag);
        }
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(14, 0, 14, 12) };
        if (title.Name.Length > 0)
        {
            var mark = Ui.TitleMark(title, 46, 260, 19, Brushes.White);
            mark.Margin = new Thickness(0, 0, 0, 6);
            words.Children.Add(mark);
        }
        var facts = new StackPanel { Orientation = Orientation.Horizontal };
        var when = string.Join("  ·  ", new[] { title.Year?[..Math.Min(4, title.Year.Length)], title.Runtime is { } r ? Format.Runtime(r) : null }.Where(s => s is not null));
        facts.Children.Add(OrderUi.White(when, "Meta", 12, alpha: 0.8));
        if (title.Rating is not null)
        {
            var imdb = OrderUi.Tag("IMDb", (Brush)FindResource("Imdb"), Brushes.Black);
            imdb.Margin = new Thickness(10, 0, 5, 0);
            facts.Children.Add(imdb);
            facts.Children.Add(OrderUi.White(title.Rating, "Meta", 12, FontWeights.SemiBold));
        }
        words.Children.Add(facts);
        box.Children.Add(words);
        var button = Ui.Button(box, () => { if (title.Name.Length > 0) shell.OpenTitle(title); });
        button.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        return button;
    }
}

/// <summary>The best by IMDb rating: films or series, the viewer's genres or any one, and how high to start (mobile ForYou.kt TopScreen).</summary>
public sealed class TopView : UserControl
{
    /// <summary>Stands for a genre: all the viewer's chosen ones together.</summary>
    const string YourGenres = "your genres";
    static readonly (double? Min, string Label)[] Minimums = { (null, "Any rating"), (7, "IMDb 7+"), (8, "IMDb 8+"), (9, "IMDb 9+") };

    readonly List<string> mine = MainWindow.Shell.Settings.Genres.ToList();
    readonly WrapPanel chips = new() { Margin = new Thickness(24, 0, 24, 8) };
    readonly ColumnWrap grid = new() { MinColumn = 150 };
    readonly ContentControl status = new() { Focusable = false };
    string kind;
    string? genre;
    double? atLeast;
    List<Title>? titles;
    int latest;

    public TopView(string kind)
    {
        this.kind = kind;
        genre = mine.Count > 0 ? YourGenres : null;
        var (root, _, body) = Ui.Screen("Top on IMDb", () => MainWindow.Shell.CloseLayer(this));
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(24, 8, 24, 40), Content = new StackPanel { Children = { status, grid } } };
        Ui.SmoothWheel(scroll);
        var page = new DockPanel();
        DockPanel.SetDock(chips, Dock.Top);
        page.Children.Add(chips);
        page.Children.Add(scroll);
        body.Content = page;
        Content = root;
        Load();
    }

    void Add(string label, bool on, Action pick)
    {
        var chip = Ui.Chip(label, on, pick);
        chip.Margin = new Thickness(0, 0, 8, 6);
        chips.Children.Add(chip);
    }

    void Chips()
    {
        chips.Children.Clear();
        void Kind(string to) { kind = to; Load(); }
        void Genre(string? to) { genre = to; Load(); }
        Add("Films", kind == "movie", () => Kind("movie"));
        Add("Series", kind == "tv", () => Kind("tv"));
        foreach (var (min, label) in Minimums) Add(label, atLeast == min, () => { atLeast = min; Render(); });
        var rule = Ui.Rule();
        rule.Margin = new Thickness(4, 8, 12, 0);
        rule.VerticalAlignment = VerticalAlignment.Top;
        chips.Children.Add(rule);
        if (mine.Count > 0) Add("Your genres", genre == YourGenres, () => Genre(YourGenres));
        Add("All genres", genre is null, () => Genre(null));
        foreach (var g in mine.Concat(Meta.Genres).Distinct()) Add(g, genre == g, () => Genre(g));
    }

    async void Load()
    {
        var ticket = ++latest;
        titles = null;
        Render();
        var found = genre == YourGenres ? await Meta.TopOf(kind, mine) : await Meta.LoadTop(kind, genre);
        if (ticket != latest) return;
        titles = KidsMode.Only(found);
        Render();
    }

    void Render()
    {
        Chips();
        grid.Children.Clear();
        status.Content = null;
        if (titles is null)
        {
            status.Content = new Grid { Children = { Ui.Spinner() }, Margin = new Thickness(0, 32, 0, 0) };
            return;
        }
        var shown = titles.Where(t => atLeast is null || (t.Score ?? 0) >= atLeast).ToList();
        if (shown.Count == 0) status.Content = Ui.Empty("search", "Nothing rated that high here", "Try a lower rating, or another genre.");
        for (var i = 0; i < shown.Count; i++)
        {
            var rank = OrderUi.Tag($"{i + 1}", new SolidColorBrush(Color.FromArgb(0xB8, 0, 0, 0)), Brushes.White);
            rank.Margin = new Thickness(6);
            rank.IsHitTestVisible = false;
            grid.Children.Add(new Grid
            {
                Margin = new Thickness(0, 0, 0, 16),
                HorizontalAlignment = HorizontalAlignment.Left,
                Children = { new ContentPresenter { Content = shown[i], ContentTemplate = (DataTemplate)Application.Current.FindResource("PosterCard") }, rank },
            });
        }
    }
}

/// <summary>One person: their photo, a little about them, and what they're in, best known first.</summary>
public sealed class PersonView : UserControl
{
    bool more;

    public PersonView(int id, string name)
    {
        var (root, _, body) = Ui.Screen(name, () => MainWindow.Shell.CloseLayer(this));
        body.Content = new Grid { Children = { Ui.Spinner() }, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 32, 0, 0) };
        Content = root;
        Load(id, name, body);
    }

    async void Load(int id, string name, ContentControl body)
    {
        if (await Tmdb.Person(id) is not { } person)
        {
            body.Content = Ui.Empty("wifi-off", "Could not load this page", "Check the connection and try again.");
            return;
        }
        var page = new StackPanel();
        var words = new StackPanel { Margin = new Thickness(18, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, MaxWidth = 760 };
        var line = string.Join("  ·  ", new[] { person.KnownFor, person.Credits.Count > 0 ? $"{person.Credits.Count} titles" : null }.Where(s => s is not null));
        if (line.Length > 0) words.Children.Add(Ui.Text(line, "Meta", "Faint", 13));
        if (person.About is { } about)
        {
            var text = Ui.Text(about, "Body", "Muted", 14);
            text.TextWrapping = TextWrapping.Wrap;
            text.MaxHeight = 118;
            var open = Ui.Button(text, () =>
            {
                more = !more;
                text.MaxHeight = more ? double.PositiveInfinity : 118;
            });
            open.Margin = new Thickness(0, 8, 0, 0);
            words.Children.Add(open);
        }
        var head = new DockPanel { Margin = new Thickness(0, 0, 0, 18) };
        var photo = Ui.Art(person.Photo, name, 120, 180, 10);
        DockPanel.SetDock(photo, Dock.Left);
        head.Children.Add(photo);
        head.Children.Add(words);
        page.Children.Add(head);
        var heading = Ui.Text("Known for", "TitleText", "Text", 17, FontWeights.Bold);
        heading.Margin = new Thickness(0, 0, 0, 12);
        page.Children.Add(heading);
        var grid = new ColumnWrap { MinColumn = 150 };
        foreach (var card in person.Credits)
        {
            var button = OrderUi.Card(card);
            button.Margin = new Thickness(0, 0, 0, 16);
            grid.Children.Add(button);
        }
        page.Children.Add(grid);
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(24, 4, 24, 40), Content = page };
        Ui.SmoothWheel(scroll);
        body.Content = scroll;
    }
}
