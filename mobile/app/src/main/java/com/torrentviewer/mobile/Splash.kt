package com.torrentviewer.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Type

private const val NAME = "WATCHLY"
private val RED = Color(0xFFE50914)

// Everything in ms from the start, on one clock.
private const val RISE_MS = 700f
private const val RISE_STAGGER_MS = 55f
private const val EXIT_AT_MS = 1050f
private const val EXIT_MS = 360f
private const val EXIT_STAGGER_MS = 25f
private const val FADE_AT_MS = 1250f
private const val FADE_MS = 300f
private const val TOTAL_MS = 1560

/** Fast off the mark and a long settle, then the opposite to leave: the motion is all there is to look at. */
private val RISE = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val EXIT = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)

/** Once per launch: coming back from the background or turning the phone is not a start. */
private var shown = false

/**
 * The name, flat on black, each letter rising from behind an unseen line and leaving through the
 * top of it, while the app loads underneath. No glow or zoom: only the letters and their timing.
 * [skip]: opened by a link or a file, which has somewhere to be.
 */
@Composable
fun Splash(skip: Boolean) {
    var on by remember { mutableStateOf(!shown && !skip) }
    if (!on) return
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        shown = true
        clock.animateTo(TOTAL_MS.toFloat(), tween(TOTAL_MS, easing = LinearEasing))
        on = false
    }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - ((clock.value - FADE_AT_MS) / FADE_MS).coerceIn(0f, 1f) }
            .background(Color.Black)
            // Nothing reaches the app under it.
            .clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.clipToBounds(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            NAME.forEachIndexed { i, letter ->
                Text(
                    letter.toString(),
                    Modifier.graphicsLayer {
                        val rise = RISE.transform(((clock.value - i * RISE_STAGGER_MS) / RISE_MS).coerceIn(0f, 1f))
                        val exit = EXIT.transform(((clock.value - EXIT_AT_MS - i * EXIT_STAGGER_MS) / EXIT_MS).coerceIn(0f, 1f))
                        translationY = size.height * (1f - rise - exit)
                    },
                    style = Type.title.copy(color = RED, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 50.sp),
                )
            }
        }
    }
}
