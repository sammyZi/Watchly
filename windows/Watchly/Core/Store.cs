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

    // ── Per profile ─────────────────────────────────────────────────────────
    // What a viewer accumulates — history, lists, searches — is namespaced by profile, as the
    // phone's and TV's Kv is; the first profile keeps the bare keys, so a library from before
    // profiles is still there. The guest writes to memory only and is forgotten on exit.

    static string profile = "";
    static bool guest;
    static readonly Dictionary<string, string> Memory = new();

    public static void UseProfile(string id, bool isGuest)
    {
        lock (Gate)
        {
            profile = id;
            guest = isGuest;
            if (isGuest) Memory.Clear();
        }
    }

    static string Own(string key) => profile.Length > 0 ? $"{key}@{profile}" : key;

    public static T LoadOwn<T>(string key, T fallback)
    {
        if (!guest) return Load(Own(key), fallback);
        lock (Gate)
        {
            try { return Memory.TryGetValue(Own(key), out var json) ? JsonSerializer.Deserialize<T>(json, Json) ?? fallback : fallback; }
            catch { return fallback; }
        }
    }

    public static void SaveOwn<T>(string key, T value)
    {
        if (!guest)
        {
            Save(Own(key), value);
            return;
        }
        lock (Gate) Memory[Own(key)] = JsonSerializer.Serialize(value, Json);
    }

    public static void RemoveOwn(string key)
    {
        if (!guest)
        {
            Remove(Own(key));
            return;
        }
        lock (Gate) Memory.Remove(Own(key));
    }

    /// <summary>Everything a deleted profile kept ("key@id", which is "key_id" on disk).</summary>
    public static void WipeProfile(string id)
    {
        if (id.Length == 0) return;
        lock (Gate)
        {
            if (!Directory.Exists(Root)) return;
            foreach (var file in Directory.GetFiles(Root, $"*_{id}.json"))
            {
                try { File.Delete(file); } catch { }
            }
        }
    }

    public static T Clone<T>(T value) =>
        JsonSerializer.Deserialize<T>(JsonSerializer.Serialize(value, Json), Json)!;

    public static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
