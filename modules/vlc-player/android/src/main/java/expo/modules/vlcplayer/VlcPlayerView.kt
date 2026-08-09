package expo.modules.vlcplayer

import android.content.Context
import android.graphics.Bitmap
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
  /** Applied on load — tracks do not exist until the media is parsed. */
  private var wantedSpu: Int? = null
  private var announced = false
  private var restartQueued = false
  /** Buffering flaps at 100% constantly; only report real changes. */
  private var buffering = false
  /** External subtitle file paths (local files from the torrent). */
  private var subtitleFiles: List<String>? = null
  /** Survives a restart(), which rebuilds the player it is set on. */
  private var scale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

  /**
   * The video surface is added here, not by React, and React Native does not
   * run a layout pass over children it does not know about — the surface stays
   * 0x0 and the screen is black. This opts into Android's own layout, which is
   * what ExpoView provides for exactly this case.
   */
  override val shouldUseAndroidLayout = true

  init {
    addView(videoLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
    subtitleFiles = paths
    val p = player ?: return
    val m = p.media ?: return
    (paths ?: emptyList()).forEach { subPath ->
      val subFile = File(subPath)
      if (subFile.exists()) {
        m.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, Uri.fromFile(subFile).toString()))
      }
    }
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
      // The stream is local HTTP over a torrent, so it can stall at any moment;
      // a deeper buffer rides over the gaps the swarm leaves.
      val instance = LibVLC(
        context,
        arrayListOf(
          "--network-caching=3000",
          "--file-caching=3000",
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
      subtitleFiles?.forEach { subPath ->
        val subFile = File(subPath)
        if (subFile.exists()) {
          media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, Uri.fromFile(subFile).toString()))
        }
      }
      mediaPlayer.media = media
      media.release()

      vlc = instance
      player = mediaPlayer
      announced = false
      mediaPlayer.rate = rate
      if (!paused) mediaPlayer.play()
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
    wantedSpu?.let { p.setSpuTrack(it) }
    onLoaded(mapOf("duration" to length / 1000.0))
    sendTracks(p)
  }

  private fun sendTracks(p: MediaPlayer) {
    // id -1 is VLC's "Disable" entry — the UI has its own Off row.
    val subtitles = (p.spuTracks ?: emptyArray())
      .filter { it.id >= 0 }
      .map { mapOf("id" to it.id, "name" to it.name) }
    val audio = (p.audioTracks ?: emptyArray())
      .filter { it.id >= 0 }
      .map { mapOf("id" to it.id, "name" to it.name) }
    onTracks(mapOf("subtitles" to subtitles, "audio" to audio))
  }

  fun release() {
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
  }

  override fun onDetachedFromWindow() {
    release()
    super.onDetachedFromWindow()
  }
}
