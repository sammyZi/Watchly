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
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.TORBOX_STALL_MS
import com.torrentviewer.core.NeedsVlc
import com.torrentviewer.core.TORBOX_STALL_NOTE
import com.torrentviewer.core.Ep
import com.torrentviewer.core.Episode
import com.torrentviewer.core.History
import com.torrentviewer.core.Stremio
import com.torrentviewer.core.WatchedEverywhere
import com.torrentviewer.core.episodeProgress
import com.torrentviewer.core.isWatched
import com.torrentviewer.core.seasonWatched
import com.torrentviewer.core.ExoView
import com.torrentviewer.core.Facts
import com.torrentviewer.core.Secrets
import com.torrentviewer.core.Ink
import com.torrentviewer.core.IntroDb
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
import com.torrentviewer.core.resizeSubtitles
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Stats
import com.torrentviewer.core.Sub
import com.torrentviewer.core.findSubtitles
import com.torrentviewer.core.LANGS
import com.torrentviewer.core.langName
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.TorrentFile
import com.torrentviewer.core.Tracks
import com.torrentviewer.core.Type
import com.torrentviewer.core.Video
import com.torrentviewer.core.VlcView
import com.torrentviewer.core.Watch
import com.torrentviewer.core.Party
import com.torrentviewer.core.PartyAsk
import com.torrentviewer.core.PartyDriver
import com.torrentviewer.core.PartyPlayer
import com.torrentviewer.core.Floater
import com.torrentviewer.core.partyMedia
import com.torrentviewer.core.PARTY_REACTIONS
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.Dp
import com.torrentviewer.core.bytes
import com.torrentviewer.core.clock
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.episodeLabel
import com.torrentviewer.core.fileEpisodeLabel
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

private enum class Panel { TRACKS, SPEED, ASPECT, FIND, LANG, MORE, EPISODES, PARTY }

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

/**
 * Landscape, bars hidden, screen kept on — for as long as the player is up. The rest of the app is
 * portrait only (the manifest says so), and that is where it goes back to. Brightness goes back to the system's after.
 */
