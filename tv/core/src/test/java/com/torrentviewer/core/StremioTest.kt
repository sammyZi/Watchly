package com.torrentviewer.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioTest {
    @Test fun linkSignIn() {
        // Both shapes link.stremio.com answers create with.
        val c = parseLink("""{"success":true,"code":"Q0X1","link":"https://link.stremio.com/Q0X1","result":{"success":true,"code":"Q0X1","link":"https://link.stremio.com/Q0X1"}}""")
        assertEquals("Q0X1", c.userCode)
        assertEquals("https://link.stremio.com/Q0X1", c.uriComplete)
        assertEquals("34GW", parseLink("""{"result":{"success":true,"code":"34GW","link":"https://link.stremio.com/34GW"}}""").deviceCode)
        // Waiting is an error; approved carries the key.
        assertNull(parseLinkRead("""{"result":null,"error":{"code":101,"message":"Invalid or expired token"}}"""))
        assertEquals("abc", parseLinkRead("""{"result":{"authKey":"abc"}}"""))
        assertNull(parseLinkRead("not json"))
    }

    @Test fun library() {
        val lib = parseLibrary(
            """{"result":[
              {"_id":"tt1","name":"Film","type":"movie","removed":false,"temp":false,"_mtime":"2026-01-02T00:00:00.000Z",
               "state":{"timeOffset":0,"duration":0,"timesWatched":1,"flaggedWatched":1,"lastWatched":"2026-01-02T00:00:00.000Z"}},
              {"_id":"tt2","name":"Show","type":"series","removed":false,"temp":true,"_mtime":"2026-01-03T00:00:00.000Z",
               "state":{"timeOffset":600000,"duration":2400000,"video_id":"tt2:3:4","lastWatched":"2026-01-03T00:00:00.000Z"}},
              {"_id":"tt3","name":"Gone","type":"movie","removed":true,"temp":false,"state":{"timeOffset":0,"duration":0}},
              {"_id":"tt4","name":"Half film","type":"movie","removed":false,"temp":false,"_mtime":"2026-01-01T00:00:00.000Z",
               "state":{"timeOffset":3000000,"duration":6000000,"lastWatched":"2026-01-01T00:00:00.000Z"}}
            ]}""",
        )
        assertEquals(setOf("tt1"), lib.films)
        // Newest first; a show's spot is its episode's.
        assertEquals(listOf("tt2", "tt4"), lib.paused.map { it.imdb })
        assertEquals(Ep(3, 4), lib.paused[0].ep)
        assertEquals(25.0, lib.pausedAt("tt2", Ep(3, 4))!!, 0.001)
        assertEquals(600.0, lib.pausedSeconds("tt2", Ep(3, 4))!!, 0.001)
        // The library: not removed, not only seen once (temp).
        assertEquals(listOf("tt1", "tt4"), lib.seen.map { it.imdb })
        assertEquals(StremioLibrary(), parseLibrary("{}"))
    }

    @Test fun earlierHistoryGoesUp() {
        val t = isoMs("2026-01-10T00:00:00.000Z")!!
        fun played(uri: String, imdb: String?, kind: String, file: String, at: Double, of: Double, opened: Long = t) = Item(
            uri = uri, title = file, at = opened, lastFileIndex = 0,
            files = listOf(StoredFile(0, file, 1, true, false, file)),
            positions = mapOf(0 to at), durations = mapOf(0 to of), imdb = imdb, kind = kind, titleName = "Name",
        )
        val items = listOf(
            played("a", "tt1", "movie", "Film.mkv", 6000.0, 6000.0), // finished
            played("b", "tt2", "tv", "Show.S02E03.mkv", 600.0, 2400.0), // part-way
            played("c", "tt3", "movie", "Old.mkv", 100.0, 6000.0, opened = t - 1000), // Stremio's is newer
            played("d", null, "movie", "pasted.mkv", 100.0, 6000.0), // no id
            played("e", "tt5", "movie", "Barely.mkv", 10.0, 6000.0), // not started
            played("f", "tt6", "tv", "nonumber.mkv", 600.0, 2400.0), // which episode?
        )
        val remote = mapOf("tt3" to JSONObject("""{"_id":"tt3","state":{"lastWatched":"2026-01-10T00:00:00.000Z","timeOffset":5}}"""))
        val up = backlog(items, remote).associateBy { it.getString("_id") }
        assertEquals(setOf("tt1", "tt2"), up.keys)
        val film = up.getValue("tt1").getJSONObject("state")
        assertEquals(1, film.getInt("timesWatched"))
        assertEquals(0, film.getInt("timeOffset"))
        assertEquals("2026-01-10T00:00:00.000Z", film.getString("lastWatched"))
        val show = up.getValue("tt2")
        assertEquals("series", show.getString("type"))
        assertEquals("tt2:2:3", show.getJSONObject("state").getString("video_id"))
        assertEquals(600_000L, show.getJSONObject("state").getLong("timeOffset"))
        // Sent once: the same play against what it wrote is not sent again.
        assertTrue(backlog(items, up).none { it.getString("_id") in up.keys })
        assertEquals(t, isoMs("2026-01-10T00:00:00Z"))
    }

    @Test fun myListGoesIntoTheLibraryOnce() {
        fun saved(id: String, kind: String = "movie", list: String = "later") = Saved(id, kind, "T$id", null, null, null, list, 0)
        val remote = mapOf(
            "tt1" to JSONObject("""{"_id":"tt1","removed":false,"temp":false}"""), // already in the library
            "tt2" to JSONObject("""{"_id":"tt2","removed":false,"temp":true,"state":{"timeOffset":7}}"""), // only seen once
            "tt3" to JSONObject("""{"_id":"tt3","removed":true,"temp":false}"""), // taken out there…
        )
        val played = mapOf("tt5" to libraryItem(null, "tt5", "Played", "series", null))
        val out = withMyList(
            played, remote,
            listOf(saved("tt1"), saved("tt2"), saved("tt3"), saved("tt4", "tv"), saved("tt5", "tv"), saved("tt6", list = "favorite")),
            sent = setOf("tt3"), // …after it was sent: stays out
        )
        assertEquals(setOf("tt2", "tt4", "tt5"), out.keys)
        for (id in listOf("tt2", "tt4", "tt5")) {
            assertFalse(out.getValue(id).getBoolean("temp"))
            assertFalse(out.getValue(id).getBoolean("removed"))
        }
        // What was there is kept; a series is a series.
        assertEquals(7, out.getValue("tt2").getJSONObject("state").getInt("timeOffset"))
        assertEquals("series", out.getValue("tt4").getString("type"))
    }

    @Test fun removingAnAddonLeavesTheRestAlone() {
        val all = listOf(
            JSONObject("""{"transportUrl":"https://v3-cinemeta.strem.io/manifest.json","flags":{"official":true,"protected":true},"manifest":{"id":"c"}}"""),
            JSONObject("""{"transportUrl":"https://torrentio.strem.fun/sort=qualitysize/manifest.json","flags":{},"manifest":{"id":"t","x":1}}"""),
            JSONObject("""{"transportUrl":"https://subs.example/manifest.json","manifest":{"id":"s"}}"""),
        )
        val keep = withoutAddon(all, "https://torrentio.strem.fun/sort=qualitysize")
        assertEquals(listOf("c", "s"), keep.map { it.getJSONObject("manifest").getString("id") })
        // Untouched, field for field.
        assertEquals(all[2].toString(), keep[1].toString())
        // Stremio's own stays, even asked for by its address.
        assertEquals(3, withoutAddon(all, "https://v3-cinemeta.strem.io").size)
        assertEquals(3, withoutAddon(all, "https://other.example").size)
    }

    @Test fun episodeIds() {
        assertEquals(Ep(1, 2), epOfVideo("tt0944947:1:2"))
        assertEquals(Ep(1, 5), epOfVideo("kitsu:123:1:5"))
        assertNull(epOfVideo("tt0944947"))
        assertNull(epOfVideo(null))
    }

    @Test fun libraryItemsWrittenWhole() {
        // New: every field Stremio's apps read, typed, and only in Continue Watching.
        val fresh = libraryItem(null, "tt1", "Film", "movie", null, now = 0L) { it.put("timeOffset", 5000L) }
        assertEquals("tt1", fresh.getString("_id"))
        assertTrue(fresh.isNull("poster"))
        assertTrue(fresh.getBoolean("temp"))
        assertFalse(fresh.getBoolean("removed"))
        assertEquals("1970-01-01T00:00:00.000Z", fresh.getString("_mtime"))
        val s = fresh.getJSONObject("state")
        for (k in listOf("timeWatched", "overallTimeWatched", "timesWatched", "flaggedWatched", "duration")) assertEquals(0, s.getInt(k))
        assertEquals(5000L, s.getLong("timeOffset"))
        assertTrue(s.isNull("video_id"))
        assertFalse(s.getBoolean("noNotif"))
        assertFalse(fresh.getJSONObject("behaviorHints").getBoolean("hasScheduledVideos"))

        // Old: kept as it was but for the change; My List puts it in the library.
        val old = JSONObject("""{"_id":"tt2","name":"Show","type":"series","temp":true,"removed":false,"extra":7,"state":{"timeOffset":10,"timesWatched":2,"video_id":"tt2:1:1"}}""")
        val kept = libraryItem(old, "tt2", "x", "series", null, inLibrary = true) { it.put("timesWatched", it.optInt("timesWatched") + 1) }
        assertEquals(7, kept.getInt("extra"))
        assertEquals("Show", kept.getString("name"))
        assertEquals(3, kept.getJSONObject("state").getInt("timesWatched"))
        assertEquals("tt2:1:1", kept.getJSONObject("state").getString("video_id"))
        assertFalse(kept.getBoolean("temp"))
        assertFalse(kept.getBoolean("removed"))
        assertTrue(libraryItem(old, "tt2", "x", "series", null, inLibrary = false).getBoolean("removed"))
    }
}
