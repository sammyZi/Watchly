package com.torrentviewer.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * The playback card in the notification shade and on the lock screen, and the headset's buttons.
 *
 * One media session for whichever film is on screen. Android 13+ draws the card's controls (and
 * PiP's play/pause) from the session's state; older Android draws the Play/Pause action given
 * here. Media-session notifications are exempt from the notification permission: no prompt.
 */
object MediaCard {
    /** What a press on the card, the lock screen or a headset asks of the player. */
    interface Remote {
        fun play()
        fun pause()
        fun next()
        fun seek(ms: Long)
    }

    private const val TAG = "MediaCard"
    private const val CHANNEL = "playback"
    private const val NOTE_ID = 8
    /** A notification icon, not a wallpaper: the bitmap is copied to the system on every post. */
    private const val ART_PX = 512

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var session: MediaSession? = null
    private var remote: Remote? = null

    private var title = "Watchly"
    private var subtitle: String? = null
    private var paused = true
    private var hasNext = false
    private var art: Bitmap? = null
    private var artUrl: String? = null
    private var lastNote = ""

    /** The player that owns the card now; a newer one takes it over from an older. */
    fun start(context: Context, remote: Remote) {
        this.remote = remote
        if (session != null) return
        val ctx = context.applicationContext
        app = ctx
        runCatching {
            val ms = MediaSession(ctx, "watchly")
            // Vendor ROMs that route buttons by receiver rather than by the playing session
            // never reach an app without one. Harmless on stock Android.
            runCatching { ms.setMediaButtonReceiver(buttons(ctx, 0, null)) }
            ms.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { this@MediaCard.remote?.play() }
                override fun onPause() { this@MediaCard.remote?.pause() }
                override fun onStop() { this@MediaCard.remote?.pause() }
                override fun onSkipToNext() { this@MediaCard.remote?.next() }
                override fun onSeekTo(pos: Long) { this@MediaCard.remote?.seek(pos) }
            })
            ms.isActive = true
            session = ms
            // The legacy registration too: some ROMs (Vivo's) keep their own slot that only this
            // call fills, and keep sending every press to whoever last did.
            runCatching {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerMediaButtonEventReceiver(ComponentName(ctx, MediaButtons::class.java))
            }
        }.onFailure { Log.w(TAG, "media session unavailable", it) }
    }

    /** Title, state and where the film is; the system runs the clock on from here by itself. */
    fun update(title: String, subtitle: String?, paused: Boolean, positionMs: Long, durationMs: Long, rate: Float, hasNext: Boolean) {
        val ms = session ?: return
        val metaChanged = title != this.title || subtitle != this.subtitle
        this.title = title
        this.subtitle = subtitle
        this.paused = paused
        this.hasNext = hasNext
        runCatching {
            if (metaChanged || ms.controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) != durationMs) metadata(ms, durationMs)
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
            if (hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, positionMs, if (paused) 0f else rate)
                    .build()
            )
        }
        post()
    }

    /** The poster beside the title, fetched off the main thread; a failed fetch leaves the card without one. */
    suspend fun artwork(url: String?) {
        val ctx = app ?: return
        if (session == null || url == artUrl) return
        artUrl = url
        art = null
        val bitmap = url?.let {
            val request = ImageRequest.Builder(ctx).data(it).size(ART_PX).allowHardware(false).build()
            (SingletonImageLoader.get(ctx).execute(request) as? SuccessResult)?.image?.toBitmap()
        }
        if (artUrl != url || bitmap == null) return
        art = bitmap
        session?.let { ms -> runCatching { metadata(ms, ms.controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) } }
        lastNote = ""
        post()
    }

    /** The player is gone: let go of the buttons and take the card down — unless a newer one has it. */
    fun stop(owner: Remote) {
        if (owner !== remote) return
        val ctx = app
        remote = null
        session?.let { ms -> runCatching { ms.isActive = false; ms.release() } }
        session = null
        if (ctx != null) {
            runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID) }
            runCatching {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterMediaButtonEventReceiver(ComponentName(ctx, MediaButtons::class.java))
            }
        }
        art = null
        artUrl = null
        lastNote = ""
    }

    /** A press that came the legacy way, through [MediaButtons]. */
    fun onKey(code: Int) {
        main.post { remote?.let { press(it, code) } }
    }

    private fun press(r: Remote, code: Int) {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> r.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> r.pause()
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (paused) r.play() else r.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (hasNext) r.next()
        }
    }

    private fun metadata(ms: MediaSession, durationMs: Long) {
        ms.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .apply { subtitle?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) } }
                // The shade's progress bar needs the length to draw.
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .apply {
                    // Android 13+ paints the card's background from this.
                    art?.let {
                        putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                    }
                }
                .build()
        )
    }

    private fun buttons(ctx: Context, code: Int, key: KeyEvent?): PendingIntent {
        val i = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(ctx, MediaButtons::class.java)
        if (key != null) i.putExtra(Intent.EXTRA_KEY_EVENT, key)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Only when what it shows changes: the position runs on from the session's state. */
    private fun post() {
        val ms = session ?: return
        val ctx = app ?: return
        val key = "$title|$subtitle|$paused"
        if (key == lastNote) return
        lastNote = key
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            }
            val toggle = buttons(ctx, 1, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                PendingIntent.getActivity(ctx, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            val icon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            val note = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(art)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(open)
                .setOngoing(!paused)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, icon), if (paused) "Play" else "Pause", toggle).build())
                .setStyle(Notification.MediaStyle().setMediaSession(ms.sessionToken).setShowActionsInCompactView(0))
                .build()
            nm.notify(NOTE_ID, note)
        }.onFailure { Log.w(TAG, "playback notification failed", it) }
    }
}

/**
 * Where a headset's buttons land on ROMs that route them by receiver, and the card's own
 * Play/Pause on older Android. It only forwards to [MediaCard].
 */
class MediaButtons : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val key: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        // A tap is a down and an up; acting on both would toggle straight back.
        if (key == null || key.action != KeyEvent.ACTION_DOWN) return
        MediaCard.onKey(key.keyCode)
    }
}
