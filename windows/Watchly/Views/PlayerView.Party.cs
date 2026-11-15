using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using LibVLCSharp.Shared;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// The watch party in the player: its side of the sync (Core/PartyDriver, the phone's and TV's rules),
/// the party sheet with who is here, the chat and reactions, and the cards over the picture.
/// </summary>
public sealed partial class PlayerView
{
    readonly PartyDriver party;
    bool partyOpen;
    /// <summary>Reactions rise here, over the controls and never rebuilt with them.</summary>
    readonly Canvas floatLayer = new() { IsHitTestVisible = false };
    /// <summary>Kept across redraws, or a message half typed would go with every one.</summary>
    readonly TextBox chatBox = new()
    {
        FontSize = 13, MaxLength = Party.ChatMax, VerticalContentAlignment = VerticalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        Background = Brushes.Transparent, Foreground = Brushes.White, CaretBrush = Brushes.White, BorderThickness = new Thickness(0),
    };
    /// <summary>The box's look: a rounded field the height of the Send button beside it, with a word in it until typed in.</summary>
    Border? chatField;
    readonly TextBlock chatHint = new() { Text = "Message", FontSize = 13, Foreground = new SolidColorBrush(Color.FromArgb(0x70, 0xff, 0xff, 0xff)), VerticalAlignment = VerticalAlignment.Center, IsHitTestVisible = false };

    // ---------------------------------------------------------------- what the party asks of the player

    double IPartyPlayer.PartyTime => disposed ? 0 : Math.Max(0, SafeTime() / 1000.0);
    double IPartyPlayer.PartySpot => current / 1000.0;
    bool IPartyPlayer.PartyPaused => SafeState == VLCState.Paused;
    bool IPartyPlayer.PartyLoaded => started && !Preparing && duration > 0;
    bool IPartyPlayer.PartyStarved => buffering || stalled;
    bool IPartyPlayer.PartySeeking => (DateTime.UtcNow - seekAt).TotalMilliseconds < 1500;
    float IPartyPlayer.PartyRate => (float)rate;
    string? IPartyPlayer.PartyFile => SharedFile();
    string? IPartyPlayer.PartyImdb => request.Imdb;

    long SafeTime()
    {
        try { return player.Time; } catch { return current; }
    }

    /// <summary>The playing file's name as the torrent has it, without its folders — what the phone and TV send.</summary>
    string? SharedFile()
    {
        if (request.Torrent is { } t && request.FileIndex >= 0 && request.FileIndex < t.Files.Count)
            return System.IO.Path.GetFileName(t.Files[request.FileIndex].Path.ToString());
        return null;
    }

    PartyMedia? IPartyPlayer.PartyShare()
    {
        if (SharedFile() is not { } file) return null;
        var hash = request.Torrent?.InfoHashes.V1OrV2.ToHex();
        // The torrent's own trackers with it, where its peers are.
        var trackers = request.Torrent?.AnnounceUrls.SelectMany(tier => tier).Where(t => t.StartsWith("udp:") || t.StartsWith("http"));
        var uri = Party.ShareableUri(request.HistoryUri ?? request.Link, request.Torrent?.Name ?? file, hash, trackers);
        // And the working public trackers this PC adds itself: an old release's own (YTS's) are mostly dead,
        // and a guest given only those found a handful of peers. Fitted to the relay's message again after.
        if (uri is not null) uri = Party.ShareableUri(TitleText.WithTrackers(uri), request.Torrent?.Name ?? file, null);
        return uri is null ? null : new PartyMedia(uri, file, request.MetaTitle ?? request.Title, request.Imdb, request.Kind, null);
    }

    void IPartyPlayer.PartyPause(bool on)
    {
        if (disposed || !started) return;
        try
        {
            if (on) player.SetPause(true);
            else if (SafeState is VLCState.Ended or VLCState.Stopped) player.Play();
            else player.SetPause(false);
        }
        catch { }
        Render();
    }

