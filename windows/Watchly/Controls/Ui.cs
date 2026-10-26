using System.Collections;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;

namespace Watchly.Controls;

/// <summary>Small builders for the pieces every screen repeats.</summary>
public static class Ui
{
    static Style S(string key) => (Style)Application.Current.FindResource(key);

    public static TextBlock Text(string text, string style, string? brush = null, double? size = null, FontWeight? weight = null)
    {
        var t = new TextBlock { Text = text, Style = S(style) };
        if (brush is not null) t.SetResourceReference(TextBlock.ForegroundProperty, brush);
        if (size is not null) t.FontSize = size.Value;
        if (weight is not null) t.FontWeight = weight.Value;
        return t;
    }

    public static Icon Glyph(string kind, double size, string brush)
    {
        var icon = new Icon { Kind = kind, Size = size, VerticalAlignment = VerticalAlignment.Center };
        icon.SetResourceReference(Icon.ForegroundProperty, brush);
        return icon;
    }

    public static Button Button(object content, Action onClick, string style = "Bare", string? tooltip = null)
    {
        var b = new Button { Style = S(style), Content = content, ToolTip = tooltip };
        b.Click += (_, e) => { e.Handled = true; onClick(); };
        return b;
    }

    /// <summary>A round icon button — src/components/Button.tsx's IconButton.</summary>
    public static Button Round(string icon, Action onClick, string iconBrush = "Text", string background = "SurfaceAlt", double size = 40, double iconSize = 18, string? tooltip = null)
    {
        var b = Button(Glyph(icon, iconSize, iconBrush), onClick, "Round", tooltip);
        b.Width = b.Height = size;
        b.Tag = size / 2;
        b.SetResourceReference(Control.BackgroundProperty, background);
        return b;
    }

    public static Border Card(UIElement child, string background = "Surface", double radius = 10, Thickness? padding = null) =>
        Brushed(new Border { Child = child, CornerRadius = new CornerRadius(radius), Padding = padding ?? new Thickness(12) }, background);

    /// <summary>Round the ends fully, whatever height the border lays out at — see PillRadius.</summary>
    public static T Pill<T>(T border) where T : Border
    {
        border.SizeChanged += (_, e) => border.CornerRadius = new CornerRadius(Math.Min(e.NewSize.Height, e.NewSize.Width) / 2);
        return border;
    }

    public static T Brushed<T>(T border, string key) where T : Border
    {
        border.SetResourceReference(Border.BackgroundProperty, key);
        return border;
    }

    /// <summary>
    /// Page scrolling with the wheel. A ScrollViewer eats every wheel event even
    /// when it has nothing to scroll that way, so a horizontal shelf under the
    /// pointer would stop the page moving; this hands the wheel on to the page
    /// unless Shift is held, which scrolls the shelf instead.
    /// </summary>
    public static void PassWheel(ScrollViewer rail)
    {
        rail.PreviewMouseWheel += (_, e) =>
        {
            if (Keyboard.Modifiers == ModifierKeys.Shift)
            {
                if (e.Delta < 0) rail.LineRight(); else rail.LineLeft();
                e.Handled = true;
                return;
            }
            e.Handled = true;
            var parent = VisualTreeHelper.GetParent(rail) as UIElement;
            parent?.RaiseEvent(new MouseWheelEventArgs(e.MouseDevice, e.Timestamp, e.Delta) { RoutedEvent = UIElement.MouseWheelEvent, Source = rail });
        };
    }

    /// <summary>
    /// Smooth vertical wheel scrolling. The stock ScrollViewer jumps 48px a notch
    /// and rounds touchpad deltas into the same steps; this glides to the target
    /// once per rendered frame. Shift is left alone so shelves still get Shift+wheel.
    /// </summary>
    public static void SmoothWheel(ScrollViewer sv)
    {
        double target = 0;
        var running = false;
        var clock = System.Diagnostics.Stopwatch.StartNew();
        var last = TimeSpan.Zero;

        void Tick(object? s, EventArgs e)
        {
            var now = clock.Elapsed;
            var dt = (now - last).TotalSeconds;
            last = now;
            target = Math.Clamp(target, 0, sv.ScrollableHeight);
            var off = sv.VerticalOffset;
            var next = off + (target - off) * (1 - Math.Exp(-dt * 16));
            if (Math.Abs(target - next) < 0.5)
            {
                next = target;
                CompositionTarget.Rendering -= Tick;
                running = false;
            }
            sv.ScrollToVerticalOffset(next);
        }

        sv.PreviewMouseWheel += (_, e) =>
        {
            if (Keyboard.Modifiers == ModifierKeys.Shift) return;
            e.Handled = true;
            if (!running) target = sv.VerticalOffset;
            target = Math.Clamp(target - e.Delta, 0, sv.ScrollableHeight);
            if (running) return;
            running = true;
            last = clock.Elapsed;
            CompositionTarget.Rendering += Tick;
        };
    }

