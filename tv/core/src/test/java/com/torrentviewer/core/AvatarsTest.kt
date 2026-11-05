package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AvatarsTest {
    /** A name without its file would show an empty tile. */
    @Test fun everyEmojiIconHasItsPicture() {
        assertEquals(emptyList<String>(), EMOJI_ICONS.filterNot { File("src/main/assets/$it.png").exists() })
    }

    @Test fun runtimesShowHoursFromTheHourUp() {
        assertEquals("59 min", runtimeLabel("59 min"))
        assertEquals("1h", runtimeLabel("60 min"))
        assertEquals("1h 47m", runtimeLabel("107 min"))
        assertEquals("2h", runtimeLabel("120 min"))
        assertEquals("N/A", runtimeLabel("N/A"))
    }
}
