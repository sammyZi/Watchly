using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using Watchly.Core;

namespace Watchly.Controls;

/// <summary>
/// Every TextBlock in the viewer's language, translated where it is shown rather than where it is made: as it
/// loads, and again whenever code sets its text. A button's or a tooltip's words are a TextBlock too, so the
/// views, the templates and the helpers are all covered without one of them asking. Each block keeps the English
/// it was given, so another language can be put on the screens already open.
/// </summary>
public static class Words
{
    sealed class Shown
    {
        public string English = "";
        public string Text = "";
    }

    static readonly ConditionalWeakTable<TextBlock, Shown> known = new();
    static readonly DependencyPropertyDescriptor text = DependencyPropertyDescriptor.FromProperty(TextBlock.TextProperty, typeof(TextBlock));

    public static void Start()
    {
        EventManager.RegisterClassHandler(typeof(TextBlock), FrameworkElement.LoadedEvent, new RoutedEventHandler(Loaded));
        EventManager.RegisterClassHandler(typeof(TextBlock), FrameworkElement.UnloadedEvent, new RoutedEventHandler(Unloaded));
    }

    static void Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is not TextBlock t) return;
        Show(t);
        // Watched only while on screen: a watched block is held until it is let go of here.
        text.RemoveValueChanged(t, Changed);
        text.AddValueChanged(t, Changed);
    }

    static void Unloaded(object sender, RoutedEventArgs e)
    {
        if (sender is TextBlock t) text.RemoveValueChanged(t, Changed);
    }

    static void Changed(object? sender, EventArgs e)
    {
        if (sender is TextBlock t) Show(t);
    }

    static void Show(TextBlock t)
    {
        // Bound text is data — a title, a name — and setting it would cut the binding.
        if (BindingOperations.IsDataBound(t, TextBlock.TextProperty)) return;
        var shown = known.GetOrCreateValue(t);
        var now = t.Text;
        // Set by code since it was last translated: that is the English now.
        if (now != shown.Text) shown.English = now;
        var next = I18n.Tr(shown.English);
        shown.Text = next;
        if (next != now) t.Text = next;
    }

    /// <summary>The screens open now in the language just picked; the rest turn to it as they load.</summary>
    public static void Again(DependencyObject root)
    {
        if (root is TextBlock t) Show(t);
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++) Again(VisualTreeHelper.GetChild(root, i));
    }
}
