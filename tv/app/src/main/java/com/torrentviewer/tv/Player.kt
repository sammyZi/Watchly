package com.torrentviewer.tv

import com.torrentviewer.core.*
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import com.composables.icons.lucide.R as L

/** How long the controls stay up after the last press. */
private const val HIDE_MS = 5000L
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

/** The Up Next card appears this many seconds before the end and counts down. */
private const val UP_NEXT_SECONDS = 10

/** Presses of ‹ › commit after this pause, the way a TV remote expects. */
private const val SCRUB_COMMIT_MS = 700L

/**
 * No frame this long means stalled. The stream blocks inside the socket read, below
 * anything libVLC reports — a clock that stops is the only signal there is.
 */
private const val STALL_MS = 1200L

/**
 * A seek that has not landed. libVLC goes on reporting the old time until it does, and
 * each report dragged the bar back to where the viewer had just left.
 */
private class Seeking(val target: Double, val from: Double, val since: Long, val connections: Long)

/** A seek with no new connection and the player still starved this long reopens the stream. */
private const val SEEK_CHECK_MS = 2500L

/** The bar stops holding a seek target this long after it, landed or not. */
private const val SEEK_HOLD_MS = 45_000L

/** Seeks further than this also aim the torrent at the target before libVLC asks for it. */
private const val AIM_SECONDS = 30.0

/** A stream that ends this far from its runtime gave up; reopen it, at most this many times. */
private const val EARLY_END_SECONDS = 60.0
private const val MAX_RECOVERIES = 3

/** Held ‹ › walks up these: ten seconds for a tap, two minutes once hunting for a scene. */
private fun stepFor(repeat: Int) = when {
    repeat < 3 -> 10
    repeat < 8 -> 30
    repeat < 16 -> 60
    else -> 120
}

/** libVLC's own buffer by bitrate; three seconds rides out a torrent's bursts. */
private fun cacheMsFor(bytesPerSecond: Double) = if (bytesPerSecond > 4_000_000) 6000 else 3000

private fun delta(seconds: Double): String {
    val n = abs(seconds).toLong()
    return "${if (seconds < 0) "−" else "+"}${n / 60}:%02d".format(n % 60)
}

private enum class Band { SLIDER, TRANSPORT }

private enum class Panel { TRACKS, SPEED, ASPECT, FIND, LANG }

@Composable
fun PlayerScreen(watch: Watch) {
    // Everything resets per file — a new episode is a new player.
    key(watch.playId) { Player(watch) }
}

