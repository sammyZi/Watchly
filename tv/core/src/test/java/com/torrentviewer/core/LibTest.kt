package com.torrentviewer.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the Expo build's lib tests (search, pick, title, magnet, format, subdl). */
class LibTest {
    private fun row(vararg over: Pair<String, Any>) = JSONObject(
        mapOf<String, Any>(
            "id" to "1", "name" to "A Movie 1080p", "info_hash" to "C39FE3EEFBDB62DA9C27EB6398FF4A7D2E26E7AB",
            "seeders" to "5", "leechers" to "1", "size" to "1024", "category" to "207",
        ) + over,
    )

    private fun hit(id: String = "x", name: String = "n", seeders: Int = 0, leechers: Int = 0, size: Long = 0, scope: Scope = Scope.EPISODE) =
        Hit(id, name, "m", seeders, leechers, size, null, "movie", scope, null)

    private fun f(index: Int, name: String, size: Long, video: Boolean = true) = TorrentFile(index, name, name, size, video, false)

    @Test fun apibayRows() {
        assertEquals(0, toHits(JSONArray().put(row("id" to "0", "info_hash" to "0".repeat(40))).put(row("id" to "2", "category" to "101"))).size)
        val numeric = toHits(JSONArray().put(row("id" to 42, "seeders" to 7, "leechers" to 2, "size" to 9, "category" to 207))).single()
        assertEquals("c39fe3eefbdb62da9c27eb6398ff4a7d2e26e7ab", numeric.id)
        assertEquals(7, numeric.seeders)
        assertTrue(numeric.magnet.startsWith("magnet:?xt=urn:btih:c39fe3eefbdb62da9c27eb6398ff4a7d2e26e7ab&dn="))
        assertTrue(numeric.magnet.contains("&tr="))
        val tagged = toHits(JSONArray().put(row("category" to 205, "imdb" to "tt0133093"))).single()
        assertEquals("tv", tagged.kind)
        assertEquals("tt0133093", tagged.imdb)
        assertNull(toHits(JSONArray().put(row("imdb" to ""))).single().imdb)
    }

    @Test fun qualityKindHealth() {
        assertEquals("4K", quality("Some.Film.2160p.WEB"))
        assertEquals("720p", quality("Some.Film.720p"))
        assertNull(quality("Some.Film.DVDRip"))
        assertEquals("movie", kindOf(207))
        assertEquals("tv", kindOf(208))
        assertEquals("other", kindOf(203))
        assertEquals(Health.FAST, health(500))
        assertEquals(Health.GOOD, health(20))
        assertEquals(Health.WEAK, health(1))
        assertEquals(Health.DEAD, health(0))
    }

    @Test fun ranking() {
        assertEquals(listOf("seed", "leech"), rank(listOf(hit("leech", leechers = 20), hit("seed", seeders = 10))).map { it.id })
        assertEquals(
            listOf("exact", "partial"),
            rank(listOf(hit("partial", "The Matrix Pack", 100), hit("exact", "The Matrix Reloaded", 80)), "matrix reloaded").map { it.id },
        )
        assertEquals(
            listOf("pack", "ep", "all"),
            rankEpisode(
                listOf(
                    hit("all", "Show S01-S05", 99, scope = Scope.COMPLETE),
                    hit("ep", "Show S01E02 1080p", 80),
                    hit("pack", "Show S01 COMPLETE", 40, scope = Scope.SEASON),
                ),
                "", 1,
            ).map { it.id },
        )
        val pb = { name: String, seeders: Int -> Hit(name, name, "m", seeders, 0, 0, null, "tv", scopeOf(name), null) }
        val ranked = rankEpisode(
            listOf(
                pb("Prison Break (2005) Season 1-5 S01-S05 1080p BluRay x265 HEVC", 145),
                pb("Prison Break 2005 Season 1 Complete 720p AMZN WEB-DL x264 [i_c]", 73),
                pb("Prison Break 2005 Season 2 Complete 720p AMZN WEB-DL x264 [i_c]", 70),
                pb("Prison.Break.S01E01.ENG.1080p.HD.WEB", 18),
            ),
            "Prison Break", 1,
        )
        assertEquals("Prison Break 2005 Season 1 Complete 720p AMZN WEB-DL x264 [i_c]", ranked[0].name)
        assertTrue(ranked.indexOfFirst { it.scope == Scope.EPISODE } < ranked.indexOfFirst { "Season 2" in it.name })
    }

