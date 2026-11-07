using System.IO;
using System.Text.Json.Nodes;

namespace Watchly.Core;

/// <summary>The words in tv/core's legal.json, the file the phone and TV read.</summary>
public static class Legal
{
    public sealed record Section(string Heading, string Text);

    static readonly JsonNode Root = Read();

    static JsonNode Read()
    {
        using var stream = typeof(Legal).Assembly.GetManifestResourceStream("legal.json")!;
        using var reader = new StreamReader(stream);
        return JsonNode.Parse(reader.ReadToEnd())!;
    }

    static List<Section> Sections(string key) =>
        Root[key]!.AsArray().Select(s => new Section((string)s!["heading"]!, (string)s["text"]!)).ToList();

    /// <summary>What was accepted is kept by this: a new date asks everyone again.</summary>
    public static string Updated => (string)Root["updated"]!;
    public static string Summary => (string)Root["summary"]!;
    public static string Accept => (string)Root["accept"]!;
    public static List<Section> Notice => Sections("notice");
    public static List<Section> Privacy => Sections("privacy");
    public static List<Section> Terms => Sections("terms");
}