@Composable
private fun Player(watch: Watch) {
    val engine = watch.engine
    val scope = rememberCoroutineScope()
    val ready by engine.ready.collectAsState()
    // A State, not a value: read where it is shown, so a tick does not recompose the player.
    val progressState = engine.progress.collectAsState()
    val title = watch.title
    val file = watch.playingFile
    val episode = remember(file) { watch.episodeOf(file) }
    val next = watch.next
    val nextEpisode = remember(next) { watch.episodeOf(next) }
    val startAt = remember { watch.startAt() }
    val bitrate = remember { file?.size?.let { size -> watch.item?.durations?.get(watch.playingIndex)?.takeIf { it > 0 }?.let { size / it } } ?: 0.0 }

    val view = remember { mutableStateOf<Video?>(null) }
    /** Media3 unless the setting asks for VLC, or Media3 has already given up on this file. */
    var vlc by remember { mutableStateOf(SettingsStore.value.value.vlcPlayer) }
    var paused by remember { mutableStateOf(false) }
    var at by remember { mutableDoubleStateOf(startAt) }
    var duration by remember { mutableDoubleStateOf(0.0) }
    var loaded by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(false) }
    var band by remember { mutableStateOf(Band.TRANSPORT) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var info by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var tracks by remember { mutableStateOf<Tracks?>(null) }
    var subtitle by remember { mutableStateOf<Int?>(null) }
    var audio by remember { mutableStateOf<Int?>(null) }
    var rate by remember { mutableFloatStateOf(1f) }
    var scale by remember { mutableIntStateOf(0) }
    var noNext by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Double?>(null) }
    var hudAt by remember { mutableLongStateOf(0L) }
    var hud by remember { mutableStateOf<String?>(null) }
    var external by remember { mutableStateOf(watch.savedSubs()) }
    var found by remember { mutableStateOf<List<Sub>?>(null) }
    var finding by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    val root = remember { FocusRequester() }
    /** Whether the bar holds focus — plain, read only by the focus retry. */
    val controlFocus = remember { booleanArrayOf(false) }
    val waiting = ready == null || !loaded
    val slider = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }

    // Not state: the hide timer reads it when it wakes. As state, every key press
    // recomposed the whole player, which is what made moving between buttons judder.
    val lastInput = remember { longArrayOf(0L) }
    fun touch() {
        lastInput[0] = System.currentTimeMillis()
    }

    var flash by remember { mutableStateOf<Flash?>(null) }
    /** Taps in a row in one direction add up to one running total. */
    fun flashSeek(seconds: Int, total: Boolean = false) {
        val now = System.currentTimeMillis()
        val prev = flash
        val sum = when {
            total -> seconds
            prev != null && now - prev.id < 1000 && (prev.seconds < 0) == (seconds < 0) -> prev.seconds + seconds
            else -> seconds
        }
        if (sum != 0) flash = Flash(sum, now)
    }

    fun say(text: String) {
        hud = text
        hudAt = System.currentTimeMillis()
    }

    fun show(into: Band = Band.TRANSPORT) {
        if (!controls) band = into
        controls = true
        touch()
    }

    fun hide() {
        controls = false
        touch()
    }

    fun togglePause() {
        val v = view.value ?: return
        v.setPaused(!v.isPaused)
        paused = v.isPaused
    }

    fun goNext() {
        if (next == null || advanced) return
        advanced = true
        watch.playSibling(next)
    }

    val seeking = remember { mutableStateOf<Seeking?>(null) }
    val seekCheck = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun seekTo(seconds: Double) {
        val target = seconds.coerceIn(0.0, if (duration > 0) duration - 1 else seconds)
        val v = view.value ?: return
        val from = at
        at = target
        val ms = (target * 1000).toLong()
        if (duration > 0 && abs(target - from) > AIM_SECONDS) {
            val ratio = target / duration
            scope.launch(Dispatchers.IO) { engine.setPosition(ratio) }
        }
        // Starved, libVLC would act on the seek only once the piece it is blocked on
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

    // Walk the playhead without moving it: seeking per press would re-aim the torrent a
    // dozen times while ‹ is held and throw away the readahead each time.
    val commit = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun nudge(seconds: Int) {
        val from = scrub ?: at
        scrub = (from + seconds).coerceIn(0.0, if (duration > 0) duration else from + seconds)
        // With the bar down this is a skip, and it should feel like one.
        if (!controls) flashSeek(((scrub ?: at) - at).roundToInt(), total = true)
        touch()
        commit.value?.cancel()
        commit.value = scope.launch {
            delay(SCRUB_COMMIT_MS)
            scrub?.let(::seekTo)
            scrub = null
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

    // Home leaves the activity stopped, not destroyed: without this the film plays on behind the launcher.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) view.value?.let { v ->
                v.setPaused(true)
                paused = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The bar comes up with the first frame, focus on Play, then steps aside.
    LaunchedEffect(loaded) { if (loaded) show() }
    LaunchedEffect(controls, panel, paused) {
        if (!controls || panel != null) return@LaunchedEffect
        // Paused, the bar steps aside after a while for the "You're watching" card.
        val after = if (paused) 8000L else HIDE_MS
        while (true) {
            val wait = after - (System.currentTimeMillis() - lastInput[0])
            if (wait <= 0) break
            delay(wait)
        }
        controls = false
    }
    LaunchedEffect(hud, hudAt) {
        if (hud != null) {
            delay(2200)
            hud = null
        }
    }
    // Focus follows the bar: up lands on the scrubber, down on the buttons, hidden on the
    // picture. Retried, because the buttons are still being composed on the first frame.
    LaunchedEffect(controls, band, panel, scrub, waiting) {
        if (panel != null || waiting) return@LaunchedEffect
        val target = when {
            !controls -> root
            band == Band.SLIDER -> slider
            else -> playButton
        }
        repeat(10) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess && (target == root || controlFocus[0])) return@LaunchedEffect
        }
    }
    // Restarted by every frame; only an absence of them fires it.
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
    // Saved every few seconds, so a power cut still remembers the spot.
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

    // Derived, so the 4-a-second clock only recomposes what shows it.
    val offerNext by remember { derivedStateOf { next != null && !noNext && loaded && duration > 0 && duration - at <= UP_NEXT_SECONDS } }
    LaunchedEffect(offerNext) {
        if (!offerNext) return@LaunchedEffect
        snapshotFlow { duration - at }.first { it <= 0.5 }
        goNext()
    }

    // This episode's intro: IntroDB's window once it answers; until then, one marked before. A film has none.
    val introKey = remember(title, file) {
        if (title?.isSeries == true || parseEpisode(file?.name.orEmpty()) != null) title?.imdb ?: watch.item?.imdb ?: title?.title else null
    }
    var intro by remember { mutableStateOf(introKey?.let(Intros::get)) }
    val imdb = title?.imdb ?: watch.item?.imdb
    val introEp = remember(episode, file) { episode?.let { Ep(it.season, it.episode) } ?: parseEpisode(file?.name.orEmpty()) }
    LaunchedEffect(imdb, introEp) {
        if (introKey == null || imdb == null || introEp == null) return@LaunchedEffect
        IntroDb.intro(imdb, introEp.season, introEp.episode)?.let { intro = it }
    }
    val skipIntro by remember { derivedStateOf { intro?.covers(at) == true && loaded } }
    fun skipPastIntro() = intro?.let { seekTo(it.end) }
    // Once per intro: scrubbing back into it to watch it is not undone.
    var autoSkipped by remember(intro) { mutableStateOf(false) }
    LaunchedEffect(skipIntro) {
        if (!skipIntro || autoSkipped || !SettingsStore.value.value.autoSkipIntro) return@LaunchedEffect
        autoSkipped = true
        skipPastIntro()
        say("Skipped intro")
    }

    /* ----------------------------------------------------------------- back */

    // BACK closes a panel, then the controls, then the info card — and only then leaves.
    BackHandler {
        when {
            panel != null -> panel = null
            scrub != null -> {
                commit.value?.cancel()
                scrub = null
            }
            controls -> hide()
            info -> info = false
            else -> watch.exit()
        }
    }

    /* ----------------------------------------------------------------- keys */

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val repeat = e.nativeKeyEvent.repeatCount
                if (panel != null || offerNext) return@onPreviewKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        togglePause()
                        show()
                        return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { nudge(stepFor(repeat)); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { nudge(-stepFor(repeat)); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { goNext(); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { watch.prev?.let(watch::playSibling); return@onPreviewKeyEvent true }
                }
                val arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                val sign = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (!controls) {
                    when (code) {
                        // OK is play/pause with the bar down — or Skip intro while that is up; up/down only peek, as Android TV asks.
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (skipIntro) skipPastIntro()
                            else {
                                togglePause()
                                show()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> { show(Band.SLIDER); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { show(Band.TRANSPORT); true }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(sign * stepFor(repeat)); true }
                        else -> false
                    }
                } else {
                    touch()
                    when {
                        arrow && (band == Band.SLIDER || scrub != null) -> { nudge(sign * stepFor(repeat)); true }
                        band == Band.SLIDER && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) -> { togglePause(); true }
                        band == Band.SLIDER && code == KeyEvent.KEYCODE_DPAD_UP -> { hide(); true }
                        else -> false
                    }
                }
            }
            .focusRequester(root)
            .focusable(),
    ) {
        key(vlc) { AndroidView(
            factory = { context ->
                (if (vlc) VlcView(context) else ExoView(context)).also { v ->
                    // A fallback keeps the viewer's speed and picture.
                    v.setRate(rate)
                    // The TV never applied the Settings style: Media3 drew its own default.
                    v.subtitleStyle = SettingsStore.value.value.subtitles
                    v.setScale(SCALES[scale].first)
                    if (!vlc) v.onUnsupported = { message ->
                        Log.w("Player", "Media3 gave up ($message), falling back to VLC")
                        // Track ids are the backend's own.
                        tracks = null
                        subtitle = null
                        audio = null
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
                            if (startAt > 0 && duration > 0) {
                                val ratio = minOf(0.99, startAt / duration)
                                scope.launch(Dispatchers.IO) { engine.setPosition(ratio) }
                            }
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
                        // The panel says what is really on screen, not what was last picked: a
                        // subtitle just downloaded is turned on by the view itself.
                        subtitle = t.spuId.takeIf { it >= 0 }
                    }
                    v.onEnded = {
                        // A stream that gave up mid-film looks like the end to libVLC: reopen
                        // where it stopped rather than leave the film or skip to the next one.
                        if (duration > 0 && at < duration - EARLY_END_SECONDS && recoveries < MAX_RECOVERIES) {
                            recoveries++
                            v.reload((at * 1000).toLong())
                        } else if (next != null && !noNext) goNext() else watch.exit()
                    }
                    v.onFailed = { message ->
                        watch.notes.tryEmit(message)
                        watch.exit()
                    }
                    view.value = v
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) }

        // Debounced: libVLC flickers "buffering" on a torrent stream, and every flicker
        // started the spinner's fade — UI frames composited over the 4K video, which is
        // what made the TV drop video frames. Only a wait of a full second shows it.
        val wanting = !waiting && (buffering || stalled) && !paused
        var spinning by remember { mutableStateOf(false) }
        LaunchedEffect(wanting) {
            if (!wanting) spinning = false else { delay(1000); spinning = true }
        }

        // Mid-film stall: just the ring and the speed.
        AnimatedVisibility(spinning && scrub == null, Modifier.align(Alignment.Center), enter = fadeIn(tween(300, 400)), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 64.dp)
                progressState.value?.let {
                    Text("${speed(it.speed)}  ·  ${it.peers} peers", Modifier.padding(top = 14.dp), style = Type.meta.copy(color = Color.White))
                }
            }
        }

        // The clock as a lambda: read here, every libVLC tick recomposed the whole player.
        PausedCard(paused && !controls && panel == null && !waiting, title, episode, watch, { at }, duration)

        // Controls: a scrim top and bottom, title up top, the bar and buttons below.
        val barUp = (controls || scrub != null) && !offerNext && !waiting
        AnimatedVisibility(barUp, enter = fadeIn(tween(220)), exit = fadeOut(tween(320))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))))
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.62f), 1f to Color.Black.copy(alpha = 0.92f))),
                )
                AnimatedVisibility(controls, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut() + slideOutVertically { -it / 3 }) {
                    TopBar(title, episode, file, progressState.value, duration, { at }, rate)
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Safe.x).padding(bottom = Safe.y)) {
                    SeekBar(
                        at = { scrub ?: at },
                        playing = { at },
                        duration = duration,
                        scrubbing = scrub != null,
                        modifier = Modifier
                            .focusRequester(slider)
                            .onFocusChanged { if (it.isFocused) band = Band.SLIDER }
                            .focusable(),
                        focused = band == Band.SLIDER && controls,
                    )
                    AnimatedVisibility(controls && scrub == null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                        Row(
                            // Room above the buttons for the names that rise over them.
                            Modifier.fillMaxWidth().padding(top = 40.dp).focusRestorer(playButton).focusGroup(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val toTransport = { band = Band.TRANSPORT }
                            Control(if (paused) L.drawable.lucide_ic_play else L.drawable.lucide_ic_pause, if (paused) "Play" else "Pause", requester = playButton, big = true, onFocus = toTransport) { togglePause() }
                            Control(L.drawable.lucide_ic_rotate_ccw, "Back $SKIP s", badge = "$SKIP", onFocus = toTransport) {
                                seekTo(at - SKIP)
                                flashSeek(-SKIP)
                            }
                            Control(L.drawable.lucide_ic_rotate_cw, "Forward $SKIP s", badge = "$SKIP", onFocus = toTransport) {
                                seekTo(at + SKIP)
                                flashSeek(SKIP)
                            }
                            watch.prev?.let { prev -> Control(L.drawable.lucide_ic_skip_back, "Previous episode", onFocus = toTransport) { watch.playSibling(prev) } }
                            next?.let { Control(L.drawable.lucide_ic_skip_forward, "Next episode", onFocus = toTransport) { goNext() } }
                            Spacer(Modifier.weight(1f))
                            Control(L.drawable.lucide_ic_captions, "Audio & subtitles", on = subtitle != null, onFocus = toTransport) { panel = Panel.TRACKS }
                            Control(L.drawable.lucide_ic_gauge, "Speed ${rateLabel(rate)}", on = rate != 1f, onFocus = toTransport) { panel = Panel.SPEED }
                            Control(L.drawable.lucide_ic_ratio, SCALES[scale].second, on = scale != 0, onFocus = toTransport) { panel = Panel.ASPECT }
                            Control(L.drawable.lucide_ic_info, if (info) "Hide stats" else "Stats for nerds", on = info, onFocus = toTransport) { info = !info }
                        }
                    }
                }
            }
        }

        if (!waiting) SeekFlash(flash)

        // Skip intro, bottom-right, drawn as the focused button: with the bar down, OK presses it.
        AnimatedVisibility(
            skipIntro && !barUp && !offerNext && panel == null,
            Modifier.align(Alignment.BottomEnd).padding(end = Safe.x, bottom = Safe.y),
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut(),
        ) {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Lc(L.drawable.lucide_ic_chevrons_right, tint = Color.Black, size = 22.dp)
                Text("  Skip intro", style = Type.label.copy(color = Color.Black, fontWeight = FontWeight.Bold))
            }
        }

        // A short word for anything that happened without the bar: "Added EN".
        AnimatedVisibility(hud != null && !waiting, Modifier.align(Alignment.TopCenter).padding(top = Safe.y + 8.dp), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut()) {
            Text(
                hud.orEmpty(),
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 20.dp, vertical = 10.dp),
                style = Type.label.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        AnimatedVisibility(info && !waiting, Modifier.align(Alignment.TopStart).padding(start = Safe.x, top = if (controls) 120.dp else Safe.y), enter = fadeIn(), exit = fadeOut()) {
            StatsPanel(stats, progressState.value, file, duration)
        }

        if (offerNext && next != null) {
            UpNext(
                name = nextEpisode?.let { "E${it.episode} · ${it.name}" } ?: searchableName(next.name).ifBlank { next.name },
                still = nextEpisode?.thumbnail ?: title?.backdrop,
                seconds = { (duration - at).toInt().coerceAtLeast(0) },
                onPlay = ::goNext,
                onCancel = { noNext = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Safe.x, bottom = Safe.y),
            )
        }

        AnimatedVisibility(waiting, enter = fadeIn(), exit = fadeOut(tween(500))) {
            Loading(title, episode, file, ready != null, progressState.value)
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
                    val series = ep != null || title?.isSeries == true
                    findSubtitles(
                        query = title?.title ?: searchableName(file?.name.orEmpty()),
                        imdb = title?.imdb ?: watch.item?.imdb,
                        season = ep?.season,
                        episode = ep?.episode,
                        type = if (series) "tv" else "movie",
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
                audio = audio ?: tracks?.defaultAudio,
                subSize = SettingsStore.value.collectAsState().value.subtitles.size,
                onSubSize = { step -> view.value?.subtitleStyle = SettingsStore.resizeSubtitles(step) },
                onSubtitle = {
                    subtitle = it
                    view.value?.setSubtitleTrack(it)
                },
                onAudio = {
                    audio = it
                    view.value?.setAudioTrack(it)
                },
                onFind = ::findSubs,
                onDismiss = { panel = null },
            )
            Panel.SPEED -> Sheet(
                title = "Playback speed",
                options = RATES.map { Option(it, rateLabel(it), if (it == 1f) "Normal" else null) },
                selected = rate,
                onPick = {
                    rate = it
                    view.value?.setRate(it)
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
                    panel = null
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
                            say("Added ${sub.language}")
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
            null -> Unit
        }
    }

    root.claim()
}

private fun rateLabel(r: Float) = "${r.toString().removeSuffix(".0")}×"

/* ---------------------------------------------------------------- pieces */

@Composable
private fun TopBar(title: Title?, episode: Episode?, file: TorrentFile?, progress: Progress?, duration: Double, at: () -> Double, rate: Float) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Safe.x, vertical = Safe.y), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            val ep = episode?.let { "${episodeLabel(title, it.season, it.episode)}  ${it.name}" }
                ?: file?.let { fileEpisodeLabel(title, it.name) }
            Text(
                title?.title ?: searchableName(file?.name.orEmpty()).ifBlank { file?.name.orEmpty() },
                style = Type.display.copy(fontSize = 26.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ep != null) Text(ep, Modifier.padding(top = 2.dp), style = Type.title.copy(color = Ink.muted, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(15_000)
                    now = System.currentTimeMillis()
                }
            }
            val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
            Text(fmt.format(Date(now)), style = Type.title.copy(fontWeight = FontWeight.Bold))
            if (duration > 0) {
                val ends = now + ((duration - at()) / rate * 1000).toLong()
                Text("Ends at ${fmt.format(Date(ends))}", style = Type.meta)
            }
            progress?.let {
                Row(Modifier.padding(top = 8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lc(L.drawable.lucide_ic_download, tint = Ink.muted, size = 13.dp)
                    Text(" ${speed(it.speed)}", style = Type.meta.copy(color = Color.White))
                    Spacer(Modifier.width(10.dp))
                    Lc(L.drawable.lucide_ic_users, tint = Ink.muted, size = 13.dp)
                    Text(" ${it.peers}", style = Type.meta.copy(color = Color.White))
                }
            }
        }
    }
}

