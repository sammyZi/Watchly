package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingTest {
    private fun ep(s: Int, e: Int, released: String?) = Episode("tt1:$s:$e", s, e, "Episode $e", null, null, released)

    private val show = Title(
        "tt1", "tv", "The Show",
        episodes = listOf(
            ep(1, 9, "2026-11-10T00:00:00.000Z"), // aired
            ep(0, 1, "2026-11-20T00:00:00.000Z"), // a special
            ep(2, 1, "2026-11-20T00:00:00.000Z"),
            ep(2, 2, "2027-03-01T00:00:00.000Z"), // past the window
        ),
    )
    private val film = Title("tt2", "movie", "A Film", released = "2026-12-18T00:00:00.000Z")

    @Test fun onlyWhatsAheadInTheWindowSoonestFirst() {
        val got = Upcoming.ahead(listOf(film, show), "2026-11-16", "2027-01-15")
        assertEquals(listOf("2026-11-20" to "S2 E1", "2026-12-18" to "Film"), got.map { it.date to it.what })
    }

    @Test fun anAllDayEventEndsTheNextDayAcrossAMonth() {
        assertEquals("2026-12-01", Upcoming.nextDay("2026-11-30"))
    }

    @Test fun calendarFileHasOneEventEachWithEscapedText() {
        val ics = Upcoming.ics(listOf(Coming(film.copy(title = "Film; Part 1, Again"), "2026-12-18", null)), now = 0)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue("DTSTART;VALUE=DATE:20261218\r\n" in ics)
        assertTrue("DTEND;VALUE=DATE:20261219\r\n" in ics)
        assertTrue("SUMMARY:Film\\; Part 1\\, Again (release)" in ics)
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(ics).count())
    }
}
