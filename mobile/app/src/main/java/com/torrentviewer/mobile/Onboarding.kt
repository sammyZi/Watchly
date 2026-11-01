package com.torrentviewer.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** Settings asks for the tour again; the shell shows it until it is done. */
var tourAgain by androidx.compose.runtime.mutableStateOf(false)

/**
 * The first launch: one welcome over a drifting wall of what is trending, then signing in to Stremio —
 * which brings the library, Continue Watching and addons — or skipping it. Sign in opens Stremio's own
 * page; no password comes here. Without a network the pages stand on their gradient alone.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val films by produceState(emptyList<Title>()) { value = Meta.catalog("movie") }
    val series by produceState(emptyList<Title>()) { value = Meta.catalog("tv") }
    val posters = (films.take(14) + series.take(14)).filter { it.poster != null }
    var signIn by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<com.torrentviewer.core.DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val browser = androidx.compose.ui.platform.LocalUriHandler.current
    BackHandler(signIn) {
        job?.cancel()
        signIn = false
    }

    fun connect() {
        note = null
        job?.cancel()
        job = scope.launch {
            try {
                val c = com.torrentviewer.core.Stremio.start()
                code = c
                runCatching { browser.openUri(c.uriComplete) }
                // Signed in: the account's addons and library are on their way; the app opens.
                if (com.torrentviewer.core.Stremio.await(c)) onDone() else note = "That code ran out — try again."
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) note = e.message ?: "Couldn't reach Stremio."
            } finally {
                code = null
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        if (signIn) Backdrop(films.firstOrNull { it.backdrop != null } ?: films.firstOrNull()) else PosterWall(posters)
        // Dark at the foot for the words, and a little at the head for Skip.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.18f to Color.Transparent, 0.45f to Color.Transparent, 0.78f to Color.Black.copy(alpha = 0.88f), 1f to Color.Black),
            ),
        )

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = EDGE, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WATCHLY", Modifier.weight(1f), style = Type.title.copy(color = Color(0xFFE50914), fontWeight = FontWeight.Black, letterSpacing = 2.sp))
            if (signIn) Tap({ job?.cancel(); onDone() }, shape = RoundedCornerShape(8.dp)) {
                Text("Skip", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = Type.label.copy(color = Color.White.copy(alpha = 0.85f)))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = EDGE + 8.dp, vertical = 24.dp).widthIn(max = 520.dp)) {
            AnimatedContent(
                signIn,
                transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(160)) },
                label = "step",
            ) { second ->
                Column {
                    if (!second) {
                        Text("Your media player", style = Type.hero.copy(color = Color.White, fontSize = 34.sp, lineHeight = 38.sp))
                        Text(
                            "Browse trending films, series and anime, and play them from the addons and servers you choose. Watchly comes with none.",
                            Modifier.padding(top = 10.dp), style = Type.body.copy(color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 23.sp),
                        )
                        Button("Get started", { signIn = true }, Modifier.padding(top = 26.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_arrow_right, primary = true)
                    } else {
                        Text("Sign in to Stremio", style = Type.hero.copy(color = Color.White, fontSize = 34.sp, lineHeight = 38.sp))
                        Text(
                            "Your library, Continue Watching and addons come with you, and stay in step both ways. You sign in on Stremio's own page — your password never comes to Watchly.",
                            Modifier.padding(top = 10.dp), style = Type.body.copy(color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 23.sp),
                        )
                        code?.let { c ->
                            Text(
                                "Sign in and approve on the page that opened — this notices by itself. Code ${c.userCode}",
                                Modifier.padding(top = 14.dp), style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f)),
                            )
                        }
                        note?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f))) }
                        Button(
                            if (code != null) "Open Stremio again" else "Sign in to Stremio",
                            { code?.let { c -> runCatching { browser.openUri(c.uriComplete) } } ?: connect() },
                            Modifier.padding(top = 22.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_link, primary = true,
                        )
                        Button("Skip for now", { job?.cancel(); onDone() }, Modifier.padding(top = 10.dp).fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Three rows of posters drifting opposite ways, tilted, as a wall of what is on. */
@Composable
private fun PosterWall(posters: List<Title>) {
    if (posters.isEmpty()) return Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A0A0C), Color(0xFF0A0A0A)))))
    val drift = rememberInfiniteTransition(label = "wall")
    val t by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    val rows = posters.chunked((posters.size + 2) / 3).take(3)
    Column(
        Modifier.fillMaxSize().graphicsLayer { rotationZ = -8f; scaleX = 1.35f; scaleY = 1.35f },
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        rows.forEachIndexed { r, row ->
            val card = 112.dp
            val step = card + 10.dp
            // Twice over, so the second copy is in place as the first slides out: a seamless loop.
            val span = step * row.size
            val shift = if (r % 2 == 0) -span * t else -span * (1 - t)
            Row(
                Modifier.requiredWidth(span * 2).graphicsLayer { translationX = shift.toPx() + span.toPx() / 2 },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (row + row).forEach { title ->
                    Artwork(title.poster, title.title, Modifier.width(card).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                }
            }
        }
    }
}

/** A backdrop drifting slowly closer — the Ken Burns of a trailer's first seconds. */
@Composable
private fun Backdrop(title: Title?) {
    if (title == null) return Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF151515), Color(0xFF0A0A0A)))))
    val zoom = rememberInfiniteTransition(label = "zoom")
    val s by zoom.animateFloat(1.04f, 1.16f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "scale")
    Box(Modifier.fillMaxSize()) {
        Artwork(title.wide, title.title, Modifier.fillMaxSize().graphicsLayer { scaleX = s; scaleY = s }, fill = false)
        Text(
            title.title,
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = EDGE, top = 56.dp),
            style = Type.meta.copy(color = Color.White.copy(alpha = 0.6f)),
        )
    }
}
