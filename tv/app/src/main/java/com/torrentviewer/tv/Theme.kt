package com.torrentviewer.tv

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.zIndex
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.composables.icons.lucide.R as L

/** Near-black page, one red, white focus. The artwork carries the colour. */
object Ink {
    val bg = Color(0xFF0A0A0A)
    val surface = Color(0xFF181818)
    val surface2 = Color(0xFF262626)
    val stroke = Color(0xFF333333)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFFB3B3B3)
    val faint = Color(0xFF7A7A7A)
    val accent = Color(0xFFE50914)
    val good = Color(0xFF46D369)
    val warn = Color(0xFFFFB340)
    val danger = Color(0xFFFF5A4E)
    val imdb = Color(0xFFF5C518)
    val focus = Color.White
}

/** Televisions crop the edges of the picture; nothing readable goes nearer than this. */
object Safe {
    val x = 48.dp
    val y = 27.dp
}

/** The collapsed rail floats over the page; every page leaves this gutter for it. */
val NAV_W = 72.dp
val GUTTER = NAV_W + 40.dp

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int) = Font(
    R.font.google_sans_flex,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Sans = FontFamily(flex(400), flex(500), flex(600), flex(700), flex(800))

/** Sized for the 960×540dp canvas a television reports, nudged up for ten feet. */
object Type {
    val hero = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp, color = Ink.text)
    val display = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp, color = Ink.text)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = Ink.text)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = Ink.muted)
    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, color = Ink.text)
    val meta = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, color = Ink.muted)
    val overline = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp, color = Ink.faint)
}

object Motion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 600f)
    fun <T> effects(): FiniteAnimationSpec<T> = tween(180)
}

@Composable
fun Lc(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = Ink.text, size: Dp = 20.dp) =
    Icon(painterResource(icon), null, modifier.size(size), tint = tint)

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

/** Netflix's TV buttons: translucent grey at rest, white with black text on focus. */
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
    Focusable(onClick, modifier, RoundedCornerShape(8.dp), 1.05f, ring = false, requester = requester, onFocus = onFocus) { focused ->
        val bg by animateColorAsState(
            when {
                focused -> Color.White
                // Red at rest, so the main action reads as one without looking focused.
                primary -> Ink.accent
                else -> Color.White.copy(alpha = 0.14f)
            },
            Motion.effects(), label = "bg",
        )
        val fg = if (focused) Color.Black else Color.White
        Row(
            Modifier.background(bg).height(44.dp).padding(horizontal = 22.dp),
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

/** A network image over a placeholder tinted from the name. */
@Composable
/** [fill]: a placeholder under the picture — skipped where the picture covers it anyway and the fill is pure GPU cost. */
fun Artwork(url: String?, name: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, fill: Boolean = true) {
    val hue = remember(name) { hueOf(name).toFloat() }
    // The placeholder is a painter Coil draws only until the picture lands, not a fill
    // that stays painted underneath it forever after.
    Box(modifier.then(if (url == null) Modifier.background(Color.hsv(hue, 0.45f, 0.28f)) else Modifier)) {
        if (url != null) {
            val context = LocalContext.current
            AsyncImage(
                model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(300).build() },
                placeholder = if (fill) remember { ColorPainter(Ink.surface) } else null,
                contentDescription = name,
                contentScale = scale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                name,
                Modifier.align(Alignment.BottomStart).padding(10.dp),
                style = Type.label.copy(fontWeight = FontWeight.Bold),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The film's title logo when Cinemeta has one — the premium look — else the name set large. */
@Composable
fun TitleMark(title: Title, modifier: Modifier = Modifier, maxHeight: Dp = 110.dp, style: TextStyle = Type.hero) {
    var failed by remember(title.logo) { mutableStateOf(title.logo == null) }
    if (!failed) {
        val context = LocalContext.current
        AsyncImage(
            model = remember(title.logo) { ImageRequest.Builder(context).data(title.logo).crossfade(250).build() },
            contentDescription = title.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = modifier.height(maxHeight).fillMaxWidth(),
        )
    } else {
        Text(title.title, modifier, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ImdbBadge(rating: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "IMDb",
            Modifier.clip(RoundedCornerShape(3.dp)).background(Ink.imdb).padding(horizontal = 5.dp, vertical = 1.dp),
            style = Type.overline.copy(color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 0.sp, fontSize = 11.sp),
        )
        Spacer(Modifier.width(6.dp))
        Text(rating, style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold))
    }
}

/** Year · runtime · genres, dot-separated, with the rating badge first. */
@Composable
fun Facts(title: Title, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        title.rating?.let {
            ImdbBadge(it)
            Spacer(Modifier.width(14.dp))
        }
        title.facts().forEachIndexed { i, fact ->
            if (i > 0) Text("  •  ", style = Type.meta.copy(color = Ink.faint))
            Text(fact, style = Type.meta.copy(color = Ink.text.copy(alpha = 0.85f)))
        }
    }
}

/** A thin bar for how far through something is. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).background(Ink.accent))
    }
}

/**
 * Fades artwork into the page, bottom and left, the way a hero sits — drawn over the
 * picture only, and each fade only across the part where it is not yet transparent.
 * Full-screen scrim layers cost this TV's GPU more than the picture itself.
 */
fun Modifier.heroScrims(): Modifier = drawWithContent {
    drawContent()
    val left = size.width * 0.55f
    drawRect(
        Brush.horizontalGradient(0f to Ink.bg, 0.25f to Ink.bg.copy(alpha = 0.85f), 1f to Color.Transparent, endX = left),
        size = Size(left, size.height),
    )
    val top = size.height * 0.45f
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Ink.bg, startY = top, endY = size.height),
        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
        size = Size(size.width, size.height - top),
    )
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = Ink.accent) {
    androidx.compose.material3.CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 4.dp, trackColor = Color.White.copy(alpha = 0.12f))
}

/** Quiet text that says why a section is empty. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = Type.body.copy(color = Ink.faint))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))

/** Drawn glyph for each avatar name kept from the Expo build. */
fun avatarIcon(name: String?): Int = when (name) {
    "emoticon-happy" -> L.drawable.lucide_ic_smile
    "emoticon-cool" -> L.drawable.lucide_ic_rocket
    "emoticon-excited" -> L.drawable.lucide_ic_laugh
    "emoticon-wink" -> L.drawable.lucide_ic_smile_plus
    "emoticon-tongue" -> L.drawable.lucide_ic_flame
    "robot" -> L.drawable.lucide_ic_bot
    "cat" -> L.drawable.lucide_ic_cat
    "dog" -> L.drawable.lucide_ic_dog
    "alien" -> L.drawable.lucide_ic_ghost
    "penguin" -> L.drawable.lucide_ic_bird
    else -> L.drawable.lucide_ic_user_round
}

fun colorOf(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Ink.surface2)

@Composable
fun Avatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 8)).background(
            Brush.linearGradient(listOf(colorOf(profile.colour), colorOf(profile.colour).copy(alpha = 0.65f))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (profile.icon != null || profile.isGuest) Lc(avatarIcon(profile.icon), tint = Color.White, size = size * 0.48f)
        else Text(profile.name.take(1).uppercase(), style = Type.hero.copy(fontSize = (size.value * 0.42f).sp))
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

/** Bring a focused item into view by the least scroll, not the TV's pivot to a third of the way. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset
            else -> end - containerSize
        }
    }
}
