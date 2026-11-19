package com.torrentviewer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleScaleTest {
    @Test fun normalIsTheDiscsOwnSize() {
        assertEquals(1f, SubtitleStyle(size = "normal").pictureScale())
        assertEquals(16f / 12, SubtitleStyle(size = "large").pictureScale())
        assertEquals(0.8f, SubtitleStyle(size = "small").pictureScale())
    }

    @Test fun aLineGrowsAboutItsBottomCentre() {
        // A PGS line: 40% wide, 10% tall, centred, its foot at 90% down the frame.
        val got = scaledBox(0.3f, 0.8f, 0.4f, 0.1f, 1.5f)
        assertArrayEquals(floatArrayOf(0.2f, 0.75f, 0.6f, 0.15f), got, 1e-5f)
    }

    @Test fun itStaysInsideTheFrame() {
        // 80% wide at twice the size would be 160%: it stops at the frame's width, still on screen.
        val (x, y, w, h) = scaledBox(0.1f, 0.85f, 0.8f, 0.1f, 2f).toList()
        assertEquals(1f, w, 1e-5f)
        assertEquals(0f, x, 1e-5f)
        assertEquals(0.125f, h, 1e-5f)
        assertEquals(true, y + h <= 1f + 1e-5f)
    }
}