@Composable
private fun Fullscreen() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = WindowCompat.getInsetsController(activity.window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            bars.show(WindowInsetsCompat.Type.systemBars())
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

/**
 * One short buzz, from the motor itself: View haptics follow the system's touch-feedback
 * setting, which some phones (this vivo) leave off — the hold for 2× went silent there.
 */
private fun buzz(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    } ?: return
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(35)
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
    val ready by engine.ready.collectAsState()
    // A State, not a value: read where it is shown, so a tick does not recompose the player.
    val progressState = engine.progress.collectAsState()
    val title = watch.title
    val file = watch.playingFile
    // Again once the show's episode list is in: opened before it, the file looked like no episode at all.
    val episode = remember(file, title) { watch.playingEpisode(file) }
    val next = watch.next
    /** No next file in this torrent: the show's next episode, found online when asked for. */
    val onlineNext = remember(title, episode, next) {
        if (next != null || title == null || episode == null) null
        else title.episodes.indexOfFirst { it.id == episode.id }.takeIf { it >= 0 }?.let { title.episodes.getOrNull(it + 1) }
    }
    val nextEpisode = remember(next, title) { watch.episodeOf(next) } ?: onlineNext
    val startAt = remember { watch.startAt() }
    val bitrate = remember { file?.size?.let { size -> watch.item?.durations?.get(watch.playingIndex)?.takeIf { it > 0 }?.let { size / it } } ?: 0.0 }
    /** Intros are per show; a film has no recurring titles. */
    val show = remember(title, file) {
        if (title?.isSeries == true || parseEpisode(file?.name.orEmpty()) != null) title?.imdb ?: watch.item?.imdb ?: title?.title else null
    }

    val view = remember { mutableStateOf<Video?>(null) }
    /** Media3 unless the setting asks for VLC, or Media3 has already given up on this file — now or any time before. */
    var vlc by remember { mutableStateOf(SettingsStore.value.value.vlcPlayer || file?.let { NeedsVlc.has(it.name, it.size) } == true) }
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
    /** Bytes downloaded straight on from the playhead; -1 when it isn't a torrent. */
    var ahead by remember { mutableStateOf(-1L) }
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
    /** What subtitle sync last did, and whether it worked: shown in the subtitle panel. */
    var syncNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    /** The line a tap last moved, never moved again by the next tap: see SubtitleSync.match. */
    var syncedLine by remember { mutableStateOf<Long?>(null) }
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
    var advanced by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    /** Fetch the whole file, or only the next few minutes past the playhead; remembered for the next video. */
    var wholeFile by remember { mutableStateOf(SettingsStore.value.value.wholeFile.also { engine.wholeFile = it }) }
    /** Locked, with the lock icon up in its corner. */
    var lockShown by remember { mutableStateOf(false) }
    var boosting by remember { mutableStateOf(false) }
    /** IntroDB's window for this episode once it answers; until then, one marked on this phone before. */
    var intro by remember { mutableStateOf(show?.let(Intros::get)) }
    val pip = Pip.active
    val waiting = ready == null || !loaded
    /** The watch party this phone is in, if any, and its side of this player — shared with the TV. */
    val room = Party.room
    val wp = remember { PartyDriver(watch) }
    val guest = wp.guest
    /** Playing, but no new time for a while: starved, even where the player doesn't say buffering. */
    var stalled by remember { mutableStateOf(false) }

    fun show() {
        controls = true
        lastTouch = System.currentTimeMillis()
    }

    fun say(text: String, @DrawableRes icon: Int? = null) {
        hud = Hud(icon, text)
    }

    /** The subtitle files to sync against: the torrent's own and any downloaded. */
    fun subtitleFilesNow(): List<String> = ready?.subtitleFiles.orEmpty() + external.map { it.path }

    /** Put a sync's delay in, and say what it did in the panel and over the picture. */
    fun applySync(ms: Long) {
        if (kotlin.math.abs(ms - subDelay) < 150) {
            syncNote = "Already in sync." to true
            return
        }
        engine.note("player: subtitle tap sync set the delay to ${ms}ms (was ${subDelay}ms)")
        subDelay = ms
        val seconds = "%.1f".format(kotlin.math.abs(ms) / 1000.0)
        val what = if (ms >= 0) "$seconds s later" else "$seconds s earlier"
        syncNote = "Synced. Subtitles now show $what." to true
        say("Subtitles synced: $what", L.drawable.lucide_ic_circle_check)
        if (view.value?.setSubtitleDelay(ms) == false) {
            say("Switching to VLC to bring subtitles earlier", L.drawable.lucide_ic_captions)
            tracks = null
            langDone.fill(false)
            vlc = true
        }
    }

    fun togglePause() {
        val v = view.value ?: return
        // In a party, Play and Pause may be the party's: asked, or "go on without them".
        if (wp.onTogglePause()) {
            show()
            return
        }
        v.setPaused(!v.isPaused)
        paused = v.isPaused
        show()
    }

    /** Out of the player — and off the TV, whose stream stops with us, and out of the party (ended, for its leader). */
    fun leave() {
        if (castSession != null) runCatching { cast?.sessionManager?.endCurrentSession(true) }
        Party.leave()
        watch.exit()
    }

    /** An episode not in this torrent: its best stream, found online. */
    fun findEpisode(ep: Episode, onNone: () -> Unit = {}) {
        val t = title ?: return
        advanced = true
        if (at > 0) watch.savePosition(at)
        val tag = episodeTag(ep.season, ep.episode)
        say("Finding $tag…")
        scope.launch {
            val hit = runCatching { searchEpisode(t, ep.season, ep.episode) }.getOrNull()?.firstOrNull()
            if (hit == null) {
                watch.notes.tryEmit("No sources found for $tag.")
                advanced = false
                onNone()
            } else {
                watch.stream(hit, t, tag, ep)
            }
        }
    }

    fun goNext() {
        // A guest goes on when the leader does.
        if (advanced || guest) return
        val sibling = next
        if (sibling != null) {
            advanced = true
            watch.playSibling(sibling)
            return
        }
        findEpisode(onlineNext ?: return) { noNext = true }
    }

    /** Picked from the list: from this torrent if it holds the episode, else found online. */
    fun playEpisode(ep: Episode) {
        panel = null
        if (advanced || ep.id == episode?.id) return
        val sibling = watch.fileOf(ep) ?: return findEpisode(ep)
        advanced = true
        if (at > 0) watch.savePosition(at)
        watch.playSibling(sibling)
    }

    fun mediaCard() {
        if (castSession != null) return
        MediaCard.update(
            title = title?.title ?: watch.item?.label ?: "Watchly",
            subtitle = episode?.let { "${episodeLabel(title, it.season, it.episode)}  “${it.name}”" },
            paused = paused || !loaded,
            positionMs = (at * 1000).toLong(),
            durationMs = (duration * 1000).toLong(),
            rate = rate,
            hasNext = (next != null || onlineNext != null) && !noNext,
        )
    }

    val seeking = remember { mutableStateOf<Seeking?>(null) }
    val seekCheck = remember { mutableStateOf<Job?>(null) }

    /** [party]: a jump the watch party decided on — any other a guest is asked about first. */
    fun seekTo(seconds: Double, party: Boolean = false) {
        if (!party && wp.onSeek(seconds)) return
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
        mediaCard()
        wp.tell()
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

    /* ------------------------------------------------------------ media card */

    // The shade's and the lock screen's controls, and a headset's buttons. Casting, the
    // Cast SDK shows its own.
    val local = castSession == null
    val hasNext = (next != null || onlineNext != null) && !noNext
    val nextNow by rememberUpdatedState { goNext() }
    val seekNow by rememberUpdatedState { s: Double -> seekTo(s) }
    DisposableEffect(local) {
        val remote = object : MediaCard.Remote {
            override fun play() {
                if (wp.onRemotePlay()) return
                view.value?.setPaused(false)
                paused = false
            }
            override fun pause() {
                view.value?.setPaused(true)
                paused = true
                if (at > 0) watch.savePosition(at)
            }
            override fun next() = nextNow()
            override fun seek(ms: Long) = seekNow(ms / 1000.0)
        }
        if (local) MediaCard.start(context, remote)
        onDispose { MediaCard.stop(remote) }
    }
    // The system runs the card's clock on from each update; this keeps it honest through stalls.
    LaunchedEffect(local, title, episode, paused, loaded, duration, rate, hasNext) {
        if (!local) return@LaunchedEffect
        while (true) {
            mediaCard()
            delay(10_000)
        }
    }
    LaunchedEffect(local, title) {
        if (local) MediaCard.artwork(title?.poster ?: watch.item?.poster)
    }

    /* ------------------------------------------------------------ watch party */

    // The party's side of this player, the same code as the TV's: core/PartyDriver.
    val partyPlayer = remember {
        // The player's own state by other names: inside the object, `paused` or `say` would be the object's.
        val pausedNow = { paused }
        val loadedNow = { loaded }
        val boostingNow = { boosting }
        val rateNow = { rate }
        val seekingNow = { seeking.value != null }
        val starvedNow = { buffering || (stalled && !paused) }
        val pause = { on: Boolean ->
            view.value?.setPaused(on)
            paused = on
        }
        val show = { r: Float -> rate = r }
        val tell = { text: String, icon: Int? -> say(text, icon) }
        object : PartyPlayer {
            override fun time() = (view.value?.time ?: 0L) / 1000.0
            override val spot get() = at
            override val paused get() = pausedNow()
            override val loaded get() = loadedNow()
            override val starved get() = starvedNow()
            override val seeking get() = seekingNow()
            override val boosting get() = boostingNow()
            // Holding for 2× counts: the room goes at the speed the leader's film really runs at.
            override val rate get() = if (boostingNow()) HOLD_RATE else rateNow()
            override val fileName get() = file?.name
            override fun setPaused(on: Boolean) = pause(on)
            override fun steerRate(r: Float) {
                view.value?.setRate(r)
            }
            override fun showRate(r: Float) = show(r)
            override fun jump(seconds: Double) = seekTo(seconds, party = true)
            override fun say(text: String, icon: Int?) = tell(text, icon)
            override fun savePosition() {
                if (at > 0) watch.savePosition(at)
            }
        }
    }
    wp.Effects(partyPlayer, panel == Panel.PARTY)
    // The party gone while its panel was up (the leader ended it): the panel shuts, rather than offer to start another.
    val hadRoom = remember { booleanArrayOf(false) }
    LaunchedEffect(room) {
        if (room == null && hadRoom[0] && panel == Panel.PARTY) panel = null
        hadRoom[0] = room != null
    }

    /**
     * Shares the film's artwork with the link and nothing else: the link as the text, the poster as the picture.
     * With no art to be had (offline, no poster) it is the link alone.
     */
    fun invite(r: com.torrentviewer.core.Room) {
        val film = r.film ?: watch.partyMedia()
        val art = film?.poster ?: film?.imdb?.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/$it/img" }
        scope.launch {
            val uri = art?.let { url ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(context.cacheDir, "party-art.jpg")
                        val c = java.net.URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                        c.getInputStream().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    }.getOrNull()
                }
            }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_TEXT, r.link)
            if (uri != null) {
                send.setType("image/jpeg").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                send.clipData = android.content.ClipData.newRawUri("", uri)
                send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else send.setType("text/plain")
            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Invite to the watch party")) }
        }
    }

    /** The button opens the panel: with no party yet it offers "Start a watch party", and nothing opens until it is pressed. */
    fun partyButton() {
        if (room == null && watch.partyMedia() == null) {
            watch.notes.tryEmit("Only a torrent can be watched together — this one is a file or a plain link.")
            return
        }
        panel = Panel.PARTY
    }

    fun startParty() {
        val media = watch.partyMedia()
        if (room != null || media == null) return
        invite(Party.host(media))
    }

    /* ------------------------------------------------------------ effects */

    // This episode's or film's intro.
    val imdb = title?.imdb ?: watch.item?.imdb
    val introEp = remember(episode, file) { episode?.let { Ep(it.season, it.episode) } ?: parseEpisode(file?.name.orEmpty()) }
    LaunchedEffect(imdb, introEp) {
        if (imdb == null) return@LaunchedEffect
        val found = if (introEp != null) IntroDb.intro(imdb, introEp.season, introEp.episode) else if (title?.isSeries == false) IntroDb.intro(imdb) else null
        found?.let { intro = it }
    }

    // The stream arrives: hand it to the player, with the torrent's own subtitle files.
    // From where it is, not startAt: a fallback to VLC picks up mid-film.
    LaunchedEffect(ready, view.value) {
        val r = ready ?: return@LaunchedEffect
        val v = view.value ?: return@LaunchedEffect
        v.headers = r.headers
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
            // Back from another app — sharing a party's invite, say — paused: the controls up, not a black
            // picture that looked like nothing was there, where Back (to get somewhere) ended the party.
            if (event == Lifecycle.Event.ON_START && loaded && paused) show()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(loaded) { if (loaded) show() }
    LaunchedEffect(controls, lastTouch, paused, scrub, panel) {
        if (!controls || scrub != null || panel != null) return@LaunchedEffect
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
            ahead = withContext(Dispatchers.IO) { watch.engine.bytesAhead() }
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
    // Well before the end — 70% through, or ten minutes left — the next episode is made ready.
    val nearEnd by remember { derivedStateOf { loaded && duration > 0 && (at / duration >= 0.7 || duration - at <= 600) } }
    LaunchedEffect(nearEnd, next, onlineNext) {
        if (nearEnd && !noNext && (next != null || onlineNext != null)) watch.warmNext(next, onlineNext)
    }
    LaunchedEffect(offerNext) {
        if (!offerNext) return@LaunchedEffect
        snapshotFlow { duration - at }.first { it <= 0.5 }
        goNext()
    }
    val skipIntro by remember { derivedStateOf { intro?.covers(at) == true && loaded } }
    // Once per intro: scrubbing back into it to watch it is not undone.
    var autoSkipped by remember(intro) { mutableStateOf(false) }
    LaunchedEffect(skipIntro) {
        val i = intro ?: return@LaunchedEffect
        // A guest's would only ask the leader, whose own skip brings everyone along.
        if (!skipIntro || autoSkipped || guest || !SettingsStore.value.value.autoSkipIntro) return@LaunchedEffect
        autoSkipped = true
        seekTo(i.end)
        say("Skipped intro", L.drawable.lucide_ic_chevrons_right)
    }

    /* ---------------------------------------------------- TorBox stall */

    // A TorBox stream that hasn't moved goes to Watchly's own engine: a video this phone can't
    // decode left VLC silent, with no error to fall back on, and the film never started. Counted
    // again from the switch to VLC: Media3's failed attempt used 4–6 s of VLC's time.
    LaunchedEffect(watch.via, vlc) {
        if (watch.via == null) return@LaunchedEffect
        val from = at
        kotlinx.coroutines.delay(TORBOX_STALL_MS)
        if (watch.via != null && !paused && at < from + 1) watch.leaveTorBox(at, TORBOX_STALL_NOTE)
    }

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
            // Holding for 2× only with the controls down, so a held button never speeds the film up;
            // never for a party guest, whose speed is the leader's.
            val partyGuest = Party.room?.leader == false
            val kind = withTimeoutOrNull(if (paused || locked || controls || partyGuest) Long.MAX_VALUE else HOLD_MS) {
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
                    buzz(context)
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
                        v.onNote = { engine.note("player: $it") }
                        if (!vlc) v.onUnsupported = { message ->
                            Log.w("Player", "Media3 gave up ($message), falling back to VLC")
                            watch.engine.note("player: Media3 gave up ($message), falling back to VLC")
                            // Said, so a slower start isn't a mystery: VLC takes a few seconds more to open a file.
                            // A note, not the HUD: the loading screen is usually still up and hides the HUD.
                            watch.notes.tryEmit("Switching to VLC — the built-in player can't play this video")
                            // Next time this file opens straight in VLC.
                            file?.let { NeedsVlc.add(it.name, it.size) }
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
                                engine.noteStarted()
                                // Aim the engine at the resume point now the real runtime is known. Not over a
                                // measured start: running time is a poorer guess, and pulled pieces from behind the picture.
                                if (startAt > 0 && duration > 0 && !watch.startIsMeasured()) scope.launch(Dispatchers.IO) { engine.aimStart(minOf(0.99, startAt / duration)) }
                            }
                        }
                        v.onTime = { ms ->
                            watch.aheadSeconds = v.aheadSeconds
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
                            // What is really on screen: a subtitle just downloaded is turned on by the view itself.
                            subtitle = t.spuId.takeIf { it >= 0 }
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
                            watch.engine.note("player: failed — $message")
                            // From TorBox: on to Watchly's own engine at the same spot, not out of the film.
                            if (!watch.leaveTorBox(at)) {
                                watch.notes.tryEmit(message)
                                watch.exit()
                            }
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

        PausedCard(paused && !controls && panel == null && !waiting && !locked, title, episode, watch.item?.label.orEmpty(), { at }, duration)

        AnimatedVisibility(controls && !waiting && !locked, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    TopBar(
                        title, episode, file, progressState.value, duration, { at }, rate, info,
                        canCast = cast != null,
                        party = room?.let { maxOf(it.people, it.members.size) } ?: 0,
                        unread = room?.unread ?: 0,
                        onParty = if (Party.enabled) ::partyButton else null,
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
                        // Icons alone, just the few reached for mid-film; the rest under More.
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
                            watch.prev?.let { prev -> BarButton(L.drawable.lucide_ic_skip_back, "Previous") { watch.playSibling(prev) } }
                            BarButton(L.drawable.lucide_ic_gauge, "Speed (${rateLabel(rate)})", on = rate != 1f) { panel = Panel.SPEED }
                            BarButton(L.drawable.lucide_ic_captions, "Audio & subtitles", on = subtitle != null) { panel = Panel.TRACKS }
                            if (canPip) BarButton(L.drawable.lucide_ic_picture_in_picture_2, "Mini player") {
                                pipParams()?.let { p -> runCatching { activity?.enterPictureInPictureMode(p) } }
                            }
                            BarButton(L.drawable.lucide_ic_ellipsis, "More", on = scale != 0 || SLEEP[sleep] != 0 || wholeFile) { panel = Panel.MORE }
                            if (title?.episodes.orEmpty().isNotEmpty()) BarButton(L.drawable.lucide_ic_list, "Episodes") { panel = Panel.EPISODES }
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

        // A short word for anything that happened without the bar: "Added EN", "60%". Over the panels
        // too: said from the subtitle panel, it came up behind it and was never read.
        val shownHud = hud
        AnimatedVisibility(shownHud != null && !waiting, Modifier.zIndex(10f).align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 20.dp), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut()) {
            val h = shownHud ?: return@AnimatedVisibility
            Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                h.icon?.let {
                    Lc(it, tint = Color.White, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(h.text, style = Type.label.copy(fontWeight = FontWeight.SemiBold))
            }
        }

        // The party held for someone loading, on every screen in it; the leader can go on without them.
        val heldFor = wp.heldFor
        if (heldFor.isNotEmpty() && wp.ask == null) {
            val others = heldFor - wp.me
            Row(
                Modifier.zIndex(11f).align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 64.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner(size = 18.dp)
                Text(
                    when {
                        others.isEmpty() -> "  Everyone is waiting while yours loads"
                        wp.me in heldFor -> "  Waiting for you and ${others.joinToString()}"
                        else -> "  Waiting for ${others.joinToString()} to load"
                    },
                    Modifier.padding(vertical = 9.dp),
                    style = Type.label.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.width(10.dp))
                if (room?.leader == true) Tap(wp::goOnWithout, shape = RoundedCornerShape(8.dp)) {
                    Text("Go on", Modifier.background(Ink.accent).padding(horizontal = 14.dp, vertical = 9.dp), style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                }
            }
        }

        // Guest: whose pause or jump this is.
        wp.choice?.let { c ->
            val jumpTo = c.seekTo
            Column(
                Modifier.zIndex(12f).align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xF2101012)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (jumpTo != null) "Jump to ${clock(jumpTo)}" else "Pause", style = Type.title.copy(color = Color.White, fontWeight = FontWeight.Bold))
                val verb = if (jumpTo != null) "jump" else "pause"
                Text(
                    if (wp.leaderState?.asks == false) "Only the leader can $verb the party — or $verb just for you, and catch up after."
                    else "Just for you, or ask the leader to $verb for everyone?",
                    Modifier.padding(top = 4.dp, bottom = 14.dp).widthIn(max = 300.dp),
                    style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f)),
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tap(wp::justMe, shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Lc(L.drawable.lucide_ic_user, tint = Color.White, size = 18.dp)
                            Text("  Just me", style = Type.label.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                        }
                    }
                    if (wp.leaderState?.asks != false) Tap(wp::everyone, shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.background(Ink.accent).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Lc(L.drawable.lucide_ic_users, tint = Color.White, size = 18.dp)
                            Text("  Everyone", style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }

        // Guest on its own: the way back to the party, always in reach.
        if (guest && wp.detached && !locked) Row(
            Modifier.zIndex(11f).align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = if (controls) 132.dp else 22.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha = 0.8f)).padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(L.drawable.lucide_ic_user, tint = Color(0xFF7AA7FF), size = 16.dp)
            Text("  On your own", style = Type.meta.copy(color = Color.White))
            Spacer(Modifier.width(10.dp))
            Tap(wp::catchUp, shape = CircleShape) {
                Text("Catch up", Modifier.background(Ink.accent).padding(horizontal = 14.dp, vertical = 8.dp), style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
            }
        }

        // Leader: a guest asking to pause, play or jump.
        wp.ask?.let { a ->
            val what = when (a.what) {
                "pause" -> "pause"
                "play" -> "play"
                else -> "jump to ${clock(a.pos)}"
            }
            Row(
                Modifier.zIndex(11f).align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 64.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Lc(L.drawable.lucide_ic_users, tint = Ink.accent, size = 18.dp)
                Text("  ${a.from} asks to $what", style = Type.label.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.width(10.dp))
                Tap({ wp.ask = null }, shape = RoundedCornerShape(8.dp)) {
                    Text("No", Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = Type.label.copy(color = Color.White.copy(alpha = 0.7f)))
                }
                Tap({ wp.ask = null; wp.allow(a) }, shape = RoundedCornerShape(8.dp)) {
                    Text("Allow", Modifier.background(Ink.accent).padding(horizontal = 14.dp, vertical = 9.dp), style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                }
            }
        }

        // Reactions, rising and fading over the picture.
        wp.floating.forEach { f -> key(f.id) { FloatingReaction(f) { wp.floating.remove(f) } } }

        // A chat line from someone, bottom left, while the panel is shut; a tap opens the panel.
        val peek = wp.chatPeek
        AnimatedVisibility(
            peek != null && panel == null && !locked,
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, bottom = if (controls) 130.dp else 24.dp),
            enter = fadeIn() + slideInHorizontally { -it / 3 },
            exit = fadeOut(),
        ) {
            val p = peek ?: return@AnimatedVisibility
            Tap({ wp.chatPeek = null; panel = Panel.PARTY }, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.widthIn(max = 320.dp).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    p.from?.let { NameDot(it, 24.dp) }
                    Column(Modifier.padding(start = 10.dp)) {
                        p.from?.let { Text(it, style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)) }
                        Text(p.text, style = Type.label.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (boosting) {
            Row(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Lc(L.drawable.lucide_ic_fast_forward, tint = Color.White, size = 16.dp)
                Text("  ${rateLabel(HOLD_RATE)}", style = Type.label.copy(fontWeight = FontWeight.Bold))
            }
        }

        AnimatedVisibility(info && !waiting, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = if (controls) 64.dp else 16.dp, top = if (controls) 64.dp else 16.dp), enter = fadeIn(), exit = fadeOut()) {
            StatsPanel(stats, progressState.value, file, duration, ahead)
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
            Loading(title, episode, file, ready != null, progressState.value, watch.via, watch.askingTorBox, onBack = ::leave)
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

        /** Online subtitles in the chosen language — again whenever it is changed from the sheet. */
        fun findSubs() {
            panel = Panel.FIND
            finding = true
            found = null
            scope.launch {
                found = runCatching {
                    // OpenSubtitles needs no key; SubDL joins in once the viewer has given theirs.
                    val ep = parseEpisode(file?.name.orEmpty())
                    findSubtitles(
                        query = title?.title ?: searchableName(file?.name.orEmpty()),
                        imdb = title?.imdb ?: watch.item?.imdb,
                        season = ep?.season,
                        episode = ep?.episode,
                        type = if (ep != null || title?.isSeries == true) "tv" else "movie",
                        language = SettingsStore.value.value.subLang,
                        subdlKey = Secrets.subdl.value.ifBlank { BuildConfig.SUBDL_KEY },
                        fileName = file?.name.orEmpty(),
                    )
                }.getOrElse {
                    watch.notes.tryEmit(it.message ?: "Subtitle search failed")
                    emptyList()
                }
                finding = false
            }
        }

        when (panel) {
            Panel.TRACKS -> TracksPanel(
                tracks = tracks,
                subtitle = subtitle,
                delay = subDelay,
                onDelay = { ms ->
                    engine.note("player: subtitle delay set to ${ms}ms")
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
                subSize = SettingsStore.value.collectAsState().value.subtitles.size,
                onSubSize = { step -> view.value?.subtitleStyle = SettingsStore.resizeSubtitles(step) },
                // Picking one yourself wins over the preferred language.
                onSubtitle = {
                    langDone[1] = true
                    subtitle = it
                    engine.note("player: subtitle picked: ${it?.let { id -> tracks?.subtitles?.find { s -> s.id == id }?.name ?: "#$id" } ?: "off"} (${if (vlc) "VLC" else "Media3"}, delay ${subDelay}ms)")
                    view.value?.setSubtitleTrack(it)
                },
                onAudio = {
                    langDone[0] = true
                    audio = it
                    view.value?.setAudioTrack(it)
                },
                onFind = ::findSubs,
                // Only a subtitle file has lines to read; one built into the video is timed with − and +.
                canSync = subtitle != null && (ready?.subtitleFiles.orEmpty().isNotEmpty() || external.isNotEmpty()),
                syncNote = syncNote,
                onSyncLine = {
                    // As a line begins: the subtitle file's nearest line is moved to this moment.
                    val pressMs = (at * 1000).toLong()
                    val files = subtitleFilesNow()
                    scope.launch {
                        val best = withContext(Dispatchers.IO) {
                            files.mapNotNull { f -> runCatching { com.torrentviewer.core.SubtitleSync.match(com.torrentviewer.core.SubtitleSync.starts(java.io.File(f).readText()), pressMs, subDelay, syncedLine) }.getOrNull() }
                                .minByOrNull { it.distanceMs }
                        }
                        // Said in the panel itself, under the button that was pressed.
                        if (best == null) {
                            syncNote = "No other line near that moment. Tap just as someone starts speaking." to false
                        } else {
                            syncedLine = best.lineMs
                            applySync(best.delayMs)
                        }
                    }
                },
                onDismiss = { panel = null },
            )
            Panel.SPEED -> Sheet(
                title = "Playback speed",
                options = RATES.map { Option(it, rateLabel(it), if (it == 1f) "Normal" else null) },
                selected = rate,
                onPick = {
                    panel = null
                    if (guest) {
                        say("The leader sets the speed in a watch party", L.drawable.lucide_ic_users)
                        return@Sheet
                    }
                    rate = it
                    view.value?.setRate(it)
                    SettingsStore.save(SettingsStore.value.value.copy(playbackRate = it))
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
            // Sleep and download are toggles: the sheet stays up, its hint showing the new setting.
            Panel.MORE -> Sheet(
                title = "More",
                options = listOfNotNull(
                    Option("picture", "Picture", SCALES[scale].second),
                    Option(
                        "sleep", "Sleep timer",
                        when {
                            SLEEP[sleep] == 0 -> "Off"
                            SLEEP[sleep] < 0 -> "End of episode"
                            else -> "${maxOf(1L, (sleepEnds - System.currentTimeMillis() + 59_999) / 60_000)} min left"
                        },
                    ),
                    Option("download", "Download ahead", if (wholeFile) "Whole file" else "Next 5 minutes"),
                    Option("open", "Open in another player"),
                ),
                selected = null,
                onPick = { what ->
                    when (what) {
                        "picture" -> panel = Panel.ASPECT
                        "sleep" -> cycleSleep()
                        "download" -> {
                            wholeFile = !wholeFile
                            engine.wholeFile = wholeFile
                            SettingsStore.save(SettingsStore.value.value.copy(wholeFile = wholeFile))
                            say(if (wholeFile) "Downloading the whole file" else "Downloading the next 5 minutes", if (wholeFile) L.drawable.lucide_ic_hard_drive else L.drawable.lucide_ic_clock)
                        }
                        "open" -> {
                            panel = null
                            ready?.let { r ->
                                if (openExternally(context, r.url, r.name, at)) {
                                    view.value?.setPaused(true)
                                    paused = true
                                } else watch.notes.tryEmit("No other player on this phone can open it.")
                            }
                        }
                    }
                },
                onDismiss = { panel = null },
            )
            Panel.FIND -> Sheet(
                title = "Find subtitles",
                // First, the language searched in — the Settings one — to change it right here.
                options = listOf(Option(-1, "Language: " + SettingsStore.value.value.subLang.ifEmpty { "EN" }.let(::langName), "Change")) +
                    found.orEmpty().mapIndexed { i, s -> Option(i, s.language, s.release.ifEmpty { s.name }) } +
                    listOfNotNull(Option(-2, if (finding) "Searching…" else "Nothing found in this language").takeIf { found.isNullOrEmpty() }),
                selected = null,
                onPick = { i ->
                    if (i == -1) panel = Panel.LANG
                    if (i < 0) return@Sheet
                    val sub = found?.getOrNull(i) ?: return@Sheet
                    panel = null
                    scope.launch {
                        runCatching {
                            val path = withContext(Dispatchers.IO) { engine.saveSubtitle(sub.url, sub.label.replace(Regex("\\W+"), "_")) }
                            if (external.none { it.path == path }) {
                                external = external + SavedSub(path, sub.label)
                                watch.saveSubs(external)
                            }
                            view.value?.addSubtitleFiles(listOf(path), select = true)
                            say("Added ${sub.language}", L.drawable.lucide_ic_captions)
                        }.onFailure { watch.notes.tryEmit(it.message ?: "Couldn't fetch that subtitle") }
                    }
                },
                onDismiss = { panel = null },
            )
            // The same list as Settings → Subtitles, and the same setting: it sticks for the next film.
            Panel.LANG -> Sheet(
                title = "Subtitle language",
                options = LANGS.map { Option(it.code, it.name) },
                selected = SettingsStore.value.value.subLang.ifEmpty { "EN" },
                onPick = { code ->
                    SettingsStore.save(SettingsStore.value.value.copy(subLang = code))
                    findSubs()
                },
                onDismiss = { panel = Panel.FIND },
            )
            Panel.EPISODES -> title?.let { EpisodesPanel(it, episode, ::playEpisode) { panel = null } }
            // A party just left shuts its panel (Leave does) rather than turn into the offer to start one.
            Panel.PARTY -> room?.let { r ->
                PartyPanel(
                    r,
                    heldFor = wp.heldFor,
                    onInvite = { invite(r) },
                    onGoOn = wp::goOnWithout,
                    onLeave = {
                        panel = null
                        Party.leave()
                    },
                    onDismiss = { panel = null },
                )
            } ?: StartPartyPanel(title?.title ?: watch.item?.label ?: "this film", ::startParty) { panel = null }
            null -> Unit
        }
    }
}

/**
 * The party button with no party yet: what a watch party is, and one button to begin it. Nothing is
 * opened or shared until it is pressed — a tap on the button alone used to start a room at once.
 */
@Composable
private fun StartPartyPanel(film: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); onDismiss() } })
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 460.dp).fillMaxWidth(0.9f)
                .background(Color(0xF2101012)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Watch party", Modifier.weight(1f), style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                PanelIcon(L.drawable.lucide_ic_x, "Close", onDismiss)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(end = 8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Lc(L.drawable.lucide_ic_users, tint = Color.White.copy(alpha = 0.6f), size = 44.dp)
                Text("Watch $film together", Modifier.padding(top = 18.dp), style = Type.title.copy(color = Color.White, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                Text(
                    "Start a party and send the link. Everyone who opens it plays the film in step with you, with chat and reactions. No account, and only play, pause and the spot are shared — encrypted.",
                    Modifier.padding(top = 8.dp).widthIn(max = 340.dp),
                    style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f)),
                    textAlign = TextAlign.Center,
                )
                Button("Start a watch party", onStart, Modifier.padding(top = 28.dp).widthIn(min = 240.dp), icon = L.drawable.lucide_ic_users, primary = true)
            }
        }
    }
}

/**
 * The watch party down the right of the picture, as the episode list sits: who is here and what each
 * player is doing, who the film is held for, reactions, and the chat. The picture stays in view.
 */
@Composable
private fun PartyPanel(
    room: com.torrentviewer.core.Room,
    heldFor: List<String>,
    onInvite: () -> Unit,
    onGoOn: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var draft by remember { mutableStateOf("") }
    val people = room.members.values.sortedWith(compareByDescending<com.torrentviewer.core.Member> { it.leader }.thenByDescending { it.me }.thenBy { it.name.lowercase() })
    val list = rememberLazyListState()
    LaunchedEffect(room.chat.size) {
        room.unread = 0
        if (room.chat.isNotEmpty()) list.animateScrollToItem(room.chat.lastIndex)
    }
    fun send() {
        room.say(draft)
        draft = ""
    }
    var tab by remember { mutableStateOf("chat") }
    val settings by SettingsStore.value.collectAsState()
    // Turned, the keyboard takes most of the screen: then only the conversation and the box stay.
    val typing = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val faint = Color.White.copy(alpha = 0.55f)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); onDismiss() } })
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 460.dp).fillMaxWidth(0.9f)
                .background(Color(0xF2101012)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        ) {
            if (!typing) {
                // One line: what this is, how many, and its three actions as icons.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (room.leader) "Your watch party" else "Watch party", style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                        Text(
                            if (room.connected) "${people.size.coerceAtLeast(room.people)} watching · encrypted" else "Reconnecting…",
                            style = Type.meta.copy(color = if (room.connected) faint else Ink.warn, fontSize = 12.sp),
                        )
                    }
                    PanelIcon(L.drawable.lucide_ic_user_plus, "Invite", onInvite)
                    PanelIcon(L.drawable.lucide_ic_log_out, if (room.leader) "End party" else "Leave", onLeave)
                    PanelIcon(L.drawable.lucide_ic_x, "Close", onDismiss)
                }

                if (heldFor.isNotEmpty()) Row(
                    Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.warn.copy(alpha = 0.16f)).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(size = 14.dp, color = Ink.warn)
                    Text("  Waiting for ${heldFor.joinToString()}", Modifier.weight(1f).padding(vertical = 6.dp), style = Type.meta.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (room.leader) Tap(onGoOn, shape = RoundedCornerShape(8.dp)) {
                        Text("Go on", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = Type.label.copy(color = Ink.warn, fontWeight = FontWeight.Bold))
                    }
                }

                // Chat and People each get the whole height, rather than sharing it.
                Row(Modifier.padding(top = 8.dp, bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)).padding(3.dp)) {
                    listOf("chat" to "Chat", "people" to "People · ${people.size.coerceAtLeast(room.people)}").forEach { (key, label) ->
                        val on = tab == key
                        Tap({ tab = key }, Modifier.weight(1f), RoundedCornerShape(8.dp)) {
                            Text(
                                label,
                                Modifier.fillMaxWidth().background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent).padding(vertical = 7.dp),
                                style = Type.meta.copy(color = if (on) Color.White else faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            if (tab == "people" && !typing) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    people.forEach { m -> PersonRow(m) }
                    if (people.size <= 1) Text("No one else yet — tap the invite button above.", style = Type.meta.copy(color = faint))
                    Text("ON THE PICTURE", Modifier.padding(top = 8.dp), style = Type.overline.copy(color = faint))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Chat messages", settings.partyShowChat, onClick = { SettingsStore.save(settings.copy(partyShowChat = !settings.partyShowChat)) }, compact = true)
                        Chip("Reactions", settings.partyShowReactions, onClick = { SettingsStore.save(settings.copy(partyShowReactions = !settings.partyShowReactions)) }, compact = true)
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (room.chat.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Lc(L.drawable.lucide_ic_message_circle, tint = faint, size = 28.dp)
                            Text("Say hello", Modifier.padding(top = 8.dp), style = Type.label.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                            Text("Only the people in this party see it, and it's gone when the party ends.", Modifier.padding(top = 2.dp), style = Type.meta.copy(color = faint), textAlign = TextAlign.Center)
                        }
                    }
                    items(room.chat) { ChatRow(it) }
                }
                if (!typing) Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PARTY_REACTIONS.forEach { e ->
                        Tap({ room.react(e) }, Modifier.semantics { contentDescription = "React $e" }, CircleShape) {
                            Text(e, Modifier.padding(5.dp), fontSize = 20.sp)
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(draft, { draft = it.take(com.torrentviewer.core.Party.CHAT_MAX) }, "Message", Modifier.weight(1f), onSubmit = ::send, action = androidx.compose.ui.text.input.ImeAction.Send)
                    Tap(::send, Modifier.padding(start = 4.dp).semantics { contentDescription = "Send" }, CircleShape) {
                        Box(Modifier.size(44.dp).background(if (draft.isBlank()) Color.White.copy(alpha = 0.1f) else Ink.accent), contentAlignment = Alignment.Center) {
                            Lc(L.drawable.lucide_ic_send_horizontal, tint = Color.White, size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Tap(onClick, Modifier.semantics { contentDescription = label }, CircleShape) { Lc(icon, Modifier.padding(9.dp), tint = Color.White, size = 20.dp) }
}

@Composable
private fun PersonRow(m: com.torrentviewer.core.Member) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NameDot(m.name, 32.dp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name + if (m.me) " (you)" else "", style = Type.label.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.leader) Lc(L.drawable.lucide_ic_crown, Modifier.padding(start = 6.dp), tint = Color(0xFFF5C542), size = 14.dp)
            }
            val (word, colour) = when (m.status) {
                "watching" -> "Watching" to Ink.good
                "paused" -> "Paused" to Color.White.copy(alpha = 0.55f)
                "loading" -> "Loading…" to Ink.warn
                "behind" -> "Watching on their own" to Color(0xFF7AA7FF)
                else -> "Opening the film…" to Color.White.copy(alpha = 0.55f)
            }
            Text(word, style = Type.meta.copy(color = colour))
        }
    }
}

@Composable
private fun ChatRow(line: com.torrentviewer.core.ChatLine) {
    val from = line.from
    if (from == null) {
        Text(line.text, Modifier.fillMaxWidth(), style = Type.meta.copy(color = Color.White.copy(alpha = 0.5f)), textAlign = TextAlign.Center)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!line.mine) NameDot(from, 24.dp)
        Column(
            Modifier.padding(start = if (line.mine) 40.dp else 8.dp, end = if (line.mine) 0.dp else 40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (line.mine) Ink.accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.1f)).padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            if (!line.mine) Text(from, style = Type.meta.copy(color = Color.White.copy(alpha = 0.65f), fontWeight = FontWeight.Bold))
            Text(line.text, style = Type.label.copy(color = Color.White))
        }
    }
}

/** A round initial in a colour of its own per name, so the same person looks the same on every phone. */
@Composable
internal fun NameDot(name: String, size: Dp) {
    val hues = listOf(0xFFE5484D, 0xFF3E63DD, 0xFF30A46C, 0xFFF76B15, 0xFF8E4EC6, 0xFF12A594, 0xFFD6409F, 0xFFFFB224)
    val c = Color(hues[(name.hashCode() and Int.MAX_VALUE) % hues.size])
    Box(Modifier.size(size).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp))
    }
}

