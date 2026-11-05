using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// The Addons tab (mobile Pages.kt AddonsScreen): where everything played comes from. Add one by its
/// link (or an addon site's Install button, which opens here), then switch, order or uninstall each,
/// laid out as Stremio lists them. Order is the order asked. The UI shows an addon's host only: the
/// rest of its link can be the viewer's own key.
/// </summary>
public sealed class AddonsView : UserControl
{
    readonly MainWindow shell = MainWindow.Shell;
    readonly StackPanel page = new() { Margin = new Thickness(24, 8, 24, 32) };
    /// <summary>Side by side, as many as the window has room for.</summary>
    readonly ColumnWrap cards = new() { MinColumn = 520, FillRow = true };
    readonly WrapPanel pills = new() { Margin = new Thickness(0, 12, 0, 14) };
    readonly TextBox link;
    readonly TextBox query;
    string? type;
    bool adding;

    public AddonsView()
    {
        var (root, _, body) = Ui.Screen("Addons");
        body.Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = page };
        Content = root;

        var intro = Ui.Text("Streams, subtitles and extra catalogues come from addons — public ones, your debrid service's, or your own media server's. Watchly comes with none.", "Meta", "Faint", 13);
        intro.TextWrapping = TextWrapping.Wrap;
        intro.Margin = new Thickness(0, 0, 0, 18);
        page.Children.Add(intro);