    @Test fun scopesAndEpisodes() {
        assertEquals("S01E02", episodeTag(1, 2))
        assertEquals("S10E11", episodeTag(10, 11))
        assertEquals(Scope.EPISODE, scopeOf("Ted Lasso S01E02 1080p WEB-DL"))
        assertEquals(Scope.SEASON, scopeOf("Ted.Lasso.S01E01-E10.1080p"))
        assertEquals(Scope.SEASON, scopeOf("Ted.Lasso.S01.COMPLETE.720p.WEBRip"))
        assertEquals(Scope.SEASON, scopeOf("Ted Lasso (2020) Season 1 S01 1080p"))
        assertEquals(Scope.COMPLETE, scopeOf("Ted Lasso Seasons 1-3 COMPLETE 1080p"))
        assertEquals(Scope.COMPLETE, scopeOf("Ted Lasso Complete Series 1080p"))
        assertEquals(Scope.COMPLETE, scopeOf("Prison Break [S01-S05] 1080p"))
        assertEquals(Scope.EPISODE, scopeOf("Some.Film.2024.1080p.BluRay"))
        assertEquals(Scope.EPISODE, scopeOf("Show Complete Series S02E05"))

        assertEquals(Ep(1, 2), parseEpisode("Ted.Lasso.S01E02.1080p.mkv"))
        assertEquals(Ep(1, 2), parseEpisode("Show S1 E2.mkv"))
        assertEquals(Ep(1, 2), parseEpisode("Show 1x02.mkv"))
        assertEquals(Ep(10, 11), parseEpisode("Show.S10E11.mkv"))
        assertNull(parseEpisode("Some.Film.2024.1080p.mkv"))

        assertEquals(listOf(4), seasonsIn("Stranger.Things.S04.COMPLETE.1080p"))
        assertEquals(listOf(5), seasonsIn("Stranger Things - Season 5 Complete"))
        assertEquals(listOf(5), seasonsIn("Stranger.Things.Season.5.Complete.2025.720p"))
        assertEquals(listOf(4), seasonsIn("Stranger Things S04E01 Chapter One"))
        assertEquals(listOf(1, 2, 3), seasonsIn("Show S01-S03 COMPLETE"))
        assertEquals(listOf(1, 2, 3), seasonsIn("Show Seasons 1-3 COMPLETE"))
        assertEquals(listOf(4), seasonsIn("Stranger Things Season 4 S04 Complete"))
        assertEquals(emptyList<Int>(), seasonsIn("Show Complete Series 1080p"))
        assertEquals(emptyList<Int>(), seasonsIn("The.Matrix.1999.1080p.BluRay.x264"))
        assertEquals(listOf(4), seasonsIn("Show.S04.DDP5.1.Atmos.x265.10bit"))
        assertEquals(emptyList<Int>(), seasonsIn("Ocean's 8 2018 1080p"))
    }

