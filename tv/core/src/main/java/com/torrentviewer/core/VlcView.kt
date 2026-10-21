package com.torrentviewer.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

private const val TAG = "VlcView"

/** One selectable stream inside the media. `id` is libVLC's, not an index. */
data class Track(val id: Int, val name: String)

data class Tracks(val subtitles: List<Track>, val audio: List<Track>, val audioId: Int, val defaultAudio: Int, /** What libVLC is showing now; -1 is off. */ val spuId: Int = -1)

data class Stats(
    val videoCodec: String?,
    val width: Int,
    val height: Int,
    val fps: Double,
    val audioCodec: String?,
    val channels: Int,
    val sampleRate: Int,
    val language: String?,
    val inputKbps: Double,
    val contentKbps: Double,
    val displayed: Int,
    val lostFrames: Int,
    val lostBuffers: Int,
    val cacheMs: Int,
    /** Which backend, and for Media3 the decoders it picked. */
    val engine: String = "VLC",
)

/** What the player screen drives: Media3 ([ExoView]) or libVLC ([VlcView]). */
abstract class Video(context: Context) : FrameLayout(context) {
    var onLoaded: (durationMs: Long) -> Unit = {}
    var onTime: (ms: Long) -> Unit = {}
    var onBuffering: (Boolean) -> Unit = {}
    var onEnded: () -> Unit = {}
    var onFailed: (String) -> Unit = {}
    /** This backend cannot play the file, though another might. */
    var onUnsupported: (String) -> Unit = { onFailed(it) }
    var onTracks: (Tracks) -> Unit = {}
    var onPlaying: (Boolean) -> Unit = {}
    /** Subtitle text; null is the player's own default. Set before [open] — libVLC reads it only when it starts. */
    open var subtitleStyle: SubtitleStyle? = null

    abstract fun open(url: String, startAt: Long, cacheMs: Int, subtitles: List<String>)
    abstract fun reload(ms: Long)
    abstract val time: Long
    abstract fun seekTo(ms: Long)
    abstract fun setPaused(value: Boolean)
    abstract val isPaused: Boolean
    abstract fun setRate(value: Float)
    abstract fun setSubtitleTrack(id: Int?)
    abstract fun setAudioTrack(id: Int)
    abstract fun setScale(type: MediaPlayer.ScaleType)
    /**
     * [select]: turn the last of [paths] on once it is attached. Done here, where the new track
     * is known, and as one switch — picking "the last track" afterwards switched twice, off and
     * on, and could land on one of the film's own; each switch has the player resync the stream.
     */
    abstract fun addSubtitleFiles(paths: List<String>, select: Boolean = false)
    /** Positive shows subtitles later. False when this backend cannot shift them that way. */
    open fun setSubtitleDelay(ms: Long): Boolean = ms == 0L
    abstract fun stats(): Stats?
}

/**
 * A video surface backed by libVLC — the fallback to [ExoView]: software decode for what
 * the TV cannot, AVI, full ASS styling. It blends subtitles into the picture, which on a
 * weak TV costs 4K frames each time a line changes.
 */
class VlcView(context: Context) : Video(context) {
    private val videoLayout = VLCVideoLayout(context)
    private var vlc: LibVLC? = null
    /** The buffer [vlc] was made with — a reload at the same one keeps the instance. */
    private var vlcCacheMs = -1
    private var player: MediaPlayer? = null

