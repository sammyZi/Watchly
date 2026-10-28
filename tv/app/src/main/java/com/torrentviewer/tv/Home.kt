@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.tv
import com.torrentviewer.core.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class Shelf(val title: String, val kind: String, val genre: String? = null, val note: String? = null) {
    val key get() = "$kind:${genre.orEmpty()}"
}

/** The rails every home page ends with, after the viewer's own genres and picks. */
private val SHELVES = listOf(
    Shelf("Trending Movies", "movie"),
    Shelf("Popular Series", "tv"),
    Shelf("Anime", "tv", ANIME_GENRE),
    Shelf("Sci-Fi", "movie", "Sci-Fi"),
    Shelf("Thrillers", "movie", "Thriller"),
    Shelf("Horror", "movie", "Horror"),
    Shelf("Mystery", "movie", "Mystery"),
    Shelf("Action", "movie", "Action"),
    Shelf("Comedy", "movie", "Comedy"),
    Shelf("Crime", "movie", "Crime"),
    Shelf("Animation", "movie", "Animation"),
    Shelf("Fantasy", "movie", "Fantasy"),
    Shelf("Adventure", "movie", "Adventure"),
    Shelf("Documentaries", "movie", "Documentary"),
    Shelf("Drama Series", "tv", "Drama"),
    Shelf("Crime Series", "tv", "Crime"),
)

/** Survives opening a title, so coming back lands on the same row, same card. */
class HomeState {
    val list = LazyListState()
    val rows = HashMap<String, LazyListState>()
    val loaded = mutableStateMapOf<String, List<Title>>()
    var hero by mutableStateOf<Title?>(null)
    /** The card the remote is on. Read only by the billboard's loader, never by the page:
     *  a page that read it redrew every row on every press. */
    var aim by mutableStateOf<Title?>(null)
    /** "row/imdb" of the card that opened a title, to hand focus back to. */
    var restore: String? = null
    var picks by mutableStateOf<List<Shelf>>(emptyList())

    fun row(key: String) = rows.getOrPut(key) { LazyListState() }
}

private fun inProgress(item: Item): Boolean {
    val r = item.ratio
    return r > 0.02 && r < 0.95
}

/** "S2:E4 · 18m left" under a Continue Watching card. */
private fun resumeStamp(item: Item): String? {
    val index = item.lastFileIndex ?: return null
    val file = item.files?.find { it.index == index }
    val ep = parseEpisode(file?.name ?: item.title)?.let { "S${it.season}:E${it.episode}" }
    val leftText = item.durations[index]?.let { d -> item.positions[index]?.let { left(d - it) } }
    return listOfNotNull(ep, leftText).joinToString("  ·  ").ifEmpty { null }
}

