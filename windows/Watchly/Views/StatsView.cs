using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// The phone's Your stats (StatsPage.kt): this year's hours month by month, the titles and genres watched most,
/// and the runs of days. From <see cref="WatchLog"/>, which starts counting with this version.
/// </summary>
public static class StatsView
{
    static readonly Color Orange = Color.FromRgb(0xF5, 0x9E, 0x0B), Blue = Color.FromRgb(0x3B, 0x82, 0xF6), Green = Color.FromRgb(0x22, 0xC5, 0x5E),
        Pink = Color.FromRgb(0xEC, 0x48, 0x99), Teal = Color.FromRgb(0x14, 0xB8, 0xA6);

    static Color Accent => ((SolidColorBrush)Application.Current.FindResource("Accent")).Color;
    static Color[] Palette => new[] { Accent, Blue, Green, Orange, Teal, Pink };

    public static FrameworkElement Build(MainWindow shell)
    {
        var log = WatchLog.Read();
        var stats = WatchStats.Of(log);
        var year = DateTime.Now.Year;
        var whole = Math.Max(1, stats.ThisYear);
        string Percent(double s) => $"{Math.Clamp((int)(s * 100 / whole), 1, 100)}%";
        var page = new StackPanel { MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Left };

        // The headline: the year, what it is made of, and its months.
        var head = new StackPanel();
        head.Children.Add(Ui.Text($"{year}", "Meta", "Accent", 13, FontWeights.Bold));
        head.Children.Add(Ui.Text(WatchStats.Hours(stats.ThisYear), "TitleText", "Text", 44, FontWeights.Bold));
        head.Children.Add(Ui.Text($"watched this year  ·  {stats.Shows} series  ·  {stats.Films} film{(stats.Films == 1 ? "" : "s")}", "Label", "Muted", 14));
        head.Children.Add(Months(stats.ByMonth));
        if (stats.AllTime <= 0) head.Children.Add(Spaced(Ui.Text("Watch something, and your stats start here.", "Meta", "Muted", 13), 14));
        page.Children.Add(Card(head));

        var tiles = new UniformGrid { Columns = 4, Margin = new Thickness(0, 0, 0, 22) };
        tiles.Children.Add(Tile("award", Orange, $"{stats.Streak}", stats.Streak == 1 ? "day streak" : "days streak"));
        tiles.Children.Add(Tile("check-circle", Blue, $"{stats.ActiveDays}", stats.ActiveDays == 1 ? "day watched" : "days watched"));
        tiles.Children.Add(Tile("clock", Green, WatchStats.Hours(stats.ThisMonth), "this month"));
        tiles.Children.Add(Tile("star", Pink, $"{stats.LongestStreak}", "longest streak"));
        page.Children.Add(tiles);

        if (stats.Top.Count > 0)
        {
            var rows = new StackPanel();
            for (var i = 0; i < stats.Top.Count; i++)
            {
                var (name, kind, seconds, id) = stats.Top[i];
                var poster = shell.HistoryList.FirstOrDefault(h => h.Imdb == id)?.Poster ?? shell.SavedList.FirstOrDefault(s => s.Imdb == id)?.Poster;
                var number = Ui.Text($"{i + 1}", "TitleText", i == 0 ? null : "Faint", 18, FontWeights.Bold);
                if (i == 0) number.Foreground = new SolidColorBrush(Orange);
                number.Width = 28;
                number.VerticalAlignment = VerticalAlignment.Center;
                var art = Ui.Art(poster, name, 44, 66, 6);
                var words = new StackPanel { Margin = new Thickness(14, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
                words.Children.Add(Ui.Text(name, "Label", "Text", 15, FontWeights.SemiBold));
                words.Children.Add(Ui.Text($"{(kind == "tv" ? "Series" : "Film")}  ·  {WatchStats.Hours(seconds)}  ·  {Percent(seconds)} of your year", "Meta", "Muted", 13));
                words.Children.Add(Spaced(Meter(seconds / whole, Palette[i % Palette.Length]), 8));
                var row = new DockPanel { Margin = new Thickness(0, i == 0 ? 0 : 14, 0, 0) };
                DockPanel.SetDock(number, Dock.Left);
                DockPanel.SetDock(art, Dock.Left);
                row.Children.Add(number);
                row.Children.Add(art);
                row.Children.Add(words);
                rows.Children.Add(row);
            }
            page.Children.Add(Block("Most watched", "star", Orange, rows));
        }

        // Genres are a metadata lookup each: filled in when they come.
        var genres = new StackPanel();
        var genreBlock = Block("Favourite genres", "film", Accent, genres);
        genreBlock.Visibility = Visibility.Collapsed;
        page.Children.Add(genreBlock);
        _ = FillGenres();
        async Task FillGenres()
        {
            var list = await WatchStats.Genres(log);
            if (list.Count == 0) return;
            for (var i = 0; i < list.Count; i++)
            {
                var (genre, seconds) = list[i];
                var colour = Palette[i % Palette.Length];
                var dot = new Border { Width = 10, Height = 10, CornerRadius = new CornerRadius(5), Background = new SolidColorBrush(colour), VerticalAlignment = VerticalAlignment.Center };
                var amount = Ui.Text($"{Percent(seconds)}  ·  {WatchStats.Hours(seconds)}", "Meta", "Muted", 13);
                DockPanel.SetDock(dot, Dock.Left);
                DockPanel.SetDock(amount, Dock.Right);
                var label = Ui.Text(genre, "Label", "Text", 15);
                label.Margin = new Thickness(10, 0, 0, 0);
                var line = new DockPanel { Children = { dot, amount, label } };
                genres.Children.Add(new StackPanel { Margin = new Thickness(0, i == 0 ? 0 : 14, 0, 0), Children = { line, Spaced(Meter(seconds / whole, colour), 8) } });
            }
            genreBlock.Visibility = Visibility.Visible;
        }

        var habits = new StackPanel();
        if (stats.BusiestWeekday is { } day) habits.Children.Add(Fact("You watch most on", day));
        habits.Children.Add(Fact("All time", WatchStats.Hours(stats.AllTime)));
        if (log.Base > 0) habits.Children.Add(Spaced(Ui.Text($"All time includes about {WatchStats.Hours(log.Base)} from before this log began.", "Meta", "Faint", 12), 8));
        page.Children.Add(Block("Habits", "sliders", Blue, habits));
        return page;
    }

    static T Spaced<T>(T e, double top) where T : FrameworkElement
    {
        e.Margin = new Thickness(0, top, 0, 0);
        return e;
    }

    static Border Card(UIElement content)
    {
        var card = Ui.Card(content, "Surface", 18, new Thickness(22));
        card.Margin = new Thickness(0, 0, 0, 14);
        return card;
    }

    static FrameworkElement Block(string title, string icon, Color colour, UIElement content)
    {
        var glyph = new Icon { Kind = icon, Size = 18, Foreground = new SolidColorBrush(colour), VerticalAlignment = VerticalAlignment.Center };
        var heading = Ui.Text("  " + title, "TitleText", "Text", 18, FontWeights.Bold);
        return new StackPanel
        {
            Margin = new Thickness(0, 0, 0, 22),
            Children = { new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 10), Children = { glyph, heading } }, Ui.Card(content, "Surface", 16, new Thickness(18)) },
        };
    }