        link = new TextBox { Style = (Style)FindResource("Field"), Tag = "https://…/manifest.json", VerticalAlignment = VerticalAlignment.Center };
        link.KeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Enter) Add(); };
        var add = Ui.Action("Add addon", "plus", Add, primary: true);
        add.Margin = new Thickness(12, 0, 0, 0);
        var row = new DockPanel();
        DockPanel.SetDock(add, Dock.Right);
        row.Children.Add(add);
        row.Children.Add(Ui.Card(link, "SurfaceAlt", 6, new Thickness(10, 8, 10, 8)));
        var hint = Ui.Text("Or click Install on an addon's site. Links are kept encrypted for this Windows account.", "Meta", "Faint", 12);
        hint.Margin = new Thickness(0, 10, 0, 0);
        var addCard = Ui.Card(new StackPanel { Children = { Ui.Text("Add an addon", "Label", "Text", 15, FontWeights.SemiBold), Gap(10), row, hint } }, "Surface", 14, new Thickness(16));
        addCard.Margin = new Thickness(0, 0, 0, 22);
        page.Children.Add(addCard);

        // Stremio's list: a search over what is installed, and a pill per type they serve.
        var (box, input, _) = Ui.SearchField("Search installed addons", 44);
        query = input;
        query.TextChanged += (_, _) => Render();
        page.Children.Add(box);
        page.Children.Add(pills);
        page.Children.Add(cards);

        Addons.Changed += () => Dispatcher.BeginInvoke(Render);
        Render();
    }

    static FrameworkElement Gap(double height) => new Border { Height = height };

    async void Add()
    {
        if (adding) return;
        if (link.Text.Trim().Length == 0) { shell.Toast("Paste an addon link first", true); return; }
        adding = true;
        try
        {
            var added = await Addons.Add(link.Text);
            link.Clear();
            shell.Toast($"Added {added.Name}");
        }
        catch (Exception e)
        {
            shell.Toast(e.Message, true);
        }
        finally
        {
            adding = false;
        }
    }

    void Render()
    {
        var addons = Addons.List;
        var types = addons.SelectMany(a => a.Types).Distinct().ToList();
        if (type is not null && !types.Contains(type)) type = null;

        pills.Children.Clear();
        void Pill(string label, string? value)
        {
            var chip = Ui.Chip(label, type == value, () => { type = value; Render(); });
            chip.Margin = new Thickness(0, 0, 8, 8);
            pills.Children.Add(chip);
        }
        Pill($"All · {addons.Count}", null);
        foreach (var t in types) Pill(Addons.TypeLabel(t), t);

        cards.Children.Clear();
        if (addons.Count == 0)
        {
            cards.Children.Add(Ui.Empty("puzzle", "No addons yet", "Add one to find streams for every film and episode. Without any, only links you paste in Search will play."));
            return;
        }
        var q = query.Text.Trim();
        var shown = addons.Where(a => (q.Length == 0 || a.Name.Contains(q, StringComparison.OrdinalIgnoreCase)) && (type is null || a.Types.Contains(type))).ToList();
        if (shown.Count == 0) cards.Children.Add(Ui.Text("No installed addon matches.", "Body", "Faint"));
        foreach (var a in shown)
        {
            var at = addons.IndexOf(a);
            cards.Children.Add(Card(a, at == 0, at == addons.Count - 1));
        }
    }

    /// <summary>
    /// One addon, as Stremio lists it: logo, name with a tick while it is on, the types it serves and its
    /// version, what it says it does, then Uninstall — with its switch and place in the order beside it.
    /// </summary>
    FrameworkElement Card(Addon a, bool first, bool last)
    {
        var logo = new Grid { Width = 52, Height = 52, ClipToBounds = true };
        logo.Children.Add(Ui.Brushed(new Border { CornerRadius = new CornerRadius(10), Child = Ui.Glyph("puzzle", 24, "Muted") }, "TintLo"));
        ((FrameworkElement)((Border)logo.Children[0]).Child).HorizontalAlignment = HorizontalAlignment.Center;
        ((FrameworkElement)((Border)logo.Children[0]).Child).VerticalAlignment = VerticalAlignment.Center;
        if (a.Logo is { } url && ImageUrl.Load(url, 104) is { } image)
            logo.Children.Add(new Border { CornerRadius = new CornerRadius(10), Background = new ImageBrush(image) { Stretch = Stretch.Uniform } });

        var name = Ui.Text(a.Name, "TitleText", a.On ? "Text" : "Faint", 17, FontWeights.Bold);
        name.TextTrimming = TextTrimming.CharacterEllipsis;
        var title = new StackPanel { Orientation = Orientation.Horizontal, Children = { name } };
        if (a.On)
        {
            var tick = Ui.Glyph("check-circle", 16, "Muted");
            tick.Margin = new Thickness(8, 0, 0, 0);
            tick.VerticalAlignment = VerticalAlignment.Center;
            title.Children.Add(tick);
        }
        var typeLine = Ui.Text(a.Types.Count > 0 ? string.Join(", ", a.Types.Select(Addons.TypeLabel)) : a.Offers, "Meta", "Muted", 13);
        typeLine.Margin = new Thickness(0, 2, 0, 0);
        var words = new StackPanel { Margin = new Thickness(14, 0, 8, 0), Children = { title, typeLine } };

        var top = new DockPanel();
        DockPanel.SetDock(logo, Dock.Left);
        top.Children.Add(logo);
        if (a.Version is { } version)
        {
            var v = Ui.Text($"v{version}", "Label", "Muted", 13, FontWeights.SemiBold);
            v.VerticalAlignment = VerticalAlignment.Top;
            DockPanel.SetDock(v, Dock.Right);
            top.Children.Add(v);
        }
        top.Children.Add(words);

        var body = new StackPanel { Children = { top } };
        if (a.Description is { } description)
        {
            var d = Ui.Text(description, "Body", "Muted");
            d.TextWrapping = TextWrapping.Wrap;
            d.MaxHeight = 66;
            d.TextTrimming = TextTrimming.WordEllipsis;
            d.Margin = new Thickness(0, 14, 0, 0);
            body.Children.Add(d);
        }
        // The host only: the rest of the link can be the viewer's own key.
        var host = Ui.Text(a.Host, "Meta", "Faint", 12);
        host.Margin = new Thickness(0, 8, 0, 0);
        body.Children.Add(host);

        var uninstall = Ui.Button(Ui.Text("Uninstall", "Label", "Muted", 14, FontWeights.SemiBold), () =>
        {
            Addons.Remove(a.Url);
            shell.Toast($"Uninstalled {a.Name}");
        }, tooltip: $"Uninstall {a.Name}");
        ((FrameworkElement)uninstall.Content).HorizontalAlignment = HorizontalAlignment.Center;
        uninstall.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        uninstall.Height = 46;
        uninstall.Tag = 23; // round ends
        uninstall.BorderThickness = new Thickness(1);
        uninstall.SetResourceReference(BorderBrushProperty, "Line");

        var controls = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 0, 0) };
        var on = Ui.Chip(a.On ? "On" : "Off", a.On, () => Addons.Toggle(a.Url));
        on.VerticalAlignment = VerticalAlignment.Center;
        controls.Children.Add(on);
        Button Step(string icon, int step, string tip)
        {
            var b = Ui.Round(icon, () => Addons.Move(a.Url, step), "Muted", "Transparent", 36, 20, tip);
            b.Background = Brushes.Transparent;
            return b;
        }
        if (!first) controls.Children.Add(Step("chevron-up", -1, "Ask it sooner"));
        if (!last) controls.Children.Add(Step("chevron-down", 1, "Ask it later"));

        var actions = new DockPanel { Margin = new Thickness(0, 14, 0, 0) };
        DockPanel.SetDock(controls, Dock.Right);
        actions.Children.Add(controls);
        actions.Children.Add(uninstall);
        body.Children.Add(actions);

        var card = Ui.Card(body, "Surface", 14, new Thickness(16));
        card.Margin = new Thickness(0, 0, 12, 12);
        return card;
    }
}
