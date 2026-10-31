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

/** One page of the tour: what it says, and what it is drawn over. */
private data class Step(val title: String, val body: String, val art: Art, val tags: List<String> = emptyList())

private enum class Art { WALL, FILM, SERIES, WALL_AGAIN }

private val STEPS = listOf(
    Step("Everything to watch", "Trending films, series and anime — browse, search and press play in seconds.", Art.WALL),
    Step("Pick your quality", "Every release with its size and seeds, sorted for you before you press play.", Art.FILM, listOf("4K", "1080p", "720p")),
    Step("A profile for everyone", "Each with its own history and My List. A Kids profile shows family films only.", Art.SERIES),
    Step("Pick up anywhere", "Continue Watching follows you to your TV and PC. Connect Simkl or TorBox any time in Settings.", Art.WALL_AGAIN),
)

/**
 * The first launch: four pages over what is trending right now — a drifting wall of posters, then
 * a film's and a series' backdrops — so the tour looks like the app it introduces. Skip ends it;
 * without a network the pages stand on their gradient alone.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val films by produceState(emptyList<Title>()) { value = Meta.catalog("movie") }
    val series by produceState(emptyList<Title>()) { value = Meta.catalog("tv") }
    val posters = (films.take(14) + series.take(14)).filter { it.poster != null }
    val pager = rememberPagerState { STEPS.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == STEPS.lastIndex
    BackHandler(pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            when (STEPS[page].art) {
                Art.WALL -> PosterWall(posters)
                Art.FILM -> Backdrop(films.firstOrNull { it.backdrop != null } ?: films.firstOrNull())
                Art.SERIES -> Backdrop(series.firstOrNull { it.backdrop != null } ?: series.firstOrNull())
                Art.WALL_AGAIN -> PosterWall(posters.reversed())
            }
        }
        // Dark at the foot for the words, and a little at the head for Skip.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.18f to Color.Transparent, 0.45f to Color.Transparent, 0.78f to Color.Black.copy(alpha = 0.88f), 1f to Color.Black),
            ),
        )

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = EDGE, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WATCHLY", Modifier.weight(1f), style = Type.title.copy(color = Color(0xFFE50914), fontWeight = FontWeight.Black, letterSpacing = 2.sp))
            if (!last) Tap(onDone, shape = RoundedCornerShape(8.dp)) {
                Text("Skip", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = Type.label.copy(color = Color.White.copy(alpha = 0.85f)))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = EDGE + 8.dp, vertical = 24.dp).widthIn(max = 520.dp)) {
            // The words change with the page; the pictures move under them on their own.
            AnimatedContent(
                STEPS[pager.currentPage],
                transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(160)) },
                label = "step",
            ) { step ->
                Column {
                    if (step.tags.isNotEmpty()) Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        step.tags.forEachIndexed { i, tag ->
                            Text(
                                tag,
                                Modifier.clip(RoundedCornerShape(6.dp)).background(if (i == 0) Color(0xFFE50914) else Color.White.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                                style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                    Text(step.title, style = Type.hero.copy(color = Color.White, fontSize = 34.sp, lineHeight = 38.sp))
                    Text(step.body, Modifier.padding(top = 10.dp), style = Type.body.copy(color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 23.sp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(STEPS.size) { i ->
                        val on = i == pager.currentPage
                        Box(Modifier.size(if (on) 22.dp else 7.dp, 7.dp).clip(CircleShape).background(if (on) Color(0xFFE50914) else Color.White.copy(alpha = 0.35f)))
                    }
                }
                Button(
                    if (last) "Get started" else "Next",
                    { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    Modifier.widthIn(min = 140.dp),
                    icon = if (last) L.drawable.lucide_ic_play else L.drawable.lucide_ic_arrow_right,
                    primary = true,
                )
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
