using System.Windows;
using System.Windows.Controls;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// App.tsx's stream-or-download picker. Only a torrent arriving from outside —
/// a dropped .torrent, a magnet on the command line — asks, because only then
/// is the intent unknown.
/// </summary>
public sealed class ModePickerView : UserControl
{
    public ModePickerView(TorrentMeta meta, Action stream, Action download, Action cancel)
    {
        var playable = meta.Files.Count(f => f.IsVideo);

        FrameworkElement Choice(string icon, string label, string sub, Action onClick)
        {
            var circle = Ui.Brushed(new Border { Width = 44, Height = 44, CornerRadius = new CornerRadius(22), HorizontalAlignment = HorizontalAlignment.Center, Child = new Grid { Children = { Ui.Glyph(icon, 20, "Accent") } } }, "Control");
            ((Grid)circle.Child).Children[0].SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
            var text = Ui.Text(label, "TitleText", "Text", 16, FontWeights.SemiBold);
            text.HorizontalAlignment = HorizontalAlignment.Center;
            text.Margin = new Thickness(0, 8, 0, 0);
            var note = Ui.Text(sub, "Meta", "Faint", 11);
            note.HorizontalAlignment = HorizontalAlignment.Center;
            var card = Ui.Card(new StackPanel { Children = { circle, text, note } }, "Surface", 10, new Thickness(14, 22, 14, 22));
            return Ui.Button(card, onClick);
        }

        var mark = Ui.Brushed(new Border { Width = 52, Height = 52, CornerRadius = new CornerRadius(14), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 14), Child = new Grid { Children = { Ui.Glyph("layers", 22, "Accent") } } }, "AccentSoft");
        ((Grid)mark.Child).Children[0].SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
        var title = Ui.Text(meta.Name, "TitleText", "Text", 18, FontWeights.SemiBold);
        title.TextWrapping = TextWrapping.Wrap;
        title.TextAlignment = TextAlignment.Center;
        var sub = Ui.Text($"{playable} playable · {meta.Files.Count} files — how do you want to open it?", "Meta", "Muted", 13);
        sub.HorizontalAlignment = HorizontalAlignment.Center;
        sub.Margin = new Thickness(0, 12, 0, 16);

        var buttons = new Grid();
        buttons.ColumnDefinitions.Add(new ColumnDefinition());
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(12) });
        buttons.ColumnDefinitions.Add(new ColumnDefinition());
        var s = Choice("play", "Stream", "Watch as pieces arrive", stream);
        var d = Choice("download", "Download", "Save fully, then open", download);
        Grid.SetColumn(d, 2);
        buttons.Children.Add(s);
        buttons.Children.Add(d);

        var cancelButton = Ui.Button(Ui.Text("Cancel", "Label", "Muted", 14), cancel);
        cancelButton.HorizontalAlignment = HorizontalAlignment.Center;
        cancelButton.Margin = new Thickness(0, 16, 0, 0);

        Content = new StackPanel
        {
            Width = 380,
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
            Children = { mark, title, sub, buttons, cancelButton },
        };
    }
}
