package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyTest {
    private val hash = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    private val key = hex("00112233445566778899aabbccddeeff")

    @Test fun sealedOnlyOpensWithItsKey() {
        val sealed = seal(key, "hello")
        assertFalse(sealed.contains("hello"))
        assertEquals("hello", unseal(key, sealed))
        assertNull(unseal(hex("ffeeddccbbaa99887766554433221100"), sealed))
        assertNull(unseal(key, "not hex at all"))
    }

    @Test fun stateRoundTrips() {
        val s = PartyState(1234.5, true, PartyMedia("magnet:?xt=urn:btih:$hash", "Film.mkv", "Film", "tt0133093", "movie", null), 1.25f, 1_700_000_000_000, listOf("Sam"), asks = false, by = "Alex")
        assertEquals(s, decodeState(encodeState(s)))
    }

    @Test fun theLeaderMovesOnWhileTheMessageTravels() {
        val s = PartyState(100.0, true, PartyMedia("m", "f", "t", null, null, null), 1.5f, 10_000)
        assertEquals(103.0, s.posAt(12_000), 1e-9)
        assertEquals(100.0, s.copy(playing = false).posAt(12_000), 1e-9)
    }

    @Test fun smallDriftIsANudgeNeverMoreThanEightPercent() {
        // Inside the dead zone: the leader's own speed.
        assertEquals(1f, Party.catchUpRate(0.1, 1f), 0f)
        assertEquals(1.05f, Party.catchUpRate(0.2, 1f), 1e-6f)
        assertEquals(1.08f, Party.catchUpRate(1.9, 1f), 1e-6f)
        assertEquals(0.92f, Party.catchUpRate(-1.9, 1f), 1e-6f)
        assertEquals(1.575f, Party.catchUpRate(0.2, 1.5f), 1e-6f)
    }

    @Test fun theClockTrustsTheQuickestPing() {
        val r = Room("a".repeat(32), key, leader = false)
        // Slow trip: the server read 5000 somewhere in a 400 ms round.
        r.clock(sent = 1000, server = 5000, back = 1400)
        // Quick trip: 20 ms round, so the server is 4000 ahead of this phone.
        r.clock(sent = 2000, server = 6010, back = 2020)
        r.clock(sent = 3000, server = 7300, back = 3600)
        assertEquals(4000.0, (r.now() - mono()).toDouble(), 2.0)
    }

    @Test fun invitesInEitherForm() {
        val id = "0123456789abcdef0123456789abcdef"
        val k = "fedcba9876543210fedcba9876543210"
        assertEquals(id to k, Party.parseInvite("https://watchly-party.x.workers.dev/j/$id#$k"))
        assertEquals(id to k, Party.parseInvite("watchly://party/$id?k=$k"))
        assertNull(Party.parseInvite("watchly://party/$id"))
    }

    @Test fun onlyTorrentsAreShared() {
        // Its own trackers go with it: where the release's peers are.
        assertEquals("magnet:?xt=urn:btih:$hash&dn=Film&tr=udp://a:1", shareableUri("magnet:?xt=urn:btih:$hash&dn=Film&tr=udp://a:1", "Film", null))
        // A debrid link holds the host's key: the torrent behind it goes instead.
        val fromLink = shareableUri("https://torrentio.strem.fun/resolve/torbox/SECRETKEY/$hash/1/Film.mkv", "Film", null)!!
        assertTrue(fromLink.startsWith("magnet:?xt=urn:btih:$hash&dn=Film"))
        assertFalse(fromLink.contains("SECRETKEY"))
        assertTrue(shareableUri("/data/imports/abc.torrent", "Film", hash)!!.startsWith("magnet:?xt=urn:btih:$hash&dn=Film"))
        assertNull(shareableUri("https://example.com/film.mp4", "Film", null))
    }

    @Test fun theInviteCarriesTheFilmForPreviewsAndKeepsTheKeyAfterTheHash() {
        Party.server = "https://p.example.dev"
        val id = "a".repeat(32)
        val room = Room(id, ByteArray(16) { 1 }, leader = true)
        assertEquals("https://p.example.dev/j/$id#${"01".repeat(16)}", room.link)
        room.film = PartyMedia("magnet:?xt=urn:btih:$hash", "f.mkv", "Ten zing", "tt1234567", "movie", null)
        assertEquals("https://p.example.dev/j/$id?i=tt1234567&t=Ten%20zing#${"01".repeat(16)}", room.link)
        // Still an invite as far as the apps' parser goes.
        assertEquals(id to "01".repeat(16), Party.parseInvite(room.link))
        // A made-up id is left out of the link.
        room.film = PartyMedia("magnet:?xt=urn:btih:$hash", "f.mkv", "Ten zing", "not-an-id", null, null)
        assertEquals("https://p.example.dev/j/$id#${"01".repeat(16)}", room.link)
    }

    @Test fun aLongMagnetLosesItsLastTrackersOnly() {
        val long = "magnet:?xt=urn:btih:$hash&dn=Film" + (1..400).joinToString("") { "&tr=udp://tracker$it.example:1337" }
        val fit = fitMagnet(long)
        assertTrue(fit.length <= MAGNET_MAX)
        assertTrue(fit.startsWith("magnet:?xt=urn:btih:$hash&dn=Film&tr=udp://tracker1.example:1337"))
    }
}
