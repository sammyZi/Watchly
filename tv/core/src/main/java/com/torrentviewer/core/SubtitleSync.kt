package com.torrentviewer.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lining a subtitle file up with the film. Two ways:
 *
 * - **Automatically** ([align]): the player's own audio is listened to for a minute or so
 *   ([SpeechMeter]: where speech is, every 100 ms), and the file's lines are slid along it until
 *   they cover the speech best — the method of the desktop subtitle-sync tools, cross-correlating
 *   speech against subtitle time. Then it stops.
 * - **By ear** ([match]): the viewer taps as someone starts to speak, and the nearest line is
 *   moved to that moment.
 *
 * Works on a subtitle *file* — SRT, WebVTT or ASS — not a track built into the video.
 */
object SubtitleSync {
    /** Between a line beginning and a finger reaching the button. */
    const val REACTION_MS = 250L

    /** Further than this from any line and the press was for something else (a sound, a pause). */
    const val WINDOW_MS = 20_000L

    /** How far automatic sync looks either way. Calibration knob: wider finds more, and is fooled more. */
    const val SEARCH_MS = 20_000L

    /** One frame of [SpeechMeter], and the step [align] slides in. */
    const val FRAME_MS = 100L

    data class Match(
        /** The delay to set, in milliseconds: positive shows the subtitles later. */
        val delayMs: Long,
        /** How far the nearest line was from the press with the old delay, for choosing between files. */
        val distanceMs: Long,
        /** The start of the line it was matched to, so the next tap does not move the same line again. */
        val lineMs: Long = 0,
    )

    // 00:01:02,500 --> 00:01:04,000  (SRT, WebVTT; WebVTT may leave out the hours)
    private const val T = """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})"""
    private val ARROW = Regex("""$T\s*-->\s*$T""")
    // Dialogue: 0,0:01:02.50,0:01:05.00,Default,…  (ASS / SSA)
    private val ASS = Regex("""^\s*Dialogue:\s*[^,]*,\s*(\d+):(\d{2}):(\d{2})[.,](\d{1,2})\s*,\s*(\d+):(\d{2}):(\d{2})[.,](\d{1,2})""")

