package com.torrentviewer.mobile

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.provider.Settings as SystemSettings
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Episode
import com.torrentviewer.core.ExoView
import com.torrentviewer.core.Facts
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Intro
import com.torrentviewer.core.Intros
import com.torrentviewer.core.Lc
import com.torrentviewer.core.LocalFileServer
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import android.net.Uri
import java.io.File
import com.torrentviewer.core.Motion
import com.torrentviewer.core.Note
import com.torrentviewer.core.Progress
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.SavedSub
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Stats
import com.torrentviewer.core.Sub
import com.torrentviewer.core.SubDl
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.TorrentFile
import com.torrentviewer.core.Tracks
import com.torrentviewer.core.Type
import com.torrentviewer.core.Video
import com.torrentviewer.core.VlcView
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes
import com.torrentviewer.core.clock
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.left
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.searchEpisode
import com.torrentviewer.core.searchableName
import com.torrentviewer.core.trackForLang
import com.torrentviewer.core.speed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import com.composables.icons.lucide.R as L

/** How long the controls stay up after the last touch. */
private const val HIDE_MS = 4000L
/** Paused with the controls down this long, the "You're watching" card fades up. */
private const val PAUSE_CARD_MS = 800L
private const val SKIP = 10
private val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SCALES = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Best fit",
    MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Zoom to fill",
    MediaPlayer.ScaleType.SURFACE_FILL to "Stretch",
    MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
    MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Original",
)
/** Sleep timer steps, in minutes; -1 is "at the end of this episode". */
private val SLEEP = listOf(0, 15, 30, 60, -1)
/** The Up Next card: long enough to cancel before the credits, short enough not to sit over the last scene. */
private const val UP_NEXT_SECONDS = 30
/** Press and hold the picture: this speed until the finger lifts, as YouTube does. */
private const val HOLD_RATE = 2f
/** Long enough that a slow tap is still a tap. */
private const val HOLD_MS = 450L
/** A second tap inside this is a double tap, so a single one waits it out. */
private const val DOUBLE_TAP_MS = 220L
/** libVLC's own buffer by bitrate; three seconds rides out a torrent's bursts. */
private fun cacheMsFor(bytesPerSecond: Double) = if (bytesPerSecond > 4_000_000) 6000 else 3000
/** Seeks further than this also aim the torrent at the target before the player asks for it. */
private const val AIM_SECONDS = 30.0
/** A stream that ends this far from its runtime gave up; reopen it, at most this many times. */
private const val EARLY_END_SECONDS = 60.0
private const val MAX_RECOVERIES = 3
/** No new time this long means stalled: the stream blocks below anything the player reports. */
private const val STALL_MS = 1200L
/** The bar stops holding a seek target this long after it, landed or not. */
private const val SEEK_HOLD_MS = 45_000L
/** A seek with no new connection and the player still starved this long reopens the stream. */
private const val SEEK_CHECK_MS = 2500L

/** A seek that has not landed: the player goes on reporting the old time until it does. */
private class Seeking(val target: Double, val from: Double, val since: Long, val connections: Long)

private enum class Panel { TRACKS, SPEED, ASPECT, FIND }

private data class Flash(val seconds: Int, val id: Long)

private data class Hud(@DrawableRes val icon: Int?, val text: String, val id: Long = System.nanoTime())

private fun rateLabel(r: Float) = "${r.toString().removeSuffix(".0")}×"

private fun delta(seconds: Double): String {
    val n = abs(seconds).toLong()
    return "${if (seconds < 0) "−" else "+"}${n / 60}:%02d".format(n % 60)
}

@Composable
fun PlayerScreen(watch: Watch) {
    Fullscreen()
    // Over video the page is always dark, whatever the app's theme; it comes back on the way out.
    DisposableEffect(Unit) {
        val was = Ink.isDark
        Ink.use(true)
        onDispose { Ink.use(was) }
    }
    // Survives the next episode starting: "pause in 30 minutes" means from when it was set.
    val sleep = remember { mutableIntStateOf(0) }
    val sleepEnds = remember { mutableLongStateOf(0L) }
    // Everything else resets per file — a new episode is a new player.
    key(watch.playId) { Player(watch, sleep, sleepEnds) }
}

/** Landscape, bars hidden, screen kept on — for as long as the player is up. Brightness goes back to the system's after. */
@Composable
private fun Fullscreen() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(Unit) {
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = WindowCompat.getInsetsController(activity.window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            bars.show(WindowInsetsCompat.Type.systemBars())
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            activity.requestedOrientation = before
        }
    }
}

