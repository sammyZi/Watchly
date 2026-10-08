package app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaTest {
    @Test fun releaseNames() {
        assertEquals("The Matrix", Meta.searchableName("The.Matrix.1999.1080p.BluRay.x264-GRP"))
        assertEquals("Severance", Meta.searchableName("Severance.S02E05.2160p.WEB-DL"))
        assertEquals("Dune Part Two", Meta.searchableName("[YTS] Dune Part Two (2024) [1080p]"))
        assertEquals("1999", Meta.yearOf("The.Matrix.1999.1080p"))
        assertTrue(Meta.isSeries("Severance.S02E05.2160p"))
        assertFalse(Meta.isSeries("The.Matrix.1999.1080p"))
    }

    @Test fun matching() {
        val dunes = listOf(
            Meta.Hit("tt1", "Dune", "1984", "movie"),
            Meta.Hit("tt2", "Dune", "2021", "movie"),
            Meta.Hit("tt3", "Dune: Part Two", "2024", "movie"),
        )
        assertEquals("tt2", Meta.bestMatch(dunes, "Dune", "2021")?.id) // the year settles two films of one name
        assertEquals("tt1", Meta.bestMatch(dunes, "Dune", "1984")?.id)
        assertNull(Meta.bestMatch(listOf(Meta.Hit("tt9", "Matrix Resurrections", "2021", "movie")), "The Matrix", "1999")) // a missing word is no match
    }
}
