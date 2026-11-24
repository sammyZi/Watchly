package com.torrentviewer.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private const val NAME = "WATCHLY"
private val RED = Color(0xFFE50914)
private val INK = Color(0xFF0B0B0C)
private val ANTON = FontFamily(Font(R.font.anton_regular))

// Everything in ms from the start, on one clock.
private const val REEL_IN_MS = 380f
private const val UNROLL_AT_MS = 260f
private const val UNROLL_MS = 900f
private const val FADE_AT_MS = 1700f
private const val FADE_MS = 300f
private const val TOTAL_MS = 2000
/** The reel's turn: quick while it pays the film out, slowing to rest once the name is out. Degrees. */
private const val SPIN_DEGREES = 720f

private val RISE = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
/** Film comes off a reel briskly and eases to a stop as the last letter clears it. */
private val UNROLL = CubicBezierEasing(0.33f, 0f, 0.15f, 1f)

/** Once per launch: coming back from the background or turning the phone is not a start. */
private var shown = false

/**
 * The phone's opening titles: a film reel comes up on the black and spins, paying out a red strip of film
 * with the name written along it — each letter coming off the reel as the strip unrolls past it — and the
 * reel slows to rest as the last one clears. Then all of it fades as the app shows under it. The TV keeps
 * its own (core Splash). [skip]: opened by a link or a file, which has somewhere to be.
 */
@Composable
fun FilmSplash(skip: Boolean) {
    var on by remember { mutableStateOf(!shown && !skip) }
    if (!on) return
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        shown = true
        clock.animateTo(TOTAL_MS.toFloat(), tween(TOTAL_MS, easing = LinearEasing))
        on = false
    }
    val unroll = { UNROLL.transform(((clock.value - UNROLL_AT_MS) / UNROLL_MS).coerceIn(0f, 1f)) }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - ((clock.value - FADE_AT_MS) / FADE_MS).coerceIn(0f, 1f) }
            .background(Color.Black)
            // Nothing reaches the app under it.
            .clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The strip leaves the reel at its middle, tucked under its rim: the reel is drawn over it.
            Box(contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier.padding(start = 48.dp).height(80.dp)
                        // Unrolled so far and no further: the film still on the reel isn't drawn.
                        .drawWithContent { clipRect(right = size.width * unroll()) { this@drawWithContent.drawContent() } }
                        // Square where it leaves the reel, rounded where it ends.
                        .background(RED, androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                        .drawWithContent {
                            drawContent()
                            // Whole holes only, spaced evenly from clear of the reel's rim to the rounded end:
                            // counted to fit, not run off the end part-cut.
                            val w = 9.dp.toPx()
                            val h = 6.dp.toPx()
                            val gap = 9.dp.toPx()
                            val inset = 8.dp.toPx()
                            val from = 52.dp.toPx()
                            val to = size.width - 14.dp.toPx()
                            val n = ((to - from + gap) / (w + gap)).toInt().coerceAtLeast(1)
                            val spare = (to - from) - (n * w + (n - 1) * gap)
                            for (k in 0 until n) {
                                val x = from + spare / 2 + k * (w + gap)
                                drawRoundRect(INK, Offset(x, inset), Size(w, h), CornerRadius(2.dp.toPx()))
                                drawRoundRect(INK, Offset(x, size.height - inset - h), Size(w, h), CornerRadius(2.dp.toPx()))
                            }
                        }
                        // The lettering clear of the reel, which reaches 48dp past where the strip starts.
                        .padding(start = 62.dp, end = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        NAME.forEachIndexed { i, letter ->
                            Text(
                                letter.toString(),
                                Modifier.graphicsLayer {
                                    // Each letter settles as the strip carrying it comes off the reel.
                                    val at = (unroll() * (NAME.length + 1) - i - 0.5f).coerceIn(0f, 1f)
                                    alpha = at
                                    translationX = -18.dp.toPx() * (1f - RISE.transform(at))
                                },
                                style = TextStyle(fontFamily = ANTON, fontSize = 42.sp, lineHeight = 50.sp, color = Color.White, letterSpacing = 1.5.sp),
                            )
                        }
                    }
                }
                Reel(
                    Modifier.size(96.dp)
                        .graphicsLayer {
                            val up = RISE.transform((clock.value / REEL_IN_MS).coerceIn(0f, 1f))
                            alpha = up
                            scaleX = 0.7f + 0.3f * up
                            scaleY = 0.7f + 0.3f * up
                        },
                    turn = { SPIN_DEGREES * unroll() },
                )
            }
        }
    }
}

/**
 * The film reel of the logo, drawn as reels are in a mark, by geometry alone: a white flange inside a red rim
 * with a hair of black between them, five round windows evenly on a ring, and a hub — a white boss on a red
 * ring. The windows are kept small (a fifth of the radius): large ones read as a bowling ball. Turned by [turn].
 */
@Composable
private fun Reel(modifier: Modifier, turn: () -> Float) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(RED, r, c)
        drawCircle(INK, r * 0.9f, c)
        drawCircle(Color.White, r * 0.86f, c)
        rotate(turn(), c) {
            val ring = r * 0.5f
            for (k in 0 until 5) {
                val a = Math.toRadians(-90.0 + k * 72.0)
                drawCircle(INK, r * 0.19f, Offset(c.x + ring * cos(a).toFloat(), c.y + ring * sin(a).toFloat()))
            }
        }
        drawCircle(RED, r * 0.17f, c)
        drawCircle(Color.White, r * 0.08f, c)
    }
}