    private var source: String? = null
    private var startMs = 0L
    private var paused = false
    private var rate = 1f
    private var cacheMs = 3000
    /** Applied on load — tracks do not exist until the media is parsed. */
    private var wantedSpu: Int? = null
    private var wantedAudio: Int? = null
    /** Reopened while paused: libVLC only opens a file it is told to play, so play, then pause on arrival. */
    private var pauseOnStart = false
    private var announced = false
    private var buffering = false
    private var subtitleFiles: List<String> = emptyList()
    private val attachedSubs = mutableSetOf<String>()
    private var subRetries = 0
    private val attachSubs = Runnable { attachPendingSubs() }
    private var scale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
    private var defaultAudioId: Int? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var holdingFocus = false
    private val onAudioFocus = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) setPaused(true)
    }

    init {
        addView(videoLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The TV screen saver watches for input, not for a film playing.
        keepScreenOn = true
    }

    /** Load [url], starting at [startAt] ms. libVLC's buffer is [cacheMs] of stream. */
    override fun open(url: String, startAt: Long, cacheMs: Int, subtitles: List<String>) {
        source = url
        startMs = startAt
        this.cacheMs = cacheMs.coerceIn(500, 8000)
        subtitleFiles = subtitles
        restart()
    }

    /**
     * Reopen at [ms], keeping pause, speed, picture and tracks. libVLC acts on a seek only
     * between reads, so with its read blocked on a piece that has not arrived a seek waits
     * for that piece; reopening abandons the read. Also the recovery for a dropped
     * connection that looked like the end.
     */
    override fun reload(ms: Long) {
        if (source == null) return
        startMs = ms
        restart()
    }

    override val time: Long get() = player?.time ?: 0L

    override fun seekTo(ms: Long) {
        // Fast seek; a precise one over a torrent waits on data we may not have.
        player?.setTime(ms, true)
    }

    override fun setPaused(value: Boolean) {
        paused = value
        // Paused is idle: let the screen saver have a still frame.
        keepScreenOn = !value
        if (value) dropAudioFocus() else takeAudioFocus()
        if (!value) pauseOnStart = false
        // Unconditional: while buffering libVLC reports not-playing yet resumes on its own.
        runCatching { if (value) player?.pause() else player?.play() }
        onPlaying(!value)
    }

    override val isPaused: Boolean get() = paused

    override fun setRate(value: Float) {
        rate = value
        player?.rate = value
    }

    private var spuDelayMs = 0L

    /** Either way: libVLC shifts the subtitle clock itself (in microseconds). */
    override fun setSubtitleDelay(ms: Long): Boolean {
        spuDelayMs = ms
        runCatching { player?.spuDelay = ms * 1000 }
        return true
    }

    /** null turns subtitles off. libVLC uses -1 for "no track". */
    override fun setSubtitleTrack(id: Int?) {
        wantedSpu = id
        player?.setSpuTrack(id ?: -1)
    }

    override fun setAudioTrack(id: Int) {
        wantedAudio = id
        player?.setAudioTrack(id)
    }

    override fun setScale(type: MediaPlayer.ScaleType) {
        scale = type
        player?.videoScale = type
    }

    /** A file to turn on as it is attached; see [addSubtitleFiles]. */
    private var selectOnAttach: String? = null

    override fun addSubtitleFiles(paths: List<String>, select: Boolean) {
        subtitleFiles = (subtitleFiles + paths).distinct()
        if (select) selectOnAttach = paths.lastOrNull()
        subRetries = 0
        attachPendingSubs()
    }

    private fun attachPendingSubs() {
        removeCallbacks(attachSubs)
        val p = player ?: return
        val pending = subtitleFiles.filter { it !in attachedSubs }
        if (pending.isEmpty()) return
        var waiting = false
        var added = false
        var chosen = false
        val before = (p.spuTracks ?: emptyArray()).map { it.id }.toSet()
        for (path in pending) {
            val file = File(path)
            // Sidecar files from the torrent often land after the video starts.
            if (!file.exists() || file.length() == 0L) {
                waiting = true
                continue
            }
            val select = path == selectOnAttach
            // MediaPlayer.addSlave works while playing; Media.addSlave after setMedia often does not.
            // Asked to select, libVLC turns the file on itself: that is the one switch.
            if (p.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), select)) {
                attachedSubs.add(path)
                added = true
                if (select) {
                    selectOnAttach = null
                    chosen = true
                }
            }
        }
        if (added) {
            postDelayed({
                val pl = player ?: return@postDelayed
                if (chosen) {
                    // Remember the new track as the choice; no switch of our own.
                    (pl.spuTracks ?: emptyArray()).map { it.id }.firstOrNull { it >= 0 && it !in before }?.let { wantedSpu = it }
                } else if (pl.spuTrack != (wantedSpu ?: -1)) {
                    // A file attached unasked must not turn itself on; only switch when it did.
                    pl.setSpuTrack(wantedSpu ?: -1)
                }
                sendTracks(pl)
            }, 600)
        }
        if (waiting && subRetries++ < 20) postDelayed(attachSubs, 1500)
    }

    override fun stats(): Stats? {
        val p = player ?: return null
        val m = runCatching { p.media }.getOrNull() ?: return null
        try {
            val s = runCatching { m.stats }.getOrNull()
            val video = runCatching { p.currentVideoTrack }.getOrNull()
            val audioId = runCatching { p.audioTrack }.getOrDefault(-1)
            var audio: IMedia.AudioTrack? = null
            for (i in 0 until runCatching { m.trackCount }.getOrDefault(0)) {
                val t = runCatching { m.getTrack(i) }.getOrNull() as? IMedia.AudioTrack ?: continue
                if (audio == null || t.id == audioId) audio = t
                if (t.id == audioId) break
            }
            return Stats(
                videoCodec = video?.codec?.let(::codecName),
                width = video?.width ?: 0,
                height = video?.height ?: 0,
                fps = video?.takeIf { it.frameRateDen > 0 }?.let { it.frameRateNum.toDouble() / it.frameRateDen } ?: 0.0,
                audioCodec = audio?.codec?.let(::codecName),
                channels = audio?.channels ?: 0,
                sampleRate = audio?.rate ?: 0,
                language = audio?.language,
                // × 8000 = kb/s, the scaling VLC's own statistics use.
                inputKbps = (s?.inputBitrate ?: 0f) * 8000.0,
                contentKbps = (s?.demuxBitrate ?: 0f) * 8000.0,
                displayed = s?.displayedPictures ?: 0,
                lostFrames = s?.lostPictures ?: 0,
                lostBuffers = s?.lostAbuffers ?: 0,
                cacheMs = cacheMs,
            )
        } finally {
            runCatching { m.release() }
        }
    }

    private fun codecName(fourcc: String): String = when (fourcc.trim().lowercase()) {
        "h264", "avc1", "x264" -> "H.264"
        "hevc", "h265", "hev1", "hvc1" -> "HEVC"
        "av01" -> "AV1"
        "vp90", "vp09" -> "VP9"
        "mp4v", "xvid", "divx", "dx50" -> "MPEG-4"
        "mpgv", "mp2v" -> "MPEG-2"
        "mp4a", "aac" -> "AAC"
        "a52", "ac-3", "ac3" -> "Dolby Digital"
        "eac3", "ec-3" -> "Dolby Digital+"
        "mlp", "trhd" -> "Dolby TrueHD"
        "dts", "dtsh", "dtsl" -> "DTS"
        "opus" -> "Opus"
        "vorb" -> "Vorbis"
        "flac" -> "FLAC"
        "mpga", "mp3" -> "MP3"
        else -> fourcc.trim().uppercase()
    }

    /**
     * Tell libVLC the screen size. Its own helper finds no Activity from here, and not
     * knowing, it makes a 4K subtitle surface redrawn on every change — dropped frames.
     */
    private fun tellWindowSize() {
        val tell = Runnable {
            val w = videoLayout.width
            val h = videoLayout.height
            if (w > 0 && h > 0) runCatching { player?.vlcVout?.setWindowSize(w, h) }
        }
        tell.run()
        postDelayed(tell, 500)
    }

    private fun restart() {
        release(keepVlc = vlcCacheMs == cacheMs)
        val uri = source?.takeIf { it.isNotBlank() } ?: return
        try {
            val instance = vlc ?: LibVLC(
                context,
                ArrayList(listOf(
                    "--network-caching=$cacheMs", "--file-caching=$cacheMs", "--no-video-title-show", "--audio-time-stretch",
                    // The HTTP read-ahead runs in its own thread and stays parked on the first
                    // missing piece; a seek waits for that read to return. 16MB by default sits
                    // right on the download edge, so most seeks — and the jump to the index on
                    // open — waited for a piece. From loopback, 4MB refills in milliseconds.
                    "--prefetch-buffer-size=4096", "--prefetch-read-size=1048576",
                ) + subtitleStyle?.let { s ->
                    listOf(
                        "--freetype-rel-fontsize=${SUB_SIZES[s.size] ?: 16}",
                        "--freetype-color=${if (s.yellow) 0xFFFF00 else 0xFFFFFF}",
                        "--freetype-background-color=0",
                        "--freetype-background-opacity=${if (s.box) 160 else 0}",
                    )
                }.orEmpty()),
            ).also { vlcCacheMs = cacheMs }
            val mediaPlayer = MediaPlayer(instance)
            mediaPlayer.attachViews(videoLayout, null, true, false)
            mediaPlayer.videoScale = scale
            mediaPlayer.setEventListener(::onVlcEvent)

            val media = Media(instance, Uri.parse(uri))
            // HEVC 10-bit is common and not every TV decodes it in hardware.
            media.setHWDecoderEnabled(true, true)
            // Start the demuxer there rather than seeking after play.
            if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
            attachedSubs.clear()
            subRetries = 0
            removeCallbacks(attachSubs)
            subtitleFiles.forEach { path ->
                val file = File(path)
                if (file.exists() && file.length() > 0L) {
                    media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, Uri.fromFile(file).toString()))
                    attachedSubs.add(path)
                }
            }
            mediaPlayer.media = media
            media.release()

            vlc = instance
            player = mediaPlayer
            announced = false
            mediaPlayer.rate = rate
            if (!paused) takeAudioFocus()
            pauseOnStart = paused
            mediaPlayer.play()
            mediaPlayer.setSpuTrack(wantedSpu ?: -1)
            if (spuDelayMs != 0L) runCatching { mediaPlayer.spuDelay = spuDelayMs * 1000 }
            attachPendingSubs()
        } catch (e: Throwable) {
            Log.e(TAG, "could not start playback", e)
            onFailed(e.message ?: "Playback could not start")
        }
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        val p = player ?: return
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                report(false)
                if (pauseOnStart) {
                    pauseOnStart = false
                    runCatching { p.pause() }
                } else {
                    onPlaying(true)
                }
                if (!announced) announceLoaded(p)
            }
            MediaPlayer.Event.Paused -> onPlaying(false)
            MediaPlayer.Event.LengthChanged -> if (!announced) announceLoaded(p)
            MediaPlayer.Event.Vout -> if (event.voutCount > 0) tellWindowSize()
            MediaPlayer.Event.TimeChanged -> onTime(event.timeChanged)
            // Fires continuously; 100 means done.
            MediaPlayer.Event.Buffering -> report(event.buffering < 100f)
            MediaPlayer.Event.EndReached -> onEnded()
            MediaPlayer.Event.EncounteredError -> onFailed("This file could not be played")
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> sendTracks(p)
            else -> Unit
        }
    }

    private fun report(value: Boolean) {
        if (value == buffering) return
        buffering = value
        onBuffering(value)
    }

    private fun announceLoaded(p: MediaPlayer) {
        val length = p.length
        if (length <= 0) return
        announced = true
        // -1, not "leave it": with nothing chosen libVLC turns the first track on itself.
        p.setSpuTrack(wantedSpu ?: -1)
        if (defaultAudioId == null) p.audioTrack.takeIf { it >= 0 }?.let { defaultAudioId = it }
        wantedAudio?.let { p.setAudioTrack(it) }
        onLoaded(length)
        sendTracks(p)
    }

    private fun sendTracks(p: MediaPlayer) {
        val subtitles = (p.spuTracks ?: emptyArray()).filter { it.id >= 0 }.mapIndexed { i, t ->
            // libVLC names external files "Track 1 - [/data/…/subs/EN_Film.srt]": keep the bracket,
            // and of a path only the file's own name.
            val raw = t.name?.trim().orEmpty().let { n ->
                Regex("""^Track \d+ - \[(.*)]$""").find(n)?.groupValues?.get(1)?.substringAfterLast('/')?.substringBeforeLast('.') ?: n
            }
            val label = if (raw.isEmpty() || raw == "..." || raw.equals("Track", true)) "Subtitle ${i + 1}"
            else raw.replace(Regex("\\.zip$", RegexOption.IGNORE_CASE), "").replace('_', ' ')
            Track(t.id, label)
        }
        val audio = (p.audioTracks ?: emptyArray()).filter { it.id >= 0 }.map { Track(it.id, it.name ?: "Track ${it.id}") }
        onTracks(Tracks(subtitles, audio, p.audioTrack, defaultAudioId ?: p.audioTrack, p.spuTrack))
    }

    private fun takeAudioFocus() {
        if (holdingFocus) return
        val granted = if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(onAudioFocus)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(onAudioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        holdingFocus = granted
    }

    private fun dropAudioFocus() {
        if (!holdingFocus) return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(onAudioFocus) }
        }
        holdingFocus = false
    }

    fun release(keepVlc: Boolean = false) {
        dropAudioFocus()
        removeCallbacks(attachSubs)
        subRetries = 0
        player?.let { p ->
            runCatching { p.setEventListener(null) }
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.detachViews() }
            runCatching { p.release() }
        }
        if (!keepVlc) {
            runCatching { vlc?.release() }
            vlc = null
            vlcCacheMs = -1
        }
        player = null
        announced = false
        buffering = false
        defaultAudioId = null
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private var hidden = false

    /**
     * The screen saver hides the window and abandons the video surface; libVLC's picture
     * dies with it ("cannot create EGL window surface") while the sound plays on, and it
     * never rebuilds the picture on the new surface. Reopen where it was when we come back.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            hidden = true
            return
        }
        if (!hidden) return
        hidden = false
        // Not loaded yet reports 0: keep the start point rather than rewind to the top.
        player?.let { reload(it.time.takeIf { t -> t > 0 } ?: startMs) }
    }
}
