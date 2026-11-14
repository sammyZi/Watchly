package com.torrentviewer.tv

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Motion
import com.torrentviewer.core.Type
import com.torrentviewer.core.accentGlow
import com.torrentviewer.core.glow

// The remote's half of the theme: focus rings, D-pad scrolling and the TV-safe gutters.
// The look itself (Ink, Type, artwork, title marks) is in :core, shared with the phone.

/** Televisions crop the edges of the picture; nothing readable goes nearer than this. */
object Safe {
    val x = 48.dp
    val y = 27.dp
}

/** The collapsed rail floats over the page; every page leaves this gutter for it. */
val NAV_W = 72.dp
val GUTTER = NAV_W + 40.dp

/** A held arrow moves focus once in this long. Calibration knob: lower is faster through a long row, and more to draw each second. */
private const val HELD_MOVE_MS = 90L

/**
 * A held arrow repeats about twenty times a second, and every repeat is a focus move: a card to grow,
 * a row to scroll, the next card and its picture to make. A television's processor can't finish one
 * before the next arrives, so they pile up and the row stutters along behind the remote. Paced to one
 * move per [HELD_MOVE_MS], each is drawn through before the next. A press — not a hold — always goes.
 */
fun Modifier.pacedArrows(): Modifier = composed {
    val last = remember { longArrayOf(0L) }
    onPreviewKeyEvent { e ->
        val k = e.key
        if (e.type != KeyEventType.KeyDown || (k != Key.DirectionLeft && k != Key.DirectionRight && k != Key.DirectionUp && k != Key.DirectionDown)) return@onPreviewKeyEvent false
        val native = e.nativeKeyEvent
        if (native.repeatCount > 0 && native.eventTime - last[0] < HELD_MOVE_MS) return@onPreviewKeyEvent true
        last[0] = native.eventTime
        false
    }
}

/** Runs when focus moves into this subtree from outside — not on every move within it. */
fun Modifier.onFocusEnter(enter: () -> Unit): Modifier = composed {
    var had by remember { mutableStateOf(false) }
    onFocusChanged {
        if (it.hasFocus && !had) enter()
        had = it.hasFocus
    }
}

