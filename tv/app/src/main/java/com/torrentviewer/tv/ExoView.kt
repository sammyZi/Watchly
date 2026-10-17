package com.torrentviewer.tv

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import org.videolan.libvlc.MediaPlayer.ScaleType
import java.io.File
import java.util.Locale
import androidx.media3.common.Tracks as MediaTracks

private const val TAG = "ExoView"

/** Sidecar subtitle types by extension; anything else waits for VLC. */
internal fun subtitleMime(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    else -> null
}

/**
 * A video surface backed by Media3. The picture goes straight from the hardware decoder to
 * a SurfaceView; subtitles are a plain view above it, so a new line never touches the video
 * pipeline — what keeps 4K smooth on a weak TV. Anything it cannot play goes to
 * [onUnsupported], and the player screen falls back to [VlcView].
 */
@OptIn(UnstableApi::class)
class ExoView(context: Context) : Video(context) {
    private val frame = AspectRatioFrameLayout(context)
    private val surface = SurfaceView(context)
    private val subtitleView = SubtitleView(context)
    private var player: ExoPlayer? = null

    private var source: String? = null
    private var startMs = 0L
    private var cacheMs = 3000
    private var paused = false
    private var rate = 1f
    private var scale = ScaleType.SURFACE_BEST_FIT
    private var videoSize = VideoSize.UNKNOWN
    /** Indexes into the playable subtitle / audio tracks; text is off until one is picked. */
    private var wantedText: Int? = null
    private var wantedAudio: Int? = null
    private var defaultAudio = -1
    private var announced = false
    private var buffering = false
    private var gaveUp = false
    private var ioRetries = 0
    private var subtitleFiles: List<String> = emptyList()
    /** Sidecar files in the current media item: Media3 takes new ones only with a new item. */
    private var attached: List<String> = emptyList()
    private var subRetries = 0
    private val attachSubs = Runnable { attachPendingSubs() }
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var bandwidthKbps = 0.0

    /** Media3 has no time events: the screen's clock is read four times a second. */
    private val tick = object : Runnable {
        override fun run() {
            player?.let { onTime(it.currentPosition) }
            postDelayed(this, 250)
        }
    }

    init {
        frame.addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        subtitleView.setUserDefaultStyle()
        subtitleView.setUserDefaultTextSize()
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        keepScreenOn = true
    }

    override fun open(url: String, startAt: Long, cacheMs: Int, subtitles: List<String>) {
        source = url
        startMs = startAt
        this.cacheMs = cacheMs.coerceIn(500, 8000)
        subtitleFiles = subtitles
        subRetries = 0
        prepare()
    }

    /** Reopen at [ms] — abandons a read parked on a missing piece, and takes new sidecar files. */
    override fun reload(ms: Long) {
        if (source == null) return
        startMs = ms
        prepare()
    }

    override val time: Long get() = player?.currentPosition ?: 0L

    override fun seekTo(ms: Long) {
        player?.seekTo(ms)
    }

    override fun setPaused(value: Boolean) {
        paused = value
        keepScreenOn = !value
        player?.playWhenReady = !value
        onPlaying(!value)
    }

    override val isPaused: Boolean get() = paused

    override fun setRate(value: Float) {
        rate = value
        player?.setPlaybackSpeed(value)
    }

