@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.Stremio
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.Type
import com.torrentviewer.core.WatchOrder
import com.torrentviewer.core.WatchOrders
import com.torrentviewer.core.WatchedEverywhere
import com.torrentviewer.core.heroScrims
import com.torrentviewer.core.nextUp
import com.torrentviewer.core.orderLine
import com.torrentviewer.core.runtimeLabel
import com.torrentviewer.core.totalRuntime
import com.torrentviewer.core.watchedFilms

val ORDER_SHAPE = RoundedCornerShape(10.dp)
val ORDER_LEFT = Safe.x + 12.dp

/** Everything seen so far, here or on a connected account. */
@Composable
fun seenFilms(): Set<String> {
    val history by History.items.collectAsState()
    val library by WatchedEverywhere.collectAsState(Stremio.library.value)
    return remember(history, library) { watchedFilms(history, library) }
}

/** The first posters, fanned like cards in a hand; the first film lies on top. */
@Composable
private fun FannedPosters(order: WatchOrder, modifier: Modifier = Modifier) {
    val posters = order.posters()
    val spread = listOf(0f, -10f, 10f)
    Box(modifier.size(width = 116.dp, height = 106.dp), contentAlignment = Alignment.Center) {
        posters.indices.reversed().forEach { i ->
            Artwork(
                posters[i], order.name,
                Modifier
                    .offset(x = (spread[i] * 2.6f).dp, y = if (i == 0) 0.dp else 5.dp)
                    .graphicsLayer { rotationZ = spread[i] }
                    .size(width = 62.dp, height = 93.dp)
                    .clip(RoundedCornerShape(5.dp)),
            )
        }
    }
}

/** A banner: the franchise's art darkened to the left under its name, its posters on the right. */
@Composable
fun OrderBanner(order: WatchOrder, seen: Set<String>) {
    val done = order.films.count { it in seen }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Artwork(order.art, order.name, Modifier.fillMaxSize(), fill = false)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.94f), 0.55f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black.copy(alpha = 0.25f))))
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.6f).padding(start = 14.dp)) {
            Text(order.name, style = Type.title.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(orderLine(order, seen), Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Color.White.copy(alpha = 0.72f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FannedPosters(order, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
        if (done > 0) ProgressLine(done.toFloat() / order.films.size, Modifier.align(Alignment.BottomStart))
    }
}

/** A small label over a film's art. */
@Composable
private fun ArtTag(text: String, colour: Color, modifier: Modifier = Modifier, words: Color = Color.White) {
    Text(
        text,
        modifier.background(colour, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        style = Type.overline.copy(color = words, letterSpacing = 0.6.sp),
    )
}

/** A film's place in its order, as a poster: its number on it, a tick once seen, a tag on the one open now. */
@Composable
private fun OrderPoster(number: Int, imdb: String, here: Boolean, watched: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    Focusable(onClick, Modifier.width(112.dp).aspectRatio(2f / 3f), ORDER_SHAPE, 1.07f, requester = requester) {
        Box(Modifier.fillMaxSize().background(Ink.surface)) {
            Artwork("https://images.metahub.space/poster/medium/$imdb/img", "Film $number", Modifier.fillMaxSize(), fill = false)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            if (watched && !here) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)))
            Text(
                "$number",
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
                style = Type.title.copy(color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp),
            )
            if (here) ArtTag("THIS FILM", Color.Black.copy(alpha = 0.6f), Modifier.align(Alignment.TopStart).padding(6.dp))
            if (watched) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Ink.good),
                contentAlignment = Alignment.Center,
            ) {
                Lc(L.drawable.lucide_ic_check, tint = Color.Black, size = 13.dp)
            }
        }
    }
}

