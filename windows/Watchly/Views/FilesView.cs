using System.Windows;
using System.Windows.Controls;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>src/screens/FilesScreen.tsx — the files inside a torrent, grouped by folder.</summary>
public sealed class FilesView : UserControl
{
    public FilesView(TorrentMeta meta, string mode, Action<TorrentFileInfo> play, Action<TorrentFileInfo> download, Action downloadAll, Action back)
    {
        var downloading = mode == "download";
        var videos = meta.Files.Where(f => f.IsVideo).ToList();
        var videoBytes = videos.Sum(f => f.Size);
        var others = meta.Files.Count - videos.Count;

        var header = new DockPanel { Margin = new Thickness(24, 20, 24, 12) };
        var backButton = Ui.Round("arrow-left", back, "Text", "Tint", 40, 20, "Back");
        DockPanel.SetDock(backButton, Dock.Left);
        header.Children.Add(backButton);
        if (videos.Count > 1)
        {
            var all = Ui.Button(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children = { Ui.Glyph("download", 12, "Accent"), Ui.Text($"All {videos.Count}", "Meta", "Accent", 12, FontWeights.SemiBold) },
            }, downloadAll, tooltip: "Download every video");
            ((StackPanel)all.Content).Children[1].SetValue(MarginProperty, new Thickness(5, 0, 0, 0));
            all.Tag = 999;
            all.Padding = new Thickness(10, 6, 10, 6);
            all.SetResourceReference(BackgroundProperty, "AccentSoft");
            all.VerticalAlignment = VerticalAlignment.Center;
            DockPanel.SetDock(all, Dock.Right);
            header.Children.Add(all);
        }
        var name = TitleText.SearchableName(meta.Name);
        header.Children.Add(new StackPanel
        {
            Margin = new Thickness(12, 0, 12, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Children =
            {
                Ui.Text(name.Length > 0 ? name : meta.Name, "TitleText", "Text", 16, FontWeights.SemiBold),
                Ui.Text(string.Join("  ·  ", new[] { $"{videos.Count} {(videos.Count == 1 ? "video" : "videos")}", videoBytes > 0 ? Format.Bytes(videoBytes) : null, others > 0 ? $"{others} other" : null }.Where(s => s is not null)), "Meta"),
            },
        });

        var list = new StackPanel { Margin = new Thickness(24, 0, 24, 40), MaxWidth = 1000, HorizontalAlignment = HorizontalAlignment.Left };
        var groups = Pick.GroupByFolder(meta.Files);
        foreach (var group in groups)
        {
            if (groups.Count > 1)
            {
                var folder = new DockPanel { Margin = new Thickness(4, 16, 4, 8) };
                var icon = Ui.Glyph("folder", 13, "Faint");
                icon.Margin = new Thickness(0, 0, 8, 0);
                DockPanel.SetDock(icon, Dock.Left);
                var count = Ui.Text(group.Files.Count.ToString(), "Meta", "Faint", 11);
                DockPanel.SetDock(count, Dock.Right);
                folder.Children.Add(icon);
                folder.Children.Add(count);
                folder.Children.Add(Ui.Text((group.Folder.Length > 0 ? group.Folder : meta.Name).ToUpperInvariant(), "Overline"));
                list.Children.Add(folder);
            }
            foreach (var file in group.Files.OrderByDescending(f => f.IsVideo))
                list.Children.Add(Row(file, downloading, play, download));
        }

        var root = new DockPanel();
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        root.Children.Add(new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list });
        Content = root;
    }

    static FrameworkElement Row(TorrentFileInfo file, bool downloading, Action<TorrentFileInfo> play, Action<TorrentFileInfo> download)
    {
        var at = file.IsVideo ? Search.ParseEpisode(file.Name) : null;
        var number = at?.Episode ?? (file.IsVideo ? Search.ParseAbsolute(file.Name) : null);
        var mark = Ui.Brushed(new Border { Width = 56, Height = 56, CornerRadius = new CornerRadius(8) }, file.IsVideo ? "AccentSoft" : file.IsSubtitle ? "Surface" : "Control");
        mark.Child = number is not null
            ? new Grid { Children = { Center(Ui.Text(number.Value.ToString(), "TitleText", "Accent", 16, FontWeights.SemiBold)) } }
            : new Grid { Children = { Ui.Glyph(file.IsVideo ? (downloading ? "download" : "play") : file.IsSubtitle ? "type" : "file", 14, file.IsVideo ? "Accent" : "Faint") } };
        ((Grid)mark.Child).Children[0].SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);

        var name = Ui.Text(file.Name, "Body", file.IsVideo ? "Text" : "Muted", 14, file.IsVideo ? FontWeights.Medium : FontWeights.Normal);
        name.TextWrapping = TextWrapping.NoWrap;
        name.TextTrimming = TextTrimming.CharacterEllipsis;
        name.ToolTip = file.Path;
        var detail = string.Join("  ·  ", new[] { at is null ? null : Search.EpisodeTag(at.Value.Season, at.Value.Episode), Format.Bytes(file.Size), file.IsSubtitle ? "subtitle" : !file.IsVideo ? "not playable" : null }.Where(s => s is not null));
        var body = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 12, 0), Children = { name, Ui.Text(detail, "Meta") } };

        var dock = new DockPanel();
        DockPanel.SetDock(mark, Dock.Left);
        dock.Children.Add(mark);
        if (file.IsVideo)
        {
            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            if (!downloading)
            {
                var save = Ui.Round("download", () => download(file), "Muted", "Control", 32, 14, "Download");
                save.Margin = new Thickness(0, 0, 4, 0);
                actions.Children.Add(save);
            }
            actions.Children.Add(Ui.Brushed(new Border { Width = 32, Height = 32, CornerRadius = new CornerRadius(16), Child = new Grid { Children = { Ui.Glyph(downloading ? "download" : "chevron-right", 14, downloading ? "OnAccent" : "Accent") } } }, downloading ? "Accent" : "AccentSoft"));
            ((Grid)((Border)actions.Children[^1]).Child).Children[0].SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
            DockPanel.SetDock(actions, Dock.Right);
            dock.Children.Add(actions);
        }
        dock.Children.Add(body);

        var card = new Border { Child = dock, CornerRadius = new CornerRadius(12), Padding = new Thickness(8), Margin = new Thickness(0, 0, 0, 8) };
        if (!file.IsVideo)
        {
            card.Opacity = 0.5;
            return card;
        }
        card.SetResourceReference(Border.BackgroundProperty, "Surface");
        return Ui.Button(card, () => (downloading ? download : play)(file));
    }

    static T Center<T>(T e) where T : FrameworkElement
    {
        e.HorizontalAlignment = HorizontalAlignment.Center;
        e.VerticalAlignment = VerticalAlignment.Center;
        return e;
    }
}