    void IPartyPlayer.PartySteer(float r)
    {
        if (disposed) return;
        try { player.SetRate(r); } catch { }
    }

    void IPartyPlayer.PartyShowRate(float r)
    {
        rate = r;
        Render();
    }

    void IPartyPlayer.PartyJump(double seconds) => SeekTo((long)(seconds * 1000), fromParty: true);
    void IPartyPlayer.PartySay(string text) => ShowHud(text, 2400);
    void IPartyPlayer.PartySave() => SavePosition();
    void IPartyPlayer.PartyRedraw() => Ui(Refresh);

    // ---------------------------------------------------------------- the button and the sheet

    string PartyLabel => Party.Room is { } r ? $"Watch party · {Math.Max(r.People, r.Members.Count)}" + (r.Unread > 0 ? " · new" : "")
        : Party.Last is { Leader: true } ? "Reopen watch party" : Party.Last is not null ? "Rejoin watch party" : "Watch party";

    void OpenParty()
    {
        // The party just left, back again: a guest straight in, a leader's same room reopened.
        if (Party.Room is null && Party.Last is not null) Party.Rejoin();
        else if (Party.Room is null)
        {
            if (((IPartyPlayer)this).PartyShare() is null)
            {
                ShowHud("Only a torrent can be watched together — this one is a file or a plain link.", 3000);
                return;
            }
            var r = Party.Host();
            CopyInvite(r);
        }
        var open = !partyOpen;
        CloseSheets();
        partyOpen = open;
        party.PanelOpen = partyOpen;
        Render();
    }

    void CopyInvite(Room r)
    {
        try
        {
            Clipboard.SetText($"Watch {request.MetaTitle ?? request.Title} with me on Watchly: {r.Link}");
            ShowHud("Invite link copied — paste it to your friends", 3000);
        }
        catch
        {
            ShowHud("Couldn't copy the invite link", 2400);
        }
    }

    void SendChat()
    {
        Party.Room?.Say(chatBox.Text);
        chatBox.Text = "";
    }

    FrameworkElement PartySheet()
    {
        var r = Party.Room!;
        party.PanelOpen = true;
        r.Unread = 0;
        var people = r.Members.Values.OrderByDescending(m => m.Leader).ThenByDescending(m => m.Me).ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase).ToList();
        // As tall as the room above the controls (the sheet's margins, 68 and 96, its padding and this
        // margin): the chat takes what the rest leaves, and the message row is always in sight at the foot.
        var tall = Math.Max(320, ActualHeight / ControlsScale - 68 - 96 - 12 - 20);
        var sheet = new DockPanel { Width = 380, Height = tall, Margin = new Thickness(10), LastChildFill = true };
        var body = new StackPanel();
        DockPanel.SetDock(body, Dock.Top);