    static FrameworkElement Tile(string icon, Color colour, string value, string label)
    {
        var mark = new Border
        {
            Width = 32, Height = 32, CornerRadius = new CornerRadius(16), HorizontalAlignment = HorizontalAlignment.Left,
            Background = new SolidColorBrush(Color.FromArgb(0x38, colour.R, colour.G, colour.B)),
            Child = new Icon { Kind = icon, Size = 17, Foreground = new SolidColorBrush(colour), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
        };
        return new Border
        {
            CornerRadius = new CornerRadius(16), Padding = new Thickness(14), Margin = new Thickness(0, 0, 10, 0),
            Background = new SolidColorBrush(Color.FromArgb(0x24, colour.R, colour.G, colour.B)),
            Child = new StackPanel { Children = { mark, Spaced(Ui.Text(value, "TitleText", "Text", 20, FontWeights.Bold), 10), Ui.Text(label, "Meta", "Muted", 13) } },
        };
    }

    /// <summary>A bar as long as its share of the year.</summary>
    static FrameworkElement Meter(double share, Color colour)
    {
        var fill = new Border { Height = 6, CornerRadius = new CornerRadius(3), Background = new SolidColorBrush(colour), HorizontalAlignment = HorizontalAlignment.Left };
        var track = Ui.Brushed(new Border { Height = 6, CornerRadius = new CornerRadius(3), Child = fill }, "SurfaceAlt");
        track.SizeChanged += (_, e) => fill.Width = e.NewSize.Width * Math.Clamp(share, 0.03, 1);
        return track;
    }

    static FrameworkElement Fact(string label, string value)
    {
        var v = Ui.Text(value, "Label", "Text", 15, FontWeights.Bold);
        DockPanel.SetDock(v, Dock.Right);
        return new DockPanel { Margin = new Thickness(0, 6, 0, 6), Children = { v, Ui.Text(label, "Label", "Muted", 15) } };
    }

    /// <summary>The twelve months as columns of height by hours; this month in the accent.</summary>
    static FrameworkElement Months(double[] byMonth)
    {
        var most = Math.Max(1, byMonth.Max());
        var now = DateTime.Now.Month - 1;
        var bars = new UniformGrid { Columns = 12, Height = 64, Margin = new Thickness(0, 20, 0, 0) };
        var letters = new UniformGrid { Columns = 12, Margin = new Thickness(0, 6, 0, 0) };
        for (var i = 0; i < 12; i++)
        {
            var bar = Ui.Brushed(new Border { Height = 4 + 60 * (byMonth[i] / most), CornerRadius = new CornerRadius(4), Margin = new Thickness(3, 0, 3, 0), VerticalAlignment = VerticalAlignment.Bottom }, i == now ? "Accent" : "SurfaceAlt");
            bars.Children.Add(bar);
            var letter = Ui.Text("JFMAMJJASOND"[i].ToString(), "Meta", i == now ? "Text" : "Faint", 11);
            letter.HorizontalAlignment = HorizontalAlignment.Center;
            letters.Children.Add(letter);
        }
        return new StackPanel { Children = { bars, letters } };
    }
}
