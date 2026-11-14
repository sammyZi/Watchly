package com.torrentviewer.core

import kotlin.math.abs

/**
 * "Sync to this line": the viewer presses as they hear a line begin, and the subtitles are shifted
 * so the nearest line in the file begins then. Fixing timing from the audio itself would need
 * speech detection the apps do not have; this takes the ear of the person watching instead, and
 * is exact to a press. Works on a subtitle *file* — SRT, WebVTT or ASS — not a track built into the video.
 */
object SubtitleSync {
    /** Between a line beginning and a finger reaching the button. */
    const val REACTION_MS = 250L

    /** Further than this from any line and the press was for something else (a sound, a pause). */
    const val WINDOW_MS = 20_000L

    data class Match(
        /** The delay to set, in milliseconds: positive shows the subtitles later. */
        val delayMs: Long,
        /** How far the nearest line was from the press with the old delay, for choosing between files. */
        val distanceMs: Long,
    )

    // 00:01:02,500 --> …  (SRT, WebVTT; WebVTT may leave out the hours)
    private val ARROW = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->""")
    // Dialogue: 0,0:01:02.50,0:01:05.00,Default,…  (ASS / SSA)
    private val ASS = Regex("""^\s*Dialogue:\s*[^,]*,\s*(\d+):(\d{2}):(\d{2})[.,](\d{1,2})""")

    /** The start of every line in the file, in milliseconds, in the order they come. */
    fun starts(text: String): List<Long> {
        val out = ArrayList<Long>()
        for (line in text.lineSequence()) {
            ASS.find(line)?.let { m ->
                val (h, mi, s, cs) = m.destructured
                out += ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + cs.padEnd(2, '0').take(2).toLong() * 10
                return@let
            } ?: ARROW.find(line)?.let { m ->
                val (h, mi, s, ms) = m.destructured
                out += ((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').take(3).toLong()
            }
        }
        return out
    }

    /**
     * The delay that puts the line nearest [pressMs] at the press (less [REACTION_MS]). Lines are
     * shown at their start plus [delayMs] now, so that is where to look; null when none is within
     * [WINDOW_MS], or the file has none.
     */
    fun match(starts: List<Long>, pressMs: Long, delayMs: Long): Match? {
        val heard = pressMs - REACTION_MS
        val nearest = starts.minByOrNull { abs(it + delayMs - heard) } ?: return null
        val distance = abs(nearest + delayMs - heard)
        if (distance > WINDOW_MS) return null
        return Match(heard - nearest, distance)
    }
}
