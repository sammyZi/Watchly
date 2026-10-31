namespace Watchly.Core;

/// <summary>
/// A kids profile sees family titles only — the phone's and TV's Kids.kt: those in Cinemeta's Family
/// catalogues, or tagged Family themselves. Cinemeta cuts a title's own genres to three (Toy Story's
/// has no Family), but its Family catalogue goes by IMDb's full list. Genres, not age ratings —
/// Cinemeta has none. Torrent search and magnets are the shell's to switch off.
/// </summary>
public static class KidsMode
{
    const string Catalog = "https://cinemeta-catalogs.strem.io/top/catalog";

    public static bool On { get; private set; }

    /// <summary>The family list arrived. Raised on a thread-pool thread.</summary>
    public static event Action? Changed;

    /// <summary>Empty until fetched: nothing passes but what is tagged Family, which errs the safe way.</summary>
    static HashSet<string> allowed = new();
    static int loading;

    sealed record Cached(long At, List<string> Ids);

    /// <summary>On every profile switch, with the profile's own flag.</summary>
    public static void Use(Profile profile)
    {
        On = profile.Kids;
        if (On && allowed.Count == 0) _ = Load();
    }

    public static bool Allows(Title title) => !On || allowed.Contains(title.Imdb) || title.Genres.Contains("Family");

    public static List<T> Only<T>(IEnumerable<T> items, Func<T, Title> titleOf) => On ? items.Where(i => Allows(titleOf(i))).ToList() : items.ToList();

    public static List<Title> Only(IEnumerable<Title> titles) => Only(titles, t => t);

    /// <summary>1000 films and 500 series, fifty to a page, kept a day; a page that fails just leaves its titles out.</summary>
    static async Task Load()
    {
        if (Interlocked.Exchange(ref loading, 1) == 1) return;
        try
        {
            if (Store.Load<Cached?>("family-ids", null) is { } kept && Store.Now - kept.At < 86_400_000 && kept.Ids.Count > 0)
            {
                allowed = kept.Ids.ToHashSet();
                Changed?.Invoke();
                return;
            }
            var pages = Enumerable.Range(0, 20).Select(i => ("movie", i * 50)).Concat(Enumerable.Range(0, 10).Select(i => ("series", i * 50)));
            var found = await Task.WhenAll(pages.Select(async p =>
            {
                try
                {
                    var metas = (await Net.Json($"{Catalog}/{p.Item1}/top/genre=Family&skip={p.Item2}.json", default, true))?["metas"]?.AsArray();
                    return metas?.Select(m => (string?)m?["imdb_id"] ?? (string?)m?["id"]).Where(id => id is not null).Select(id => id!).ToList() ?? new();
                }
                catch
                {
                    return new List<string>();
                }
            }));
            var ids = found.SelectMany(l => l).ToHashSet();
            if (ids.Count == 0) return;
            allowed = ids;
            Store.Save("family-ids", new Cached(Store.Now, ids.ToList()));
            Changed?.Invoke();
        }
        finally
        {
            Interlocked.Exchange(ref loading, 0);
        }
    }
}
