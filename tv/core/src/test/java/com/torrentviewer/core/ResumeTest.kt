package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Numbers from real resumes in the phone's engine log. */
class ResumeTest {
    @Test fun freshPlayFetchesOnlyTheHead() {
        assertEquals(listOf(0, 1), prerollTargets(0, 0, 2, 1352))
    }

    @Test fun resumeFetchesHeaderThenIndexThenTheSpot() {
        assertEquals(listOf(1396, 1634, 1452, 1453), prerollTargets(1396, 1452, 2, 1634))
    }

    @Test fun resumeInTheClosingMinutesStaysInTheFile() {
        assertEquals(listOf(0, 1352, 1351), prerollTargets(0, 1351, 3, 1352))
    }

    @Test fun savedSpotIsBehindWhatThePlayerRead() {
        // 30s read ahead of a 1,500s episode, and the keyframe margin.
        assertEquals(0.5 - 35.0 / 1500, playheadRatio(0.5, 30.0, 1500.0), 1e-9)
    }

    @Test fun nothingMeasuredStaysThat() {
        assertEquals(-1.0, playheadRatio(-1.0, 30.0, 1500.0), 0.0)
    }

    @Test fun neverBeforeTheStartOfTheFile() {
        assertEquals(0.0, playheadRatio(0.001, 30.0, 1500.0), 0.0)
    }

    @Test fun unknownRuntimeKeepsTheReading() {
        assertEquals(0.5, playheadRatio(0.5, 30.0, 0.0), 0.0)
    }

    @Test fun nothingOpensWithoutTheHeader() {
        assertEquals(false, readyToOpen(0, 8, 8, 8, resuming = true))
    }

    @Test fun aResumeOpensOnTheHeaderAlone() {
        assertEquals(true, readyToOpen(8, 8, 0, 8, resuming = true))
    }

    @Test fun aNewStartWaitsForTheViewersBuffer() {
        assertEquals(false, readyToOpen(8, 8, 8, 16, resuming = false))
        assertEquals(true, readyToOpen(16, 8, 16, 16, resuming = false))
    }
}