/**
 * Netflix's bar: the played part red, what is on disk lighter, a knob that swells
 * when the remote is on it, the time so far on the left and left on the right, and while scrubbing a bubble
 * with where the press has got to and how far that is from now.
 */
@Composable
private fun SeekBar(at: () -> Double, playing: () -> Double, duration: Double, scrubbing: Boolean, focused: Boolean, modifier: Modifier) {
    val fraction = if (duration > 0) (at() / duration).toFloat().coerceIn(0f, 1f) else 0f
    // Glides to a seek rather than jumping; follows a held key closely while scrubbing.
    val shown by animateFloatAsState(fraction, if (scrubbing) tween(120, easing = FastOutSlowInEasing) else tween(420, easing = FastOutSlowInEasing), label = "head")
    val lift by animateFloatAsState(if (focused || scrubbing) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "lift")
    val red = Ink.accent
    // Fixed height: the bubble floats above, so scrubbing never moves the buttons below.
    Row(modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            clock(at()),
            Modifier.padding(end = 18.dp).widthIn(min = 64.dp),
            style = Type.label.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.End,
        )
        BoxWithConstraints(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.CenterStart) {
            val width = maxWidth
            Canvas(Modifier.fillMaxSize()) {
                val thick = (4 + 3 * lift).dp.toPx()
                val top = (size.height - thick) / 2
                val r = CornerRadius(thick / 2)
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, top), Size(size.width, thick), r)
                drawRoundRect(red, Offset(0f, top), Size(size.width * shown, thick), r)
                val knob = 10.dp.toPx() * lift
                if (knob > 0.5f) {
                    // A soft halo under the knob while it is held.
                    drawCircle(red.copy(alpha = 0.28f * lift), knob * 1.9f, Offset(size.width * shown, size.height / 2))
                    drawCircle(red, knob, Offset(size.width * shown, size.height / 2))
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                scrubbing,
                Modifier.align(Alignment.BottomStart).offset(x = (width * shown - 60.dp).coerceIn(0.dp, width - 120.dp), y = (-34).dp).width(120.dp),
                enter = fadeIn(tween(140)) + scaleIn(tween(180, easing = FastOutSlowInEasing), 0.8f, TransformOrigin(0.5f, 1f)),
                exit = fadeOut(tween(220)) + scaleOut(tween(220), 0.9f, TransformOrigin(0.5f, 1f)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(clock(at()), Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 4.dp), style = Type.title.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                    Text(delta(at() - playing()), Modifier.padding(top = 3.dp), style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                }
            }
        }
        Text(
            if (duration > 0) clock(duration - at()) else "--:--",
            Modifier.padding(start = 18.dp).widthIn(min = 64.dp),
            style = Type.label.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * A round control; its name rises above it while the remote is on it.
 *
 * Nothing here changes size or recomposes while it animates: the label floats outside
 * the button's fixed box, and the white fill, the icon colour swap and the scale are all
 * drawn from one 0→1 value in the graphics layer. Laid out inside the button, the label
 * re-measured the whole row on every focus move, and the icons jumped.
 */
@Composable
private fun Control(
    @DrawableRes icon: Int,
    label: String,
    requester: FocusRequester? = null,
    big: Boolean = false,
    on: Boolean = false,
    badge: String? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (big) 60.dp else 50.dp
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(180, easing = FastOutSlowInEasing), label = "focus")
    val iconSize = if (big) 30.dp else 24.dp
    Box(Modifier.padding(horizontal = 6.dp).size(size)) {
        Text(
            label,
            Modifier
                .align(Alignment.TopCenter)
                .wrapContentSize(unbounded = true)
                .graphicsLayer {
                    alpha = t
                    translationY = -(size.toPx() * 0.5f + 26.dp.toPx()) + (1 - t) * 8.dp.toPx()
                }
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f + 0.12f * t
                    scaleX = s
                    scaleY = s
                }
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged {
                    if (it.isFocused != focused) {
                        focused = it.isFocused
                        if (it.isFocused) onFocus()
                    }
                }
                .drawBehind { drawCircle(Color.White.copy(alpha = t)) }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Both colours drawn, crossfaded by alpha: no recomposition per animation frame.
            Box(Modifier.graphicsLayer { alpha = 1 - t }, contentAlignment = Alignment.Center) {
                Lc(icon, tint = Color.White, size = iconSize)
                if (badge != null) Text(badge, Modifier.offset(y = 1.dp), style = Type.overline.copy(color = Color.White, fontSize = 8.sp, letterSpacing = 0.sp))
            }
            Box(Modifier.graphicsLayer { alpha = t }, contentAlignment = Alignment.Center) {
                Lc(icon, tint = Color.Black, size = iconSize)
                if (badge != null) Text(badge, Modifier.offset(y = 1.dp), style = Type.overline.copy(color = Color.Black, fontSize = 8.sp, letterSpacing = 0.sp))
            }
            if (on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp).graphicsLayer { alpha = 1 - t }.clip(CircleShape).background(Ink.accent))
        }
    }
}

