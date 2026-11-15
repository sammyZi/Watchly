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
    readonly Action own;
    readonly Action cancel;
    readonly DispatcherTimer tick = new() { Interval = TimeSpan.FromSeconds(1) };
    readonly DateTime opened = DateTime.UtcNow;
    readonly TextBlock status = Ui.Text("", "Meta", "Muted", 13);
    PartyState? state;
    bool waitedLong;
    /// <summary>The film's full record — logo, facts, plot — once Cinemeta answers for the leader's imdb id.</summary>
    Title? title;
    string? titleFor;

    async void LoadTitle(string? imdb, string? kind)
    {
        if (imdb is not { Length: > 0 } || imdb == titleFor) return;
        titleFor = imdb;
        var t = await Meta.LoadTitle(imdb, kind ?? "movie");
        if (t is null || titleFor != imdb) return;
        title = t;
        Render();
    }

    public JoinPartyView(Room room, Action join, Action own, Action cancel)
    {
        this.room = room;
        this.join = join;
        this.own = own;
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
        if (s is not null)
        {
            AsDetail(s, imdb);
            return;
        }
        Background = Brushes.Black;
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

    }

    /// <summary>
    /// Once the leader has said what is on: the film's own Detail page (DetailView's layout — the backdrop on the
    /// right, the logo, facts and actions down the left) with Join where Play would be.
    /// </summary>
    void AsDetail(PartyState s, string? imdb)
    {
        SetResourceReference(BackgroundProperty, "Bg");
        LoadTitle(imdb, s.Media.Kind);

        // The backdrop stays put on the right, faded into the page, as DetailView's does.
        var stage = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition { Width = new GridLength(30, GridUnitType.Star) }, new ColumnDefinition { Width = new GridLength(70, GridUnitType.Star) } },
            RowDefinitions = { new RowDefinition { Height = new GridLength(90, GridUnitType.Star) }, new RowDefinition { Height = new GridLength(10, GridUnitType.Star) } },
        };
        var wide = new Border();
        var wideUrl = title?.Wide ?? (imdb is { Length: > 0 } ? $"https://images.metahub.space/background/medium/{imdb}/img" : null);
        if (wideUrl is not null && ImageUrl.Load(wideUrl, 1920) is { } image)
            wide.Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill, AlignmentY = AlignmentY.Top };
        var art = Ui.FadedArt(wide);
        Grid.SetColumn(art, 1);
        stage.Children.Add(art);
        Children.Add(stage);

        var head = new StackPanel { Width = 460, HorizontalAlignment = HorizontalAlignment.Left };
        head.Children.Add(new Border
        {
            CornerRadius = new CornerRadius(14), Background = new SolidColorBrush(Color.FromArgb(0xe0, 0xe5, 0x09, 0x14)), Padding = new Thickness(12, 5, 12, 5),
            HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 0, 0, 14),
            Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { new Controls.Icon { Kind = "users", Size = 13, Foreground = Brushes.White }, Text("  WATCH PARTY", 11, Brushes.White, FontWeights.Bold) } },
        });
        if (title is not null)
        {
            head.Children.Add(Ui.TitleMark(title, 80, 320));
            var facts = Ui.Facts(title);
            facts.Margin = new Thickness(0, 12, 0, 0);
            head.Children.Add(facts);
        }
        else
        {
            var name = Ui.Text(s.Media.Title, "TitleText", "Text", 34, FontWeights.Bold);
            name.TextWrapping = TextWrapping.Wrap;
            head.Children.Add(name);
        }

        // Who invited you, where the film has got to, and who else is there.
        var people = room.Members.Values.OrderByDescending(m => m.Leader).ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase).ToList();
        var host = s.By.Length > 0 ? s.By : people.FirstOrDefault(m => m.Leader)?.Name ?? "The host";
        var others = people.Where(m => !m.Leader && !m.Me).ToList();
        var count = Math.Max(room.People, people.Count);
        UpdateStatus();
        var dot = new Border { Width = 8, Height = 8, CornerRadius = new CornerRadius(4), Background = s.Playing ? new SolidColorBrush(Color.FromRgb(0x30, 0xd1, 0x58)) : new SolidColorBrush(Color.FromRgb(0x8a, 0x8a, 0x8a)), VerticalAlignment = VerticalAlignment.Center };
        var invited = Ui.Text($"{host} invited you to a watch party", "Body", "Text", 14, FontWeights.SemiBold);
        invited.TextTrimming = TextTrimming.CharacterEllipsis;
        var hostRow = new DockPanel();
        var face = PlayerView.NameDot(host, 40);
        DockPanel.SetDock(face, Dock.Left);
        hostRow.Children.Add(face);
        hostRow.Children.Add(Pad(new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            Children = { invited, new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 3, 0, 0), Children = { dot, Pad(Detach(status), 8) } } },
        }, 12));
        var card = new StackPanel { Children = { hostRow } };
        if (others.Count > 0)
        {
            var faces = new Canvas { Height = 26, Width = 26 + 16 * (Math.Min(4, others.Count) - 1) };
            for (var i = 0; i < Math.Min(4, others.Count); i++)
            {
                var f = PlayerView.NameDot(others[i].Name, 26);
                Canvas.SetLeft(f, 16 * i);
                faces.Children.Add(f);
            }
            var who = $"{string.Join(", ", others.Take(2).Select(o => o.Name))}{(others.Count > 2 ? $" and {others.Count - 2} more" : "")} · {count} watching";
            card.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0), Children = { faces, Pad(Ui.Text(who, "Meta", "Muted", 12), 10) } });
        }
        var cardBox = new Border { CornerRadius = new CornerRadius(14), Padding = new Thickness(14), Margin = new Thickness(0, 18, 0, 16), Child = card };
        cardBox.SetResourceReference(Border.BackgroundProperty, "Tint");
        head.Children.Add(cardBox);

        // Where Play and Resume are on the Detail page.
        head.Children.Add(Ui.Action("Join the party", "users", join, primary: true));
        // The leader's torrent may be slow here, or too big: any release of the same film keeps in step.
        if (imdb is { Length: > 0 })
        {
            var mine = Ui.Action("Pick my own source", "list", own);
            mine.Margin = new Thickness(0, 8, 0, 0);
            head.Children.Add(mine);
        }
        var notNow = Ui.Action("Not now", "x", cancel);
        notNow.Margin = new Thickness(0, 8, 0, 0);
        head.Children.Add(notNow);

        if (title?.Plot is { } plot)
        {
            var words = Ui.Text(plot, "Body", "Text");
            words.Opacity = 0.85;
            words.MaxHeight = 88;
            words.TextTrimming = TextTrimming.WordEllipsis;
            words.TextWrapping = TextWrapping.Wrap;
            words.Margin = new Thickness(0, 14, 0, 0);
            head.Children.Add(words);
        }
        if (title is { Directors.Count: > 0 })
        {
            var crew = Ui.Text((title.Kind == "tv" ? "Creators: " : "Director: ") + string.Join(", ", title.Directors.Take(2)), "Meta", "Faint", 13);
            crew.Margin = new Thickness(0, 8, 0, 0);
            head.Children.Add(crew);
        }
        var note = Ui.Text("🔒  The film streams on this PC. Only play, pause and the spot are shared — encrypted, no account.", "Meta", "Faint", 12);
        note.TextWrapping = TextWrapping.Wrap;
        note.Margin = new Thickness(0, 16, 0, 0);
        head.Children.Add(note);

        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = new StackPanel { HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(32, 72, 32, 40), Children = { head } } };
        Ui.SmoothWheel(scroll);
        Children.Add(scroll);

        // DetailView's Back: round, dark, over the art — here it is Not now.
        var back = Ui.Round("arrow-left", cancel, "Text", "Tint", 40, 20, "Not now (Esc)");
        ((Controls.Icon)back.Content).Foreground = Brushes.White;
        back.Background = new SolidColorBrush(Color.FromArgb(0x80, 0, 0, 0));
        back.HorizontalAlignment = HorizontalAlignment.Left;
        back.VerticalAlignment = VerticalAlignment.Top;
        back.Margin = new Thickness(20, 16, 0, 0);
        Children.Add(back);
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
