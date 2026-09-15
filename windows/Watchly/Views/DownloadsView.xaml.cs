using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Microsoft.Win32;
using Watchly.Controls;
using Watchly.Core;
using Watchly.Engine;

namespace Watchly.Views;

/// <summary>
/// src/screens/DownloadsScreen.tsx: what is downloading now, with pause, stop
/// and per-file skip, and everything already saved grouped by show and season.
/// </summary>
public partial class DownloadsView : UserControl
{
    readonly MainWindow shell = MainWindow.Shell;
    readonly HashSet<string> openShows = new();

    public DownloadsView()
    {
        InitializeComponent();
        ActiveList.ItemsSource = Downloads.Instance.Live;
        Downloads.Instance.Live.CollectionChanged += (_, _) => RenderSaved();
        SavedDownloads.Changed += () => Dispatcher.BeginInvoke(RenderSaved);
        IsVisibleChanged += (_, _) => { if (IsVisible) { RenderSaved(); ShowFolder(); } };
        shell.SettingsChanged += ShowFolder;
        RenderSaved();
        ShowFolder();
    }

    void ShowFolder()
    {
        FolderText.Text = $"Saving to {shell.Settings.DownloadDir}";
        FolderText.ToolTip = shell.Settings.DownloadDir;
    }

    void OnChangeFolder(object sender, RoutedEventArgs e)
    {
        var dir = shell.Settings.DownloadDir;
        var dialog = new OpenFolderDialog { Title = "Where should downloads go?", InitialDirectory = Directory.Exists(dir) ? dir : null };
        if (dialog.ShowDialog() != true) return;
        shell.ChangeSettings(shell.Settings with { DownloadFolder = dialog.FolderName });
        // A download that failed on the old folder picks the new one up straight away.
        if (sender is FrameworkElement { DataContext: ActiveDownload { HasError: true } row }) _ = Downloads.Instance.Retry(row);
    }

    static ActiveDownload RowOf(object sender) => (ActiveDownload)((FrameworkElement)sender).DataContext;

    async void OnPauseResume(object sender, RoutedEventArgs e)
    {
        var row = RowOf(sender);
        if (row.HasError) await Downloads.Instance.Retry(row);
        else if (row.Paused) await Downloads.Instance.Resume(row);
        else await Downloads.Instance.Pause(row);
    }

    void OnStop(object sender, RoutedEventArgs e)
    {
        var row = RowOf(sender);
        shell.Confirm("Stop download?", $"{row.Headline} will be removed. Finished files stay; unfinished ones are deleted.", "Stop",
            () => _ = Downloads.Instance.Stop(row), "x");
    }

    void OnOpenFolder(object sender, RoutedEventArgs e) => Reveal(RowOf(sender).SaveDirOrDefault());

    async void OnQueueAction(object sender, RoutedEventArgs e)
    {
        var file = (QueueFile)((FrameworkElement)sender).DataContext;
        var row = Downloads.Instance.Live.FirstOrDefault(r => r.Queue.Contains(file));
        if (row is null) return;
        if (file.IsOff) await Downloads.Instance.Include(row, file.Index);
        else if (file.IsDone && Downloads.FilePath(row, file.Index) is { } path) shell.PlayLocal(path, file.Title);
        else await Downloads.Instance.Skip(row, file.Index);
    }

    void OnAdd(object sender, RoutedEventArgs e) => Add(AddBox.Text.Trim());

