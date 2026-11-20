package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StartKitsTest {
    private val day = 24 * 3600 * 1000L

    @Test
    fun trimsAllButTheKeptPieces() {
        // A 10MB file 3MB into its torrent, 4MB pieces: pieces 0..3, the first and last shared with its neighbours.
        val mb = 1024L * 1024
        assertEquals(listOf(1 * mb to 5 * mb, 9 * mb to 10 * mb), keptOnly(3 * mb, 10 * mb, 4 * mb, setOf(0, 2)))
        // Nothing kept: the whole file, from its own first byte, not the torrent's.
        assertEquals(listOf(0L to 10 * mb), keptOnly(3 * mb, 10 * mb, 4 * mb, emptySet()))
        // All kept: nothing.
        assertEquals(emptyList<Pair<Long, Long>>(), keptOnly(3 * mb, 10 * mb, 4 * mb, setOf(0, 1, 2, 3)))
    }

    @Test
    fun keepsTakesExpiresAndCaps() {
        val root = Files.createTempDirectory("kits").toFile()
        val save = java.io.File(root, "torrents").apply { mkdirs() }
        val index = java.io.File(root, "starts.json")
        fun film(name: String) = java.io.File(save, name).apply { writeText("x") }
        val now = System.currentTimeMillis()

        val kits = StartKits(index) { save }
        film("old.mkv"); film("new.mkv"); film("big.mkv")
        kits.put(StartKits.Kit("a", "old.mkv", listOf(0, 9), 10, now - (START_KIT_DAYS + 1) * day))
        // Put prunes: the one unplayed too long is gone, files and all.
        assertFalse(kits.has("a"))
        assertFalse(java.io.File(save, "old.mkv").exists())

        kits.put(StartKits.Kit("b", "new.mkv", listOf(0, 1, 9), 20, now, mapOf(5 to listOf(0, 1, 2))))
        // Survives a reload, half-finished pieces and all.
        val again = StartKits(index) { save }
        assertTrue(again.has("b"))
        assertEquals(setOf("new.mkv"), again.names())
        assertEquals(mapOf(5 to listOf(0, 1, 2)), again.get("b")?.blocks)

        // Over the cap, the longest unplayed goes first.
        again.put(StartKits.Kit("c", "big.mkv", listOf(0), START_KIT_CAP, now - 1000))
        assertTrue(again.has("b"))
        assertFalse(again.has("c"))

        // Read for a play, it stays on the list, so a kill mid-film keeps it; forgotten, its files stay.
        assertEquals(listOf(0, 1, 9), again.get("b")?.pieces)
        assertTrue(again.has("b"))
        again.forget("b")
        assertNull(again.get("b"))
        assertTrue(java.io.File(save, "new.mkv").exists())
        root.deleteRecursively()
    }
}
