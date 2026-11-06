@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.tv

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.GENRES
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kids
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Note
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.TOP_SHELF
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.imdbScore

/** Stands for a genre: all the viewer's chosen ones together. */
private const val YOUR_GENRES = "your genres"

private val MINIMUMS = listOf<Pair<Double?, String>>(null to "Any rating", 7.0 to "IMDb 7+", 8.0 to "IMDb 8+", 9.0 to "IMDb 9+")

/** The best by IMDb rating: films or series, the viewer's genres or any one, and how high to start. */
@Composable
fun TopScreen(startKind: String, onOpen: (Title) -> Unit) {
    val settings by SettingsStore.value.collectAsState()
    var kind by rememberSaveable { mutableStateOf(startKind) }
    var genre by rememberSaveable { mutableStateOf(YOUR_GENRES.takeIf { settings.genres.isNotEmpty() }) }
    var atLeast by rememberSaveable { mutableStateOf<Double?>(null) }
    /** The card that opened a title, to come back to. */
    var last by rememberSaveable { mutableStateOf<String?>(null) }
    val aim = remember { last }
    val target = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val genres = remember(settings.genres) { (settings.genres + GENRES).distinct() }
    val shelf = TOP_SHELF + genre.orEmpty()
    var titles by remember(kind, genre) { mutableStateOf(if (genre == YOUR_GENRES) Meta.peekTopOf(kind, settings.genres) else Meta.peekCatalog(kind, shelf)) }
    LaunchedEffect(kind, genre) {
        if (titles.isNullOrEmpty()) titles = if (genre == YOUR_GENRES) Meta.topOf(kind, settings.genres) else Meta.catalog(kind, shelf)
    }
    val shown = remember(titles, atLeast) { Kids.only(titles.orEmpty()).filter { t -> atLeast.let { it == null || (t.imdbScore ?: 0.0) >= it } } }

    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberMinimalScroll(60.dp)) {
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp),
            Modifier.fillMaxSize(),
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(start = ORDER_LEFT, end = Safe.x, top = Safe.y + 12.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Top on IMDb", style = Type.display)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("Films", kind == "movie", onClick = { kind = "movie" }, requester = first)
                        Chip("Series", kind == "tv", onClick = { kind = "tv" })
                        MINIMUMS.forEach { (min, label) -> Chip(label, atLeast == min, onClick = { atLeast = min }) }
                    }
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (settings.genres.isNotEmpty()) item(key = YOUR_GENRES) { Chip("Your genres", genre == YOUR_GENRES, onClick = { genre = YOUR_GENRES }) }
                        item(key = "all") { Chip("All genres", genre == null, onClick = { genre = null }) }
                        items(genres, key = { it }) { g -> Chip(g, genre == g, onClick = { genre = g }) }
                    }
                }
            }
            if (titles == null) item(span = { GridItemSpan(maxLineSpan) }, key = "wait") { Spinner(size = 36.dp) }
            else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "none") { Note("Nothing rated that high here.") }
            itemsIndexed(shown, key = { _, t -> t.imdb }) { i, t ->
                PosterCard(
                    t,
                    { last = t.imdb; onOpen(t) },
                    sub = listOfNotNull("#${i + 1}", t.year?.take(4)).joinToString("  ·  "),
                    requester = if (t.imdb == aim) target else null,
                )
            }
        }
    }
    if (aim != null) target.claim(titles != null) else first.claim()
}
