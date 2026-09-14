using System.Windows;
using System.Windows.Threading;
using Watchly.Core;
using Watchly.Engine;

namespace Watchly;

public partial class App : Application
{
    [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
    static extern bool AttachConsole(int dwProcessId);

    [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
    static extern bool AllocConsole();

    const int ATTACH_PARENT_PROCESS = -1;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        if (!AttachConsole(ATTACH_PARENT_PROCESS))
        {
            if (e.Args.Contains("--console") || e.Args.Contains("-c"))
                AllocConsole();
        }

        if (e.Args.Contains("--smoke"))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            Shutdown(Smoke.Run(this));
            return;
        }

        Log.Info("app", "Watchly starting up...");
        LibVLCSharp.Shared.Core.Initialize();
        Theme.Apply(AppSettings.Load().ThemeMode);

        DispatcherUnhandledException += (_, args) =>
        {
            // One bad response must not take the whole app down; say what happened instead.
            Log.Error("app", "Unhandled exception", args.Exception);
            (MainWindow as MainWindow)?.Toast(args.Exception.GetBaseException().Message, true);
            args.Handled = true;
        };
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            Log.Error("app", "Unobserved task exception", args.Exception);
            args.SetObserved();
        };

        var window = new MainWindow(e.Args.FirstOrDefault(TitleText.IsTorrentSource));
        MainWindow = window;
        window.Show();
    }

}
