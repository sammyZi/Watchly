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

    @Test fun aSpotInAFileOfUnknownLengthUsesTheRuntime() {
        val carried = Item("a", "Film", at = 1, positions = mapOf(0 to 3000.0))
        assertEquals(0.0, carried.resumeRatio(0), 0.0)
        assertEquals(0.5, carried.resumeRatio(0, 6000.0), 0.0)
    }

    @Test fun theFilesOwnLengthBeatsTheRuntime() {
        val known = Item("a", "Film", at = 1, positions = mapOf(0 to 3000.0), durations = mapOf(0 to 4000.0))
        assertEquals(0.75, known.resumeRatio(0, 6000.0), 0.0)
    }

    private fun swarm(n: Int, stalled: Int) = List(n) { i -> PeerLoad("10.0.0.$i", 4, if (i < stalled) i * 1000 else 500_000) }

    @Test fun peersSittingOnRequestsAreDroppedSlowestFirst() {
        assertEquals(listOf("10.0.0.0", "10.0.0.1"), stalledPeers(swarm(30, 2)))
    }

    @Test fun aSlowPeerWithNothingAskedOfItStays() {
        assertEquals(emptyList<String>(), stalledPeers(List(30) { PeerLoad("10.0.0.$it", 0, 0) }))
    }

    @Test fun droppingStopsAtEightAndLeavesTenPeers() {
        assertEquals(8, stalledPeers(swarm(30, 20)).size)
        assertEquals(2, stalledPeers(swarm(12, 12)).size)
        assertEquals(emptyList<String>(), stalledPeers(swarm(10, 10)))
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
