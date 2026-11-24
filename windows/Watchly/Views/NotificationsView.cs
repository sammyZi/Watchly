using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>The phone's notification center: Watchly's own notes and AniList's, newest first. Opening it marks both read.</summary>
public sealed class NotificationsView : UserControl
{
    readonly MainWindow shell = MainWindow.Shell;
    readonly StackPanel page = new() { Margin = new Thickness(24, 8, 24, 32), MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Left };
    static readonly Color AniListBlue = Color.FromRgb(0x02, 0xA9, 0xFF);
    List<AniList.Notice>? ani;
    string? aniError;
    long seenBefore;
    int aniUnread;

    public NotificationsView()
    {
        var (root, _, body) = Ui.Screen("Notifications");
        body.Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = page };
        Content = root;
        Inbox.Changed += () => Dispatcher.BeginInvoke(() => { if (IsVisible) Render(); });
        shell.SettingsChanged += () => { if (IsVisible) Render(); };
        IsVisibleChanged += async (_, _) =>
        {
            if (!IsVisible) return;
            // As they were on opening: what is new stays marked while the page is up.
            seenBefore = Inbox.ReadAt;
            aniUnread = AniList.Unread;
            Inbox.MarkRead();
            Render();
            if (!AniList.Connected) return;
            try { ani = await AniList.Notices(read: true); aniError = null; }
            catch { aniError = "Couldn't reach AniList"; }
            shell.RefreshNotificationsBadge();
            Render();
        };
    }

    sealed record Line(string Key, string? Title, string Text, long At, bool FromAniList, string? Image, bool Unread, Action? Open);

    void Render()
    {
        page.Children.Clear();
        var notes = Inbox.Items;
        var lines = notes.Select(n => new Line("w:" + n.Id, n.Title, n.Text, n.At, false, null, n.At > seenBefore,
                n.Imdb is { } id ? () => shell.OpenTitle(new Title { Imdb = id, Kind = n.Kind ?? "tv", Name = n.Title }) : null))
            .Concat((ani ?? new()).Select((n, i) => new Line("a:" + n.Id, null, n.Text, n.At, true, n.Poster, i < aniUnread,
                n.Imdb is { } id ? () => shell.OpenTitle(new Title { Imdb = id, Kind = n.Kind ?? "tv" }) : () => Ui.Open(n.Url))))
            .OrderByDescending(l => l.At).ToList();

        var fresh = lines.Count(l => l.Unread);
        var alerts = Ui.Chip(shell.Settings.EpisodeAlerts ? "New-episode alerts: On" : "New-episode alerts: Off", shell.Settings.EpisodeAlerts,
            () => shell.ChangeSettings(shell.Settings with { EpisodeAlerts = !shell.Settings.EpisodeAlerts }), style: "ChoiceChip");
        var bar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 16), Children = { alerts } };
        if (notes.Count > 0)
        {
            var clear = Ui.Chip("Clear", false, Inbox.Clear, style: "ChoiceChip");
            clear.Margin = new Thickness(8, 0, 0, 0);
            bar.Children.Add(clear);
        }
        page.Children.Add(Ui.Text(fresh > 0 ? $"{fresh} new" : "All caught up", "Meta", fresh > 0 ? "Accent" : "Faint", 13));
        page.Children.Add(new Border { Height = 10 });
        page.Children.Add(bar);

        if (lines.Count == 0)
        {
            var empty = Ui.Text("New episodes of shows you follow show here" + (AniList.Connected ? ", with your AniList notifications." : ". Sign in to AniList in Settings to see its notifications too."), "Body", "Faint", 14);
            empty.TextWrapping = TextWrapping.Wrap;
            page.Children.Add(empty);
            if (aniError is not null) page.Children.Add(Ui.Text(aniError, "Meta", "Danger", 13));
            return;
        }
        var today = DateTimeOffset.Now.Date;
        foreach (var group in lines.GroupBy(l =>
                 {
                     var at = DateTimeOffset.FromUnixTimeMilliseconds(l.At).LocalDateTime;
                     return at >= today ? "TODAY" : at >= today.AddDays(-6) ? "THIS WEEK" : "EARLIER";
                 }))
        {
            var heading = Ui.Text(group.Key, "Meta", "Faint", 11, FontWeights.SemiBold);
            heading.Margin = new Thickness(2, 10, 0, 6);
            page.Children.Add(heading);
            foreach (var line in group) page.Children.Add(Row(line));
        }
    }

    static FrameworkElement Row(Line line)
    {
        var mark = line.FromAniList ? AniListBlue : ((SolidColorBrush)Application.Current.FindResource("Accent")).Color;
        FrameworkElement tile = line.Image is { } img
            ? Ui.Art(img, line.Text, 44, 60, 10)
            : new Border
            {
                Width = 44, Height = 44, CornerRadius = new CornerRadius(10), Background = new SolidColorBrush(Color.FromArgb(0x24, mark.R, mark.G, mark.B)),
                Child = new Icon { Kind = line.Key.StartsWith("w:ep:") ? "tv" : "inbox", Size = 20, Foreground = new SolidColorBrush(mark), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
            };
        var words = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        if (line.Title is not null) words.Children.Add(Ui.Text(line.Title, "Label", "Text", 15, FontWeights.SemiBold));
        var text = Ui.Text(line.Text, line.Title is null ? "Label" : "Meta", line.Title is null ? "Text" : "Muted", line.Title is null ? 14 : 13);
        text.TextWrapping = TextWrapping.Wrap;
        words.Children.Add(text);
        words.Children.Add(Ui.Text($"{(line.FromAniList ? "AniList" : "Watchly")} · {Ago(line.At)}", "Meta", "Faint", 12));
        DockPanel.SetDock(tile, Dock.Left);
        var dock = new DockPanel { Children = { tile } };
        if (line.Unread)
        {
            var dot = new Border { Width = 8, Height = 8, CornerRadius = new CornerRadius(4), Background = new SolidColorBrush(mark), Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(dot, Dock.Right);
            dock.Children.Add(dot);
        }
        dock.Children.Add(words);
        var card = Ui.Card(dock, line.Unread ? "SurfaceAlt" : "Surface", 14, new Thickness(12));
        if (line.Open is null)
        {
            card.Margin = new Thickness(0, 0, 0, 8);
            return card;
        }
        var b = Ui.Button(card, line.Open, "Bare");
        b.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        b.Margin = new Thickness(0, 0, 0, 8);
        return b;
    }

    /// <summary>"now", "5 min ago", "3 h ago", "2 d ago".</summary>
    static string Ago(long at)
    {
        var m = (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - at) / 60_000;
        return m < 1 ? "now" : m < 60 ? $"{m} min ago" : m < 24 * 60 ? $"{m / 60} h ago" : $"{m / (24 * 60)} d ago";
    }
}
