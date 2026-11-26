using System.Diagnostics;
using System.IO;

namespace Watchly.Core;

public static class Log
{
    public static void Info(string tag, string message) =>
        Write(tag, message, ConsoleColor.Gray);

    public static void Torrent(string message) =>
        Write("torrent", message, ConsoleColor.Cyan);

    public static void Stream(string message) =>
        Write("stream", message, ConsoleColor.Green);

    public static void Player(string message) =>
        Write("player", message, ConsoleColor.Magenta);

    public static void Warn(string tag, string message) =>
        Write(tag, message, ConsoleColor.Yellow);

    public static void Error(string tag, string message, Exception? ex = null) =>
        Write(tag, $"{message}{(ex != null ? $": {ex.GetBaseException().Message}\n{ex.StackTrace}" : "")}", ConsoleColor.Red);

    static readonly object Gate = new();

    /// <summary>Every run's log, kept: the last <see cref="Keep"/> of them, newest by name.</summary>
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Watchly", "logs");
    const int Keep = 10;

    /// <summary>This run's: watchly-&lt;start&gt;-&lt;pid&gt;.log, its own file, so a restart after a crash wipes nothing.</summary>
    public static readonly string FilePath = Path.Combine(Dir, $"watchly-{DateTime.Now:yyyyMMdd-HHmmss}-{Environment.ProcessId}.log");

    /// <summary>The last line of a run that closed; a log without it ended in a crash.</summary>
    const string Closed = "[app] closed";

    static readonly StreamWriter? File = Open();

    static StreamWriter? Open()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            var old = new DirectoryInfo(Dir).GetFiles("watchly-*.log").OrderByDescending(f => f.Name).ToList();
            foreach (var f in old.Skip(Keep - 1)) try { f.Delete(); } catch { }
            var writer = new StreamWriter(new FileStream(FilePath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite)) { AutoFlush = true };
            if (old.FirstOrDefault() is { } last) writer.WriteLine(LastRun(last));
            return writer;
        }
        catch { return null; }
    }

    /// <summary>How the run before ended: closed, or not — then the crash dump Windows wrote for it, if any.</summary>
    static string LastRun(FileInfo last)
    {
        string? tail = null;
        try { using var r = new StreamReader(new FileStream(last.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)); tail = r.ReadToEnd(); } catch { }
        if (tail?.TrimEnd().EndsWith(Closed) == true) return $"{DateTime.Now:HH:mm:ss.fff} [app] last run ({last.Name}) closed normally";
        var dumps = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CrashDumps");
        var dump = Directory.Exists(dumps)
            ? new DirectoryInfo(dumps).GetFiles("Watchly.exe.*.dmp").Where(d => d.LastWriteTime >= last.CreationTime).OrderByDescending(d => d.LastWriteTime).FirstOrDefault()
            : null;
        return $"{DateTime.Now:HH:mm:ss.fff} [app] last run ({last.Name}) did not close: it crashed or was killed" + (dump is null ? "" : $"; crash dump {dump.FullName}");
    }

    /// <summary>Marks this run's log as closed normally.</summary>
    public static void Close() => Info("app", "closed");

    static void Write(string tag, string message, ConsoleColor color)
    {
        var line = $"{DateTime.Now:HH:mm:ss.fff} [{tag}] {message}";
        Debug.WriteLine(line);
        lock (Gate)
        {
            try { File?.WriteLine(line); } catch { }
            try
            {
                var prev = Console.ForegroundColor;
                Console.ForegroundColor = color;
                Console.WriteLine(line);
                Console.ForegroundColor = prev;
            }
            catch { }
        }
    }
}