    void OnAddKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) Add(AddBox.Text.Trim());
    }

    void OnBrowse(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Filter = "Torrent files (*.torrent)|*.torrent", Title = "Download a torrent" };
        if (dialog.ShowDialog() == true) Add(dialog.FileName);
    }

    void Add(string source)
    {
        if (!TitleText.IsTorrentSource(source))
        {
            shell.Toast("That is not a magnet link or a .torrent", true);
            return;
        }
        AddBox.Clear();
        Downloads.Instance.Start(new DownloadJob { Uri = source, All = true, Title = TitleText.TitleOf(source) });
    }

    static void Reveal(string path)
    {
        try
        {
            if (File.Exists(path)) Process.Start("explorer.exe", $"/select,\"{path}\"");
            else
            {
                Directory.CreateDirectory(path);
                Process.Start("explorer.exe", $"\"{path}\"");
            }
        }
        catch { }
    }

    static string ShowTitle(SavedDownload d)
    {
        if (!string.IsNullOrEmpty(d.TitleName)) return d.TitleName!;
        var name = TitleText.SearchableName(d.Name);
        return name.Length > 0 ? name : d.Name;
    }

    static string SeasonLabel(int? season) => season is null ? "" : season == 0 ? "Specials" : $"Season {season}";

    void RenderSaved()
    {
        var items = SavedDownloads.Load();
        SavedList.Children.Clear();
        SavedHeading.Visibility = items.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptySlot.Content = items.Count == 0 && Downloads.Instance.Live.Count == 0
            ? Ui.Empty("download", "Nothing saved yet", "Downloads you start live here until you delete them. Paste a magnet link above to download any torrent.")
            : null;

        var shows = items
            .GroupBy(ShowTitle)
            .Select(g => (Title: g.Key, Poster: g.Select(d => d.Poster).FirstOrDefault(p => p is not null), Seasons: g
                .GroupBy(d => Search.ParseEpisode(d.Name)?.Season)
                .OrderBy(s => s.Key ?? 999)
                .Select(s => (Season: s.Key, Items: s.OrderBy(d => Search.ParseEpisode(d.Name)?.Episode ?? Search.ParseAbsolute(d.Name) ?? 0).ThenBy(d => d.Name, Comparer<string>.Create(Format.Natural)).ToList()))
                .ToList()));

        foreach (var show in shows)
        {
            var count = show.Seasons.Sum(s => s.Items.Count);
            if (count == 1)
            {
                SavedList.Children.Add(SavedRow(show.Seasons[0].Items[0], false));
                continue;
            }
            var open = openShows.Contains(show.Title);
            var meta = show.Seasons.Count > 1 ? $"{show.Seasons.Count} seasons · {count} episodes"
                : show.Seasons[0].Season is not null ? $"{SeasonLabel(show.Seasons[0].Season)} · {count} episodes"
                : $"{count} files";

            var head = new DockPanel();
            var art = Ui.Art(show.Poster, show.Title, 44, 66, 8);
            DockPanel.SetDock(art, Dock.Left);
            head.Children.Add(art);
            var paths = show.Seasons.SelectMany(s => s.Items).Select(d => d.Path).ToList();
            var delete = Ui.Round("trash-2", () => shell.Confirm("Delete downloads?", $"{show.Title} will be removed from this list and from disk.", "Delete",
                () => SavedDownloads.Remove(paths)), "Faint", "Control", 32, 15, $"Delete {show.Title}");
            DockPanel.SetDock(delete, Dock.Right);
            head.Children.Add(delete);
            var chevron = Ui.Glyph(open ? "chevron-up" : "chevron-down", 16, "Faint");
            chevron.Margin = new Thickness(8, 0, 8, 0);
            DockPanel.SetDock(chevron, Dock.Right);
            head.Children.Add(chevron);
            head.Children.Add(new StackPanel
            {
                Margin = new Thickness(12, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center,
                Children = { Ui.Text(show.Title, "Label", "Text", 14, FontWeights.SemiBold), Ui.Text(meta, "Meta") },
            });
            var title = show.Title;
            var toggle = Ui.Button(head, () =>
            {
                if (!openShows.Remove(title)) openShows.Add(title);
                RenderSaved();
            });

            var stack = new StackPanel { Children = { toggle } };
            if (open)
            {
                foreach (var season in show.Seasons)
                {
                    if (show.Seasons.Count > 1 && season.Season is not null)
                    {
                        var label = Ui.Text(SeasonLabel(season.Season).ToUpperInvariant(), "Overline", "Muted");
                        label.Margin = new Thickness(0, 10, 0, 2);
                        stack.Children.Add(label);
                    }
                    foreach (var entry in season.Items) stack.Children.Add(SavedRow(entry, true));
                }
            }
            var card = Ui.Card(stack, "Surface", 18, new Thickness(12));
            card.Margin = new Thickness(0, 0, 12, 12);
            card.VerticalAlignment = VerticalAlignment.Top;
            SavedList.Children.Add(card);
        }
    }

    FrameworkElement SavedRow(SavedDownload entry, bool nested)
    {
        var at = Search.ParseEpisode(entry.Name);
        var ep = at is null ? Search.ParseAbsolute(entry.Name) is int n ? $"Episode {n}" : null
            : at.Value.Season == 0 ? $"Special {at.Value.Episode}" : $"Episode {at.Value.Episode}";
        var dock = new DockPanel();
        if (!nested)
        {
            var art = Ui.Art(entry.Poster, entry.TitleName ?? entry.Name, 44, 66, 8);
            DockPanel.SetDock(art, Dock.Left);
            dock.Children.Add(art);
        }
        var delete = Ui.Round("trash-2", () => shell.Confirm("Delete download?", $"{ShowTitle(entry)} will be removed from this list and from disk.", "Delete",
            () => SavedDownloads.Remove(new[] { entry.Path })), "Faint", "Control", 32, 15, "Delete");
        var reveal = Ui.Round("folder", () => Reveal(entry.Path), "Muted", "Control", 32, 14, "Show in folder");
        reveal.Margin = new Thickness(0, 0, 6, 0);
        var play = Ui.Brushed(new Border { Width = 36, Height = 36, CornerRadius = new CornerRadius(18), Margin = new Thickness(0, 0, 8, 0), Child = new Grid { Children = { Ui.Glyph("play", 14, "Accent") } } }, "AccentSoft");
        foreach (var e in new FrameworkElement[] { delete, reveal, play })
        {
            DockPanel.SetDock(e, Dock.Right);
            dock.Children.Add(e);
        }
        var name = Ui.Text(nested && ep is not null ? ep : ShowTitle(entry), "Label", "Text", 14, FontWeights.SemiBold);
        name.ToolTip = entry.Name;
        dock.Children.Add(new StackPanel
        {
            Margin = new Thickness(nested ? 0 : 12, 0, 8, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Children =
            {
                name,
                Ui.Text(string.Join(" · ", new[] { nested ? null : ep, entry.Size > 0 ? Format.Bytes(entry.Size) : null, Format.Ago(entry.At) }.Where(s => s is not null)), "Meta"),
            },
        });
        var button = Ui.Button(dock, () => shell.PlayLocal(entry.Path, Path.GetFileNameWithoutExtension(entry.Name)));
        if (nested)
        {
            button.Margin = new Thickness(0, 8, 0, 0);
            return button;
        }
        var card = Ui.Card(button, "Surface", 18, new Thickness(12));
        card.Margin = new Thickness(0, 0, 12, 12);
        card.VerticalAlignment = VerticalAlignment.Top;
        return card;
    }
}

static class ActiveDownloadExtensions
{
    public static string SaveDirOrDefault(this ActiveDownload row) => row.SaveDir.Length > 0 ? row.SaveDir : AppSettings.Load().DownloadDir;
}
