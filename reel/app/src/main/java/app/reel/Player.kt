package app.reel

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import com.composables.icons.lucide.R as L

/** The same steps and labels as Watchly's player. */
private val RATES = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP = listOf(0, 15, 30, 60)
private val SCALES = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Best fit",
    MediaPlayer.ScaleType.SURFACE_FILL to "Stretch",
    MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Crop",
    MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
    MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Original",
)

/** Press and hold the picture: double speed until the finger lifts, as YouTube does. */
private const val HOLD_MS = 450L

@Composable
fun PlayerScreen(screen: Screen.Player, shared: Shared, back: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val source = screen.opened.source
    val entry = Library.entries.collectAsState().value.find { it.source == source }
    val stream = remember { screen.torrent.stream(screen.file.index).also { it.wholeFile = Library.wholeFile } }
    val state by stream.state.collectAsState()
    var wholeFile by remember { mutableStateOf(stream.wholeFile) }
    DisposableEffect(stream) {
        stream.start()
        onDispose { stream.close() }
    }
    Fullscreen()
    val pip = rememberInPip()

    val vlc = remember { LibVLC(context, arrayListOf("--audio-time-stretch", "--no-drop-late-frames", "--no-skip-frames")) }
    val player = remember { MediaPlayer(vlc) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var time by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var cache by remember { mutableFloatStateOf(100f) }
    var failed by remember { mutableStateOf(false) }

    /** Where this film was left, for Continue watching. */
    fun savePlace() {
        if (length <= 0 || time <= 0) return
        Library.update(source) { (it ?: Entry(source, screen.file.name)).copy(fileIndex = screen.file.index, position = time, length = length, watched = System.currentTimeMillis()) }
    }

    DisposableEffect(player) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; started = true }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EndReached -> { playing = false; time = length }
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.Buffering -> cache = e.buffering
                MediaPlayer.Event.EncounteredError -> failed = true
            }
        }
        onDispose {
            savePlace()
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            vlc.release()
        }
    }
    LaunchedEffect(state.url) {
        val url = state.url ?: return@LaunchedEffect
        val media = Media(vlc, Uri.parse(url))
        // The HTTP server already blocks for missing pieces; VLC's own cache just smooths reads.
        media.addOption(":network-caching=2500")
        if (screen.startAt > 0) media.addOption(":start-time=${screen.startAt / 1000.0}")
        player.media = media
        media.release()
        player.play()
    }
    // "Next 5 min" is worked out in bytes from the running time.
    LaunchedEffect(length) { stream.durationMs = length }
    // The torrent's own subtitle files, handed to VLC as each finishes downloading.
    val attached = remember { mutableSetOf<String>() }
    LaunchedEffect(state.subtitles, started) {
        if (!started) return@LaunchedEffect
        state.subtitles.filter { attached.add(it.path) }.forEach { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(it), false) }
    }
    // Saved every few seconds too, so a crash or a swipe-away still remembers the spot.
    LaunchedEffect(started) {
        while (started) { delay(10_000); savePlace() }
    }
    // Home button or screen off: don't keep playing to an empty room. (Picture-in-picture
    // only pauses the activity; this fires when the little window is closed.)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause(); savePlace() }

    // ---- controls state
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var rate by remember { mutableFloatStateOf(1f) }
    var holding by remember { mutableStateOf(false) }
    var scale by remember { mutableIntStateOf(0) }
    var sleep by remember { mutableIntStateOf(0) }
    var sleepEnds by remember { mutableLongStateOf(0L) }
    var poke by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<String?>(null) }
    var hudAt by remember { mutableLongStateOf(0L) }
    fun say(text: String) { hud = text; hudAt = System.currentTimeMillis() }
    fun bump() { poke++ }

    LaunchedEffect(controls, playing, poke, picking, info) {
        if (controls && playing && !picking && !info) {
            delay(4000)
            controls = false
        }
    }
    LaunchedEffect(hudAt) { if (hud != null) { delay(1400); hud = null } }
    LaunchedEffect(sleepEnds) {
        if (sleepEnds == 0L) return@LaunchedEffect
        delay(sleepEnds - System.currentTimeMillis())
        player.pause()
        sleep = 0
        sleepEnds = 0
        controls = true
        say("Sleep timer — paused")
    }

    val brightness = rememberBrightness()

    val scope = rememberCoroutineScope()
    val ripple = remember { Animatable(0f) }
    var skipped by remember { mutableIntStateOf(0) }
    val currentLength by rememberUpdatedState(length)

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, maxOf(0, currentLength - 1000))
        player.time = target
        time = target
        bump()
    }
    fun skip(seconds: Int) {
        // Taps in a row add up — "20 seconds", "30 seconds" — as on YouTube.
        skipped = if (ripple.value > 0f && skipped != 0 && (skipped > 0) == (seconds > 0)) skipped + seconds else seconds
        seekTo(player.time + seconds * 1000L)
        scope.launch {
            ripple.snapTo(1f)
            ripple.animateTo(0f, tween(500, delayMillis = 450))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                VLCVideoLayout(it).also { layout ->
                    layout.keepScreenOn = true
                    player.attachViews(layout, null, true, false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!pip) {
            // The picture itself: tap for controls, double-tap a side to skip, hold for 2x.
            Box(
                Modifier.fillMaxSize().pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            if (picking || info) { picking = false; info = false } else controls = !controls
                        },
                        onDoubleTap = if (locked) null else { at: Offset -> skip(if (at.x < size.width / 2) -10 else 10) },
                        onPress = {
                            if (locked) return@detectTapGestures
                            val hold = scope.launch {
                                delay(HOLD_MS)
                                if (playing) { holding = true; player.rate = 2f }
                            }
                            tryAwaitRelease()
                            hold.cancel()
                            if (holding) { holding = false; player.rate = rate }
                        },
                    )
                },
            )

            SkipRipple(skipped, ripple.value)

            AnimatedVisibility(holding, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
                Hud("2x", L.drawable.lucide_ic_chevrons_right)
            }

            // Mid-film stall with the controls away: just the spinner and the speed.
            AnimatedVisibility(started && cache < 100f && !failed && !controls, Modifier.align(Alignment.Center), enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.15f))
                    Text(rate(state.swarm.rate), Modifier.padding(top = 12.dp), style = Type.small.copy(color = Color.White))
                }
            }

            // Locked: one button, and nothing else responds.
            AnimatedVisibility(started && controls && locked, Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 20.dp), enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
                GlassButton(L.drawable.lucide_ic_lock, "Unlock controls", size = 46) { locked = false; say("Unlocked"); bump() }
            }

            AnimatedVisibility(started && controls && !locked, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TopBar(
                        title = entry?.title ?: screen.file.name,
                        state = state,
                        info = info,
                        back = back,
                        toggleInfo = { info = !info; picking = false; bump() },
                    )
                    BrightnessRail(brightness, Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { say("Brightness ${(it * 100).toInt()}%"); bump() }
                    Transport(
                        Modifier.align(Alignment.Center),
                        playing = playing,
                        waiting = cache < 100f,
                        toggle = { if (playing) player.pause() else player.play(); bump() },
                        skip = ::skip,
                    )
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(time), Modifier.widthIn(min = 56.dp), style = Type.small.copy(color = Color.White))
                            Box(Modifier.weight(1f)) {
                                SeekBar(if (length > 0) time.toFloat() / length else 0f, state.spans, length) { seekTo((it * length).toLong()) }
                            }
                            Text(clock(length), Modifier.widthIn(min = 56.dp).padding(start = 10.dp), style = Type.small.copy(color = Color.White))
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                        ) {
                            Action(L.drawable.lucide_ic_lock, "Lock") { locked = true; say("Controls locked"); bump() }
                            Action(L.drawable.lucide_ic_maximize, SCALES[scale].second, on = scale != 0) {
                                scale = (scale + 1) % SCALES.size
                                player.videoScale = SCALES[scale].first
                                say(SCALES[scale].second)
                                bump()
                            }
                            Action(L.drawable.lucide_ic_captions, "Subtitles", on = picking) { picking = !picking; info = false; bump() }
                            Action(null, "Speed", text = "${rate.toString().removeSuffix(".0")}x", on = rate != 1f) {
                                rate = RATES[(RATES.indexOf(rate) + 1) % RATES.size]
                                player.rate = rate
                                say("Speed ${rate.toString().removeSuffix(".0")}x")
                                bump()
                            }
                            Action(
                                if (wholeFile) L.drawable.lucide_ic_hard_drive else L.drawable.lucide_ic_clock,
                                if (wholeFile) "Whole file" else "Next 5 min",
                                on = wholeFile,
                            ) {
                                wholeFile = !wholeFile
                                stream.wholeFile = wholeFile
                                Library.wholeFile = wholeFile
                                say(if (wholeFile) "Downloading the whole file" else "Downloading 5 min ahead")
                                bump()
                            }
                            Action(L.drawable.lucide_ic_picture_in_picture_2, "Mini player") {
                                runCatching {
                                    activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
                                }
                            }
                            Action(
                                L.drawable.lucide_ic_moon,
                                if (sleep == 0) "Sleep" else "${maxOf(1, ((sleepEnds - System.currentTimeMillis()) / 60_000).toInt() + 1)} min",
                                on = sleep != 0,
                            ) {
                                sleep = (sleep + 1) % SLEEP.size
                                sleepEnds = if (SLEEP[sleep] == 0) 0 else System.currentTimeMillis() + SLEEP[sleep] * 60_000L
                                say(if (SLEEP[sleep] == 0) "Sleep timer off" else "Pausing in ${SLEEP[sleep]} min")
                                bump()
                            }
                        }
                    }
                }
            }

            // Under the title, never over the play button.
            AnimatedVisibility(hud != null && !holding, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 76.dp), enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { -it / 2 }, exit = fadeOut(Motion.effects())) {
                Hud(hud.orEmpty())
            }

            AnimatedVisibility(
                picking && controls,
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 68.dp, end = 16.dp),
                enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial(), expandFrom = Alignment.Top),
                exit = fadeOut(Motion.effects()) + shrinkVertically(Motion.spatial(), shrinkTowards = Alignment.Top),
            ) {
                TracksSheet(player) { picking = false; bump() }
            }

            AnimatedVisibility(
                info && controls,
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 68.dp, end = 16.dp),
                enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial(), expandFrom = Alignment.Top),
                exit = fadeOut(Motion.effects()) + shrinkVertically(Motion.spatial(), shrinkTowards = Alignment.Top),
            ) {
                StatsPanel(state, screen.file, cache, rate)
            }
        }

        AnimatedVisibility(!started, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.spatialSlow())) {
            Opening(entry, screen, shared, state, failed, back)
        }
    }
}