data class Flash(val seconds: Int, val id: Long)

/**
 * A seek, felt: a badge on the side it went that spins its arrow, a ring pulsing out
 * behind it, and the running total — taps in a row add up, as on Netflix and YouTube.
 */
@Composable
private fun SeekFlash(flash: Flash?, modifier: Modifier = Modifier) {
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
    Box(modifier.fillMaxSize().padding(horizontal = 150.dp), contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 700f), 0.6f),
            exit = fadeOut(tween(320)) + scaleOut(tween(320), 1.12f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    // The ring that rolls outward on every press.
                    Box(
                        Modifier.size(104.dp).graphicsLayer {
                            val s = 1f + 0.45f * pulse.value
                            scaleX = s
                            scaleY = s
                            alpha = 0.55f * (1f - pulse.value)
                        }.border(2.dp, Color.White, CircleShape),
                    )
                    Box(Modifier.size(104.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Lc(
                            if (forward) L.drawable.lucide_ic_rotate_cw else L.drawable.lucide_ic_rotate_ccw,
                            Modifier.graphicsLayer { rotationZ = spin.value },
                            tint = Color.White,
                            size = 58.dp,
                        )
                        Text("${abs(f.seconds)}", style = Type.label.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp))
                    }
                }
                Text(
                    "${if (forward) "+" else "−"}${abs(f.seconds)} seconds",
                    Modifier.padding(top = 6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 5.dp),
                    style = Type.label.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** Paused for a while: what you are watching, over a dimmed frame — Netflix's pause screen. */
@Composable
private fun PausedCard(visible: Boolean, title: Title?, episode: Episode?, watch: Watch, playhead: () -> Double, duration: Double) {
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
            Column(Modifier.align(Alignment.CenterStart).padding(start = Safe.x + 24.dp).widthIn(max = 560.dp)) {
                Text("You're watching", style = Type.title.copy(color = Ink.muted, fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(10.dp))
                if (title != null) TitleMark(title, maxHeight = 90.dp) else Text(watch.item?.label.orEmpty(), style = Type.hero)
                episode?.let {
                    Text("${episodeLabel(title, it.season, it.episode)}  “${it.name}”", Modifier.padding(top = 14.dp), style = Type.title)
                }
                val plot = episode?.overview ?: title?.plot
                if (title != null) Facts(title, Modifier.padding(top = 12.dp))
                plot?.let { Text(it, Modifier.padding(top = 12.dp), style = Type.body.copy(color = Ink.text.copy(alpha = 0.8f)), maxLines = 4, overflow = TextOverflow.Ellipsis) }
                // Where the film is, and how much is left of it — the reason to glance at a paused screen.
                if (duration > 0) {
                    val at = playhead()
                    val left = (duration - at).coerceAtLeast(0.0)
                    Column(Modifier.padding(top = 28.dp).width(460.dp)) {
                        ProgressLine((at / duration).toFloat(), height = 4.dp)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${clock(at)} of ${clock(duration)}", Modifier.weight(1f), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                            Text(left(left), style = Type.label.copy(color = Ink.muted))
                        }
                        val ends = remember(at, duration) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (left * 1000).toLong())) }
                        Text("Ends at $ends if you resume now", Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint))
                    }
                }
                Text("Paused", Modifier.padding(top = 22.dp), style = Type.overline.copy(color = Ink.muted, fontSize = 13.sp))
            }
        }
    }
}

