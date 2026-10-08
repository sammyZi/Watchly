package app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeTest {
    @Test fun ranges() {
        assertEquals(0L to 999L, parseRange(null, 1000))
        assertEquals(100L to 999L, parseRange("bytes=100-", 1000))
        assertEquals(100L to 199L, parseRange("bytes=100-199", 1000))
        assertEquals(900L to 999L, parseRange("bytes=-100", 1000)) // suffix: the last 100, not 0..100
        assertEquals(500L to 999L, parseRange("bytes=500-5000", 1000)) // end clamped
        assertNull(parseRange("bytes=1000-", 1000)) // VLC's probe past the end: 416
    }

    @Test fun magnetNames() {
        assertEquals("Big Buck Bunny", magnetName("magnet:?xt=urn:btih:abc&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Fx"))
        assertNull(magnetName("magnet:?xt=urn:btih:abc"))
    }
}