/** The window's brightness 0–1, starting from the system's own level. */
private fun brightnessOf(activity: android.app.Activity): Float {
    val own = activity.window.attributes.screenBrightness
    if (own >= 0) return own
    return runCatching { SystemSettings.System.getInt(activity.contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
}

@Composable
private fun Player(watch: Watch, sleepState: MutableState<Int>, sleepEndsState: MutableState<Long>) {
    var sleep by sleepState
    var sleepEnds by sleepEndsState
    val engine = watch.engine
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = LocalHapticFeedback.current
    val ready by engine.ready.collectAsState()
    // A State, not a value: read where it is shown, so a tick does not recompose the player.
    val progressState = engine.progress.collectAsState()
    val title = watch.title
    val file = watch.playingFile
    val episode = watch.episodeOf(file)
    val next = watch.next
    /** No next file in this torrent: the show's next episode, found online when asked for. */
    val onlineNext = remember(title, episode, next) {
        if (next != null || title == null || episode == null) null
        else title.episodes.indexOfFirst { it.id == episode.id }.takeIf { it >= 0 }?.let { title.episodes.getOrNull(it + 1) }
    }
    val nextEpisode = watch.episodeOf(next) ?: onlineNext
    val startAt = remember { watch.startAt() }
    val bitrate = remember { file?.size?.let { size -> watch.item?.durations?.get(watch.playingIndex)?.takeIf { it > 0 }?.let { size / it } } ?: 0.0 }
    /** Intros are per show; a film has no recurring titles. */
    val show = remember(title, file) {
        if (title?.isSeries == true || parseEpisode(file?.name.orEmpty()) != null) title?.imdb ?: watch.item?.imdb ?: title?.title else null
    }

    val view = remember { mutableStateOf<Video?>(null) }
    /** Media3 unless the setting asks for VLC, or Media3 has already given up on this file. */
    var vlc by remember { mutableStateOf(SettingsStore.value.value.vlcPlayer) }
    var paused by remember { mutableStateOf(false) }
    var at by remember { mutableDoubleStateOf(startAt) }
    var duration by remember { mutableDoubleStateOf(0.0) }
    var loaded by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var info by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var tracks by remember { mutableStateOf<Tracks?>(null) }
    var subtitle by remember { mutableStateOf<Int?>(null) }
    var audio by remember { mutableStateOf<Int?>(null) }
    // Speed and picture carry from one video to the next.
    var rate by remember { mutableFloatStateOf(SettingsStore.value.value.playbackRate) }
    var scale by remember { mutableIntStateOf(SCALES.indexOfFirst { it.second == SettingsStore.value.value.playerScale }.coerceAtLeast(0)) }
    /** Audio, subtitles: the preferred language already tried, or the viewer already chose. */
    val langDone = remember { booleanArrayOf(false, false) }
    /** Subtitle offset in ms; positive shows them later. */
    var subDelay by remember { mutableLongStateOf(0L) }
    val cast = remember { castContext(context) }
    var castSession by remember { mutableStateOf(cast?.sessionManager?.currentCastSession?.takeIf { it.isConnected }) }
    var castPaused by remember { mutableStateOf(false) }
    var noNext by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Double?>(null) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    var external by remember { mutableStateOf(watch.savedSubs()) }
    var found by remember { mutableStateOf<List<Sub>?>(null) }
    var finding by remember { mutableStateOf(false) }
    var pendingSub by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    /** Fetch the whole file, or only the next few minutes past the playhead; remembered for the next video. */
    var wholeFile by remember { mutableStateOf(SettingsStore.value.value.wholeFile.also { engine.wholeFile = it }) }
    /** Locked, with the lock icon up in its corner. */
    var lockShown by remember { mutableStateOf(false) }
    var boosting by remember { mutableStateOf(false) }
    var intro by remember { mutableStateOf(show?.let(Intros::get)) }
    /** Where the viewer said the intro starts, until they say where it ends. */
    var introMark by remember { mutableStateOf<Double?>(null) }
    val pip = Pip.active
    val waiting = ready == null || !loaded

    fun show() {
        controls = true
        lastTouch = System.currentTimeMillis()
    }

    fun say(text: String, @DrawableRes icon: Int? = null) {
        hud = Hud(icon, text)
    }

    fun togglePause() {
        val v = view.value ?: return
        v.setPaused(!v.isPaused)
        paused = v.isPaused
        show()
    }

    /** Out of the player — and off the TV, whose stream stops with us. */
    fun leave() {
        if (castSession != null) runCatching { cast?.sessionManager?.endCurrentSession(true) }
        watch.exit()
    }

    fun goNext() {
        if (advanced) return
        val sibling = next
        if (sibling != null) {
            advanced = true
            watch.playSibling(sibling)
            return
        }
        val ep = onlineNext ?: return
        val t = title ?: return
        advanced = true
        if (at > 0) watch.savePosition(at)
        val tag = episodeTag(ep.season, ep.episode)
        say("Finding $tag…")
        scope.launch {
            val hit = runCatching { searchEpisode(t.title, ep.season, ep.episode, t.imdb) }.getOrNull()?.firstOrNull()
            if (hit == null) {
                watch.notes.tryEmit("No sources found for $tag.")
                advanced = false
                noNext = true
            } else {
                watch.stream(hit, t, tag, ep)
            }
        }
    }

    val seeking = remember { mutableStateOf<Seeking?>(null) }
    val seekCheck = remember { mutableStateOf<Job?>(null) }
    fun seekTo(seconds: Double) {
        val target = seconds.coerceIn(0.0, if (duration > 0) duration - 1 else seconds)
        val v = view.value ?: return
        val from = at
        at = target
        val ms = (target * 1000).toLong()
        if (duration > 0 && abs(target - from) > AIM_SECONDS) scope.launch(Dispatchers.IO) { engine.setPosition(target / duration) }
        // Starved, the player would act on the seek only once the piece it is blocked on
        // arrives — seconds, or a minute in a thin swarm. Reopening there does not wait.
        val reopen = engine.starved()
        val s = Seeking(target, from, System.currentTimeMillis(), engine.connections())
        seeking.value = s
        if (reopen) v.reload(ms) else v.seekTo(ms)
        seekCheck.value?.cancel()
        if (reopen) return
        // It can starve just after the check; if the seek has gone nowhere, reopen then.
        seekCheck.value = scope.launch {
            delay(SEEK_CHECK_MS)
            if (seeking.value === s && engine.connections() == s.connections && engine.starved()) view.value?.reload(ms)
        }
    }

    /** Taps in a row in one direction add up to one running total, as on YouTube. */
    fun skip(seconds: Int) {
        val now = System.currentTimeMillis()
        val prev = flash
        val sum = if (prev != null && now - prev.id < 1000 && (prev.seconds < 0) == (seconds < 0)) prev.seconds + seconds else seconds
        flash = Flash(sum, now)
        seekTo(at + seconds)
    }

    fun markIntro() {
        val key = show ?: return
        val start = introMark
        if (start == null) {
            introMark = at
            say("Intro start marked — tap again where it ends", L.drawable.lucide_ic_flag)
            return
        }
        introMark = null
        if (at < start + 5) return say("Too short for an intro — marking cancelled")
        val marked = Intro(start.roundToInt().toDouble(), at.roundToInt().toDouble())
        intro = marked
        Intros.set(key, marked)
        say("Intro saved — other episodes will offer to skip it", L.drawable.lucide_ic_flag)
    }

    fun cycleSleep() {
        val i = (sleep + 1) % SLEEP.size
        sleep = i
        sleepEnds = System.currentTimeMillis() + SLEEP[i] * 60_000L
        say(
            when {
                SLEEP[i] == 0 -> "Sleep timer off"
                SLEEP[i] < 0 -> "Stop after this episode"
                else -> "Pause in ${SLEEP[i]} min"
            },
            L.drawable.lucide_ic_moon,
        )
    }

    /* ------------------------------------------------------------ pip */

    fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val b = PictureInPictureParams.Builder()
        // The film's shape, inside the range Android accepts (it throws outside 1:2.39–2.39:1).
        view.value?.stats()?.takeIf { it.width > 0 && it.height > 0 }?.let { s ->
            val ratio = (s.width.toDouble() / s.height).coerceIn(1 / 2.39, 2.39)
            b.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(!paused && loaded)
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }
    val canPip = remember { Pip.supported(context) }
    DisposableEffect(Unit) {
        Pip.params = { if (!paused && loaded) pipParams() else null }
        onDispose {
            Pip.params = null
            if (canPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            }
        }
    }
    // Android 12+ shrinks on Home by itself, from whatever the params say right now.
    LaunchedEffect(paused, loaded) {
        if (canPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) pipParams()?.let { p -> runCatching { activity?.setPictureInPictureParams(p) } }
    }
    LaunchedEffect(pip) {
        if (pip) {
            controls = false
            panel = null
        }
    }

    /* ------------------------------------------------------------ effects */

    // The stream arrives: hand it to the player, with the torrent's own subtitle files.
    // From where it is, not startAt: a fallback to VLC picks up mid-film.
    LaunchedEffect(ready, view.value) {
        val r = ready ?: return@LaunchedEffect
        val v = view.value ?: return@LaunchedEffect
        v.open(r.url, (at * 1000).toLong(), cacheMsFor(bitrate), r.subtitleFiles + external.map { it.path })
    }

    /* ------------------------------------------------------------ cast */

    DisposableEffect(cast) {
        val manager = cast?.sessionManager
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) { castSession = session }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { castSession = session }
            override fun onSessionEnded(session: CastSession, error: Int) { castSession = null }
            override fun onSessionStartFailed(session: CastSession, error: Int) { watch.notes.tryEmit("Couldn't connect to that device.") }
            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }
        manager?.addSessionManagerListener(listener, CastSession::class.java)
        onDispose { manager?.removeSessionManagerListener(listener, CastSession::class.java) }
    }
    // A finished download has no network address: serve it while it is on the TV.
    val fileServer = remember { mutableStateOf<LocalFileServer?>(null) }
    DisposableEffect(Unit) { onDispose { fileServer.value?.stop() } }
    var casted by remember { mutableStateOf(false) }
    LaunchedEffect(castSession, ready) {
        val s = castSession ?: return@LaunchedEffect
        val r = ready ?: return@LaunchedEffect
        val url = r.lanUrl ?: if (r.url.startsWith("file:")) {
            val f = File(Uri.parse(r.url).path ?: return@LaunchedEffect)
            (fileServer.value ?: LocalFileServer(f, f.length(), { true }, { _, _ -> }, {}, lan = true).also {
                it.start()
                fileServer.value = it
            }).lanUrl
        } else null
        if (url == null) {
            watch.notes.tryEmit("Casting needs Wi-Fi — there is no address to hand the TV.")
            return@LaunchedEffect
        }
        val client = s.remoteMediaClient ?: return@LaunchedEffect
        // Two copies playing at once is the one thing nobody wants.
        view.value?.setPaused(true)
        paused = true
        controls = false
        client.load(castRequest(url, r.name, title?.title ?: watch.item?.label.orEmpty(), title?.art, at))
        casted = true
        // The receiver's clock is the one that counts now; kept in `at`, so it is saved and local playback picks up from it.
        while (true) {
            delay(500)
            client.approximateStreamPosition.takeIf { it > 0 }?.let { at = it / 1000.0 }
            client.streamDuration.takeIf { it > 0 }?.let { duration = it / 1000.0 }
            castPaused = client.isPaused
        }
    }
    // The session ended: carry on here from where the TV got to.
    LaunchedEffect(castSession) {
        if (castSession != null || !casted) return@LaunchedEffect
        casted = false
        seekTo(at)
        view.value?.setPaused(false)
        paused = false
    }

    // Home without PiP, the lock button, or closing the PiP window: stop, and keep the spot.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) view.value?.let { v ->
                v.setPaused(true)
                paused = true
                if (at > 0) watch.savePosition(at)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(loaded) { if (loaded) show() }
    LaunchedEffect(controls, lastTouch, paused, scrub, panel) {
        if (!controls || paused || scrub != null || panel != null) return@LaunchedEffect
        delay(HIDE_MS)
        controls = false
    }
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(1800)
            hud = null
        }
    }
    LaunchedEffect(lockShown) {
        if (lockShown) {
            delay(4000)
            lockShown = false
        }
    }
    // Restarted by every new time; only an absence of them fires it.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(ready, paused) {
        stalled = false
        if (ready == null || paused) return@LaunchedEffect
        snapshotFlow { at }.collectLatest {
            stalled = false
            delay(STALL_MS)
            stalled = true
        }
    }
    // Saved every few seconds, so a dead battery still remembers the spot.
    LaunchedEffect(loaded) {
        while (loaded) {
            delay(5000)
            if (at > 0) watch.savePosition(at)
        }
    }
    DisposableEffect(Unit) { onDispose { if (at > 0) watch.savePosition(at) } }
    LaunchedEffect(info) {
        while (info) {
            stats = view.value?.stats()
            delay(1000)
        }
    }
    // A timed sleep: pause where we are and keep the place, as leaving the app does.
    LaunchedEffect(sleep, sleepEnds) {
        if (SLEEP[sleep] <= 0) return@LaunchedEffect
        delay((sleepEnds - System.currentTimeMillis()).coerceAtLeast(0))
        view.value?.setPaused(true)
        paused = true
        if (at > 0) watch.savePosition(at)
        sleep = 0
        say("Sleep timer — paused", L.drawable.lucide_ic_moon)
    }

    val stopAfter = SLEEP[sleep] < 0
    // Derived, so the clock only recomposes what shows it.
    val offerNext by remember(next, onlineNext, stopAfter) {
        derivedStateOf { (next != null || onlineNext != null) && !noNext && !stopAfter && loaded && duration > 0 && duration - at <= UP_NEXT_SECONDS }
    }
    LaunchedEffect(offerNext) {
        if (!offerNext) return@LaunchedEffect
        snapshotFlow { duration - at }.first { it <= 0.5 }
        goNext()
    }
    val skipIntro by remember { derivedStateOf { intro?.covers(at) == true && introMark == null && loaded } }

    /* ------------------------------------------------------------ input */

    BackHandler {
        when {
            panel != null -> panel = null
            locked -> lockShown = true
            else -> leave()
        }
    }

    // Tap toggles the controls and a double tap skips, so a single tap waits out the double-tap window.
    val pendingTap = remember { mutableStateOf<Job?>(null) }
    fun tapped(x: Float, width: Float) {
        // Locked: a tap shows the lock in its corner, the next one hides it again.
        if (locked) {
            lockShown = !lockShown
            return
        }
        val waitingTap = pendingTap.value
        if (waitingTap?.isActive == true) {
            waitingTap.cancel()
            pendingTap.value = null
            skip(if (x < width / 2) -SKIP else SKIP)
            return
        }
        pendingTap.value = scope.launch {
            delay(DOUBLE_TAP_MS)
            if (controls) controls = false else show()
        }
    }

    /** The window's brightness 0–1, shown on the rail and moved by it or a left-edge swipe. */
    var brightness by remember { mutableFloatStateOf(activity?.let(::brightnessOf) ?: 0.5f) }
    fun setBrightness(level: Float) {
        val b = level.coerceIn(0.01f, 1f)
        brightness = b
        activity?.window?.let { win -> win.attributes = win.attributes.apply { screenBrightness = b } }
        lastTouch = System.currentTimeMillis()
    }

    val gestures = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            // Read live: this block is set up once and outlives the composition it came from.
            if (Pip.active || engine.ready.value == null || !loaded) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val x0 = down.position.x
            val y0 = down.position.y
            // What this press turns out to be: a tap, a hold, a side drag, or nothing.
            // Holding for 2× only with the controls down, so a held button never speeds the film up.
            val kind = withTimeoutOrNull(if (paused || locked || controls) Long.MAX_VALUE else HOLD_MS) {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "up"
                    // A button over the picture took this one.
                    if (!c.pressed) return@withTimeoutOrNull if (c.isConsumed) "none" else "up"
                    val dx = c.position.x - x0
                    val dy = c.position.y - y0
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        return@withTimeoutOrNull when {
                            locked -> "none"
                            x0 < w * 0.4f -> "bright"
                            else -> "none"
                        }
                    }
                    if (abs(dx) > slop) return@withTimeoutOrNull "none"
                }
            } ?: "hold"
            when (kind) {
                "up" -> tapped(x0, w)
                "hold" -> {
                    // Double speed until the finger lifts.
                    boosting = true
                    view.value?.setRate(HOLD_RATE)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    do {
                        val e = awaitPointerEvent()
                    } while (e.changes.any { it.pressed })
                    view.value?.setRate(rate)
                    boosting = false
                }
                "bright" -> {
                    val from = brightness
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        // The full sweep is most of the screen's height, as VLC has it.
                        setBrightness(from - (c.position.y - y0) / (h * 0.7f))
                        say("${(brightness * 100).roundToInt()}%", L.drawable.lucide_ic_sun)
                    }
                }
                else -> do {
                    val e = awaitPointerEvent()
                } while (e.changes.any { it.pressed })
            }
        }
    }

    /* ------------------------------------------------------------ layout */

    Box(Modifier.fillMaxSize().background(Color.Black).then(gestures)) {
        key(vlc) {
            AndroidView(
                factory = { ctx ->
                    (if (vlc) VlcView(ctx) else ExoView(ctx)).also { v ->
                        // A fallback keeps the viewer's speed and picture.
                        v.setRate(rate)
                        v.subtitleStyle = SettingsStore.value.value.subtitles
                        v.setSubtitleDelay(subDelay)
                        v.setScale(SCALES[scale].first)
                        if (!vlc) v.onUnsupported = { message ->
                            Log.w("Player", "Media3 gave up ($message), falling back to VLC")
                            // Track ids are the backend's own.
                            tracks = null
                            subtitle = null
                            audio = null
                            langDone.fill(false)
                            vlc = true
                        }
                        var recoveries = 0
                        v.onLoaded = { ms ->
                            duration = ms / 1000.0
                            // Again after every reopen — only the first is the film starting.
                            if (!loaded) {
                                loaded = true
                                watch.saveDuration(duration)
                                // Aim the engine at the resume point now the real runtime is known.
                                if (startAt > 0 && duration > 0) scope.launch(Dispatchers.IO) { engine.setPosition(minOf(0.99, startAt / duration)) }
                            }
                        }
                        v.onTime = { ms ->
                            val t = ms / 1000.0
                            val s = seeking.value
                            // Landed once a report is nearer the target than the spot it left.
                            if (s == null || abs(t - s.target) <= abs(t - s.from) || System.currentTimeMillis() - s.since > SEEK_HOLD_MS) {
                                if (s != null) seeking.value = null
                                at = t
                            }
                        }
                        v.onBuffering = { buffering = it }
                        v.onPlaying = { paused = !it }
                        v.onTracks = { t ->
                            tracks = t
                            // A subtitle just downloaded comes on by itself.
                            if (pendingSub && t.subtitles.isNotEmpty()) {
                                subtitle = t.subtitles.last().id
                                v.setSubtitleTrack(subtitle)
                                pendingSub = false
                            } else {
                                subtitle = t.spuId.takeIf { it >= 0 }
                            }
                            // The preferred languages, once each, the first time the file lists any.
                            val prefs = SettingsStore.value.value
                            if (!langDone[0] && t.audio.isNotEmpty()) {
                                langDone[0] = true
                                trackForLang(t.audio, prefs.audioLang)?.let { a ->
                                    audio = a.id
                                    v.setAudioTrack(a.id)
                                }
                            }
                            if (!langDone[1] && t.subtitles.isNotEmpty()) {
                                langDone[1] = true
                                if (prefs.subLang.isNotEmpty()) trackForLang(t.subtitles, prefs.subLang)?.let { s ->
                                    subtitle = s.id
                                    v.setSubtitleTrack(s.id)
                                }
                            }
                        }
                        v.onEnded = {
                            // A stream that gave up mid-film looks like the end: reopen where it stopped.
                            when {
                                duration > 0 && at < duration - EARLY_END_SECONDS && recoveries < MAX_RECOVERIES -> {
                                    recoveries++
                                    v.reload((at * 1000).toLong())
                                }
                                // The sleep timer said this episode was the last.
                                SLEEP[sleep] < 0 -> {
                                    sleep = 0
                                    watch.exit()
                                }
                                (next != null || onlineNext != null) && !noNext -> goNext()
                                else -> watch.exit()
                            }
                        }
                        v.onFailed = { message ->
                            watch.notes.tryEmit(message)
                            watch.exit()
                        }
                        view.value = v
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Debounced: a torrent stream flickers "buffering", and only a wait of a full second is worth a spinner.
        val wanting = !waiting && (buffering || stalled) && !paused
        var spinning by remember { mutableStateOf(false) }
        LaunchedEffect(wanting) { if (!wanting) spinning = false else { delay(1000); spinning = true } }
        AnimatedVisibility(spinning && scrub == null, Modifier.align(Alignment.Center), enter = fadeIn(tween(300, 400)), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = if (pip) 32.dp else 56.dp)
                if (!pip) progressState.value?.let { Text("${speed(it.speed)}  ·  ${it.peers} peers", Modifier.padding(top = 12.dp), style = Type.meta.copy(color = Color.White)) }
            }
        }

        // In PiP the window is the picture and nothing else.
        if (pip) return@Box

        PausedCard(paused && !controls && panel == null && !waiting && !locked, title, episode, watch.item?.label.orEmpty(), at, duration)

        AnimatedVisibility(controls && !waiting && !locked, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    TopBar(
                        title, episode, file, progressState.value, duration, { at }, rate, info,
                        canCast = cast != null,
                        onBack = ::leave,
                        onStats = { info = !info },
                        onLock = {
                            locked = true
                            controls = false
                            lockShown = true
                        },
                    )
                    BrightnessRail(brightness, ::setBrightness, Modifier.align(Alignment.CenterStart))
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                        RoundIcon(L.drawable.lucide_ic_rotate_ccw, { skip(-SKIP); show() }, size = 52.dp)
                        RoundIcon(if (paused) L.drawable.lucide_ic_play else L.drawable.lucide_ic_pause, ::togglePause, size = 72.dp)
                        RoundIcon(L.drawable.lucide_ic_rotate_cw, { skip(SKIP); show() }, size = 52.dp)
                    }
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                        SeekBar(
                            at = { scrub ?: at },
                            playing = { at },
                            duration = duration,
                            scrubbing = scrub != null,
                            onScrub = {
                                scrub = it
                                lastTouch = System.currentTimeMillis()
                            },
                            onCommit = {
                                scrub?.let(::seekTo)
                                scrub = null
                            },
                        )
                        // Icons alone, spread across the bar.
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            BarButton(L.drawable.lucide_ic_captions, "Audio & subtitles", on = subtitle != null) { panel = Panel.TRACKS }
                            BarButton(L.drawable.lucide_ic_gauge, "Speed (${rateLabel(rate)})", on = rate != 1f) { panel = Panel.SPEED }
                            BarButton(L.drawable.lucide_ic_ratio, SCALES[scale].second, on = scale != 0) { panel = Panel.ASPECT }
                            BarButton(
                                L.drawable.lucide_ic_moon,
                                when {
                                    SLEEP[sleep] == 0 -> "Sleep timer"
                                    SLEEP[sleep] < 0 -> "End of episode"
                                    else -> "${maxOf(1L, (sleepEnds - System.currentTimeMillis() + 59_999) / 60_000)} min"
                                },
                                on = SLEEP[sleep] != 0,
                                onClick = ::cycleSleep,
                            )
                            if (show != null) BarButton(L.drawable.lucide_ic_flag, if (introMark != null) "Intro ends here" else if (intro != null) "Re-mark intro" else "Mark intro", on = introMark != null, onClick = ::markIntro)
                            BarButton(if (wholeFile) L.drawable.lucide_ic_hard_drive else L.drawable.lucide_ic_clock, if (wholeFile) "Whole file" else "Next 5 minutes", on = wholeFile) {
                                wholeFile = !wholeFile
                                engine.wholeFile = wholeFile
                                SettingsStore.save(SettingsStore.value.value.copy(wholeFile = wholeFile))
                                say(if (wholeFile) "Downloading the whole file" else "Downloading the next 5 minutes", if (wholeFile) L.drawable.lucide_ic_hard_drive else L.drawable.lucide_ic_clock)
                            }
                            BarButton(L.drawable.lucide_ic_external_link, "Open in…") {
                                val r = ready ?: return@BarButton
                                if (openExternally(context, r.url, r.name, at)) {
                                    view.value?.setPaused(true)
                                    paused = true
                                } else watch.notes.tryEmit("No other player on this phone can open it.")
                            }
                            if (canPip) BarButton(L.drawable.lucide_ic_picture_in_picture_2, "Mini player") {
                                pipParams()?.let { p -> runCatching { activity?.enterPictureInPictureMode(p) } }
                            }
                            watch.prev?.let { prev -> BarButton(L.drawable.lucide_ic_skip_back, "Previous") { watch.playSibling(prev) } }
                            if (next != null || onlineNext != null) BarButton(L.drawable.lucide_ic_skip_forward, "Next episode", onClick = ::goNext)
                        }
                    }
                }
            }
        }

        if (!waiting) SeekFlash(flash)

        // Skip intro, bottom-right, where Up Next will sit later.
        AnimatedVisibility(
            skipIntro && !offerNext && !locked && panel == null,
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 20.dp, bottom = if (controls) 120.dp else 28.dp),
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut(),
        ) {
            Tap({ intro?.let { seekTo(it.end) } }, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.background(Color.White).padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lc(L.drawable.lucide_ic_chevrons_right, tint = Color.Black, size = 18.dp)
                    Text("  Skip intro", style = Type.label.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                }
            }
        }

        // Locked: just the lock, in the corner; tapping it unlocks.
        AnimatedVisibility(locked && lockShown, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
            RoundIcon(L.drawable.lucide_ic_lock, {
                locked = false
                lockShown = false
                show()
            }, size = 48.dp)
        }

        // A short word for anything that happened without the bar: "Added EN", "60%".
        val shownHud = hud
        AnimatedVisibility(shownHud != null && !waiting, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 20.dp), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut()) {
            val h = shownHud ?: return@AnimatedVisibility
            Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                h.icon?.let {
                    Lc(it, tint = Color.White, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(h.text, style = Type.label.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        if (boosting) {
            Row(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Lc(L.drawable.lucide_ic_fast_forward, tint = Color.White, size = 16.dp)
                Text("  ${rateLabel(HOLD_RATE)}", style = Type.label.copy(fontWeight = FontWeight.Bold))
            }
        }

        AnimatedVisibility(info && !waiting, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, top = if (controls) 64.dp else 16.dp), enter = fadeIn(), exit = fadeOut()) {
            StatsPanel(stats, progressState.value, file)
        }

        if (offerNext && !locked) {
            UpNext(
                name = nextEpisode?.let { "E${it.episode} · ${it.name}" } ?: next?.let { searchableName(it.name).ifBlank { it.name } }.orEmpty(),
                still = nextEpisode?.thumbnail ?: title?.backdrop,
                seconds = { (duration - at).toInt().coerceAtLeast(0) },
                onPlay = ::goNext,
                onCancel = { noNext = true },
                modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 16.dp, bottom = 16.dp),
            )
        }

        AnimatedVisibility(waiting, enter = fadeIn(), exit = fadeOut(tween(500))) {
            Loading(title, episode, file, ready != null, progressState.value, onBack = ::leave)
        }

        // On the TV now: this screen becomes its remote.
        castSession?.let { s ->
            CastingCard(
                device = s.castDevice?.friendlyName ?: "TV",
                title = title,
                episode = episode,
                label = watch.item?.label.orEmpty(),
                at = at,
                duration = duration,
                paused = castPaused,
                onToggle = { s.remoteMediaClient?.let { c -> if (c.isPaused) c.play() else c.pause() } },
                onSeek = { to -> s.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition((to.coerceAtLeast(0.0) * 1000).toLong()).build()) },
                onStop = { runCatching { cast?.sessionManager?.endCurrentSession(true) } },
                onBack = ::leave,
            )
        }

        when (panel) {
            Panel.TRACKS -> TracksPanel(
                tracks = tracks,
                subtitle = subtitle,
                delay = subDelay,
                onDelay = { ms ->
                    subDelay = ms
                    // Media3 can only hold subtitles back; earlier is VLC's, picked up where the film is.
                    if (view.value?.setSubtitleDelay(ms) == false) {
                        say("Switching to VLC to bring subtitles earlier", L.drawable.lucide_ic_captions)
                        tracks = null
                        langDone.fill(false)
                        vlc = true
                    }
                },
                audio = audio ?: tracks?.defaultAudio,
                // Picking one yourself wins over the preferred language.
                onSubtitle = {
                    langDone[1] = true
                    subtitle = it
                    view.value?.setSubtitleTrack(it)
                },
                onAudio = {
                    langDone[0] = true
                    audio = it
                    view.value?.setAudioTrack(it)
                },
                onFind = {
                    panel = Panel.FIND
                    finding = true
                    found = null
                    scope.launch {
                        found = runCatching {
                            val key = BuildConfig.SUBDL_KEY
                            if (key.isBlank()) error("This build has no SubDL key — set SUBDL_KEY in mobile/.env")
                            val ep = parseEpisode(file?.name.orEmpty())
                            SubDl.search(
                                query = title?.title ?: searchableName(file?.name.orEmpty()),
                                key = key,
                                imdb = title?.imdb ?: watch.item?.imdb,
                                season = ep?.season,
                                episode = ep?.episode,
                                type = if (ep != null || title?.isSeries == true) "tv" else "movie",
                                language = SettingsStore.value.value.subLang,
                            ).take(25)
                        }.getOrElse {
                            watch.notes.tryEmit(it.message ?: "Subtitle search failed")
                            emptyList()
                        }
                        finding = false
                    }
                },
                onDismiss = { panel = null },
            )
            Panel.SPEED -> Sheet(
                title = "Playback speed",
                options = RATES.map { Option(it, rateLabel(it), if (it == 1f) "Normal" else null) },
                selected = rate,
                onPick = {
                    rate = it
                    view.value?.setRate(it)
                    SettingsStore.save(SettingsStore.value.value.copy(playbackRate = it))
                    panel = null
                },
                onDismiss = { panel = null },
            )
            Panel.ASPECT -> Sheet(
                title = "Picture",
                options = SCALES.mapIndexed { i, (_, label) -> Option(i, label) },
                selected = scale,
                onPick = {
                    scale = it
                    view.value?.setScale(SCALES[it].first)
                    SettingsStore.save(SettingsStore.value.value.copy(playerScale = SCALES[it].second))
                    panel = null
                },
                onDismiss = { panel = null },
            )
            Panel.FIND -> Sheet(
                title = "Find subtitles",
                options = found.orEmpty().mapIndexed { i, s -> Option(i, s.language, s.release.ifEmpty { s.name }) },
                selected = null,
                busy = finding,
                empty = "Nothing found for this title.",
                onPick = { i ->
                    val sub = found?.getOrNull(i) ?: return@Sheet
                    panel = null
                    scope.launch {
                        runCatching {
                            val path = withContext(Dispatchers.IO) { engine.saveSubtitle(sub.url, sub.label.replace(Regex("\\W+"), "_")) }
                            if (external.none { it.path == path }) {
                                external = external + SavedSub(path, sub.label)
                                watch.saveSubs(external)
                            }
                            pendingSub = true
                            view.value?.addSubtitleFiles(listOf(path))
                            say("Added ${sub.language}", L.drawable.lucide_ic_captions)
                        }.onFailure { watch.notes.tryEmit(it.message ?: "Couldn't fetch that subtitle") }
                    }
                },
                onDismiss = { panel = null },
            )
            null -> Unit
        }
    }
}

