using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Watchly.Core;

/// <summary>
/// The phone's AsyncStorage, as one JSON file per key under %APPDATA%\Watchly.
/// Small documents only — history, lists, settings — so plain synchronous file
/// IO is fast enough, and writes go through a temp file so a crash mid-write
/// never leaves half a document behind.
/// </summary>
public static class Store
{
    public static readonly string Root = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Watchly");

    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    static readonly object Gate = new();

    static string PathOf(string key)
    {
        var safe = string.Concat(key.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' or '.' ? c : '_'));
        return Path.Combine(Root, safe + ".json");
    }

    public static T Load<T>(string key, T fallback)
    {
        lock (Gate)
        {
            try
            {
                var path = PathOf(key);
                if (!File.Exists(path)) return fallback;
                return JsonSerializer.Deserialize<T>(File.ReadAllText(path), Json) ?? fallback;
            }
            catch
            {
                // Unreadable entry: treat as absent, same as the phone.
                return fallback;
            }
        }
    }

    public static void Save<T>(string key, T value)
    {
        lock (Gate)
        {
            Directory.CreateDirectory(Root);
            var path = PathOf(key);
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(value, Json));
            File.Move(tmp, path, true);
        }
    }

    public static void Remove(string key)
    {
        lock (Gate)
        {
            try { File.Delete(PathOf(key)); } catch { }
        }
    }

    /// <summary>Every stored key starting with <paramref name="prefix"/> (file-name form).</summary>
    public static void RemovePrefix(string prefix)
    {
        lock (Gate)
        {
            if (!Directory.Exists(Root)) return;
            var safe = Path.GetFileNameWithoutExtension(PathOf(prefix));
            foreach (var file in Directory.GetFiles(Root, safe + "*.json"))
            {
                try { File.Delete(file); } catch { }
            }
        }
    }

    public static T Clone<T>(T value) =>
        JsonSerializer.Deserialize<T>(JsonSerializer.Serialize(value, Json), Json)!;

    public static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
