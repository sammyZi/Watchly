using System.Windows;
using System.Windows.Media;

namespace Watchly.Controls;

/// <summary>
/// Feather line icons — the set the phone uses (src/components/Icon.tsx). Each is
/// the icon's 24×24 SVG as path data, stroked 2px with round caps, so it scales
/// to any size and takes its colour from <see cref="Foreground"/>.
/// </summary>
public sealed class Icon : FrameworkElement
{
    static Icon()
    {
        foreach (var (name, data) in Glyphs)
        {
            var geometry = Geometry.Parse(data);
            geometry.Freeze();
            Parsed[name] = geometry;
        }
    }

    static readonly Dictionary<string, Geometry> Parsed = new();

    static string Circle(double cx, double cy, double r) =>
        $"M{cx - r},{cy} A{r},{r} 0 1 0 {cx + r},{cy} A{r},{r} 0 1 0 {cx - r},{cy} Z ";

    static readonly (string, string)[] Glyphs =
    {
        ("home", "M3,9 L12,2 L21,9 V20 A2,2 0 0 1 19,22 H5 A2,2 0 0 1 3,20 Z M9,22 V12 H15 V22"),
        ("search", Circle(11, 11, 8) + "M21,21 L16.65,16.65"),
        ("clock", Circle(12, 12, 10) + "M12,6 V12 L16,14"),
        ("download", "M21,15 V19 A2,2 0 0 1 19,21 H5 A2,2 0 0 1 3,19 V15 M7,10 L12,15 L17,10 M12,15 V3"),
        ("upload", "M21,15 V19 A2,2 0 0 1 19,21 H5 A2,2 0 0 1 3,19 V15 M17,8 L12,3 L7,8 M12,3 V15"),
        ("sliders", "M4,21 V14 M4,10 V3 M12,21 V12 M12,8 V3 M20,21 V16 M20,12 V3 M1,14 H7 M9,8 H15 M17,16 H23"),
        ("play", "M5,3 L19,12 L5,21 Z"),
        ("pause", "M6,4 H10 V20 H6 Z M14,4 H18 V20 H14 Z"),
        ("x", "M18,6 L6,18 M6,6 L18,18"),
        ("x-circle", Circle(12, 12, 10) + "M15,9 L9,15 M9,9 L15,15"),
        ("star", "M12,2 L15.09,8.26 L22,9.27 L17,14.14 L18.18,21.02 L12,17.77 L5.82,21.02 L7,14.14 L2,9.27 L8.91,8.26 Z"),
        ("chevron-right", "M9,18 L15,12 L9,6"),
        ("chevron-left", "M15,18 L9,12 L15,6"),
        ("chevron-down", "M6,9 L12,15 L18,9"),
        ("chevron-up", "M18,15 L12,9 L6,15"),
        ("arrow-left", "M19,12 H5 M12,19 L5,12 L12,5"),
        ("bookmark", "M19,21 L12,16 L5,21 V5 A2,2 0 0 1 7,3 H17 A2,2 0 0 1 19,5 Z"),
        ("heart", "M20.84,4.61 A5.5,5.5 0 0 0 13.06,4.61 L12,5.67 L10.94,4.61 A5.5,5.5 0 0 0 3.16,12.39 L4.22,13.45 L12,21.23 L19.78,13.45 L20.84,12.39 A5.5,5.5 0 0 0 20.84,4.61 Z"),
        ("film", "M4.18,2 H19.82 A2.18,2.18 0 0 1 22,4.18 V19.82 A2.18,2.18 0 0 1 19.82,22 H4.18 A2.18,2.18 0 0 1 2,19.82 V4.18 A2.18,2.18 0 0 1 4.18,2 Z M7,2 V22 M17,2 V22 M2,12 H22 M2,7 H7 M2,17 H7 M17,17 H22 M17,7 H22"),
        ("tv", "M4,7 H20 A2,2 0 0 1 22,9 V20 A2,2 0 0 1 20,22 H4 A2,2 0 0 1 2,20 V9 A2,2 0 0 1 4,7 Z M17,2 L12,7 L7,2"),
        ("trash-2", "M3,6 H21 M19,6 V20 A2,2 0 0 1 17,22 H7 A2,2 0 0 1 5,20 V6 M8,6 V4 A2,2 0 0 1 10,2 H14 A2,2 0 0 1 16,4 V6 M10,11 V17 M14,11 V17"),
        ("lock", "M5,11 H19 A2,2 0 0 1 21,13 V20 A2,2 0 0 1 19,22 H5 A2,2 0 0 1 3,20 V13 A2,2 0 0 1 5,11 Z M7,11 V7 A5,5 0 0 1 17,7 V11"),
        ("unlock", "M5,11 H19 A2,2 0 0 1 21,13 V20 A2,2 0 0 1 19,22 H5 A2,2 0 0 1 3,20 V13 A2,2 0 0 1 5,11 Z M7,11 V7 A5,5 0 0 1 16.9,6"),
        ("maximize", "M8,3 H5 A2,2 0 0 0 3,5 V8 M21,8 V5 A2,2 0 0 0 19,3 H16 M16,21 H19 A2,2 0 0 0 21,19 V16 M3,16 V19 A2,2 0 0 0 5,21 H8"),
        ("minimize", "M8,3 V6 A2,2 0 0 1 6,8 H3 M21,8 H18 A2,2 0 0 1 16,6 V3 M16,21 V18 A2,2 0 0 1 18,16 H21 M3,16 H6 A2,2 0 0 1 8,18 V21"),
        ("info", "M12,2 A10,10 0 1 1 11.99,2 Z M12,16 V12 M12,8 H12.01"),
        ("pip","M4,4 H20 A2,2 0 0 1 22,6 V18 A2,2 0 0 1 20,20 H4 A2,2 0 0 1 2,18 V6 A2,2 0 0 1 4,4 Z M13,12 H19 V17 H13 Z"),
        ("rotate-ccw","M1,4 V10 H7 M3.51,15 A9,9 0 1 0 5.64,5.64 L1,10"),
        ("rotate-cw", "M23,4 V10 H17 M20.49,15 A9,9 0 1 1 18.37,5.64 L23,10"),
        ("skip-forward", "M5,4 L15,12 L5,20 Z M19,5 V19"),
        ("users", "M17,21 V19 A4,4 0 0 0 13,15 H5 A4,4 0 0 0 1,19 V21 " + Circle(9, 7, 4) + "M23,21 V19 A4,4 0 0 0 20,15.13 M16,3.13 A4,4 0 0 1 16,10.88"),
        ("external-link", "M18,13 V19 A2,2 0 0 1 16,21 H5 A2,2 0 0 1 3,19 V8 A2,2 0 0 1 5,6 H11 M15,3 H21 V9 M10,14 L21,3"),
        ("award", Circle(12, 8, 7) + "M8.21,13.89 L7,23 L12,20 L17,23 L15.79,13.88"),
        ("check", "M20,6 L9,17 L4,12"),
        ("folder", "M22,19 A2,2 0 0 1 20,21 H4 A2,2 0 0 1 2,19 V5 A2,2 0 0 1 4,3 H9 L11,6 H20 A2,2 0 0 1 22,8 Z"),
        ("file", "M13,2 H6 A2,2 0 0 0 4,4 V20 A2,2 0 0 0 6,22 H18 A2,2 0 0 0 20,20 V9 Z M13,2 V9 H20"),
        ("type", "M4,7 V4 H20 V7 M9,20 H15 M12,4 V20"),
        ("plus", "M12,5 V19 M5,12 H19"),
        ("minus", "M5,12 H19"),
        ("sun", Circle(12, 12, 5) + "M12,1 V3 M12,21 V23 M4.22,4.22 L5.64,5.64 M18.36,18.36 L19.78,19.78 M1,12 H3 M21,12 H23 M4.22,19.78 L5.64,18.36 M18.36,5.64 L19.78,4.22"),
        ("volume", "M11,5 L6,9 H2 V15 H6 L11,19 Z M19.07,4.93 A10,10 0 0 1 19.07,19.07 M15.54,8.46 A5,5 0 0 1 15.54,15.54"),
        ("volume-x", "M11,5 L6,9 H2 V15 H6 L11,19 Z M23,9 L17,15 M17,9 L23,15"),
        ("layers", "M12,2 L2,7 L12,12 L22,7 Z M2,17 L12,22 L22,17 M2,12 L12,17 L22,12"),
        ("inbox", "M22,12 H16 L14,15 H10 L8,12 H2 M5.45,5.11 L2,12 V18 A2,2 0 0 0 4,20 H20 A2,2 0 0 0 22,18 V12 L18.55,5.11 A2,2 0 0 0 16.76,4 H7.24 A2,2 0 0 0 5.45,5.11 Z"),
        ("wifi-off", "M1,1 L23,23 M16.72,11.06 A10.94,10.94 0 0 1 19,12.55 M5,12.55 A10.94,10.94 0 0 1 10.17,10.16 M10.71,5.05 A16,16 0 0 1 22.58,9 M1.42,9 A15.91,15.91 0 0 1 6.12,6.12 M8.53,16.11 A6,6 0 0 1 15.48,16.11 M12,20 H12.01"),
        ("hard-drive", "M22,12 H2 M5.45,5.11 L2,12 V18 A2,2 0 0 0 4,20 H20 A2,2 0 0 0 22,18 V12 L18.55,5.11 A2,2 0 0 0 16.76,4 H7.24 A2,2 0 0 0 5.45,5.11 Z M6,16 H6.01 M10,16 H10.01"),
        ("radio", Circle(12, 12, 2) + "M16.24,7.76 A6,6 0 0 1 16.24,16.25 M7.76,16.24 A6,6 0 0 1 7.76,7.75 M19.07,4.93 A10,10 0 0 1 19.07,19.07 M4.93,19.07 A10,10 0 0 1 4.93,4.93"),
        ("fast-forward", "M13,19 L22,12 L13,5 Z M2,19 L11,12 L2,5 Z"),
        ("link", "M10,13 A5,5 0 0 0 17.54,13.54 L20.54,10.54 A5,5 0 0 0 13.47,3.47 L11.75,5.18 M14,11 A5,5 0 0 0 6.46,10.46 L3.46,13.46 A5,5 0 0 0 10.53,20.53 L12.24,18.82"),
        ("copy", "M11,9 H20 A2,2 0 0 1 22,11 V20 A2,2 0 0 1 20,22 H11 A2,2 0 0 1 9,20 V11 A2,2 0 0 1 11,9 Z M5,15 H4 A2,2 0 0 1 2,13 V4 A2,2 0 0 1 4,2 H13 A2,2 0 0 1 15,4 V5"),
        ("subtitles", "M4,4 H20 A2,2 0 0 1 22,6 V18 A2,2 0 0 1 20,20 H4 A2,2 0 0 1 2,18 V6 A2,2 0 0 1 4,4 Z M6,12 H10 M13,12 H18 M6,16 H14 M17,16 H18"),
        ("monitor", "M4,3 H20 A2,2 0 0 1 22,5 V15 A2,2 0 0 1 20,17 H4 A2,2 0 0 1 2,15 V5 A2,2 0 0 1 4,3 Z M8,21 H16 M12,17 V21"),
    };