/** Take focus once this is on screen. Throws if nothing is attached yet — so retried. */
@Composable
fun FocusRequester.claim(key: Any? = Unit) {
    LaunchedEffect(key) {
        repeat(5) {
            if (runCatching { requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(32)
        }
    }
}

/** False: every card draws its own ring, fading on as the last one's fades off — how it was. Testing knob. */
const val GLIDING_FOCUS = true

/**
 * The spring Compose scrolls a focused card into place with, which a page cannot change. The ring
 * and Home's row pin use the same one, so that where a row slides a card to the lead the ring's own
 * move and the row's cancel out, and it holds still while the cards pass under it.
 */
val FocusSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
private val RingSpring = spring<Rect>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

/** The page's one focus ring, where there is one; a [Focusable] under it reports to it instead of drawing its own. */
val LocalFocusRing = compositionLocalOf<FocusRing?> { null }

/**
 * One ring for the whole page, which glides from the card the remote leaves to the one it lands on
 * — left, right, up or down — instead of one ring fading out as another fades in. It is drawn over
 * the page by [FocusRingLayer].
 *
 * Two things move it. A change of focus is animated: [box] springs to where the new card was when
 * it took focus. A scroll is not: [shift] is how far that card has moved since, added on as it
 * happens, so the ring rides the page rather than chasing it.
 */
class FocusRing internal constructor(private val scope: CoroutineScope) {
    internal val box = Animatable(Rect.Zero, Rect.VectorConverter)
    internal val alpha = Animatable(0f)
    internal var shift by mutableStateOf(Offset.Zero)
    internal var shape: Shape by mutableStateOf(RoundedCornerShape(8.dp))
    /** Where the layer itself sits in the window. */
    internal var origin = Offset.Zero

    private var owner: Any? = null
    /** The size the ring is going to, as its owner last gave it. */
    private var size = Size.Zero
    private var base = Offset.Zero
    private var leftAt = 0L
    private var moving: Job? = null
    private var fading: Job? = null
    private var movedAt = 0L

    /**
     * An arrow on the remote: the change of focus that follows is the viewer's, and glides. Any other
     * just appears where it lands — a page opening puts the focus on whatever is first and then on its
     * own button, and the ring slid down the screen to Play each time a film was opened.
     */
    fun moved() {
        movedAt = android.os.SystemClock.uptimeMillis()
    }

    /** [token] has the focus and its outline, grown, is [at] in the window. Called as it takes focus and whenever it moves. */
    fun at(token: Any, at: Rect, outline: Shape) {
        val topLeft = at.topLeft - origin
        if (owner === token) {
            // Against [size], set as the card took the ring — not the box's own target, which the
            // coroutine below only sets a moment later. Read too soon it was still the last card's,
            // every card looked resized, and a ring that should have just appeared slid in from
            // wherever it had last been: down the screen to Play, across it on the way back up.
            if (abs(size.width - at.width) < 1f && abs(size.height - at.height) < 1f) {
                // The same card, somewhere else: the page scrolled under the remote.
                shift = topLeft - base
                return
            }
            // The same card, another size: its label changed under the remote ("Finding sources…" to
            // "Play"). Only the move was followed, so the ring kept the old width around the new button.
            val target = Rect(topLeft, at.size)
            size = at.size
            base = topLeft
            shift = Offset.Zero
            moving?.cancel()
            moving = scope.launch {
                launch { alpha.animateTo(1f, tween(120)) }
                box.snapTo(target)
            }
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        // Straight from another card: glide. From nothing — a new page, a chip, the rail — it is just there.
        val glide = (owner != null || now - leftAt < 150) && alpha.value > 0.3f && now - movedAt < 500
        val from = box.value.translate(shift)
        val velocity = box.velocity
        val target = Rect(topLeft, at.size)
        owner = token
        size = at.size
        base = topLeft
        shift = Offset.Zero
        shape = outline
        fading?.cancel()
        moving?.cancel()
        moving = scope.launch {
            launch { alpha.animateTo(1f, tween(120)) }
            if (glide) {
                box.snapTo(from)
                box.animateTo(target, RingSpring, velocity)
            } else {
                box.snapTo(target)
            }
        }
    }

    /** [token] lost the focus, or left the page. Fades a moment later, unless another card has taken it by then. */
    fun release(token: Any) {
        if (owner !== token) return
        owner = null
        leftAt = android.os.SystemClock.uptimeMillis()
        fading?.cancel()
        fading = scope.launch {
            delay(60)
            alpha.animateTo(0f, tween(140))
        }
    }
}

@Composable
fun rememberFocusRing(): FocusRing? {
    val scope = rememberCoroutineScope()
    return remember { if (GLIDING_FOCUS) FocusRing(scope) else null }
}

/** Draws [ring] over everything before it in the same box. Reads it only while drawing: a frame of the glide recomposes nothing. */
@Composable
fun FocusRingLayer(ring: FocusRing?) {
    ring ?: return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().onGloballyPositioned { ring.origin = it.positionInRoot() }) {
        val a = ring.alpha.value
        val r = ring.box.value.translate(ring.shift)
        val w = 3.dp.toPx()
        if (a <= 0f || r.width <= w || r.height <= w) return@Canvas
        val outline = ring.shape.createOutline(Size(r.width - w, r.height - w), layoutDirection, this)
        translate(r.left + w / 2, r.top + w / 2) { drawOutline(outline, Color.White.copy(alpha = a), style = Stroke(w)) }
    }
}

/**
 * Anything the remote can land on: grows a little and takes a white ring, so the
 * viewer can always tell what OK will do from across the room.
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    scale: Float = 1.06f,
    ring: Boolean = true,
    requester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    /** Where it grows from; row cards grow from their left edge so that edge holds still. */
    origin: TransformOrigin = TransformOrigin.Center,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // One 0→1 value drives the grow and the ring, read only in the graphics layer and the
    // draw pass: an animated border colour recomposed every card on every frame.
    // Up eases out, as something arriving does; down is quicker, so the card left is out of the way.
    val t by animateFloatAsState(if (focused) 1f else 0f, if (focused) tween(240, easing = LinearOutSlowInEasing) else tween(170, easing = FastOutSlowInEasing), label = "focus")
    // The page's gliding ring, if it has one: then this draws none of its own.
    val shared = if (ring) LocalFocusRing.current else null
    val token = remember { Any() }
    val spot = remember { arrayOfNulls<LayoutCoordinates>(1) }
    /** This, at full size, in the window: where the ring belongs once it has grown. */
    fun grown(c: LayoutCoordinates): Rect {
        val size = c.size.toSize()
        val big = Size(size.width * scale, size.height * scale)
        return Rect(c.positionInRoot() - Offset((big.width - size.width) * origin.pivotFractionX, (big.height - size.height) * origin.pivotFractionY), big)
    }
    if (shared != null) DisposableEffect(shared) { onDispose { shared.release(token) } }
    Box(
        modifier
            // Over its neighbours while it is grown, not under them.
            .zIndex(if (focused) 1f else 0f)
            // Before the layer: its own place on the page, without the grow.
            .then(
                if (shared != null) Modifier.onGloballyPositioned { c ->
                    spot[0] = c
                    if (focused) shared.at(token, grown(c), shape)
                } else Modifier,
            )
            .graphicsLayer {
                val s = 1f + (scale - 1f) * t
                scaleX = s
                scaleY = s
                transformOrigin = origin
            }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) {
                        onFocus?.invoke()
                        spot[0]?.takeIf { c -> c.isAttached }?.let { c -> shared?.at(token, grown(c), shape) }
                    } else {
                        shared?.release(token)
                    }
                }
            }
            .drawWithContent {
                drawContent()
                if (ring && shared == null && t > 0f) {
                    val w = 3.dp.toPx()
                    val outline = shape.createOutline(Size(size.width - w, size.height - w), layoutDirection, this)
                    translate(w / 2, w / 2) { drawOutline(outline, Color.White.copy(alpha = t), style = Stroke(w)) }
                }
            }
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) { content(focused) }
}

