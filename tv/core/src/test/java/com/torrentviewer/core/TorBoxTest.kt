package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers in the shapes TorBox's v1 API gives them. */
class TorBoxTest {
    private val hash = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"

    @Test fun cachedAsList() {
        val json = """{"success":true,"detail":"Found cached torrents.","data":[
            {"name":"Big Buck Bunny","size":276445467,"hash":"${hash.uppercase()}",
             "files":[{"name":"Big Buck Bunny/Big Buck Bunny.mp4","size":276134947},{"name":"Big Buck Bunny/poster.jpg","size":310380}]}]}"""
        val found = parseCached(json)
        assertEquals(setOf(hash), found.keys)
        assertEquals(TorBoxFile(-1, "Big Buck Bunny/Big Buck Bunny.mp4", 276134947), found[hash]!!.first())
        assertEquals("Big Buck Bunny.mp4", found[hash]!!.first().shortName)
    }

    @Test fun cachedAsObject() {
        val found = parseCached("""{"success":true,"data":{"$hash":{"name":"x","size":1,"hash":"$hash"}}}""")
        assertEquals(setOf(hash), found.keys)
        assertTrue(found[hash]!!.isEmpty())
    }

    @Test fun nothingCached() {
        assertTrue(parseCached("""{"success":true,"detail":"No cached torrents found.","data":null}""").isEmpty())
        assertTrue(parseCached("""{"success":true,"data":[]}""").isEmpty())
        assertTrue(parseCached("<html>bad gateway</html>").isEmpty())
    }

    @Test fun myList() {
        val one = """{"success":true,"data":{"id":42,"hash":"$hash","name":"Big Buck Bunny","download_present":true,"download_finished":true,
            "files":[{"id":0,"name":"Big Buck Bunny/Big Buck Bunny.mp4","short_name":"Big Buck Bunny.mp4","size":276134947}]}}"""
        val t = parseMyList(one).single()
        assertEquals(42, t.id)
        assertTrue(t.ready)
        assertEquals(TorBoxFile(0, "Big Buck Bunny/Big Buck Bunny.mp4", 276134947), t.files.single())
        // Still downloading on TorBox's side: not something to stream from yet.
        val all = parseMyList("""{"success":true,"data":[{"id":7,"hash":"AB","name":"y","download_present":false,"download_finished":false}]}""")
        assertEquals(listOf(TorBoxTorrent(7, "ab", "y", false, emptyList())), all)
    }

    @Test fun errors() {
        assertEquals("Invalid API key.", torboxError("""{"success":false,"error":"AUTH_ERROR","detail":"Invalid API key.","data":null}"""))
        assertNull(torboxError("""{"success":true,"data":"https://store.example/file.mkv"}"""))
        assertNull(torboxError("not json"))
    }

    @Test fun matching() {
        val files = listOf(
            TorBoxFile(3, "Show/Show.S01E01.mkv", 1000),
            TorBoxFile(4, "Show/Show.S01E02.mkv", 1000),
            TorBoxFile(5, "Show/Sample/sample.mkv", 20),
        )
        // libtorrent's index can differ from TorBox's (padding files): by name and size, never by index.
        assertEquals(4, matchFile(files, "Show\\Show.S01E02.mkv", 1000)?.id)
        assertEquals(5, matchFile(files, "renamed.mkv", 20)?.id)
        assertEquals(3, matchFile(files, "Show.S01E01.mkv", 999)?.id)
        assertNull(matchFile(files, "other.mkv", 7))
    }

    @Test fun hashes() {
        assertEquals(hash, magnetHash("magnet:?xt=urn:btih:${hash.uppercase()}&dn=Big+Buck+Bunny"))
        assertNull(magnetHash("magnet:?xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4"))
        assertNull(magnetHash("/storage/emulated/0/Download/film.torrent"))
    }

    @Test fun plans() {
        assertEquals("Pro", torboxPlan(2))
        assertNull(torboxPlan(9))
    }
}