/** Before the first frame: the title's own backdrop, its logo, and a ring that fills. */
@Composable
private fun Loading(title: Title?, episode: Episode?, file: TorrentFile?, starting: Boolean, progress: Progress?) {
    val percent = if (starting) 100 else progress?.buffer ?: 0
    val ring by animateFloatAsState(percent / 100f, Motion.spatial(), label = "ring")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        (episode?.thumbnail ?: title?.art)?.let { art ->
            Artwork(art, title?.title.orEmpty(), Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier), fallback = title?.art)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.7f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.45f))))
        Row(Modifier.align(Alignment.CenterStart).padding(start = Safe.x + 24.dp, end = Safe.x), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (title != null) TitleMark(title, Modifier.widthIn(max = 520.dp), maxHeight = 100.dp)
                else Text(searchableName(file?.name.orEmpty()).ifBlank { file?.name.orEmpty() }, style = Type.hero, maxLines = 2)
                (episode?.let { "${episodeLabel(title, it.season, it.episode)}  ${it.name}" } ?: file?.let { fileEpisodeLabel(title, it.name) })
                    ?.let { Text(it, Modifier.padding(top = 12.dp), style = Type.title) }
                Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    val live = (progress?.peers ?: 0) > 0
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (live) Ink.good else Ink.faint))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            starting -> "Starting playback"
                            progress == null -> "Finding peers"
                            progress.peers == 0 -> "Finding peers  ·  ${progress.dhtNodes} DHT nodes"
                            else -> "Buffering  ·  ${speed(progress.speed)}  ·  ${progress.peers} peers"
                        },
                        style = Type.label.copy(color = Ink.muted),
                    )
                }
            }
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                val red = Ink.accent
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 6.dp.toPx()
                    val inset = Offset(stroke / 2, stroke / 2)
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, inset, arc, style = Stroke(stroke))
                    drawArc(red, -90f, 360f * ring, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("$percent%", style = Type.display.copy(fontSize = 24.sp))
            }
        }
    }
}

