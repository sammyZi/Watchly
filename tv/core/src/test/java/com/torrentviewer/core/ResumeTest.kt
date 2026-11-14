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
        assertEquals(listOf("10.0.0.0", "10.0.0.1"), stalledPeers(swarm(60, 2), 60))
    }

    @Test fun aSlowPeerWithNothingAskedOfItStays() {
        assertEquals(emptyList<String>(), stalledPeers(List(60) { PeerLoad("10.0.0.$it", 0, 0) }, 60))
    }

    @Test fun aNarrowFetchHoldsItsWidthThroughSmallChanges() {
        assertEquals(8, steadyWidth(8, 9))
        assertEquals(8, steadyWidth(8, 6))
        assertEquals(12, steadyWidth(8, 12))
        assertEquals(4, steadyWidth(8, 4))
        assertEquals(9, steadyWidth(0, 9))
    }

    @Test fun inASlowSwarmEveryoneIsSlowAndNobodyIsStalled() {
        // Batman Begins: 470kB/s from 61 peers, all under 32KB/s, and these are the peers sending the film.
        assertEquals(emptyList<String>(), stalledPeers(List(61) { PeerLoad("10.0.0.$it", 2, 5_000 + it * 100) }, 61))
    }

    @Test fun aSilentPeerIsStillStalledInASlowSwarm() {
        val peers = List(60) { PeerLoad("10.0.0.$it", 2, if (it == 0) 0 else 8_000) }
        assertEquals(listOf("10.0.0.0"), stalledPeers(peers, 60))
    }

    @Test fun droppingStopsAtFourAndLeavesFortyPeers() {
        assertEquals(4, stalledPeers(swarm(60, 30), 60).size)
        assertEquals(2, stalledPeers(swarm(42, 42), 42).size)
        assertEquals(emptyList<String>(), stalledPeers(swarm(40, 40), 40))
    }

    @Test fun aSmallSwarmKeepsItsSlowPeers() {
        assertEquals(emptyList<String>(), stalledPeers(swarm(16, 16), 16))
    }

    @Test fun peersStillDiallingDontCount() {
        assertEquals(emptyList<String>(), stalledPeers(swarm(176, 20), 32))
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

    @Test fun aMissingBackdropFallsBackToThePoster() {
        val wide = "https://images.metahub.space/background/medium/tt0111161/img"
        val tall = "https://images.metahub.space/poster/medium/tt0111161/img"
        assertEquals(listOf(wide, tall), artCandidates(wide, null))
        assertEquals(listOf(tall, wide), artCandidates(tall, null))
        // A still, then the title's backdrop, then its poster; nothing twice.
        assertEquals(listOf("https://x/still.jpg", wide, tall), artCandidates("https://x/still.jpg", wide))
        assertEquals(listOf(wide, tall), artCandidates(wide, wide))
        // Someone else's picture has no other to try.
        assertEquals(listOf("https://x/a.jpg"), artCandidates("https://x/a.jpg", null))
    }

    private fun blocks(vararg done: Int) = java.util.BitSet().apply { done.forEach { set(it) } }

    @Test fun aPieceStillArrivingReadsAsFarAsItsBlocksRun() {
        val piece = 8 * BLOCK_BYTES
        // Nothing of it yet, or not the block the reader is at.
        assertEquals(0, finishedRun(blocks(), 0, piece))
        assertEquals(0, finishedRun(blocks(1, 2), 0, piece))
        // Blocks 0-2 are here and 3 is not: block 4 arriving early does not extend the run.
        assertEquals(3 * BLOCK_BYTES, finishedRun(blocks(0, 1, 2, 4), 0, piece))
        // From partway into a block, the rest of that block and the run after it.
        assertEquals(3 * BLOCK_BYTES - 100, finishedRun(blocks(0, 1, 2, 4), 100, piece))
        assertEquals(BLOCK_BYTES, finishedRun(blocks(0, 1, 2, 4), 4 * BLOCK_BYTES, piece))
    }

    @Test fun aRunOfBlocksEndsWithThePiece() {
        // The last piece of a torrent is short, and so is its last block.
        val piece = 2 * BLOCK_BYTES + 500
        assertEquals(piece, finishedRun(blocks(0, 1, 2), 0, piece))
        assertEquals(500, finishedRun(blocks(2), 2 * BLOCK_BYTES, piece))
        assertEquals(0, finishedRun(blocks(0, 1, 2), piece, piece))
        assertEquals(0, finishedRun(blocks(0), -1, piece))
    }
}
