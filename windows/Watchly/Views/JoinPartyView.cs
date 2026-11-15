using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// An invite, opened — the phone's join screen: first "Connecting…", then what the party is watching,
/// the film's own art, who leads it and who is there, where the film has got to, and one big Join.
/// Nothing plays until Join; Not now (or Esc) leaves the room. Any web page could hand Watchly a party
/// link, so what it would play is always shown first.
/// </summary>
public sealed class JoinPartyView : Grid
{
    readonly Room room;
    readonly Action join;
    readonly Action cancel;
    readonly DispatcherTimer tick = new() { Interval = TimeSpan.FromSeconds(1) };
    readonly DateTime opened = DateTime.UtcNow;
    readonly TextBlock status = Text("", 14, Brushes.White, FontWeights.SemiBold);
    PartyState? state;
    bool waitedLong;

    public JoinPartyView(Room room, Action join, Action cancel)
    {
        this.room = room;
        this.join = join;
        this.cancel = cancel;
        Background = Brushes.Black;
        Focusable = true;
        state = room.LastState;
        KeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) { cancel(); e.Handled = true; }
            if (e.Key == Key.Enter && state is not null) { join(); e.Handled = true; }
        };
        room.Changed += Render;
        tick.Tick += (_, _) =>
        {
            if (state is null && !waitedLong && (DateTime.UtcNow - opened).TotalSeconds > 12)
            {
                waitedLong = true;
                Render();
            }
            UpdateStatus();
        };
        Loaded += (_, _) => { tick.Start(); Focus(); Keyboard.Focus(this); };
        Unloaded += (_, _) => { tick.Stop(); room.Changed -= Render; };
        Render();
    }

    public void Update(PartyState s)
    {
        state = s;
        Render();
    }

    void UpdateStatus()
    {
        if (state is not { } s) return;
        status.Text = $"{(s.Playing ? "Playing now" : "Paused")} at {Format.Clock(s.PosAt(room.Now()))}";
    }

    void Render()
    {
        Children.Clear();
        var s = state;
        var imdb = s?.Media.Imdb;
        // The film's backdrop behind it all, blurred and darkened, as the player's loading screen has it.
        if (imdb is { Length: > 0 } && ImageUrl.Load($"https://images.metahub.space/background/medium/{imdb}/img", 1280) is { } backdrop)
            Children.Add(new Grid
            {
                ClipToBounds = true,
                Children = { new Border { Margin = new Thickness(-30), Opacity = 0.55, Background = new ImageBrush(backdrop) { Stretch = Stretch.UniformToFill }, Effect = new BlurEffect { Radius = 22 } } },
            });
        Children.Add(new Border
        {
            Background = new LinearGradientBrush(new GradientStopCollection
            {
                new(Color.FromArgb(0x60, 0, 0, 0), 0), new(Color.FromArgb(0xc0, 0, 0, 0), 0.55), new(Color.FromArgb(0xff, 0, 0, 0), 1),
            }, 90),
        });

        var close = Controls.Ui.Button(new Border { Width = 40, Height = 40, CornerRadius = new CornerRadius(20), Background = White(0x22), Child = Center(new Controls.Icon { Kind = "x", Size = 18, Foreground = Brushes.White }) }, cancel, "Bare", "Not now (Esc)");
        close.HorizontalAlignment = HorizontalAlignment.Left;
        close.VerticalAlignment = VerticalAlignment.Top;
        close.Margin = new Thickness(24);
        Children.Add(close);

        var body = new StackPanel { Width = 460, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var badge = new Border
        {
            CornerRadius = new CornerRadius(14), Background = new SolidColorBrush(Color.FromArgb(0x40, 0xe5, 0x09, 0x14)), Padding = new Thickness(12, 5, 12, 5),
            HorizontalAlignment = HorizontalAlignment.Center,
            Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { new Controls.Icon { Kind = "users", Size = 13, Foreground = Brushes.White }, Text("  WATCH PARTY", 11, Brushes.White, FontWeights.Bold) } },
        };
        body.Children.Add(badge);

        if (s is null)
        {
            var words = !waitedLong ? ("Connecting to the party…", "Finding the room and asking the host what's on.")
                : room.Connected ? ("Connected — waiting for the host to play something", "Keep this open — the film shows here as soon as they start it.")
                : ("Can't reach the party yet", "Check this PC's connection. Watchly keeps trying.");
            var spinner = new Controls.Icon { Kind = "users", Size = 40, Foreground = White(0x90), Margin = new Thickness(0, 60, 0, 0), HorizontalAlignment = HorizontalAlignment.Center };
            body.Children.Add(spinner);
            body.Children.Add(Centered(Text(words.Item1, 22, Brushes.White, FontWeights.SemiBold), 18));
            body.Children.Add(Centered(Text(words.Item2, 13, White(0xb0)), 6));
            body.Children.Add(Centered(Pill("Not now", cancel, false), 36));
            Children.Add(body);
            return;
        }

        var poster = imdb is { Length: > 0 } ? $"https://images.metahub.space/poster/medium/{imdb}/img" : s.Media.Poster;
        var art = Controls.Ui.Art(poster, s.Media.Title, 170, 255, 12);
        art.Margin = new Thickness(0, 24, 0, 0);
        art.HorizontalAlignment = HorizontalAlignment.Center;
        art.Effect = new DropShadowEffect { BlurRadius = 30, ShadowDepth = 6, Opacity = 0.6 };
        body.Children.Add(art);
        body.Children.Add(Centered(Text(s.Media.Title, 28, Brushes.White, FontWeights.Bold), 22));

        UpdateStatus();
        var dot = new Border { Width = 8, Height = 8, CornerRadius = new CornerRadius(4), Background = s.Playing ? new SolidColorBrush(Color.FromRgb(0x30, 0xd1, 0x58)) : White(0x80), VerticalAlignment = VerticalAlignment.Center };
        body.Children.Add(Centered(new Border
        {
            CornerRadius = new CornerRadius(16), Background = White(0x1a), Padding = new Thickness(14, 7, 14, 7),
            Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { dot, Pad(Detach(status), 8) } },
        }, 14));

        // Who leads, and who is there.
        var people = room.Members.Values.OrderByDescending(m => m.Leader).ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase).ToList();
        var host = s.By.Length > 0 ? s.By : people.FirstOrDefault(m => m.Leader)?.Name ?? "The host";
        var others = people.Where(m => !m.Leader && !m.Me).ToList();
        var faces = new Canvas { Height = 28, Width = 28 + 18 * Math.Max(0, Math.Min(4, others.Count) - 1) };
        for (var i = 0; i < Math.Min(4, others.Count); i++)
        {
            var face = PlayerView.NameDot(others[i].Name, 28);
            Canvas.SetLeft(face, 18 * i);
            faces.Children.Add(face);
        }
        var count = Math.Max(room.People, people.Count);
        var who = others.Count == 0 ? "You'd be the first to join"
            : $"{string.Join(", ", others.Take(2).Select(o => o.Name))}{(others.Count > 2 ? $" and {others.Count - 2} more" : "")} · {count} watching";
        var hostRow = new DockPanel();
        var crown = Text("★", 18, new SolidColorBrush(Color.FromRgb(0xf5, 0xc5, 0x42)), FontWeights.Bold);
        crown.VerticalAlignment = VerticalAlignment.Center;
        DockPanel.SetDock(crown, Dock.Right);
        hostRow.Children.Add(crown);
        hostRow.Children.Add(new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Children = { PlayerView.NameDot(host, 40), Pad(new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { Text(host, 15, Brushes.White, FontWeights.Bold), Text("invited you to watch together", 12, White(0xa6)) } }, 12) },
        });
        body.Children.Add(new Border
        {
            CornerRadius = new CornerRadius(16), Background = White(0x14), Padding = new Thickness(16), Margin = new Thickness(0, 26, 0, 0),
            Child = new StackPanel
            {
                Children =
                {
                    hostRow,
                    new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0), Children = { faces, Pad(Text(who, 12, White(0xcc)), 10) } },
                },
            },
        });

        var joinButton = Pill("▶  Join the party", join, true);
        joinButton.HorizontalAlignment = HorizontalAlignment.Stretch;
        joinButton.Margin = new Thickness(0, 26, 0, 0);
        body.Children.Add(joinButton);
        body.Children.Add(Centered(Pill("Not now", cancel, false), 10));
        body.Children.Add(Centered(Text("🔒 The film streams on this PC. Only play, pause and the spot are shared — encrypted, no account.", 11, White(0x80)), 18));
        Children.Add(new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = new Border { Padding = new Thickness(24), Child = body } });
    }

    static FrameworkElement Pill(string label, Action onClick, bool primary)
    {
        var text = Text(label, 15, Brushes.White, FontWeights.SemiBold);
        text.HorizontalAlignment = HorizontalAlignment.Center;
        var b = Controls.Ui.Button(new Border
        {
            Height = 46, CornerRadius = new CornerRadius(23), Padding = new Thickness(28, 0, 28, 0),
            Background = primary ? new SolidColorBrush(Color.FromRgb(0xe5, 0x09, 0x14)) : White(0x1f),
            Child = new Grid { VerticalAlignment = VerticalAlignment.Center, Children = { text } },
        }, onClick, "Bare", label);
        b.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        return b;
    }

    static TextBlock Text(string text, double size, Brush brush, FontWeight? weight = null) => new()
    {
        Text = text, FontSize = size, Foreground = brush, FontWeight = weight ?? FontWeights.Normal, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center,
    };

    static Brush White(byte alpha) => new SolidColorBrush(Color.FromArgb(alpha, 0xff, 0xff, 0xff));

    static FrameworkElement Centered(FrameworkElement e, double top)
    {
        e.HorizontalAlignment = HorizontalAlignment.Center;
        e.Margin = new Thickness(0, top, 0, 0);
        return e;
    }

    static FrameworkElement Center(FrameworkElement e)
    {
        e.HorizontalAlignment = HorizontalAlignment.Center;
        e.VerticalAlignment = VerticalAlignment.Center;
        return e;
    }

    static FrameworkElement Pad(FrameworkElement e, double left)
    {
        e.Margin = new Thickness(left, 0, 0, 0);
        return e;
    }

    static FrameworkElement Detach(FrameworkElement e)
    {
        if (e.Parent is Panel p) p.Children.Remove(e);
        else if (e.Parent is Border b) b.Child = null;
        return e;
    }
}