/** Ten seconds from the end: the next episode's still, its name, and a button that fills. */
@Composable
private fun UpNext(name: String, still: String?, seconds: () -> Int, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val play = remember { FocusRequester() }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInHorizontally { it / 3 }) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xF2141414)).padding(14.dp).focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(still, name, Modifier.width(200.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.padding(start = 18.dp).width(250.dp)) {
                Text("NEXT EPISODE", style = Type.overline.copy(color = Ink.muted))
                Text(name, Modifier.padding(top = 4.dp, bottom = 14.dp), style = Type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row {
                    val left = seconds()
                    val fill by animateFloatAsState(1f - left / UP_NEXT_SECONDS.toFloat(), tween(900), label = "fill")
                    Focusable(onPlay, shape = RoundedCornerShape(8.dp), scale = 1.05f, ring = false, requester = play) { focused ->
                        Box(Modifier.background(if (focused) Color.White else Color.White.copy(alpha = 0.85f)).height(42.dp).width(150.dp)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill.coerceIn(0f, 1f)).background(Color.Black.copy(alpha = 0.14f)))
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                Lc(L.drawable.lucide_ic_play, tint = Color.Black, size = 16.dp)
                                Text("  Play  ${left}s", style = Type.label.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    TvButton("Cancel", onCancel)
                }
            }
        }
    }
    play.claim()
}