@Composable
fun HomeScreen(state: HomeState, watch: Watch, onOpen: (Title) -> Unit) {
    val scope = rememberCoroutineScope()
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()
    val settings by SettingsStore.value.collectAsState()
    val online by Net.online.collectAsState()

    // Shelves inferred from what has been watched: the lead pick says so.
    LaunchedEffect(history.size, saved.size, online) {
        val seen = (history.mapNotNull { h -> h.imdb?.let { it to (h.kind ?: "movie") } } + saved.map { it.imdb to it.kind }).take(30)
        state.picks = if (seen.isEmpty()) emptyList() else Meta.watchedGenres(seen).mapIndexed { i, (genre, kind) ->
            Shelf(
                if (i == 0) "Because you watched ${genre.lowercase()}" else "More ${genre.lowercase()}",
                kind,
                genre,
            )
        }
    }
    val chosen = settings.genres.map { Shelf(if (it == ANIME_GENRE) "Anime" else it, kindOfGenre(it), it) }
    val shelves = remember(chosen, state.picks) {
        val taken = chosen.map { it.key }.toMutableSet()
        val rest = state.picks.filter { taken.add(it.key) }
        chosen + rest + SHELVES.filter { it.key !in taken }
    }

    // Top-down, not all at once: a 2GB television and a burst of parallel fetches do not mix.
    LaunchedEffect(shelves, online) {
        for (shelf in shelves) {
            if (state.loaded[shelf.key].isNullOrEmpty()) {
                val titles = Meta.peekCatalog(shelf.kind, shelf.genre) ?: Meta.catalog(shelf.kind, shelf.genre)
                if (titles.isNotEmpty()) state.loaded[shelf.key] = titles
            }
        }
    }

    // The billboard lags focus by a beat, and swaps only once the next title's full entry,
    // backdrop and logo are in memory — so it cross-fades picture to picture instead of
    // fading to black and popping the art in a moment later.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        snapshotFlow { state.aim }.collectLatest { t ->
            t ?: return@collectLatest
            delay(220)
            val full = withTimeoutOrNull(1500) { Meta.title(t.imdb, t.kind) } ?: t
            withTimeoutOrNull(1500) {
                val loader = SingletonImageLoader.get(context)
                listOfNotNull(full.wide, full.logo).forEach { loader.execute(ImageRequest.Builder(context).data(it).build()) }
            }
            state.hero = full
        }
    }

    // One card per title — its newest started episode or release — while that one is part-watched.
    val resumable = latestPerTitle(history).filter(::inProgress).take(8)
    val watchlist = saved.filter { it.list == "later" }.map { it.asTitle() }
    val fallback = { shelves.firstNotNullOfOrNull { state.loaded[it.key]?.firstOrNull() } ?: watchlist.firstOrNull() }
    // Focus lands on the first card the first time only; afterwards it goes back to the opened one.
    val firstKey = remember { if (state.restore == null) "first" else null }
    val aim: (Title) -> Unit = { state.aim = it }

    Box(Modifier.fillMaxSize()) {
        Billboard(state, fallback, Modifier.fillMaxWidth().fillMaxHeight(0.62f))
        // The column never scrolls itself on focus: each row pins itself to the top when
        // entered, and the two fighting made the page lurch.
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoScroll) {
        LazyColumn(
            state = state.list,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.47f),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // The row the remote is on sits at the top of the list, the one above scrolls
            // off: half a row of cards peeking under the synopsis looked broken.
            fun pin(index: Int) = { scope.launch { state.list.animateScrollToItem(index) }; Unit }
            var at = 0
            if (resumable.isNotEmpty()) item(key = "resume") {
                Rail("Continue Watching", pin(0)) {
                    ResumeRow(state, resumable, firstKey != null, watch, onFocus = aim)
                }
            }.also { at++ }
            val listAt = at
            if (watchlist.isNotEmpty()) item(key = "list") {
                Rail("My List", pin(listAt)) {
                    TitleRow(state, "list", watchlist, firstKey != null && resumable.isEmpty(), onFocus = aim, onOpen = onOpen)
                }
            }.also { at++ }
            val shelvesAt = at
            itemsIndexed(shelves, key = { _, s -> s.key }) { i, shelf ->
                val titles = state.loaded[shelf.key].orEmpty()
                Rail(shelf.title, pin(shelvesAt + i)) {
                    if (titles.isEmpty()) {
                        Box(Modifier.height(CARD_H).fillMaxWidth()) { Placeholders() }
                    } else {
                        TitleRow(state, shelf.key, titles, firstKey != null && resumable.isEmpty() && watchlist.isEmpty() && i == 0, onFocus = aim, onOpen = onOpen)
                    }
                }
            }
        }
        }
        if (!online) {
            Text(
                "You're offline — showing what was saved",
                Modifier.align(Alignment.TopEnd).padding(top = Safe.y, end = Safe.x).background(Ink.surface2, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                style = Type.meta.copy(color = Ink.text),
            )
        }
    }
}

val CARD_W = 210.dp
val CARD_H = 118.dp

