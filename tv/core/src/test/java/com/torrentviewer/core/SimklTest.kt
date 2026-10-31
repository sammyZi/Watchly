package com.torrentviewer.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answers below are Simkl's own examples from its AUTH V2 device-flow docs. */
class SimklTest {
    @Test fun deviceCode() {
        val c = parseDevice(
            """{"device_code":"dc","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
               "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}""",
        )
        assertEquals(DeviceCode("dc", "BDWP-HQPK", "https://simkl.com/pin", "https://simkl.com/pin?user_code=BDWP-HQPK", 900, 5), c)
        // Only the essentials: the rest falls back to Simkl's documented defaults.
        val bare = parseDevice("""{"device_code":"dc","user_code":"AAAA-BBBB"}""")
        assertEquals("https://simkl.com/pin?user_code=AAAA-BBBB", bare.uriComplete)
        assertEquals(5, bare.interval)
    }

    @Test fun polling() {
        val ok = """{"access_token":"simkl_at_x","refresh_token":"simkl_rt_y","expires_in":604800,"scope":"media:read media:write","token_type":"Bearer"}"""
        assertEquals(Poll.Done(SimklTokens("simkl_at_x", "simkl_rt_y", 1000 + 604_800_000L)), parsePoll(200, ok, 1000))
        assertEquals(Poll.Pending, parsePoll(400, """{"error":"authorization_pending"}""", 0))
        assertEquals(Poll.SlowDown, parsePoll(400, """{"error":"slow_down"}""", 0))
        assertEquals(Poll.Expired, parsePoll(400, """{"error":"expired_token"}""", 0))
        assertTrue(parsePoll(401, """{"error":"invalid_client"}""", 0) is Poll.Failed)
        assertTrue(parsePoll(502, "<html>bad gateway</html>", 0) is Poll.Failed)
        // A mistyped scope comes back read-only rather than refused: caught before the first write.
        assertTrue(parsePoll(200, ok.replace("media:read media:write", "media:read"), 0) is Poll.Failed)
    }

    @Test fun tokensRoundTrip() {
        val t = SimklTokens("a", "r", 42)
        assertEquals(t, SimklTokens.from(t.json()))
        assertNull(SimklTokens.from("not json"))
    }

