using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Watchly.Controls;
using Watchly.Core;

namespace Watchly.Views;

/// <summary>
/// The phone's first-launch tour (Onboarding.kt): one welcome over a wall of what is trending, then the genres the
/// home page leads with. Once, on a fresh install, after the first profile is made.
/// </summary>
public sealed class WelcomeView : UserControl
{
    const string Key = "toured";

    /// <summary>
    /// A fresh install: never toured, no profile yet, no history, and the terms never accepted before — a Guest-only
    /// user keeps no history, but has accepted them. Read at launch, before the first profile is made.
    /// </summary>
    public static bool Needed => Store.Load<string?>(Key, null) is null && Profiles.Load().Count == 0 && History.Load().Count == 0 && !AgreementView.EverAccepted;

    readonly MainWindow shell = MainWindow.Shell;
    readonly Action onDone;
    readonly WrapPanel wall = new() { Opacity = 0.35, IsHitTestVisible = false, Margin = new Thickness(-20, -20, 0, 0) };
    readonly StackPanel words = new() { VerticalAlignment = VerticalAlignment.Bottom, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(64, 0, 64, 56), MaxWidth = 720 };
    readonly HashSet<string> picked;

    public WelcomeView(Action onDone)
    {
        this.onDone = onDone;
        picked = shell.Settings.Genres.ToHashSet();
        SetResourceReference(BackgroundProperty, "PageBg");
        var fade = new Border
        {
            IsHitTestVisible = false,
            Background = new LinearGradientBrush(new GradientStopCollection
            {
                new(Color.FromArgb(0x30, 0, 0, 0), 0), new(Color.FromArgb(0xd0, 0, 0, 0), 0.5), new(Colors.Black, 1),
            }, 90),
        };
        Content = new Grid { ClipToBounds = true, Children = { wall, fade, words } };
        _ = FillWall();
        Front();
    }

    async Task FillWall()
    {
        try
        {
            var titles = (await Meta.LoadCatalog("movie")).Take(14).Concat((await Meta.LoadCatalog("tv")).Take(14)).Where(t => t.Poster is not null);
            foreach (var t in titles)
            {
                var art = Ui.Art(t.Poster, t.Name, 150, 225, 10);
                art.Margin = new Thickness(0, 0, 14, 14);
                wall.Children.Add(art);
            }
        }
        catch { /* Offline: the page stands on its own. */ }
    }

    static TextBlock White(string text, double size, FontWeight weight, double alpha = 1)
    {
        var t = new TextBlock { Text = text, FontSize = size, FontWeight = weight, Foreground = new SolidColorBrush(Color.FromArgb((byte)(alpha * 255), 255, 255, 255)), TextWrapping = TextWrapping.Wrap, FontFamily = Ui.Sans };
        return t;
    }

    void Front()
    {
        words.Children.Clear();
        var brand = Ui.Text("WATCHLY", "TitleText", "Accent", 18, FontWeights.Black);
        brand.Margin = new Thickness(0, 0, 0, 14);
        words.Children.Add(brand);
        words.Children.Add(White("Your media player", 40, FontWeights.ExtraBold));
        var body = White("Browse trending films, series and anime, and play them from the addons and servers you choose. Watchly comes with none.", 16, FontWeights.Normal, 0.8);
        body.Margin = new Thickness(0, 10, 0, 26);
        words.Children.Add(body);
        var go = Ui.Button(Ui.Text("Get started", "Label", "OnAccent", 15, FontWeights.SemiBold), Genres, "PillGlow");
        go.HorizontalAlignment = HorizontalAlignment.Left;
        words.Children.Add(go);
    }

    void Genres()
    {
        words.Children.Clear();
        words.Children.Add(White("What do you like?", 40, FontWeights.ExtraBold));
        var body = White("Pick a few — your home page leads with them. Change them any time in Settings.", 16, FontWeights.Normal, 0.8);
        body.Margin = new Thickness(0, 10, 0, 18);
        words.Children.Add(body);
        var chips = new WrapPanel();
        foreach (var g in Meta.Genres)
        {
            var chip = Ui.Chip(g, picked.Contains(g), () => { if (!picked.Remove(g)) picked.Add(g); Genres(); }, style: "ChoiceChip");
            chip.Margin = new Thickness(0, 0, 8, 8);
            chips.Children.Add(chip);
        }
        words.Children.Add(chips);
        var go = Ui.Button(Ui.Text(picked.Count == 0 ? "Continue" : $"Continue with {picked.Count}", "Label", "OnAccent", 15, FontWeights.SemiBold), () =>
        {
            shell.ChangeSettings(shell.Settings with { Genres = Meta.Genres.Where(picked.Contains).ToList() });
            Finish();
        }, "PillGlow");
        var skip = Ui.Button(Ui.Text("Skip", "Label", "Text", 15), Finish, "Pill");
        skip.Margin = new Thickness(10, 0, 0, 0);
        words.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 16, 0, 0), Children = { go, skip } });
    }

    void Finish()
    {
        Store.Save(Key, "1");
        onDone();
    }
}
