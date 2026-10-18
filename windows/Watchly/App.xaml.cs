using System.IO;
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
        // Neither is needed for the first frame, and on a cold disk after boot both compete
        // with the window for it: libVLC loads, and old builds are cleared, once it is up.
        window.ContentRendered += (_, _) =>
        {
            Views.PlayerView.Warm();
            _ = Task.Run(RemoveOldBuilds);
        };
        window.Show();
    }

    /// <summary>
    /// The single-file exe unpacks itself (≈240 MB, libVLC and all) to %TEMP%\.net\Watchly\&lt;build id&gt;\
    /// and nothing ever removes the folders of earlier builds. A folder whose build is still running
    /// is left whole: deleting it would strip the files that build hasn't loaded yet (a libVLC plugin
    /// it opens later). Renaming doesn't tell — Windows renames a folder with a running exe in it —
    /// but a loaded DLL can't be opened for writing, so that is the test. Nothing is written.
    /// </summary>
    static void RemoveOldBuilds()
    {
        try
        {
            var current = new DirectoryInfo(AppContext.BaseDirectory.TrimEnd('\\'));
            var root = current.Parent;
            if (root is null || !root.FullName.EndsWith(@"\.net\Watchly", StringComparison.OrdinalIgnoreCase)) return;
            foreach (var dir in root.EnumerateDirectories())
            {
                if (dir.FullName.Equals(current.FullName, StringComparison.OrdinalIgnoreCase)) continue;
                try
                {
                    if (InUse(dir)) continue;
                    dir.Delete(true);
                    Log.Info("app", $"removed an old build's files: {dir.Name}");
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
        catch (Exception e)
        {
            Log.Warn("app", $"old build cleanup: {e.Message}");
        }
    }

    static bool InUse(DirectoryInfo dir)
    {
        // WPF's renderer first: a running build always has it loaded, so a live folder is found
        // before this briefly holds (and so blocks) any file that build might open next.
        var files = dir.EnumerateFiles("*", SearchOption.AllDirectories)
            .OrderBy(f => f.Name.Equals("wpfgfx_cor3.dll", StringComparison.OrdinalIgnoreCase) ? 0 : 1);
        foreach (var file in files)
        {
            try
            {
                using var _ = file.Open(FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            }
            catch (IOException) { return true; }
            catch (UnauthorizedAccessException) { return true; }
        }
        return false;
    }

}
