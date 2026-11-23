package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodersTest {
    @Test fun aReleaseNameSaysItsVideo() {
        assertEquals(VideoGuess("video/av01", 3840, 2160, 48.0, "AV1"), videoOf("The.Odyssey.2026.  2160p.AI Enhanced.IMAX.BluRay.48FPS.AV1.SDR.DTS-HD.MA.5.1MultiSubs_Marjenbo .mkv"))
        assertEquals(VideoGuess("video/hevc", 1920, 1080, 24.0, "HEVC"), videoOf("The.Sixth.Sense.1999.1080p.BluRay.DDP5.1.x265.10bit-GalaxyRG265.mkv"))
        assertEquals(VideoGuess("video/avc", 1920, 1080, 24.0, "H.264"), videoOf("Tron.Legacy.2010.RERIP.PROPER.1080p.BluRay.H264.AAC-LAMA.mp4"))
        assertEquals("video/hevc", videoOf("Show S01 2160p WEB-DL H.265 60fps")?.mime)
        assertEquals(60.0, videoOf("Show S01 2160p WEB-DL H.265 60fps")!!.fps, 0.0)
        assertEquals(1280, videoOf("Film 720p x264")?.width)
        // No codec in the name: nothing to warn about.
        assertNull(videoOf("Some.Film.2020.1080p.BluRay"))
        // "avc" inside a word is not the codec.
        assertNull(videoOf("Havoc.2025.1080p.WEB"))
    }
}
