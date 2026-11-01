using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using Watchly.Core;
using Watchly.Views;

namespace Watchly;

/// <summary>
/// `Watchly.exe --smoke`: the phone's lib tests (src/lib/*.test.ts) run against
/// the C# ports, then every screen is built and laid out offscreen — XAML and
/// template mistakes only surface at runtime. No window is shown and no torrent
/// is opened. Exit code 0 means everything passed; failures go to smoke.log.
/// </summary>
static class Smoke
{
    static readonly List<string> Failures = new();

    static void Eq<T>(T actual, T expected, string what)
    {
        if (!EqualityComparer<T>.Default.Equals(actual, expected)) Failures.Add($"{what}: expected «{expected}», got «{actual}»");
    }

    static void Ok(bool condition, string what)
    {
        if (!condition) Failures.Add(what);
    }

    static Hit H(string id, string name = "n", int seeders = 0, int leechers = 0, long size = 0, string scope = "episode", bool perFile = false, string uri = "m") =>
        new() { Id = id, Name = name, Uri = uri, Seeders = seeders, Leechers = leechers, Size = size, Scope = scope, PerFileSize = perFile };

    static TorrentFileInfo F(int index, string name, long size, bool video = true) => new() { Index = index, Name = name, Path = name, Size = size, IsVideo = video };

    public static int Run(Application app)
    {
        var log = Path.Combine(Path.GetTempPath(), "watchly-smoke.log");
        try
        {
            try { _ = Engine.TorrentEngine.Instance; } catch (Exception ex) { Failures.Add($"torrent engine: {ex}"); }
            Logic();
            Screens();
        }
        catch (Exception e)
        {
            Failures.Add($"crashed: {e}");
        }
        Engine.Downloads.Instance.Live.Clear();
        File.WriteAllText(log, Failures.Count == 0 ? "ok\n" : string.Join("\n", Failures) + "\n");
        return Failures.Count == 0 ? 0 : 1;
    }