/* ---------------------------------------------------------------- pieces */

@Composable
private fun TopBar(
    title: Title?,
    episode: Episode?,
    file: TorrentFile?,
    progress: Progress?,
    duration: Double,
    at: () -> Double,
    rate: Float,
    info: Boolean,
    canCast: Boolean,
    onBack: () -> Unit,
    onStats: () -> Unit,
    onLock: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title?.title ?: searchableName(file?.name.orEmpty()).ifBlank { file?.name.orEmpty() }, style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ep = episode?.let { "S${it.season}:E${it.episode}  ${it.name}" } ?: file?.let { parseEpisode(it.name) }?.let { "S${it.season}:E${it.episode}" }
            val ends = if (duration > 0) "Ends at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + ((duration - at()) / rate * 1000).toLong())) else null
            Text(listOfNotNull(ep, ends).joinToString("  ·  "), style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        progress?.let {
            Row(Modifier.padding(end = 6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Lc(L.drawable.lucide_ic_download, tint = Ink.muted, size = 13.dp)
                Text(" ${speed(it.speed)}", style = Type.meta.copy(color = Color.White))
                Spacer(Modifier.width(10.dp))
                Lc(L.drawable.lucide_ic_users, tint = Ink.muted, size = 13.dp)
                Text(" ${it.peers}", style = Type.meta.copy(color = Color.White))
            }
        }
        if (canCast) AndroidView(factory = ::castButton, modifier = Modifier.size(42.dp))
        Tap(onStats, shape = CircleShape) { Lc(L.drawable.lucide_ic_info, Modifier.padding(9.dp), tint = if (info) Ink.accent else Color.White, size = 22.dp) }
        Tap(onLock, shape = CircleShape) { Lc(L.drawable.lucide_ic_lock, Modifier.padding(9.dp), tint = Color.White, size = 22.dp) }
    }
}

/** Brightness, in sight with the controls: drag along it or tap a point on it. Short, so it clears both bars turned. */
@Composable
private fun BrightnessRail(level: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    Column(modifier.padding(start = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Lc(L.drawable.lucide_ic_sun, tint = Color.White, size = 18.dp)
        Canvas(
            Modifier.padding(vertical = 8.dp).size(width = 36.dp, height = 120.dp).pointerInput(Unit) {
                fun levelAt(y: Float) = 1f - (y / size.height).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Taken, or the picture under it would read it as a tap too.
                    down.consume()
                    onChange(levelAt(down.position.y))
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        onChange(levelAt(c.position.y))
                    }
                }
            },
        ) {
            val w = 4.dp.toPx()
            val x = (size.width - w) / 2
            val filled = size.height * level
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
            drawRoundRect(Color.White, Offset(x, size.height - filled), Size(w, filled), CornerRadius(w / 2))
            drawCircle(Color.White, 7.dp.toPx(), Offset(size.width / 2, size.height - filled))
        }
        Text("${(level * 100).roundToInt()}%", style = Type.meta.copy(color = Color.White, fontSize = 11.sp))
    }
}

