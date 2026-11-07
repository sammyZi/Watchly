using System.Windows;
using System.Windows.Controls;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>What the viewer confirms before using the app, over everything else — mobile Onboarding.kt Agreement.</summary>
public sealed class AgreementView : UserControl
{
    const string Key = "legal-accepted";

    /// <summary>Nothing accepted yet, or the words have changed since.</summary>
    public static bool Needed => Store.Load<string?>(Key, null) != Legal.Updated;

    readonly Action onDone;
    readonly bool changed = Store.Load<string?>(Key, null) is not null;

    public AgreementView(Action onDone)
    {
        this.onDone = onDone;
        SetResourceReference(BackgroundProperty, "PageBg");
        Front();
    }

    /// <summary>A legal page's sections, for here and for Settings.</summary>
    public static StackPanel Sections(List<Legal.Section> sections)
    {
        var page = new StackPanel { MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };
        page.Children.Add(Wrapped(Legal.Updated, "Faint", 12, new Thickness(0, 0, 0, 14)));
        foreach (var section in sections)
        {
            page.Children.Add(Ui.Text(section.Heading, "Label", "Text", 15, FontWeights.SemiBold));
            page.Children.Add(Wrapped(section.Text, "Muted", 14, new Thickness(0, 4, 0, 16)));
        }
        return page;
    }

    static TextBlock Wrapped(string text, string brush, double size, Thickness margin)
    {
        var t = Ui.Text(text, "Meta", brush, size);
        t.TextWrapping = TextWrapping.Wrap;
        t.Margin = margin;
        return t;
    }

    void Front()
    {
        var head = Ui.Text(changed ? "Our terms have changed" : "Before you start", "Label", "Text", 32, FontWeights.Bold);
        var column = new StackPanel { MaxWidth = 620, Margin = new Thickness(24), VerticalAlignment = VerticalAlignment.Center, Children =
        {
            head,
            Wrapped(Legal.Summary, "Muted", 15, new Thickness(0, 12, 0, 0)),
            Wrapped(Legal.Accept, "Muted", 15, new Thickness(0, 12, 0, 24)),
        } };
        var links = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 12) };
        foreach (var (name, icon, sections) in new[] { ("Privacy policy", "lock", Legal.Privacy), ("Terms and conditions", "file", Legal.Terms), ("Legal notice", "info", Legal.Notice) })
        {
            var link = Ui.Action(name, icon, () => Read(name, sections));
            link.Margin = new Thickness(0, 0, 10, 0);
            links.Children.Add(link);
        }
        column.Children.Add(links);
        column.Children.Add(Ui.Action("I accept", "check", Accept, primary: true));
        Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = column };
    }

    void Read(string name, List<Legal.Section> sections)
    {
        var (root, _, body) = Ui.Screen(name, Front);
        var page = Sections(sections);
        page.Margin = new Thickness(24, 0, 24, 32);
        body.Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = page };
        Content = root;
    }

    void Accept()
    {
        Store.Save(Key, Legal.Updated);
        onDone();
    }
}