/* ------------------------------------------------------------- pieces */

@Composable
private fun TopBar(title: String, state: StreamState, info: Boolean, back: () -> Unit, toggleInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassButton(L.drawable.lucide_ic_arrow_left, "Back", onClick = back)
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 12.dp),
            style = Type.title.copy(color = Color.White, fontSize = 16.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        GlassButton(L.drawable.lucide_ic_info, "Stats", on = info, size = 40, onClick = toggleInfo)
        Spacer(Modifier.width(10.dp))
        // Fixed widths: the numbers change every second and mustn't shove anything around.
        Row(
            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(L.drawable.lucide_ic_users, tint = Color.White.copy(alpha = 0.6f), size = 13.dp)
            Text("${state.swarm.seeds}/${state.swarm.peers}", Modifier.padding(start = 6.dp, end = 12.dp).widthIn(min = 34.dp), style = Type.caption.copy(color = Color.White.copy(alpha = 0.85f)))
            Lc(L.drawable.lucide_ic_download, tint = Color.White.copy(alpha = 0.6f), size = 13.dp)
            Text(rate(state.swarm.rate), Modifier.padding(start = 6.dp).widthIn(min = 64.dp), style = Type.caption.copy(color = Color.White.copy(alpha = 0.85f)))
        }
    }
}

