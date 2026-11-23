package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniListTest {
    // A season AniList keeps as two parts of 12 and 10, and one still airing (no count yet).
    private val split = listOf(Cour(1, 12), Cour(2, 10))

    @Test fun anEpisodeFindsItsPart() {
        assertEquals(1 to 1, courOf(split, 1))
        assertEquals(1 to 12, courOf(split, 12))
        assertEquals(2 to 1, courOf(split, 13))
        assertEquals(2 to 10, courOf(split, 22))
        // Past the counts: the last part takes it.
        assertEquals(2 to 13, courOf(split, 25))
        assertEquals(7 to 30, courOf(listOf(Cour(7, null)), 30))
        assertNull(courOf(emptyList(), 3))
    }

    @Test fun aPartsProgressIsTheSeasonsEpisodes() {
        assertEquals(listOf(1, 2, 3), episodesOf(split, 1, 3))
        assertEquals(listOf(13, 14), episodesOf(split, 2, 2))
        // A part before it with no count yet: nothing to place it by.
        assertEquals(emptyList<Int>(), episodesOf(listOf(Cour(1, null), Cour(2, 10)), 2, 2))
    }

    @Test fun theSignInLinkGivesItsToken() {
        val t = tokenFromRedirect("watchly://anilist#access_token=abc.def%3D&token_type=Bearer&expires_in=100", now = 1_000)!!
        assertEquals("abc.def=", t.access)
        assertEquals(101_000L, t.expires)
        assertNull(tokenFromRedirect("watchly://anilist#error=access_denied"))
    }

    @Test fun aRecapFilmOnTheSeriesTitleIsLeftOut() {
        val got = usable(listOf(AniEntry("tt5626028", 21459, 1, "TV"), AniEntry("tt5626028", 169402, 0, "MOVIE"), AniEntry("tt1517268", 5, -1, "MOVIE")))
        assertEquals(listOf(21459, 5), got.map { it.id })
    }

    @Test fun anAiringNoticeReadsWhole() {
        assertEquals("Episode 5 of Frieren aired.", airingText(listOf("Episode ", " of ", " aired."), 5, "Frieren"))
        assertEquals("Episode 5 of Frieren aired.", airingText(emptyList(), 5, "Frieren"))
    }
}