/** Audio and subtitles side by side, as Netflix lays them out, with the online search at the end. */
@Composable
private fun TracksPanel(
    tracks: Tracks?,
    subtitle: Int?,
    audio: Int?,
    subSize: String,
    onSubSize: (Int) -> Unit,
    onSubtitle: (Int?) -> Unit,
    onAudio: (Int) -> Unit,
    onFind: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.4f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.95f)))) {
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.72f).padding(horizontal = Safe.x + 24.dp).padding(bottom = Safe.y)) {
            TrackColumn("Audio", Modifier.weight(1f)) {
                val list = tracks?.audio.orEmpty()
                if (list.isEmpty()) item { Note("No audio tracks reported yet.") }
                itemsIndexed(list) { i, t ->
                    TrackRow(t.name, t.id == audio, if (i == 0) first else null) { onAudio(t.id) }
                }
            }
            Spacer(Modifier.width(48.dp))
            TrackColumn("Subtitles", Modifier.weight(1f)) {
                item { TrackRow("Off", subtitle == null, if (tracks?.audio.isNullOrEmpty()) first else null) { onSubtitle(null) } }
                itemsIndexed(tracks?.subtitles.orEmpty()) { _, t -> TrackRow(t.name, t.id == subtitle, null) { onSubtitle(t.id) } }
                item {
                    Spacer(Modifier.height(10.dp))
                    TrackRow("Find subtitles online…", false, null, icon = L.drawable.lucide_ic_search, onClick = onFind)
                }
                item {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Size", Modifier.width(64.dp), style = Type.title.copy(color = Ink.muted, fontWeight = FontWeight.Medium))
                        SizeStep(L.drawable.lucide_ic_a_arrow_down) { onSubSize(-1) }
                        Text(subSize.replaceFirstChar(Char::uppercase), Modifier.width(96.dp), style = Type.title.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
                        SizeStep(L.drawable.lucide_ic_a_arrow_up) { onSubSize(1) }
                    }
                }
            }
        }
    }
    first.claim()
}