/** Back ten, play/pause (a spinner while VLC waits on data), forward ten. */
@Composable
private fun Transport(modifier: Modifier, playing: Boolean, waiting: Boolean, toggle: () -> Unit, skip: (Int) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        SkipButton(L.drawable.lucide_ic_rotate_ccw, "Back 10 seconds") { skip(-10) }
        Box(Modifier.press(enabled = !waiting, onClick = toggle).size(68.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            AnimatedContent(
                if (waiting) 2 else if (playing) 1 else 0,
                transitionSpec = { (scaleIn(Motion.bouncy(), 0.6f) + fadeIn(Motion.effects())) togetherWith (scaleOut(Motion.effects(), 0.6f) + fadeOut(Motion.effects())) },
                label = "playpause",
            ) { s ->
                when (s) {
                    2 -> CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp)
                    1 -> Lc(L.drawable.lucide_ic_pause, tint = Color.White, size = 30.dp)
                    else -> Lc(L.drawable.lucide_ic_play, tint = Color.White, size = 30.dp, modifier = Modifier.offset(x = 2.dp))
                }
            }
        }
        SkipButton(L.drawable.lucide_ic_rotate_cw, "Forward 10 seconds") { skip(10) }
    }
}

/** The rotate arrow with the "10" sitting inside it, so it reads as "back 10". */
@Composable
private fun SkipButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.press(onClick = onClick).size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        Lc(icon, tint = Color.White, size = 26.dp, description = description)
        Text("10", Modifier.offset(y = 1.dp), style = Type.caption.copy(color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun GlassButton(icon: Int, description: String, size: Int = 42, on: Boolean = false, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (on) Ink.accent else Color.Black.copy(alpha = 0.5f), Motion.effects(), label = "glass")
    Box(Modifier.press(onClick = onClick).size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Lc(icon, tint = Color.White, size = (size * 0.45f).dp, description = description)
    }
}

/** One of the labelled buttons along the bottom: an icon (or a value like "1.5x") over a word. */
@Composable
private fun Action(icon: Int?, label: String, text: String? = null, on: Boolean = false, onClick: () -> Unit) {
    val tint by androidx.compose.animation.animateColorAsState(if (on) Ink.accent else Color.White, Motion.effects(), label = "action")
    Column(
        Modifier.press(pressedScale = 0.9f, onClick = onClick).widthIn(min = 64.dp).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Lc(icon, tint = tint, size = 20.dp) else Text(text.orEmpty(), style = Type.title.copy(color = tint, fontSize = 15.sp))
        }
        Text(label, Modifier.padding(top = 3.dp), style = Type.caption.copy(color = if (on) Ink.accent else Color.White.copy(alpha = 0.65f), fontSize = 10.sp))
    }
}

