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
        var (root, _, body) = Ui.Screen(shelf.Heading, () => MainWindow.Shell.CloseLayer(this));
        body.Content = new Grid { Children = { Ui.Spinner() }, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 32, 0, 0) };
        Content = root;
        Load(shelf, body);
    }

    /// <summary>Cinemeta hands the shelf over by popularity; this reorders what arrived.</summary>
    string sort = "popular";

    async void Load(Shelf shelf, ContentControl body)
    {
        var titles = KidsMode.Only(await Meta.LoadCatalog(shelf.Kind, shelf.Genre));
        if (titles.Count == 0)
        {
            body.Content = Ui.Empty("wifi-off", "Could not load this shelf", "Check the connection — it loads again when you are back online.");
            return;
        }

        var chips = new WrapPanel { Margin = new Thickness(24, 0, 24, 4) };
        var grid = new ContentControl { Focusable = false };
        // Top rated is the genre's real best, not just its most popular reordered. An addon's list is only itself.
        List<Title>? best = null;
        async void Sort(string key)
        {
            sort = key;
            if (key == "rating" && best is null && shelf.Genre?.StartsWith(Addons.ShelfPrefix) != true)
                best = KidsMode.Only(await Meta.LoadTop(shelf.Kind, shelf.Genre));
            Show();
        }
        void Show()
        {
            chips.Children.Clear();
            var sliders = Ui.Glyph("sliders", 13, "Faint");
            sliders.Margin = new Thickness(0, 0, 8, 0);
            sliders.VerticalAlignment = VerticalAlignment.Center;
            chips.Children.Add(sliders);
            foreach (var (key, label) in Meta.Sorts)
            {
                var chip = Ui.Chip(label, sort == key, () => Sort(key));
                chip.Margin = new Thickness(0, 0, 8, 6);
                chips.Children.Add(chip);
            }
            grid.Content = Ui.Grid(Meta.SortTitles(sort == "rating" && best is { Count: > 0 } ? best : titles, sort));
        }
        Show();

        var page = new DockPanel();
        DockPanel.SetDock(chips, Dock.Top);
        page.Children.Add(chips);
        page.Children.Add(new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Padding = new Thickness(24, 8, 24, 40),
            Content = grid,
        });
        body.Content = page;
    }
}
