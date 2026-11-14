package com.torrentviewer.core

import kotlin.math.abs

/**
 * "Sync to this line": the viewer taps as they hear a line begin, and the subtitles are shifted
 * so the nearest line in the file begins then. Exact to a tap. Works on a subtitle *file* — SRT,
 * WebVTT or ASS — not a track built into the video.
 */
object SubtitleSync {
    /** Between a line beginning and a finger reaching the button. */
    const val REACTION_MS = 250L

    /** Further than this from any line and the tap was for something else (a sound, a pause). */
    const val WINDOW_MS = 20_000L

    data class Match(
        /** The delay to set, in milliseconds: positive shows the subtitles later. */
        val delayMs: Long,
        /** How far the nearest line was from the tap with the old delay, for choosing between files. */
        val distanceMs: Long,
        /** The start of the line it was matched to, so the next tap does not move the same line again. */
        val lineMs: Long = 0,
    )

    // 00:01:02,500 --> …  (SRT, WebVTT; WebVTT may leave out the hours)
    private val ARROW = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->""")
    // Dialogue: 0,0:01:02.50,0:01:05.00,Default,…  (ASS / SSA)
    private val ASS = Regex("""^\s*Dialogue:\s*[^,]*,\s*(\d+):(\d{2}):(\d{2})[.,](\d{1,2})""")

    /** The start of every line in the file, in milliseconds, in the order they come. */
    fun starts(text: String): List<Long> {
        val out = ArrayList<Long>()
        for (line in text.lineSequence()) {
            val ass = ASS.find(line)
            if (ass != null) {
                val (h, mi, s, cs) = ass.destructured
                out += ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + cs.padEnd(2, '0').take(2).toLong() * 10
                continue
            }
            val m = ARROW.find(line) ?: continue
            val (h, mi, s, ms) = m.destructured
            out += ((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').take(3).toLong()
        }
        return out
    }

    /**
     * The delay that puts the line nearest [pressMs] at the tap (less [REACTION_MS]). Lines are
     * shown at their start plus [delayMs] now, so that is where to look; null when none is within
     * [WINDOW_MS], or the file has none.
     *
     * [anchored] is the line the last tap moved. It is never taken again: tapped twice for one line,
     * the second tap found that line, now right on the first tap, and moved it to the second — each
     * tap of a quick run added the time between them, and the subtitles drifted further off.
     */
    fun match(starts: List<Long>, pressMs: Long, delayMs: Long, anchored: Long? = null): Match? {
        val heard = pressMs - REACTION_MS
        val nearest = starts.filter { it != anchored }.minByOrNull { abs(it + delayMs - heard) } ?: return null
        val distance = abs(nearest + delayMs - heard)
        if (distance > WINDOW_MS) return null
        return Match(heard - nearest, distance, nearest)
    }
}