@Composable
private fun Hud(text: String, icon: Int? = null) {
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.title.copy(color = Color.White, fontSize = 14.sp))
        if (icon != null) {
            Spacer(Modifier.width(6.dp))
            Lc(icon, tint = Color.White, size = 16.dp)
        }
    }
}

/**
 * Half the picture, bulging in from the edge that was tapped, with the arrows and the
 * running total — the shape YouTube uses. Darkens rather than whitens, so it reads over snow.
 */
@Composable
private fun SkipRipple(seconds: Int, shown: Float) {
    if (shown <= 0f || seconds == 0) return
    val forward = seconds > 0
    Box(Modifier.fillMaxSize(), contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.42f)
                .graphicsLayer { alpha = shown }
                .clip(if (forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50) else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
                .background(Color.Black.copy(alpha = 0.45f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Lc(if (forward) L.drawable.lucide_ic_chevrons_right else L.drawable.lucide_ic_chevrons_left, tint = Color.White, size = 30.dp)
            Text("${kotlin.math.abs(seconds)} seconds", Modifier.padding(top = 4.dp), style = Type.small.copy(color = Color.White))
        }
    }
}

/** Brightness for this window only, set back to the system's when the player closes. */
private class Brightness(val get: () -> Float, val set: (Float) -> Unit)

@Composable
private fun rememberBrightness(): Brightness {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var level by remember {
        val system = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        mutableFloatStateOf(system.coerceIn(0.05f, 1f))
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    return Brightness({ level }) { v ->
        level = v.coerceIn(0.05f, 1f)
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level } }
    }
}

