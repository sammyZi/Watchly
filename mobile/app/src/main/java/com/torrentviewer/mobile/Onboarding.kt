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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

/** The onboarding's pages, in order. */
private const val WELCOME = 0
private const val GENRES_STEP = 1
private const val SIGN_IN = 2

/**
 * The first launch: one welcome over a drifting wall of what is trending, then the genres the home
 * screen leads with, then signing in to Stremio — which brings the library, Continue Watching and
 * addons — or skipping it. Sign in opens Stremio's own page; no password comes here. Without a
 * network the pages stand on their gradient alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Onboarding(onDone: () -> Unit) {
    val films by produceState(emptyList<Title>()) { value = Meta.catalog("movie") }
    val series by produceState(emptyList<Title>()) { value = Meta.catalog("tv") }
    val posters = (films.take(14) + series.take(14)).filter { it.poster != null }
    var step by remember { mutableStateOf(WELCOME) }
    /** The same list as Settings → Genres on the home screen; taken again shows what was picked. */
    var picked by remember { mutableStateOf(com.torrentviewer.core.SettingsStore.value.value.genres.toSet()) }
    var code by remember { mutableStateOf<com.torrentviewer.core.DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val browser = androidx.compose.ui.platform.LocalUriHandler.current
    BackHandler(step > WELCOME) {
        job?.cancel()
        step--
    }

    fun keepGenres() {
        val s = com.torrentviewer.core.SettingsStore.value.value
        com.torrentviewer.core.SettingsStore.save(s.copy(genres = com.torrentviewer.core.GENRES.filter { it in picked }))
        step = SIGN_IN
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
        if (step == SIGN_IN) Backdrop(films.firstOrNull { it.backdrop != null } ?: films.firstOrNull()) else PosterWall(posters)
        // Dark at the foot for the words, and a little at the head for Skip.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.18f to Color.Transparent, 0.45f to Color.Transparent, 0.78f to Color.Black.copy(alpha = 0.88f), 1f to Color.Black),
            ),
        )

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = EDGE, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WATCHLY", Modifier.weight(1f), style = Type.title.copy(color = Color(0xFFE50914), fontWeight = FontWeight.Black, letterSpacing = 2.sp))
            // Skip past the genres keeps the home screen as it was; past sign-in, into the app.
            if (step > WELCOME) Tap({ if (step == GENRES_STEP) step = SIGN_IN else { job?.cancel(); onDone() } }, shape = RoundedCornerShape(8.dp)) {
                Text("Skip", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = Type.label.copy(color = Color.White.copy(alpha = 0.85f)))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = EDGE + 8.dp, vertical = 24.dp).widthIn(max = 520.dp)) {
            AnimatedContent(
                step,
                transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(160)) },
                label = "step",
            ) { page ->
                Column {
                    if (page == WELCOME) {
                        Text("Your media player", style = Type.hero.copy(color = Color.White, fontSize = 34.sp, lineHeight = 38.sp))
                        Text(
                            "Browse trending films, series and anime, and play them from the addons and servers you choose. Watchly comes with none.",
                            Modifier.padding(top = 10.dp), style = Type.body.copy(color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 23.sp),
                        )
                        Button("Get started", { step = GENRES_STEP }, Modifier.padding(top = 26.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_arrow_right, primary = true)
                    } else if (page == GENRES_STEP) {
                        Text("What do you like?", style = Type.hero.copy(color = Color.White, fontSize = 34.sp, lineHeight = 38.sp))
                        Text(
                            "Pick a few — your home screen leads with them. Change them any time in Settings.",
                            Modifier.padding(top = 10.dp, bottom = 16.dp), style = Type.body.copy(color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 23.sp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.torrentviewer.core.GENRES.forEach { g ->
                                val on = g in picked
                                // The tour's own pills: white on the dark page, whatever the phone's theme.
                                Tap({ picked = if (on) picked - g else picked + g }, shape = CircleShape) {
                                    Text(
                                        g,
                                        Modifier.background(if (on) Color(0xFFE50914) else Color.White.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 8.dp),
                                        style = Type.label.copy(color = Color.White, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                                    )
                                }
                            }
                        }
                        Button(
                            if (picked.isEmpty()) "Continue" else "Continue with ${picked.size}",
                            ::keepGenres, Modifier.padding(top = 22.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_arrow_right, primary = true,
                        )
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