    @Test fun otherIndexes() {
        val stream = JSONObject()
            .put("name", "Torrentio\n4k HDR")
            .put("title", "The.Matrix.1999.2160p.BluRay.x265\n👤 84 💾 51.3 GB ⚙️ 1337x")
            .put("infoHash", "aa0fd381c944a72cabb3e962d5f727c8694d7dd6")
        val one = toTorrentioHits(JSONArray().put(stream), "movie").single()
        assertEquals("The.Matrix.1999.2160p.BluRay.x265", one.name)
        assertEquals(84, one.seeders)
        assertEquals(Math.round(51.3 * 1024 * 1024 * 1024), one.size)
        assertEquals("4K", one.quality)
        assertEquals(0, toTorrentioHits(JSONArray().put(JSONObject(stream.toString()).put("title", "Some.Release.720p")), "movie").single().seeders)
        assertEquals(0, toTorrentioHits(JSONArray().put(JSONObject(stream.toString()).put("infoHash", "nope")), "movie").size)

        val yts = toYtsHits(
            JSONArray().put(
                JSONObject().put("title_long", "The Matrix (1999)").put("imdb_code", "tt0133093").put(
                    "torrents",
                    JSONArray()
                        .put(JSONObject().put("hash", "C39FE3EEFBDB62DA9C27EB6398FF4A7D2E26E7AB").put("quality", "1080p").put("type", "bluray").put("seeds", 40).put("peers", 3).put("size_bytes", 2000))
                        .put(JSONObject().put("hash", "nope").put("quality", "720p").put("seeds", 1)),
                ),
            ),
        ).single()
        assertEquals(40, yts.seeders)
        assertEquals("1080p", yts.quality)

        val eztv = toEztvHits(JSONArray().put(JSONObject().put("hash", "C39FE3EEFBDB62DA9C27EB6398FF4A7D2E26E7AB").put("title", "Show.S01E02.1080p").put("seeds", 12))).single()
        assertEquals("tv", eztv.kind)
        assertEquals(Scope.EPISODE, eztv.scope)

        val csv = toCsvHits(JSONArray().put(JSONObject().put("infohash", "C39FE3EEFBDB62DA9C27EB6398FF4A7D2E26E7AB").put("name", "The.Matrix.1999.1080p").put("seeders", 9).put("size_bytes", 3000))).single()
        assertEquals(9, csv.seeders)
        assertEquals(3000L, csv.size)

        val nyaa = toNyaaHits(
            "<item><title>[Sub] Show - 05 &amp; more</title><nyaa:infoHash>C39FE3EEFBDB62DA9C27EB6398FF4A7D2E26E7AB</nyaa:infoHash>" +
                "<nyaa:seeders>12</nyaa:seeders><nyaa:leechers>3</nyaa:leechers><nyaa:size>389.7 MiB</nyaa:size></item>",
        ).single()
        assertEquals("[Sub] Show - 05 & more", nyaa.name)
        assertEquals(12, nyaa.seeders)
        assertEquals(Math.round(389.7 * 1024 * 1024), nyaa.size)
    }

    @Test fun merging() {
        val merged = merge(listOf(listOf(hit("a", seeders = 5, size = 100), hit("b", seeders = 1)), listOf(hit("a", seeders = 900))))
        assertEquals(2, merged.size)
        assertEquals(900, merged.first { it.id == "a" }.seeders)
        assertEquals(100L, merged.first { it.id == "a" }.size)
    }

    @Test fun titleMatching() {
        assertEquals("Dune", aliasOf("Dune: Part One"))
        assertEquals("Star Wars", aliasOf("Star Wars - A New Hope"))
        assertNull(aliasOf("The Matrix"))
        assertNull(aliasOf("Spider-Man"))
        assertEquals("Spider-Man", aliasOf("Spider-Man: No Way Home"))

        assertTrue(namesTitle("Interstellar.2014.PROPER.IMAX.2160p", "Interstellar"))
        assertTrue(namesTitle("Interstellar (2014) [1080p] [WEBRip]", "Interstellar"))
        assertFalse(namesTitle("Imdb top 263 movies hindi english dual audio", "Interstellar"))
        assertFalse(namesTitle("IMDB Top 250 - 2024 Edition", "Interstellar"))
        assertTrue(namesTitle("Dune (2021) [1080p] [WEBRip]", aliasOf("Dune: Part One")!!))
        assertFalse(namesTitle("The Matrix Reloaded 2003", "The Matrix Revolutions"))
        assertTrue(namesTitle("The.Matrix.1999.1080p", "The Matrix"))

        assertTrue(sameTitle("House.S01E01.1080p", "House"))
        assertTrue(sameTitle("House M.D. S01 1080p", "House"))
        assertFalse(sameTitle("House.of.the.Dragon.S01E01", "House"))
    }

