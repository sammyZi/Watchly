package expo.modules.vlcplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/** The playback card in the shade — its own id and channel, apart from downloads'. */
private const val PLAYBACK_NOTIFICATION = 42101
private const val PLAYBACK_CHANNEL = "watchly_playback"

/** Poster width on the playback card, in pixels. */
private const val ARTWORK_WIDTH = 360

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
      session = ms
      updateMetadata()
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

  /** What the notification and the lock screen call this video. */
  private var title: String? = null

  fun setTitle(value: String?) {
    title = value
    updateMetadata()
    pushState()
  }

  /** The poster shown beside the title, and where it came from. */
  private var artworkUrl: String? = null
  private var art: Bitmap? = null

  /**
   * The film's or show's poster for the playback card, as other players show
   * theirs. Fetched off the main thread — a web address, or a still already on
   * disk — and dropped in when it lands; a slow or failed fetch just leaves the
   * card without a picture.
   */
  fun setArtwork(value: String?) {
    if (value == artworkUrl) return
    artworkUrl = value
    art = null
    if (value.isNullOrBlank()) {
      updateMetadata()
      pushState()
      return
    }
    Thread({
      val bitmap = runCatching {
        if (value.startsWith("http", true)) {
          val connection = java.net.URL(value).openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 15_000
          }
          connection.getInputStream().use { BitmapFactory.decodeStream(it) }
        } else {
          BitmapFactory.decodeFile(value.removePrefix("file://"))
        }
      }.getOrNull() ?: return@Thread
      // A notification icon, not a wallpaper: a full-size poster would be
      // megabytes pushed across to the system on every state change.
      val small = if (bitmap.width > ARTWORK_WIDTH) {
        Bitmap.createScaledBitmap(
          bitmap, ARTWORK_WIDTH, bitmap.height * ARTWORK_WIDTH / bitmap.width, true
        )
      } else {
        bitmap
      }
      post {
        if (artworkUrl != value) return@post
        art = small
        updateMetadata()
        pushState()
      }
    }, "watchly-artwork").start()
  }

  /** Title, length and poster — the shade's progress bar needs the length to draw. */
  private fun updateMetadata() {
    val ms = session ?: return
    runCatching {
      ms.setMetadata(
        MediaMetadata.Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "Watchly")
          .putLong(MediaMetadata.METADATA_KEY_DURATION, player?.length ?: 0L)
          .apply {
            // Android 13+ paints the media card's background from this.
            art?.let {
              putBitmap(MediaMetadata.METADATA_KEY_ART, it)
              putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            }
          }
          .build()
      )
    }
  }

  private val notifications =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  /**
   * The playback card in the notification shade and on the lock screen.
   *
   * Bound to the session, so Android 13+ draws its own media controls from the
   * session's actions — and many ROMs, this phone's among them, send a
   * headset's button to whichever app shows one. Media-session notifications
   * are exempt from the notification permission, so this needs no prompt.
   * The Play/Pause action is for older Android, which draws the buttons we give it.
   */
  private fun postNotification() {
    val ms = session ?: return
    if (player == null) return
    runCatching {
      if (Build.VERSION.SDK_INT >= 26 &&
        notifications.getNotificationChannel(PLAYBACK_CHANNEL) == null
      ) {
        notifications.createNotificationChannel(
          NotificationChannel(PLAYBACK_CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        )
      }
      val toggle = PendingIntent.getBroadcast(
        context,
        1,
        Intent(Intent.ACTION_MEDIA_BUTTON)
          .setClass(context, MediaButtons::class.java)
          .putExtra(
            Intent.EXTRA_KEY_EVENT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
          ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
      val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(
          context,
          0,
          it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
      }
      val builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, PLAYBACK_CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
      val icon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
      val notification = builder
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setLargeIcon(art)
        .setContentTitle(title ?: "Watchly")
        .setContentIntent(open)
        .setOngoing(!paused)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(
          Notification.Action.Builder(
            Icon.createWithResource(context, icon),
            if (paused) "Play" else "Pause",
            toggle
          ).build()
        )
        .setStyle(
          Notification.MediaStyle()
            .setMediaSession(ms.sessionToken)
            .setShowActionsInCompactView(0)
        )
        .build()
      notifications.notify(PLAYBACK_NOTIFICATION, notification)
    }.onFailure { Log.w(TAG, "playback notification failed", it) }
  }

  /** Headsets and car stereos read the state, not the player — a session that
   *  never reports one is treated as idle and its buttons are ignored. */
  private fun pushState() {
    updatePip()
    postNotification()
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

  /* ------------------------------------------------------ picture-in-picture */

  private val onPip by EventDispatcher()
  /** Go to PiP on Home while playing (Android 12+ does this itself from the params). */
  private var autoPip = false
  private var inPip = false

  fun setAutoPip(value: Boolean) {
    autoPip = value
    updatePip()
  }

  private fun canPip(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

  @android.annotation.TargetApi(Build.VERSION_CODES.O)
  private fun pipParams(): android.app.PictureInPictureParams {
    val builder = android.app.PictureInPictureParams.Builder()
    // The film's shape, inside the range Android accepts (it throws outside 1:2.39–2.39:1).
    val track = runCatching { player?.currentVideoTrack }.getOrNull()
    if (track != null && track.width > 0 && track.height > 0) {
      val sar = if (track.sarNum > 0 && track.sarDen > 0) track.sarNum.toDouble() / track.sarDen else 1.0
      val ratio = (track.width * sar / track.height).coerceIn(1 / 2.39, 2.39)
      builder.setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setAutoEnterEnabled(autoPip && !paused && player != null)
      builder.setSeamlessResizeEnabled(false) // video, not UI: let the system crossfade
    }
    return builder.build()
  }

  private fun updatePip() {
    if (!canPip()) return
    val activity = appContext.currentActivity ?: return
    runCatching { activity.setPictureInPictureParams(pipParams()) }
  }

  /** Shrink to a floating window. False where the device has no PiP. */
  fun enterPip(): Boolean {
    if (!canPip()) return false
    val activity = appContext.currentActivity ?: return false
    return runCatching { activity.enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
  }

  /** Entering and leaving PiP resizes the window, which arrives here as a configuration change. */
  override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
    super.onConfigurationChanged(newConfig)
    val now = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
      appContext.currentActivity?.isInPictureInPictureMode == true
    if (now == inPip) return
    inPip = now
    onPip(mapOf("active" to now))
  }

  /* ------------------------------------------------------------------ stats */

  /**
   * libVLC's input statistics and the playing tracks, for the stats panel. Called about once a
   * second, only while that panel is open. `media` is a new reference each call and must be
   * released; null before anything is loaded.
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
        // The codec's fourcc, named — not `description`, which is the track's title and
        // in these releases usually the group's name.
        "videoCodec" to video?.codec?.let(::codecName),
        "width" to (video?.width ?: 0),
        "height" to (video?.height ?: 0),
        "fps" to (video?.takeIf { it.frameRateDen > 0 }?.let { it.frameRateNum.toDouble() / it.frameRateDen } ?: 0.0),
        "audioCodec" to audio?.codec?.let(::codecName),
        "channels" to (audio?.channels ?: 0),
        "sampleRate" to (audio?.rate ?: 0),
        "language" to audio?.language,
        // × 8000 = kb/s — the scaling VLC's own statistics use.
        "inputKbps" to ((s?.inputBitrate ?: 0f) * 8000.0),
        "contentKbps" to ((s?.demuxBitrate ?: 0f) * 8000.0),
        "readBytes" to (s?.readBytes ?: 0),
        "displayed" to (s?.displayedPictures ?: 0),
        "lostFrames" to (s?.lostPictures ?: 0),
        "playedBuffers" to (s?.playedAbuffers ?: 0),
        "lostBuffers" to (s?.lostAbuffers ?: 0),
        "corrupted" to (s?.demuxCorrupted ?: 0),
        "discontinuities" to (s?.demuxDiscontinuity ?: 0)
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
    "vp80" -> "VP8"
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
    "araw", "s16l", "s24l", "lpcm" -> "PCM"
    else -> fourcc.trim().uppercase()
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

  /**
   * Kept, like [wantedSpu]: coming back to the app reloads the stream into a new player,
   * which starts on the file's default audio (an Italian dub, say) while the picker —
   * whose prop never changed — still showed the viewer's choice.
   */
  private var wantedAudio: Int? = null

  fun setAudioTrack(id: Int?) {
    wantedAudio = id
    if (id != null) player?.setAudioTrack(id)
  }

  /** Audio tracks appear as the demuxer finds them; take the wanted one when it does. */
  private fun applyWantedAudio(p: MediaPlayer) {
    val id = wantedAudio ?: return
    if (runCatching { p.audioTrack }.getOrDefault(id) != id) runCatching { p.setAudioTrack(id) }
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

  /** Subtitle offset in ms; positive shows them later. Kept for the next load,
   *  which starts a new player at zero. */
  private var spuDelayMs = 0

  fun setSubtitleDelay(ms: Int) {
    spuDelayMs = ms
    player?.let { runCatching { it.setSpuDelay(ms * 1000L) } }
  }

  /**
   * Subtitle text, as libVLC's freetype options — instance-wide, fixed when the
   * LibVLC instance is made. A change while something plays restarts it in
   * place, as `reload` does, so the new style shows straight away.
   * `subtitleSize` is VLC's relative size, where *smaller* is bigger text: 16
   * is normal, 12 large, 20 small.
   */
  var subtitleSize = 16
    set(value) {
      if (field == value) return
      field = value
      restyle()
    }
  var subtitleColor = 0xFFFFFF
    set(value) {
      if (field == value) return
      field = value
      restyle()
    }
  var subtitleBackground = false
    set(value) {
      if (field == value) return
      field = value
      restyle()
    }

  private var restylePending = false

  /** Posted, so the three style props of one batch cost one restart. Before the
   *  first load there is no player, and the load itself picks the options up. */
  private fun restyle() {
    if (player == null || restylePending) return
    restylePending = true
    post {
      restylePending = false
      val p = player ?: return@post
      // Still opening: no time yet, so keep the resume point it was opening at.
      reload(p.time.takeIf { it > 0L } ?: startMs)
    }
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
    // The shade's progress bar extrapolates from the last reported position.
    pushState()
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
    releasePlayer()
    val uri = source?.takeIf { it.isNotBlank() } ?: return
    // Gone if the view was detached and has come back (release() ran then).
    if (session == null) startSession()

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
          "--audio-time-stretch",
          // The same options VLC for Android sets from its subtitle settings.
          "--freetype-rel-fontsize=$subtitleSize",
          "--freetype-color=$subtitleColor",
          "--freetype-background-color=0",
          "--freetype-background-opacity=${if (subtitleBackground) 160 else 0}"
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
        pushState()
      }

      MediaPlayer.Event.LengthChanged -> {
        if (!announced) announceLoaded(p)
        updateMetadata()
      }

      // The picture's size is known: PiP takes its shape.
      MediaPlayer.Event.Vout -> updatePip()

      MediaPlayer.Event.TimeChanged ->
        onProgress(mapOf("currentTime" to event.timeChanged / 1000.0))

      // Buffering fires continuously; 100 means it is done.
      MediaPlayer.Event.Buffering -> report(event.buffering < 100f)

      MediaPlayer.Event.EndReached -> onEnded(emptyMap())

      MediaPlayer.Event.EncounteredError ->
        onFailed(mapOf("message" to "This file could not be played"))

      // Tracks appear as the demuxer finds them, not all at once.
      MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> {
        if (event.type == MediaPlayer.Event.ESAdded && announced &&
          event.esChangedType == IMedia.Track.Type.Audio && event.esChangedID == wantedAudio
        ) applyWantedAudio(p)
        sendTracks(p)
      }

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
    if (spuDelayMs != 0) runCatching { p.setSpuDelay(spuDelayMs * 1000L) }
    if (defaultAudioId == null) {
      val id = p.audioTrack
      if (id >= 0) defaultAudioId = id
    }
    // After the default is noted, so the picker still marks the file's own default.
    applyWantedAudio(p)
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

  /** Leaving the player for good: the libVLC side and the media session. */
  fun release() {
    // Leaving the player: Home must not shrink an empty screen into a window.
    // Here and not in releasePlayer, which runs on every load — straight after
    // the autoPip prop is set, so it was quietly switching auto-PiP off.
    autoPip = false
    releasePlayer()
    // Left active, it keeps claiming the headset's buttons after the player is
    // gone — pressing play would resume nothing at all.
    runCatching {
      session?.isActive = false
      session?.release()
    }
    session = null
    runCatching { notifications.cancel(PLAYBACK_NOTIFICATION) }
    runCatching {
      @Suppress("DEPRECATION")
      audioManager.unregisterMediaButtonEventReceiver(
        ComponentName(context, MediaButtons::class.java)
      )
    }
    if (live === this) live = null
  }

  /**
   * Only the libVLC side — what a reload tears down.
   *
   * restart() used to call release(), session and all, on every load. The
   * session is made once, when the view is, so from the first frame of the
   * first video there was nothing for a headset button to reach and no
   * playback notification to hang controls on.
   */
  private fun releasePlayer() {
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
    updatePip()
    announced = false
    buffering = false
    defaultAudioId = null
  }

  override fun onDetachedFromWindow() {
    release()
    super.onDetachedFromWindow()
  }
}
