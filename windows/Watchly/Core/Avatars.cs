using System.Text.Json;
using System.Windows;
using System.Windows.Media;

namespace Watchly.Core;

public sealed record AvatarArt(string Id, string Name, DrawingImage Image);

/// <summary>The profile faces in tv/core's avatars.json — the phone and the TV draw the same file. See tv/core Avatars.kt.</summary>
public static class Avatars
{
    const string GuestArt = "pebble";
    static readonly Color Pupil = Color.FromRgb(0x1D, 0x1B, 0x2A);
    static readonly Color Cheek = Color.FromRgb(0xFF, 0x8F, 0xB8);

    public static readonly IReadOnlyList<AvatarArt> All = Load();

    /// <summary>What a profile can pick; the guest's own is not among them.</summary>
    public static IReadOnlyList<AvatarArt> Pickable { get; } = All.Where(a => a.Id != GuestArt).ToList();

    public static AvatarArt Art(string? id) => All.FirstOrDefault(a => a.Id == id) ?? Pickable[0];

    /// <summary>A profile's face: its pick, or a steady one from its id for a profile that never picked.</summary>
    public static AvatarArt Of(Profile p) =>
        p.IsGuest ? Art(GuestArt)
        : Pickable.FirstOrDefault(a => a.Id == p.Icon) ?? Pickable[(p.Id + p.Name).Sum(c => c) % Pickable.Count];

    static List<AvatarArt> Load()
    {
        using var stream = typeof(Avatars).Assembly.GetManifestResourceStream("avatars.json")!;
        using var doc = JsonDocument.Parse(stream);
        return doc.RootElement.GetProperty("avatars").EnumerateArray().Select(a =>
        {
            var body = a.TryGetProperty("body", out var b) ? b.GetString() : null;
            // The tile's own bounds, so every face scales the same; clipped to its rounded corners.
            var group = new DrawingGroup { ClipGeometry = new RectangleGeometry(new Rect(0, 0, 100, 100), 22, 22) };
            group.Children.Add(new GeometryDrawing(Brushes.Transparent, null, new RectangleGeometry(new Rect(0, 0, 100, 100))));
            foreach (var part in a.GetProperty("parts").EnumerateArray())
                foreach (var layer in Layers(part, body)) group.Children.Add(layer);
            group.Freeze();
            var image = new DrawingImage(group);
            image.Freeze();
            return new AvatarArt(a.GetProperty("id").GetString()!, a.GetProperty("name").GetString()!, image);
        }).ToList();
    }

    static Color Hex(string s) => (Color)ColorConverter.ConvertFromString(s);

    static Drawing Spot(double x, double y, double rx, double ry, Color c, double a = 1) =>
        new GeometryDrawing(new SolidColorBrush(c) { Opacity = a }, null, new EllipseGeometry(new Point(x, y), rx, ry));

    static IEnumerable<Drawing> Layers(JsonElement o, string? body)
    {
        double[]? Nums(string key) => o.TryGetProperty(key, out var v) ? v.EnumerateArray().Select(n => n.GetDouble()).ToArray() : null;
        string? Str(string key) => o.TryGetProperty(key, out var v) ? v.GetString() : null;

        if (Nums("eye") is [var ex, var ey, var er])
            return new[] { Spot(ex, ey, er, er, Colors.White), Spot(ex + .1 * er, ey + .15 * er, .56 * er, .56 * er, Pupil), Spot(ex + .32 * er, ey - .12 * er, .2 * er, .2 * er, Colors.White) };
        if (Nums("dot") is [var dx, var dy, var dr])
            return new[] { Spot(dx, dy, dr, dr, Pupil), Spot(dx + .35 * dr, dy - .35 * dr, .36 * dr, .36 * dr, Colors.White) };
        if (Nums("cheek") is [var cx, var cy]) return new[] { Spot(cx, cy, 6, 3.5, Cheek, .45) };
        if (Nums("hi") is [var hx, var hy, var hrx, var hry]) return new[] { Spot(hx, hy, hrx, hry, Colors.White, .32) };

        // "F1": nonzero fill, as the phone and the TV draw it; WPF's mini-language defaults to even-odd.
        Geometry shape = Nums("o") is [var ox, var oy, var r] ? new EllipseGeometry(new Point(ox, oy), r, r)
            : Nums("e") is [var ox2, var oy2, var rx, var ry] ? new EllipseGeometry(new Point(ox2, oy2), rx, ry)
            : Geometry.Parse("F1 " + (o.TryGetProperty("b", out _) ? body : Str("d")));
        var fill = Str("f") is { } f ? new SolidColorBrush(Hex(f)) : null;
        var pen = Str("s") is { } s
            ? new Pen(new SolidColorBrush(Hex(s)), o.TryGetProperty("w", out var w) ? w.GetDouble() : 1) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round }
            : null;
        var layer = new DrawingGroup { Opacity = o.TryGetProperty("a", out var a) ? a.GetDouble() : 1 };
        if (Nums("t") is [var tx, var ty]) layer.Transform = new TranslateTransform(tx, ty);
        layer.Children.Add(new GeometryDrawing(fill, pen, shape));
        return new[] { layer };
    }
}
