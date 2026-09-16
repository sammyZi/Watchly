using System.Globalization;

namespace Watchly.Core;

/// <summary>
/// What a home shelf shows today. Cinemeta's genre lists barely move, so every shelf showed
/// the same titles day after day, and a film tagged Action, Adventure and Sci-Fi sat on all
/// three. Each shelf now draws from a deeper pool, scores it on popularity rank, rating,
/// recency and the genres you watch, and shakes that with a jitter seeded by the date —
/// the same all day, different tomorrow. A title in several genres goes on one of them a day.
/// </summary>
public static class Recommend
{
    public const int ShelfSize = 30;

    /// <param name="pool">The catalogue, most popular first.</param>
    /// <param name="genre">The shelf's genre, or null for a mixed shelf (Trending films).</param>
    /// <param name="taste">Genre → 0..1, how much of what you watch is that genre.</param>
    /// <param name="onePerGenre">A plain genre shelf: keep only titles this genre owns today.</param>
    public static List<Title> ForToday(IReadOnlyList<Title> pool, string? genre, IReadOnlyDictionary<string, double> taste,
        ISet<string?> watched, bool onePerGenre, DateTime? now = null)
    {
        var when = now ?? DateTime.Now;
        var day = DateOnly.FromDateTime(when).DayNumber;
        return pool
            .Select((title, rank) => (Title: title, Rank: rank))
            .Where(x => !watched.Contains(x.Title.Imdb))
            .Where(x => !onePerGenre || genre is null || Owner(x.Title, day) is not { } owner || owner == genre)
            .Select(x => (x.Title, Score: Score(x.Title, x.Rank, genre, taste, day, when.Year)))
            .OrderByDescending(x => x.Score)
            .Take(ShelfSize)
            .Select(x => x.Title)
            .ToList();
    }

    static double Score(Title t, int rank, string? genre, IReadOnlyDictionary<string, double> taste, int day, int year)
    {
        var popularity = 1 / Math.Sqrt(1 + rank / 8.0);
        var rating = double.TryParse(t.Rating, NumberStyles.Float, CultureInfo.InvariantCulture, out var r) ? Math.Clamp((r - 5) / 4, 0, 1) : 0.4;
        var age = t.Year is { Length: >= 4 } && int.TryParse(t.Year[..4], out var y) ? year - y : 10;
        var fresh = age <= 1 ? 0.25 : age <= 4 ? 0.1 : age > 25 ? -0.1 : 0;
        var liked = t.Genres.Count == 0 ? 0 : t.Genres.Sum(g => taste.GetValueOrDefault(g)) / t.Genres.Count;
        var basis = 0.45 * popularity + 0.3 * rating + fresh + 0.35 * liked;
        return basis * (0.5 + Unit(day, genre ?? "", t.Imdb)); // ×0.5–1.5: good titles stay likely, the shelf still turns over
    }

    /// <summary>The one home genre a title belongs to today.</summary>
    static string? Owner(Title t, int day) =>
        t.Genres.Where(Meta.Genres.Contains).OrderByDescending(g => Unit(day, g, t.Imdb)).FirstOrDefault();

    /// <summary>A stable 0..1 from its inputs (string.GetHashCode changes every launch).</summary>
    static double Unit(int day, string a, string b)
    {
        var h = 1469598103934665603UL;
        void Mix(string s)
        {
            foreach (var c in s) { h ^= c; h *= 1099511628211UL; }
            h ^= 0xff;
            h *= 1099511628211UL;
        }
        Mix(day.ToString(CultureInfo.InvariantCulture));
        Mix(a);
        Mix(b);
        h ^= h >> 33;
        h *= 0xff51afd7ed558ccdUL;
        h ^= h >> 33;
        return (h >> 11) / (double)(1UL << 53);
    }
}
