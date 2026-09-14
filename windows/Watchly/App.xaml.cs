using System.Windows;
using System.Windows.Threading;
using Watchly.Core;
using Watchly.Engine;

namespace Watchly;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args.Contains("--smoke"))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            Shutdown(Smoke.Run(this));
            return;
        }
        LibVLCSharp.Shared.Core.Initialize();
        Theme.Apply(AppSettings.Load().ThemeMode);

        DispatcherUnhandledException += (_, args) =>
        {
            // One bad response must not take the whole app down; say what happened instead.
            System.Diagnostics.Debug.WriteLine($"[app] unhandled: {args.Exception}");
            (MainWindow as MainWindow)?.Toast(args.Exception.Message, true);
            args.Handled = true;
        };
        TaskScheduler.UnobservedTaskException += (_, args) => args.SetObserved();

        var window = new MainWindow(e.Args.FirstOrDefault(TitleText.IsTorrentSource));
        MainWindow = window;
        window.Show();
    }

}
