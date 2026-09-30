package expo.modules.vlcplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream

private const val TAG = "VlcPlayer"

/** Poster tiles are small; a full-resolution frame is pure waste. */
private const val POSTER_WIDTH = 320

/** Cache-dir posters kept before the oldest are dropped. */
private const val POSTER_KEEP = 60

/**
 * A video surface backed by libVLC.
 *
 * The engine matters more than the wrapper here. Media3 hands AC3, E-AC3 and
 * DTS to the platform MediaCodec, and most Android devices ship no decoder for
 * them — so a release with `AC3 5.1` in the name plays as picture with silence.
 * libVLC carries its own decoders and renders bitmap subtitles (PGS, VOBSUB)
 * besides, which is the other half of what ExoPlayer could not do.
 */
class VlcPlayerView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onLoaded by EventDispatcher()
  private val onProgress by EventDispatcher()
  private val onBuffering by EventDispatcher()
  private val onEnded by EventDispatcher()
  private val onFailed by EventDispatcher()
  private val onTracks by EventDispatcher()

  private val videoLayout = VLCVideoLayout(context)
  private var vlc: LibVLC? = null
  private var player: MediaPlayer? = null

  private var source: String? = null
  private var startMs = 0L
  private var paused = false
  private var rate = 1f
  /**
   * libVLC's own buffer, in milliseconds of stream.
   *
   * A fixed 1000 is one second whatever the bitrate — fine for a 700MB encode,
   * and about 8MB for a 4K remux, which is gone the instant a piece is late.
   * The caller sets this from the bitrate.
   */
  private var cacheMs = 1000
  /** Applied on load — tracks do not exist until the media is parsed. */
  private var wantedSpu: Int? = null
  private var announced = false
  private var restartQueued = false
  /** Buffering flaps at 100% constantly; only report real changes. */
  private var buffering = false
  /** External subtitle file paths (local files from the torrent). */
  private var subtitleFiles: List<String>? = null
  /** Paths already handed to libVLC — avoid attaching the same slave twice. */
  private val attachedSubs = mutableSetOf<String>()
  /** Sidecar files often land after the video is already playing. */
  private var subRetries = 0
  private val attachSubs = Runnable { attachPendingSubs() }
  /** Survives a restart(), which rebuilds the player it is set on. */
  private var scale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
  /** Audio track VLC picked on load — the picker marks this as Default. */
  private var defaultAudioId: Int? = null
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var focusRequest: AudioFocusRequest? = null
  private var holdingFocus = false
  private val onAudioFocus = AudioManager.OnAudioFocusChangeListener { change ->
    if (change == AudioManager.AUDIOFOCUS_LOSS ||
      change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    ) {
      pause()
    }
  }

  /**
   * The video surface is added here, not by React, and React Native does not
   * run a layout pass over children it does not know about — the surface stays
   * 0x0 and the screen is black. This opts into Android's own layout, which is
   * what ExpoView provides for exactly this case.
   */
  override val shouldUseAndroidLayout = true

  init {
    addView(videoLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    // The television's screen saver does not care that a film is playing — it
    // watches for input, and a remote nobody is touching looks like an idle
    // set. The engine's PARTIAL_WAKE_LOCK deliberately does not cover this:
    // partial locks keep the CPU up and let the panel sleep. This is the flag
    // that covers it, and it follows the view, so it lifts by itself when the
    // player unmounts.
    keepScreenOn = true
  }

  /* ------------------------------------------------------------------ stats */

  /**
   * libVLC's input statistics and the playing tracks, for the info panel. Called
   * about once a second, only while that panel is open. `media` is a new
   * reference each call and must be released; null before anything is loaded.
   */
  fun stats(): Map<String, Any?>? {
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
      return mapOf(
        // The codec's fourcc, named — not `description`, which is the track's
        // title and in these releases usually the group's name.
        "videoCodec" to video?.codec?.let(::codecName),
        "width" to (video?.width ?: 0),
        "height" to (video?.height ?: 0),
        "fps" to (video?.takeIf { it.frameRateDen > 0 }
          ?.let { it.frameRateNum.toDouble() / it.frameRateDen } ?: 0.0),
        "audioCodec" to audio?.codec?.let(::codecName),
        "channels" to (audio?.channels ?: 0),
        "sampleRate" to (audio?.rate ?: 0),
        "language" to audio?.language,
        // × 8000 = kb/s — the scaling VLC's own statistics use.
        "inputKbps" to ((s?.inputBitrate ?: 0f) * 8000.0),
        "contentKbps" to ((s?.demuxBitrate ?: 0f) * 8000.0),
        "displayed" to (s?.displayedPictures ?: 0),
        "lostFrames" to (s?.lostPictures ?: 0),
        "lostBuffers" to (s?.lostAbuffers ?: 0),
        "cacheMs" to cacheMs
      )
    } finally {
      runCatching { m.release() }
    }
  }

  private fun codecName(fourcc: String): String = when (fourcc.trim().lowercase()) {
    "h264", "avc1", "x264" -> "H.264"
    "hevc", "h265", "hev1", "hvc1" -> "HEVC (H.265)"
    "av01" -> "AV1"
    "vp90", "vp09" -> "VP9"
    "mp4v", "xvid", "divx", "dx50" -> "MPEG-4 Part 2"
    "mpgv", "mp2v" -> "MPEG-2"
    "mp4a", "aac" -> "AAC"
    "a52", "ac-3", "ac3" -> "Dolby Digital (AC-3)"
    "eac3", "ec-3" -> "Dolby Digital Plus (E-AC-3)"
    "mlp", "trhd" -> "Dolby TrueHD"
    "dts", "dtsh", "dtsl" -> "DTS"
    "opus" -> "Opus"
    "vorb" -> "Vorbis"
    "flac" -> "FLAC"
    "mpga", "mp3" -> "MP3"
    else -> fourcc.trim().uppercase()
  }

  /**
   * Tell libVLC the size of the screen it is drawing on.
   *
   * libVLC's own helper is meant to, but it finds the window through the
   * view's Activity, and a React Native view's context does not lead to one —
   * it returns without a word and the size is never set. Not knowing it,
   * libVLC makes the subtitle surface as big as the *video*: for a 4K film a
   * 3840×2076 translucent layer, cleared and redrawn (32MB) on the thread that
   * presents video at every subtitle change, and blended by the GPU on every
   * frame. On this television that cost one frame in twelve — 13 drops in three
   * minutes with subtitles off, 12–21 every ten seconds with them on. Sized to
   * the 1080p screen it is a quarter of the work, and the text is drawn at the
   * resolution it is shown at.
   *
   * Said twice: the first can land before the output has registered to hear it.
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

  private var dropsLoggedAt = 0L
  private var dropsLogged = 0

  /**
   * One line whenever libVLC has dropped more frames, at most every ten
   * seconds. A judder nobody can describe is either frames being dropped or the
   * panel's own 24-into-60 cadence, and this is what tells them apart after the
   * fact.
   */
  private fun logDrops(p: MediaPlayer) {
    val now = System.currentTimeMillis()
    if (now - dropsLoggedAt < 10_000) return
    dropsLoggedAt = now
    val m = runCatching { p.media }.getOrNull() ?: return
    try {
      val s = runCatching { m.stats }.getOrNull() ?: return
      if (s.lostPictures != dropsLogged) {
        Log.i(
          TAG,
          "frames: ${s.lostPictures - dropsLogged} dropped in the last stretch " +
            "(${s.lostPictures} of ${s.displayedPictures + s.lostPictures} so far), " +
            "${s.lostAbuffers} audio buffers lost, cache ${cacheMs}ms"
        )
        dropsLogged = s.lostPictures
      }
    } finally {
      runCatching { m.release() }
    }
  }

  /* ----------------------------------------------------------------- props */

  fun setCacheMs(value: Int) {
    // Read when the media loads; setSource defers that to the end of the prop
    // batch, so arriving in any order is fine.
    cacheMs = value.coerceIn(500, 8000)
  }

  fun setSource(uri: String?) {
    if (uri == source) return
    source = uri
    // Deferred, not immediate: startPosition and the track selection arrive as
    // separate prop writes in the same batch, and starting here would use
    // whatever had been set so far. By the time a posted runnable executes, the
    // whole batch has landed.
    if (!restartQueued) {
      restartQueued = true
      post {
        restartQueued = false
        restart()
      }
    }
  }

  fun setStartPosition(ms: Long) {
    startMs = ms
  }

  fun setPaused(value: Boolean) {
    paused = value
    // Paused is idle: someone who walks away mid-film should get the screen
    // saver they asked for, rather than a still frame burned in all night.
    keepScreenOn = !value
    if (value) dropAudioFocus() else takeAudioFocus()
    val p = player ?: return
    // Unconditional. Gating on isPlaying looks like an optimisation but leaves a
    // hole: while buffering, libVLC reports not-playing yet resumes on its own
    // the moment data arrives, so a pause asked for in that window was dropped
    // and the audio came back. Both calls are no-ops when already in that state.
    runCatching { if (value) p.pause() else p.play() }
  }

  /**
   * Same as setPaused(true), but callable directly rather than through a prop.
   *
   * Handing the stream to another player has to silence this one *before* the
   * other starts, and a prop update is a round trip through React that may not
   * land before the activity switches — which is how you end up hearing both.
   */
  fun pause() {
    paused = true
    keepScreenOn = false
    dropAudioFocus()
    runCatching { player?.pause() }
  }

  fun setRate(value: Float) {
    rate = value
    player?.rate = value
  }

  /** null turns subtitles off. libVLC uses -1 for "no track". */
  fun setSubtitleTrack(id: Int?) {
    wantedSpu = id
    player?.setSpuTrack(id ?: -1)
  }

  fun setAudioTrack(id: Int?) {
    if (id != null) player?.setAudioTrack(id)
  }

  /**
   * How the picture is fitted to the surface. libVLC's own enum — best fit,
   * fit screen (crop to fill), fill (stretch), 16:9, 4:3, original — so this is
   * a name lookup, not a matrix of our own.
   */
  fun setScaleType(name: String?) {
    scale = runCatching { MediaPlayer.ScaleType.valueOf(name ?: "") }
      .getOrDefault(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
    player?.videoScale = scale
  }

  fun setSubtitleFiles(paths: List<String>?) {
    val normalized = paths?.map { localPath(it) }?.filter { it.isNotEmpty() }
    subtitleFiles = normalized
    subRetries = 0
    attachPendingSubs()
  }

  private fun attachPendingSubs() {
    removeCallbacks(attachSubs)
    val p = player ?: return
    val pending = (subtitleFiles ?: emptyList()).filter { it !in attachedSubs }
    if (pending.isEmpty()) return
    var waiting = false
    for (path in pending) {
      val subFile = File(path)
      if (!subFile.exists() || subFile.length() == 0L) {
        waiting = true
        continue
      }
      // MediaPlayer.addSlave works while playing; Media.addSlave after setMedia
      // often does nothing — which is why picking a search result felt dead.
      val ok = p.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(subFile), false)
      if (ok) {
        attachedSubs.add(path)
        Log.i(TAG, "subtitle attached: ${subFile.name}")
      } else {
        Log.w(TAG, "subtitle attach failed: ${subFile.name}")
      }
    }
    if (attachedSubs.isNotEmpty()) {
      postDelayed({
        val pl = player ?: return@postDelayed
        pl.setSpuTrack(wantedSpu ?: -1)
        sendTracks(pl)
      }, 600)
    }
    if (waiting && subRetries++ < 20) postDelayed(attachSubs, 1500)
  }

  /** `file:///…` from JS, or a bare absolute path from older callers. */
  private fun localPath(uriOrPath: String): String {
    val raw = uriOrPath.trim()
    if (raw.startsWith("file://")) {
      return Uri.parse(raw).path ?: raw.removePrefix("file://")
    }
    return raw
  }

  /* ---------------------------------------------------------------- control */

  /** Reopen the stream at `ms`. Recovery for an end-of-stream that was really
   *  the HTTP connection dropping, not the end of the film. */
  fun reload(ms: Long) {
    startMs = ms
    restart()
  }

  fun seekTo(ms: Long) {
    val p = player ?: return
    // Two-arg form is the fast seek; the default does a precise one, which over
    // a torrent means waiting on data we may not have yet.
    p.setTime(ms, true)
  }

  /* ---------------------------------------------------------------- poster */

  /**
   * Copy the frame currently on screen.
   *
   * PixelCopy reads the decoded surface: no network, no container parse, so it
   * cannot disturb the torrent. The previous approach pointed a metadata
   * retriever at the streaming server, which re-read the file from byte 0 —
   * a different region entirely from the playhead. It parked on pieces that had
   * not arrived and superseded the playback connection, which the player then
   * reported as end-of-stream.
   */
  fun snapshot(promise: Promise) {
    val surface = videoSurface()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
      surface == null ||
      !surface.holder.surface.isValid ||
      surface.width <= 0 ||
      surface.height <= 0
    ) {
      promise.resolve(null)
      return
    }

    val frame = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    try {
      PixelCopy.request(
        surface,
        frame,
        { result ->
          val uri = if (result == PixelCopy.SUCCESS) write(frame) else null
          frame.recycle()
          promise.resolve(uri)
        },
        Handler(Looper.getMainLooper())
      )
    } catch (e: Throwable) {
      frame.recycle()
      Log.w(TAG, "snapshot failed", e)
      promise.resolve(null)
    }
  }

  private fun write(frame: Bitmap): String? = try {
    val dir = File(context.cacheDir, "posters").apply { mkdirs() }
    val height = (POSTER_WIDTH.toLong() * frame.height / maxOf(1, frame.width)).toInt()
    val scaled = Bitmap.createScaledBitmap(frame, POSTER_WIDTH, maxOf(1, height), true)
    val file = File(dir, "p-${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    if (scaled !== frame) scaled.recycle()

    // Cache dir is OS-managed, but a poster a minute for a three-hour film adds
    // up; keep the recent ones and let the rest go.
    dir.listFiles()
      ?.sortedByDescending { it.lastModified() }
      ?.drop(POSTER_KEEP)
      ?.forEach { runCatching { it.delete() } }

    "file://${file.absolutePath}"
  } catch (e: Throwable) {
    Log.w(TAG, "poster write failed", e)
    null
  }

  /**
   * libVLC's own video surface. Its id is resolved by name at runtime — library
   * resources merge into the app's table — so this does not compile against
   * libVLC's R class. The layout also holds a *subtitle* surface, which is
   * transparent and would copy as a blank frame.
   */
  private fun videoSurface(): SurfaceView? {
    val id = resources.getIdentifier("surface_video", "id", context.packageName)
    if (id != 0) {
      (videoLayout.findViewById<View>(id) as? SurfaceView)?.let { return it }
    }
    return firstSurface(videoLayout)
  }

  private fun firstSurface(view: View): SurfaceView? {
    if (view is SurfaceView) return view
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        firstSurface(view.getChildAt(i))?.let { return it }
      }
    }
    return null
  }

  /* --------------------------------------------------------------- lifecycle */

  private fun restart() {
    release()
    val uri = source?.takeIf { it.isNotBlank() } ?: return

    try {
      // --file-caching / --network-caching are milliseconds of demux buffer,
      // sized by the screen from the file's bitrate (see cacheMsFor).
      val instance = LibVLC(
        context,
        arrayListOf(
          "--network-caching=$cacheMs",
          "--file-caching=$cacheMs",
          "--no-video-title-show",
          "--audio-time-stretch"
        )
      )
      val mediaPlayer = MediaPlayer(instance)
      mediaPlayer.attachViews(videoLayout, null, true, false)
      mediaPlayer.videoScale = scale
      mediaPlayer.setEventListener(::onVlcEvent)

      val media = Media(instance, Uri.parse(uri))
      // Hardware decode with software fallback: HEVC 10-bit is common in these
      // releases and not every device decodes it in hardware.
      media.setHWDecoderEnabled(true, true)
      // Seconds, fractional. Starting the demuxer here rather than seeking
      // after play means no decoding from byte zero of a two-hour file.
      if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
      // External subtitle files alongside the video (e.g. from the torrent)
      attachedSubs.clear()
      subRetries = 0
      removeCallbacks(attachSubs)
      subtitleFiles?.forEach { subPath ->
        val path = localPath(subPath)
        val subFile = File(path)
        if (subFile.exists() && subFile.length() > 0L) {
          media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, Uri.fromFile(subFile).toString()))
          attachedSubs.add(path)
        }
      }
      mediaPlayer.media = media
      media.release()

      vlc = instance
      player = mediaPlayer
      announced = false
      dropsLogged = 0
      mediaPlayer.rate = rate
      if (!paused) {
        takeAudioFocus()
        mediaPlayer.play()
      }
      mediaPlayer.setSpuTrack(wantedSpu ?: -1)
      attachPendingSubs()
    } catch (e: Throwable) {
      Log.e(TAG, "could not start playback", e)
      onFailed(mapOf("message" to (e.message ?: "Playback could not start")))
    }
  }

  private fun onVlcEvent(event: MediaPlayer.Event) {
    val p = player ?: return
    when (event.type) {
      MediaPlayer.Event.Playing -> {
        report(false)
        if (!announced) announceLoaded(p)
      }

      MediaPlayer.Event.LengthChanged -> if (!announced) announceLoaded(p)

      // The video output exists now, so it can be told how big the screen is.
      MediaPlayer.Event.Vout -> if (event.voutCount > 0) tellWindowSize()

      MediaPlayer.Event.TimeChanged -> {
        onProgress(mapOf("currentTime" to event.timeChanged / 1000.0))
        logDrops(p)
      }

      // Buffering fires continuously; 100 means it is done.
      MediaPlayer.Event.Buffering -> report(event.buffering < 100f)

      MediaPlayer.Event.EndReached -> onEnded(emptyMap())

      MediaPlayer.Event.EncounteredError ->
        onFailed(mapOf("message" to "This file could not be played"))

      // Tracks appear as the demuxer finds them, not all at once.
      MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> sendTracks(p)

      else -> Unit
    }
  }

  private fun report(value: Boolean) {
    if (value == buffering) return
    buffering = value
    onBuffering(mapOf("isBuffering" to value))
  }

  private fun announceLoaded(p: MediaPlayer) {
    val length = p.length
    if (length <= 0) return
    announced = true
    // `?: -1` and not `?.let`, which is what this was: with no track chosen the
    // old form applied nothing, and libVLC turns the first subtitle track on by
    // itself. Subtitles appeared while the picker still said Off, and choosing
    // Off did nothing because that was already the state it thought it was in.
    p.setSpuTrack(wantedSpu ?: -1)
    if (defaultAudioId == null) {
      val id = p.audioTrack
      if (id >= 0) defaultAudioId = id
    }
    onLoaded(mapOf("duration" to length / 1000.0))
    sendTracks(p)
  }

  private fun sendTracks(p: MediaPlayer) {
    // id -1 is VLC's "Disable" entry — the UI has its own Off row.
    val subtitles = (p.spuTracks ?: emptyArray())
      .filter { it.id >= 0 }
      .mapIndexed { i, it ->
        val raw = it.name?.trim().orEmpty()
        // libVLC often labels external slaves as "..." — useless in the picker.
        val label =
          if (raw.isEmpty() || raw == "..." || raw == "—" || raw.equals("Track", true)) {
            "Subtitle ${i + 1}"
          } else {
            raw.replace(Regex("\\.zip$", RegexOption.IGNORE_CASE), ".srt")
          }
        mapOf("id" to it.id, "name" to label)
      }
    val audio = (p.audioTracks ?: emptyArray())
      .filter { it.id >= 0 }
      .map { mapOf("id" to it.id, "name" to it.name) }
    onTracks(
      mapOf(
        "subtitles" to subtitles,
        "audio" to audio,
        "audioId" to p.audioTrack,
        "defaultAudio" to (defaultAudioId ?: p.audioTrack),
      )
    )
  }

  /** Pause Spotify / other apps for the duration of playback. */
  private fun takeAudioFocus() {
    if (holdingFocus) return
    val granted = if (Build.VERSION.SDK_INT >= 26) {
      val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        )
        .setOnAudioFocusChangeListener(onAudioFocus)
        .build()
      focusRequest = req
      audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        onAudioFocus,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN,
      ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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

  fun release() {
    dropAudioFocus()
    removeCallbacks(attachSubs)
    subRetries = 0
    player?.let { p ->
      runCatching { p.setEventListener(null) }
      runCatching { if (p.isPlaying) p.stop() }
      runCatching { p.detachViews() }
      runCatching { p.release() }
    }
    runCatching { vlc?.release() }
    player = null
    vlc = null
    announced = false
    buffering = false
    defaultAudioId = null
  }

  override fun onDetachedFromWindow() {
    release()
    super.onDetachedFromWindow()
  }
}