    public static readonly DependencyProperty KindProperty = DependencyProperty.Register(
        nameof(Kind), typeof(string), typeof(Icon),
        new FrameworkPropertyMetadata("x", FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty SizeProperty = DependencyProperty.Register(
        nameof(Size), typeof(double), typeof(Icon),
        new FrameworkPropertyMetadata(18.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ForegroundProperty = DependencyProperty.Register(
        nameof(Foreground), typeof(Brush), typeof(Icon),
        new FrameworkPropertyMetadata(Brushes.Gray, FrameworkPropertyMetadataOptions.AffectsRender | FrameworkPropertyMetadataOptions.Inherits));

    public static readonly DependencyProperty FilledProperty = DependencyProperty.Register(
        nameof(Filled), typeof(bool), typeof(Icon),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public string Kind { get => (string)GetValue(KindProperty); set => SetValue(KindProperty, value); }
    public double Size { get => (double)GetValue(SizeProperty); set => SetValue(SizeProperty, value); }
    public Brush Foreground { get => (Brush)GetValue(ForegroundProperty); set => SetValue(ForegroundProperty, value); }
    /// <summary>Solid, for the saved state of bookmark and heart.</summary>
    public bool Filled { get => (bool)GetValue(FilledProperty); set => SetValue(FilledProperty, value); }

    public Icon()
    {
        SnapsToDevicePixels = true;
        // A glyph is a fixed-size mark: centred in whatever slot it is given.
        HorizontalAlignment = HorizontalAlignment.Center;
        VerticalAlignment = VerticalAlignment.Center;
    }

    protected override Size MeasureOverride(Size availableSize) => new(Size, Size);

    protected override void OnRender(DrawingContext dc)
    {
        if (!Parsed.TryGetValue(Kind ?? "", out var geometry)) return;
        var scale = Size / 24.0;
        var pen = new Pen(Foreground, 2) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round };
        dc.PushTransform(new ScaleTransform(scale, scale));
        dc.DrawGeometry(Filled ? Foreground : null, pen, geometry);
        dc.Pop();
    }
}
