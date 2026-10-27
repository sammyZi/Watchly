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

/**
 * Where a roll cuts: from the first piece at or before [rewind] bytes behind the reader at
 * file byte [at]. Returns the file bytes to free (piece-aligned at their end) and the pieces
 * they touch, which the torrent no longer has.
 */
internal fun rollCut(fileStart: Long, at: Long, rewind: Long, pieceLen: Long): Pair<Long, IntRange> {
    val keepFrom = maxOf(fileStart, fileStart + at - rewind) / pieceLen
    val cut = keepFrom * pieceLen - fileStart
    return if (cut <= 0) 0L to IntRange.EMPTY else cut to ((fileStart / pieceLen).toInt() until keepFrom.toInt())
}