/** Vertical, because up should mean brighter; finger-sized, because it gets pressed repeatedly. */
@Composable
private fun BrightnessRail(brightness: Brightness, modifier: Modifier, changed: (Float) -> Unit) {
    Column(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.press { brightness.set(brightness.get() + 0.1f); changed(brightness.get()) }.size(44.dp, 46.dp), contentAlignment = Alignment.Center) {
            Lc(L.drawable.lucide_ic_plus, tint = Color.White, size = 18.dp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Lc(L.drawable.lucide_ic_sun, tint = Color.White, size = 16.dp)
            Text("${(brightness.get() * 100).toInt()}", Modifier.padding(top = 2.dp), style = Type.caption.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
        }
        Box(Modifier.press { brightness.set(brightness.get() - 0.1f); changed(brightness.get()) }.size(44.dp, 46.dp), contentAlignment = Alignment.Center) {
            Lc(L.drawable.lucide_ic_minus, tint = Color.White, size = 18.dp)
        }
    }
}

/**
 * Thin bar: downloaded stretches brighter than the empty track, the played part in the
 * accent. Dragging previews the time under the thumb and seeks on release.
 */
@Composable
private fun SeekBar(position: Float, spans: List<Pair<Float, Float>>, length: Long, onSeek: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = (dragging ?: position).coerceIn(0f, 1f)
    val knob by animateDpAsState(if (dragging != null) 18.dp else 12.dp, Motion.bouncy(), label = "knob")
    val track by animateDpAsState(if (dragging != null) 5.dp else 3.dp, Motion.spatial(), label = "track")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(seek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val full = maxWidth
        Box(Modifier.fillMaxWidth().height(track).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
            spans.forEach { (from, to) ->
                Box(Modifier.offset(x = full * from).width(full * (to - from)).fillMaxHeight().background(Color.White.copy(alpha = 0.55f)))
            }
            Box(Modifier.width(full * shown).fillMaxHeight().background(Ink.accent))
        }
        Box(Modifier.offset(x = maxWidth * shown - knob / 2).size(knob).clip(CircleShape).background(Ink.accent))
        dragging?.let { d ->
            Text(
                clock((d * length).toLong()),
                Modifier.offset(x = (maxWidth * d - 28.dp).coerceIn(0.dp, maxWidth - 56.dp), y = (-30).dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 3.dp),
                style = Type.small.copy(color = Color.White),
            )
        }
    }
}

/** Audio beside subtitles, not under them: the screen is landscape, so the room is sideways. */
@Composable
private fun TracksSheet(player: MediaPlayer, close: () -> Unit) {
    var audio by remember { mutableIntStateOf(player.audioTrack) }
    var spu by remember { mutableIntStateOf(player.spuTrack) }
    // VLC lists its own "Disable" (id -1) among the audio tracks; it isn't a choice anyone wants here.
    val audios = remember { player.audioTracks.orEmpty().filter { it.id != -1 } }
    val subs = remember { player.spuTracks.orEmpty().toList() }
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(6.dp).heightIn(max = 260.dp)) {
        if (audios.size > 1) {
            TrackColumn("Audio", Modifier.widthIn(min = 170.dp, max = 240.dp)) {
                audios.forEach { t -> TrackOption(t.name, t.id == audio) { player.setAudioTrack(t.id); audio = t.id; close() } }
            }
        }
        // The rule between the columns, drawn on the right one.
        val rule = if (audios.size > 1) {
            Modifier.padding(start = 6.dp).drawBehind { drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }.padding(start = 6.dp)
        } else Modifier
        TrackColumn("Subtitles", rule.widthIn(min = 170.dp, max = 260.dp)) {
            if (subs.isEmpty()) Text("No subtitles in this file", Modifier.padding(10.dp), style = Type.small.copy(color = Color.White.copy(alpha = 0.5f)))
            // VLC lists "Disable" (id -1) itself among the tracks.
            subs.forEach { t -> TrackOption(if (t.id == -1) "Off" else t.name, t.id == spu) { player.setSpuTrack(t.id); spu = t.id; close() } }
        }
    }
}