/** A bar button: the icon alone, a red dot when on; [label] is what a screen reader says. */
@Composable
private fun BarButton(@DrawableRes icon: Int, label: String, on: Boolean = false, onClick: () -> Unit) {
    Tap(onClick, Modifier.semantics { contentDescription = label }, CircleShape) {
        Box(Modifier.padding(12.dp)) {
            Lc(icon, tint = Color.White, size = 22.dp)
            if (on) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-3).dp).size(6.dp).clip(CircleShape).background(Ink.accent))
        }
    }
}

/**
 * The TV's bar under a finger: the played part red, a knob that swells while held, the
 * time so far and left either side, and while dragging a bubble with the target and the jump.
 */
@Composable
private fun SeekBar(at: () -> Double, playing: () -> Double, duration: Double, scrubbing: Boolean, onScrub: (Double) -> Unit, onCommit: () -> Unit) {
    val fraction = if (duration > 0) (at() / duration).toFloat().coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(fraction, if (scrubbing) tween(60) else tween(420, easing = FastOutSlowInEasing), label = "head")
    val lift by animateFloatAsState(if (scrubbing) 1f else 0.4f, tween(200, easing = FastOutSlowInEasing), label = "lift")
    val red = Ink.accent
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clock(at()), Modifier.padding(start = 6.dp, end = 12.dp), style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            val width = maxWidth
            Canvas(
                Modifier.fillMaxSize().pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Taken, or the picture under the bar would read it as a tap too.
                        down.consume()
                        onScrub((down.position.x / size.width).coerceIn(0f, 1f) * duration)
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            onScrub((c.position.x / size.width).coerceIn(0f, 1f) * duration)
                        }
                        onCommit()
                    }
                },
            ) {
                val thick = (3 + 3 * lift).dp.toPx()
                val top = (size.height - thick) / 2
                val r = CornerRadius(thick / 2)
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, top), Size(size.width, thick), r)
                drawRoundRect(red, Offset(0f, top), Size(size.width * shown, thick), r)
                val knob = 8.dp.toPx() * (0.6f + 0.6f * lift)
                drawCircle(red.copy(alpha = 0.28f * lift), knob * 1.9f, Offset(size.width * shown, size.height / 2))
                drawCircle(red, knob, Offset(size.width * shown, size.height / 2))
            }
            // Qualified: inside the Row the RowScope overload would be picked and refused.
            androidx.compose.animation.AnimatedVisibility(
                scrubbing,
                Modifier.align(Alignment.BottomStart).offset(x = (width * shown - 55.dp).coerceIn(0.dp, width - 110.dp), y = (-44).dp).width(110.dp),
                enter = fadeIn(tween(140)) + scaleIn(tween(180, easing = FastOutSlowInEasing), 0.8f, TransformOrigin(0.5f, 1f)),
                exit = fadeOut(tween(220)) + scaleOut(tween(220), 0.9f, TransformOrigin(0.5f, 1f)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(clock(at()), Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 10.dp, vertical = 3.dp), style = Type.label.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                    Text(delta(at() - playing()), Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                }
            }
        }
        Text(if (duration > 0) "-" + clock(duration - at()) else "--:--", Modifier.padding(start = 12.dp, end = 6.dp), style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * A skip, felt: a badge on the side it went that spins its arrow, a ring pulsing out
 * behind it, and the running total — taps in a row add up.
 */
@Composable
private fun SeekFlash(flash: Flash?) {
    var last by remember { mutableStateOf(flash) }
    var visible by remember { mutableStateOf(false) }
    val spin = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(flash?.id) {
        val f = flash ?: return@LaunchedEffect
        last = f
        visible = true
        launch { spin.animateTo(spin.value + if (f.seconds < 0) -360f else 360f, tween(520, easing = FastOutSlowInEasing)) }
        launch {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(650, easing = LinearOutSlowInEasing))
        }
        delay(950)
        visible = false
    }
    val f = last ?: return
    val forward = f.seconds >= 0
    Box(Modifier.fillMaxSize().padding(horizontal = 72.dp), contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 700f), 0.6f),
            exit = fadeOut(tween(320)) + scaleOut(tween(320), 1.12f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(80.dp).graphicsLayer {
                            val s = 1f + 0.45f * pulse.value
                            scaleX = s
                            scaleY = s
                            alpha = 0.55f * (1f - pulse.value)
                        }.border(2.dp, Color.White, CircleShape),
                    )
                    Box(Modifier.size(80.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Lc(
                            if (forward) L.drawable.lucide_ic_rotate_cw else L.drawable.lucide_ic_rotate_ccw,
                            Modifier.graphicsLayer { rotationZ = spin.value },
                            tint = Color.White,
                            size = 44.dp,
                        )
                        Text("${abs(f.seconds)}", style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp))
                    }
                }
                Text(
                    "${if (forward) "+" else "−"}${abs(f.seconds)} seconds",
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 4.dp),
                    style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** Paused for a while: what you are watching, over a dimmed frame — Netflix's pause screen, as on the TV. */