/**
 * Netflix's TV buttons: translucent grey at rest, white with black text on focus. The
 * primary one — Play — is the phone's grainy red glow either way, and takes a ring on focus.
 */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    requester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
) {
    Focusable(onClick, modifier, RoundedCornerShape(8.dp), 1.05f, ring = primary, requester = requester, onFocus = onFocus) { focused ->
        val bg by animateColorAsState(if (focused) Color.White else Color.White.copy(alpha = 0.14f), Motion.effects(), label = "bg")
        val fg = if (focused && !primary) Color.Black else Color.White
        Row(
            (if (primary) Modifier.glow(accentGlow(), 0.85f, 1.1f) else Modifier.background(bg)).height(44.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 18.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = Type.label.copy(color = fg, fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** A pill of choices — seasons, qualities, genres. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, requester: FocusRequester? = null, @DrawableRes trailing: Int? = null, trailingTint: Color? = null) {
    Focusable(onClick, modifier, CircleShape, 1.06f, ring = false, requester = requester) { focused ->
        val bg = when {
            focused -> Color.White
            on -> Ink.accent
            else -> Color.White.copy(alpha = 0.1f)
        }
        val fg = if (focused) Color.Black else if (on) Color.White else Ink.muted
        Row(Modifier.background(bg).height(38.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 16.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = Type.label.copy(color = fg, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                Lc(trailing, tint = trailingTint ?: fg, size = 16.dp)
            }
        }
    }
}

/**
 * A row the remote comes into from above or below. Left to the focus search, that lands on whatever
 * is nearest the middle of where it came from: from something wide — a list row, a text box, an
 * episode card — a chip halfway along the row, and not the one in force. Instead the row gives
 * focus back to where the remote last was in it, and the first time to [lead]: the choice in
 * force, or the row's first. [lead] has to be on something in the row.
 */
fun Modifier.focusRow(lead: FocusRequester): Modifier = focusRestorer(lead).focusGroup()

/** One of several, as a row of chips. The remote comes into the row on the one chosen. */
@Composable
fun <T> Options(values: List<T>, current: T, label: (T) -> String, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    val lead = remember { FocusRequester() }
    // Chosen, but not on offer (a value from a backup, say): the first.
    val at = values.indexOf(current).coerceAtLeast(0)
    Row(modifier.focusRow(lead), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEachIndexed { i, v -> Chip(label(v), v == current, onClick = { onPick(v) }, requester = lead.takeIf { i == at }) }
    }
}

/** Shared column with the gutter every page leaves for the rail. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().padding(start = GUTTER, end = Safe.x, top = Safe.y)) { content() }
}

/**
 * Netflix's rows: the focused card holds still at the left edge and the row glides under
 * it, on one easing curve — rather than the TV default, which lets focus wander right and
 * then jerks the row.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun edgeScroll(leadPx: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    // Row cards grow from their left edge (see Focusable.origin), so that edge is the one
    // to hold at the lead — the card before it then sits exactly outside the clip.
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - leadPx
}

/** [edgeScroll] for a grid: the focused row holds [lead] below the top and the grid glides under it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun rememberEdgeScroll(lead: Dp): androidx.compose.foundation.gestures.BringIntoViewSpec {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { lead.toPx() }
    return remember(px) { edgeScroll(px) }
}

/** Rows that the page scrolls itself (Home pins the focused row): no scroll from focus. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val NoScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Bring a focused item into view by the least scroll, not the TV's pivot to a third of the
 * way: nothing moves while it is on screen, and [margin] keeps that much around it visible.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun minimalScroll(margin: Float = 0f) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin
        val end = offset + size + margin
        return when {
            start >= 0f && end <= containerSize -> 0f
            start < 0f || end - start > containerSize -> start
            else -> end - containerSize
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalScroll = minimalScroll()

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun rememberMinimalScroll(margin: Dp): androidx.compose.foundation.gestures.BringIntoViewSpec {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { margin.toPx() }
    return remember(px) { minimalScroll(px) }
}
