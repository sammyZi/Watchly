package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowsTest {
    private val home = listOf("resume", "list", "picks", "Action", "Drama")

    @Test fun nothingMovedKeepsHomesOwnOrder() {
        assertEquals(home, arrangeRows(home, emptyList()))
    }

    @Test fun savedOrderLeadsAndUnknownRowsFollowTheirNeighbour() {
        // "Comedy" was saved but is gone now; "list" and "Drama" weren't there when the rows were moved.
        assertEquals(listOf("picks", "resume", "list", "Action", "Drama"), arrangeRows(home, listOf("picks", "Comedy", "resume", "Action")))
    }

    @Test fun continueWatchingComingBackKeepsItsPlaceAtTheTop() {
        assertEquals(listOf("resume", "Drama", "list", "picks", "Action"), arrangeRows(home, listOf("Drama", "list", "picks", "Action")))
    }

    @Test fun aRowMovesOneStepAndStopsAtTheEnds() {
        assertEquals(listOf("list", "resume", "picks", "Action", "Drama"), movedRow(home, "list", -1))
        assertEquals(home, movedRow(home, "resume", -1))
        assertEquals(home, movedRow(home, "Drama", 1))
        assertEquals(home, movedRow(home, "missing", 1))
    }
}
