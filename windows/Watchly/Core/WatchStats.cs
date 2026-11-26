using System.Globalization;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>
/// core Stats.kt's WatchLog: seconds actually watched, per day and title, for Your stats. History keeps only where
/// each title was left, so the player credits what it plays here as it saves its spot. Per profile.
/// <see cref="WatchLogData.Base"/> is the rough total from before the log began.
/// </summary>
public sealed record WatchLogData
{
    public double Base { get; init; }
    /// <summary>yyyy-MM-dd → imdb (or "name:…") → seconds.</summary>
    public SortedDictionary<string, Dictionary<string, double>> Days { get; init; } = new();
    public Dictionary<string, WatchLog.Seen> Titles { get; init; } = new();
}

public static class WatchLog
{
    const string Key = "watch-log";
    /// <summary>A year and a bit: enough for "your year", and the log stays small.</summary>
    const int KeepDays = 400;

    public sealed record Seen(string Name, string Kind);

    /// <summary>The series, not the episode: "The Mentalist · S04E09" is The Mentalist.</summary>
    public static string ShowName(string label) => Regex.Replace(label, @"\s+·\s+S\d+:?E\d+.*$", "");

    public static WatchLogData Read() => Store.LoadOwn(Key, new WatchLogData());

    static readonly object gate = new();

    /// <summary>Credit <paramref name="seconds"/> of <paramref name="item"/> to today.</summary>
    public static void Add(HistoryItem item, double seconds, IEnumerable<HistoryItem> history)
    {
        if (seconds <= 0) return;
        lock (gate)
        {
            var log = Read();
            // The first: what History already knew is the best guess at what came before.
            if (log.Days.Count == 0 && log.Base == 0) log = log with { Base = history.Sum(h => h.Positions?.Values.Sum() ?? 0) };
            var name = ShowName(item.Title);
            var id = item.Imdb ?? "name:" + name;
            var today = DateTime.Now.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            if (!log.Days.TryGetValue(today, out var day)) log.Days[today] = day = new();
            day[id] = day.GetValueOrDefault(id) + seconds;
            while (log.Days.Count > KeepDays) log.Days.Remove(log.Days.Keys.First());
            log.Titles[id] = new Seen(name, item.Kind ?? "movie");
            var kept = log.Days.Values.SelectMany(d => d.Keys).ToHashSet();
            foreach (var gone in log.Titles.Keys.Where(k => !kept.Contains(k)).ToList()) log.Titles.Remove(gone);
            Store.SaveOwn(Key, log);
        }
    }
}

/// <summary>
/// core AccountWatches: what Stremio, Nuvio and the trackers say was watched, for Your stats. They keep what was
/// watched, not for how long: each film counts <see cref="FilmSeconds"/> and each episode <see cref="EpisodeSeconds"/>,
/// on the day the account says the title was finished, or to all time only when it doesn't say. A title this PC timed
/// or played itself is left as logged, so nothing counts twice. Names are the accounts' where they have one, else the IMDb id.
/// </summary>
public static class AccountWatches
{
    /// <summary>Calibration knobs: a typical film, a typical episode.</summary>
    public const double FilmSeconds = 110 * 60, EpisodeSeconds = 42 * 60;

    /// <summary><paramref name="log"/> with the accounts' watches added, and how many seconds of it are estimated.</summary>
    public static (WatchLogData Merged, double Estimated) Merge(WatchLogData log, StremioLibrary accounts, ISet<string> played)
    {
        var names = accounts.Seen.GroupBy(s => s.Imdb).ToDictionary(g => g.Key, g => g.First().Title);
        var days = new SortedDictionary<string, Dictionary<string, double>>(log.Days.ToDictionary(d => d.Key, d => new Dictionary<string, double>(d.Value)));
        var titles = new Dictionary<string, WatchLog.Seen>(log.Titles);
        var @base = log.Base;
        var added = 0.0;
        void Credit(string id, string kind, double seconds)
        {
            if (seconds <= 0 || log.Titles.ContainsKey(id) || played.Contains(id)) return;
            titles[id] = new WatchLog.Seen(names.GetValueOrDefault(id) is { Length: > 0 } n ? n : id, kind);
            added += seconds;
            var at = accounts.FinishedAt.GetValueOrDefault(id);
            if (at <= 0) { @base += seconds; return; }
            var day = DateTimeOffset.FromUnixTimeMilliseconds(at).LocalDateTime.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            if (!days.TryGetValue(day, out var d)) days[day] = d = new();
            d[id] = d.GetValueOrDefault(id) + seconds;
        }
        foreach (var id in accounts.Films) Credit(id, "movie", FilmSeconds);
        foreach (var (id, eps) in accounts.Episodes) Credit(id, "tv", eps.Count * EpisodeSeconds);
        return (log with { Base = @base, Days = days, Titles = titles }, added);
    }

