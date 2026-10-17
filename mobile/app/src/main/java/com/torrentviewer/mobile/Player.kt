package com.torrentviewer.mobile

import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Motion
import com.torrentviewer.core.Progress
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.TorrentFile
import com.torrentviewer.core.Type
import com.torrentviewer.core.Video
import com.torrentviewer.core.VlcView
import com.torrentviewer.core.Watch
import com.torrentviewer.core.clock
import com.torrentviewer.core.left
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.searchableName
import com.torrentviewer.core.speed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import com.composables.icons.lucide.R as L

/** How long the controls stay up after the last touch. */
private const val HIDE_MS = 4000L
/** Paused with the controls down this long, the "You're watching" card fades up. */
private const val PAUSE_CARD_MS = 800L
private const val SKIP = 10
/** libVLC's own buffer by bitrate; three seconds rides out a torrent's bursts. */
private fun cacheMsFor(bytesPerSecond: Double) = if (bytesPerSecond > 4_000_000) 6000 else 3000
/** Seeks further than this also aim the torrent at the target before the player asks for it. */
private const val AIM_SECONDS = 30.0
/** A stream that ends this far from its runtime gave up; reopen it, at most this many times. */
private const val EARLY_END_SECONDS = 60.0
private const val MAX_RECOVERIES = 3
/** No new time this long means stalled: the stream blocks below anything the player reports. */
private const val STALL_MS = 1200L

@Composable
fun PlayerScreen(watch: Watch) {
    Fullscreen()
    // Everything resets per file — a new episode is a new player.
    key(watch.playId) { Player(watch) }
}