    static void Logic()
    {
        var sealedKey = Secrets.Protect(System.Text.Encoding.UTF8.GetBytes("subdl_smoke"));
        Ok(!System.Text.Encoding.UTF8.GetString(sealedKey).Contains("subdl_smoke"), "SubDL key stored encrypted");
        Eq(System.Text.Encoding.UTF8.GetString(Secrets.Unprotect(sealedKey)), "subdl_smoke", "SubDL key reads back");

        // LibTest.kt: addon subtitle rows, subtitlesRanked, IntroDB
        var open = AddonSubs.ToSubs(JsonNode.Parse("""
            {"subtitles":[
                {"id":"1","url":"https://subs.example.org/a","lang":"eng","subtitleFileName":"Show.S01E01.720p.BluRay.x264-DEMAND.srt","movieReleaseName":"Show.S01E01.720p.BluRay.x264-DEMAND","season":1,"episode":1},
                {"id":"2","url":"https://subs.example.org/b","lang":"ger","subtitleFileName":"film_ger.srt","season":0,"episode":0},
                {"id":"3","lang":"eng"}
            ]}
            """));
        Eq(open.Count, 2, "opensubs: rows with a url");
        Eq(open[0].Language, "EN", "opensubs: three letters become the app's code");
        Eq(open[0].Episode, (int?)1, "opensubs: episode");
        Eq(open[1].Language, "DE", "opensubs: ger");
        Eq(open[1].Release, "film_ger", "opensubs: release from the file name");
        Eq(open[1].Season, (int?)null, "opensubs: a film's 0/0 is no episode");
        static Sub Sb(string release, string lang = "EN") => new(release, release, lang, "u/" + release, null, null);
        var ranked = Subtitles.Rank(new[] { Sb("Show.S01E01.1080p.WEB-NTb"), Sb("Show.S01E01.720p.BluRay.x264-DEMAND"), Sb("show.s01e01.720p.bluray.x264-demand"), Sb("Show.S01E01.FR", "FR") },
            "en", "Show.S01E01.720p.BluRay.x264-DEMAND.mkv");
        Eq(string.Join(",", ranked.Select(r => r.Release)), "Show.S01E01.720p.BluRay.x264-DEMAND,Show.S01E01.1080p.WEB-NTb", "subs: English, one per release, matching release first");
        Eq(string.Join(",", Subtitles.Rank(new[] { Sb("Film.WEB"), Sb("Film.1080p.BluRay", "FR") }, "HI", "Film.1080p.BluRay.mkv").Select(r => r.Language)), "FR,EN", "subs: none in the language, every language");
        Eq(IntroDb.Parse("""{"intro":{"start_sec":12.5,"end_sec":70}}"""), new Intro(12.5, 70), "introdb: seconds");
        Eq(IntroDb.Parse("""{"intro":{"start_ms":5000,"end_ms":65000}}"""), new Intro(5, 65), "introdb: milliseconds");
        Eq(IntroDb.Parse("""{"intro":{"start_sec":70,"end_sec":12}}"""), null, "introdb: backwards window");
        Ok(new Intro(30, 90).Covers(29) && !new Intro(30, 90).Covers(89.5), "intro: covers with a lead, not the last second");

        // LibTest.kt: addons
        Eq(Addons.Base("stremio://addon.example.org/abc/manifest.json"), "https://addon.example.org/abc", "addonBase stremio link");
        Eq(Addons.Base("https://addon.example.org/abc/"), "https://addon.example.org/abc", "addonBase trailing slash");
        Eq(Addons.Base("addon.example.org"), "https://addon.example.org", "addonBase bare host");
        Eq(Addons.Base("ftp://addon.example.org"), null, "addonBase other scheme");
        var addon = Addons.ToAddon("https://a.example.org", JsonNode.Parse("""
            {"id":"x","name":"Mine","types":["movie","series"],"idPrefixes":["tt"],
             "resources":["stream",{"name":"subtitles","types":["movie"],"idPrefixes":["tt","kitsu"]}]}
            """));
        Ok(addon is not null && addon.Serves("stream", "series", "tt1:1:2"), "addon serves its type and prefix");
        Ok(addon is not null && !addon.Serves("stream", "series", "kitsu:1"), "addon refuses other prefixes");
        Ok(addon is not null && addon.Serves("subtitles", "movie", "kitsu:1") && !addon.Serves("subtitles", "series", "tt1"), "addon resource's own types and prefixes");
        Ok(addon is not null && !(addon with { On = false }).Serves("stream", "movie", "tt1"), "addon off serves nothing");
        Eq(addon?.Host, "a.example.org", "addon host");
        Eq(addon?.Offers, "streams · subtitles", "addon offers");
        Eq(Addons.ToAddon("https://a.example.org", JsonNode.Parse("""{"name":"Empty","resources":[]}""")), null, "manifest serving nothing is no addon");

        // search.test.ts
        var streams = (JsonArray)JsonNode.Parse("""
            [{"name":"Addon","title":"The.Matrix.1999.2160p.BluRay.x265\n👤 84 💾 51.3 GB","infoHash":"AA0FD381C944A72CABB3E962D5F727C8694D7DD6"},
             {"name":"Mine 1080p","url":"https://media.example.org/films/matrix.mkv","behaviorHints":{"filename":"The.Matrix.1999.mkv","videoSize":1234}},
             {"name":"Proxied","url":"https://media.example.org/p.mkv","behaviorHints":{"proxyHeaders":{"request":{}}}},
             {"name":"Nothing to play","title":"x"}]
            """)!;
        var hits = Search.ToStreamHits(streams, "movie", "Mine");
        Eq(hits.Count, 2, "streams: a torrent and a link, nothing proxied or empty");
        Eq(hits[0].Id, "aa0fd381c944a72cabb3e962d5f727c8694d7dd6", "streams: torrent keyed by info hash");
        Ok(hits[0].Uri.StartsWith("magnet:?xt=urn:btih:aa0fd381c944a72cabb3e962d5f727c8694d7dd6&dn=") && hits[0].Uri.Contains("&tr="), "magnet carries hash and trackers");
        Eq(hits[0].Seeders, 84, "streams: seeders from 👤");
        Eq(hits[0].Size, (long)Math.Round(51.3 * 1024 * 1024 * 1024), "streams: size from 💾");
        Ok(!hits[0].Direct && hits[1].Direct, "streams: a url plays directly");
        Eq(hits[1].Name, "The.Matrix.1999.mkv", "streams: the file's own name");
        Eq(hits[1].Size, 1234L, "streams: the file's own size");
        Eq(hits[1].Quality, "1080p", "streams: quality from the label");
        Eq(hits[1].Addon, "Mine", "streams: which addon");
        Eq(Search.LinkHit("https://media.example.org/Film.2020.720p.mp4")?.Name, "Film.2020.720p.mp4", "linkHit names a pasted link");
        Ok(Search.LinkHit("magnet:?xt=urn:btih:aa0fd381c944a72cabb3e962d5f727c8694d7dd6") is { Direct: false }, "linkHit magnet is a torrent");
        Eq(Search.LinkHit("The Matrix"), null, "linkHit words are no link");
        Ok(!Search.IsDirectLink("https://x.example.org/thing.torrent") && Search.IsDirectLink("http://nas.local/a.mkv"), "isDirectLink");
        Eq(string.Join(",", Search.Rank(new[] { H("seed", seeders: 900), H("link", uri: "https://x.example.org/a.mkv") }).Select(h => h.Id)), "link,seed", "rank links first");

        // Every stream shape: fileIdx, sources trackers, proxyHeaders, ytId / externalUrl.
        var shapes = Search.ToStreamHits((JsonArray)JsonNode.Parse("""
            [{"name":"T","title":"Show.S01.1080p","infoHash":"aa0fd381c944a72cabb3e962d5f727c8694d7dd6","fileIdx":3,"sources":["tracker:udp://tr.example.org:80/announce","dht:aa0f"]},
             {"name":"L","url":"https://media.example.org/a.mkv","behaviorHints":{"filename":"A.mkv","proxyHeaders":{"request":{"User-Agent":"UA/1","Referer":"https://media.example.org/"}}}},
             {"name":"Trailer","title":"Trailer","ytId":"abc123"},
             {"name":"Page","title":"On the site","externalUrl":"https://site.example.org/watch"}]
            """)!, "tv", "Mine");
        Eq(shapes.Count, 4, "every stream shape kept");
        Eq(shapes[0].FileIdx, (int?)3, "fileIdx on a torrent");
        Ok(shapes[0].Uri.Contains("&tr=" + Uri.EscapeDataString("udp://tr.example.org:80/announce")), "sources trackers go into the magnet");
        Eq(shapes[1].Headers.GetValueOrDefault("User-Agent"), "UA/1", "proxyHeaders kept for a link");
        Ok(shapes[1].Direct && !shapes[1].External, "a link with headers still plays directly");
        Ok(shapes[2].External && shapes[2].Uri == "https://www.youtube.com/watch?v=abc123" && !shapes[2].IsTorrent, "ytId opens in the browser");
        Ok(shapes[3].External && !shapes[3].Direct, "externalUrl opens in the browser");
        Eq(Search.Rank(shapes).Last().External, true, "other apps' streams last");

        // Catalogues, meta types and kinds.
        var catalogs = Addons.ToAddon("https://c.example.org", JsonNode.Parse("""
            {"name":"Lists","resources":[{"name":"meta","types":["channel"],"idPrefixes":["yt:"]}],"types":["channel"],
             "catalogs":[{"type":"channel","id":"top","name":"Top"},{"type":"movie","id":"g","name":"By genre","extra":[{"name":"genre","isRequired":true}]},
                         {"type":"series","id":"s","name":"Search","extraSupported":["search"]}]}
            """));
        Ok(catalogs is not null && catalogs.Catalogs.Count == 3, "a catalogue-only addon is an addon");
        Eq(catalogs?.MetaTypes("yt:1", "series").FirstOrDefault(), "channel", "meta types by id prefix");
        Ok(catalogs is not null && catalogs.Catalogs[1].Required && catalogs.Catalogs[2].Search, "required and search extras");
        Eq(Addons.KindOfType("tv"), "movie", "live tv plays its own id");
        Eq(Addons.KindOfType("channel"), "tv", "a channel holds videos");
        Eq(Addons.TypeLabel("channel"), "Channels", "type label");
        Ok(catalogs is not null && catalogs.ShelfKey(catalogs.Catalogs[0]).StartsWith("addon:") && !catalogs.ShelfKey(catalogs.Catalogs[0]).Contains("example"), "shelf key keeps the link out");
        var channel = Meta.ToEpisodes((JsonArray)JsonNode.Parse("""[{"id":"yt:v1","title":"First"},{"id":"yt:v2","title":"Second"}]""")!);
        Eq(string.Join(",", channel.Select(e => $"{e.Season}:{e.Number}:{e.Id}")), "1:1:yt:v1,1:2:yt:v2", "a channel's videos numbered in order");
        var own = Meta.ToTitles((JsonArray)JsonNode.Parse("""[{"id":"my:1","type":"other","name":"Mine"}]""")!, "tv", true, true);
        Ok(own.Count == 1 && own[0].Episodes.Single().Id == "my:1" && own[0].StremioType == "other", "a custom type with no videos plays its own id");

        // StremioTest.kt
        Eq(Stremio.ParseLink("""{"result":{"success":true,"code":"34GW","link":"https://link.stremio.com/34GW"}}""").Code, "34GW", "link sign-in code");
        Eq(Stremio.ParseLinkRead("""{"result":null,"error":{"code":101}}"""), null, "link sign-in waiting");
        Eq(Stremio.ParseLinkRead("""{"result":{"authKey":"abc"}}"""), "abc", "link sign-in approved");
        var lib = Stremio.ParseLibrary(((JsonArray)JsonNode.Parse("""
            [{"_id":"tt1","name":"Film","type":"movie","removed":false,"temp":false,"_mtime":"2026-01-02T00:00:00.000Z","state":{"timeOffset":0,"duration":0,"timesWatched":1,"lastWatched":"2026-01-02T00:00:00.000Z"}},
             {"_id":"tt2","name":"Show","type":"series","removed":false,"temp":true,"state":{"timeOffset":600000,"duration":2400000,"video_id":"tt2:3:4","lastWatched":"2026-01-03T00:00:00.000Z"}},
             {"_id":"tt4","name":"Half","type":"movie","removed":false,"temp":false,"state":{"timeOffset":3000000,"duration":6000000,"lastWatched":"2026-01-01T00:00:00.000Z"}}]
            """)!).OfType<JsonObject>());
        Ok(lib.Films.SetEquals(new[] { "tt1" }), "stremio films watched");
        Eq(string.Join(",", lib.Paused.Select(p => p.Imdb)), "tt2,tt4", "stremio paused newest first");
        Eq(lib.PausedSeconds("tt2", (3, 4)), (double?)600, "stremio paused to the second");
        Eq(string.Join(",", lib.Seen.Select(s => s.Imdb)), "tt1,tt4", "stremio library leaves temp out");
        Eq(Stremio.EpOfVideo("kitsu:123:1:5"), ((int, int)?)(1, 5), "episode of an addon's id");
        var fresh = Stremio.LibraryItem(null, "tt1", "Film", "movie", null, now: 0, change: s => s["timeOffset"] = 5000);
        Ok(fresh["temp"]!.GetValue<bool>() && fresh["_mtime"]!.GetValue<string>() == "1970-01-01T00:00:00.000Z" && fresh["state"]!["video_id"] is null, "library item written whole");
        var collection = new[]
        {
            (JsonObject)JsonNode.Parse("""{"transportUrl":"https://own.example.org/manifest.json","flags":{"protected":true},"manifest":{"id":"c"}}""")!,
            (JsonObject)JsonNode.Parse("""{"transportUrl":"https://a.example.org/cfg/manifest.json","manifest":{"id":"a"}}""")!,
        };
        // The account follows this PC: a removed one goes, a new one is added whole, Stremio's own stays.
        var t = Addons.ToAddon("https://t.example.org/c=1", JsonNode.Parse("""{"id":"t","name":"T","resources":["stream"],"types":["movie"]}"""))!;
        var mirrored = Stremio.Mirrored(collection, new[] { t });
        Eq(string.Join(" ", mirrored.Select(e => e["transportUrl"]!.GetValue<string>())), "https://own.example.org/manifest.json https://t.example.org/c=1/manifest.json", "account addons follow this PC");
        Eq(mirrored[1]["transportName"]!.GetValue<string>(), "http", "a new addon is a whole entry");
        Eq(Stremio.Mirrored(collection, Array.Empty<Addon>()).Count, 1, "a protected addon stays");

        // One resume spot per title on this PC, whichever release opens it.
        var older = new HistoryItem { Uri = "a", Title = "Film.720p", Imdb = "tt9", Files = new() { F(0, "Film.720p.mkv", 1) }, LastFileIndex = 0, Positions = new() { [0] = 600 }, Durations = new() { [0] = 6000 }, Played = new() { [0] = 100 } };
        var newer = new HistoryItem { Uri = "b", Title = "Film.1080p", Imdb = "tt9", Files = new() { F(0, "Film.1080p.mkv", 1) }, LastFileIndex = 0, Positions = new() { [0] = 2400 }, Durations = new() { [0] = 6000 }, Played = new() { [0] = 200 } };
        Eq(History.ResumeAt(new[] { older, newer }, older, 0, "Film.720p.mkv"), (double?)2400, "an older release resumes where the title was last left");
        Eq(History.ResumeAt(new[] { older, newer }, newer, 0, "Film.1080p.mkv"), (double?)2400, "the newest release keeps its own spot");
        Eq(History.LatestPerTitle(new[] { older with { At = 999 }, newer }).Single().Uri, "b", "Continue Watching shows the release played last");

        Eq(Search.Quality("Some.Film.2160p.WEB"), "4K", "quality 4K");
        Eq(Search.Quality("Some.Film.720p"), "720p", "quality 720p");
        Eq(Search.Quality("Some.Film.DVDRip"), null, "quality none");
        Eq(Search.Health(500), "fast", "health fast");
        Eq(Search.Health(0), "dead", "health dead");

        Eq(string.Join(",", Search.Rank(new[] { H("leech", leechers: 20), H("seed", seeders: 10) }).Select(h => h.Id)), "seed,leech", "rank seeders over leechers");
        Eq(string.Join(",", Search.Rank(new[] { H("partial", "The Matrix Pack", 100), H("exact", "The Matrix Reloaded", 80) }, "matrix reloaded").Select(h => h.Id)), "exact,partial", "rank full name match");
        Eq(string.Join(",", Search.RankEpisode(new[] { H("all", "Show S01-S05", 99, scope: "complete"), H("ep", "Show S01E02 1080p", 80), H("pack", "Show S01 COMPLETE", 40, scope: "season") }, "", 1).Select(h => h.Id)), "pack,ep,all", "rankEpisode order");

        Eq(Search.EpisodeTag(1, 2), "S01E02", "episodeTag");
        Eq(Search.ScopeOf("Ted Lasso S01E02 1080p WEB-DL"), "episode", "scope episode");
        Eq(Search.ScopeOf("Ted.Lasso.S01E01-E10.1080p"), "season", "scope range season");
        Eq(Search.ScopeOf("Ted.Lasso.S01.COMPLETE.720p.WEBRip"), "season", "scope season");
        Eq(Search.ScopeOf("Ted Lasso Seasons 1-3 COMPLETE 1080p"), "complete", "scope seasons range");
        Eq(Search.ScopeOf("Prison Break [S01-S05] 1080p"), "complete", "scope S01-S05");
        Eq(Search.ScopeOf("Some.Film.2024.1080p.BluRay"), "episode", "scope film");
        Eq(Search.ScopeOf("Show Complete Series S02E05"), "episode", "scope marker wins");

        Eq(Search.ParseEpisode("Ted.Lasso.S01E02.1080p.mkv"), (1, 2), "parseEpisode S01E02");
        Eq(Search.ParseEpisode("Show S1 E2.mkv"), (1, 2), "parseEpisode S1 E2");
        Eq(Search.ParseEpisode("Show 1x02.mkv"), (1, 2), "parseEpisode 1x02");
        Eq(Search.ParseEpisode("Some.Film.2024.1080p.mkv"), null, "parseEpisode film");
        Eq(Search.ParseEpisode("One.Piece.S01E1071.1080p.mkv"), (1, 1071), "parseEpisode 4-digit episode");

        // Anime: running episode numbers, no season.
        Eq(Search.ParseAbsolute("[SubsPlease] Jujutsu Kaisen - 29 (1080p) [ABCD1234].mkv"), 29, "parseAbsolute dash");
        Eq(Search.ParseAbsolute("One Piece E1071 1080p"), 1071, "parseAbsolute E1071");
        Eq(Search.ParseAbsolute("Naruto Shippuden Episode 145 [720p]"), 145, "parseAbsolute Episode 145");
        Eq(Search.ParseAbsolute("[Erai-raws] Show - 05v2 [1080p].mkv"), 5, "parseAbsolute v2");
        Eq(Search.ParseAbsolute("Some Film - 1080p.mkv"), null, "parseAbsolute resolution");
        Eq(Search.ParseAbsolute("Dune - 2021.mkv"), null, "parseAbsolute year");
        Eq(Search.ParseAbsolute("Ted.Lasso.S01E02.mkv"), null, "parseAbsolute leaves SxxEyy");
        Eq(Search.ParseAbsolute("[orion origin] Jujutsu Kaisen S2 [29] [1080p] [H265 AAC].mp4"), 29, "parseAbsolute bracketed");
        Eq(Search.ParseAbsolute("[yibis]_One_Piece_588_[720p][C2543B95].mkv"), 588, "parseAbsolute underscores");
        Eq(Search.ParseAbsolute("Show Season 12 [1080p]"), null, "parseAbsolute not a season number");
        Eq(Search.ParseAbsolute("Show 1080p [GROUP]"), null, "parseAbsolute not a resolution");
        Eq(Search.ScopeOf("[orion origin] Jujutsu Kaisen S2 [29] [1080p]"), "episode", "scope bracketed anime episode");
        Eq(Search.ScopeOf("Jujutsu Kaisen S2 COMPLETE 1080p"), "season", "scope anime season pack");
        Eq(Search.ParseAbsolute("Stranger.Things.S04.COMPLETE.1080p.x265"), null, "parseAbsolute pack");
        Eq(Search.EpisodeSpan("[Judas] Show (01-24) [BD 1080p] [Batch]"), (1, 24), "episodeSpan batch");
        Eq(Search.EpisodeSpan("Show Seasons 1-3"), (1, 3), "episodeSpan is only asked without season labels");
        Eq(Search.NamesEpisode("[SubsPlease] Jujutsu Kaisen - 29 (1080p)", 2, 5, 29), true, "namesEpisode absolute");
        Eq(Search.NamesEpisode("[SubsPlease] Jujutsu Kaisen - 05 (1080p)", 2, 5, 29), false, "namesEpisode not season 1's fifth");
        Eq(Search.NamesEpisode("Jujutsu Kaisen Season 2 - 05", 2, 5, 29), true, "namesEpisode season label");
        Eq(Search.NamesEpisode("[RH] Attack on Titan Season 2 - 05 [1080p]", 1, 5, 5), false, "namesEpisode other season's label");
        Eq(Search.NamesEpisode("[orion origin] Jujutsu Kaisen S2 [29] [1080p]", 2, 5, 29), true, "namesEpisode season label + running number");
        Eq(Search.NamesEpisode("Jujutsu Kaisen S02E05", 2, 5, 29), true, "namesEpisode SxxEyy");
        Eq(Search.NamesEpisode("Jujutsu Kaisen S2 COMPLETE", 2, 5, 29), null, "namesEpisode pack names none");
        Eq(Search.PickTag(2, 5, 29), "S02E05#29", "pickTag absolute");
        Eq(Search.PickTag(1, 5, 5), "S01E05", "pickTag plain");
        var jjk = new List<Episode>();
        for (var e = 1; e <= 24; e++) jjk.Add(new Episode { Id = $"tt:1:{e}", Season = 1, Number = e });
        for (var e = 1; e <= 23; e++) jjk.Add(new Episode { Id = $"tt:2:{e}", Season = 2, Number = e });
        jjk.Insert(0, new Episode { Id = "tt:0:1", Season = 0, Number = 1 });
        // Shelf ordering. Unknown rating or year goes last — it is unknown, not zero.
        var shelf = new List<Title>
        {
            new() { Imdb = "tt1", Name = "Bravo", Rating = "7.1", Year = "2019" },
            new() { Imdb = "tt2", Name = "Alpha", Rating = null, Year = null },
            new() { Imdb = "tt3", Name = "Charlie", Rating = "8.4", Year = "2024" },
        };
        Eq(string.Join(",", Meta.SortTitles(shelf, "popular").Select(t => t.Name)), "Bravo,Alpha,Charlie", "sortTitles popular keeps order");
        Eq(string.Join(",", Meta.SortTitles(shelf, "rating").Select(t => t.Name)), "Charlie,Bravo,Alpha", "sortTitles by rating");
        Eq(string.Join(",", Meta.SortTitles(shelf, "year").Select(t => t.Name)), "Charlie,Bravo,Alpha", "sortTitles by year");
        Eq(string.Join(",", Meta.SortTitles(shelf, "title").Select(t => t.Name)), "Alpha,Bravo,Charlie", "sortTitles by title");
        Eq(shelf[0].Name, "Bravo", "sortTitles leaves the shelf alone");

        Eq(Meta.AbsoluteNumber(jjk, 2, 5), 29, "absoluteNumber skips specials");
        Eq(Meta.AbsoluteNumber(new[] { new Episode { Season = 21, Number = 892 }, new Episode { Season = 21, Number = 893 } }, 21, 893), 893, "absoluteNumber keeps running numbering");
        Eq(string.Join(",", Meta.AbsoluteNumbers(jjk).Select(n => n.Number)), string.Join(",", jjk.Where(e => e.Season > 0).Select(e => Meta.AbsoluteNumber(jjk, e.Season, e.Number))), "absoluteNumbers matches absoluteNumber");
        Store.UseProfile(Profiles.Guest.Id, true);
        Store.SaveOwn("smoke-guest-only", 5);
        Eq(Store.LoadOwn("smoke-guest-only", 0), 5, "guest keeps things in memory");
        Store.UseProfile("", false);
        Eq(Store.LoadOwn("smoke-guest-only", 0), 0, "guest's things never reach the disk");
        var silo = new List<TorrentFileInfo>
        {
            new() { Index = 0, Path = "Silo S02/Silo.S02E03.1080p.mkv", Name = "Silo.S02E03.1080p.mkv", IsVideo = true },
            new() { Index = 1, Path = "Silo S02/Silo.S02E04.1080p.mkv", Name = "Silo.S02E04.1080p.mkv", IsVideo = true },
            new() { Index = 2, Path = "Silo S02/Silo.S02E03.1080p.srt", Name = "Silo.S02E03.1080p.srt", IsSubtitle = true },
            new() { Index = 3, Path = "Silo S02/Silo.S02E04.1080p.srt", Name = "Silo.S02E04.1080p.srt", IsSubtitle = true },
            new() { Index = 4, Path = "Silo S02/Subs/Silo.S02E04.1080p/2_English.srt", Name = "2_English.srt", IsSubtitle = true },
        };
        Eq(string.Join(",", Pick.SubtitlesFor(silo, 1)), "3,4", "a pack's episode gets only its own subtitles");
        var film = new List<TorrentFileInfo>
        {
            new() { Index = 0, Path = "Film/Film.2024.mkv", Name = "Film.2024.mkv", IsVideo = true },
            new() { Index = 1, Path = "Film/English.srt", Name = "English.srt", IsSubtitle = true },
            new() { Index = 2, Path = "Film/Subs/French.srt", Name = "French.srt", IsSubtitle = true },
        };
        Eq(string.Join(",", Pick.SubtitlesFor(film, 0)), "1,2", "a single film keeps every subtitle");
        Eq(new AppSettings().Resized(1).SubSize, "large", "subtitle size up");
        Eq(new AppSettings { SubSize = "huge" }.Resized(1).SubSize, "huge", "subtitle size stops at huge");
        Eq(new AppSettings { SubSize = "small" }.Resized(-1).SubRel, 20, "subtitle size stops at small");
        Eq(jjk[Meta.IndexOfEpisode(jjk, "[SubsPlease] Jujutsu Kaisen - 29 (1080p).mkv", "JJK/[SubsPlease] Jujutsu Kaisen - 29 (1080p).mkv")].Id, "tt:2:5", "indexOfEpisode running number");
        Eq(jjk[Meta.IndexOfEpisode(jjk, "Show - 05.mkv", "Show Season 2/Show - 05.mkv")].Id, "tt:2:5", "indexOfEpisode season folder");

        Eq(string.Join(",", Search.SeasonsIn("Stranger.Things.S04.COMPLETE.1080p")), "4", "seasonsIn S04");
        Eq(string.Join(",", Search.SeasonsIn("Stranger.Things.Season.5.Complete.2025.720p")), "5", "seasonsIn Season.5");
        Eq(string.Join(",", Search.SeasonsIn("Show S01-S03 COMPLETE")), "1,2,3", "seasonsIn range");
        Eq(string.Join(",", Search.SeasonsIn("Show Seasons 1-3 COMPLETE")), "1,2,3", "seasonsIn spelled range");
        Eq(string.Join(",", Search.SeasonsIn("Show.S04.DDP5.1.Atmos.x265.10bit")), "4", "seasonsIn ignores codecs");
        Eq(string.Join(",", Search.SeasonsIn("Ocean's 8 2018 1080p")), "", "seasonsIn Ocean's 8");

        var merged = Search.Merge(new[] { new[] { H("a", seeders: 5, size: 100), H("b", seeders: 1) }, new[] { H("a", seeders: 900) } });
        Eq(merged.Count, 2, "merge dedupes");
        Eq(merged.First(h => h.Id == "a").Seeders, 900, "merge keeps bigger swarm");
        Eq(merged.First(h => h.Id == "a").Size, 100L, "merge keeps known size");
        var dual = Search.Merge(new[] { new[] { H("c", seeders: 900, size: 15L << 30) }, new[] { H("c", seeders: 20, size: 700L << 20, perFile: true) } });
        Eq(dual[0].PerFileSize, false, "merge flag follows size");

        var pb = Search.RankEpisode(new[]
        {
            H("a", "Prison Break (2005) Season 1-5 S01-S05 1080p BluRay x265 HEVC", 145, scope: Search.ScopeOf("Prison Break (2005) Season 1-5 S01-S05 1080p BluRay x265 HEVC")),
            H("b", "Prison Break 2005 Season 1 Complete 720p AMZN WEB-DL x264 [i_c]", 73, scope: Search.ScopeOf("Prison Break 2005 Season 1 Complete 720p AMZN WEB-DL x264 [i_c]")),
            H("c", "Prison Break 2005 Season 2 Complete 720p AMZN WEB-DL x264 [i_c]", 70, scope: Search.ScopeOf("Prison Break 2005 Season 2 Complete 720p AMZN WEB-DL x264 [i_c]")),
            H("d", "Prison.Break.S01E01.ENG.1080p.HD.WEB", 18, scope: Search.ScopeOf("Prison.Break.S01E01.ENG.1080p.HD.WEB")),
        }, "Prison Break", 1);
        Eq(pb[0].Id, "b", "prison break season 1 pack first");

        // pick.test.ts
        Eq(Pick.PickFile(new[] { F(0, "Movie.1080p.mkv", 8_000_000_000), F(1, "sample.mkv", 40_000_000), F(2, "Movie.nfo", 900, false) }), 0, "pickFile feature");
        var pack = new[] { F(0, "Show.S01E01.1080p.mkv", 2_000_000_000), F(1, "Show.S01E02.1080p.mkv", 1_000_000_000), F(2, "Show.S01E03.1080p.mkv", 3_000_000_000) };
        Eq(Pick.PickFile(pack, "S01E02"), 1, "pickFile tag");
        Eq(Pick.PickFile(new[] { F(0, "Show.1x02.mkv", 5), F(1, "Show.S1E2.mkv", 9) }, "S01E02"), 1, "pickFile no zeros");
        Eq(Pick.PickFile(pack, "S09E09"), null, "pickFile refuses wrong episode");
        Eq(Pick.PickFile(new[] { F(0, "episode.mkv", 500) }, "S09E09"), 0, "pickFile single video");
        Eq(Pick.PickFile(new[] { F(0, "readme.txt", 10, false) }), null, "pickFile nothing");
        var season = new[] { F(0, "Show.S01E10.mkv", 100), F(1, "Show.S01E02.mkv", 100), F(2, "Show.S01E01.mkv", 100), F(3, "Show.S02E01.mkv", 100), F(4, "sample.mkv", 5) };
        Eq(Pick.NextEpisode(season, 2)?.Name, "Show.S01E02.mkv", "nextEpisode order");
        Eq(Pick.NextEpisode(season, 0)?.Name, "Show.S02E01.mkv", "nextEpisode boundary");
        Eq(Pick.NextEpisode(season, 3), null, "nextEpisode end");
        Eq(string.Join(",", Pick.SeasonFiles(pack, 1)), "0,1,2", "seasonFiles pack");
        Eq(string.Join(",", Pick.SeasonFiles(season, 1)), "2,1,0", "seasonFiles order");
        Eq(string.Join(",", Pick.SeasonFiles(season, 2)), "3", "seasonFiles season 2");
        // Recommend: daily shelves.
        var genresCycle = new[] { "Action", "Adventure", "Sci-Fi", "Drama", "Comedy" };
        var pool = Enumerable.Range(0, 150).Select(i => new Title
        {
            Imdb = $"tt{1000000 + i}", Name = $"Film {i}", Rating = (5 + i % 5).ToString(), Year = (1995 + i % 30).ToString(),
            Genres = new() { genresCycle[i % 5], genresCycle[(i + 1) % 5], genresCycle[(i + 3) % 5] },
        }).ToList();
        var noTaste = new Dictionary<string, double>();
        var noneWatched = new HashSet<string?>();
        var day1 = new DateTime(2026, 9, 16, 9, 0, 0);
        var morning = Recommend.ForToday(pool, "Action", noTaste, noneWatched, true, day1).Select(t => t.Imdb).ToList();
        var evening = Recommend.ForToday(pool, "Action", noTaste, noneWatched, true, day1.AddHours(12)).Select(t => t.Imdb).ToList();
        var tomorrow = Recommend.ForToday(pool, "Action", noTaste, noneWatched, true, day1.AddDays(1)).Select(t => t.Imdb).ToList();
        Ok(morning.SequenceEqual(evening), "recommend stable within a day");
        Ok(!morning.SequenceEqual(tomorrow) && morning.Intersect(tomorrow).Count() < morning.Count, "recommend changes next day");
        Ok(morning.Count > 0 && morning.Count <= Recommend.ShelfSize, "recommend shelf size");
        Ok(Recommend.ForToday(pool, null, noTaste, new HashSet<string?> { pool[0].Imdb }, false, day1).All(t => t.Imdb != pool[0].Imdb), "recommend skips watched");
        var perShelf = genresCycle.Select(g => Recommend.ForToday(pool, g, noTaste, noneWatched, true, day1).Select(t => t.Imdb)).ToList();
        Ok(perShelf.SelectMany(x => x).GroupBy(x => x).All(g => g.Count() == 1), "recommend one genre shelf per title a day");

        Ok(Pick.IsUnsafe("Movie.2020.1080p/Codec Installer.exe"), "isUnsafe exe");
        Ok(Pick.IsUnsafe("Movie.mp4.exe"), "isUnsafe double extension");
        Ok(Pick.IsUnsafe("Movie.mp4.lnk"), "isUnsafe shortcut");
        Ok(Pick.IsUnsafe("setup.exe. "), "isUnsafe trailing dot");
        Ok(Pick.IsUnsafe("Show/../../AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup/x.mkv"), "isUnsafe path climbs out");
        Ok(Pick.IsUnsafe("C:/Windows/x.mkv"), "isUnsafe drive path");
        Ok(!Pick.IsUnsafe("The.Matrix.1999/The.Matrix.1999.1080p.mkv"), "isUnsafe video ok");
        Ok(!Pick.IsUnsafe("Show S01/Subs/English.srt"), "isUnsafe subtitle ok");
        Ok(!Pick.IsUnsafe("Movie...2019/Movie.mkv"), "isUnsafe dotted folder ok");
        Ok(Pick.IsUnsafe("Movie.2020/Movie.2020.1080p.rar"), "isUnsafe archive");
        Ok(Pick.IsUnsafe("Movie.2020/Bonus.iso"), "isUnsafe disk image");
        Ok(Pick.IsUnsafe("Movie.2020/Watch Online.html"), "isUnsafe web page");
        Ok(Pick.IsUnsafe("README"), "isUnsafe no extension");
        Ok(!Pick.IsUnsafe("Movie.2020/Cover.jpg") && !Pick.IsUnsafe("Movie.2020/Movie.nfo") && !Pick.IsUnsafe("Album/01.flac"), "isUnsafe art, notes, audio ok");
        var batch = new[] { F(0, "Show/[Group] Show - 28 [1080p].mkv", 100), F(1, "Show/[Group] Show - 29 [1080p].mkv", 100), F(2, "Show/[Group] Show - 30 [1080p].mkv", 100) };
        Eq(Pick.PickFile(batch, "S02E05#29"), 1, "pickFile anime running number");
        Eq(Pick.PickFile(batch, "S02E09#33"), null, "pickFile anime missing episode");
        var cour = new[] { F(0, "[Group] Show - 04.mkv", 100), F(1, "[Group] Show - 05.mkv", 100), F(2, "[Group] Show - 06.mkv", 100) };
        Eq(Pick.PickFile(cour, "S02E05#29"), 1, "pickFile unlabelled season pack numbered from 1");
        Eq(Pick.NextEpisode(batch, 1)?.Index, 2, "nextEpisode anime");
        Eq(Pick.NextEpisode(cour, 2), null, "nextEpisode anime end");

        // title.test.ts
        Eq(TitleText.TitleOf("magnet:?xt=urn:btih:ABC123&dn=Big+Buck+Bunny%202008"), "Big Buck Bunny 2008", "titleOf dn");
        Eq(TitleText.TitleOf("magnet:?xt=urn:btih:abcdef0123456789aabb"), "abcdef0123456789", "titleOf hash");
        Eq(TitleText.TitleOf("https://x.org/dl/thing.torrent?key=1"), "thing.torrent", "titleOf url");
        Eq(TitleText.TitleOf("magnet:?xt=urn:btih:ABC&dn=100%"), "100%", "titleOf bad escape");
        Ok(TitleText.HueOf("Spider-Man") != TitleText.HueOf("Spider-Man 2"), "hueOf spreads");
        Eq(TitleText.SearchableName("The.Matrix.1999.1080p.BluRay.x264-GRP"), "The Matrix", "searchableName film");
        Eq(TitleText.SearchableName("Ted Lasso S01E02 1080p WEB-DL"), "Ted Lasso", "searchableName episode");
        Eq(TitleText.SearchableName("Some Show Season 2 Complete"), "Some Show", "searchableName season");
        Eq(TitleText.SearchableName("Dune Part Two (2024) 2160p.mkv"), "Dune Part Two", "searchableName parens");
        Eq(TitleText.SearchableName("[Anime Time] Attack On Titan - 01.mkv"), "Attack On Titan - 01", "searchableName tags");
        Eq(TitleText.YearOf("The.Matrix.1999.1080p"), "1999", "yearOf");
        Ok(TitleText.IsSeries("Show Season 2 Complete") && !TitleText.IsSeries("The.Matrix.1999.1080p"), "isSeries");

        // format
        Eq(Format.Clock(75), "1:15", "clock minutes");
        Eq(Format.Clock(3675), "1:01:15", "clock hours");
        Ok(Format.Natural("E2", "E10") < 0, "natural sort numbers");
    }