    /// <summary>
    /// The page's numbers: hours, months, titles and genres from everything; streaks, days watched and the busiest
    /// weekday from what this PC timed, as the accounts don't say which days the rest was watched on.
    /// </summary>
    public static WatchStats Stats(WatchLogData log, WatchLogData merged, DateTime? at = null)
    {
        var here = WatchStats.Of(log, at);
        return WatchStats.Of(merged, at) with { Streak = here.Streak, LongestStreak = here.LongestStreak, ActiveDays = here.ActiveDays, BusiestWeekday = here.BusiestWeekday };
    }
}

/// <summary>core WatchStats: what Your stats shows, worked out from the log.</summary>
public sealed record WatchStats(
    double AllTime, double ThisYear, double ThisMonth, double[] ByMonth,
    List<(string Name, string Kind, double Seconds, string Id)> Top,
    int ActiveDays, int Streak, int LongestStreak, string? BusiestWeekday, int Shows, int Films)
{
    static readonly string[] Weekdays = { "Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays" };

    public static WatchStats Of(WatchLogData log, DateTime? at = null)
    {
        var now = at ?? DateTime.Now;
        var byMonth = new double[12];
        var perTitle = new Dictionary<string, double>();
        var weekday = new double[7];
        var all = log.Base;
        var active = new SortedSet<int>();
        foreach (var (d, titles) in log.Days)
        {
            var total = titles.Values.Sum();
            if (total <= 0 || !DateTime.TryParseExact(d, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)) continue;
            all += total;
            active.Add(date.DayNumber());
            weekday[(int)date.DayOfWeek] += total;
            if (date.Year != now.Year) continue;
            byMonth[date.Month - 1] += total;
            foreach (var (id, s) in titles) perTitle[id] = perTitle.GetValueOrDefault(id) + s;
        }
        var seen = perTitle.Keys.Where(log.Titles.ContainsKey).Select(k => log.Titles[k]).ToList();
        // The run of days from today back; one not yet watched today does not break it.
        var today = now.Date.DayNumber();
        var streak = 0;
        for (var d = active.Contains(today) ? today : today - 1; active.Contains(d); d--) streak++;
        int longest = 0, run = 0, prev = int.MinValue;
        foreach (var x in active)
        {
            run = x == prev + 1 ? run + 1 : 1;
            longest = Math.Max(longest, run);
            prev = x;
        }
        var busiest = Array.IndexOf(weekday, weekday.Max());
        return new WatchStats(
            all, byMonth.Sum(), byMonth[now.Month - 1], byMonth,
            perTitle.OrderByDescending(kv => kv.Value).Take(5).Where(kv => log.Titles.ContainsKey(kv.Key))
                .Select(kv => (log.Titles[kv.Key].Name, log.Titles[kv.Key].Kind, kv.Value, kv.Key)).ToList(),
            active.Count, streak, longest, weekday.Max() > 0 ? Weekdays[busiest] : null,
            seen.Count(s => s.Kind == "tv"), seen.Count(s => s.Kind != "tv"));
    }

    /// <summary>This year's seconds by genre, from the twelve most-watched titles; a title counts in each of its first three.</summary>
    public static async Task<List<(string Genre, double Seconds)>> Genres(WatchLogData log, DateTime? at = null)
    {
        var year = (at ?? DateTime.Now).Year.ToString(CultureInfo.InvariantCulture);
        var seconds = new Dictionary<string, double>();
        foreach (var (d, titles) in log.Days)
            if (d.StartsWith(year))
                foreach (var (id, s) in titles) seconds[id] = seconds.GetValueOrDefault(id) + s;
        var byGenre = new Dictionary<string, double>();
        foreach (var (id, s) in seconds.Where(kv => kv.Key.StartsWith("tt")).OrderByDescending(kv => kv.Value).Take(12))
        {
            var kind = log.Titles.GetValueOrDefault(id)?.Kind ?? "movie";
            List<string> genres;
            try { genres = (await Meta.LoadTitle(id, kind))?.Genres ?? new(); } catch { genres = new(); }
            foreach (var g in genres.Take(3)) byGenre[g] = byGenre.GetValueOrDefault(g) + s;
        }
        return byGenre.OrderByDescending(kv => kv.Value).Take(5).Select(kv => (kv.Key, kv.Value)).ToList();
    }

    /// <summary>"132h 40m", "3h 05m", "42m".</summary>
    public static string Hours(double seconds)
    {
        var m = (long)(seconds / 60);
        return m >= 60 ? $"{m / 60}h {m % 60:00}m" : $"{m}m";
    }
}

static class DayNumbers
{
    public static int DayNumber(this DateTime d) => DateOnly.FromDateTime(d).DayNumber;
}