/** Landscape, bars hidden, screen kept on — for as long as the player is up. */
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
            activity.requestedOrientation = before
        }
    }
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
    val episode = watch.episodeOf(file)
    val next = watch.next
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
    var scrub by remember { mutableStateOf<Double?>(null) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var advanced by remember { mutableStateOf(false) }
    val waiting = ready == null || !loaded

    fun show() {
        controls = true
        lastTouch = System.currentTimeMillis()
    }

    fun togglePause() {
        val v = view.value ?: return
        v.setPaused(!v.isPaused)
        paused = v.isPaused
        show()
    }

    fun goNext() {
        if (next == null || advanced) return
        advanced = true
        watch.playSibling(next)
    }

    fun seekTo(seconds: Double) {
        val target = seconds.coerceIn(0.0, if (duration > 0) duration - 1 else seconds)
        val v = view.value ?: return
        val from = at
        at = target
        val ms = (target * 1000).toLong()
        if (duration > 0 && abs(target - from) > AIM_SECONDS) scope.launch(Dispatchers.IO) { engine.setPosition(target / duration) }
        // Starved, the player would act on the seek only once the piece it is blocked on arrives; reopening does not wait.
        if (engine.starved()) v.reload(ms) else v.seekTo(ms)
        lastTouch = System.currentTimeMillis()
    }

    // The stream arrives: hand it to the player, with the torrent's own subtitle files.
    // From where it is, not startAt: a fallback to VLC picks up mid-film.
    LaunchedEffect(ready, view.value) {
        val r = ready ?: return@LaunchedEffect
        val v = view.value ?: return@LaunchedEffect
        v.open(r.url, (at * 1000).toLong(), cacheMsFor(bitrate), r.subtitleFiles + watch.savedSubs().map { it.path })
    }

    // Home or the lock button: stop, and keep the spot.
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
    LaunchedEffect(controls, lastTouch, paused, scrub) {
        if (!controls || paused || scrub != null) return@LaunchedEffect
        delay(HIDE_MS)
        controls = false
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

    BackHandler { watch.exit() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = { if (controls) controls = false else show() },
                // The sides skip, as on YouTube; the middle plays and pauses.
                onDoubleTap = { o ->
                    when {
                        o.x < size.width / 3f -> seekTo(at - SKIP)
                        o.x > size.width * 2 / 3f -> seekTo(at + SKIP)
                        else -> togglePause()
                    }
                },
            )
        },
    ) {
        key(vlc) {
            AndroidView(
                factory = { context ->
                    (if (vlc) VlcView(context) else ExoView(context)).also { v ->
                        if (!vlc) v.onUnsupported = { message ->
                            Log.w("Player", "Media3 gave up ($message), falling back to VLC")
                            vlc = true
                        }
                        var recoveries = 0
                        v.onLoaded = { ms ->
                            duration = ms / 1000.0
                            if (!loaded) {
                                loaded = true
                                watch.saveDuration(duration)
                                // Aim the engine at the resume point now the real runtime is known.
                                if (startAt > 0 && duration > 0) scope.launch(Dispatchers.IO) { engine.setPosition(minOf(0.99, startAt / duration)) }
                            }
                        }
                        v.onTime = { ms -> if (scrub == null) at = ms / 1000.0 }
                        v.onBuffering = { buffering = it }
                        v.onPlaying = { paused = !it }
                        v.onEnded = {
                            // A stream that gave up mid-film looks like the end: reopen where it stopped.
                            if (duration > 0 && at < duration - EARLY_END_SECONDS && recoveries < MAX_RECOVERIES) {
                                recoveries++
                                v.reload((at * 1000).toLong())
                            } else if (next != null) goNext() else watch.exit()
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
        AnimatedVisibility(spinning, Modifier.align(Alignment.Center), enter = fadeIn(tween(300, 400)), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 56.dp)
                progressState.value?.let { Text("${speed(it.speed)}  ·  ${it.peers} peers", Modifier.padding(top = 12.dp), style = Type.meta.copy(color = Color.White)) }
            }
        }

        PausedCard(paused && !controls && !waiting, title, episode, watch.item?.label.orEmpty(), at, duration)

        AnimatedVisibility(controls && !waiting, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    TopBar(title, episode, file, progressState.value, onBack = { watch.exit() })
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        RoundIcon(L.drawable.lucide_ic_rotate_ccw, { seekTo(at - SKIP) }, size = 52.dp)
                        RoundIcon(if (paused) L.drawable.lucide_ic_play else L.drawable.lucide_ic_pause, ::togglePause, size = 72.dp)
                        RoundIcon(L.drawable.lucide_ic_rotate_cw, { seekTo(at + SKIP) }, size = 52.dp)
                    }
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                        Slider(
                            value = if (duration > 0) ((scrub ?: at) / duration).toFloat().coerceIn(0f, 1f) else 0f,
                            onValueChange = {
                                scrub = it * duration
                                lastTouch = System.currentTimeMillis()
                            },
                            onValueChangeFinished = {
                                scrub?.let(::seekTo)
                                scrub = null
                            },
                            enabled = duration > 0,
                            colors = SliderDefaults.colors(thumbColor = Ink.accent, activeTrackColor = Ink.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(scrub ?: at), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                            Text("  /  " + if (duration > 0) clock(duration) else "--:--", Modifier.weight(1f), style = Type.label.copy(color = Ink.muted))
                            next?.let {
                                Tap(::goNext, shape = RoundedCornerShape(6.dp)) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Lc(L.drawable.lucide_ic_skip_forward, tint = Color.White, size = 18.dp)
                                        Text("  Next episode", style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(waiting, enter = fadeIn(), exit = fadeOut(tween(500))) {
            Loading(title, episode, file, ready != null, progressState.value, onBack = { watch.exit() })
        }
    }
}

@Composable
private fun TopBar(title: Title?, episode: Episode?, file: TorrentFile?, progress: Progress?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title?.title ?: searchableName(file?.name.orEmpty()).ifBlank { file?.name.orEmpty() }, style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ep = episode?.let { "S${it.season}:E${it.episode}  ${it.name}" } ?: file?.let { parseEpisode(it.name) }?.let { "S${it.season}:E${it.episode}" }
            ep?.let { Text(it, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        progress?.let {
            Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Lc(L.drawable.lucide_ic_download, tint = Ink.muted, size = 13.dp)
                Text(" ${speed(it.speed)}", style = Type.meta.copy(color = Color.White))
                Spacer(Modifier.width(10.dp))
                Lc(L.drawable.lucide_ic_users, tint = Ink.muted, size = 13.dp)
                Text(" ${it.peers}", style = Type.meta.copy(color = Color.White))
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
