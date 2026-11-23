using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// The rail's Join a party, as the phone's sheet asks for it: you beside two free seats, the code the leader
/// shared filling its eight slots, and Join. Typed into a box laid unseen over the slots, so a click on them
/// types there too. Once a code takes, the invite's own screen (<see cref="JoinPartyView"/>) shows what's on.
/// </summary>
public sealed class JoinCodeView : Grid
{
    /// <summary>A party code's length, as the leader's screen shows it (K7Q2-M9XD).</summary>
    const int Slots = 8;

    readonly TextBox box = new()
    {
        Opacity = 0, MaxLength = 32, CharacterCasing = CharacterCasing.Upper, Cursor = Cursors.IBeam,
        Background = Brushes.Transparent, BorderThickness = new Thickness(0), CaretBrush = Brushes.Transparent,
    };
    readonly StackPanel slots = new() { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
    readonly TextBlock error = Ui.Text(" ", "Meta", "Danger", 13);
    readonly Button join;
    readonly Action close;
    bool wrong;

    public JoinCodeView(Profile? me, Action close)
    {
        this.close = close;
        SetResourceReference(BackgroundProperty, "Scrim");
        // Off the card, it closes; on it, nothing does.
        MouseDown += (_, e) => { if (e.OriginalSource == this) close(); };

        var seats = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        if (me is not null)
        {
            var face = ProfilesView.Avatar(me, 64);
            face.Clip = new EllipseGeometry(new Point(32, 32), 32, 32);
            seats.Children.Add(new Border { Width = 64, Height = 64, Child = face });
        }
        for (var i = 0; i < 2; i++) seats.Children.Add(Seat(me is null && i == 0 ? 0 : -14));

        var heading = Ui.Text("Join a watch party", "Title", "Text", 24, FontWeights.Bold);
        heading.HorizontalAlignment = HorizontalAlignment.Center;
        heading.Margin = new Thickness(0, 18, 0, 0);
        var sub = Ui.Text($"Enter the {Slots}-character code your friend shared.", "Body", "Muted", 14);
        sub.HorizontalAlignment = HorizontalAlignment.Center;
        sub.Margin = new Thickness(0, 6, 0, 0);

        box.TextChanged += (_, _) =>
        {
            var clean = new string(box.Text.Where(char.IsLetterOrDigit).Take(Slots).ToArray());
            if (clean != box.Text) { box.Text = clean; box.CaretIndex = clean.Length; return; }
            wrong = false;
            error.Text = " ";
            Draw();
        };
        box.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter) { Join(); e.Handled = true; }
            else if (e.Key == Key.Escape) { close(); e.Handled = true; }
        };
        var typing = new Grid { Margin = new Thickness(0, 26, 0, 0), Children = { slots, box } };
        typing.MouseDown += (_, _) => box.Focus();

        error.HorizontalAlignment = HorizontalAlignment.Center;
        error.Margin = new Thickness(0, 12, 0, 0);
        join = Ui.Button("Join", Join, "PillAccent");
        join.Margin = new Thickness(0, 14, 0, 0);
        join.HorizontalAlignment = HorizontalAlignment.Stretch;
        var note = Ui.Text("Only play, pause and where you are are shared, encrypted. You'll see what's on before anything plays.\nTo host, start a party from the player.", "Meta", "Faint", 12);
        note.TextAlignment = TextAlignment.Center;
        note.TextWrapping = TextWrapping.Wrap;
        note.Margin = new Thickness(0, 18, 0, 0);

        var card = Ui.Card(new StackPanel { Children = { seats, heading, sub, typing, error, join, note } }, "Surface", 16, new Thickness(32, 28, 32, 24));
        card.Width = 460;
        card.HorizontalAlignment = HorizontalAlignment.Center;
        card.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(card);
        Draw();
        Loaded += (_, _) => { box.Focus(); Keyboard.Focus(box); };
    }

    void Join()
    {
        if (box.Text.Length < Slots) { Fail($"The code is {Slots} letters and numbers."); return; }
        if (Party.Join(box.Text) is null) { Fail("That isn't a watch party code."); return; }
        close();
    }

    void Fail(string why)
    {
        wrong = true;
        error.Text = why;
        Draw();
        box.Focus();
    }

    /// <summary>The slots as the code stands: the next to fill lit, all red while it's wrong; Join faint until it's whole.</summary>
    void Draw()
    {
        slots.Children.Clear();
        var code = box.Text;
        for (var i = 0; i < Slots; i++)
        {
            if (i == Slots / 2)
            {
                var dash = Ui.Text("–", "Title", "Faint", 22);
                dash.Margin = new Thickness(4, 0, 4, 8);
                dash.VerticalAlignment = VerticalAlignment.Bottom;
                slots.Children.Add(dash);
            }
            var next = i == code.Length;
            var letter = Ui.Text(i < code.Length ? code[i].ToString() : "", "Title", "Text", 26, FontWeights.Bold);
            letter.HorizontalAlignment = HorizontalAlignment.Center;
            var line = new Border { Height = next ? 3 : 2, CornerRadius = new CornerRadius(1.5), Margin = new Thickness(0, 6, 0, 0) };
            line.SetResourceReference(Border.BackgroundProperty, wrong ? "Danger" : next ? "Text" : i < code.Length ? "Muted" : "Stroke");
            slots.Children.Add(new StackPanel { Width = 38, Margin = new Thickness(3, 0, 3, 0), Children = { new Border { Height = 38, Child = letter }, line } });
        }
        join.Opacity = code.Length == Slots ? 1 : 0.45;
    }

    /// <summary>A seat still free in the party: a dashed ring with a plus, over the one before it by <paramref name="overlap"/>.</summary>
    static FrameworkElement Seat(double overlap)
    {
        var ring = new Ellipse { Width = 64, Height = 64, StrokeThickness = 1.5, StrokeDashArray = new DoubleCollection { 4, 3 } };
        ring.SetResourceReference(Shape.StrokeProperty, "Muted");
        var plus = Ui.Glyph("plus", 22, "Muted");
        plus.HorizontalAlignment = HorizontalAlignment.Center;
        plus.VerticalAlignment = VerticalAlignment.Center;
        return new Grid { Width = 64, Height = 64, Margin = new Thickness(overlap, 0, 0, 0), Children = { ring, plus } };
    }
}
