package app.reel

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay
import com.composables.icons.lucide.R as L

@Immutable
class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val stroke: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentSoft: Color,
    val error: Color,
)

/** Neutral near-black, one accent. Imagery carries the colour. */
val DarkInk = Palette(
    dark = true,
    bg = Color(0xFF0B0B0E), surface = Color(0xFF15151A), surface2 = Color(0xFF1E1E25), stroke = Color(0xFF2A2A33),
    text = Color(0xFFF4F4F6), text2 = Color(0xFFA6A6B0), text3 = Color(0xFF6A6A75),
    accent = Color(0xFF3D8BFF), accentSoft = Color(0x293D8BFF), error = Color(0xFFFF6464),
)

/** The same, in daylight: a soft grey page, white cards, and a slightly deeper blue for contrast. */
val LightInk = Palette(
    dark = false,
    bg = Color(0xFFF3F3F6), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE8E8EE), stroke = Color(0xFFD9D9E1),
    text = Color(0xFF111115), text2 = Color(0xFF55555F), text3 = Color(0xFF8C8C97),
    accent = Color(0xFF2A74F0), accentSoft = Color(0x1F2A74F0), error = Color(0xFFD63A3A),
)

val LocalInk = staticCompositionLocalOf { DarkInk }

/** The current palette's colours, by name. */
object Ink {
    val bg: Color @Composable @ReadOnlyComposable get() = LocalInk.current.bg
    val surface: Color @Composable @ReadOnlyComposable get() = LocalInk.current.surface
    val surface2: Color @Composable @ReadOnlyComposable get() = LocalInk.current.surface2
    val stroke: Color @Composable @ReadOnlyComposable get() = LocalInk.current.stroke
    val text: Color @Composable @ReadOnlyComposable get() = LocalInk.current.text
    val text2: Color @Composable @ReadOnlyComposable get() = LocalInk.current.text2
    val text3: Color @Composable @ReadOnlyComposable get() = LocalInk.current.text3
    val accent: Color @Composable @ReadOnlyComposable get() = LocalInk.current.accent
    val accentSoft: Color @Composable @ReadOnlyComposable get() = LocalInk.current.accentSoft
    val error: Color @Composable @ReadOnlyComposable get() = LocalInk.current.error
    val imdb = Color(0xFFF5C518)
}

/** Google Sans Flex, one variable file: each weight is a point on its wght axis. */
@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int) = Font(
    R.font.google_sans_flex,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("ROND", 30f)),
)

val Sans = FontFamily(flex(400), flex(500), flex(600), flex(700), flex(800))

/** Text styles in the current palette's colours. */
object Type {
    private val hero0 = TextStyle(fontFamily = Sans, fontWeight = FontWeight(750), fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp)
    private val h10 = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp)
    private val h20 = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp)
    private val title0 = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    private val body0 = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    private val small0 = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    private val caption0 = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)

    val hero: TextStyle @Composable @ReadOnlyComposable get() = hero0.copy(color = Ink.text)
    val h1: TextStyle @Composable @ReadOnlyComposable get() = h10.copy(color = Ink.text)
    val h2: TextStyle @Composable @ReadOnlyComposable get() = h20.copy(color = Ink.text)
    val title: TextStyle @Composable @ReadOnlyComposable get() = title0.copy(color = Ink.text)
    val body: TextStyle @Composable @ReadOnlyComposable get() = body0.copy(color = Ink.text2)
    val small: TextStyle @Composable @ReadOnlyComposable get() = small0.copy(color = Ink.text2)
    val caption: TextStyle @Composable @ReadOnlyComposable get() = caption0.copy(color = Ink.text3)
}

/** "system", "light" or "dark" → the palette to use. */
@Composable
fun paletteFor(theme: String): Palette = when (theme) {
    "light" -> LightInk
    "dark" -> DarkInk
    else -> if (isSystemInDarkTheme()) DarkInk else LightInk
}

/** The palette, plus Material components (switches, menus, the bottom bar) in the same colours and font. */
@Composable
fun ReelTheme(palette: Palette, content: @Composable () -> Unit) {
    val base = Typography()
    val p = palette
    val scheme = if (p.dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalInk provides p) {
        MaterialTheme(
            colorScheme = scheme.copy(
                primary = p.accent, onPrimary = Color.White,
                secondaryContainer = p.accentSoft, onSecondaryContainer = p.text,
                background = p.bg, onBackground = p.text,
                surface = p.bg, onSurface = p.text, onSurfaceVariant = p.text2,
                surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
                outline = p.stroke, outlineVariant = p.stroke, error = p.error,
            ),
            typography = Typography(
                labelMedium = base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
                labelLarge = base.labelLarge.copy(fontFamily = Sans),
                bodyLarge = base.bodyLarge.copy(fontFamily = Sans),
                bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
                titleMedium = base.titleMedium.copy(fontFamily = Sans),
            ),
            content = content,
        )
    }
}

/** Material 3 Expressive spring tokens: spatial for movement, effects for fades and colour. */
object Motion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 300f)
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 900f)
}

