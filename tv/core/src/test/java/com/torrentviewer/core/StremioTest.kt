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
