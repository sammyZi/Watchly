package expo.modules.vlcplayer

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
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
  private val onRemote by EventDispatcher()

  /**
   * What makes a headset's play/pause button reach this player.
   *
   * Android hands a Bluetooth AVRCP press to whichever app holds an *active*
   * media session; audio focus alone is not enough. Without one the buttons
   * went to whatever else had a session — usually nothing, so they did nothing.
   * Registered on the platform class rather than the support library, because
   * that needs no dependency this module does not already have.
   */
  private var session: MediaSession? = null

  private val videoLayout = VLCVideoLayout(context)
  private var vlc: LibVLC? = null
  private var player: MediaPlayer? = null

  private var source: String? = null
  private var startMs = 0L
  private var paused = false
  private var rate = 1f
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
    startSession()
  }

  /**
   * Take the media buttons, and tell React what was pressed.
   *
   * The session acts on the player directly so the button works even if React
   * is busy, and *also* emits — otherwise the overlay would still be drawing a
   * pause icon over a paused film.
   */
  private fun startSession() {
    runCatching {
      val ms = MediaSession(context, "watchly")
      // Vendor ROMs that route media buttons by receiver rather than by which
      // session is playing will not see this app without one. Harmless on stock
      // Android, where the active session wins anyway.
      runCatching {
        ms.setMediaButtonReceiver(
          PendingIntent.getBroadcast(
            context,
            0,
            Intent(Intent.ACTION_MEDIA_BUTTON).setClass(context, MediaButtons::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
          )
        )
      }
      // Some ROMs treat a session with no metadata as not worth routing to.
      runCatching {
        ms.setMetadata(
          MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Watchly")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, player?.length ?: 0L)
            .build()
        )
      }
      ms.setCallback(object : MediaSession.Callback() {
        override fun onPlay() = remotePlay(false)

        override fun onPause() = remotePlay(true)

        override fun onStop() = remotePlay(true)

        // Seeking is this player's own business, but next/previous belong to
        // whatever is playing them — React owns the queue, so it decides.
        override fun onSkipToNext() = onRemote(mapOf("action" to "next"))
        override fun onSkipToPrevious() = onRemote(mapOf("action" to "previous"))

        override fun onSeekTo(pos: Long) {
          seekTo(pos)
          onRemote(mapOf("action" to "seek", "position" to pos / 1000.0))
        }
      })
      ms.isActive = true
      session = ms
      live = this
      pushState()
      /**
       * The legacy registration as well, deliberately.
       *
       * It has been deprecated since API 21 and on stock Android the session
       * above is enough. But this phone's ROM keeps its own
       * "VivoMediaButtonReceiver" slot, and that slot is filled by this call —
       * not by owning a session. Whatever last called it was still receiving
       * every press, so a modern-only implementation could not win.
       */
      runCatching {
        @Suppress("DEPRECATION")
        audioManager.registerMediaButtonEventReceiver(
          ComponentName(context, MediaButtons::class.java)
        )
      }
      Log.i(TAG, "media session active")
    }.onFailure { Log.w(TAG, "media session unavailable", it) }
  }

  companion object {
    /** The player a media button should act on — there is only ever one on
     *  screen, and the receiver is a separate object with no handle to it. */
    private var live: VlcPlayerView? = null

    fun onMediaKey(code: Int) {
      val view = live ?: return
      view.post {
        when (code) {
          KeyEvent.KEYCODE_MEDIA_PLAY -> view.remotePlay(false)
          KeyEvent.KEYCODE_MEDIA_PAUSE -> view.remotePlay(true)
          KeyEvent.KEYCODE_MEDIA_STOP -> view.remotePlay(true)
          KeyEvent.KEYCODE_HEADSETHOOK,
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> view.remotePlay(!view.paused)
          KeyEvent.KEYCODE_MEDIA_NEXT -> view.onRemote(mapOf("action" to "next"))
          KeyEvent.KEYCODE_MEDIA_PREVIOUS -> view.onRemote(mapOf("action" to "previous"))
          else -> Unit
        }
      }
    }
  }

  /** Act, then tell React, so the overlay does not keep drawing the old icon. */
  private fun remotePlay(pause: Boolean) {
    setPaused(pause)
    onRemote(mapOf("action" to if (pause) "pause" else "play"))
  }

  /** Headsets and car stereos read the state, not the player — a session that
   *  never reports one is treated as idle and its buttons are ignored. */
  private fun pushState() {
    val ms = session ?: return
    val position = runCatching { player?.time ?: 0L }.getOrDefault(0L)
    runCatching {
      ms.setPlaybackState(
        PlaybackState.Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_STOP or
              PlaybackState.ACTION_SEEK_TO or
              PlaybackState.ACTION_SKIP_TO_NEXT or
              PlaybackState.ACTION_SKIP_TO_PREVIOUS
          )
          .setState(
            if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
            position,
            if (paused) 0f else rate
          )
          .build()
      )
    }
  }

  /* ----------------------------------------------------------------- props */

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
    if (value) dropAudioFocus() else takeAudioFocus()
    val p = player ?: return
    // Unconditional. Gating on isPlaying looks like an optimisation but leaves a
    // hole: while buffering, libVLC reports not-playing yet resumes on its own
    // the moment data arrives, so a pause asked for in that window was dropped
    // and the audio came back. Both calls are no-ops when already in that state.
    runCatching { if (value) p.pause() else p.play() }
    pushState()
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
    dropAudioFocus()
    runCatching { player?.pause() }
    pushState()
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
      // --file-caching / --network-caching are milliseconds of demux buffer.
      // The torrent window already holds tens of MB ahead; 1s covers a piece
      // stall without adding another 3s of black screen after ready.
      val instance = LibVLC(
        context,
        arrayListOf(
          // Native torrent window already holds tens of MB ahead of the
          // playhead. 1s is enough to ride a piece stall; 3s was extra black
          // screen after the URL was already handed over.
          "--network-caching=1000",
          "--file-caching=1000",
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

      MediaPlayer.Event.TimeChanged ->
        onProgress(mapOf("currentTime" to event.timeChanged / 1000.0))

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
    // Left active, it keeps claiming the headset's buttons after the player is
    // gone — pressing play would resume nothing at all.
    runCatching {
      session?.isActive = false
      session?.release()
    }
    session = null
    runCatching {
      @Suppress("DEPRECATION")
      audioManager.unregisterMediaButtonEventReceiver(
        ComponentName(context, MediaButtons::class.java)
      )
    }
    if (live === this) live = null
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
