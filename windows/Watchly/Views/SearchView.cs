using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Watchly.Controls;
using Watchly.Core;
using Watchly.Engine;

namespace Watchly.Views;

/// <summary>
/// src/screens/SearchScreen.tsx: titles first (posters from Cinemeta), with a
/// Torrents switch that searches the indexes by release name instead.
/// </summary>
public sealed class SearchView : UserControl
{
    static readonly string[] BrowseGenres = { Meta.AnimeGenre, "Action", "Comedy", "Drama", "Thriller", "Horror", "Sci-Fi", "Animation" };

    readonly MainWindow shell = MainWindow.Shell;
    readonly TextBox input;
    readonly ContentControl trailing;
    readonly WrapPanel recentRow = new() { Margin = new Thickness(24, 0, 24, 12) };
    readonly WrapPanel chipRow = new() { Margin = new Thickness(24, 0, 24, 12) };
    readonly ContentControl results = new() { Focusable = false };
    readonly DispatcherTimer debounce = new() { Interval = TimeSpan.FromMilliseconds(400) };

    List<string> recent = Searches.Load();
    string? kind;
    string? genre;
    bool torrents;
    bool busy;
    List<Title>? titles;
    List<Hit>? hits;
    int latest;
    string shownFor = "";

    public SearchView()
    {
        var (root, _, body) = Ui.Screen("Search", "search");
        var (box, field, trail) = Ui.SearchField("Films and series");
        input = field;
        trailing = trail;
        box.Margin = new Thickness(24, 4, 24, 12);

        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = results, Padding = new Thickness(24, 0, 24, 120) };
        var dock = new DockPanel();
        foreach (var top in new FrameworkElement[] { box, recentRow, chipRow })
        {
            DockPanel.SetDock(top, Dock.Top);
            dock.Children.Add(top);
        }
        dock.Children.Add(scroll);
        body.Content = dock;
        Content = root;