    @Test fun picking() {
        assertEquals(0, pickFile(listOf(f(0, "Movie.1080p.mkv", 8_000_000_000), f(1, "sample.mkv", 40_000_000), f(2, "Movie.nfo", 900, false))))
        val pack = listOf(f(0, "Show.S01E01.1080p.mkv", 2_000_000_000), f(1, "Show.S01E02.1080p.mkv", 1_000_000_000), f(2, "Show.S01E03.1080p.mkv", 3_000_000_000))
        assertEquals(1, pickFile(pack, "S01E02"))
        assertEquals(1, pickFile(listOf(f(0, "Show.1x02.mkv", 5), f(1, "Show.S1E2.mkv", 9)), "S01E02"))
        assertNull(pickFile(pack, "S09E09"))
        assertEquals(0, pickFile(listOf(f(0, "episode.mkv", 500)), "S09E09"))
        assertNull(pickFile(listOf(f(0, "readme.txt", 10, false))))

        val season = listOf(f(0, "Show.S01E10.mkv", 100), f(1, "Show.S01E02.mkv", 100), f(2, "Show.S01E01.mkv", 100), f(3, "Show.S02E01.mkv", 100), f(4, "sample.mkv", 5))
        assertEquals("Show.S01E02.mkv", nextEpisode(season, 2)?.name)
        assertEquals("Show.S02E01.mkv", nextEpisode(season, 0)?.name)
        assertNull(nextEpisode(season, 3))
        assertNull(nextEpisode(listOf(f(0, "Dune.2021.1080p.mkv", 100)), 0))
        assertEquals("Show.S01E01.mkv", prevEpisode(season, 1)?.name)
        assertEquals("Show.S01E10.mkv", prevEpisode(season, 3)?.name)
        assertNull(prevEpisode(season, 2))
    }

    @Test fun titles() {
        assertEquals("Big Buck Bunny 2008", titleOf("magnet:?xt=urn:btih:ABC123&dn=Big+Buck+Bunny%202008"))
        assertEquals("abcdef0123456789", titleOf("magnet:?xt=urn:btih:abcdef0123456789aabb"))
        assertEquals("My Movie.torrent", titleOf("file:///data/cache/My%20Movie.torrent"))
        assertEquals("thing.torrent", titleOf("https://x.org/dl/thing.torrent?key=1"))
        assertEquals("100%", titleOf("magnet:?xt=urn:btih:ABC&dn=100%"))
        assertEquals(hueOf("Spider-Man"), hueOf("Spider-Man"))
        assertTrue(hueOf("Spider-Man") != hueOf("Spider-Man 2"))

        assertEquals("The Matrix", searchableName("The.Matrix.1999.1080p.BluRay.x264-GRP"))
        assertEquals("Ted Lasso", searchableName("Ted Lasso S01E02 1080p WEB-DL"))
        assertEquals("Some Show", searchableName("Some Show Season 2 Complete"))
        assertEquals("Dune Part Two", searchableName("Dune Part Two (2024) 2160p.mkv"))
        assertEquals("Arrival", searchableName("Arrival"))
        assertEquals("Attack On Titan - 01", searchableName("[Anime Time] Attack On Titan - 01.mkv"))
        assertEquals("1999", yearOf("The.Matrix.1999.1080p"))
        assertNull(yearOf("Ted Lasso S01E02"))
        assertTrue(isSeries("Ted Lasso S01E02 1080p"))
        assertTrue(isSeries("Show Season 2 Complete"))
        assertFalse(isSeries("The.Matrix.1999.1080p"))
    }

    @Test fun magnets() {
        val tr = listOf("udp://a/announce", "udp://b/announce")
        assertEquals("magnet:?xt=urn:btih:ABC&tr=udp%3A%2F%2Fa%2Fannounce&tr=udp%3A%2F%2Fb%2Fannounce", withTrackers("magnet:?xt=urn:btih:ABC", tr))
        val has = "magnet:?xt=urn:btih:ABC&tr=udp%3A%2F%2Fa%2Fannounce"
        assertEquals("$has&tr=udp%3A%2F%2Fb%2Fannounce", withTrackers(has, tr))
        assertEquals(has, withTrackers(has, listOf("udp://a/announce")))
        assertEquals("https://x.org/a.torrent", withTrackers("https://x.org/a.torrent", tr))
        assertTrue(isTorrentSource("magnet:?xt=urn:btih:ABC"))
        assertTrue(isTorrentSource("content://com.android.providers.downloads/1"))
        assertTrue(isTorrentSource("file:///storage/x.torrent"))
        assertTrue(isTorrentSource("https://x.org/a.torrent?k=1"))
        assertFalse(isTorrentSource("https://example.com/page"))
    }