@Composable
private fun SizeStep(@DrawableRes icon: Int, onClick: () -> Unit) {
    Focusable(onClick, shape = CircleShape, scale = 1.1f, ring = false) { focused ->
        Box(Modifier.size(44.dp).background(if (focused) Color.White else Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Lc(icon, tint = if (focused) Color.Black else Color.White, size = 22.dp)
        }
    }
}

@Composable
private fun TrackColumn(heading: String, modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(modifier) {
        Text(heading, Modifier.padding(start = 14.dp, bottom = 14.dp), style = Type.display.copy(fontSize = 22.sp))
        LazyColumn(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun TrackRow(label: String, on: Boolean, requester: FocusRequester?, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(6.dp), 1.02f, ring = false, requester = requester) { focused ->
        Row(Modifier.background(if (focused) Color.White else Color.Transparent).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp)) {
                when {
                    icon != null -> Lc(icon, tint = if (focused) Color.Black else Ink.muted, size = 18.dp)
                    on -> Lc(L.drawable.lucide_ic_check, tint = if (focused) Color.Black else Color.White, size = 20.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = Type.title.copy(color = if (focused) Color.Black else if (on) Color.White else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What is playing and how it is going — dropped frames climbing means the player is discarding them. */
@Composable
private fun StatsPanel(s: Stats?, progress: Progress?, file: TorrentFile?, duration: Double) {
    fun mbps(kbps: Double) = "%.1f Mb/s".format(kbps / 1000)
    val rows = buildList {
        if (s == null) add("Player" to "Reading…")
        else {
            add("Player" to s.engine)
            add("Video" to listOfNotNull(s.videoCodec, if (s.width > 0) "${s.width}×${s.height}" else null, if (s.fps > 0) "%.3f fps".format(s.fps).replace(".000", "") else null).joinToString("  ·  ").ifEmpty { "—" })
            add("Audio" to listOfNotNull(s.audioCodec, if (s.channels > 0) "${s.channels} ch" else null, if (s.sampleRate > 0) "${s.sampleRate / 1000} kHz" else null, s.language).joinToString("  ·  ").ifEmpty { "—" })
            // "Arriving" is the swarm's rate: the player's own input is reads from the loopback
            // server, which run far faster than the torrent and said nothing about it.
            // Media3 knows a bitrate only where the container states one, and MKV does not: then the file's average.
            val video = s.contentKbps.takeIf { it > 0 }?.let { "${mbps(it)} video" }
                ?: file?.size?.takeIf { it > 0 && duration > 0 }?.let { "${mbps(it * 8 / duration / 1000)} average" }
                ?: "—"
            add("Bitrate" to video + (progress?.let { "  ·  ${mbps(it.speed * 8 / 1000.0)} arriving" } ?: ""))
            add("Frames" to "${s.displayed} shown  ·  ${s.lostFrames} dropped")
            add("Buffer" to "${s.cacheMs / 1000} s" + if (s.lostBuffers > 0) "  ·  ${s.lostBuffers} audio gaps" else "")
        }
        progress?.let { add("Swarm" to "${speed(it.speed)}  ·  ${it.peers} peers  ·  ${it.seeds} seeds") }
        file?.let { add("File" to "${bytes(it.size)}  ·  ${it.name}") }
    }
    Column(Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(label, Modifier.width(80.dp), style = Type.meta.copy(color = Ink.faint))
                Text(value, style = Type.meta.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
