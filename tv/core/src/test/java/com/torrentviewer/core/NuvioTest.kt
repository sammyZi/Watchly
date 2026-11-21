package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioTest {
    @Test fun session() {
        val s = parseNuvioSession("""{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"email":"x@y.z"}}""", now = 1000)!!
        assertEquals(3_601_000L, s.expires)
        assertEquals("x@y.z", s.email)
        assertNull(parseNuvioSession("""{"error":"invalid_grant"}"""))
        assertEquals("Invalid login credentials", nuvioError("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""))
    }

    @Test fun library() {
        val progress = """[
            {"content_id":"tt0137523","content_type":"movie","video_id":"tt0137523","season":null,"episode":null,"position":3600000,"duration":7920000,"last_watched":200},
            {"content_id":"tt0903747","content_type":"series","video_id":"tt0903747:1:2","season":1,"episode":2,"position":10,"duration":3480000,"last_watched":300},
            {"content_id":"tmdb:1396","content_type":"series","video_id":"tmdb:1396:1:1","season":1,"episode":1,"position":1800000,"duration":3480000,"last_watched":400}
        ]"""
        val watched = """[
            {"content_id":"tt0137523","content_type":"movie","title":"Fight Club","season":null,"episode":null,"watched_at":100},
            {"content_id":"tt0903747","content_type":"series","season":1,"episode":1,"watched_at":150}
        ]"""
        val lib = """[{"content_id":"tt0137523","content_type":"movie","name":"Fight Club","release_info":"1999","added_at":50}]"""
        val l = parseNuvio(progress, watched, lib)
        assertEquals(setOf("tt0137523"), l.films)
        assertEquals(setOf(Ep(1, 1)), l.episodes["tt0903747"])
        // Barely started and tmdb-only ids are left out; the rest is named from the library.
        assertEquals(listOf("tt0137523"), l.paused.map { it.imdb })
        assertEquals("Fight Club", l.paused[0].title)
        assertEquals(3600.0, l.paused[0].seconds!!, 0.01)
        // Left part-way after it was finished: not finished any more.
        assertNull(l.finishedAt["tt0137523"])
        assertEquals(150L, l.finishedAt["tt0903747"])
        assertEquals("movie", l.seen.single().kind)
        assertTrue(parseNuvio("oops", "[]", "[]").paused.isEmpty())
        assertEquals("tt1_s2e3", nuvioKey("tt1", Ep(2, 3)))
    }

    @Test fun whatNuvioHasntGotGoesUp() {
        val t = 1_000_000L
        fun played(uri: String, imdb: String?, kind: String, file: String, at: Double, of: Double) = Item(
            uri = uri, title = file, at = t, lastFileIndex = 0,
            files = listOf(StoredFile(0, file, 1, true, false, file)),
            positions = mapOf(0 to at), durations = mapOf(0 to of), imdb = imdb, kind = kind, titleName = "Name",
        )
        val items = listOf(
            played("a", "tt1", "movie", "Film.mkv", 6000.0, 6000.0), // finished, Nuvio never heard
            played("b", "tt2", "tv", "Show.S02E03.mkv", 600.0, 2400.0), // part-way
            played("c", "tt3", "movie", "Seen.mkv", 6000.0, 6000.0), // finished, Nuvio has it
            played("d", "tt4", "movie", "Kept.mkv", 600.0, 6000.0), // part-way, Nuvio has this spot
            played("e", null, "movie", "Link.mkv", 6000.0, 6000.0), // no id: can't be placed
        )
        val there = StremioLibrary(films = setOf("tt3"), paused = listOf(Paused("tt4", "Kept", null, null, 10.0, 600.0, t)))
        val (watched, progress) = nuvioBacklog(items, there)
        assertEquals(listOf("tt1"), watched.map { it.getString("content_id") })
        assertEquals(t, watched[0].getLong("watched_at"))
        assertEquals(listOf("tt2"), progress.map { it.getString("content_id") })
        assertEquals("tt2:2:3", progress[0].getString("video_id"))
        assertEquals(600_000L, progress[0].getLong("position"))
        // Sent once: pulled again with it there, nothing more goes.
        assertTrue(nuvioBacklog(items.take(1), StremioLibrary(films = setOf("tt1"))).first.isEmpty())
    }

    @Test fun profiles() {
        val p = parseNuvioProfiles("""[{"profile_index":2,"name":"Kids","avatar_color_hex":"#FF5722","uses_primary_addons":true},{"profile_index":1,"name":"Main"}]""")
        assertEquals(listOf("Main", "Kids"), p.map { it.name })
        assertTrue(p[1].usesPrimaryAddons)
        assertTrue(parseNuvioProfiles("nope").isEmpty())
    }

    @Test fun paging() = kotlinx.coroutines.runBlocking {
        // Full pages keep going; the first short one ends it.
        val asked = mutableListOf<Int>()
        val all = allPages(2) { i -> asked += i; listOf("[1,2]", "[3,4]", "[5]")[i] }
        assertEquals("[1,2,3,4,5]", all)
        assertEquals(listOf(0, 1, 2), asked)
        assertNull(allPages(2) { null })
    }
}