@Composable
private fun TrackColumn(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(title.uppercase(), Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp), style = Type.caption.copy(color = Color.White.copy(alpha = 0.45f), letterSpacing = 0.8.sp))
        content()
    }
}

@Composable
private fun TrackOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().press(pressedScale = 0.97f, onClick = onClick).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Ink.accent else Color.Transparent).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), style = Type.small.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 16.dp)
    }
}

/** What the stream is doing, behind the info button. */
@Composable
private fun StatsPanel(state: StreamState, file: TorrentFile, cache: Float, rate: Float) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(14.dp).width(240.dp)) {
        Text("STREAM", style = Type.caption.copy(color = Color.White.copy(alpha = 0.45f), letterSpacing = 0.8.sp))
        StatRow("Download", rate(state.swarm.rate))
        StatRow("Peers", "${state.swarm.peers} (${state.swarm.seeds} seeds)")
        StatRow("Downloaded", "${(state.progress * 100).toInt()}% of ${bytes(file.size)}")
        StatRow("Player buffer", if (cache >= 100f) "Full" else "${cache.toInt()}%")
        StatRow("Speed", "${rate.toString().removeSuffix(".0")}x")
        Text("FILE", Modifier.padding(top = 10.dp), style = Type.caption.copy(color = Color.White.copy(alpha = 0.45f), letterSpacing = 0.8.sp))
        Text(file.name, Modifier.padding(top = 4.dp), style = Type.caption.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Text(label, Modifier.weight(1f), style = Type.caption.copy(color = Color.White.copy(alpha = 0.6f)))
        Text(value, style = Type.caption.copy(color = Color.White, fontWeight = FontWeight.Medium))
    }
}

/** Landscape, edge to edge, system bars away until swiped back. */
@Composable
private fun Fullscreen() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = before
        }
    }
}

/** True while the film is in the picture-in-picture window — the controls stay out of it. */
@Composable
private fun rememberInPip(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity ?: return false
    var pip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return pip
}

/** Before the first frame: the film's own backdrop, dimmed, and a ring that fills. */
@Composable
private fun Opening(entry: Entry?, screen: Screen.Player, shared: Shared, state: StreamState, failed: Boolean, back: () -> Unit) {
    val percent by animateIntAsState(if (state.url != null) 100 else state.buffer, Motion.spatialSlow(), label = "percent")
    val ring by animateFloatAsState(percent / 100f, Motion.spatial(), label = "ring")
    val title = entry?.title ?: screen.file.name
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Artwork(
            entry?.art?.backdrop,
            title,
            Modifier.fillMaxSize().shared("backdrop-${screen.opened.source}", shared)
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(6.dp) else Modifier),
            RoundedCornerShape(0.dp),
            fallbackText = false,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
        IconCircle(L.drawable.lucide_ic_arrow_left, "Back", Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), glass = true, onClick = back)
        Row(Modifier.align(Alignment.Center).padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                val accent = Ink.accent
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 6.dp.toPx()
                    val inset = Offset(stroke / 2, stroke / 2)
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(Color.White.copy(alpha = 0.14f), 0f, 360f, false, inset, arc, style = Stroke(stroke))
                    drawArc(accent, -90f, 360f * ring, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("$percent%", style = Type.h2.copy(color = Color.White, fontWeight = FontWeight.Bold))
            }
            Column(Modifier.padding(start = 28.dp).width(320.dp)) {
                Text(
                    when {
                        failed -> "Couldn't play this file"
                        state.url != null -> "Starting playback"
                        else -> "Buffering"
                    },
                    style = Type.small.copy(color = if (failed) Ink.error else Ink.accent, fontWeight = FontWeight.SemiBold),
                )
                Text(title, Modifier.padding(top = 4.dp), style = Type.h1.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(state.swarm.peers > 0)
                    Spacer(Modifier.width(8.dp))
                    Text("${peers(state.swarm.peers)} · ${rate(state.swarm.rate)}", style = Type.small.copy(color = Color.White.copy(alpha = 0.75f)))
                }
            }
        }
    }
}
