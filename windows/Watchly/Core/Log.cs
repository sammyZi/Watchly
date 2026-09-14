using System.Diagnostics;

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

    static void Write(string tag, string message, ConsoleColor color)
    {
        var line = $"{DateTime.Now:HH:mm:ss.fff} [{tag}] {message}";
        Debug.WriteLine(line);
        lock (Gate)
        {
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