@Composable
private fun PausedCard(visible: Boolean, title: Title?, episode: Episode?, label: String, at: Double, duration: Double) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        shown = false
        if (visible) {
            delay(PAUSE_CARD_MS)
            shown = true
        }
    }
    AnimatedVisibility(shown && visible, enter = fadeIn(tween(700)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f), 0.6f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.3f)))) {
            Column(Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 32.dp, end = 24.dp).widthIn(max = 460.dp)) {
                Text("You're watching", style = Type.label.copy(color = Ink.muted, fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(8.dp))
                if (title != null) TitleMark(title, maxHeight = 64.dp, style = Type.display) else Text(label, style = Type.display, maxLines = 2)
                episode?.let { Text("S${it.season}:E${it.episode}  “${it.name}”", Modifier.padding(top = 10.dp), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (title != null) Facts(title.copy(genres = title.genres.take(2)), Modifier.padding(top = 10.dp))
                (episode?.overview ?: title?.plot)?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.body.copy(fontSize = 14.sp, color = Ink.text.copy(alpha = 0.8f)), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                // Where the film is, and how much is left of it — the reason to glance at a paused screen.
                if (duration > 0) {
                    val remaining = (duration - at).coerceAtLeast(0.0)
                    Column(Modifier.padding(top = 18.dp).fillMaxWidth()) {
                        ProgressLine((at / duration).toFloat(), height = 4.dp)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${clock(at)} of ${clock(duration)}", Modifier.weight(1f), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                            Text(left(remaining), style = Type.label.copy(color = Ink.muted))
                        }
                        val ends = remember(at, duration) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (remaining * 1000).toLong())) }
                        Text("Ends at $ends if you resume now", Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint))
                    }
                }
                Text("PAUSED", Modifier.padding(top = 16.dp), style = Type.overline.copy(color = Ink.muted, fontSize = 12.sp))
            }
        }
    }
}

