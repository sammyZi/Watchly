package com.torrentviewer.core

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** What a release's name says of its video: [mime] as MediaCodec names it, its size and frame rate, and [codec] to show. */
internal data class VideoGuess(val mime: String, val width: Int, val height: Int, val fps: Double, val codec: String)

/** From the release name alone ("…2160p…48FPS.AV1…"); null when it names no codec. Unsaid, it is 1080p at 24 frames. */
internal fun videoOf(release: String): VideoGuess? {
    val r = release.lowercase()
    fun has(pattern: String) = Regex("(?<![a-z0-9])($pattern)(?![a-z0-9])").containsMatchIn(r)
    val (mime, codec) = when {
        has("av1") -> "video/av01" to "AV1"
        has("x265|h\\.?265|hevc") -> "video/hevc" to "HEVC"
        has("x264|h\\.?264|avc") -> "video/avc" to "H.264"
        has("vp9") -> "video/x-vnd.on2.vp9" to "VP9"
        else -> return null
    }
    val (w, h) = when {
        has("2160p|4k|uhd") -> 3840 to 2160
        has("1440p") -> 2560 to 1440
        has("720p") -> 1280 to 720
        has("480p") -> 854 to 480
        else -> 1920 to 1080
    }
    val fps = Regex("(\\d{2,3})\\s?fps").find(r)?.groupValues?.get(1)?.toDoubleOrNull() ?: 24.0
    return VideoGuess(mime, w, h, fps, codec)
}

/**
 * Whether this device decodes [v] in hardware, at its size and frame rate. In software a phone's CPU can't keep
 * up with 4K, let alone AV1 at 48 frames: a Snapdragon 865 has hardware H.264 and HEVC only, and a 4K AV1 film
 * dropped frames all through. Asked of MediaCodec once per kind of video.
 */
object Decoders {
    private val known = HashMap<String, Boolean>()
    private val all by lazy { runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList()) }

    private fun hardware(info: MediaCodecInfo) =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    internal fun smooth(v: VideoGuess): Boolean = synchronized(known) {
        known.getOrPut("${v.mime} ${v.width}x${v.height}@${v.fps}") {
            all.filter { hardware(it) && it.supportedTypes.any { t -> t.equals(v.mime, ignoreCase = true) } }.any { info ->
                runCatching { info.getCapabilitiesForType(v.mime).videoCapabilities?.areSizeAndRateSupported(v.width, v.height, v.fps) == true }.getOrDefault(false)
            }
        }
    }

    /** "AV1", when the release's video won't play smoothly here; null when it will, or its name doesn't say. */
    fun choppy(release: String): String? = videoOf(release)?.takeIf { !smooth(it) }?.let { v -> if (v.height >= 2160) "${v.codec} 4K" else v.codec }
}