/** A Lucide icon. */
@Composable
fun Lc(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = Ink.text, size: Dp = 20.dp, description: String? = null) =
    Icon(painterResource(icon), description, modifier.size(size), tint = tint)

/** Click with a springy press-down instead of a ripple. */
fun Modifier.press(enabled: Boolean = true, pressedScale: Float = 0.95f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, Motion.bouncy(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(source, indication = null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
}

/** Items arrive one after another, rising a little — not all at once. */
fun Modifier.enter(index: Int): Modifier = composed {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(35L * index.coerceAtMost(10))
        shown.animateTo(1f, Motion.spatialSlow())
    }
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    graphicsLayer {
        alpha = shown.value
        translationY = (1 - shown.value) * rise
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val fill by animateColorAsState(if (enabled) Ink.accent else Ink.surface2, Motion.effects(), label = "fill")
    val ink by animateColorAsState(if (enabled) Color.White else Ink.text3, Motion.effects(), label = "ink")
    Row(
        modifier.press(enabled && !busy, onClick = onClick).clip(CircleShape).background(fill).height(52.dp).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp)
        } else if (icon != null) {
            Lc(icon, tint = ink, size = 19.dp)
        }
        if (busy || icon != null) Spacer(Modifier.width(10.dp))
        Text(text, style = Type.title.copy(color = ink, fontSize = 16.sp))
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    Row(
        modifier.press(onClick = onClick).clip(CircleShape).background(Ink.surface2).height(52.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Lc(icon, size = 19.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Type.title.copy(fontSize = 16.sp))
    }
}

/** A round icon button; [glass] for one sitting on artwork. */
@Composable
fun IconCircle(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, size: Dp = 42.dp, glass: Boolean = false, tint: Color = Ink.text, onClick: () -> Unit) {
    Box(
        modifier.press(onClick = onClick).size(size).clip(CircleShape).background(if (glass) Color.Black.copy(alpha = 0.45f) else Ink.surface2),
        contentAlignment = Alignment.Center,
    ) { Lc(icon, tint = tint, size = size * 0.46f, description = description) }
}

/** An icon on a tinted rounded square — list rows and settings. */
@Composable
fun IconTile(@DrawableRes icon: Int, tint: Color = Ink.text, background: Color = Ink.surface2, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
        Lc(icon, tint = tint, size = size * 0.48f)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = Type.h2)
        if (action != null) Text(action, Modifier.press(onClick = onAction).padding(6.dp), style = Type.small.copy(color = Ink.accent))
    }
}

/** A network image over a placeholder; a coloured title card when there's no artwork. */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(12.dp), fallbackText: Boolean = true) {
    val hue = remember(title) { title.fold(0) { h, c -> (h * 31 + c.code) % 360 }.toFloat() }
    Box(modifier.clip(shape).background(if (url == null) Color.hsv(hue, 0.42f, 0.30f) else Ink.surface2)) {
        if (url != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            AsyncImage(
                model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(250).build() },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallbackText) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Lc(L.drawable.lucide_ic_clapperboard, tint = Color.White.copy(alpha = 0.55f), size = 18.dp)
                Spacer(Modifier.weight(1f))
                Text(title, style = Type.title.copy(color = Color.White, fontSize = 14.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Fades artwork into the page below it. */
@Composable
fun scrim(stop: Float = 0.45f) = Brush.verticalGradient(0f to Color.Transparent, stop to Color.Transparent, 1f to Ink.bg)

/** The yellow IMDb badge and the score next to it. */
@Composable
fun ImdbBadge(rating: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "IMDb",
            Modifier.clip(RoundedCornerShape(4.dp)).background(Ink.imdb).padding(horizontal = 5.dp, vertical = 1.dp),
            style = Type.caption.copy(color = Color.Black, fontWeight = FontWeight.Black, fontSize = 11.sp),
        )
        Spacer(Modifier.width(6.dp))
        Text(rating, style = Type.small.copy(color = Ink.text, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun Chip(text: String) = Text(
    text,
    Modifier.clip(CircleShape).background(Ink.surface2).padding(horizontal = 12.dp, vertical = 6.dp),
    style = Type.caption.copy(color = Ink.text2),
)

/** A thin bar for how far through something is. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, track: Color = Color.White.copy(alpha = 0.22f)) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(Ink.accent))
    }
}

/** A dot that breathes while something is live. */
@Composable
fun LiveDot(live: Boolean) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        0.35f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "glow",
    )
    val color by animateColorAsState(if (live) Color(0xFF34D399) else Ink.text3, Motion.effects(), label = "dot")
    Box(Modifier.size(8.dp).graphicsLayer { alpha = if (live) glow else 1f }.clip(CircleShape).background(color))
}

fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.0f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}

fun rate(bytesPerSecond: Int): String = when {
    bytesPerSecond >= 1 shl 20 -> "%.1f MB/s".format(bytesPerSecond / (1 shl 20).toDouble())
    else -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun peers(n: Int) = if (n == 1) "1 peer" else "$n peers"

/** "1h 12m left" / "38m left". */
fun left(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left"
}
