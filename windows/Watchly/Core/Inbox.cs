using System.Globalization;

namespace Watchly.Core;

/// <summary>
/// core Inbox: what Watchly told the viewer — new episodes, an update — kept per profile, newest first, for the
/// Notifications tab. <see cref="Note.Imdb"/> is the title a click opens.
/// </summary>
public static class Inbox
{
    public sealed record Note(string Id, string Title, string Text, long At, string? Imdb = null, string? Kind = null);

    const string Key = "inbox";
    const string ReadKey = "inbox-read";
    /// <summary>Older ones go. Calibration knob.</summary>
    const int Max = 60;

    public static event Action? Changed;

    public static List<Note> Items => Store.LoadOwn(Key, new List<Note>());

    /// <summary>Everything up to this was seen on the Notifications tab.</summary>
    public static long ReadAt => Store.LoadOwn(ReadKey, 0L);

    public static int Unseen => Items.Count(n => n.At > ReadAt) + AniList.Unread;

    /// <summary>A note already kept (by its id) isn't added again.</summary>
    public static void Add(Note note)
    {
        var now = Items;
        if (now.Any(n => n.Id == note.Id)) return;
        Store.SaveOwn(Key, new[] { note }.Concat(now).Take(Max).ToList());
        Changed?.Invoke();
    }

    public static void MarkRead()
    {
        Store.SaveOwn(ReadKey, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        Changed?.Invoke();
    }

    public static void Clear()
    {
        Store.SaveOwn(Key, new List<Note>());
        Changed?.Invoke();
    }
}

/// <summary>
/// core NewEpisodes.alerts, for the profile in use: its followed series (watched or saved) with episodes out since it
/// was last told. A show met for the first time only sets its baseline, so turning alerts on never floods.
/// </summary>
// ponytail: only the profile in use is checked, while the app runs; the phone's background check covers every profile.
public static class NewEpisodes
{
    const string Alerted = "alerted-counts";
    /// <summary>Each show is one metadata request on a cold cache.</summary>
    const int MaxShows = 25;

    static bool Aired(Episode e, DateTime now) =>
        e.Season > 0 && e.Released is { Length: >= 10 } r && DateTime.TryParseExact(r[..10], "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at) && at <= now;

    public static async Task Check(IEnumerable<HistoryItem> history, IEnumerable<SavedTitle> saved)
    {
        var now = DateTime.UtcNow;
        var shows = history.Where(h => h.Kind == "tv").OrderByDescending(h => h.At).Select(h => h.Imdb)
            .Concat(saved.Where(s => s.Kind == "tv").Select(s => (string?)s.Imdb))
            .OfType<string>().Where(i => i.StartsWith("tt")).Distinct().Take(MaxShows).ToList();
        if (shows.Count == 0) return;
        var known = Store.LoadOwn(Alerted, new Dictionary<string, int>());
        var next = new Dictionary<string, int>(known);
        foreach (var imdb in shows)
        {
            Title? t;
            try { t = await Meta.LoadTitle(imdb, "tv"); } catch { continue; }
            if (t is null || t.Episodes.Count == 0) continue;
            var aired = t.Episodes.Where(e => Aired(e, now)).ToList();
            next[imdb] = aired.Count;
            if (!known.TryGetValue(imdb, out var seen) || aired.Count <= seen) continue;
            var latest = aired.OrderBy(e => e.Released![..10]).ThenBy(e => e.Season).ThenBy(e => e.Number).Last();
            var count = aired.Count - seen;
            var name = latest.Name is { Length: > 0 } n && !n.StartsWith("Episode") ? $" · {n}" : "";
            Inbox.Add(new Inbox.Note($"ep:{imdb}:{latest.Season}:{latest.Number}", t.Name,
                $"New episode: {(count > 1 ? $"{count} new episodes, the latest is " : "")}S{latest.Season}:E{latest.Number}{name}",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), imdb, "tv"));
        }
        Store.SaveOwn(Alerted, next);
    }
}