        var head = new DockPanel();
        var close = Circle(WIcon("x", 16), 30, () => { partyOpen = false; party.PanelOpen = false; Render(); }, 0x55, "Close");
        DockPanel.SetDock(close, Dock.Right);
        head.Children.Add(close);
        head.Children.Add(new StackPanel
        {
            Children =
            {
                WText(r.Leader ? "Your watch party" : "Watch party", 18, Brushes.White, FontWeights.Bold),
                WText(r.Connected ? $"{Math.Max(people.Count, r.People)} watching · encrypted" : "Reconnecting…", 11, r.Connected ? White(0x99) : Brushes.Orange),
            },
        });
        body.Children.Add(head);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 4) };
        buttons.Children.Add(Pill("copy", "Copy invite link", () => CopyInvite(r)));
        buttons.Children.Add(Pill("x", r.Leader ? "End party" : "Leave", () =>
        {
            partyOpen = false;
            Party.Leave();
            Render();
        }));
        body.Children.Add(buttons);

        if (party.HeldFor is { Count: > 0 } held)
        {
            var wait = new DockPanel { Margin = new Thickness(0, 8, 0, 0) };
            if (r.Leader)
            {
                var go = Pill(null, "Go on", party.GoOnWithout);
                DockPanel.SetDock(go, Dock.Right);
                wait.Children.Add(go);
            }
            wait.Children.Add(WText($"Waiting for {string.Join(", ", held)}", 12, Brushes.Orange));
            body.Children.Add(wait);
        }

        body.Children.Add(Overline("PEOPLE"));
        var list = new StackPanel();
        foreach (var m in people) list.Children.Add(PersonRow(m));
        if (people.Count <= 1) list.Children.Add(WText("No one else yet — copy the invite link and send it.", 12, White(0x99)));
        body.Children.Add(new ScrollViewer { MaxHeight = Math.Min(150, tall * 0.25), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list });

        var reactions = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
        foreach (var e in Party.Reactions)
        {
            var b = Controls.Ui.Button(Reaction(e, 30, new Thickness(6, 2, 6, 2)), () => r.React(e), "Bare", $"React {e}");
            reactions.Children.Add(b);
        }
        body.Children.Add(reactions);

        body.Children.Add(Overline("CHAT"));
        sheet.Children.Add(body);

        var s = shell.Settings;
        var toggles = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
        toggles.Children.Add(Pad(WText("On the picture", 11, White(0x99)), 0, 10));
        var chatChip = Controls.Ui.Chip("Chat", s.PartyShowChat, () => { shell.ChangeSettings(shell.Settings with { PartyShowChat = !shell.Settings.PartyShowChat }); Render(); });
        chatChip.Margin = new Thickness(0, 0, 8, 0);
        toggles.Children.Add(chatChip);
        toggles.Children.Add(Controls.Ui.Chip("Reactions", s.PartyShowReactions, () => { shell.ChangeSettings(shell.Settings with { PartyShowReactions = !shell.Settings.PartyShowReactions }); Render(); }));
        DockPanel.SetDock(toggles, Dock.Bottom);
        sheet.Children.Add(toggles);

        // The message row: one rounded field and a Send of the same height, centred on one line.
        var typing = chatBox.IsKeyboardFocusWithin;
        Detach(chatBox);
        Detach(chatHint);
        chatBox.KeyDown -= OnChatKey;
        chatBox.KeyDown += OnChatKey;
        chatBox.TextChanged -= OnChatTyped;
        chatBox.TextChanged += OnChatTyped;
        chatHint.Visibility = chatBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (chatField is not null) chatField.Child = null;
        chatField = new Border
        {
            Height = 36, CornerRadius = new CornerRadius(18), Background = White(0x1f), BorderBrush = White(0x30), BorderThickness = new Thickness(1),
            Padding = new Thickness(14, 0, 14, 0), VerticalAlignment = VerticalAlignment.Center,
            Child = new Grid { Children = { chatHint, chatBox } },
        };
        var send = Controls.Ui.Button(new Border
        {
            Height = 36, CornerRadius = new CornerRadius(18), Background = Red, Padding = new Thickness(18, 0, 18, 0),
            Child = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Children = { WText("Send", 13, Brushes.White, FontWeights.SemiBold) } },
        }, () => { SendChat(); Bump(); }, "Bare", "Send (Enter)");
        send.Margin = new Thickness(8, 0, 0, 0);
        send.VerticalAlignment = VerticalAlignment.Center;
        var row = new DockPanel { Margin = new Thickness(0, 10, 0, 0), LastChildFill = true };
        DockPanel.SetDock(send, Dock.Right);
        row.Children.Add(send);
        row.Children.Add(chatField);
        DockPanel.SetDock(row, Dock.Bottom);
        sheet.Children.Add(row);
        if (typing) Dispatcher.BeginInvoke(() => chatBox.Focus(), System.Windows.Threading.DispatcherPriority.Input);

        // Last, so it fills what is left between the people above and the message row below.
        var lines = new StackPanel();
        if (r.Chat.Count == 0) lines.Children.Add(WText("Say hello. Only the people in this party see it, and it's gone when the party ends.", 12, White(0x99)));
        foreach (var l in r.Chat) lines.Children.Add(ChatRow(l));
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = lines };
        scroll.Loaded += (_, _) => scroll.ScrollToEnd();
        sheet.Children.Add(scroll);
        return SheetBox(sheet);
    }

    void OnChatTyped(object sender, TextChangedEventArgs e) => chatHint.Visibility = chatBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;

    void OnChatKey(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        SendChat();
        e.Handled = true;
    }

    FrameworkElement Pill(string? icon, string label, Action onClick)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal };
        if (icon is not null) content.Children.Add(WIcon(icon, 13));
        content.Children.Add(WText(icon is null ? label : "  " + label, 12, Brushes.White, FontWeights.SemiBold));
        var b = Controls.Ui.Button(new Border { CornerRadius = new CornerRadius(14), Background = White(0x24), Padding = new Thickness(12, 6, 12, 6), Child = content }, () => { onClick(); Bump(); }, "Bare", label);
        b.Margin = new Thickness(0, 0, 8, 0);
        return b;
    }

    static TextBlock Overline(string text)
    {
        var t = WText(text, 10, White(0x88), FontWeights.Bold);
        t.Margin = new Thickness(0, 12, 0, 6);
        return t;
    }

    internal static FrameworkElement NameDot(string name, double size)
    {
        uint[] hues = { 0xFFE5484D, 0xFF3E63DD, 0xFF30A46C, 0xFFF76B15, 0xFF8E4EC6, 0xFF12A594, 0xFFD6409F, 0xFFFFB224 };
        // The phone's and TV's hash of the name, so the same person has the same colour everywhere.
        var h = hues[(KotlinHash(name) & int.MaxValue) % hues.Length];
        var letter = WText(name.Trim().Length > 0 ? name.Trim()[..1].ToUpperInvariant() : "?", size * 0.45, Brushes.White, FontWeights.Bold);
        letter.HorizontalAlignment = HorizontalAlignment.Center;
        letter.VerticalAlignment = VerticalAlignment.Center;
        return new Border
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(size / 2),
            Background = new SolidColorBrush(Color.FromArgb((byte)(h >> 24), (byte)(h >> 16), (byte)(h >> 8), (byte)h)),
            Child = letter,
        };
    }

    /// <summary>Java's String.hashCode, which the phone and TV colour by.</summary>
    static int KotlinHash(string s)
    {
        var h = 0;
        foreach (var c in s) h = unchecked(31 * h + c);
        return h;
    }

    FrameworkElement PersonRow(PartyMember m)
    {
        var (word, colour) = m.Status switch
        {
            "watching" => ("Watching", (Brush)Brushes.LightGreen),
            "paused" => ("Paused", White(0x99)),
            "loading" => ("Loading…", Brushes.Orange),
            "behind" => ("Watching on their own", new SolidColorBrush(Color.FromRgb(0x7a, 0xa7, 0xff))),
            _ => ("Opening the film…", White(0x99)),
        };
        var words = new StackPanel { Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        words.Children.Add(WText(m.Name + (m.Me ? " (you)" : "") + (m.Leader ? "  ★ leader" : ""), 13, Brushes.White, FontWeights.SemiBold));
        words.Children.Add(WText(word, 11, colour));
        return new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 8), Children = { NameDot(m.Name, 30), words } };
    }

    FrameworkElement ChatRow(ChatLine l)
    {
        if (l.From is null)
        {
            var t = WText(l.Text, 11, White(0x80));
            t.HorizontalAlignment = HorizontalAlignment.Center;
            t.Margin = new Thickness(0, 2, 0, 6);
            return t;
        }
        var text = WText(l.Text, 13, Brushes.White);
        text.TextWrapping = TextWrapping.Wrap;
        var bubble = new StackPanel();
        if (!l.Mine) bubble.Children.Add(WText(l.From, 11, White(0xa6), FontWeights.Bold));
        bubble.Children.Add(text);
        var box = new Border
        {
            CornerRadius = new CornerRadius(12), Padding = new Thickness(10, 6, 10, 6), MaxWidth = 290,
            Background = l.Mine ? Red : White(0x1a), Child = bubble,
            HorizontalAlignment = l.Mine ? HorizontalAlignment.Right : HorizontalAlignment.Left,
        };
        if (l.Mine) return new Border { Margin = new Thickness(40, 0, 0, 6), Child = box };
        return new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 40, 6), Children = { NameDot(l.From, 24), Pad(box, 8, 0) } };
    }

    // ---------------------------------------------------------------- over the picture

    void PartyCards()
    {
        if (Party.Room is null || Preparing) return;

        if (party.HeldFor is { Count: > 0 } held && party.AskShown is null && party.Choice is null)
        {
            var others = held.Where(h => h != party.Me).ToList();
            var text = others.Count == 0 ? "Everyone is waiting while yours loads"
                : held.Contains(party.Me) ? $"Waiting for you and {string.Join(", ", others)}"
                : $"Waiting for {string.Join(", ", others)} to load";
            var row = new StackPanel { Orientation = Orientation.Horizontal, Children = { Spin(), Pad(WText(text, 13, Brushes.White, FontWeights.SemiBold), 10, 0) } };
            if (party.Leader) row.Children.Add(Pad(Pill(null, "Go on", party.GoOnWithout), 12, 0));
            chrome.Children.Add(Card(row, VerticalAlignment.Top, new Thickness(0, 76, 0, 0)));
        }

        if (party.Choice is { } c)
        {
            var verb = c.SeekTo is null ? "pause" : "jump";
            var words = party.LeaderState?.Asks == false ? $"Only the leader can {verb} the party — or {verb} just for you, and catch up after."
                : $"Just for you, or ask the leader to {verb} for everyone?";
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 12, 0, 0) };
            buttons.Children.Add(Pill("user", "Just me", party.JustMe));
            if (party.LeaderState?.Asks != false) buttons.Children.Add(Pill("users", "Everyone", party.Everyone));
            buttons.Children.Add(Pill(null, "Cancel", party.DismissChoice));
            var message = WText(words, 12, White(0xb0));
            message.TextWrapping = TextWrapping.Wrap;
            message.MaxWidth = 320;
            message.TextAlignment = TextAlignment.Center;
            var title = WText(c.SeekTo is { } to ? $"Jump to {Format.Clock(to)}" : "Pause", 17, Brushes.White, FontWeights.Bold);
            title.HorizontalAlignment = HorizontalAlignment.Center;
            chrome.Children.Add(Card(new StackPanel { Children = { title, Pad(message, 0, 0), buttons } }, VerticalAlignment.Center, new Thickness(0)));
        }

        if (party.AskShown is { } a)
        {
            var what = a.What switch { "pause" => "pause", "play" => "play", _ => $"jump to {Format.Clock(a.Pos)}" };
            chrome.Children.Add(Card(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children =
                {
                    WIcon("users", 16),
                    Pad(WText($"{a.From} asks to {what}", 13, Brushes.White, FontWeights.SemiBold), 10, 14),
                    Pill(null, "No", party.Refuse),
                    Pill(null, "Allow", () => party.Allow(a)),
                },
            }, VerticalAlignment.Top, new Thickness(0, 76, 0, 0)));
        }

        if (party.Guest && party.Detached)
        {
            chrome.Children.Add(Card(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children = { WIcon("user", 14), Pad(WText("On your own", 12, Brushes.White), 8, 12), Pill("users", "Catch up", party.CatchUp) },
            }, VerticalAlignment.Bottom, new Thickness(0, 0, 0, showUi ? 120 : 24)));
        }

        if (party.Peek is { From: { } from } peek && !partyOpen)
        {
            var text = WText(peek.Text, 13, Brushes.White);
            text.TextWrapping = TextWrapping.Wrap;
            text.MaxWidth = 280;
            var card = Card(new StackPanel { Orientation = Orientation.Horizontal, Children = { NameDot(from, 26), Pad(new StackPanel { Children = { WText(from, 11, White(0xa6), FontWeights.Bold), text } }, 10, 0) } },
                VerticalAlignment.Bottom, new Thickness(16, 0, 0, showUi ? 120 : 24));
            card.HorizontalAlignment = HorizontalAlignment.Left;
            chrome.Children.Add(card);
        }
    }

    static Border Card(UIElement content, VerticalAlignment where, Thickness margin) => new()
    {
        CornerRadius = new CornerRadius(14),
        Background = Black(0xdd),
        Padding = new Thickness(16, 10, 10, 10),
        HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = where,
        Margin = margin,
        Child = content,
    };

    /// <summary>The picture for each reaction the phone and TV send: Fluent Emoji, as the profile faces are.</summary>
    static readonly Dictionary<string, string> ReactionFiles = new()
    {
        ["😂"] = "laugh", ["😮"] = "wow", ["😱"] = "scream", ["❤️"] = "heart", ["👏"] = "clap", ["🔥"] = "fire",
    };

    static readonly Dictionary<string, System.Windows.Media.Imaging.BitmapImage> reactionArt = new();

    /// <summary>
    /// A reaction in colour. WPF draws emoji fonts in black and white only, which left every reaction a grey
    /// outline; the pictures are the same Fluent set as the profile faces. Text for anything unknown.
    /// </summary>
    static FrameworkElement Reaction(string emoji, double size, Thickness margin)
    {
        if (!ReactionFiles.TryGetValue(emoji, out var file))
            return new TextBlock { Text = emoji, FontSize = size * 0.75, Margin = margin, HorizontalAlignment = HorizontalAlignment.Center };
        if (!reactionArt.TryGetValue(file, out var art))
        {
            art = new System.Windows.Media.Imaging.BitmapImage();
            art.BeginInit();
            art.UriSource = new Uri($"pack://application:,,,/Assets/Reactions/{file}.png");
            art.DecodePixelWidth = 128;
            art.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
            art.EndInit();
            art.Freeze();
            reactionArt[file] = art;
        }
        var image = new Image { Source = art, Width = size, Height = size, Margin = margin, HorizontalAlignment = HorizontalAlignment.Center };
        RenderOptions.SetBitmapScalingMode(image, BitmapScalingMode.HighQuality);
        return image;
    }

    /// <summary>A reaction rising up the picture and fading, with who sent it under it.</summary>
    void Float(string who, string emoji)
    {
        if (disposed) return;
        var w = Math.Max(200, overlay.ActualWidth);
        var h = Math.Max(200, overlay.ActualHeight);
        var name = WText(who, 12, Brushes.White);
        name.HorizontalAlignment = HorizontalAlignment.Center;
        var item = new StackPanel
        {
            Children = { Reaction(emoji, 52, new Thickness(0)), name },
        };
        Canvas.SetLeft(item, w * (0.55 + Random.Shared.NextDouble() * 0.35));
        Canvas.SetTop(item, h * 0.8);
        floatLayer.Children.Add(item);
        var time = TimeSpan.FromMilliseconds(2600);
        item.BeginAnimation(Canvas.TopProperty, new DoubleAnimation(h * 0.8, h * 0.25, time));
        var fade = new DoubleAnimation(1, 0, time) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } };
        fade.Completed += (_, _) => floatLayer.Children.Remove(item);
        item.BeginAnimation(OpacityProperty, fade);
    }
}
