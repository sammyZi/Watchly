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

    /// <summary>Cinemeta hands the shelf over by popularity; this reorders what arrived.</summary>
    string sort = "popular";

    async void Load(Shelf shelf, ContentControl body)
    {
        var titles = await Meta.LoadCatalog(shelf.Kind, shelf.Genre);
        if (titles.Count == 0)
        {
            body.Content = Ui.Empty("wifi-off", "Could not load this shelf", "Check the connection — it loads again when you are back online.");
            return;
        }

        var chips = new WrapPanel { Margin = new Thickness(24, 0, 24, 4) };
        var grid = new ContentControl { Focusable = false };
        void Show()
        {
            chips.Children.Clear();
            var sliders = Ui.Glyph("sliders", 13, "Faint");
            sliders.Margin = new Thickness(0, 0, 8, 0);
            sliders.VerticalAlignment = VerticalAlignment.Center;
            chips.Children.Add(sliders);
            foreach (var (key, label) in Meta.Sorts)
            {
                var chip = Ui.Chip(label, sort == key, () => { sort = key; Show(); });
                chip.Margin = new Thickness(0, 0, 8, 6);
                chips.Children.Add(chip);
            }
            grid.Content = Ui.Grid(Meta.SortTitles(titles, sort));
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
