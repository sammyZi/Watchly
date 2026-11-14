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
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.asImageBitmap
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
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
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
        // A warm paper page; cards and sheets white on it, the greys warmed to match.
        Color(0xFFF6F2EE), Color(0xFFFFFFFF), Color(0xFFEAE4DE), Color(0xFFDDD5CE),
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

private val ART_HEADERS = NetworkHeaders.Builder().set("User-Agent", USER_AGENT).build()
private val WIKI_HEADERS = NetworkHeaders.Builder().set("User-Agent", WIKI_AGENT).build()

/** A picture that failed for a passing reason is asked for again this many times, a little later each time. */
private const val ART_RETRIES = 2
private const val ART_RETRY_MS = 2_500L

/** How long a picture fades in. The TV sets 0: each fading card is a redraw per frame, a row at a time. */
var ArtFadeMs = 300

/**
 * Metahub has a poster for nearly every title and a backdrop for far fewer, and most wide cards ask
 * for the backdrop: one stands in for the other rather than leaving the card empty.
 */
internal fun otherArt(url: String?): String? = when {
    url == null -> null
    "images.metahub.space/background/" in url -> url.replace("/background/", "/poster/")
    "images.metahub.space/poster/" in url -> url.replace("/poster/", "/background/")
    else -> null
}

/** What to try for a picture, in order: the one asked for, the caller's [fallback], then [otherArt] of that. */
internal fun artCandidates(url: String, fallback: String?): List<String> =
    listOfNotNull(url, fallback, otherArt(fallback ?: url)).distinct()