    @Test fun formatting() {
        assertEquals("0:00", clock(0.0))
        assertEquals("1:15", clock(75.0))
        assertEquals("1:01:15", clock(3675.0))
        assertEquals("0:00", clock(-5.0))
        assertEquals("2.5 MB/s", speed(2_500_000))
        assertEquals("45 kB/s", speed(45_000))
        assertEquals("almost done", left(20.0))
        assertEquals("11m left", left(640.0))
        assertEquals("1h 0m left", left(3600.0))
        assertEquals("2h 5m left", left(7500.0))
        assertEquals("just now", ago(1000, now = 1000))
        assertEquals("1h ago", ago(0, now = 90 * 60_000L))
    }

    @Test fun cinemetaMatching() {
        val t = { name: String, kind: String, year: String? -> Title("tt1", kind, name, year) }
        assertEquals("Dune", bestMatch(listOf(t("Dune", "movie", "1984"), t("Dune", "movie", "2021")), "Dune", "2021")?.year?.let { "Dune" })
        assertEquals("2021", bestMatch(listOf(t("Dune", "movie", "1984"), t("Dune", "movie", "2021")), "Dune", "2021")?.year)
        assertNull(bestMatch(listOf(t("Arrival of the Fittest", "movie", null)), "Arrival Day", null))
        val toGenres = topGenres(listOf(Title("a", "tv", "A", genres = listOf("Drama")), Title("b", "movie", "B", genres = listOf("Drama", "Crime"))))
        assertEquals("Drama", toGenres.first().first)
    }

    @Test fun subdlRows() {
        val subs = SubDl.toSubs(
            JSONArray()
                .put(JSONObject().put("release_name", "Film.2020.1080p").put("language", "en").put("url", "/subtitle/1.zip"))
                .put(JSONObject().put("name", "no url")),
        )
        assertEquals(1, subs.size)
        assertEquals("EN", subs[0].language)
        assertEquals("https://dl.subdl.com/subtitle/1.zip", subs[0].url)
        assertNull(subs[0].season)
    }

    @Test fun historyRoundTrip() {
        val item = Item(
            uri = "magnet:?xt=urn:btih:abc", title = "Show.S01E02", at = 5, torrentName = "Show",
            files = listOf(StoredFile(0, "Show.S01E02.mkv", 100, true, false, "Show/Show.S01E02.mkv")),
            lastFileIndex = 0, positions = mapOf(0 to 30.0), durations = mapOf(0 to 60.0),
            subtitles = mapOf(0 to listOf(SavedSub("/x.srt", "EN"))), imdb = "tt1", kind = "tv", titleName = "Show · S01E02", seeders = 84,
        )
        val back = Item.from(JSONObject(item.toJson().toString()))
        assertEquals(item, back)
        assertEquals(0.5, back.ratio, 1e-9)
        // The Expo build's JSON, as AsyncStorage held it.
        val legacy = Item.from(JSONObject("""{"uri":"u","title":"t","at":1,"positions":{"3":12.5},"durations":{"3":100}}"""))
        assertEquals(12.5, legacy.positions[3])
        assertNull(legacy.lastFileIndex)
        assertNull(legacy.seeders)
    }

    @Test fun trailersAndSorting() {
        assertEquals("abc", trailerOf(JSONObject("""{"trailerStreams":[{"ytId":"abc"}],"trailers":[{"type":"Trailer","source":"old"}]}""")))
        assertEquals("old", trailerOf(JSONObject("""{"trailers":[{"type":"Clip","source":"clip"},{"type":"Trailer","source":"old"}]}""")))
        assertNull(trailerOf(JSONObject("{}")))
        val t = listOf(Title("tt1", "movie", "b", year = "2001", rating = "7.1"), Title("tt2", "movie", "a"), Title("tt3", "movie", "C", year = "2020–2022", rating = "8"))
        assertEquals(listOf("tt3", "tt1", "tt2"), sortTitles(t, Sort.RATING).map { it.imdb })
        assertEquals(listOf("tt3", "tt1", "tt2"), sortTitles(t, Sort.YEAR).map { it.imdb })
        assertEquals(listOf("tt2", "tt1", "tt3"), sortTitles(t, Sort.TITLE).map { it.imdb })
        assertEquals(t, sortTitles(t, Sort.POPULAR))
    }

