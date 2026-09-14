using System.Windows;
using System.Windows.Controls;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>src/screens/CatalogScreen.tsx — everything on one shelf, as a grid.</summary>
public sealed class CatalogView : UserControl
{
    public CatalogView(Shelf shelf)
    {
        var (root, actions, body) = Ui.Screen(shelf.Heading, null);
        actions.Children.Add(Ui.Round("x", () => MainWindow.Shell.CloseLayer(this), "Accent", "AccentSoft", 36, 18, "Back"));
        body.Content = new Grid { Children = { Ui.Spinner() }, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 32, 0, 0) };
        Content = root;
        Load(shelf, body);
    }

    async void Load(Shelf shelf, ContentControl body)
    {
        var titles = await Meta.LoadCatalog(shelf.Kind, shelf.Genre);
        body.Content = titles.Count == 0
            ? Ui.Empty("wifi-off", "Could not load this shelf", "Check the connection — it loads again when you are back online.")
            : new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Padding = new Thickness(24, 8, 24, 40),
                Content = Ui.Grid(titles),
            };
    }
}