/** On a film's page: the franchise it belongs to, every film of it in order, opened at this one. */
@Composable
fun FranchiseRail(order: WatchOrder, imdb: String, onAll: () -> Unit, onOpen: (Title) -> Unit, requester: FocusRequester?, modifier: Modifier = Modifier) {
    val seen = seenFilms()
    val at = order.films.indexOf(imdb)
    val done = order.films.count { it in seen }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = ORDER_LEFT, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(order.name, style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("Film ${at + 1} of ${order.films.size}", "$done watched".takeIf { done > 0 }).joinToString("  ·  "),
                    style = Type.meta.copy(color = Ink.faint),
                )
            }
            Spacer(Modifier.width(24.dp))
            Chip("Watch order", false, onClick = onAll, icon = L.drawable.lucide_ic_list)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll(ORDER_ROW_LEAD)) {
            LazyRow(
                Modifier.padding(start = ORDER_LEFT - ORDER_ROW_LEAD).focusRestorer(),
                state = rememberLazyListState((at - 1).coerceAtLeast(0)),
                contentPadding = PaddingValues(start = ORDER_ROW_LEAD, end = Safe.x),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(order.films, key = { _, id -> id }) { i, id ->
                    OrderPoster(i + 1, id, id == imdb, id in seen, if (id == imdb) requester else null) { if (id != imdb) onOpen(Title(id, "movie", "")) }
                }
            }
        }
    }
}

/** Room left of a focused card to grow into. */
val ORDER_ROW_LEAD = 18.dp

/** Every franchise, with a box to find one. */
@Composable
fun WatchOrdersScreen(onOpen: (WatchOrder) -> Unit) {
    val seen = seenFilms()
    var query by rememberSaveable { mutableStateOf("") }
    /** The card that opened a franchise, to come back to. */
    var last by rememberSaveable { mutableStateOf<String?>(null) }
    val aim = remember { last ?: WatchOrders.all.first().id }
    val target = remember { FocusRequester() }
    val shown = WatchOrders.all.filter { it.matches(query) }

    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberMinimalScroll(40.dp)) {
        LazyVerticalGrid(
            GridCells.Adaptive(270.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ORDER_LEFT, end = Safe.x, top = Safe.y + 12.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column {
                    Text("Watch orders", style = Type.display)
                    Text("${WatchOrders.all.size} franchises, in the right order", Modifier.padding(top = 2.dp, bottom = 14.dp), style = Type.meta.copy(color = Ink.faint))
                    Field(query, { query = it }, "Find a watch order", Modifier.widthIn(max = 480.dp), leading = L.drawable.lucide_ic_search, keyboardOnFocus = false)
                }
            }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "none") {
                Text("No watch order by that name", style = Type.meta.copy(color = Ink.faint))
            }
            items(shown, key = { it.id }) { order ->
                Focusable(
                    { last = order.id; onOpen(order) },
                    Modifier.fillMaxWidth().height(122.dp),
                    ORDER_SHAPE,
                    1.04f,
                    requester = if (order.id == aim) target else null,
                ) { OrderBanner(order, seen) }
            }
        }
    }
    target.claim()
}

