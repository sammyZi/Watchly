using System.IO;
using System.Text.Json.Nodes;

namespace Watchly.Core;

/// <summary>
/// core Backup, for this PC: the profile's history, resume points, lists, searches, stats and notifications, and the
/// settings, as one file. The phone's envelope ("app": "watchly"); "from": "windows", since this app stores its
/// library its own way and the phone's file can't be read into it, nor this one into the phone.
/// </summary>
public static class Backup
{
    /// <summary>Per profile; the settings are the PC's.</summary>
    static readonly string[] ProfileKeys = { "torrent-history", "saved", "searches", "episodes", "genres", "watch-log", "inbox", "alerted-counts" };
    const string Settings = "settings";

    public static string Export()
    {
        var data = new JsonObject();
        foreach (var k in ProfileKeys)
            if (Store.LoadOwn<JsonNode?>(k, null) is { } v) data[k] = v;
        if (Store.Load<JsonNode?>(Settings, null) is { } s) data[Settings] = s;
        return new JsonObject
        {
            ["app"] = "watchly", ["from"] = "windows", ["version"] = 1, ["at"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), ["data"] = data,
        }.ToJsonString(Store.Json);
    }

    /// <summary>Replaces what is stored, key by key, into the profile in use. Only known keys: a picked file is untrusted. Returns how many came back.</summary>
    public static int Restore(string text)
    {
        JsonObject? file;
        try { file = JsonNode.Parse(text) as JsonObject; } catch { file = null; }
        if (file?["app"]?.GetValue<string>() != "watchly" || file["data"] is not JsonObject data) throw new InvalidDataException("That file is not a Watchly backup");
        if (file["from"]?.GetValue<string>() != "windows") throw new InvalidDataException("That backup is from Watchly on a phone or TV. Restore it there; on this PC, copy your library by signing in to Stremio or Nuvio.");
        var count = 0;
        foreach (var k in ProfileKeys)
        {
            if (data[k] is not { } v || v is JsonValue) continue;
            Store.SaveOwn(k, v.DeepClone());
            count++;
        }
        if (data[Settings] is JsonObject s)
        {
            Store.Save(Settings, s.DeepClone());
            count++;
        }
        if (count == 0) throw new InvalidDataException("That backup is empty");
        return count;
    }
}
