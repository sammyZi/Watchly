package com.torrentviewer.tv

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
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
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "focus")
    Box(
        modifier
            // Over its neighbours while it is grown, not under them.
            .zIndex(if (focused) 1f else 0f)
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
                    if (it.isFocused) onFocus?.invoke()
                }
            }
            .drawWithContent {
                drawContent()
                if (ring && t > 0f) {
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
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, requester: FocusRequester? = null) {
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
        }
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