/** One franchise: how far along, and its films along one row, the art behind following the remote. */
@Composable
fun WatchOrderScreen(order: WatchOrder, onOpen: (Title) -> Unit) {
    val seen = seenFilms()
    var byStory by rememberSaveable { mutableStateOf(false) }
    val ids = if (byStory && order.story.isNotEmpty()) order.story else order.films
    // One at a time, left to right: a 2GB television and a burst of parallel fetches do not mix.
    val loaded by produceState(emptyMap<String, Title>(), order.id) {
        for (id in order.films) Meta.title(id, "movie")?.let { value = value + (id to it) }
    }
    val next = nextUp(ids, seen)
    val done = ids.count { it in seen }
    /** The film that was opened, to come back to. */
    var last by rememberSaveable { mutableStateOf<String?>(null) }
    val aim = remember { last ?: next ?: ids.first() }
    val target = remember { FocusRequester() }
    var focused by remember { mutableStateOf(aim) }

    Box(Modifier.fillMaxSize()) {
        Artwork(
            loaded[focused]?.wide ?: "https://images.metahub.space/background/medium/$focused/img", order.name,
            Modifier.align(Alignment.TopEnd).fillMaxWidth(0.8f).fillMaxHeight(0.82f).heroScrims(), fill = false,
        )
        Column(Modifier.fillMaxSize().padding(top = Safe.y + 24.dp, bottom = Safe.y)) {
            Column(Modifier.padding(start = ORDER_LEFT, end = Safe.x).widthIn(max = 560.dp)) {
                Text(order.name, style = Type.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("${ids.size} films", totalRuntime(loaded.values.toList()), "more coming".takeIf { order.more }).joinToString("  ·  "),
                    Modifier.padding(top = 6.dp),
                    style = Type.meta.copy(color = Ink.muted),
                )
                if (done > 0) {
                    ProgressLine(done.toFloat() / ids.size, Modifier.padding(top = 14.dp).widthIn(max = 320.dp))
                    Text(
                        if (next == null) "All watched" else "$done of ${ids.size} watched",
                        Modifier.padding(top = 6.dp),
                        style = Type.meta.copy(color = if (next == null) Ink.good else Ink.faint),
                    )
                }
                if (order.story.isNotEmpty()) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("Release order", !byStory, onClick = { byStory = false })
                    Chip("Story order", byStory, onClick = { byStory = true })
                }
            }
            Spacer(Modifier.weight(1f))
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll(ORDER_ROW_LEAD)) {
                LazyRow(
                    Modifier.padding(start = ORDER_LEFT - ORDER_ROW_LEAD).focusRestorer(),
                    state = rememberLazyListState(ids.indexOf(aim).coerceAtLeast(0)),
                    contentPadding = PaddingValues(start = ORDER_ROW_LEAD, end = Safe.x),
                    horizontalArrangement = Arrangement.spacedBy(ORDER_ROW_LEAD),
                ) {
                    itemsIndexed(ids, key = { _, id -> id }) { i, id ->
                        val t = loaded[id] ?: Title(id, "movie", "")
                        FilmCard(i + 1, t, id in seen, id == next, if (id == aim) target else null, onFocus = { focused = id }) {
                            if (t.title.isNotEmpty()) {
                                last = id
                                onOpen(t)
                            }
                        }
                    }
                }
            }
            // TMDB's terms ask for these words wherever its data shows.
            if (order.fromTmdb) Text(
                "Collection data from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                Modifier.padding(start = ORDER_LEFT, top = 12.dp),
                style = Type.meta.copy(color = Ink.faint, fontSize = 11.sp),
            )
        }
    }
    target.claim()
}

/** One film, as a film: its backdrop, its title logo, and the number in the order large and outlined. All on art, so white. */
@Composable
private fun FilmCard(number: Int, title: Title, watched: Boolean, next: Boolean, requester: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(
        onClick,
        Modifier.width(290.dp).aspectRatio(16f / 9f),
        ORDER_SHAPE,
        1.06f,
        origin = TransformOrigin(0f, 0.5f),
        requester = requester,
        onFocus = onFocus,
    ) { focused ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Artwork(title.wide ?: "https://images.metahub.space/background/medium/${title.imdb}/img", title.title, Modifier.fillMaxSize(), fill = false)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
            if (watched) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
            Text(
                "$number",
                Modifier.align(Alignment.TopEnd).padding(end = 10.dp),
                style = Type.display.copy(color = Color.White.copy(alpha = 0.75f), fontSize = 48.sp, lineHeight = 54.sp, fontWeight = FontWeight.Black, drawStyle = Stroke(width = 3f)),
            )
            if (next) ArtTag("UP NEXT", Color.Black.copy(alpha = 0.55f), Modifier.align(Alignment.TopStart).padding(10.dp))
            else if (watched) ArtTag("WATCHED", Ink.good, Modifier.align(Alignment.TopStart).padding(10.dp), Color.Black)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.82f).padding(10.dp)) {
                if (title.title.isNotEmpty()) TitleMark(
                    title, Modifier.padding(bottom = 4.dp), maxHeight = 34.dp,
                    style = Type.title.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(title.year?.take(4), title.runtime?.let(::runtimeLabel)).joinToString("  ·  "),
                        style = Type.meta.copy(color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp),
                    )
                    title.rating?.let {
                        ArtTag("IMDb", Ink.imdb, Modifier.padding(start = 8.dp), Color.Black)
                        Text(it, Modifier.padding(start = 5.dp), style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp))
                    }
                }
            }
            if (focused) Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Lc(L.drawable.lucide_ic_play, tint = Color.Black, size = 17.dp)
            }
        }
    }
}