    /// <summary>A shelf's heading (mobile Home.kt Rail): the name in bold, "See all ›" on the right.</summary>
    public static FrameworkElement Head(string title, Action? seeAll, double sidePadding = 24)
    {
        var row = new DockPanel { Margin = new Thickness(sidePadding, 0, sidePadding - 10, 8), LastChildFill = false };
        var text = Text(title, "TitleText", "Text", 17, FontWeights.Bold);
        text.VerticalAlignment = VerticalAlignment.Center;
        DockPanel.SetDock(text, Dock.Left);
        row.Children.Add(text);
        if (seeAll is not null)
        {
            var link = Button(new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Children = { Text("See all", "Meta"), Glyph("chevron-right", 16, "Muted") },
            }, seeAll);
            link.Padding = new Thickness(10, 6, 10, 6);
            link.Tag = 999;
            DockPanel.SetDock(link, Dock.Right);
            row.Children.Add(link);
        }
        return row;
    }

    /// <summary>
    /// One horizontal shelf, virtualised — a rail is fifty titles wide and only a
    /// handful are on screen. Arrows page it on hover; Shift+wheel scrolls it.
    /// </summary>
    public static FrameworkElement Rail(IEnumerable items, string template, double height, double sidePadding = 24)
    {
        var list = new ItemsControl
        {
            ItemsSource = items,
            ItemTemplate = (DataTemplate)Application.Current.FindResource(template),
            Focusable = false,
        };
        VirtualizingPanel.SetIsVirtualizing(list, true);
        VirtualizingPanel.SetVirtualizationMode(list, VirtualizationMode.Recycling);
        VirtualizingPanel.SetScrollUnit(list, ScrollUnit.Pixel);
        // A screenful realised either side, so dragging reveals posters already laid out.
        VirtualizingPanel.SetCacheLengthUnit(list, VirtualizationCacheLengthUnit.Page);
        VirtualizingPanel.SetCacheLength(list, new VirtualizationCacheLength(1));
        // Panel and scroller come from Templates.xaml: the ScrollViewer has to sit
        // inside the ItemsControl's own template for the row to virtualise.
        list.ItemsPanel = (ItemsPanelTemplate)Application.Current.FindResource("RailPanel");
        list.Template = (ControlTemplate)Application.Current.FindResource("RailTemplate");
        list.Padding = new Thickness(sidePadding, 0, sidePadding, 0);
        list.Height = height;

        var grid = new Grid();
        grid.Children.Add(list);
        list.Loaded += (_, _) =>
        {
            if (VisualTreeHelper.GetChildrenCount(list) > 0 && VisualTreeHelper.GetChild(list, 0) is ScrollViewer inner)
            {
                PassWheel(inner);
                DragScroll(inner);
            }
        };

        Button Arrow(string icon, HorizontalAlignment side, Action<ScrollViewer> move)
        {
            var b = Round(icon, () =>
            {
                if (VisualTreeHelper.GetChildrenCount(list) > 0 && VisualTreeHelper.GetChild(list, 0) is ScrollViewer inner) move(inner);
            }, "Text", "SurfaceAlt", 40, 20);
            b.HorizontalAlignment = side;
            b.VerticalAlignment = VerticalAlignment.Top;
            b.Margin = new Thickness(6, height * 0.32, 6, 0);
            // Hidden until hover, and no shadow effect: an effect over a moving shelf is
            // redrawn every frame of a scroll.
            b.Visibility = Visibility.Hidden;
            b.Opacity = 0.92;
            return b;
        }
        var left = Arrow("chevron-left", HorizontalAlignment.Left, sv2 => PageRail(sv2, -1));
        var right = Arrow("chevron-right", HorizontalAlignment.Right, sv2 => PageRail(sv2, 1));
        grid.Children.Add(left);
        grid.Children.Add(right);
        grid.MouseEnter += (_, _) => left.Visibility = right.Visibility = Visibility.Visible;
        grid.MouseLeave += (_, _) => left.Visibility = right.Visibility = Visibility.Hidden;
        grid.Background = Brushes.Transparent;
        return grid;
    }

    /// <summary>One card plus the gap after it — the cards' 140 wide and 10 margin.</summary>
    const double CardStep = 150;

    /// <summary>
    /// The arrows move a whole screenful of cards, landing on a card edge, so the
    /// next set starts where the last one ended rather than a few pixels along.
    /// </summary>
    static void PageRail(ScrollViewer rail, int direction)
    {
        var cards = Math.Max(1, Math.Floor(rail.ViewportWidth / CardStep));
        var start = animating.TryGetValue(rail, out var pending) ? pending : rail.HorizontalOffset;
        var target = Math.Round((start + direction * cards * CardStep) / CardStep) * CardStep;
        SmoothScroll(rail, Math.Clamp(target, 0, rail.ScrollableWidth));
    }

    static readonly Dictionary<ScrollViewer, double> animating = new();

    static void SmoothScroll(ScrollViewer rail, double to)
    {
        var from = rail.HorizontalOffset;
        animating[rail] = to;
        var started = DateTime.UtcNow;
        var timer = new System.Windows.Threading.DispatcherTimer(System.Windows.Threading.DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(12) };
        timer.Tick += (_, _) =>
        {
            if (!animating.TryGetValue(rail, out var current) || current != to) { timer.Stop(); return; }
            var t = Math.Min(1, (DateTime.UtcNow - started).TotalMilliseconds / 320);
            var eased = 1 - Math.Pow(1 - t, 3);
            rail.ScrollToHorizontalOffset(from + (to - from) * eased);
            if (t >= 1)
            {
                timer.Stop();
                animating.Remove(rail);
            }
        };
        timer.Start();
    }

    /// <summary>
    /// Click and drag to swipe a shelf, like a thumb on the phone. Past a few
    /// pixels the drag takes the mouse from the card under it, so letting go
    /// does not also open that card; a quick fling carries on for a moment.
    /// </summary>
    public static void DragScroll(ScrollViewer rail)
    {
        Point? start = null;
        double origin = 0;
        var dragging = false;
        var samples = new List<(DateTime At, double X)>();

        rail.PreviewMouseLeftButtonDown += (_, e) =>
        {
            animating.Remove(rail);
            start = e.GetPosition(rail);
            origin = rail.HorizontalOffset;
            dragging = false;
            samples.Clear();
        };
        rail.PreviewMouseMove += (_, e) =>
        {
            if (start is null) return;
            if (e.LeftButton != MouseButtonState.Pressed) { start = null; return; }
            var x = e.GetPosition(rail).X;
            var dx = x - start.Value.X;
            if (!dragging)
            {
                if (Math.Abs(dx) < 6) return;
                dragging = true;
                rail.CaptureMouse();
                rail.Cursor = Cursors.ScrollWE;
            }
            samples.Add((DateTime.UtcNow, x));
            if (samples.Count > 6) samples.RemoveAt(0);
            rail.ScrollToHorizontalOffset(origin - dx);
            e.Handled = true;
        };
        void End(MouseButtonEventArgs? e)
        {
            if (dragging)
            {
                dragging = false;
                rail.Cursor = null;
                if (rail.IsMouseCaptured) rail.ReleaseMouseCapture();
                if (e is not null) e.Handled = true;
                // Fling: keep going the way the pointer was moving, then settle.
                if (samples.Count >= 2)
                {
                    var (t0, x0) = samples[0];
                    var (t1, x1) = samples[^1];
                    var ms = (t1 - t0).TotalMilliseconds;
                    if (ms > 0 && ms < 200 && (DateTime.UtcNow - t1).TotalMilliseconds < 80)
                    {
                        var velocity = (x1 - x0) / ms;
                        SmoothScroll(rail, Math.Clamp(rail.HorizontalOffset - velocity * 260, 0, rail.ScrollableWidth));
                    }
                }
            }
            start = null;
        }
        rail.PreviewMouseLeftButtonUp += (_, e) => End(e);
        // Only the rail's own capture ending ends the drag. Taking the mouse from the poster
        // pressed under it raises the poster's LostMouseCapture, which bubbles up here too
        // and used to cut every drag off a few pixels in.
        rail.LostMouseCapture += (_, e) => { if (dragging && e.OriginalSource == rail) End(null); };
    }

    /// <summary>A wrapping grid of posters — PosterGrid, sized for a window rather than a phone.</summary>
    public static ItemsControl Grid(IEnumerable items, string template = "PosterCard")
    {
        var list = new ItemsControl
        {
            ItemsSource = items,
            ItemTemplate = (DataTemplate)Application.Current.FindResource(template),
            Focusable = false,
        };
        var panel = new FrameworkElementFactory(typeof(ColumnWrap));
        panel.SetValue(ColumnWrap.MinColumnProperty, 150.0);
        list.ItemsPanel = new ItemsPanelTemplate(panel);
        list.ItemContainerStyle = new Style(typeof(ContentPresenter)) { Setters = { new Setter(FrameworkElement.MarginProperty, new Thickness(0, 0, 0, 18)) } };
        return list;
    }

    public static FrameworkElement Spinner(double size = 22, string brush = "Accent")
    {
        var s = new ContentControl { Style = S("Spinner"), Width = size, Height = size };
        s.SetResourceReference(Control.ForegroundProperty, brush);
        return s;
    }

    /// <summary>The empty state: a badge, a title, a line of explanation.</summary>
    public static FrameworkElement Empty(string icon, string title, string text) => new StackPanel
    {
        HorizontalAlignment = HorizontalAlignment.Center,
        Margin = new Thickness(0, 48, 0, 48),
        Children =
        {
            Brushed(new Border { Width = 64, Height = 64, CornerRadius = new CornerRadius(32), Child = new Grid { Children = { Glyph(icon, 24, "Faint") } }, Margin = new Thickness(0, 0, 0, 16), HorizontalAlignment = HorizontalAlignment.Center }, "SurfaceAlt"),
            Center(Text(title, "TitleText", "Text", 16)),
            Center(Wrap(Text(text, "Body", "Faint"), 320)),
        },
    };

    static T Center<T>(T e) where T : FrameworkElement { e.HorizontalAlignment = HorizontalAlignment.Center; if (e is TextBlock t) t.TextAlignment = TextAlignment.Center; return e; }
    static TextBlock Wrap(TextBlock t, double max) { t.MaxWidth = max; t.TextWrapping = TextWrapping.Wrap; t.Margin = new Thickness(0, 8, 0, 0); return t; }

    public static ProgressBar Bar(double value, double height = 3, string brush = "Accent")
    {
        var bar = new ProgressBar { Style = S("Bar"), Value = value, Height = height };
        bar.SetResourceReference(Control.ForegroundProperty, brush);
        return bar;
    }

    /// <summary>A poster/still as a rounded image over its name's colour.</summary>
    public static FrameworkElement Art(string? url, string name, double width, double height, double radius = 12, int decode = 0)
    {
        var grid = new Grid { Width = width, Height = height };
        grid.Children.Add(new Border { CornerRadius = new CornerRadius(radius), Background = (Brush)new HueBrush().Convert(name, typeof(Brush), null!, null!) });
        var image = ImageUrl.Load(url, decode > 0 ? decode : (int)(width * 2));
        if (image is not null)
            grid.Children.Add(new Border { CornerRadius = new CornerRadius(radius), Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill } });
        return grid;
    }

    /// <summary>
    /// A page heading (mobile Ui.kt Heading): the name large, an action slot on the right,
    /// and the page under it; with <paramref name="back"/>, a round back button before the name.
    /// Returns the root plus the two slots to fill.
    /// </summary>
    public static (Grid Root, StackPanel Actions, ContentControl Body) Screen(string title, Action? back = null)
    {
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        var header = new DockPanel { Margin = new Thickness(24, 24, 24, 14), LastChildFill = false };
        if (back is not null)
        {
            var button = Round("arrow-left", back, "Text", "Tint", 40, 20, "Back");
            button.Margin = new Thickness(0, 0, 14, 0);
            DockPanel.SetDock(button, Dock.Left);
            header.Children.Add(button);
        }
        var name = Text(title, "Display");
        name.VerticalAlignment = VerticalAlignment.Center;
        DockPanel.SetDock(name, Dock.Left);
        header.Children.Add(name);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        DockPanel.SetDock(actions, Dock.Right);
        header.Children.Add(actions);
        root.Children.Add(header);
        var body = new ContentControl { Focusable = false };
        System.Windows.Controls.Grid.SetRow(body, 1);
        root.Children.Add(body);
        return (root, actions, body);
    }

    /// <summary>A search field in its rounded surface: icon, box, and a clear button.</summary>
    public static (Border Box, TextBox Input, ContentControl Trailing) SearchField(string hint, double height = 50, double radius = 8)
    {
        var input = new TextBox { Style = S("Field"), Tag = hint, VerticalAlignment = VerticalAlignment.Center };
        var trailing = new ContentControl { Focusable = false, VerticalAlignment = VerticalAlignment.Center };
        var dock = new DockPanel();
        var icon = Glyph("search", 20, "Faint");
        icon.Margin = new Thickness(0, 0, 12, 0);
        DockPanel.SetDock(icon, Dock.Left);
        DockPanel.SetDock(trailing, Dock.Right);
        dock.Children.Add(icon);
        dock.Children.Add(trailing);
        dock.Children.Add(input);
        var box = Brushed(new Border { Height = height, CornerRadius = new CornerRadius(radius), Padding = new Thickness(14, 0, 12, 0), Child = dock, Cursor = Cursors.IBeam }, "TintLo");
        box.MouseLeftButtonDown += (_, _) => input.Focus();
        return (box, input, trailing);
    }

    public static ToggleButton Chip(string label, bool on, Action onClick, string? icon = null, string style = "Chip")
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal };
        var chip = new ToggleButton { Style = S(style), IsChecked = on, Content = content };
        if (icon is not null)
        {
            var glyph = new Icon { Kind = icon, Size = 13, Margin = new Thickness(0, 0, 5, 0), VerticalAlignment = VerticalAlignment.Center };
            glyph.SetBinding(Icon.ForegroundProperty, new System.Windows.Data.Binding(nameof(Control.Foreground)) { Source = chip });
            content.Children.Add(glyph);
        }
        content.Children.Add(new TextBlock { Text = label, VerticalAlignment = VerticalAlignment.Center });
        chip.Click += (_, _) => { chip.IsChecked = on; onClick(); };
        return chip;
    }

    /// <summary>
    /// core TitleMark: the title's logo when Cinemeta has one, else its name set large. The
    /// name shows until the logo lands, and stays if it never does.
    /// </summary>
    public static FrameworkElement TitleMark(Core.Title title, double maxHeight, double maxWidth, double fontSize = 28)
    {
        var name = Text(title.Name, "Display", "Text", fontSize);
        name.TextWrapping = TextWrapping.Wrap;
        name.MaxWidth = maxWidth;
        name.HorizontalAlignment = HorizontalAlignment.Left;
        var grid = new Grid { HorizontalAlignment = HorizontalAlignment.Left, Children = { name } };
        if (ImageUrl.Load(title.Logo, 600) is not DrawingImage { Drawing: ImageDrawing drawing } logo) return grid;
        var image = new Image { Source = logo, Stretch = Stretch.Uniform, MaxHeight = maxHeight, MaxWidth = maxWidth, HorizontalAlignment = HorizontalAlignment.Left, Visibility = Visibility.Collapsed };
        grid.Children.Add(image);
        void Show()
        {
            if (drawing.ImageSource is null) return;
            image.Visibility = Visibility.Visible;
            name.Visibility = Visibility.Collapsed;
        }
        Show();
        logo.Changed += (_, _) => Show();
        return grid;
    }

    /// <summary>core Facts: the IMDb badge, then year • runtime • genres.</summary>
    public static FrameworkElement Facts(Core.Title title, int genres = 2)
    {
        var row = new WrapPanel();
        if (title.Rating is not null)
        {
            var badge = Brushed(new Border
            {
                CornerRadius = new CornerRadius(3),
                Padding = new Thickness(5, 1, 5, 1),
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock { Text = "IMDb", FontSize = 11, FontWeight = FontWeights.Black, Foreground = Brushes.Black },
            }, "Imdb");
            var score = Text(title.Rating, "Meta", "Text", 13, FontWeights.SemiBold);
            score.Margin = new Thickness(6, 0, 14, 0);
            score.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(badge);
            row.Children.Add(score);
        }
        var facts = new[] { title.Year is { Length: > 9 } y ? y[..9] : title.Year, title.Runtime }.Concat(title.Genres.Take(genres)).Where(f => !string.IsNullOrEmpty(f));
        var line = Text(string.Join("  •  ", facts), "Meta", "Text", 13);
        line.Opacity = 0.85;
        line.VerticalAlignment = VerticalAlignment.Center;
        row.Children.Add(line);
        return row;
    }

    /// <summary>The TV's button: an icon and a word, the red glow for the main action (mobile Ui.kt Button).</summary>
    public static Button Action(string label, string icon, Action onClick, bool primary = false, double width = double.NaN)
    {
        var fg = primary ? "OnAccent" : "Text";
        var words = Text(label, "Label", fg, 15, FontWeights.SemiBold);
        words.Margin = new Thickness(10, 0, 0, 0);
        var b = Button(new StackPanel { Orientation = Orientation.Horizontal, Children = { Glyph(icon, 18, fg), words } }, onClick, primary ? "PillGlow" : "Pill");
        b.Width = width;
        return b;
    }

    /// <summary>
    /// Netflix's actions under the Play button (mobile Ui.kt Action): an icon over a word. A
    /// toggle that is on sits in a disc of <paramref name="onBrush"/>, its word in the same colour.
    /// </summary>
    public static Button IconAction(string icon, string label, Action onClick, bool on = false, string onBrush = "Good", double width = 84)
    {
        var disc = Brushed(new Border { Width = 36, Height = 36, CornerRadius = new CornerRadius(18), Visibility = on ? Visibility.Visible : Visibility.Hidden }, onBrush);
        var glyph = Glyph(icon, 22, "Text");
        if (on) glyph.Foreground = Brushes.White;
        var words = Text(label, "Meta", on ? onBrush : "Muted", 12, on ? FontWeights.SemiBold : FontWeights.Medium);
        words.HorizontalAlignment = HorizontalAlignment.Center;
        words.Margin = new Thickness(0, 4, 0, 0);
        var b = Button(new StackPanel
        {
            Margin = new Thickness(0, 6, 0, 6),
            Children = { new Grid { Width = 40, Height = 40, Children = { disc, glyph } }, words },
        }, onClick);
        b.Width = width;
        b.Tag = 8;
        return b;
    }

    public static Border Rule(double height = 16) => Brushed(new Border { Width = 1, Height = height, Margin = new Thickness(4, 0, 4, 0), VerticalAlignment = VerticalAlignment.Center }, "Line");

    /// <summary>Health dot colours for a seeder count — DetailScreen's healthTone.</summary>
    public static (string Label, string Fg, string Bg) HealthTone(int seeders) => Core.Search.Health(seeders) switch
    {
        "fast" => ("Fast", "Good", "GoodSoft"),
        "good" => ("Good", "Good", "GoodSoft"),
        "weak" => ("Weak", "Warn", "WarnSoft"),
        _ => ("No seeds", "Danger", "DangerSoft"),
    };

    /// <summary>A web page in the browser. Only http(s): handed to the shell, a file path or other scheme would run.</summary>
    public static void Open(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp)) return;
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true }); } catch { }
    }
}