    override fun setSubtitleTrack(id: Int?) {
        wantedText = id
        val p = player ?: return
        if (id == null) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        } else {
            applyTracks(p.currentTracks)
        }
    }

    override fun setAudioTrack(id: Int) {
        wantedAudio = id
        player?.let { applyTracks(it.currentTracks) }
    }

    override fun setScale(type: ScaleType) {
        scale = type
        applyScale()
    }

    override fun addSubtitleFiles(paths: List<String>) {
        subtitleFiles = (subtitleFiles + paths).distinct()
        subRetries = 0
        attachPendingSubs()
    }

    override fun stats(): Stats? {
        val p = player ?: return null
        val v = p.videoFormat
        val a = p.audioFormat
        val vc = p.videoDecoderCounters?.also { it.ensureUpdated() }
        val ac = p.audioDecoderCounters?.also { it.ensureUpdated() }
        return Stats(
            videoCodec = v?.sampleMimeType?.let(::codecName),
            width = v?.width?.coerceAtLeast(0) ?: 0,
            height = v?.height?.coerceAtLeast(0) ?: 0,
            fps = v?.frameRate?.takeIf { it > 0 }?.toDouble() ?: 0.0,
            audioCodec = a?.sampleMimeType?.let(::codecName),
            channels = a?.channelCount?.coerceAtLeast(0) ?: 0,
            sampleRate = a?.sampleRate?.coerceAtLeast(0) ?: 0,
            language = a?.language,
            inputKbps = bandwidthKbps,
            contentKbps = (v?.bitrate?.takeIf { it > 0 } ?: 0) / 1000.0,
            displayed = vc?.renderedOutputBufferCount ?: 0,
            lostFrames = (vc?.droppedBufferCount ?: 0) + (vc?.skippedOutputBufferCount ?: 0),
            lostBuffers = ac?.droppedBufferCount ?: 0,
            cacheMs = p.totalBufferedDuration.toInt(),
            engine = listOfNotNull("Media3", videoDecoder, audioDecoder).joinToString("  ·  "),
        )
    }

    private fun codecName(mime: String): String = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_DIVX -> "MPEG-4"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> mime.substringAfter('/').uppercase()
    }

    private fun build(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            // A read waits on a torrent piece that has not arrived; the default 8 s ended the film.
            .setReadTimeoutMs(120_000)
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        // The TV's own decoders first; FFmpeg for the audio it has none for (DTS, TrueHD, …).
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, cacheMs, cacheMs)
            // ponytail: fixed 64 MB (~6 s of 4K) — the default 128 MB is a lot of heap on a weak TV; tune if 4K rebuffers.
            .setTargetBufferBytes(64 * 1024 * 1024)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors))
            .setLoadControl(load)
            // Lands on a keyframe: a precise seek over a torrent waits on data we may not have.
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
            .also { p ->
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                p.setVideoSurfaceView(surface)
                p.addListener(listener)
                p.addAnalyticsListener(analytics)
            }
    }

    private fun prepare() {
        val uri = source?.takeIf { it.isNotBlank() } ?: return
        try {
            val p = player ?: build().also { player = it }
            val files = subtitleFiles.filter { subtitleMime(it) != null && File(it).let { f -> f.exists() && f.length() > 0L } }
            attached = files
            val item = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(
                files.map { path ->
                    val f = File(path)
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                        .setMimeType(subtitleMime(path))
                        .setLabel(f.nameWithoutExtension.replace('_', ' '))
                        .build()
                },
            ).build()
            announced = false
            gaveUp = false
            p.setMediaItem(item, startMs)
            p.setPlaybackSpeed(rate)
            p.playWhenReady = !paused
            p.prepare()
            removeCallbacks(tick)
            post(tick)
            attachPendingSubs()
        } catch (e: Throwable) {
            Log.e(TAG, "could not start playback", e)
            giveUp(e.message ?: "Playback could not start")
        }
    }

    /** Sidecar files from the torrent often land after the video starts; take them as they do. */
    private fun attachPendingSubs() {
        removeCallbacks(attachSubs)
        val p = player ?: return
        val pending = subtitleFiles.filter { it !in attached && subtitleMime(it) != null }
        if (pending.isEmpty()) return
        if (pending.any { File(it).let { f -> f.exists() && f.length() > 0L } }) {
            reload(p.currentPosition)
            return
        }
        if (subRetries++ < 20) postDelayed(attachSubs, 1500)
    }

    private fun playable(t: MediaTracks, type: Int): List<Pair<TrackGroup, Int>> =
        t.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it, true) }.map { g.mediaTrackGroup to it } }

    private fun selected(t: MediaTracks, type: Int): Int {
        val tracks = playable(t, type)
        return tracks.indexOfFirst { (group, i) -> t.groups.any { it.mediaTrackGroup == group && it.isTrackSelected(i) } }
    }

    /** Ids are indexes, so a pick outlives a reload — the new item's groups are new objects. */
    private fun applyTracks(t: MediaTracks) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        var changed = false
        wantedText?.let { want ->
            val pick = playable(t, C.TRACK_TYPE_TEXT).getOrNull(want)
            if (pick != null && selected(t, C.TRACK_TYPE_TEXT) != want) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(pick.first, pick.second))
                changed = true
            }
        }
        wantedAudio?.let { want ->
            val pick = playable(t, C.TRACK_TYPE_AUDIO).getOrNull(want)
            if (pick != null && selected(t, C.TRACK_TYPE_AUDIO) != want) {
                b.setOverrideForType(TrackSelectionOverride(pick.first, pick.second))
                changed = true
            }
        }
        if (changed) p.trackSelectionParameters = b.build()
    }

    private fun sendTracks(t: MediaTracks) {
        fun name(f: Format, fallback: String) = f.label?.takeIf { it.isNotBlank() }
            ?: f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
            ?: fallback
        val text = playable(t, C.TRACK_TYPE_TEXT).mapIndexed { n, (g, i) -> Track(n, name(g.getFormat(i), "Subtitle ${n + 1}")) }
        val audio = playable(t, C.TRACK_TYPE_AUDIO).mapIndexed { n, (g, i) ->
            val f = g.getFormat(i)
            val extra = listOfNotNull(f.sampleMimeType?.let(::codecName), f.channelCount.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" ")
            Track(n, listOf(name(f, "Track ${n + 1}"), extra).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        val audioOn = selected(t, C.TRACK_TYPE_AUDIO)
        if (defaultAudio < 0 && wantedAudio == null) defaultAudio = audioOn
        onTracks(Tracks(text, audio, audioOn, defaultAudio.takeIf { it >= 0 } ?: audioOn, selected(t, C.TRACK_TYPE_TEXT)))
    }

    private fun applyScale() {
        val w = videoSize.width
        val h = videoSize.height
        frame.resizeMode = when (scale) {
            ScaleType.SURFACE_FIT_SCREEN -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ScaleType.SURFACE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        frame.setAspectRatio(
            when (scale) {
                ScaleType.SURFACE_16_9 -> 16f / 9f
                ScaleType.SURFACE_4_3 -> 4f / 3f
                else -> if (h > 0) w * videoSize.pixelWidthHeightRatio / h else 0f
            },
        )
        val original = scale == ScaleType.SURFACE_ORIGINAL && w > 0 && h > 0
        frame.layoutParams = LayoutParams(if (original) w else LayoutParams.MATCH_PARENT, if (original) h else LayoutParams.MATCH_PARENT, Gravity.CENTER)
    }

    private fun report(value: Boolean) {
        if (value == buffering) return
        buffering = value
        onBuffering(value)
    }

    /** Stopped before handing over, so its sound does not play on under VLC's. */
    private fun giveUp(message: String) {
        if (gaveUp) return
        gaveUp = true
        runCatching { player?.stop() }
        onUnsupported(message)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            when (state) {
                Player.STATE_BUFFERING -> report(true)
                Player.STATE_READY -> {
                    report(false)
                    ioRetries = 0
                    if (!announced && p.duration != C.TIME_UNSET) {
                        announced = true
                        onLoaded(p.duration)
                    }
                }
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Audio focus lost or headphones out: Media3 paused itself.
            paused = !playWhenReady
            keepScreenOn = playWhenReady
            onPlaying(playWhenReady)
        }

        override fun onTracksChanged(tracks: MediaTracks) {
            if (tracks.isEmpty) return
            // No error for a stream it cannot decode: it would play on without picture or sound.
            for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
                if (tracks.containsType(type) && !tracks.isTypeSupported(type, true)) {
                    giveUp(if (type == C.TRACK_TYPE_VIDEO) "No decoder for this video" else "No decoder for this audio")
                    return
                }
            }
            applyTracks(tracks)
            sendTracks(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoSize = size
            applyScale()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error ${error.errorCodeName}", error)
            val p = player
            // 2xxx is input/output: the stream dropped, not a file it cannot play. Reopen there.
            if (error.errorCode / 1000 == 2 && ioRetries++ < 3 && p != null) {
                reload(p.currentPosition)
                return
            }
            giveUp(error.message ?: "This file could not be played")
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            bandwidthKbps = bitrateEstimate / 1000.0
        }
    }

    fun release() {
        removeCallbacks(tick)
        removeCallbacks(attachSubs)
        player?.let { p ->
            p.removeListener(listener)
            runCatching { p.release() }
        }
        player = null
        announced = false
        buffering = false
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
