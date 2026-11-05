package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers in the shapes Simkl's and MDBList's device flows document. */
class TrackersTest {
    @Test fun deviceCode() {
        val c = parseDeviceCode("""{"device_code":"d","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin","verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}""")
        assertEquals(DeviceCode("d", "BDWP-HQPK", "https://simkl.com/pin", "https://simkl.com/pin?user_code=BDWP-HQPK", 900, 5), c)
    }

    @Test fun deviceCodeWithoutCompleteLink() {
        assertEquals("https://x.test/pin", parseDeviceCode("""{"device_code":"d","user_code":"U","verification_uri":"https://x.test/pin"}""").uriComplete)
    }

    @Test fun tokens() {
        assertEquals(TrackerTokens("a", "r", 1000L + 2_592_000_000L), parseTokens("""{"access_token":"a","token_type":"Bearer","expires_in":2592000,"refresh_token":"r"}""", 1000L))
        assertNull(parseTokens("""{"error":"authorization_pending"}"""))
        assertNull(parseTokens("not json"))
    }

    @Test fun pollError() {
        assertEquals("slow_down", oauthError("""{"error":"slow_down"}"""))
        assertNull(oauthError("<html>"))
    }

    @Test fun filmBody() {
        assertEquals(
            """{"movies":[{"ids":{"imdb":"tt1"},"watched_at":"T"}]}""".let(::sorted),
            sorted(historyBody("tt1", null, "T").toString()),
        )
    }

    @Test fun episodeBody() {
        val show = historyBody("tt2", Ep(3, 7), "T").getJSONArray("shows").getJSONObject(0)
        assertEquals("tt2", show.getJSONObject("ids").getString("imdb"))
        val season = show.getJSONArray("seasons").getJSONObject(0)
        assertEquals(3, season.getInt("number"))
        val episode = season.getJSONArray("episodes").getJSONObject(0)
        assertEquals(7, episode.getInt("number"))
        assertEquals("T", episode.getString("watched_at"))
    }

    @Test fun animeFlagIsSimklsAlone() {
        assertTrue(historyBody("tt2", Ep(1, 1), "T", animeSeasons = true).getJSONArray("shows").getJSONObject(0).getBoolean("use_tvdb_anime_seasons"))
        assertFalse(historyBody("tt2", Ep(1, 1), "T").getJSONArray("shows").getJSONObject(0).has("use_tvdb_anime_seasons"))
    }

    @Test fun simklFilmsOnlyWhenCompleted() {
        val l = parseSimkl("""{"movies":[{"status":"completed","movie":{"ids":{"imdb":"tt1"}}},{"status":"plantowatch","movie":{"ids":{"imdb":"tt2"}}}]}""")
        assertEquals(setOf("tt1"), l.films)
    }

    @Test fun simklShowEpisodes() {
        val l = parseSimkl("""{"shows":[{"status":"watching","show":{"ids":{"imdb":"tt3"}},"seasons":[{"number":2,"episodes":[{"number":4},{"number":5,"watched":false}]}]}]}""")
        assertEquals(mapOf("tt3" to setOf(Ep(2, 4))), l.episodes)
    }

    @Test fun simklAnimeTakesTheMappedSeason() {
        val l = parseSimkl("""{"anime":[{"show":{"ids":{"imdb":"tt4"}},"seasons":[{"number":1,"episodes":[{"number":1,"tvdb":{"season":2,"episode":1}}]}]},
            {"status":"completed","anime_type":"movie","show":{"ids":{"imdb":"tt5"}}}]}""")
        assertEquals(mapOf("tt4" to setOf(Ep(2, 1))), l.episodes)
        assertEquals(setOf("tt5"), l.films)
    }

    @Test fun simklWithoutAnImdbIdOrAnswer() {
        assertEquals(StremioLibrary(), parseSimkl("""{"anime":[{"show":{"ids":{"tmdb":"1"}},"seasons":[]}]}"""))
        assertEquals(StremioLibrary(), parseSimkl("null"))
    }

    @Test fun mdblistPage() {
        val (l, next) = parseMdblist("""{"movies":[{"movie":{"ids":{"imdb":"tt1"}}}],"episodes":[{"episode":{"season":2,"number":5,"show":{"ids":{"imdb":"tt2"}}}}],"pagination":{"limit":100,"next_cursor":"c"}}""")
        assertEquals(setOf("tt1"), l.films)
        assertEquals(mapOf("tt2" to setOf(Ep(2, 5))), l.episodes)
        assertEquals("c", next)
        assertNull(parseMdblist("""{"movies":[],"pagination":{"next_cursor":null}}""").second)
    }

    @Test fun librariesMerge() {
        val a = StremioLibrary(films = setOf("tt1"), episodes = mapOf("tt3" to setOf(Ep(1, 1))))
        val b = StremioLibrary(films = setOf("tt2"), episodes = mapOf("tt3" to setOf(Ep(1, 2)), "tt4" to setOf(Ep(1, 1))))
        val both = a + b
        assertEquals(setOf("tt1", "tt2"), both.films)
        assertEquals(mapOf("tt3" to setOf(Ep(1, 1), Ep(1, 2)), "tt4" to setOf(Ep(1, 1))), both.episodes)
    }

    @Test fun savedLibraryRoundTrips() {
        val l = StremioLibrary(films = setOf("tt1"), episodes = mapOf("tt3" to setOf(Ep(1, 1), Ep(2, 7))))
        assertEquals(l, parseWatched(watchedJson(l)))
        assertEquals(StremioLibrary(), parseWatched(null))
        assertEquals(StremioLibrary(), parseWatched("broken"))
    }

    /** JSON key order is not part of the shape. */
    private fun sorted(json: String) = json.toCharArray().sorted().joinToString("")
}
