using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// Who's watching — the TV's and the phone's profile screen (tv Pages.kt ProfilesScreen): pick a
/// profile or Guest, add one, manage them, and a PIN pad for a locked one. Over the whole window,
/// at every launch and whenever the rail's avatar is clicked.
/// </summary>
public sealed class ProfilesView : UserControl
{
    enum Mode { Pick, Manage, Edit, Pin }

    readonly Action<Profile> onPick;
    readonly Action? onCancel;
    readonly Action<Profile> onRemoved;
    readonly Grid root = new();
    List<Profile> profiles = Profiles.Load();
    Mode mode;
    /// <summary>The profile being edited (null: a new one) or unlocked.</summary>
    Profile? target;

    public ProfilesView(Action<Profile> onPick, Action? onCancel, Action<Profile> onRemoved)
    {
        this.onPick = onPick;
        this.onCancel = onCancel;
        this.onRemoved = onRemoved;
        mode = profiles.Count == 0 ? Mode.Edit : Mode.Pick;
        SetResourceReference(BackgroundProperty, "Bg");
        Content = root;
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key != Key.Escape) return;
            if (mode != Mode.Pick && profiles.Count > 0) Go(Mode.Pick);
            else onCancel?.Invoke();
            e.Handled = true;
        };
        Render();
    }

    void Go(Mode next, Profile? profile = null)
    {
        mode = next;
        target = profile;
        profiles = Profiles.Load();
        Render();
    }

    void Render()
    {
        root.Children.Clear();
        root.Children.Add(mode switch
        {
            Mode.Pick or Mode.Manage => Picker(),
            Mode.Edit => Editor(),
            _ => PinPad(),
        });
    }

    /// <summary>core Avatar: the profile's face on the raised page colour, washed with its colour.</summary>
    public static FrameworkElement Avatar(Profile p, double size) => Tile(Avatars.Of(p), p.Colour, size);

    public static FrameworkElement Tile(AvatarArt art, string colourHex, double size)
    {
        Color colour;
        try { colour = (Color)ColorConverter.ConvertFromString(colourHex); }
        catch { colour = Color.FromRgb(0xe5, 0x09, 0x14); }
        var radius = new CornerRadius(size * 0.22);
        var tile = new Border
        {
            Width = size, Height = size, CornerRadius = radius,
            Child = new Grid
            {
                Children =
                {
                    new Border { CornerRadius = radius, Background = new SolidColorBrush(Color.FromArgb(0x48, colour.R, colour.G, colour.B)) },
                    new Image { Source = art.Image, Stretch = Stretch.Fill },
                },
            },
        };
        tile.SetResourceReference(Border.BackgroundProperty, "SurfaceAlt");
        return tile;
    }

    FrameworkElement Picker()
    {
        var managing = mode == Mode.Manage;
        var tiles = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        foreach (var p in profiles)
            tiles.Children.Add(Tile(p.Name, Avatar(p, 130), managing ? "edit-2" : p.Pin is not null ? "lock" : null, () => Open(p)));
        if (!managing) tiles.Children.Add(Tile("Guest", Avatar(Profiles.Guest, 130), null, () => onPick(Profiles.Guest)));
        if (!managing && profiles.Count < Profiles.Most)
        {
            var plus = Ui.Card(Ui.Glyph("plus", 48, "Muted"), "Surface", 16);
            plus.Width = plus.Height = 130;
            tiles.Children.Add(Tile("Add profile", plus, null, () => Go(Mode.Edit)));
        }

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 48, 0, 0) };
        buttons.Children.Add(Ui.Action(managing ? "Done" : "Manage profiles", managing ? "check" : "edit-2", () => Go(managing ? Mode.Pick : Mode.Manage)));
        if (onCancel is not null && !managing)
        {
            var back = Ui.Action("Back", "arrow-left", onCancel);
            back.Margin = new Thickness(14, 0, 0, 0);
            buttons.Children.Add(back);
        }
        var heading = Ui.Text(managing ? "Manage profiles" : "Who's watching?", "Display", "Text", 44, FontWeights.Bold);
        heading.HorizontalAlignment = HorizontalAlignment.Center;
        heading.Margin = new Thickness(0, 0, 0, 44);
        return Centered(new StackPanel { Children = { heading, tiles, buttons } });
    }

    void Open(Profile p)
    {
        if (mode == Mode.Manage) Go(Mode.Edit, p);
        else if (p.Pin is not null) Go(Mode.Pin, p);
        else onPick(p);
    }

    /// <summary>A face over a name; the name lights up under the pointer, as the TV's does under focus.</summary>
    static FrameworkElement Tile(string name, FrameworkElement face, string? badge, Action onClick)
    {
        var art = new Grid { Children = { face } };
        if (badge is not null)
        {
            var mark = Ui.Glyph(badge, 16, "Text");
            mark.Foreground = Brushes.White;
            art.Children.Add(new Border
            {
                Width = 30, Height = 30, CornerRadius = new CornerRadius(8), Background = new SolidColorBrush(Color.FromArgb(0x99, 0, 0, 0)),
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(8), Child = mark,
            });
        }
        var label = Ui.Text(name, "TitleText", "Faint", 18, FontWeights.Medium);
        label.HorizontalAlignment = HorizontalAlignment.Center;
        label.TextTrimming = TextTrimming.CharacterEllipsis;
        label.Margin = new Thickness(0, 14, 0, 0);
        var b = Ui.Button(new StackPanel { Width = 140, Children = { art, label } }, onClick, "Bare", name);
        b.Margin = new Thickness(14, 0, 14, 0);
        b.MouseEnter += (_, _) => { label.SetResourceReference(TextBlock.ForegroundProperty, "Text"); label.FontWeight = FontWeights.Bold; };
        b.MouseLeave += (_, _) => { label.SetResourceReference(TextBlock.ForegroundProperty, "Faint"); label.FontWeight = FontWeights.Medium; };
        return b;
    }

    FrameworkElement Editor()
    {
        var editing = target;
        var name = editing?.Name ?? "";
        var colour = editing?.Colour ?? Profiles.Colours[0];
        var icon = editing is not null ? Avatars.Of(editing).Id : Avatars.Pickable[0].Id;
        var kids = editing?.Kids ?? false;
        var kidsRow = new StackPanel { Orientation = Orientation.Horizontal };
        var kidsHint = Ui.Text("", "Meta", "Faint", 13);
        kidsHint.Margin = new Thickness(0, 8, 0, 0);
        kidsHint.TextWrapping = TextWrapping.Wrap;
        void RefreshKids()
        {
            kidsRow.Children.Clear();
            var on = Ui.Chip("Kids profile", kids, () => { kids = true; RefreshKids(); }, kids ? "check" : null, "ChoiceChip");
            on.Margin = new Thickness(0, 0, 8, 0);
            kidsRow.Children.Add(on);
            kidsRow.Children.Add(Ui.Chip("Everything", !kids, () => { kids = false; RefreshKids(); }, style: "ChoiceChip"));
            kidsHint.Text = kids
                ? "Family films and series only. No torrent search or links — give your own profile a PIN so it stays that way."
                : "Every title, torrent search included.";
        }
        RefreshKids();
        var preview = new ContentControl { VerticalAlignment = VerticalAlignment.Top };
        var faces = new WrapPanel();
        var swatches = new StackPanel { Orientation = Orientation.Horizontal };
        void Refresh()
        {
            preview.Content = Tile(Avatars.Art(icon), colour, 160);
            faces.Children.Clear();
            foreach (var art in Avatars.Pickable)
            {
                var chosen = art.Id == icon;
                var face = Ui.Button(new Border
                {
                    BorderThickness = new Thickness(3), CornerRadius = new CornerRadius(14), Padding = new Thickness(2),
                    BorderBrush = chosen ? (Brush)FindResource("Accent") : Brushes.Transparent,
                    Child = Tile(art, colour, 50),
                }, () => { icon = art.Id; Refresh(); }, "Bare", art.Name);
                face.Margin = new Thickness(0, 0, 6, 6);
                faces.Children.Add(face);
            }
            swatches.Children.Clear();
            foreach (var c in Profiles.Colours)
            {
                var swatch = Ui.Button(c == colour ? Ui.Glyph("check", 20, "OnAccent") : new Border(), () => { colour = c; Refresh(); }, "Bare", "Colour");
                swatch.Width = swatch.Height = 42;
                swatch.Tag = 21;
                swatch.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString(c));
                swatch.Margin = new Thickness(0, 0, 12, 0);
                swatches.Children.Add(swatch);
            }
        }
        Refresh();

        var input = new TextBox { Style = (Style)FindResource("Field"), Tag = "Name", Text = name, MaxLength = 20, FontSize = 16 };
        input.TextChanged += (_, _) => { name = input.Text; Refresh(); };
        var field = Ui.Card(input, "Surface", 8, new Thickness(14, 0, 14, 0));
        field.Height = 50;
        input.Loaded += (_, _) => { input.Focus(); input.SelectAll(); };

        void Save()
        {
            if (editing is null) onPick(Profiles.Add(name, colour, icon, kids));
            else
            {
                MainWindow.Shell.ProfileEdited(Profiles.Edit(editing.Id, name, colour, icon, kids));
                Go(Mode.Pick);
            }
        }
        input.KeyDown += (_, e) => { if (e.Key == Key.Enter) Save(); };

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 30, 0, 0) };
        buttons.Children.Add(Ui.Action("Save", "check", Save, primary: true));
        if (profiles.Count > 0) buttons.Children.Add(Spaced(Ui.Action("Cancel", "x", () => Go(Mode.Pick))));
        if (editing is not null)
            buttons.Children.Add(Spaced(Ui.Action("Delete profile", "trash-2", () =>
            {
                Profiles.Remove(editing.Id);
                onRemoved(editing);
                Go(Mode.Pick);
            })));

        var heading = Ui.Text(editing is null ? "Add a profile" : "Edit profile", "Display", "Text", 32, FontWeights.Bold);
        var hint = Ui.Text(editing is null ? "Its own library, My List and resume points." : "Change the name, the face or the colour.", "Meta", "Faint", 14);
        hint.Margin = new Thickness(0, 4, 0, 20);
        var faceLabel = Ui.Text("FACE", "Overline", "Faint");
        faceLabel.Margin = new Thickness(0, 22, 0, 10);
        var colourLabel = Ui.Text("COLOUR", "Overline", "Faint");
        colourLabel.Margin = new Thickness(0, 16, 0, 10);
        var kidsLabel = Ui.Text("KIDS", "Overline", "Faint");
        kidsLabel.Margin = new Thickness(0, 16, 0, 10);
        var form = new StackPanel { Width = 600, Margin = new Thickness(48, 0, 0, 0), Children = { heading, hint, field, faceLabel, faces, colourLabel, swatches, kidsLabel, kidsRow, kidsHint, buttons } };
        return Centered(new StackPanel { Orientation = Orientation.Horizontal, Children = { preview, form } });
    }

    FrameworkElement PinPad()
    {
        var p = target!;
        var hint = Ui.Text("Enter your PIN to open this profile.", "Body", "Muted", 15);
        hint.HorizontalAlignment = HorizontalAlignment.Center;
        hint.Margin = new Thickness(0, 6, 0, 22);
        var box = PinBox();
        // Opens on the fourth digit — nobody wants to reach for Enter after a PIN.
        box.PasswordChanged += (_, _) =>
        {
            if (box.Password.Length < 4) return;
            if (box.Password == p.Pin)
            {
                onPick(p);
                return;
            }
            hint.Text = "That's not the PIN. Try again.";
            hint.SetResourceReference(TextBlock.ForegroundProperty, "Danger");
            box.Clear();
        };
        box.Loaded += (_, _) => box.Focus();
        var name = Ui.Text(p.Name, "Display", "Text", 32, FontWeights.Bold);
        name.HorizontalAlignment = HorizontalAlignment.Center;
        name.Margin = new Thickness(0, 16, 0, 0);
        var face = Avatar(p, 110);
        face.HorizontalAlignment = HorizontalAlignment.Center;
        var back = Ui.Action("Back", "arrow-left", () => Go(Mode.Pick));
        back.HorizontalAlignment = HorizontalAlignment.Center;
        back.Margin = new Thickness(0, 24, 0, 0);
        return Centered(new StackPanel { Children = { face, name, hint, Ui.Card(box, "Surface", 8, new Thickness(12, 6, 12, 6)), back } });
    }

    /// <summary>Four digits, shown as dots.</summary>
    public static PasswordBox PinBox()
    {
        var box = new PasswordBox
        {
            MaxLength = 4, Width = 196, FontSize = 26, Background = Brushes.Transparent, BorderThickness = new Thickness(0),
            HorizontalContentAlignment = HorizontalAlignment.Center, PasswordChar = '•',
        };
        box.SetResourceReference(ForegroundProperty, "Text");
        box.SetResourceReference(PasswordBox.CaretBrushProperty, "Accent");
        box.PreviewTextInput += (_, e) => e.Handled = !e.Text.All(char.IsDigit);
        DataObject.AddPastingHandler(box, (_, e) => e.CancelCommand());
        return box;
    }

    static FrameworkElement Spaced(FrameworkElement e)
    {
        e.Margin = new Thickness(12, 0, 0, 0);
        return e;
    }

    static FrameworkElement Centered(FrameworkElement e)
    {
        e.HorizontalAlignment = HorizontalAlignment.Center;
        e.VerticalAlignment = VerticalAlignment.Center;
        return new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, Content = e };
    }
}