/** One reaction rising up the picture and fading, with who sent it under it. */
@Composable
private fun FloatingReaction(f: Floater, onDone: () -> Unit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(2600))
        onDone()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        Column(
            Modifier.offset { androidx.compose.ui.unit.IntOffset((w * f.x).toInt(), (h * (0.8f - 0.55f * rise.value)).toInt()) }
                .graphicsLayer { alpha = 1f - rise.value * rise.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(f.emoji, fontSize = 34.sp)
            Text(f.from, style = Type.meta.copy(color = Color.White, fontSize = 11.sp))
        }
    }
}

/** Every episode of the show down the right of the picture, by season, the one playing in view; picking one plays it. */
@Composable
private fun EpisodesPanel(title: Title, playing: Episode?, onPlay: (Episode) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val history by History.items.collectAsState()
    val library by WatchedEverywhere.collectAsState(Stremio.library.value)
    val seen = remember(history, library, title.episodes) { episodeProgress(history, title.imdb, library, title.episodes) }
    // Specials after the seasons.
    val seasons = remember(title.episodes) { title.episodes.map { it.season }.distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it } }
    var season by remember { mutableStateOf(playing?.season ?: seasons.first()) }
    Box(Modifier.fillMaxSize()) {
        // The picture stays in view beside the list; a tap on it closes the list.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); onDismiss() } })
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 400.dp).fillMaxWidth(0.9f)
                .background(Color.Black.copy(alpha = 0.92f)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 12.dp, top = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Episodes", Modifier.weight(1f), style = Type.title.copy(color = Color.White, fontWeight = FontWeight.Bold))
                Tap(onDismiss, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(8.dp), tint = Color.White, size = 22.dp) }
            }
            if (seasons.size > 1) Row(Modifier.padding(top = 4.dp, bottom = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                seasons.forEach { s ->
                    // A green tick on a season watched through.
                    val done = seasonWatched(title.episodes, s, seen)
                    Chip(if (s == 0) "Specials" else "Season $s", s == season, onClick = { season = s }, trailing = L.drawable.lucide_ic_check.takeIf { done }, trailingTint = Ink.good)
                }
            }
            key(season) {
                val shown = remember { title.episodes.filter { it.season == season } }
                val list = rememberLazyListState(shown.indexOfFirst { it.id == playing?.id }.coerceAtLeast(0))
                LazyColumn(Modifier.padding(top = 6.dp).weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.id }) { ep ->
                        EpisodeRow(ep, title.wide, ep.id == playing?.id, seen[Ep(ep.season, ep.episode)] ?: 0.0) { onPlay(ep) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, fallback: String?, here: Boolean, seen: Double, onClick: () -> Unit) {
    val watched = isWatched(seen)
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().background(if (here) Color.White.copy(alpha = 0.12f) else Color.Transparent).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                Artwork(ep.thumbnail ?: fallback, ep.name, Modifier.fillMaxSize(), fill = false)
                if (seen > 0 && !watched) ProgressLine(seen.toFloat(), Modifier.align(Alignment.BottomStart))
                if (watched) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Ink.good), contentAlignment = Alignment.Center) {
                    Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 12.dp)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    "${ep.episode}. ${ep.name}",
                    style = Type.label.copy(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (watched) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lc(L.drawable.lucide_ic_check, tint = Ink.good, size = 13.dp)
                    Text(" Watched", style = Type.meta.copy(color = Ink.good, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                }
                if (here) Text("Now playing", Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp))
            }
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
    /** How many are in the watch party, 0 outside one. */
    party: Int,
    /** Chat lines not yet seen: a dot on the party button. */
    unread: Int,
    onParty: (() -> Unit)?,
    onBack: () -> Unit,
    onStats: () -> Unit,
    onLock: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title?.title ?: searchableName(file?.name.orEmpty()).ifBlank { file?.name.orEmpty() }, style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ep = episode?.let { "${episodeLabel(title, it.season, it.episode)}  ${it.name}" } ?: file?.let { fileEpisodeLabel(title, it.name) }
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
        if (onParty != null) Tap(onParty, Modifier.semantics { contentDescription = "Watch party" }, CircleShape) {
            Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Lc(L.drawable.lucide_ic_users, tint = if (party > 0) Ink.accent else Color.White, size = 22.dp)
                    if (unread > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-3).dp).size(8.dp).clip(CircleShape).background(Ink.accent))
                }
                if (party > 0) Text(" $party", style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
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
            // Unbounded: the bubble is taller than the 40dp bar, which squashed its last line.
            androidx.compose.animation.AnimatedVisibility(
                scrubbing,
                Modifier.align(Alignment.BottomStart).offset(x = (width * shown - 55.dp).coerceIn(0.dp, width - 110.dp), y = (-44).dp).width(110.dp)
                    .wrapContentHeight(Alignment.Bottom, unbounded = true),
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
 * The double-tap as YouTube shows it, and as the Expo build did: half the picture darkened
 * in a shape bulging in from the edge that was tapped, two overlapped chevrons, and the
 * running total ("40 seconds" for four taps). Full strength while the taps keep coming,
 * then it fades.
 */
@Composable
private fun SeekFlash(flash: Flash?) {
    var last by remember { mutableStateOf(flash) }
    val strength = remember { Animatable(0f) }
    LaunchedEffect(flash?.id) {
        val f = flash ?: return@LaunchedEffect
        last = f
        strength.snapTo(1f)
        delay(300)
        strength.animateTo(0f, tween(350))
    }
    val f = last ?: return
    if (strength.value <= 0f) return
    val forward = f.seconds >= 0
    val chevron = if (forward) L.drawable.lucide_ic_chevron_right else L.drawable.lucide_ic_chevron_left
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = strength.value }) {
        Column(
            Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.5f)
                // The outer edge flush with the screen, the inner one round.
                .clip(if (forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50) else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
                // Darkens rather than washes out: a white veil vanishes over a bright scene.
                .background(Color(0x73000000)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Overlapped, so the two read as one arrowhead.
            Row {
                Lc(chevron, tint = Color.White, size = 22.dp)
                Lc(chevron, Modifier.offset(x = (-10).dp), tint = Color.White, size = 22.dp)
            }
            Text("${abs(f.seconds)} seconds", Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** Paused for a while: what you are watching, over a dimmed frame — Netflix's pause screen, as on the TV. */
@Composable
private fun PausedCard(visible: Boolean, title: Title?, episode: Episode?, label: String, playhead: () -> Double, duration: Double) {
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
                episode?.let { Text("${episodeLabel(title, it.season, it.episode)}  “${it.name}”", Modifier.padding(top = 10.dp), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (title != null) Facts(title.copy(genres = title.genres.take(2)), Modifier.padding(top = 10.dp))
                (episode?.overview ?: title?.plot)?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.body.copy(fontSize = 14.sp, color = Ink.text.copy(alpha = 0.8f)), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                // Where the film is, and how much is left of it — the reason to glance at a paused screen.
                if (duration > 0) {
                    val at = playhead()
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
private fun Loading(title: Title?, episode: Episode?, file: TorrentFile?, starting: Boolean, progress: Progress?, via: String?, asking: Boolean, onBack: () -> Unit) {
    val percent = if (starting) 100 else progress?.buffer ?: 0
    val ring by animateFloatAsState(percent / 100f, Motion.spatial(), label = "ring")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        (episode?.thumbnail ?: title?.art)?.let { art ->
            Artwork(art, title?.title.orEmpty(), Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier), fallback = title?.art)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.7f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.45f))))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
            Row(Modifier.align(Alignment.CenterStart).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (title != null) TitleMark(title, Modifier.widthIn(max = 380.dp), maxHeight = 76.dp, style = Type.display)
                    else Text(searchableName(file?.name.orEmpty()).ifBlank { file?.name.orEmpty() }, style = Type.display, maxLines = 2)
                    (episode?.let { "${episodeLabel(title, it.season, it.episode)}  ${it.name}" } ?: file?.let { fileEpisodeLabel(title, it.name) })
                        ?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        val live = via != null || (progress?.peers ?: 0) > 0
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Ink.good else Ink.faint))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                // The player opens on the file's first blocks; what it waits on next is still arriving.
                                starting -> when {
                                    via != null -> "Starting playback  ·  from $via"
                                    progress != null && progress.peers > 0 -> "Starting playback  ·  ${speed(progress.speed)}  ·  ${progress.peers} peers"
                                    else -> "Starting playback"
                                }
                                asking -> "Checking TorBox"
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
        (episode?.thumbnail ?: title?.art)?.let { art ->
            Artwork(art, label, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(16.dp) else Modifier), fallback = title?.art)
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
                episode?.let { Text("${episodeLabel(title, it.season, it.episode)}  ${it.name}", Modifier.padding(top = 8.dp), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    subSize: String,
    onSubSize: (Int) -> Unit,
    onSubtitle: (Int?) -> Unit,
    onAudio: (Int) -> Unit,
    onFind: () -> Unit,
    canSync: Boolean,
    syncNote: Pair<String, Boolean>?,
    onSyncLine: () -> Unit,
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
                // The tracks first: under the timing tools they were off the bottom of a phone held sideways.
                item { TrackRow("Off", subtitle == null) { onSubtitle(null) } }
                itemsIndexed(tracks?.subtitles.orEmpty()) { _, t -> TrackRow(t.name, t.id == subtitle) { onSubtitle(t.id) } }
                item { TrackRow("Find subtitles online…", false, icon = L.drawable.lucide_ic_search, onClick = onFind) }
                // Then the tools, a row each, only while a subtitle is on.
                if (subtitle != null) {
                    item { Spacer(Modifier.height(10.dp)) }
                    // Timing: a tenth of a second a tap, the value resets it.
                    item {
                        Row(Modifier.padding(start = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Timing", Modifier.width(56.dp), style = Type.meta)
                            Tap({ onDelay(delay - 100) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_minus, Modifier.padding(8.dp), tint = Color.White, size = 18.dp) }
                            Tap({ onDelay(0) }, shape = RoundedCornerShape(6.dp)) {
                                Text("%+.1f s".format(delay / 1000.0), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = if (delay != 0L) Ink.accent else Color.White))
                            }
                            Tap({ onDelay(delay + 100) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_plus, Modifier.padding(8.dp), tint = Color.White, size = 18.dp) }
                        }
                    }
                    // One quiet line: what the last tap did, and the by-ear way.
                    item {
                        val good = Color(0xFF22C55E)
                        val warn = Color(0xFFF59E0B)
                        val (line, colour) = when {
                            !canSync -> "Built into the video: use − and +." to Color.White.copy(alpha = 0.5f)
                            syncNote != null -> syncNote.first to (if (syncNote.second) good else warn)
                            else -> null to Color.Unspecified
                        }
                        Row(Modifier.padding(start = 70.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            line?.let { Text(it, Modifier.weight(1f, fill = false), style = Type.meta.copy(color = colour), maxLines = 2) }
                            if (canSync) Tap(onSyncLine, shape = RoundedCornerShape(6.dp)) {
                                Text(if (line == null) "Or tap here when someone speaks" else "  ·  Tap when someone speaks", Modifier.padding(vertical = 4.dp), style = Type.meta.copy(color = Color.White.copy(alpha = 0.75f), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), maxLines = 1)
                            }
                        }
                    }
                    item {
                        Row(Modifier.padding(start = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Size", Modifier.width(56.dp), style = Type.meta)
                            Tap({ onSubSize(-1) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_a_arrow_down, Modifier.padding(8.dp), tint = Color.White, size = 18.dp) }
                            Text(subSize.replaceFirstChar(Char::uppercase), Modifier.widthIn(min = 64.dp).padding(horizontal = 10.dp, vertical = 6.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                            Tap({ onSubSize(1) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_a_arrow_up, Modifier.padding(8.dp), tint = Color.White, size = 18.dp) }
                        }
                    }
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
private fun StatsPanel(s: Stats?, progress: Progress?, file: TorrentFile?, duration: Double, ahead: Long) {
    fun mbps(kbps: Double) = "%.1f Mb/s".format(kbps / 1000)
    fun span(seconds: Double): String {
        val t = seconds.toLong()
        return when {
            t >= 3600 -> "${t / 3600}h ${t % 3600 / 60}m"
            t >= 60 -> "${t / 60}m ${t % 60}s"
            else -> "$t s"
        }
    }
    val rows = buildList {
        if (s == null) add("Player" to "Reading…")
        else {
            add("Player" to s.engine)
            add("Video" to listOfNotNull(s.videoCodec, if (s.width > 0) "${s.width}×${s.height}" else null, if (s.fps > 0) "%.3f fps".format(s.fps).replace(".000", "") else null).joinToString("  ·  ").ifEmpty { "—" })
            add("Audio" to listOfNotNull(s.audioCodec, if (s.channels > 0) "${s.channels} ch" else null, if (s.sampleRate > 0) "${s.sampleRate / 1000} kHz" else null, s.language).joinToString("  ·  ").ifEmpty { "—" })
            // "Arriving" is the swarm's rate: the player's own input is reads from the phone's
            // loopback server, which run far faster than the torrent and said nothing about it.
            // Media3 knows a bitrate only where the container states one, and MKV does not: then the file's average.
            val video = s.contentKbps.takeIf { it > 0 }?.let { "${mbps(it)} video" }
                ?: file?.size?.takeIf { it > 0 && duration > 0 }?.let { "${mbps(it * 8 / duration / 1000)} average" }
                ?: "—"
            add("Bitrate" to video + (progress?.let { "  ·  ${mbps(it.speed * 8 / 1000.0)} arriving" } ?: ""))
            add("Frames" to "${s.displayed} shown  ·  ${s.lostFrames} dropped")
            // What's downloaded on from the playhead, in running time at the file's average rate: the
            // player's own buffer is only its read-ahead, a few seconds to half a minute, however much is on disk.
            val downloaded = file?.size?.takeIf { ahead >= 0 && it > 0 && duration > 0 }?.let { "${span(ahead * duration / it)} downloaded" }
            add("Buffer" to listOfNotNull(downloaded, "${s.cacheMs / 1000} s in the player", if (s.lostBuffers > 0) "${s.lostBuffers} audio gaps" else null).joinToString("  ·  "))
        }
        progress?.let { add("Swarm" to "${speed(it.speed)}  ·  ${it.peers} peers  ·  ${it.seeds} seeds") }
        file?.let { add("File" to "${bytes(it.size)}  ·  ${it.name}") }
    }
    Column(Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(label, Modifier.width(70.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp))
                // The file's name is long and is the one worth reading whole: it scrolls rather than being cut.
                val scroll = if (label == "File") Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2000, velocity = 40.dp) else Modifier
                Text(value, scroll, style = Type.meta.copy(color = Color.White, fontSize = 12.sp), maxLines = 1, overflow = if (label == "File") TextOverflow.Clip else TextOverflow.Ellipsis)
            }
        }
    }
}