    @Test fun languages() {
        val tracks = listOf(Track(1, "Track 1 - [Japanese]"), Track(2, "English (Forced)"), Track(3, "English - SDH"), Track(4, "Director's commentary - it is"))
        assertEquals(3, trackForLang(tracks, "EN")?.id)
        assertEquals(1, trackForLang(tracks, "JA")?.id)
        assertNull(trackForLang(tracks, "IT"))
        assertEquals(2, trackForLang(listOf(Track(2, "eng forced")), "EN")?.id)
        assertEquals("Portuguese (Brazil)", langName("BR_PT"))
    }

    @Test fun backupFiles() {
        val good = """{"app":"watchly","version":1,"data":{"saved":"[]","settings":"{\"prepareMb\":4}","intros":"not json","bogus":"[]"}}"""
        assertEquals(listOf("saved" to "[]", "settings" to "{\"prepareMb\":4}"), Backup.parse(good))
        for (bad in listOf("nope", """{"app":"other","data":{}}""", """{"app":"watchly","data":{"intros":"x"}}""")) {
            assertTrue(runCatching { Backup.parse(bad) }.isFailure)
        }
    }

    @Test fun newEpisodeCounts() {
        fun ep(s: Int, e: Int, released: String?) = Episode("$s:$e", s, e, "E$e", null, null, released)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-06-01")!!.time
        val show = Title("tt9", "tv", "Show", episodes = listOf(ep(0, 1, "2020-01-01"), ep(1, 1, "2026-01-01"), ep(1, 2, "2026-05-01"), ep(1, 3, "2026-09-01"), ep(1, 4, null)))
        assertEquals(2, NewEpisodes.airedCount(show, now))
        val (first, base) = NewEpisodes.compare(listOf(show), emptyMap(), now)
        assertTrue(first.isEmpty())
        assertEquals(2, base["tt9"])
        val (later, _) = NewEpisodes.compare(listOf(show), mapOf("tt9" to 1), now)
        assertEquals(1, later.single().count)
    }

    @Test fun localVideos() {
        assertTrue(isLocalVideo("/storage/emulated/0/Movies/Watchly/Show/a.mkv"))
        assertTrue(isLocalVideo("file:///storage/x.MP4"))
        assertFalse(isLocalVideo("/data/user/0/app/files/imported/a.torrent"))
        assertFalse(isLocalVideo("magnet:?xt=urn:btih:abc&dn=a.mkv"))
    }

    @Test fun castAndAwards() {
        val answer = JSONObject(
            """{"d":[{"i":{"imageUrl":"https://m.media-amazon.com/images/M/b._V1_.jpg"},"id":"nm2","rank":40626},
               {"i":{"imageUrl":"https://m.media-amazon.com/images/M/a._V1_.jpg"},"id":"nm1","rank":208},
               {"id":"nm3","rank":5},{"i":{"imageUrl":"https://x/t.jpg"},"id":"tt9","rank":1}]}""",
        )
        assertEquals("https://m.media-amazon.com/images/M/a._V1_UY300_.jpg", photoFrom(answer))
        assertNull(photoFrom(JSONObject("""{"d":[{"id":"nm3","rank":5}]}""")))

        assertEquals(Awards("Nominated for 1 Primetime Emmy", 4, 16), awardsOf("Nominated for 1 Primetime Emmy. 4 wins & 16 nominations total"))
        assertEquals(Awards("Won 2 Oscars", 120, 200), awardsOf("Won 2 Oscars. 120 wins & 200 nominations total"))
        assertEquals(Awards(null, 1, 3), awardsOf("1 win & 3 nominations"))
    }

