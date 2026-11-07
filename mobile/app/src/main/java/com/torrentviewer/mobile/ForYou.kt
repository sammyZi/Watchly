package com.torrentviewer.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.GENRES
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kids
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Note
import com.torrentviewer.core.ImdbMark
import com.torrentviewer.core.Pick
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.TOP_SHELF
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.Type
import com.torrentviewer.core.imdbScore

/** One suggestion, big: its backdrop and logo, its IMDb rating, and whose fault it is. All on art, so white. */
@Composable
private fun PickCard(pick: Pick, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val t = pick.title
    Tap(onClick, modifier, shape) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black)) {
            Artwork(t.wide, t.title, Modifier.fillMaxSize(), fill = false)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
                TitleMark(t, Modifier.padding(bottom = 6.dp), maxHeight = 42.dp, style = Type.title.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    t.rating?.takeIf { it.isNotBlank() }?.let { ImdbMark(it, Modifier.padding(end = 8.dp)) }
                    Text(
                        "Because you watched ${pick.because}",
                        style = Type.meta.copy(color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun PickedRow(state: LazyListState, picks: List<Pick>, onOpen: (Title) -> Unit) {
    val width = if (isWide()) 340.dp else 290.dp
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(picks, key = { it.title.imdb }) { pick -> PickCard(pick, { onOpen(pick.title) }, Modifier.width(width)) }
    }
}

/** A chart: each poster stands on its rank, large and outlined. */
@Composable
fun TopRow(state: LazyListState, titles: List<Title>, onOpen: (Title) -> Unit) {
    val poster = posterWidth()
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(titles, key = { _, t -> t.imdb }) { i, t ->
            Box {
                Text(
                    "${i + 1}",
                    Modifier.offset(y = poster * 1.5f - 78.dp),
                    style = Type.display.copy(color = Ink.muted, fontSize = 72.sp, lineHeight = 84.sp, fontWeight = FontWeight.Black, drawStyle = Stroke(width = 3f)),
                )
                TitleCard(
                    t.poster ?: t.wide, t.title, { onOpen(t) },
                    Modifier.padding(start = if (i < 9) 34.dp else 62.dp).width(poster),
                    info = t.year?.take(4), rating = t.rating,
                )
            }
        }
    }
}

private val MINIMUMS = listOf<Pair<Double?, String>>(null to "Any rating", 7.0 to "IMDb 7+", 8.0 to "IMDb 8+", 9.0 to "IMDb 9+")

/** Stands for a genre: all the viewer's chosen ones together. */
private const val YOUR_GENRES = "your genres"

/** The best by IMDb rating: films or series, the viewer's genres or any one, and how high to start. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TopScreen(startKind: String, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings by SettingsStore.value.collectAsState()
    var kind by rememberSaveable { mutableStateOf(startKind) }
    var genre by rememberSaveable { mutableStateOf(YOUR_GENRES.takeIf { settings.genres.isNotEmpty() }) }
    var atLeast by rememberSaveable { mutableStateOf<Double?>(null) }
    val genres = remember(settings.genres) { (settings.genres + GENRES).distinct() }
    val shelf = TOP_SHELF + genre.orEmpty()
    var titles by remember(kind, genre) { mutableStateOf(if (genre == YOUR_GENRES) Meta.peekTopOf(kind, settings.genres) else Meta.peekCatalog(kind, shelf)) }
    LaunchedEffect(kind, genre) {
        if (titles.isNullOrEmpty()) titles = if (genre == YOUR_GENRES) Meta.topOf(kind, settings.genres) else Meta.catalog(kind, shelf)
    }
    val shown = remember(titles, atLeast) { Kids.only(titles.orEmpty()).filter { t -> atLeast.let { it == null || (t.imdbScore ?: 0.0) >= it } } }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(104.dp),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Heading("Top on IMDb", Modifier.padding(start = 48.dp))
                    // Small enough for one line; wrapped, not scrolled, where it still doesn't fit: a menu opens
                    // under its chip, so none may sit off the screen's edge.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dropdown(if (kind == "tv") "Series" else "Films", listOf("Films", "Series"), { kind = if (it == "Series") "tv" else "movie" })
                        Dropdown(MINIMUMS.first { it.first == atLeast }.second, MINIMUMS.map { it.second }, { picked -> atLeast = MINIMUMS.first { it.second == picked }.first })
                        Dropdown(
                            if (genre == YOUR_GENRES) "Your genres" else genre ?: "All genres",
                            listOfNotNull("Your genres".takeIf { settings.genres.isNotEmpty() }, "All genres") + genres,
                            { genre = when (it) { "Your genres" -> YOUR_GENRES; "All genres" -> null; else -> it } },
                        )
                    }
                }
            }
            if (titles == null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = 40.dp), contentAlignment = Alignment.Center) { Spinner(size = 36.dp) } }
            else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Note("Nothing rated that high here.") }
            itemsIndexed(shown, key = { _, t -> t.imdb }) { i, t ->
                Box {
                    PosterCard(t, { onOpen(t) }, sub = t.year?.take(4))
                    ArtTag("${i + 1}", Color.Black.copy(alpha = 0.72f), Modifier.padding(6.dp))
                }
            }
        }
        BackButton(onBack)
    }
}
