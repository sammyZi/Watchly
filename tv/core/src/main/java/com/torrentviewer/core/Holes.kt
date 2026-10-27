package com.torrentviewer.core

import java.io.File

// Apart from TorrentStream.kt, whose file class loads libtorrent: rollCut is unit-tested on the JVM.

/** libholes: frees a stretch of a file in place, its size unchanged. */
internal object Holes {
    private val loaded = runCatching { System.loadLibrary("holes") }.isSuccess

    @JvmStatic private external fun punchHole(path: String, offset: Long, length: Long): Int

    /** 0 once freed; otherwise -errno, or -1 when the library is missing. */
    fun punch(file: File, offset: Long, length: Long): Int = if (loaded) punchHole(file.path, offset, length) else -1
}

/** A stretch a roll frees: file bytes [from, to), and the pieces they touch, which the torrent no longer has. */
internal data class Cut(val from: Long, val to: Long, val pieces: IntRange)

/**
 * Where a roll cuts: all of the file but file bytes [keepFrom]..[keepTo] and the tail from piece
 * [tailFrom] (the index players seek by). What lies ahead goes too: after a seek back, the
 * stretch left behind further on would otherwise sit there, the cap stay full, and the roll
 * come round again and again. Cuts meet the kept pieces on piece boundaries.
 */
internal fun rollCuts(fileStart: Long, fileLen: Long, keepFrom: Long, keepTo: Long, tailFrom: Int, pieceLen: Long): List<Cut> {
    // Pieces [a, b) as file bytes.
    fun cut(a: Long, b: Long): Cut? {
        val from = maxOf(0, a * pieceLen - fileStart)
        val to = minOf(fileLen, b * pieceLen - fileStart)
        return if (to > from) Cut(from, to, a.toInt() until b.toInt()) else null
    }
    val from = (fileStart + maxOf(0, keepFrom)) / pieceLen
    val to = (fileStart + keepTo) / pieceLen + 1
    return listOfNotNull(cut(fileStart / pieceLen, from), cut(to, tailFrom.toLong()))
}