    static void Screens()
    {
        var window = new MainWindow(null);
        var size = new Size(1280, 820);
        void Layout(FrameworkElement e, string name)
        {
            try
            {
                e.Measure(size);
                e.Arrange(new Rect(size));
                e.UpdateLayout();
            }
            catch (Exception ex)
            {
                Failures.Add($"{name} layout: {ex.GetBaseException().Message}");
            }
        }

        // Item templates only build when there is an item, so hand them one each.
        var sample = new Title { Imdb = "tt0133093", Kind = "tv", Name = "The Matrix", Year = "1999", Rating = "8.7", Poster = "https://example.invalid/p.jpg" };
        var item = new HistoryItem { Uri = "magnet:?xt=urn:btih:c39fe3eefbdb62da9c27eb6398ff4a7d2e26e7ab", Title = "The.Matrix.1999", LastFileIndex = 0 };
        foreach (var (key, content) in new (string, object)[] { ("PosterCard", sample), ("RailCard", sample), ("ResumeCard", new ResumeTile(sample, item, 0, 40, "S1 E2  ·  12:00")) })
        {
            try
            {
                Layout(new System.Windows.Controls.ContentPresenter { Content = content, ContentTemplate = (DataTemplate)Application.Current.FindResource(key) }, key);
            }
            catch (Exception ex)
            {
                Failures.Add($"{key}: {ex.GetBaseException().Message}");
            }
        }
        var job = new Engine.DownloadJob { Uri = item.Uri, Title = "Some.Show.S01", FileIndices = new() { 0, 1 } };
        var active = new Engine.ActiveDownload(job) { HasQueue = true };
        active.Queue.Add(new Engine.QueueFile { Index = 0, Name = "Some.Show.S01E01.mkv", Size = 1_000_000, State = "Done" });
        active.Queue.Add(new Engine.QueueFile { Index = 1, Name = "Some.Show.S01E02.mkv", Size = 1_000_000, State = "Downloading", Percent = 42 });
        active.Queue.Add(new Engine.QueueFile { Index = 2, Name = "Some.Show.S01E03.mkv", Size = 1_000_000, State = "Off" });
        Engine.Downloads.Instance.Live.Add(active);

        foreach (var tab in new[] { "home", "search", "addons", "history", "downloads", "settings" })
        {
            try
            {
                window.ShowTab(tab);
                Layout((FrameworkElement)window.Content, $"tab {tab}");
            }
            catch (Exception ex)
            {
                Failures.Add($"tab {tab}: {ex.GetBaseException().Message}");
            }
        }

        var film = new Title
        {
            Imdb = "tt0133093", Kind = "movie", Name = "The Matrix", Year = "1999", Rating = "8.7",
            Genres = new() { "Action", "Sci-Fi" }, Plot = "A hacker learns the truth.", Cast = new() { "Keanu Reeves", "Carrie-Anne Moss" },
            Directors = new() { "Lana Wachowski" }, Awards = "Won 4 Oscars",
        };
        var show = film with
        {
            Imdb = "tt0000001", Kind = "tv", Name = "Some Show",
            Episodes = Enumerable.Range(1, 14).Select(i => new Episode { Id = $"tt0000001:1:{i}", Season = 1, Number = i, Name = $"Episode {i}" })
                .Append(new Episode { Id = "tt0000001:2:1", Season = 2, Number = 1, Name = "Return" }).ToList(),
        };
        foreach (var (name, make) in new (string, Func<FrameworkElement>)[]
        {
            ("detail film", () => new DetailView(film)),
            ("detail series", () => new DetailView(show)),
            ("detail long anime", () => new DetailView(show with
            {
                Genres = new() { "Animation" },
                Episodes = Enumerable.Range(1, 230).Select(i => new Episode { Id = $"tt0000001:{1 + i / 100}:{i}", Season = 1 + i / 100, Number = i, Name = $"Episode {i}" }).ToList(),
            })),
            ("profiles", () => new ProfilesView(_ => { }, () => { }, _ => { })),
            ("catalog", () => new CatalogView(new Shelf("Trending films", "movie"))),
            ("files", () => new FilesView(new TorrentMeta("Some.Show.S01.1080p", new() { F(0, "Some.Show.S01E01.mkv", 1_000_000), F(1, "Some.Show.S01E02.mkv", 1_000_000), F(2, "info.nfo", 10, false) }), "stream", _ => { }, _ => { }, () => { }, () => { })),
            ("mode picker", () => new ModePickerView(new TorrentMeta("x", new() { F(0, "a.mkv", 1) }), () => { }, () => { }, () => { })),
            ("player", () =>
            {
                LibVLCSharp.Shared.Core.Initialize();
                return new PlayerView(new PlaybackRequest { Title = "missing.mkv", LocalPath = Path.Combine(Path.GetTempPath(), "watchly-smoke-missing.mkv") });
            }),
        })
        {
            try
            {
                var view = make();
                Layout(view, name);
                (view as IDisposable)?.Dispose();
            }
            catch (Exception ex)
            {
                Failures.Add($"{name}: {ex.GetBaseException().Message}");
            }
        }
    }
}
