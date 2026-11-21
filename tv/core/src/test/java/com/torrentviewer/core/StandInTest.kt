package com.torrentviewer.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandInTest {
    private fun addon(url: String, catalogs: String) =
        toAddon(url, JSONObject("""{"name":"x","resources":["meta"],"types":["movie","series"],"catalogs":$catalogs}"""))!!

    @Test fun picksTheViewersOwnCatalogue() {
        val cinemeta = addon("https://v3-cinemeta.strem.io", """[{"type":"movie","id":"top"}]""")
        val aio = addon(
            "https://aio.example/abc",
            """[{"type":"movie","id":"search","extra":[{"name":"search","isRequired":true}]},
                {"type":"movie","id":"popular"},
                {"type":"movie","id":"tmdb.genre","extra":[{"name":"genre","isRequired":true}]},
                {"type":"series","id":"shows","extraSupported":["genre"]}]""",
        )
        // Cinemeta is the one refusing; a search-only list lists nothing on its own.
        assertEquals("popular", standIn(listOf(cinemeta, aio), "movie", null)?.second?.id)
        assertEquals("tmdb.genre", standIn(listOf(cinemeta, aio), "movie", "Action")?.second?.id)
        assertEquals("shows", standIn(listOf(aio), "series", "Drama")?.second?.id)
        assertNull(standIn(listOf(cinemeta), "movie", null))
        assertNull(standIn(listOf(aio.copy(on = false)), "movie", null))
    }
}