    private fun ms(h: String, m: String, s: String, frac: String, digits: Int) =
        ((h.ifEmpty { "0" }.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 +
            frac.padEnd(digits, '0').take(digits).toLong() * (if (digits == 2) 10 else 1)

    /** Every line of the file as when it starts and ends, in milliseconds, in the order they come. */
    fun cues(text: String): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (line in text.lineSequence()) {
            val ass = ASS.find(line)
            if (ass != null) {
                val g = ass.groupValues
                out += ms(g[1], g[2], g[3], g[4], 2)..ms(g[5], g[6], g[7], g[8], 2)
                continue
            }
            val m = ARROW.find(line) ?: continue
            val g = m.groupValues
            out += ms(g[1], g[2], g[3], g[4], 3)..ms(g[5], g[6], g[7], g[8], 3)
        }
        return out
    }

    /** The start of every line in the file, in milliseconds, in the order they come. */
    fun starts(text: String): List<Long> = cues(text).map { it.first }

    /**
     * The delay that puts the line nearest [pressMs] at the press (less [REACTION_MS]). Lines are
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

    /** What automatic sync found: the delay to set, and how sure it is (a z-score; [align] wants [SURE]). */
    data class Aligned(val delayMs: Long, val confidence: Double)

    /** How far above the rest the best fit must stand. Calibration knob: lower syncs more films and gets more wrong. */
    const val SURE = 4.0

    /** Lines needed in what was heard before a fit means anything. */
    private const val MIN_LINES = 8

    /**
     * The delay at which [cues] cover the speech heard best. [speech] is one flag per [FRAME_MS]
     * of film from [startMs]. Each candidate delay scores +1 for a frame where speech and a line
     * agree (both or neither) and −1 where they don't; the best is taken only when it stands
     * [SURE] standard deviations clear of the rest and of any other peak more than a second away,
     * as speech and subtitles that merely look alike give a flat, many-peaked score. Null: not sure.
     */
    fun align(speech: BooleanArray, startMs: Long, cues: List<LongRange>, searchMs: Long = SEARCH_MS): Aligned? {
        if (speech.isEmpty()) return null
        val endMs = startMs + speech.size * FRAME_MS
        // Only lines that could land in what was heard, at some delay in the search.
        val near = cues.filter { it.last + searchMs >= startMs && it.first - searchMs <= endMs }
        if (near.count { it.first in startMs..endMs } < MIN_LINES) return null
        // A line's cover in film time, one flag per frame, wide enough to slide over.
        val span = (searchMs / FRAME_MS).toInt()
        val total = speech.size + 2 * span
        val covered = BooleanArray(total)
        for (c in near) {
            // The frames whose moment falls inside the line: rounded in at both ends, or every line
            // grew by a frame at its start and the fit leaned a frame late.
            val a = (-Math.floorDiv(startMs - c.first, FRAME_MS)).toInt() + span
            val b = Math.floorDiv(c.last - startMs, FRAME_MS).toInt() + span
            for (i in a.coerceAtLeast(0)..b.coerceAtMost(total - 1)) covered[i] = true
        }
        val steps = 2 * span + 1
        val scores = DoubleArray(steps)
        // A delay d shows a line at its start + d: speech at frame i is covered when the line's frame i - d is.
        for (k in 0 until steps) {
            val shift = k - span
            var s = 0
            for (i in speech.indices) {
                val j = i - shift + span
                val line = j in 0 until total && covered[j]
                s += if (line == speech[i]) 1 else -1
            }
            scores[k] = s.toDouble()
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        val mean = scores.average()
        val sd = sqrt(scores.sumOf { (it - mean) * (it - mean) } / steps).takeIf { it > 0 } ?: return null
        val z = (scores[best] - mean) / sd
        // Another peak, a second or more away, nearly as good: the fit is a guess between them.
        val rival = scores.indices.filter { abs(it - best) * FRAME_MS > 1000 }.maxOfOrNull { scores[it] } ?: Double.NEGATIVE_INFINITY
        val clear = (scores[best] - rival) / sd
        if (z < SURE || clear < 0.5) return null
        return Aligned((best - span) * FRAME_MS, z)
    }
}

/**
 * Where there is speech in the film's own audio, every [SubtitleSync.FRAME_MS], from the samples
 * the player plays. Fed on the player's audio thread; read from anywhere.
 *
 * The audio is mixed to one channel and kept to the band a voice sits in (300–3400 Hz, two
 * one-pole filters), so rumble and hiss count for little; each frame's energy is kept, and a
 * frame is speech when it is among the louder ones of what was heard — music and effects pass
 * for speech often enough, and [SubtitleSync.align] is built to be outvoted by them, not fooled.
 *
 * Time is the film's: [restart] is called where the player starts afresh (a seek, the start),
 * at the position it starts from, and the samples are counted from there.
 */
class SpeechMeter {
    @Volatile var startMs = -1L
        private set
    private var rate = 0
    private var channels = 0
    private var float = false
    private var frameSamples = 0
    private var inFrame = 0
    private var acc = 0.0
    private var hpIn = 0.0
    private var hpOut = 0.0
    private var lp = 0.0
    private var hpA = 0.0
    private var lpB = 0.0
    private val energies = ArrayList<Float>()

    /** Not audio it can read (passed straight through to the speaker, or a format it does not know). */
    @Volatile var unreadable = false
        private set

    @Synchronized
    fun restart(positionMs: Long, sampleRate: Int, channelCount: Int, pcm16: Boolean, pcmFloat: Boolean) {
        energies.clear()
        inFrame = 0
        acc = 0.0
        hpIn = 0.0
        hpOut = 0.0
        lp = 0.0
        unreadable = !(pcm16 || pcmFloat) || sampleRate <= 0 || channelCount <= 0
        rate = sampleRate
        channels = channelCount
        float = pcmFloat
        frameSamples = (sampleRate * SubtitleSync.FRAME_MS / 1000).toInt().coerceAtLeast(1)
        val dt = 1.0 / sampleRate.coerceAtLeast(1)
        val hpRc = 1.0 / (2 * PI * 300)
        val lpRc = 1.0 / (2 * PI * 3400)
        hpA = hpRc / (hpRc + dt)
        lpB = dt / (lpRc + dt)
        startMs = positionMs
    }

    /** The film's true position where the current run of samples began, once the player says (a seek lands on a keyframe). */
    @Synchronized
    fun anchor(positionMs: Long) {
        // The word can come a moment after the samples start: up to 3 s in, it still applies to them.
        if (energies.size < 30) startMs = positionMs
    }

    /** One buffer of the player's samples. Read, never moved: the player plays the same buffer. */
    @Synchronized
    fun feed(buffer: ByteBuffer) {
        if (startMs < 0 || unreadable || channels <= 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytes = if (float) 4 else 2
        while (b.remaining() >= bytes * channels) {
            var mix = 0.0
            repeat(channels) { mix += if (float) b.float.toDouble() else b.short / 32768.0 }
            mix /= channels
            // Voice band: a high-pass at 300 Hz, then a low-pass at 3.4 kHz.
            hpOut = hpA * (hpOut + mix - hpIn)
            hpIn = mix
            lp += lpB * (hpOut - lp)
            acc += lp * lp
            if (++inFrame >= frameSamples) {
                energies += ln(acc / inFrame + 1e-12).toFloat()
                inFrame = 0
                acc = 0.0
            }
        }
    }

    /** Seconds of film heard since the last [restart]. */
    val seconds: Double @Synchronized get() = energies.size * SubtitleSync.FRAME_MS / 1000.0

    /** Speech or not per frame, and where the first frame is in the film. */
    @Synchronized
    fun speech(): Pair<Long, BooleanArray> = startMs to SpeechMeter.speechOf(energies.toFloatArray())

    companion object {
        /** A frame is speech when it is louder than most: above the 55th percentile, and above near-silence. */
        fun speechOf(energy: FloatArray): BooleanArray {
            if (energy.isEmpty()) return BooleanArray(0)
            val sorted = energy.sortedArray()
            val cut = sorted[(sorted.size * 55 / 100).coerceAtMost(sorted.size - 1)]
            val quiet = sorted[(sorted.size * 5 / 100).coerceAtMost(sorted.size - 1)] + 1.5f
            return BooleanArray(energy.size) { energy[it] >= cut && energy[it] > quiet }
        }
    }
}