    @Test fun onePerTitle() {
        val items = listOf(
            Item("a", "Show.S01E01.720p", at = 1, imdb = "tt1", titleName = "Show · S01E01"),
            Item("b", "Show.S01E02.1080p", at = 5, imdb = "tt1", titleName = "Show · S01E02"),
            Item("c", "Film.2020", at = 3, titleName = "Film"),
            Item("d", "Film.2020.REPACK", at = 4, titleName = "film"),
            Item("magnet:x", "Unknown", at = 2),
        )
        assertEquals(listOf("b", "d", "magnet:x"), latestPerTitle(items).map { it.uri })
    }

    @Test fun anotherReleaseResumes() {
        fun f(name: String) = listOf(StoredFile(0, name, 1, true, false, name))
        val first = Item("a", "Film.720p", at = 1, imdb = "tt5", files = f("Film.720p.mkv"), lastFileIndex = 0, positions = mapOf(0 to 600.0), durations = mapOf(0 to 6000.0))
        val second = Item("b", "Film.1080p", at = 2, imdb = "tt5")
        // The newer release, opened and never started, does not hide the first.
        assertEquals("a", latestPerTitle(listOf(first, second)).single().uri)
        assertEquals(600.0 to 6000.0, carryOver(listOf(first, second), second, "Film.1080p.mkv"))
        // A series carries only to the same episode.
        val ep1 = Item("c", "Show.S01E01", at = 1, imdb = "tt6", files = f("Show.S01E01.mkv"), lastFileIndex = 0, positions = mapOf(0 to 300.0))
        val pack = Item("d", "Show.S01", at = 2, imdb = "tt6")
        assertEquals(300.0 to null, carryOver(listOf(ep1, pack), pack, "Show.S01E01.1080p.mkv"))
        assertNull(carryOver(listOf(ep1, pack), pack, "Show.S01E02.mkv"))
        // Watched through: start the new copy from the top.
        assertNull(carryOver(listOf(first.copy(positions = mapOf(0 to 5900.0)), second), second, "Film.1080p.mkv"))
    }

    @Test fun episodesWatched() {
        val files = listOf(StoredFile(0, "Show.S01E01.mkv", 1, true, false, null), StoredFile(1, "Show.S01E02.mkv", 1, true, false, null))
        val pack = Item("a", "Show.S01", at = 1, imdb = "tt6", files = files, positions = mapOf(0 to 1200.0, 1 to 600.0), durations = mapOf(0 to 1200.0, 1 to 2400.0))
        val single = Item("b", "Show.S01E02", at = 2, imdb = "tt6", files = listOf(StoredFile(0, "Show.S01E02.720p.mkv", 1, true, false, null)), positions = mapOf(0 to 1800.0), durations = mapOf(0 to 2400.0))
        val other = Item("c", "Other.S01E01", at = 3, imdb = "tt7", files = listOf(files[0]), positions = mapOf(0 to 10.0), durations = mapOf(0 to 100.0))
        // The furthest release wins; another show's history stays out.
        assertEquals(mapOf(Ep(1, 1) to 1.0, Ep(1, 2) to 0.75), episodeProgress(listOf(pack, single, other), "tt6"))
    }

    @Test fun introWindow() {
        val intro = Intro(60.0, 150.0)
        assertFalse(intro.covers(57.0))
        assertTrue(intro.covers(58.0))
        assertTrue(intro.covers(148.9))
        assertFalse(intro.covers(149.0))
    }

    @Test fun introDbAnswer() {
        assertEquals(Intro(445.0, 466.5), parseIntroDb("""{"imdb_id":"tt1","season":1,"episode":2,"intro":{"start_sec":445,"end_sec":466.5},"recap":{"start_sec":0,"end_sec":79}}"""))
        // Milliseconds only.
        assertEquals(Intro(60.0, 90.0), parseIntroDb("""{"intro":{"start_sec":null,"start_ms":60000,"end_ms":90000}}"""))
        // A recap but no intro, an empty window, junk.
        assertNull(parseIntroDb("""{"recap":{"start_sec":0,"end_sec":79}}"""))
        assertNull(parseIntroDb("""{"intro":{"start_sec":90,"end_sec":90}}"""))
        assertNull(parseIntroDb("not json"))
    }
}
