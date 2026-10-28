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

    /// <summary>The installed app has no console: every line also goes here, started afresh each launch.</summary>
    public static readonly string FilePath = Path.Combine(Path.GetTempPath(), "watchly.log");

    static readonly StreamWriter? File = Open();

    static StreamWriter? Open()
    {
        try { return new StreamWriter(new FileStream(FilePath, FileMode.Create, FileAccess.Write, FileShare.ReadWrite)) { AutoFlush = true }; }
        catch { return null; } // a second Watchly holds it
    }

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
