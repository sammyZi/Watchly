package com.torrentviewer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonWatchedTest {
    private fun ep(season: Int, n: Int, released: String? = "2020-01-01") = Episode("$season:$n", season, n, "E$n", null, null, released)
    private val season1 = listOf(ep(1, 1), ep(1, 2), ep(2, 1))

    @Test fun aSeasonIsWatchedWhenEveryEpisodeOfItIs() {
        val seen = mapOf(Ep(1, 1) to 1.0, Ep(1, 2) to 0.85)
        assertTrue(seasonWatched(season1, 1, seen, "2026-11-07"))
        assertFalse(seasonWatched(season1, 2, seen, "2026-11-07"))
    }

    @Test fun oneShortOfTheMarkHoldsItBack() {
        assertFalse(seasonWatched(season1, 1, mapOf(Ep(1, 1) to 1.0, Ep(1, 2) to 0.5), "2026-11-07"))
    }

    @Test fun anEpisodeNotOutYetDoesNotHoldItBack() {
        val eps = season1 + ep(1, 3, released = "2027-01-01")
        assertTrue(seasonWatched(eps, 1, mapOf(Ep(1, 1) to 1.0, Ep(1, 2) to 1.0), "2026-11-07"))
    }

    @Test fun aSeasonWithNothingOutHasNoTick() {
        assertFalse(seasonWatched(listOf(ep(3, 1, released = "2027-01-01")), 3, emptyMap(), "2026-11-07"))
    }
}