        input.TextChanged += (_, _) =>
        {
            BuildTrailing();
            BuildChips();
            debounce.Stop();
            debounce.Interval = TimeSpan.FromMilliseconds(input.Text.Trim().Length > 0 ? 400 : 1);
            debounce.Start();
        };
        input.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter) { recent = Searches.Remember(input.Text); BuildChips(); debounce.Stop(); Run(); }
        };
        debounce.Tick += (_, _) => { debounce.Stop(); Run(); };
        Net.OnlineChanged += () => Dispatcher.BeginInvoke(() => { if (Net.IsOnline && titles is { Count: 0 }) Run(); });
        IsVisibleChanged += (_, _) => { if (IsVisible) Dispatcher.BeginInvoke(() => input.Focus(), DispatcherPriority.Input); };

        BuildTrailing();
        BuildChips();
        Run();
    }

    string Query => input.Text.Trim();

    void BuildTrailing()
    {
        if (busy) trailing.Content = Ui.Spinner(18);
        else if (input.Text.Length > 0) trailing.Content = Ui.Button(Ui.Glyph("x", 16, "Faint"), () => { input.Clear(); input.Focus(); });
        else trailing.Content = null;
    }

    void BuildChips()
    {
        recentRow.Children.Clear();
        // Only while browsing: once something is typed the box itself is the history.
        if (Query.Length == 0 && recent.Count > 0 && !torrents)
        {
            var clock = Ui.Glyph("clock", 13, "Faint");
            clock.Margin = new Thickness(0, 0, 8, 0);
            recentRow.Children.Add(clock);
            foreach (var q in recent)
            {
                var chip = Ui.Chip(q, false, () => { input.Text = q; input.CaretIndex = q.Length; });
                chip.Margin = new Thickness(0, 0, 8, 6);
                recentRow.Children.Add(chip);
            }
            var clear = Ui.Button(Ui.Glyph("x", 13, "Faint"), () => { recent = Searches.Clear(); BuildChips(); }, tooltip: "Clear recent searches");
            clear.VerticalAlignment = VerticalAlignment.Center;
            recentRow.Children.Add(clear);
        }

        chipRow.Children.Clear();
        void Add(FrameworkElement e) { e.Margin = new Thickness(0, 0, 8, 6); chipRow.Children.Add(e); }
        Add(Ui.Chip("Torrents", torrents, () =>
        {
            torrents = !torrents;
            hits = null;
            titles = null;
            shownFor = "";
            input.Tag = torrents ? "Release or torrent name" : "Films and series";
            BuildChips();
            Run();
        }, "search"));
        if (!torrents)
        {
            Add(Ui.Rule());
            Add(Ui.Chip("Films", kind == "movie", () => { kind = kind == "movie" ? null : "movie"; BuildChips(); Render(); }, "film"));
            Add(Ui.Chip("Series", kind == "tv", () => { kind = kind == "tv" ? null : "tv"; BuildChips(); Render(); }, "tv"));
        }
        // Search results carry no genres, so these belong to browsing only.
        if (!torrents && Query.Length == 0)
        {
            Add(Ui.Rule());
            foreach (var g in BrowseGenres)
                Add(Ui.Chip(g, genre == g, () => { genre = genre == g ? null : g; BuildChips(); Run(); }));
        }
    }

    async void Run()
    {
        var q = Query;
        var mine = ++latest;
        if (torrents)
        {
            if (q.Length == 0)
            {
                hits = null;
                SetBusy(false);
                Render();
                return;
            }
            shownFor = q;
            SetBusy(true);
            // Show releases as each index answers; the spinner in the box stays until the last one has.
            void Arrived(List<Hit> sofar) => Dispatcher.BeginInvoke(() =>
            {
                if (mine != latest) return;
                hits = sofar;
                Render();
            });
            var found = await Search.SearchTorrents(q, progress: Arrived).ContinueWith(t => t.IsCompletedSuccessfully ? t.Result : new List<Hit>());
            if (mine != latest) return;
            hits = found;
            SetBusy(false);
            Render();
            return;
        }

        if (q.Length > 0) genre = null;
        // Results for an unrelated search are not an answer to this one.
        if (shownFor.Length > 0 && q.Length > 0 && !q.StartsWith(shownFor) && !shownFor.StartsWith(q))
        {
            titles = null;
            Render();
        }
        shownFor = q;
        SetBusy(true);
        List<Title> mixed;
        if (q.Length == 0 && genre == Meta.AnimeGenre)
        {
            mixed = await Meta.LoadCatalog("tv", Meta.AnimeGenre);
        }
        else
        {
            var filmsJob = q.Length > 0 ? Meta.SearchTitles(q, "movie") : Meta.LoadCatalog("movie");
            var seriesJob = q.Length > 0 ? Meta.SearchTitles(q, "tv") : Meta.LoadCatalog("tv");
            var films = await filmsJob;
            var series = await seriesJob;
            // Interleaved: looking for a show should not mean scrolling past thirty films.
            mixed = new List<Title>();
            for (var i = 0; i < Math.Max(films.Count, series.Count); i++)
            {
                if (i < films.Count) mixed.Add(films[i]);
                if (i < series.Count) mixed.Add(series[i]);
            }
        }
        if (mine != latest) return;
        titles = mixed;
        SetBusy(false);
        Render();
    }

    void SetBusy(bool on)
    {
        busy = on;
        BuildTrailing();
    }

    void Render()
    {
        if (torrents)
        {
            var list = new StackPanel();
            if (hits is { Count: > 0 })
            {
                list.Children.Add(Ui.Text($"{hits.Count} RELEASES", "Overline", "Faint"));
                foreach (var hit in hits) list.Children.Add(HitRow(hit));
            }
            else if (!busy)
            {
                var note = Ui.Text(hits is null
                    ? "Search the indexes by release name — anything they carry, whether the catalogue lists it or not. You can also drop a .torrent file or a magnet link anywhere on this window."
                    : "No releases match that.", "Meta", "Faint", 13);
                note.TextWrapping = TextWrapping.Wrap;
                note.Margin = new Thickness(0, 24, 0, 0);
                list.Children.Add(note);
            }
            results.Content = list;
            return;
        }

        var shown = (titles ?? new()).Where(t => (kind is null || t.Kind == kind) && (genre is null || genre == Meta.AnimeGenre || t.Genres.Contains(genre))).ToList();
        var page = new StackPanel();
        if (shown.Count > 0)
        {
            var heading = Ui.Text((Query.Length > 0 ? $"{shown.Count} results" : "Popular right now").ToUpperInvariant(), "Overline");
            heading.Margin = new Thickness(0, 0, 0, 12);
            page.Children.Add(heading);
            var grid = Ui.Grid(shown);
            // A query that led somewhere is the one worth keeping.
            grid.PreviewMouseLeftButtonUp += (_, _) => { if (Query.Length > 0) recent = Searches.Remember(Query); };
            page.Children.Add(grid);
        }
        else if (!busy)
        {
            var note = Ui.Text(titles is null ? "Loading…" : "Nothing matches that.", "Meta", "Faint", 13);
            note.HorizontalAlignment = HorizontalAlignment.Center;
            note.Margin = new Thickness(0, 24, 0, 0);
            page.Children.Add(note);
        }
        results.Content = page;
    }

    FrameworkElement HitRow(Hit hit)
    {
        var tone = Ui.HealthTone(hit.Seeders);
        var meta = string.Join("  ·  ", new[] { hit.Size > 0 ? Format.Bytes(hit.Size) : null, hit.Quality, $"{hit.Seeders} seeders" }.Where(s => s is not null));
        var name = Ui.Text(hit.Name, "Label", "Text", 13);
        name.ToolTip = hit.Name;
        var body = new StackPanel { Children = { name, Ui.Text(meta, "Meta", "Faint") }, VerticalAlignment = VerticalAlignment.Center };
        ((TextBlock)body.Children[1]).Margin = new Thickness(0, 3, 0, 0);

        var download = Ui.Round("download", () =>
        {
            if (Query.Length > 0) recent = Searches.Remember(Query);
            shell.BeginDownload(new DownloadJob { Uri = hit.Magnet, Auto = "best", Title = hit.Name, TitleName = TitleText.SearchableName(hit.Name) });
        }, "Accent", "SurfaceAlt", 32, 15, "Download");
        var copy = Ui.Round("copy", () =>
        {
            // The clipboard can be held open by another app for a moment; say so
            // rather than let the exception reach the whole window.
            try
            {
                Clipboard.SetText(hit.Magnet);
                shell.Toast("Magnet link copied");
            }
            catch (System.Runtime.InteropServices.ExternalException)
            {
                shell.Toast("Couldn't reach the clipboard — try again", true);
            }
        }, "Accent", "SurfaceAlt", 32, 15, "Copy magnet link");
        copy.Margin = new Thickness(0, 0, 8, 0);
        var dot = Ui.Brushed(new Border { Width = 8, Height = 8, CornerRadius = new CornerRadius(4), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 4, 0) }, tone.Fg);

        var dock = new DockPanel { Margin = new Thickness(0, 12, 0, 12) };
        DockPanel.SetDock(dot, Dock.Right);
        DockPanel.SetDock(download, Dock.Right);
        DockPanel.SetDock(copy, Dock.Right);
        dock.Children.Add(dot);
        dock.Children.Add(download);
        dock.Children.Add(copy);
        dock.Children.Add(body);

        var row = Ui.Button(new Border { Child = dock, BorderThickness = new Thickness(0, 0, 0, 1) }, () =>
        {
            if (Query.Length > 0) recent = Searches.Remember(Query);
            // Straight to the file list — someone who tapped a release wants to see what is in it.
            _ = shell.StreamUri(hit.Magnet, new OpenOptions { TitleName = TitleText.SearchableName(hit.Name) });
        });
        ((Border)row.Content).SetResourceReference(Border.BorderBrushProperty, "Line");
        return row;
    }
}
