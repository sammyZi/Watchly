using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Watchly.Controls;
using Watchly.Core;
using Watchly.Engine;

namespace Watchly.Views;

/// <summary>
/// src/screens/DetailScreen.tsx: artwork and facts up top, a Play button for the
/// healthiest release, the episode list for a series, and every release under it.
/// </summary>
public sealed class DetailView : UserControl, IDisposable
{
    static readonly string[] Qualities = { "4K", "1080p", "720p", "CAM" };
    const int EpisodePage = 10;

    readonly MainWindow shell = MainWindow.Shell;
    readonly ScrollViewer scroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    readonly StackPanel page = new();
    readonly StackPanel topRight = new() { Orientation = Orientation.Horizontal };
    readonly StackPanel content = new() { MaxWidth = 1100, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 0, 0, 80) };
    readonly DispatcherTimer searchDelay = new() { Interval = TimeSpan.FromMilliseconds(280) };

    Title title;
    List<Hit>? sources;
    string? sourceKey;
    Episode? picked;
    int? season;
    bool wholeSeason;
    string? wanted;
    bool plotOpen;
    bool saving;
    string? error;
    CancellationTokenSource? searching;
    int searchGen;
    FrameworkElement? actionsAnchor;

    public Title Current => title;

    public DetailView(Title seed)
    {
        title = seed;
        if (seed.Kind == "tv")
        {
            picked = GuessEpisode(seed.Imdb, shell.EpisodeProgress(title.Episodes));
            season = picked.Season;
        }

        var root = new Grid();
        scroll.Content = page;
        root.Children.Add(scroll);

        var back = Ui.Round("arrow-left", () => shell.CloseLayer(this), "Accent", "AccentSoft", 40, 20, "Back");
        back.HorizontalAlignment = HorizontalAlignment.Left;
        back.VerticalAlignment = VerticalAlignment.Top;
        back.Margin = new Thickness(20, 16, 0, 0);
        root.Children.Add(back);
        topRight.HorizontalAlignment = HorizontalAlignment.Right;
        topRight.VerticalAlignment = VerticalAlignment.Top;
        topRight.Margin = new Thickness(0, 16, 28, 0);
        root.Children.Add(topRight);
        Content = root;

        searchDelay.Tick += (_, _) => { searchDelay.Stop(); FindReleases(); };
        shell.LibraryChanged += OnLibrary;
        shell.OpeningChanged += Render;

        Render();
        LoadFull();
        FindReleases();
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

    /// <summary>A shelf row carries no episodes, so a series always needs the full record.</summary>
    async void LoadFull()
    {
        if (title.Plot is not null && (title.Kind == "movie" || title.Episodes.Count > 0)) return;
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

    string PickKey => picked is not null ? $"{picked.Season}:{picked.Number}" : $"movie:{title.Imdb}";
    int? PickedAbsolute => picked is not null ? Meta.AbsoluteNumber(title.Episodes, picked.Season, picked.Number) : null;
    string? PickedTag => picked is not null ? Search.PickTag(picked.Season, picked.Number, PickedAbsolute) : null;

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
            ? Search.SearchEpisode(title.Name, picked.Season, picked.Number, cts.Token, title.Imdb, PickedAbsolute)
            : Search.SearchTorrents(title.Name, cts.Token, title.Imdb, title.Kind == "movie" ? title.Year?[..Math.Min(4, title.Year.Length)] : null);
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

    void Render()
    {
        var offset = scroll.VerticalOffset;
        page.Children.Clear();
        content.Children.Clear();
        RenderSaveButtons();

        var progress = shell.EpisodeProgress(title.Episodes);
        var resume = title.Kind == "movie" ? shell.FilmProgress(title.Imdb) : null;
        var fresh = sourceKey == PickKey;
        var shown = wanted is not null ? sources?.Where(h => h.Quality == wanted).ToList() : sources;
        var best = fresh ? shown?.FirstOrDefault() : null;
        var seen = picked is not null ? progress.GetValueOrDefault(picked.Id) : resume is { } r ? Math.Min(100, r.Position / r.Duration * 100) : 0;
        var canResume = seen >= 1 && seen < EpisodeLinks.Watched;
        var wait = shell.IsOpening || saving;

        // Artwork, with the page fading into it.
        var art = new Grid { Height = 400 };
        art.SetResourceReference(Panel.BackgroundProperty, "Surface");
        if (ImageUrl.Load(title.Backdrop ?? title.Poster, 1920) is { } backdrop)
            art.Children.Add(new Border { Background = new ImageBrush(backdrop) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top } });
        art.Children.Add(Ui.Brushed(new Border(), "FadeToBg"));
        page.Children.Add(art);

        // Poster and facts, pulled up over the fade.
        var head = new DockPanel { Margin = new Thickness(40, -120, 40, 0) };
        if (title.Poster is not null)
        {
            var poster = Ui.Art(title.Poster, title.Name, 140, 206, 12, 280);
            poster.Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 24, ShadowDepth = 6, Opacity = 0.35 };
            DockPanel.SetDock(poster, Dock.Left);
            head.Children.Add(poster);
        }
        var name = Ui.Text(title.Name, "Display", "Text", 30);
        name.TextWrapping = TextWrapping.Wrap;
        var facts = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 8, 0, 8) };
        if (title.Rating is not null)
        {
            var star = new Icon { Kind = "star", Size = 13, Filled = true };
            star.SetResourceReference(Icon.ForegroundProperty, "Warn");
            facts.Children.Add(star);
            facts.Children.Add(Ui.Text(title.Rating, "Label", "Warn", 13, FontWeights.SemiBold));
            ((FrameworkElement)facts.Children[^1]).Margin = new Thickness(4, 0, 14, 0);
        }
        facts.Children.Add(Ui.Text(string.Join(" · ", new[] { title.Year, title.Runtime, title.Kind == "tv" ? "Series" : "Film" }.Where(s => !string.IsNullOrEmpty(s))), "Meta", "Accent", 13));
        var genres = new WrapPanel();
        foreach (var g in title.Genres)
        {
            var chip = Ui.Brushed(new Border { CornerRadius = new CornerRadius(8), Padding = new Thickness(8, 3, 8, 3), Margin = new Thickness(0, 0, 6, 6), Child = Ui.Text(g, "Meta", "Accent", 11) }, "SurfaceAlt");
            genres.Children.Add(chip);
        }
        head.Children.Add(new StackPanel { Margin = new Thickness(20, 120, 0, 0), VerticalAlignment = VerticalAlignment.Bottom, Children = { name, facts, genres } });
        content.Children.Add(head);

        // The one thing most people want: start the healthiest release.
        var actions = new DockPanel { Margin = new Thickness(40, 24, 40, 0), MaxWidth = 720, HorizontalAlignment = HorizontalAlignment.Left };
        actionsAnchor = actions;
        var playLabel = wait ? "Opening…"
            : best is null ? "Finding releases…"
            : canResume ? $"Continue{(PickedTag is null ? "" : " " + PickedTag)} · {Math.Round(seen)}%"
            : PickedTag is not null ? $"Play {PickedTag}" : "Play";
        var saveButton = Ui.Round("download", () => { if (best is not null) Save(best.Magnet); }, "Text", "SurfaceAlt", 50, 18, "Download");
        if (saving) saveButton.Content = Ui.Spinner(18, "Text");
        saveButton.IsEnabled = best is not null && !wait;
        saveButton.Margin = new Thickness(12, 0, 0, 0);
        DockPanel.SetDock(saveButton, Dock.Right);
        actions.Children.Add(saveButton);
        if (canResume)
        {
            var over = Ui.Round("rotate-ccw", () => { if (best is not null) Start(best.Magnet, true); }, "Text", "SurfaceAlt", 50, 17, "Start over");
            over.IsEnabled = best is not null && !wait;
            over.Margin = new Thickness(12, 0, 0, 0);
            DockPanel.SetDock(over, Dock.Right);
            actions.Children.Add(over);
        }
        var play = Ui.Button(new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Children = { Ui.Glyph("play", 17, best is null || wait ? "Muted" : "OnAccent"), Ui.Text(playLabel, "Label", best is null || wait ? "Muted" : "OnAccent", 15, FontWeights.SemiBold) },
        }, () => { if (best is not null) Start(best.Magnet, false); }, best is null || wait ? "Pill" : "PillAccent");
        ((FrameworkElement)((StackPanel)play.Content).Children[1]).Margin = new Thickness(8, 0, 0, 0);
        play.Height = 50;
        play.Tag = 25;
        play.IsHitTestVisible = best is not null && !wait;
        actions.Children.Add(play);
        content.Children.Add(actions);

        if (title.Kind == "tv" && season is not null)
        {
            var seasonDl = Ui.Button(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children = { saving ? Ui.Spinner(15, "Text") : Ui.Glyph("download", 15, "Text"), Ui.Text(saving ? "Starting download…" : $"Download season {(season == 0 ? "specials" : season.ToString())}", "Label", "Text", 13) },
            }, SaveSeason, "Pill");
            ((FrameworkElement)((StackPanel)seasonDl.Content).Children[1]).Margin = new Thickness(8, 0, 0, 0);
            seasonDl.SetResourceReference(BackgroundProperty, "SurfaceAlt");
            seasonDl.Margin = new Thickness(40, 12, 40, 0);
            seasonDl.MaxWidth = 720;
            seasonDl.HorizontalAlignment = HorizontalAlignment.Left;
            seasonDl.Width = 720;
            seasonDl.IsEnabled = best is not null && !wait;
            content.Children.Add(seasonDl);
        }

        // Quality: one track of segments, directly under the button it governs.
        var qualities = Qualities.Where(q => sources?.Any(h => h.Quality == q) == true).ToList();
        if (qualities.Count > 1 && sources is not null)
        {
            var bar = new System.Windows.Controls.Primitives.UniformGrid { Rows = 1 };
            foreach (var q in qualities.Prepend(null))
            {
                var on = wanted == q;
                var count = q is null ? sources.Count : sources.Count(h => h.Quality == q);
                var label = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Children = { Ui.Text(q ?? "Best", "Meta", on ? "Text" : "Faint", 12, on ? FontWeights.SemiBold : FontWeights.Medium), Ui.Text(count.ToString(), "Meta", on ? "Accent" : "Faint", 10) },
                };
                ((FrameworkElement)label.Children[1]).Margin = new Thickness(5, 0, 0, 0);
                var captured = q;
                var segment = Ui.Button(label, () => { wanted = captured; Render(); });
                segment.Tag = 999;
                segment.Padding = new Thickness(0, 9, 0, 9);
                segment.Background = on ? (Brush)FindResource("SurfaceAlt") : Brushes.Transparent;
                bar.Children.Add(segment);
            }
            var track = Ui.Pill(Ui.Card(bar, "Surface", 0, new Thickness(3)));
            track.Margin = new Thickness(40, 12, 40, 0);
            track.MaxWidth = 720;
            track.HorizontalAlignment = HorizontalAlignment.Left;
            track.Width = 720;
            content.Children.Add(track);
        }

        // A film's own progress bar; series show theirs per episode.
        if (picked is null && seen >= 1 && resume is { } res)
        {
            var bar = Ui.Bar(seen, 3);
            var line = Ui.Text($"{Format.Clock(res.Position)} of {Format.Clock(res.Duration)}" + (seen >= EpisodeLinks.Watched ? " · finished" : $" · {Format.Left(res.Duration - res.Position)}"), "Meta", "Faint", 11);
            line.Margin = new Thickness(0, 4, 0, 0);
            content.Children.Add(new StackPanel { Margin = new Thickness(40, 12, 40, 0), MaxWidth = 720, HorizontalAlignment = HorizontalAlignment.Left, Width = 720, Children = { bar, line } });
        }

        if (title.Plot is not null)
        {
            var plot = Ui.Text(title.Plot, "Body", "Muted", 15);
            plot.LineHeight = 24;
            plot.MaxWidth = 820;
            plot.HorizontalAlignment = HorizontalAlignment.Left;
            if (!plotOpen)
            {
                plot.MaxHeight = 72;
                plot.TextTrimming = TextTrimming.WordEllipsis;
            }
            var plotButton = Ui.Button(plot, () => { plotOpen = !plotOpen; Render(); });
            plotButton.Margin = new Thickness(40, 20, 40, 0);
            plotButton.HorizontalAlignment = HorizontalAlignment.Left;
            content.Children.Add(plotButton);
        }

        if (title.Directors.Count > 0)
        {
            var crew = new TextBlock { FontSize = 12, Margin = new Thickness(40, 12, 40, 0) };
            crew.Inlines.Add(new System.Windows.Documents.Run("Directed by ") { Foreground = (Brush)FindResource("Faint") });
            crew.Inlines.Add(new System.Windows.Documents.Run(string.Join(", ", title.Directors)) { Foreground = (Brush)FindResource("Muted") });
            content.Children.Add(crew);
        }

        if (title.Cast.Count > 0)
        {
            var heading = Ui.Text("CAST", "Overline");
            heading.Margin = new Thickness(40, 28, 40, 12);
            content.Children.Add(heading);
            var rail = new WrapPanel { Margin = new Thickness(40, 0, 40, 0) };
            foreach (var who in title.Cast.Take(12))
            {
                var face = new Border
                {
                    Width = 68,
                    Height = 68,
                    CornerRadius = new CornerRadius(34),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Background = (Brush)new HueBrush().Convert(who, typeof(Brush), "dark", null!),
                    Child = new TextBlock { Text = (string)new Initials().Convert(who, typeof(string), null!, null!), Foreground = Brushes.White, FontSize = 20, FontWeight = FontWeights.SemiBold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
                };
                var label = Ui.Text(who, "Meta", "Muted", 11);
                label.TextWrapping = TextWrapping.Wrap;
                label.TextAlignment = TextAlignment.Center;
                label.MaxHeight = 30;
                label.Margin = new Thickness(0, 8, 0, 0);
                rail.Children.Add(new StackPanel { Width = 82, Margin = new Thickness(0, 0, 12, 12), Children = { face, label } });
            }
            content.Children.Add(rail);
        }

        if (title.Kind == "tv") RenderEpisodes(progress);

        if (title.Awards is not null)
        {
            var text = Ui.Text(title.Awards, "Meta", "Warn", 12);
            text.TextWrapping = TextWrapping.Wrap;
            text.Margin = new Thickness(8, 0, 0, 0);
            var dock = new DockPanel();
            var award = Ui.Glyph("award", 14, "Warn");
            award.VerticalAlignment = VerticalAlignment.Top;
            DockPanel.SetDock(award, Dock.Left);
            dock.Children.Add(award);
            dock.Children.Add(text);
            var card = Ui.Card(dock, "WarnSoft", 12, new Thickness(12));
            card.Margin = new Thickness(40, 20, 40, 0);
            card.MaxWidth = 720;
            card.HorizontalAlignment = HorizontalAlignment.Left;
            content.Children.Add(card);
        }

        var reviews = Ui.Text("RATINGS & REVIEWS", "Overline");
        reviews.Margin = new Thickness(40, 28, 40, 10);
        content.Children.Add(reviews);
        var links = new WrapPanel { Margin = new Thickness(40, 0, 40, 0) };
        foreach (var (label, url) in Meta.ReviewLinks(title))
        {
            var link = Ui.Button(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children = { Ui.Text(label, "Meta", "Text", 12, FontWeights.Medium), Ui.Glyph("external-link", 12, "Muted") },
            }, () => Ui.Open(url), tooltip: url);
            ((FrameworkElement)((StackPanel)link.Content).Children[1]).Margin = new Thickness(8, 0, 0, 0);
            link.Tag = 999;
            link.Padding = new Thickness(12, 8, 12, 8);
            link.Margin = new Thickness(0, 0, 8, 8);
            link.SetResourceReference(BackgroundProperty, "SurfaceAlt");
            links.Children.Add(link);
        }
        content.Children.Add(links);

        RenderSources(fresh, shown, wait);

        page.Children.Add(content);
        Dispatcher.BeginInvoke(() => scroll.ScrollToVerticalOffset(offset), DispatcherPriority.Loaded);
    }

    void RenderSaveButtons()
    {
        topRight.Children.Clear();
        foreach (var (list, glyph, label) in new[] { ("later", "bookmark", "Watch later"), ("favorite", "heart", "Favourite") })
        {
            var active = Saved.OnList(shell.SavedList, title.Imdb, list);
            var icon = new Icon { Kind = glyph, Size = 20, Filled = active };
            if (active) icon.Foreground = new SolidColorBrush(Color.FromRgb(0xe5, 0x09, 0x14));
            else icon.SetResourceReference(Icon.ForegroundProperty, "Muted");
            var button = Ui.Button(icon, () => { shell.ToggleSaved(title, list); Render(); }, "Round", active ? $"Remove from {label}" : label);
            button.SetResourceReference(BackgroundProperty, "AccentSoft");
            button.Margin = new Thickness(8, 0, 0, 0);
            topRight.Children.Add(button);
        }
    }

    void RenderEpisodes(Dictionary<string, double> progress)
    {
        var episodes = title.Episodes;
        var seasons = episodes.Count > 0 ? episodes.Select(e => e.Season).Distinct().ToList() : season is not null ? new List<int> { season.Value } : new();
        if (seasons.Count == 0) return;
        var inSeason = episodes.Where(e => e.Season == season).ToList();
        var showsWhole = wholeSeason || (picked is not null && inSeason.FindIndex(e => e.Id == picked.Id) >= EpisodePage);
        var listed = showsWhole ? inSeason : inSeason.Take(EpisodePage).ToList();

        var block = new StackPanel { Margin = new Thickness(40, 28, 40, 0), MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Left };
        var headRow = new DockPanel();
        var count = Ui.Text($"{inSeason.Count} in season", "Meta", "Faint", 11);
        DockPanel.SetDock(count, Dock.Right);
        headRow.Children.Add(count);
        headRow.Children.Add(Ui.Text("EPISODES", "Overline"));
        block.Children.Add(headRow);

        if (seasons.Count > 1)
        {
            var chips = new WrapPanel { Margin = new Thickness(0, 10, 0, 6) };
            foreach (var s in seasons)
            {
                var captured = s;
                var chip = Ui.Chip(s == 0 ? "Specials" : $"Season {s}", s == season, () => ChooseSeason(captured));
                chip.Margin = new Thickness(0, 0, 8, 8);
                chips.Children.Add(chip);
            }
            block.Children.Add(chips);
        }

        foreach (var ep in listed)
        {
            var seen = progress.GetValueOrDefault(ep.Id);
            var done = seen >= EpisodeLinks.Watched;
            var on = picked?.Id == ep.Id;

            var still = new Grid { Width = 160, Height = 90 };
            still.Children.Add(Ui.Brushed(new Border { CornerRadius = new CornerRadius(8), Child = ep.Thumbnail is null ? Ui.Glyph("film", 16, "Faint") : null }, "SurfaceAlt"));
            if (ImageUrl.Load(ep.Thumbnail, 320) is { } image)
                still.Children.Add(new Border { CornerRadius = new CornerRadius(8), Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill } });
            if (seen > 0 && !done)
            {
                var bar = Ui.Bar(seen, 3);
                bar.VerticalAlignment = VerticalAlignment.Bottom;
                bar.Margin = new Thickness(8);
                still.Children.Add(bar);
            }
            if (done)
            {
                still.Children.Add(Ui.Brushed(new Border { Width = 18, Height = 18, CornerRadius = new CornerRadius(9), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(4), Child = Ui.Glyph("check", 11, "OnAccent") }, "Accent"));
            }

            var text = new StackPanel { Margin = new Thickness(14, 2, 0, 2) };
            text.Children.Add(Ui.Text($"{ep.Number}. {ep.Name}", "Label", "Text", 14));
            text.Children.Add(Ui.Text(done ? "Watched" : seen > 0 ? $"{Math.Round(seen)}% watched" : ep.Released is { Length: >= 10 } rel ? rel[..10] : "Not watched", "Meta", "Faint", 11));
            if (ep.Overview is not null)
            {
                var overview = Ui.Text(ep.Overview, "Meta", "Muted", 12);
                overview.TextWrapping = TextWrapping.Wrap;
                overview.MaxHeight = 36;
                overview.TextTrimming = TextTrimming.WordEllipsis;
                overview.Margin = new Thickness(0, 4, 0, 0);
                text.Children.Add(overview);
            }
            var dock = new DockPanel();
            DockPanel.SetDock(still, Dock.Left);
            dock.Children.Add(still);
            dock.Children.Add(text);
            var card = Ui.Card(dock, on ? "SurfaceAlt" : "Surface", 12, new Thickness(8));
            var captured = ep;
            var button = Ui.Button(card, () =>
            {
                picked = captured;
                FindReleases(debounce: true);
                Render();
                // Bring the Play button — which now refers to this episode — into view.
                if (actionsAnchor is not null)
                {
                    var y = actionsAnchor.TransformToAncestor(page).Transform(new Point(0, 0)).Y;
                    scroll.ScrollToVerticalOffset(Math.Max(0, y - 80));
                }
            });
            button.Margin = new Thickness(0, 8, 0, 0);
            block.Children.Add(button);
        }

        if (inSeason.Count > listed.Count || (wholeSeason && inSeason.Count > EpisodePage))
        {
            var less = wholeSeason && inSeason.Count > EpisodePage && inSeason.Count == listed.Count;
            var more = Ui.Button(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Center,
                Children = { Ui.Text(less ? "Show less" : $"Show all {inSeason.Count} episodes", "Label", "Accent", 13), Ui.Glyph(less ? "chevron-up" : "chevron-down", 16, "Accent") },
            }, () => { wholeSeason = !less; Render(); });
            var card = Ui.Card(more, "Surface", 12, new Thickness(12));
            card.Margin = new Thickness(0, 8, 0, 0);
            block.Children.Add(card);
        }
        content.Children.Add(block);
    }

    void RenderSources(bool fresh, List<Hit>? shown, bool wait)
    {
        var block = new StackPanel { Margin = new Thickness(40, 28, 40, 0), MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Left, Width = 820 };
        block.Children.Add(Ui.Text((!fresh || shown is null ? "Finding releases…" : $"{shown.Count} release{(shown.Count == 1 ? "" : "s")}").ToUpperInvariant(), "Overline"));
        if (sources is null || !fresh)
        {
            var spin = Ui.Spinner();
            spin.Margin = new Thickness(0, 16, 0, 0);
            spin.HorizontalAlignment = HorizontalAlignment.Left;
            block.Children.Add(spin);
        }
        if (wait)
        {
            var opening = Ui.Card(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children = { Ui.Spinner(18), Ui.Text(saving ? "Starting download…" : "Reading torrent…", "Label", "Accent", 13) },
            }, "AccentSoft", 12, new Thickness(12));
            ((FrameworkElement)((StackPanel)opening.Child).Children[1]).Margin = new Thickness(12, 0, 0, 0);
            opening.Margin = new Thickness(0, 10, 0, 0);
            block.Children.Add(opening);
        }
        if (error is not null)
        {
            var e = Ui.Text(error, "Meta", "Danger", 12);
            e.TextWrapping = TextWrapping.Wrap;
            e.Margin = new Thickness(0, 10, 0, 0);
            block.Children.Add(e);
        }
        if (sources is { Count: 0 } && fresh && error is null)
        {
            var none = Ui.Text("Nothing on the index for this title. Try the Search tab with a different spelling.", "Meta", "Faint", 12);
            none.TextWrapping = TextWrapping.Wrap;
            none.Margin = new Thickness(0, 10, 0, 0);
            block.Children.Add(none);
        }
        if (fresh && shown is not null)
        {
            for (var i = 0; i < shown.Count; i++)
            {
                var hit = shown[i];
                var tone = Ui.HealthTone(hit.Seeders);
                var mark = Ui.Brushed(new Border { Width = 34, Height = 34, CornerRadius = new CornerRadius(17), Child = Ui.Glyph("play", 14, i == 0 ? "OnAccent" : "Muted") }, i == 0 ? "Accent" : "SurfaceAlt");
                var name = Ui.Text(hit.Name, "Label", "Text", 13);
                name.ToolTip = hit.Name;
                var meta = Format.Bytes(TorrentBytes(hit)) + (hit.Quality is null ? "" : $" · {hit.Quality}")
                    + (picked is not null && hit.Scope != "episode" ? $" · {(hit.Scope == "season" ? "season pack" : "complete series")}" : "");
                var body = new StackPanel { Margin = new Thickness(12, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center, Children = { name, Ui.Text(meta, "Meta", "Faint", 11) } };
                var pill = Ui.Brushed(new Border
                {
                    CornerRadius = new CornerRadius(11),
                    Padding = new Thickness(8, 4, 8, 4),
                    VerticalAlignment = VerticalAlignment.Center,
                    Child = new StackPanel
                    {
                        Orientation = Orientation.Horizontal,
                        Children =
                        {
                            Ui.Brushed(new Border { Width = 6, Height = 6, CornerRadius = new CornerRadius(3), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 5, 0) }, tone.Fg),
                            Ui.Text(hit.Seeders.ToString(), "Meta", tone.Fg, 11, FontWeights.Medium),
                        },
                    },
                    ToolTip = $"{tone.Label} · {hit.Seeders} seeders",
                }, tone.Bg);
                var download = Ui.Round("download", () => Save(hit.Magnet), "Muted", "SurfaceAlt", 32, 14, "Download this release");
                download.Margin = new Thickness(0, 0, 8, 0);
                var dock = new DockPanel();
                DockPanel.SetDock(mark, Dock.Left);
                DockPanel.SetDock(pill, Dock.Right);
                DockPanel.SetDock(download, Dock.Right);
                dock.Children.Add(mark);
                dock.Children.Add(pill);
                dock.Children.Add(download);
                dock.Children.Add(body);
                var captured = hit;
                var row = Ui.Button(Ui.Card(dock, "Surface", 12, new Thickness(12)), () => Start(captured.Magnet, false));
                row.Margin = new Thickness(0, 8, 0, 0);
                block.Children.Add(row);
            }
        }
        content.Children.Add(block);
    }

    /// <summary>Torrentio's size is one file; a pack's torrent is that times its episodes.</summary>
    long TorrentBytes(Hit hit)
    {
        if (!hit.PerFileSize || hit.Scope == "episode" || hit.Size == 0) return hit.Size;
        var claims = Search.SeasonsIn(hit.Name);
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

    async void Start(string magnet, bool startOver)
    {
        var episodeId = picked?.Id;
        var local = await shell.StreamUri(magnet, new OpenOptions
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

    async void SaveSeason()
    {
        if (season is not int which) return;
        saving = true;
        Render();
        bool IsPack(Hit h)
        {
            if (h.Scope is not ("season" or "complete")) return false;
            var claims = Search.SeasonsIn(h.Name);
            return claims.Count == 0 || claims.Contains(which);
        }
        var pack = (wanted is not null ? sources?.Where(h => h.Quality == wanted) : sources)?.FirstOrDefault(IsPack) ?? sources?.FirstOrDefault(IsPack);
        if (pack is null)
        {
            var hits = await Search.SearchTorrents(title.Name, suffix: $"S{which:00}").ContinueWith(t => t.IsCompletedSuccessfully ? t.Result : new List<Hit>());
            pack = hits.FirstOrDefault(IsPack);
        }
        if (pack is null)
        {
            saving = false;
            shell.Toast($"No season pack found for season {which}", true);
            Render();
            return;
        }
        shell.BeginDownload(new DownloadJob
        {
            Uri = pack.Magnet,
            Season = which,
            Imdb = title.Imdb,
            Kind = title.Kind,
            Poster = title.Poster,
            TitleName = title.Name,
            Title = title.Name,
        });
    }
}