/// <summary>
/// A WrapPanel that shares its width out in equal columns, as many as fit
/// <see cref="MinColumn"/> — so a list built for a phone fills a desktop window
/// instead of hugging its left edge. Fixed-width children sit centred in their column.
/// </summary>
public sealed class ColumnWrap : WrapPanel
{
    public static readonly DependencyProperty MinColumnProperty = DependencyProperty.Register(nameof(MinColumn), typeof(double), typeof(ColumnWrap),
        new FrameworkPropertyMetadata(520.0, FrameworkPropertyMetadataOptions.AffectsMeasure));

    public double MinColumn
    {
        get => (double)GetValue(MinColumnProperty);
        set => SetValue(MinColumnProperty, value);
    }

    public static readonly DependencyProperty FillRowProperty = DependencyProperty.Register(nameof(FillRow), typeof(bool), typeof(ColumnWrap),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsMeasure));

    /// <summary>Fewer items than fit across: they share the whole width instead of leaving it empty.</summary>
    public bool FillRow
    {
        get => (bool)GetValue(FillRowProperty);
        set => SetValue(FillRowProperty, value);
    }

    protected override Size MeasureOverride(Size available)
    {
        if (!double.IsInfinity(available.Width) && available.Width > 0)
        {
            var columns = Math.Max(1, Math.Floor(available.Width / MinColumn));
            var items = InternalChildren.Cast<UIElement>().Count(c => c.Visibility != Visibility.Collapsed);
            if (FillRow && items > 0) columns = Math.Min(columns, items);
            ItemWidth = Math.Floor(available.Width / columns);
        }
        return base.MeasureOverride(available);
    }
}