/** Before the first frame: the title's own backdrop, its logo, and a ring that fills — the TV's loading screen. */
@Composable
private fun Loading(title: Title?, episode: Episode?, file: TorrentFile?, starting: Boolean, progress: Progress?, onBack: () -> Unit) {
    val percent = if (starting) 100 else progress?.buffer ?: 0
    val ring by animateFloatAsState(percent / 100f, Motion.spatial(), label = "ring")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        (episode?.thumbnail ?: title?.art)?.let {
            Artwork(it, title?.title.orEmpty(), Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.7f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.45f))))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
            Row(Modifier.align(Alignment.CenterStart).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (title != null) TitleMark(title, Modifier.widthIn(max = 380.dp), maxHeight = 76.dp, style = Type.display)
                    else Text(searchableName(file?.name.orEmpty()).ifBlank { file?.name.orEmpty() }, style = Type.display, maxLines = 2)
                    episode?.let { Text("S${it.season}:E${it.episode}  ${it.name}", Modifier.padding(top = 10.dp), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        val live = (progress?.peers ?: 0) > 0
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Ink.good else Ink.faint))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                starting -> "Starting playback"
                                progress == null -> "Finding peers"
                                progress.peers == 0 -> "Finding peers  ·  ${progress.dhtNodes} DHT nodes"
                                else -> "Buffering  ·  ${speed(progress.speed)}  ·  ${progress.peers} peers"
                            },
                            style = Type.label.copy(color = Ink.muted, fontSize = 14.sp),
                        )
                    }
                }
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    val red = Ink.accent
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 5.dp.toPx()
                        val inset = Offset(stroke / 2, stroke / 2)
                        val arc = Size(size.width - stroke, size.height - stroke)
                        drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, inset, arc, style = Stroke(stroke))
                        drawArc(red, -90f, 360f * ring, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Text("$percent%", style = Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/** Near the end: the next episode's still, its name, and a button that fills as the countdown runs. */
@Composable
private fun UpNext(name: String, still: String?, seconds: () -> Int, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInHorizontally { it / 3 }) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xF2141414)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(still, name, Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.padding(start = 14.dp).width(210.dp)) {
                Text("NEXT EPISODE", style = Type.overline.copy(color = Ink.muted))
                Text(name, Modifier.padding(top = 4.dp, bottom = 10.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val left = seconds()
                    val fill by animateFloatAsState(1f - left / UP_NEXT_SECONDS.toFloat(), tween(900), label = "fill")
                    Tap(onPlay, shape = RoundedCornerShape(6.dp)) {
                        Box(Modifier.background(Color.White).height(38.dp).width(120.dp)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill.coerceIn(0f, 1f)).background(Color.Black.copy(alpha = 0.14f)))
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                Lc(L.drawable.lucide_ic_play, tint = Color.Black, size = 15.dp)
                                Text("  Play  ${left}s", style = Type.label.copy(fontSize = 14.sp, color = Color.Black, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Tap(onCancel, shape = RoundedCornerShape(6.dp)) {
                        Text("Cancel", Modifier.background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 9.dp), style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

/** Playing on a TV: the title over its blurred art, and this screen as the remote. */
@Composable
private fun CastingCard(
    device: String,
    title: Title?,
    episode: Episode?,
    label: String,
    at: Double,
    duration: Double,
    paused: Boolean,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    var scrub by remember { mutableStateOf<Double?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }) {
        (episode?.thumbnail ?: title?.art)?.let {
            Artwork(it, label, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(16.dp) else Modifier))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
                Lc(L.drawable.lucide_ic_cast, Modifier.padding(start = 14.dp), tint = Ink.accent, size = 20.dp)
                Text("  Playing on $device", style = Type.label.copy(fontWeight = FontWeight.SemiBold))
            }
            Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (title != null) TitleMark(title, Modifier.widthIn(max = 340.dp), maxHeight = 70.dp, style = Type.display) else Text(label, style = Type.display, maxLines = 2)
                episode?.let { Text("S${it.season}:E${it.episode}  ${it.name}", Modifier.padding(top = 8.dp), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    RoundIcon(L.drawable.lucide_ic_rotate_ccw, { onSeek(at - SKIP) }, size = 50.dp)
                    RoundIcon(if (paused) L.drawable.lucide_ic_play else L.drawable.lucide_ic_pause, onToggle, size = 66.dp)
                    RoundIcon(L.drawable.lucide_ic_rotate_cw, { onSeek(at + SKIP) }, size = 50.dp)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                SeekBar(at = { scrub ?: at }, playing = { at }, duration = duration, scrubbing = scrub != null, onScrub = { scrub = it }, onCommit = {
                    scrub?.let(onSeek)
                    scrub = null
                })
                Tap(onStop, Modifier.align(Alignment.End).padding(top = 4.dp), RoundedCornerShape(6.dp)) {
                    Text("Stop casting", Modifier.background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 8.dp), style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** Audio and subtitles side by side, as Netflix lays them out, with the online search at the end. */
@Composable
private fun TracksPanel(
    tracks: Tracks?,
    subtitle: Int?,
    delay: Long,
    onDelay: (Long) -> Unit,
    audio: Int?,
    onSubtitle: (Int?) -> Unit,
    onAudio: (Int) -> Unit,
    onFind: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 32.dp, end = 64.dp, top = 24.dp, bottom = 12.dp)) {
            TrackColumn("Audio", Modifier.weight(1f)) {
                val list = tracks?.audio.orEmpty()
                if (list.isEmpty()) item { Note("No audio tracks reported yet.", Modifier.padding(start = 14.dp)) }
                itemsIndexed(list) { _, t -> TrackRow(t.name, t.id == audio) { onAudio(t.id) } }
            }
            Spacer(Modifier.width(32.dp))
            TrackColumn("Subtitles", Modifier.weight(1f)) {
                // Out of sync: a tenth of a second a tap; the value resets it.
                item {
                    Row(Modifier.padding(start = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Delay", Modifier.width(56.dp), style = Type.meta)
                        Tap({ onDelay(delay - 100) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_minus, Modifier.padding(8.dp), tint = Color.White, size = 18.dp) }
                        Tap({ onDelay(0) }, shape = RoundedCornerShape(6.dp)) {
                            Text("%+.1f s".format(delay / 1000.0), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = if (delay != 0L) Ink.accent else Color.White))
                        }
                        Tap({ onDelay(delay + 100) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_plus, Modifier.padding(8.dp), tint = Color.White, size = 18.dp) }
                    }
                }
                item { TrackRow("Off", subtitle == null) { onSubtitle(null) } }
                itemsIndexed(tracks?.subtitles.orEmpty()) { _, t -> TrackRow(t.name, t.id == subtitle) { onSubtitle(t.id) } }
                item {
                    Spacer(Modifier.height(8.dp))
                    TrackRow("Find subtitles online…", false, icon = L.drawable.lucide_ic_search, onClick = onFind)
                }
            }
        }
        RoundIcon(L.drawable.lucide_ic_x, onDismiss, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp))
    }
}

@Composable
private fun TrackColumn(heading: String, modifier: Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier) {
        Text(heading, Modifier.padding(start = 14.dp, bottom = 10.dp), style = Type.display.copy(fontSize = 20.sp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun TrackRow(label: String, on: Boolean, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(6.dp)) { pressed ->
        Row(Modifier.background(if (pressed) Color.White.copy(alpha = 0.1f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp)) {
                when {
                    icon != null -> Lc(icon, tint = Ink.muted, size = 18.dp)
                    on -> Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 20.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(label, style = Type.label.copy(color = if (on) Color.White else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What is playing and how it is going — dropped frames climbing means the player is discarding them. */
@Composable
private fun StatsPanel(s: Stats?, progress: Progress?, file: TorrentFile?) {
    fun mbps(kbps: Double) = "%.1f Mb/s".format(kbps / 1000)
    val rows = buildList {
        if (s == null) add("Player" to "Reading…")
        else {
            add("Player" to s.engine)
            add("Video" to listOfNotNull(s.videoCodec, if (s.width > 0) "${s.width}×${s.height}" else null, if (s.fps > 0) "%.3f fps".format(s.fps).replace(".000", "") else null).joinToString("  ·  ").ifEmpty { "—" })
            add("Audio" to listOfNotNull(s.audioCodec, if (s.channels > 0) "${s.channels} ch" else null, if (s.sampleRate > 0) "${s.sampleRate / 1000} kHz" else null, s.language).joinToString("  ·  ").ifEmpty { "—" })
            add("Bitrate" to "${mbps(s.contentKbps)} video  ·  ${mbps(s.inputKbps)} arriving")
            add("Frames" to "${s.displayed} shown  ·  ${s.lostFrames} dropped")
            add("Buffer" to "${s.cacheMs / 1000} s" + if (s.lostBuffers > 0) "  ·  ${s.lostBuffers} audio gaps" else "")
        }
        progress?.let { add("Swarm" to "${speed(it.speed)}  ·  ${it.peers} peers  ·  ${it.seeds} seeds") }
        file?.let { add("File" to "${bytes(it.size)}  ·  ${it.name}") }
    }
    Column(Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(label, Modifier.width(70.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp))
                Text(value, style = Type.meta.copy(color = Color.White, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