/** A network image over a placeholder tinted from the name. */
@Composable
/** [fill]: a placeholder under the picture — skipped where the picture covers it anyway and the fill is pure GPU cost. */
fun Artwork(url: String?, name: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, fill: Boolean = true, fallback: String? = null) {
    val hue = remember(name) { hueOf(name).toFloat() }
    // Nothing would load: the card says what it is, as one with no picture to ask for does.
    var dead by remember(url, fallback) { mutableStateOf(false) }
    val named = url == null || (dead && fill)
    // The placeholder is a painter Coil draws only until the picture lands, not a fill
    // that stays painted underneath it forever after.
    Box(modifier.then(if (named) Modifier.background(Color.hsv(hue, 0.45f, 0.28f)) else Modifier)) {
        if (url != null && !dead) {
            val context = LocalContext.current
            // Episode stills 404 for much of anime: then the title's own art, and then its other picture.
            val candidates = remember(url, fallback) { artCandidates(url, fallback) }
            var at by remember(url, fallback) { mutableStateOf(0) }
            val src = candidates[at.coerceIn(0, candidates.lastIndex)]
            // A host that says slow down (429), or a connection that drops, used to leave the card blank
            // for as long as it stayed on screen: nothing asks twice by itself. The last picture there
            // is to try is asked for again a little later, twice; one that simply isn't there (404) is not.
            var tries by remember(src) { mutableStateOf(0) }
            var failed by remember(src) { mutableStateOf(false) }
            LaunchedEffect(failed, tries) {
                if (failed) {
                    kotlinx.coroutines.delay(ART_RETRY_MS * (tries + 1))
                    failed = false
                    tries++
                }
            }
            // Keyed: the same request again is no new request to the image loader.
            androidx.compose.runtime.key(tries) {
                AsyncImage(
                    // Wikimedia answers OkHttp's own User-Agent with 403: without this, no cast photo loads.
                    model = remember(src) { ImageRequest.Builder(context).data(src).httpHeaders(if ("wikimedia.org" in src) WIKI_HEADERS else ART_HEADERS).crossfade(ArtFadeMs).build() },
                    onError = { error ->
                        val gone = (error.result.throwable as? coil3.network.HttpException)?.response?.code == 404
                        when {
                            at < candidates.lastIndex -> at++
                            !gone && tries < ART_RETRIES -> failed = true
                            else -> dead = true
                        }
                    },
                    placeholder = if (fill) remember { ColorPainter(Ink.surface) } else null,
                    contentDescription = name,
                    contentScale = scale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (named) {
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

/** The IMDb rating on a poster: on art, so white on a dark plate in either theme. */
@Composable
fun ImdbMark(rating: String, modifier: Modifier = Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
    // Wrapped: some parents hand their children their own size as a minimum, and the plate would cover the poster.
    Row(modifier.wrapContentSize(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.72f), shape).padding(end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "IMDb",
            Modifier.background(Ink.imdb, shape).padding(horizontal = 4.dp, vertical = 1.dp),
            style = Type.meta.copy(color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black),
        )
        Text(rating, Modifier.padding(start = 4.dp), style = Type.meta.copy(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    }
}

/** The film's age rating, as a small outlined mark. */
@Composable
fun CertificateBadge(certificate: String, modifier: Modifier = Modifier) {
    Text(
        certificate,
        modifier.border(1.dp, Ink.muted, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = Type.meta.copy(color = Ink.muted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    )
}

/** A film seen through: here, on [at], or on an account, where the day isn't known. */
@Composable
fun WatchedBadge(at: Long?, modifier: Modifier = Modifier) {
    val date = at?.let { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it)) }
    Row(
        modifier.clip(CircleShape).background(Ink.good.copy(alpha = 0.14f)).padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The page's colour on the green: white was lost on the bright green of the dark theme.
        Box(Modifier.size(20.dp).clip(CircleShape).background(Ink.good), contentAlignment = Alignment.Center) {
            Lc(L.drawable.lucide_ic_check, tint = Ink.bg, size = 13.dp)
        }
        Text("Watched", Modifier.padding(start = 7.dp), style = Type.meta.copy(color = Ink.good, fontWeight = FontWeight.SemiBold))
        date?.let { Text("  ·  $it", style = Type.meta.copy(color = Ink.text.copy(alpha = 0.7f))) }
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

/**
 * A grainy gradient — a loud surface: [colors] (base, middle, glow) as a radial glow from
 * ([cx], [cy]), fractions of the size, with film grain in it. Warmer and quieter than a
 * glossy linear gradient. From the PDF app's Kit.kt, with the grain under the content
 * here, so text stays crisp and artwork clean.
 */
fun Modifier.glow(colors: List<Color>, cx: Float, cy: Float) = drawBehind {
    drawRect(colors[0])
    drawRect(Brush.radialGradient(listOf(colors[2], colors[1], colors[0]), androidx.compose.ui.geometry.Offset(size.width * cx, size.height * cy), size.maxDimension * 0.95f))
    drawRect(grainBrush)
}

/** Film grain on a surface, under its content: after `.background(…)`. */
fun Modifier.grain() = drawBehind { drawRect(grainBrush) }

/** [glow]'s colours for the accent: a deep base, the red itself, and a warm light rising in it. */
fun accentGlow() = listOf(
    androidx.compose.ui.graphics.lerp(Ink.accent, Color.Black, 0.45f),
    Ink.accent,
    androidx.compose.ui.graphics.lerp(Ink.accent, Color(0xFFFF8A4C), 0.6f),
)

/** Fine black-and-white noise, at most 22/255 opaque, tiled. */
private val grainBrush: androidx.compose.ui.graphics.ShaderBrush by lazy {
    val n = 192
    val r = kotlin.random.Random(42)
    val px = IntArray(n * n) {
        val v = if (r.nextBoolean()) 255 else 0
        android.graphics.Color.argb(r.nextInt(0, 22), v, v, v)
    }
    androidx.compose.ui.graphics.ShaderBrush(
        androidx.compose.ui.graphics.ImageShader(
            android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap(),
            androidx.compose.ui.graphics.TileMode.Repeated,
            androidx.compose.ui.graphics.TileMode.Repeated,
        ),
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

fun colorOf(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Ink.surface2)

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
