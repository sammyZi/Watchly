package com.torrentviewer.core

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

/**
 * Near-black page, one red, white focus. The artwork carries the colour. The phone may
 * switch to a light page ([use]); the colours are snapshot state, so what reads them
 * redraws with the switch. The TV never leaves dark.
 */
object Ink {
    private class Palette(
        val bg: Color, val surface: Color, val surface2: Color, val stroke: Color,
        val text: Color, val muted: Color, val faint: Color, val good: Color, val warn: Color, val danger: Color,
    )

    private val DARK = Palette(
        Color(0xFF0A0A0A), Color(0xFF181818), Color(0xFF262626), Color(0xFF333333),
        Color(0xFFFFFFFF), Color(0xFFB3B3B3), Color(0xFF7A7A7A), Color(0xFF46D369), Color(0xFFFFB340), Color(0xFFFF5A4E),
    )
    private val LIGHT = Palette(
        Color(0xFFFFFFFF), Color(0xFFF1F1F1), Color(0xFFE4E4E4), Color(0xFFD0D0D0),
        Color(0xFF141414), Color(0xFF575757), Color(0xFF8A8A8A), Color(0xFF1E8E3E), Color(0xFFB26A00), Color(0xFFD32F2F),
    )
    private var p by mutableStateOf(DARK)

    val isDark get() = p === DARK
    fun use(dark: Boolean) {
        p = if (dark) DARK else LIGHT
    }

    val bg get() = p.bg
    val surface get() = p.surface
    val surface2 get() = p.surface2
    val stroke get() = p.stroke
    val text get() = p.text
    val muted get() = p.muted
    val faint get() = p.faint
    val good get() = p.good
    val warn get() = p.warn
    val danger get() = p.danger
    /** Netflix's red, lit for a TV across the room; an app may set its own before it composes (the phone's is deeper). */
    var accent by mutableStateOf(Color(0xFFE50914))
    val imdb = Color(0xFFF5C518)
    val focus = Color.White
}

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int) = Font(
    R.font.google_sans_flex,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Sans = FontFamily(flex(400), flex(500), flex(600), flex(700), flex(800))

/**
 * Sized for the 960×540dp canvas a television reports, nudged up for ten feet. Getters,
 * not values, so each style picks up the palette in use when it is read.
 */
object Type {
    val hero get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp, color = Ink.text)
    val display get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp, color = Ink.text)
    val title get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = Ink.text)
    val body get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = Ink.muted)
    val label get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, color = Ink.text)
    val meta get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, color = Ink.muted)
    val overline get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp, color = Ink.faint)
}

object Motion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 600f)
    fun <T> effects(): FiniteAnimationSpec<T> = tween(180)
}

@Composable
fun Lc(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = Ink.text, size: Dp = 20.dp) =
    Icon(painterResource(icon), null, modifier.size(size), tint = tint)

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
                // On the dark tint, whatever the page's palette.
                style = Type.label.copy(fontWeight = FontWeight.Bold, color = Color.White),
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

/**
 * Year · runtime · genres, dot-separated, with the rating badge first. Too long for the
 * width, it carries whole facts onto the next line — never half a word.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Facts(title: Title, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title.rating?.let {
            Row(Modifier.align(Alignment.CenterVertically).padding(end = 14.dp)) { ImdbBadge(it) }
        }
        title.facts().forEachIndexed { i, fact ->
            Text(
                (if (i > 0) "  •  " else "") + fact,
                Modifier.align(Alignment.CenterVertically),
                style = Type.meta.copy(color = Ink.text.copy(alpha = 0.85f)),
                maxLines = 1,
                softWrap = false,
            )
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

/**
 * The panel's highest mode up to 120Hz at the current resolution. Without it Android keeps an
 * app at 60Hz on most phones and TVs, whatever the screen can do. Call again on resume.
 */
fun android.app.Activity.preferHighRefreshRate() {
    runCatching {
        @Suppress("DEPRECATION")
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display ?: return else windowManager.defaultDisplay
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate <= 120.5f }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }
}