/** A focused card grows from its left edge by this: 13dp on a 210dp card, inside [ROW_GAP], so its ring clears the next card. */
private const val CARD_GROW = 1.06f

/** Between a row's cards: the focused card's growth plus room to spare. */
private val ROW_GAP = 18.dp

/** The focused card holds at the row's left edge, where the first card sits. */
@Composable
private fun rowScroll() = with(LocalDensity.current) { ROW_LEAD.toPx() }.let { remember(it) { edgeScroll(it) } }

/** Room left of the focused card for it to grow into — the same as the gap, so the card before it is fully out. */
private val ROW_LEAD = ROW_GAP

@Composable
private fun Rail(heading: String, onEnter: () -> Unit, content: @Composable () -> Unit) {
    var had by remember { mutableStateOf(false) }
    Column(
        Modifier.onFocusChanged {
            if (it.hasFocus && !had) onEnter()
            had = it.hasFocus
        },
    ) {
        Text(heading, Modifier.padding(start = GUTTER, bottom = 10.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        content()
    }
}

@Composable
private fun Placeholders() {
    Row(Modifier.padding(start = GUTTER), horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        repeat(5) { Box(Modifier.width(CARD_W).height(CARD_H).background(Ink.surface, RoundedCornerShape(8.dp))) }
    }
}

/** The focused title, big: backdrop to the right, logo and synopsis to the left. */
@Composable
private fun Billboard(state: HomeState, fallback: () -> Title?, modifier: Modifier) {
    // Read here, not in the page: a new hero redraws the billboard and nothing else.
    val title = state.hero ?: fallback()
    Box(modifier) {
        // The picture dissolves slowly, one already-loaded backdrop into the next...
        BillboardArt(title, Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.78f))
        // ...while the words go quickly and the new ones rise into place a beat later.
        AnimatedContent(
            title,
            transitionSpec = {
                (fadeIn(tween(380, delayMillis = 120, easing = LinearOutSlowInEasing)) + slideInVertically(tween(480, easing = FastOutSlowInEasing)) { it / 10 }) togetherWith
                    fadeOut(tween(160))
            },
            contentKey = { it?.imdb },
            modifier = Modifier.align(Alignment.CenterStart),
            label = "words",
        ) { t ->
            if (t != null) {
                Column(Modifier.padding(start = GUTTER, top = Safe.y).widthIn(max = 470.dp)) {
                    TitleMark(t, Modifier.widthIn(max = 380.dp), maxHeight = 86.dp, style = Type.hero)
                    Facts(t, Modifier.padding(top = 14.dp))
                    t.plot?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.body.copy(color = Ink.text.copy(alpha = 0.78f)), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/**
 * The backdrop, cross-faded by drawing the new picture's alpha straight over the old one.
 * A fading AnimatedContent renders each full-screen picture into an offscreen layer first,
 * and with two full-screen scrims on top this TV's GPU took ~35ms a frame. The scrims are
 * drawn here too, over the picture only — the rest of the screen is plain page already.
 */
@Composable
private fun BillboardArt(title: Title?, modifier: Modifier) {
    var under by remember { mutableStateOf<Title?>(null) }
    var over by remember { mutableStateOf(title) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(title?.imdb) {
        if (title?.imdb == over?.imdb) {
            over = title
            return@LaunchedEffect
        }
        under = over
        over = title
        fade.snapTo(0f)
        fade.animateTo(1f, tween(650, easing = LinearOutSlowInEasing))
        under = null
    }
    Box(modifier.heroScrims()) {
        under?.let { Artwork(it.wide, it.title, Modifier.fillMaxSize(), fill = false) }
        over?.let {
            Artwork(
                it.wide,
                it.title,
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = fade.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
                fill = false,
            )
        }
    }
}

@Composable
private fun TitleRow(state: HomeState, row: String, titles: List<Title>, claimFirst: Boolean, onFocus: (Title) -> Unit, onOpen: (Title) -> Unit) {
    val fallback = remember { FocusRequester() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll()) {
    LazyRow(
        state = state.row(row),
        // The row starts one gap short of the page edge and clips there: cards that have
        // scrolled past slide out of sight instead of peeking out under the side bar.
        modifier = Modifier.padding(start = GUTTER - ROW_LEAD).focusRestorer(fallback),
        contentPadding = PaddingValues(start = ROW_LEAD, end = Safe.x),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        itemsIndexed(titles, key = { _, t -> t.imdb }) { i, t ->
            val here = "$row/${t.imdb}"
            val requester = remember { FocusRequester() }
            val back = state.restore == here
            Focusable(
                onClick = {
                    state.restore = here
                    onOpen(t)
                },
                modifier = Modifier.width(CARD_W).height(CARD_H),
                shape = RoundedCornerShape(8.dp),
                scale = CARD_GROW,
                origin = TransformOrigin(0f, 0.5f),
                requester = if (i == 0 && !back) fallback else requester,
                onFocus = { onFocus(t) },
            ) { focused ->
                LandscapeCard(t.wide, t.title, focused)
            }
            if (back) {
                requester.claim()
                LaunchedEffect(Unit) { state.restore = null }
            } else if (claimFirst && i == 0) {
                fallback.claim()
            }
        }
    }
    }
}

/** 16:9 art with the name set over a fade, the way Netflix's boxart reads. */
@Composable
private fun LandscapeCard(art: String?, name: String, focused: Boolean, stamp: String? = null, progress: Float? = null) {
    Box(Modifier.fillMaxSize()) {
        Artwork(art, name, Modifier.fillMaxSize())
        // Only over the bottom half, where the name sits: the top of it was transparent and still cost a fill.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.6f).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = if (focused) 0.85f else 0.75f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = if (progress != null) 6.dp else 8.dp)) {
            Text(name, style = Type.label.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            stamp?.let { Text(it, style = Type.meta.copy(fontSize = 11.sp), maxLines = 1) }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(progress)
            }
        }
    }
}

@Composable
private fun ResumeRow(state: HomeState, items: List<Item>, claimFirst: Boolean, watch: Watch, onFocus: (Title) -> Unit) {
    val fallback = remember { FocusRequester() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll()) {
    LazyRow(
        state = state.row("resume"),
        modifier = Modifier.padding(start = GUTTER - ROW_LEAD).focusRestorer(fallback),
        contentPadding = PaddingValues(start = ROW_LEAD, end = Safe.x),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        itemsIndexed(items, key = { _, it -> it.uri }) { i, item ->
            Focusable(
                onClick = { watch.resume(item) },
                modifier = Modifier.width(CARD_W).height(CARD_H),
                shape = RoundedCornerShape(8.dp),
                scale = CARD_GROW,
                origin = TransformOrigin(0f, 0.5f),
                requester = if (i == 0) fallback else null,
                onFocus = {
                    // The billboard shows the show this card belongs to, when it is known.
                    item.imdb?.let { id -> onFocus(Title(id, item.kind ?: "movie", item.titleName?.substringBefore(" · ") ?: item.title, poster = item.poster, backdrop = item.backdrop)) }
                },
            ) { focused ->
                LandscapeCard(item.backdrop ?: item.imdb?.let { "https://images.metahub.space/background/medium/$it/img" } ?: remoteArt(item.poster), item.label, focused, resumeStamp(item), item.ratio.toFloat())
            }
            if (claimFirst && i == 0) fallback.claim()
        }
    }
    }
}

/** Posters, for search results. */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier.width(130.dp)) {
        Focusable(onClick, Modifier.width(130.dp).height(195.dp), RoundedCornerShape(8.dp), 1.08f) {
            Artwork(title.poster, title.title, Modifier.fillMaxSize())
        }
        Text(title.title, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = Type.meta.copy(color = Ink.faint), maxLines = 1) }
    }
}
