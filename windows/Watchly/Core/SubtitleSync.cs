using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>
/// core SubtitleSync.kt, "Sync to this line": the viewer clicks as they hear a line begin, and the subtitles
/// are shifted so the nearest line in the file begins then. Works on a subtitle file — SRT, WebVTT or ASS.
/// </summary>
public static class SubtitleSync
{
    /// <summary>Between a line beginning and a finger reaching the button.</summary>
    const long ReactionMs = 250;

    /// <summary>Further than this from any line and the click was for something else.</summary>
    const long WindowMs = 20_000;

    /// <param name="DelayMs">The delay to set: positive shows the subtitles later.</param>
    /// <param name="DistanceMs">How far the nearest line was, for choosing between files.</param>
    /// <param name="LineMs">The line it was matched to, so the next click does not move it again.</param>
    public sealed record Match(long DelayMs, long DistanceMs, long LineMs);

    // 00:01:02,500 --> …  (SRT, WebVTT; WebVTT may leave out the hours)
    static readonly Regex Arrow = new(@"(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->");
    // Dialogue: 0,0:01:02.50,0:01:05.00,Default,…  (ASS / SSA)
    static readonly Regex Ass = new(@"^\s*Dialogue:\s*[^,]*,\s*(\d+):(\d{2}):(\d{2})[.,](\d{1,2})");

    /// <summary>The start of every line in the file, in milliseconds.</summary>
    public static List<long> Starts(string text)
    {
        var output = new List<long>();
        foreach (var line in text.Split('\n'))
        {
            var ass = Ass.Match(line);
            if (ass.Success)
            {
                output.Add(((long.Parse(ass.Groups[1].Value) * 60 + long.Parse(ass.Groups[2].Value)) * 60 + long.Parse(ass.Groups[3].Value)) * 1000
                    + long.Parse(ass.Groups[4].Value.PadRight(2, '0')[..2]) * 10);
                continue;
            }
            var m = Arrow.Match(line);
            if (!m.Success) continue;
            var h = m.Groups[1].Success ? long.Parse(m.Groups[1].Value) : 0;
            output.Add(((h * 60 + long.Parse(m.Groups[2].Value)) * 60 + long.Parse(m.Groups[3].Value)) * 1000 + long.Parse(m.Groups[4].Value.PadRight(3, '0')[..3]));
        }
        return output;
    }

    /// <summary>
    /// The delay that puts the line nearest <paramref name="pressMs"/> at the click. <paramref name="anchored"/>, the line the
    /// last click moved, is never taken again, or a quick run of clicks drifts further off each time. Null when none is near.
    /// </summary>
    public static Match? Find(List<long> starts, long pressMs, long delayMs, long? anchored = null)
    {
        var heard = pressMs - ReactionMs;
        var candidates = starts.Where(s => s != anchored).ToList();
        if (candidates.Count == 0) return null;
        var nearest = candidates.MinBy(s => Math.Abs(s + delayMs - heard));
        var distance = Math.Abs(nearest + delayMs - heard);
        return distance > WindowMs ? null : new Match(heard - nearest, distance, nearest);
    }
}
