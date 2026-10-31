using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// src/screens/HistoryScreen.tsx: history, watch later and favourites. Each
/// history row is a torrent that expands to its episodes, so a season pack can
/// be resumed at any of them.
/// </summary>
public sealed class LibraryView : UserControl
{
    readonly MainWindow shell = MainWindow.Shell;
    readonly StackPanel actions;
    readonly StackPanel tabs = new() { Orientation = Orientation.Horizontal, Margin = new Thickness(24, 4, 24, 8) };
    readonly ContentControl searchSlot = new() { Focusable = false };
    readonly ContentControl list = new() { Focusable = false };
    readonly HashSet<string> expanded = new();
    readonly HashSet<string> openFolders = new();
    string view = "history";
    bool searching;
    TextBox? query;
    bool dirty;

    public LibraryView()
    {
        var (root, actionSlot, body) = Ui.Screen("Library");
        actions = actionSlot;
        var dock = new DockPanel();
        DockPanel.SetDock(tabs, Dock.Top);
        DockPanel.SetDock(searchSlot, Dock.Top);
        dock.Children.Add(tabs);
        dock.Children.Add(searchSlot);
        dock.Children.Add(new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list, Padding = new Thickness(24, 12, 24, 32) });
        body.Content = dock;
        Content = root;

        shell.LibraryChanged += () => { if (IsVisible) Render(); else dirty = true; };
        IsVisibleChanged += (_, _) => { if (IsVisible && dirty) Render(); };
        Render();
    }

    void Render()
    {
        dirty = false;
        var history = shell.HistoryList;
        var saved = shell.SavedList;

        actions.Children.Clear();
        if (history.Count > 0 && view == "history")
        {
            var find = Ui.Round(searching ? "x" : "search", ToggleSearch, searching ? "OnAccent" : "Muted", searching ? "Accent" : "Surface", 40, 17, "Search titles and files");
            find.Margin = new Thickness(0, 0, 8, 0);
            actions.Children.Add(find);
            actions.Children.Add(Ui.Round("trash-2", shell.AskClearHistory, "Danger", "DangerSoft", 40, 16, "Clear watch history"));
        }

        tabs.Children.Clear();
        foreach (var (key, label, icon, count) in new[]
        {
            ("history", "History", "clock", history.Count),
            ("later", "Watch later", "bookmark", Saved.InList(saved, "later").Count),
            ("favorite", "Favourites", "heart", Saved.InList(saved, "favorite").Count),
        })
        {
            var chip = Ui.Chip(count > 0 ? $"{label} {count}" : label, view == key, () =>
            {
                view = key;
                searching = false;
                query = null;
                Render();
            }, icon);
            chip.Margin = new Thickness(0, 0, 8, 0);
            tabs.Children.Add(chip);
        }

        searchSlot.Content = null;
        if (searching && view == "history")
        {
            if (query is null)
            {
                var (box, input, trailing) = Ui.SearchField("Search titles and files");
                box.Margin = new Thickness(24, 8, 24, 0);
                input.TextChanged += (_, _) =>
                {
                    trailing.Content = input.Text.Length > 0 ? Ui.Button(Ui.Glyph("x-circle", 15, "Faint"), () => input.Clear()) : null;
                    RenderList();
                };
                query = input;
                searchSlot.Content = box;
                Dispatcher.BeginInvoke(() => input.Focus(), System.Windows.Threading.DispatcherPriority.Input);
            }
            else
            {
                searchSlot.Content = ((FrameworkElement)query.Parent).Parent;
            }
        }
        RenderList();
    }

    void ToggleSearch()
    {
        searching = !searching;
        if (!searching) query = null;
        Render();
    }

    static string Haystack(HistoryItem item) =>
        string.Join(' ', new[] { item.TorrentName, item.Title }.Concat(item.Files?.Select(f => f.Name) ?? Enumerable.Empty<string>())).ToLowerInvariant();

    void RenderList()
    {
        if (view != "history")
        {
            var titles = Saved.InList(shell.SavedList, view).Select(s => s.AsTitle()).ToList();
            list.Content = titles.Count > 0
                ? Ui.Grid(titles)
                : Ui.Empty(view == "later" ? "bookmark" : "heart",
                    view == "later" ? "Nothing saved for later" : "No favourites yet",
                    $"Open any title and use the {(view == "later" ? "bookmark" : "heart")} in the top corner to put it here.");
            return;
        }

        // One row per title — its newest started release — however many it was played from.
        var history = History.LatestPerTitle(shell.HistoryList);
        var q = query?.Text.Trim().ToLowerInvariant() ?? "";
        var shown = q.Length == 0 ? history : history.Where(h => Haystack(h).Contains(q)).ToList();
        // After a reinstall this PC's history is empty; what Simkl has still belongs here.
        var fromSimkl = KidsMode.Only(Simkl.Library.NotIn(shell.HistoryList)
            .Where(s => q.Length == 0 || s.Title.ToLowerInvariant().Contains(q))
            .Select(s => new Title { Imdb = s.Imdb, Kind = s.Kind, Name = s.Title, Year = s.Year, Poster = $"https://images.metahub.space/poster/medium/{s.Imdb}/img" }));
        var page = new StackPanel();
        if (q.Length == 0 && SimklCard() is { } card) page.Children.Add(card);
        if (shown.Count == 0 && fromSimkl.Count == 0)
        {
            page.Children.Add(q.Length > 0
                ? Ui.Empty("search", "No matches", $"Nothing in your library matches “{q}”.")
                : Ui.Empty("inbox", "Nothing here yet", "Torrents you stream show up here with every file they contain, each with its own resume point."));
            list.Content = page;
            return;
        }

        if (shown.Count > 0)
        {
            var count = Ui.Text(q.Length > 0
                ? $"{shown.Count} {(shown.Count == 1 ? "match" : "matches")}"
                : $"{history.Count} {(history.Count == 1 ? "torrent" : "torrents")} · right-click a row to remove it", "Meta");
            count.Margin = new Thickness(0, 0, 0, 12);
            var rows = new ColumnWrap();
            foreach (var item in shown) rows.Children.Add(Row(item, history));
            page.Children.Add(count);
            page.Children.Add(rows);
        }
        if (fromSimkl.Count > 0)
        {
            var heading = Ui.Text("From Simkl", "TitleText", "Text", 17, FontWeights.Bold);
            heading.Margin = new Thickness(0, shown.Count > 0 ? 24 : 0, 0, 4);
            var hint = Ui.Text("Watched or paused on another device, or before this PC — open one to play it from where Simkl says you were.", "Meta", "Faint", 12);
            hint.Margin = new Thickness(0, 0, 0, 12);
            page.Children.Add(heading);
            page.Children.Add(hint);
            page.Children.Add(Ui.Grid(fromSimkl));
        }
        list.Content = page;
    }

    /// <summary>Where the history is, the history's sync is offered — until connected, or put off with Not now.</summary>
    FrameworkElement? SimklCard()
    {
        if (!Simkl.Available || Simkl.Connected || shell.Who is not { IsGuest: false } || Store.LoadOwn<bool>("simkl-later", false)) return null;
        var connect = Ui.Button(Ui.Text("Connect Simkl", "Label", "Accent", 13, FontWeights.SemiBold), () => _ = shell.ConnectSimkl());
        var later = Ui.Button(Ui.Text("Not now", "Label", "Muted", 13), () => { Store.SaveOwn("simkl-later", true); Render(); });
        later.Margin = new Thickness(20, 0, 0, 0);
        var card = Ui.Card(new StackPanel
        {
            Children =
            {
                Ui.Text("Sync your history with Simkl", "TitleText", "Text", 15, FontWeights.SemiBold),
                Ui.Text("Films and episodes you finish get marked watched on your Simkl account, and Continue Watching follows you to your phone and TV.", "Meta", "Faint", 12),
                new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0), Children = { connect, later } },
            },
        }, "Surface", 10, new Thickness(16));
        card.Margin = new Thickness(0, 0, 0, 16);
        return card;
    }

    /// <summary>src/components/HistoryRow.tsx.</summary>
    FrameworkElement Row(HistoryItem item, List<HistoryItem> kin)
    {
        var videos = (item.Files ?? new()).Where(f => f.IsVideo).ToList();
        var lastPosition = item.LastFileIndex is int li ? item.PositionOf(li) : null;
        var lastDuration = item.LastFileIndex is int ld ? item.DurationOf(ld) : null;
        var percent = lastDuration > 0 && lastPosition > 0 ? Math.Round(lastPosition.Value / lastDuration.Value * 100) : 0;
        var watched = videos.Count(f => item.DurationOf(f.Index) is > 0 and var d && item.PositionOf(f.Index) is > 0 and var p && p / d * 100 >= EpisodeLinks.Watched);

        string? progressLabel = videos.Count > 1
            ? watched >= videos.Count ? "All watched" : watched > 0 ? $"{watched}/{videos.Count} watched" : percent > 0 ? $"{percent}% into ep. 1" : null
            : percent >= EpisodeLinks.Watched ? "Watched" : percent > 0 ? $"{percent}% watched" : null;
        var meta = string.Join(" · ", new[] { videos.Count > 1 ? $"{videos.Count} episodes" : null, lastPosition > 0 ? $"at {Format.Clock(lastPosition.Value)}" : null, Format.Ago(item.At) }.Where(s => s is not null));

        var body = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 4, 0) };
        var title = Ui.Text(item.DisplayName, "TitleText", "Text", 15, FontWeights.SemiBold);
        title.ToolTip = item.TorrentName ?? item.Title;
        body.Children.Add(title);
        if (item.TitleName is not null) body.Children.Add(Ui.Text(item.TorrentName ?? item.Title, "Meta", "Faint", 11));
        if (progressLabel is not null)
        {
            var label = Ui.Text(progressLabel, "Meta", "Accent", 11, FontWeights.Medium);
            label.Margin = new Thickness(0, 6, 0, 4);
            body.Children.Add(label);
            if (lastDuration > 0 && lastPosition > 0)
            {
                var bar = Ui.Bar(percent, 4);
                bar.MaxWidth = 360;
                bar.HorizontalAlignment = HorizontalAlignment.Left;
                bar.Width = 360;
                body.Children.Add(bar);
            }
        }
        var metaText = Ui.Text(meta, "Meta", "Faint", 11);
        metaText.Margin = new Thickness(0, 6, 0, 0);
        body.Children.Add(metaText);

        var art = Ui.Art(History.PosterOf(item, shell.SavedList, kin), item.DisplayName, 66, 99);
        var head = new DockPanel();
        DockPanel.SetDock(art, Dock.Left);
        head.Children.Add(art);

        var remove = Ui.Round("trash-2", () => shell.AskRemove(item), "Faint", "Control", 32, 14, "Remove from history");
        remove.VerticalAlignment = VerticalAlignment.Top;
        DockPanel.SetDock(remove, Dock.Right);
        head.Children.Add(remove);
        head.Children.Add(body);

        var headButton = Ui.Button(head, () => shell.OpenItem(item));
        headButton.ContextMenu = new ContextMenu
        {
            Items =
            {
                Menu("Open title page", () => shell.OpenItem(item)),
                Menu("Play last file", () => { if (item.LastFileIndex is int i) shell.PlayFile(item, i); else if (videos.Count > 0) shell.PlayFile(item, videos[0].Index); }),
                Menu("Remove from history", () => shell.AskRemove(item)),
            },
        };

        var card = new StackPanel { Children = { headButton } };

        if (videos.Count > 1)
        {
            var open = expanded.Contains(item.Uri);
            var more = new DockPanel { Margin = new Thickness(0, 12, 0, 0) };
            var toggle = Ui.Button(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children = { Ui.Text((open ? "Episodes" : $"{videos.Count} episodes").ToUpperInvariant(), "Overline"), Ui.Glyph(open ? "chevron-up" : "chevron-down", 14, "Faint") },
            }, () =>
            {
                if (!expanded.Remove(item.Uri)) expanded.Add(item.Uri);
                RenderList();
            });
            var dl = Ui.Round("download", () => shell.DownloadSeason(item, null), "Accent", "Control", 32, 14, "Download every episode");
            DockPanel.SetDock(dl, Dock.Right);
            more.Children.Add(dl);
            more.Children.Add(toggle);
            card.Children.Add(Divider());
            card.Children.Add(more);
            if (open) card.Children.Add(Files(item, videos));
        }
        else if (videos.Count == 1)
        {
            var dl = Ui.Round("download", () => shell.DownloadFile(item, videos[0].Index), "Accent", "Control", 32, 14, "Download");
            dl.HorizontalAlignment = HorizontalAlignment.Right;
            dl.Margin = new Thickness(0, 12, 0, 0);
            card.Children.Add(Divider());
            card.Children.Add(dl);
        }

        var border = Ui.Card(card, "Surface", 10, new Thickness(10));
        border.Margin = new Thickness(0, 0, 12, 12);
        border.VerticalAlignment = VerticalAlignment.Top;
        return border;
    }

    static MenuItem Menu(string header, Action action)
    {
        var item = new MenuItem { Header = header };
        item.Click += (_, _) => action();
        return item;
    }

    static FrameworkElement Divider()
    {
        var line = Ui.Brushed(new Border { Height = 1, Margin = new Thickness(0, 12, 0, 0) }, "Line");
        return line;
    }

    FrameworkElement Files(HistoryItem item, List<TorrentFileInfo> videos)
    {
        var stack = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        foreach (var group in Pick.GroupByFolder(videos))
        {
            var key = item.Uri + "|" + (group.Dir.Length > 0 ? group.Dir : group.Folder);
            var folderOpen = group.Folder.Length == 0 || openFolders.Contains(key);
            if (group.Folder.Length > 0)
            {
                var row = new DockPanel { Margin = new Thickness(4, 12, 4, 4) };
                var dl = Ui.Round("download", () => shell.DownloadSeason(item, group.Files.Select(f => f.Index).ToList()), "Accent", "Control", 32, 14, $"Download {group.Folder}");
                DockPanel.SetDock(dl, Dock.Right);
                row.Children.Add(dl);
                var bytes = group.Files.Sum(f => f.Size);
                var toggle = Ui.Button(new DockPanel
                {
                    Children =
                    {
                        Ui.Glyph(folderOpen ? "chevron-down" : "chevron-right", 14, "Faint"),
                        Ui.Text($"{group.Files.Count} · {Format.Bytes(bytes)}", "Overline"),
                        Ui.Text(group.Folder.ToUpperInvariant(), "Overline", "Muted"),
                    },
                }, () =>
                {
                    if (!openFolders.Remove(key)) openFolders.Add(key);
                    RenderList();
                });
                var inner = (DockPanel)toggle.Content;
                DockPanel.SetDock(inner.Children[0], Dock.Left);
                DockPanel.SetDock(inner.Children[1], Dock.Right);
                ((FrameworkElement)inner.Children[2]).Margin = new Thickness(6, 0, 6, 0);
                row.Children.Add(toggle);
                stack.Children.Add(row);
            }
            if (!folderOpen) continue;
            foreach (var file in group.Files) stack.Children.Add(FileRow(item, file));
        }
        return stack;
    }

    FrameworkElement FileRow(HistoryItem item, TorrentFileInfo file)
    {
        var position = item.PositionOf(file.Index);
        var duration = item.DurationOf(file.Index);
        var number = Search.ParseEpisode(file.Name)?.Episode ?? Search.ParseAbsolute(file.Name) ?? file.Index + 1;
        var mark = Ui.Brushed(new Border
        {
            Width = 36,
            Height = 36,
            CornerRadius = new CornerRadius(8),
            Child = new Grid { Children = { Ui.Text(number.ToString("00"), "Meta", "Faint", 12, FontWeights.SemiBold) } },
        }, "Control");
        ((FrameworkElement)((Grid)mark.Child).Children[0]).HorizontalAlignment = HorizontalAlignment.Center;
        ((FrameworkElement)((Grid)mark.Child).Children[0]).VerticalAlignment = VerticalAlignment.Center;

        var body = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 12, 0) };
        var name = Ui.Text(file.Name, "Label", "Text", 14);
        name.ToolTip = file.Name;
        body.Children.Add(name);
        body.Children.Add(Ui.Text(Format.Bytes(file.Size) + (position > 0 ? $" · {Format.Clock(position.Value)}" : ""), "Meta"));
        if (position > 0 && duration > 0)
        {
            var bar = Ui.Bar(position.Value / duration.Value * 100, 3);
            bar.Margin = new Thickness(0, 4, 0, 0);
            body.Children.Add(bar);
        }

        var dl = Ui.Round("download", () => shell.DownloadFile(item, file.Index), "Muted", "Control", 32, 14, "Download");
        var play = Ui.Brushed(new Border { Width = 32, Height = 32, CornerRadius = new CornerRadius(16), Margin = new Thickness(6, 0, 0, 0), Child = new Grid { Children = { Ui.Glyph("play", 14, "OnAccent") } } }, "Accent");
        var dock = new DockPanel { Margin = new Thickness(4, 6, 4, 6) };
        DockPanel.SetDock(mark, Dock.Left);
        DockPanel.SetDock(play, Dock.Right);
        DockPanel.SetDock(dl, Dock.Right);
        dock.Children.Add(mark);
        dock.Children.Add(play);
        dock.Children.Add(dl);
        dock.Children.Add(body);
        return Ui.Button(dock, () => shell.PlayFile(item, file.Index));
    }
}