    @Test fun history() {
        val film = JSONObject(historyBody("tt1201607", null, null)!!)
        assertEquals("tt1201607", film.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getString("imdb"))
        val show = JSONObject(historyBody("tt0944947", 3, 9)!!).getJSONArray("shows").getJSONObject(0)
        assertEquals("tt0944947", show.getJSONObject("ids").getString("imdb"))
        val season = show.getJSONArray("seasons").getJSONObject(0)
        assertEquals(3, season.getInt("number"))
        assertEquals(9, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
        // Kitsu and other non-IMDb ids aren't something Simkl's history takes by this key.
        assertNull(historyBody("kitsu:1376", 1, 1))
    }

    @Test fun backlog() {
        fun item(uri: String, imdb: String?, kind: String, file: String, at: Double, of: Double) = Item(
            uri, file, 0, files = listOf(StoredFile(0, file, 1, true, false, null)), lastFileIndex = 0,
            positions = mapOf(0 to at), durations = mapOf(0 to of), imdb = imdb, kind = kind,
        )
        val local = localWatched(
            listOf(
                item("a", "tt1", "movie", "Film.2019.mkv", 95.0, 100.0),
                item("b", "tt2", "movie", "Half.mkv", 50.0, 100.0),
                item("c", "tt3", "tv", "Show.S02E05.mkv", 99.0, 100.0),
                item("d", "tt4", "tv", "One Piece - 1071.mkv", 99.0, 100.0),
                item("e", null, "movie", "Magnet.mkv", 99.0, 100.0),
            ),
        )
        // Half-watched isn't watched; an episode known only by its number through the show can't be placed.
        assertEquals(setOf("tt1"), local.films)
        assertEquals(mapOf("tt3" to setOf(Ep(2, 5))), local.episodes)

        val body = JSONObject(historyBody(setOf("tt1", "kitsu:9"), mapOf("tt3" to setOf(Ep(2, 6), Ep(1, 1), Ep(2, 5))))!!)
        assertEquals(1, body.getJSONArray("movies").length())
        val seasons = body.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons")
        assertEquals(1, seasons.getJSONObject(0).getInt("number"))
        assertEquals(listOf(5, 6), seasons.getJSONObject(1).getJSONArray("episodes").objects().map { it.getInt("number") })
        assertNull(historyBody(emptySet(), emptyMap()))
    }

    @Test fun pausedBacklogSendsEachHalfWatchedTitleOnce() {
        fun item(uri: String, imdb: String?, kind: String, file: String, ratio: Double, at: Long) = Item(
            uri, file, at, files = listOf(StoredFile(0, file, 1, true, false, null)), lastFileIndex = 0,
            positions = mapOf(0 to ratio * 100), durations = mapOf(0 to 100.0), imdb = imdb, kind = kind, titleName = file,
        )
        val items = listOf(
            item("a", "tt1", "movie", "Shutter Island", 0.6, 3),
            item("b", "tt2", "movie", "Ready Player One", 0.4, 2),
            item("c", "tt3", "movie", "Finished", 0.95, 1),
            item("d", null, "movie", "No id", 0.5, 1),
            item("e", "tt4", "tv", "Silo.S02E06.mkv", 0.3, 1),
            item("f", "tt5", "movie", "Already paused", 0.5, 1),
        )
        val simkl = SimklLibrary(paused = listOf(Paused("tt5", "Already paused", null, null, 50.0)))
        val backlog = pausedBacklog(items, simkl)
        assertEquals(listOf("tt1", "tt2", "tt4"), backlog.map { it.imdb })
        assertEquals(60.0, backlog[0].progress, 1e-9)
        assertEquals(Ep(2, 6), backlog[2].ep)
        // Once Simkl has them, nothing is left to send.
        assertTrue(pausedBacklog(items, simkl.copy(paused = simkl.paused + backlog)).isEmpty())
    }

    @Test fun onlyWhatSimklLacks() {
        val simkl = SimklLibrary(films = setOf("tt1"), episodes = mapOf("tt3" to setOf(Ep(1, 1))))
        val local = SimklLibrary(films = setOf("tt1", "tt2"), episodes = mapOf("tt3" to setOf(Ep(1, 1), Ep(1, 2)), "tt4" to setOf(Ep(1, 1))))
        val missing = simkl.missing(local)
        assertEquals(setOf("tt2"), missing.films)
        assertEquals(mapOf("tt3" to setOf(Ep(1, 2)), "tt4" to setOf(Ep(1, 1))), missing.episodes)
        // In sync: nothing to send.
        assertEquals(SimklLibrary(), local.missing(local))
        assertEquals(mapOf("tt3" to setOf(Ep(1, 1), Ep(1, 2))), simkl.episodes.plusSets(mapOf("tt3" to setOf(Ep(1, 2)))))
    }

    @Test fun library() {
        val l = parseLibrary(
            """{"movies":[{"status":"completed","movie":{"title":"Parasite","ids":{"imdb":"tt6751668"}}},
                         {"status":"plantowatch","movie":{"title":"Later","ids":{"imdb":"tt0000001"}}}],
               "shows":[{"status":"watching","show":{"title":"The Walking Dead","ids":{"imdb":"tt1520211"}},
                         "seasons":[{"number":1,"episodes":[{"number":2},{"number":3}]}]}],
               "anime":[{"status":"watching","show":{"ids":{"mal":"52991"}},"seasons":[{"number":1,"episodes":[{"number":1}]}]}]}""",
        )
        // Plan to watch isn't watched; an anime with no IMDb id has nothing to match Cinemeta by.
        assertEquals(setOf("tt6751668"), l.films)
        assertEquals(mapOf("tt1520211" to setOf(Ep(1, 2), Ep(1, 3))), l.episodes)
        assertEquals(SimklLibrary(), parseLibrary("<html>"))
    }

    @Test fun libraryKeepsTitlesForTheLibraryScreen() {
        val l = parseLibrary(
            """{"movies":[{"status":"completed","last_watched_at":"2026-05-01T10:00:00Z","movie":{"title":"Cars","year":2006,"ids":{"imdb":"tt0317219"}}},
                         {"status":"plantowatch","movie":{"title":"Later","ids":{"imdb":"tt0000001"}}}],
               "shows":[{"status":"watching","last_watched_at":"2026-06-01T10:00:00Z","last_watched":"S02E05","show":{"title":"Silo","year":2023,"ids":{"imdb":"tt14688458"}},
                         "seasons":[{"number":2,"episodes":[{"number":5}]}]},
                        {"status":"plantowatch","show":{"title":"Someday","ids":{"imdb":"tt0000002"}}}]}""",
        )
        // Newest first; plan-to-watch is a list, not history.
        assertEquals(listOf("Silo", "Cars"), l.seen.map { it.title })
        assertEquals("S02E05", l.seen[0].last)
        assertEquals("tv", l.seen[0].kind)

        val withPaused = l.copy(paused = listOf(Paused("tt6751668", "Parasite", "2019", null, 41.5), Paused("tt0317219", "Cars", "2006", null, 10.0)))
        val played = Item("magnet:x", "Silo.S02E05", 0, imdb = "tt14688458", kind = "tv")
        // What this device played is left out; paused ones lead; a title shows once.
        assertEquals(listOf("Parasite", "Cars"), withPaused.notIn(listOf(played)).map { it.title })
    }

    @Test fun playback() {
        val p = parsePlayback(
            """[{"id":1,"progress":63,"type":"episode","episode":{"season":1,"number":3},"anime":{"title":"Frieren","year":2023,"ids":{"imdb":"tt22248376"}}},
                {"id":2,"progress":41.5,"type":"movie","movie":{"title":"Parasite","year":2019,"ids":{"imdb":"tt6751668"}}},
                {"id":3,"progress":10,"type":"movie","movie":{"title":"No imdb","ids":{"tmdb":"1"}}}]""",
        )
        assertEquals(listOf(Paused("tt22248376", "Frieren", "2023", Ep(1, 3), 63.0), Paused("tt6751668", "Parasite", "2019", null, 41.5)), p)
        assertEquals("tv", p[0].kind)
        assertEquals(41.5, SimklLibrary(paused = p).pausedAt("tt6751668", null)!!, 0.0)
    }

    @Test fun scrobble() {
        val film = JSONObject(scrobbleBody("tt6751668", null, 41.456)!!)
        assertEquals(41.46, film.getDouble("progress"), 0.0)
        assertEquals("tt6751668", film.getJSONObject("movie").getJSONObject("ids").getString("imdb"))
        val ep = JSONObject(scrobbleBody("tt1520211", Ep(2, 5), 12.0)!!)
        assertEquals(2, ep.getJSONObject("episode").getInt("season"))
        assertEquals(5, ep.getJSONObject("episode").getInt("number"))
        assertNull(scrobbleBody("kitsu:1", null, 5.0))
    }

    @Test fun progressAndContinueFromSimkl() {
        val eps = (1..3).map { Episode("tt1:1:$it", 1, it, "E$it", null, null, null) }
        val show = Title("tt1", "tv", "Show", episodes = eps)
        val simkl = SimklLibrary(episodes = mapOf("tt1" to setOf(Ep(1, 1))), paused = listOf(Paused("tt1", "Show", null, Ep(1, 2), 40.0)))
        assertEquals(mapOf(Ep(1, 1) to 1.0, Ep(1, 2) to 0.4), episodeProgress(emptyList(), "tt1", simkl))
        // Paused beats "the one after the last watched"; with nothing paused, it's the next one.
        assertEquals(eps[1], continueEpisode(show, emptyList(), simkl))
        assertEquals(eps[1], continueEpisode(show, emptyList(), simkl.copy(paused = emptyList())))
        assertNull(continueEpisode(show, emptyList()))
    }
}
