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

        // A magnet, a .torrent, or an addon site's Install link (the installer registers stremio://).
        var incoming = e.Args.FirstOrDefault(a => TitleText.IsTorrentSource(a) || a.StartsWith("stremio://", StringComparison.OrdinalIgnoreCase));
        // A Watchly already open takes it: a second one would fight it for the torrent ports.
        single = new Mutex(true, Pipe, out var first);
        if (!first && incoming is not null && Forward(incoming))
        {
            Shutdown();
            return;
        }

        var window = new MainWindow(incoming);
        MainWindow = window;
        if (first) Listen(window);
        // Neither is needed for the first frame, and on a cold disk after boot both compete
        // with the window for it: libVLC loads, and old builds are cleared, once it is up.
        window.ContentRendered += (_, _) =>
        {
            Views.PlayerView.Warm();
            _ = Task.Run(RemoveOldBuilds);
        };
        window.Show();
    }

    /// <summary>One per Windows account: the pipe's name, and the mutex that says a Watchly is open.</summary>
    static readonly string Pipe = $"Watchly-{Environment.UserName}";
    /// <summary>Held for the app's life; let go, a second Watchly would think it was the first.</summary>
    static Mutex? single;

    /// <summary>Hands a link to the Watchly already open; false when none answered, so this one opens it.</summary>
    static bool Forward(string link)
    {
        try
        {
            using var pipe = new System.IO.Pipes.NamedPipeClientStream(".", Pipe, System.IO.Pipes.PipeDirection.Out);
            pipe.Connect(1500);
            using var writer = new StreamWriter(pipe);
            writer.Write(link);
            return true;
        }
        catch (Exception e)
        {
            Log.Warn("app", $"no open Watchly took the link: {e.Message}");
            return false;
        }
    }

    /// <summary>Links from a second Watchly, one at a time, for as long as this one is open.</summary>
    static void Listen(MainWindow window) => _ = Task.Run(async () =>
    {
        while (true)
        {
            try
            {
                await using var server = new System.IO.Pipes.NamedPipeServerStream(Pipe, System.IO.Pipes.PipeDirection.In, 1,
                    System.IO.Pipes.PipeTransmissionMode.Byte, System.IO.Pipes.PipeOptions.Asynchronous);
                await server.WaitForConnectionAsync();
                using var reader = new StreamReader(server);
                var link = (await reader.ReadToEndAsync()).Trim();
                // Only what the command line could have brought: nothing else is acted on.
                if (link.Length > 0 && link.Length < 8192 && (TitleText.IsTorrentSource(link) || link.StartsWith("stremio://", StringComparison.OrdinalIgnoreCase)))
                    _ = window.Dispatcher.BeginInvoke(() => window.Incoming(link));
            }
            catch (Exception e)
            {
                Log.Warn("app", $"link pipe: {e.Message}");
                await Task.Delay(1000);
            }
        }
    });

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
