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
    /** The watch orders row as last shown, for the remote to land back on. */
    var orders: List<WatchOrder> = emptyList()
    var picks by mutableStateOf<List<Shelf>>(emptyList())
    var forYou by mutableStateOf(ForYou())

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
fun HomeScreen(state: HomeState, watch: Watch, onOpen: (Title) -> Unit, onTop: (String) -> Unit, onOrders: () -> Unit, onOrder: (WatchOrder) -> Unit) {
    val scope = rememberCoroutineScope()
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()
    val settings by SettingsStore.value.collectAsState()
    val online by Net.online.collectAsState()

    LaunchedEffect(online, Kids.on) { if (!Kids.on) WatchOrders.tag() }
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
    // What TMDB suggests for the titles this profile last played or saved: not for kids, whose rows are the family ones.
    LaunchedEffect(history.size, saved.size, online, Kids.on) {
        val played = history.sortedByDescending { it.at }.mapNotNull { h -> h.imdb?.let { it to (h.kind ?: "movie") } }
        val seeds = (played + saved.map { it.imdb to it.kind }).filter { it.first.startsWith("tt") }.distinctBy { it.first }.take(FOR_YOU_SEEDS)
        if (seeds.isEmpty() || Kids.on) state.forYou = ForYou()
        else Meta.forYou(seeds).takeIf { it.picks.isNotEmpty() }?.let { state.forYou = it }
    }
    val chosen = settings.genres.map { Shelf(if (it == ANIME_GENRE) "Anime" else it, kindOfGenre(it), it) }
    val addons by Addons.list.collectAsState()
    val personal = state.forYou.picks.isNotEmpty()
    val shelves = remember(chosen, state.picks, Kids.on, addons, personal) {
        if (Kids.on) return@remember KIDS_SHELVES.map { (title, kind, genre) -> Shelf(title, kind, genre) }
        // The viewer's addons' own lists lead, as on Stremio's board.
        val mine = Addons.shelves().map { (title, kind, key) -> Shelf(title, kind, key) }
        val taken = chosen.map { it.key }.toMutableSet()
        // A genre's popular list is no suggestion: it's what TMDB's rows replace, with no key or nothing played.
        val rest = if (personal) emptyList() else state.picks.filter { taken.add(it.key) }
        chosen + mine + rest + SHELVES.filter { it.key !in taken }
    }
    // The charts draw on every genre the viewer chose, or on all of them with none chosen.
    val tops = if (Kids.on) emptyList() else listOf("movie", "tv").flatMap { kind ->
        settings.genres.ifEmpty { listOf("") }.map { Shelf("", kind, TOP_SHELF + it) }
    }

    // Top-down, not all at once: a 2GB television and a burst of parallel fetches do not mix.
    // A kids profile's rows are filtered again once the family list is in.
    LaunchedEffect(shelves, tops, online, Kids.allowed) {
        val first = maxOf(1, chosen.size)
        for (shelf in shelves.take(first) + tops + shelves.drop(first)) {
            if (state.loaded[shelf.key].isNullOrEmpty() || Kids.on) {
                val titles = Kids.only(Meta.peekCatalog(shelf.kind, shelf.genre) ?: Meta.catalog(shelf.kind, shelf.genre))
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
    val stremio by Stremio.library.collectAsState()
    val resumable = latestPerTitle(history).filter { inProgress(it) && !stremio.finishedSince(it) }.take(8)
    // Left part-way somewhere this TV never saw — another device, before a reinstall: Stremio kept the spot.
    val fromStremio = Kids.only(stremio.paused.filter { p -> history.none { it.imdb == p.imdb } }.distinctBy { it.imdb }) { Title(it.imdb, it.kind, it.title) }.take(8)
    val resuming = resumable.isNotEmpty() || fromStremio.isNotEmpty()
    var releases by remember { mutableStateOf<List<Item>?>(null) }
    val watchlist = saved.filter { it.list == "later" }.map { it.asTitle() }
    // Nothing already played, seen elsewhere or saved is a suggestion.
    val everywhere by WatchedEverywhere.collectAsState(stremio)
    val watched = remember(history, everywhere) { watchedFilms(history, everywhere) }
    val known = remember(watched, history, saved) { watched + history.mapNotNull { it.imdb } + saved.map { it.imdb } }
    val picks = state.forYou.picks.filter { it.title.imdb !in known }.take(FOR_YOU_PICKS)
    val why = picks.associate { it.title.imdb to "Because you watched ${it.because}" }
    val because = state.forYou.rails.map { (name, titles) -> name to titles.filter { it.imdb !in known } }.filter { it.second.size >= 3 }
    val charts = tops.groupBy { it.kind }.map { (kind, parts) ->
        val word = if (kind == "tv") "series" else "movies"
        val heading = if (settings.genres.isEmpty()) "Top $word on IMDb" else "Top IMDb $word in your genres"
        Triple(kind, heading, mixTop(parts.map { state.loaded[it.key].orEmpty() }, CHART_SIZE))
    }
    // A title shows once, in the first row it lands in; what's been watched, and what the rows above suggest, not at all.
    val shown = HashSet(watched)
    picks.mapTo(shown) { it.title.imdb }
    because.forEach { (_, titles) -> titles.mapTo(shown) { it.imdb } }
    charts.forEach { (_, _, titles) -> titles.mapTo(shown) { it.imdb } }
    val rows = shelves.associate { s -> s.key to state.loaded[s.key].orEmpty().filter { shown.add(it.imdb) } }
    val visible = shelves.filter { state.loaded[it.key].isNullOrEmpty() || rows[it.key].orEmpty().isNotEmpty() }
    // Before the remote has picked a card: what's suggested, or the first of the chosen genres.
    val fallback = {
        interleave(listOf(picks.map { it.title }) + chosen.map { state.loaded[it.key].orEmpty() }).firstOrNull()
            ?: shelves.firstNotNullOfOrNull { state.loaded[it.key]?.firstOrNull() } ?: watchlist.firstOrNull()
    }
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
            if (resuming) item(key = "resume") {
                Rail("Continue Watching", pin(0)) {
                    ResumeRow(state, resumable, fromStremio, firstKey != null, onResume = { item ->
                        releasesOf(history, item).let { all -> if (all.size > 1) releases = all else watch.resume(item) }
                    }, onFocus = aim, onOpen = onOpen)
                }
            }.also { at++ }
            val listAt = at
            if (watchlist.isNotEmpty()) item(key = "list") {
                Rail("My List", pin(listAt)) {
                    TitleRow(state, "list", watchlist, firstKey != null && !resuming, onFocus = aim, onOpen = onOpen)
                }
            }.also { at++ }
            val ordersAt = at
            if (!Kids.on) item(key = "orders") {
                // Nothing chosen: what they watch stands in.
                Rail("Watch orders", pin(ordersAt)) { OrderRow(state, settings.genres.ifEmpty { state.picks.mapNotNull { it.genre } }, aim, onOrders, onOrder) }
            }.also { at++ }
            val picksAt = at
            if (picks.isNotEmpty()) item(key = "picks") {
                Rail("Picked for you", pin(picksAt)) {
                    TitleRow(state, "picks", picks.map { it.title }, false, onFocus = aim, onOpen = onOpen, stamp = { why[it.imdb] })
                }
            }.also { at++ }
            charts.forEach { (kind, heading, titles) ->
                val here = at++
                item(key = "chart:$kind") {
                    Rail(heading, pin(here)) {
                        if (titles.isEmpty()) Box(Modifier.height(CARD_H).fillMaxWidth()) { Placeholders() }
                        else ChartRow(state, "chart:$kind", titles, aim, onOpen, onAll = { onTop(kind) })
                    }
                }
            }
            because.forEach { (name, titles) ->
                val here = at++
                item(key = "because:$name") {
                    Rail("Because you watched $name", pin(here)) {
                        TitleRow(state, "because:$name", titles, false, onFocus = aim, onOpen = onOpen)
                    }
                }
            }
            val shelvesAt = at
            itemsIndexed(visible, key = { _, s -> s.key }) { i, shelf ->
                val titles = rows[shelf.key].orEmpty()
                Rail(shelf.title, pin(shelvesAt + i)) {
                    if (titles.isEmpty()) {
                        Box(Modifier.height(CARD_H).fillMaxWidth()) { Placeholders() }
                    } else {
                        TitleRow(state, shelf.key, titles, firstKey != null && !resuming && watchlist.isEmpty() && i == 0, onFocus = aim, onOpen = onOpen)
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
        releases?.let { all -> ReleasesSheet(all, watch) { releases = null } }
    }
}

val CARD_W = 210.dp
val CARD_H = 118.dp

/** The viewer's latest titles that suggestions are drawn from, and how many of the best to show. */
private const val FOR_YOU_SEEDS = 4
private const val FOR_YOU_PICKS = 15

private const val CHART_SIZE = 15

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
private fun TitleRow(state: HomeState, row: String, titles: List<Title>, claimFirst: Boolean, onFocus: (Title) -> Unit, onOpen: (Title) -> Unit, stamp: (Title) -> String? = { null }) {
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
                LandscapeCard(t.wide, t.title, focused, stamp(t), rating = t.rating)
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

/** A row's way into its whole list, first in the row: at the end it would be a dozen presses away. */
@Composable
private fun SeeAll(label: String, state: HomeState, here: String, requester: FocusRequester, onClick: () -> Unit) {
    Focusable(
        onClick = {
            state.restore = here
            onClick()
        },
        modifier = Modifier.width(150.dp).height(CARD_H),
        shape = RoundedCornerShape(8.dp),
        scale = CARD_GROW,
        origin = TransformOrigin(0f, 0.5f),
        requester = requester,
    ) {
        Column(Modifier.fillMaxSize().background(Ink.surface2).padding(14.dp), verticalArrangement = Arrangement.Center) {
            Lc(com.composables.icons.lucide.R.drawable.lucide_ic_list, tint = Ink.text, size = 24.dp)
            Text(label, Modifier.padding(top = 10.dp), style = Type.label.copy(fontWeight = FontWeight.Bold))
        }
    }
    if (state.restore == here) {
        requester.claim()
        LaunchedEffect(Unit) { state.restore = null }
    }
}

/** A chart: each card carries its rank, large and outlined. */
@Composable
private fun ChartRow(state: HomeState, row: String, titles: List<Title>, onFocus: (Title) -> Unit, onOpen: (Title) -> Unit, onAll: () -> Unit) {
    val fallback = remember { FocusRequester() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll()) {
    LazyRow(
        state = state.row(row),
        modifier = Modifier.padding(start = GUTTER - ROW_LEAD).focusRestorer(fallback),
        contentPadding = PaddingValues(start = ROW_LEAD, end = Safe.x),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        item(key = "all") { SeeAll("See all", state, "$row/all", fallback, onAll) }
        itemsIndexed(titles, key = { _, t -> t.imdb }) { i, t ->
            val here = "$row/${t.imdb}"
            val requester = remember { FocusRequester() }
            Focusable(
                onClick = {
                    state.restore = here
                    onOpen(t)
                },
                modifier = Modifier.width(CARD_W).height(CARD_H),
                shape = RoundedCornerShape(8.dp),
                scale = CARD_GROW,
                origin = TransformOrigin(0f, 0.5f),
                requester = requester,
                onFocus = { onFocus(t) },
            ) { focused ->
                LandscapeCard(t.wide, t.title, focused, rating = t.rating)
                Text(
                    "${i + 1}",
                    Modifier.align(Alignment.TopEnd).padding(end = 8.dp),
                    style = Type.display.copy(color = Color.White.copy(alpha = 0.85f), fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Black, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)),
                )
            }
            if (state.restore == here) {
                requester.claim()
                LaunchedEffect(Unit) { state.restore = null }
            }
        }
    }
    }
}

/** The franchises, All first. */
@Composable
private fun OrderRow(state: HomeState, genres: List<String>, onFocus: (Title) -> Unit, onOrders: () -> Unit, onOrder: (WatchOrder) -> Unit) {
    val seen = seenFilms()
    val fallback = remember { FocusRequester() }
    val orders by WatchOrders.tagged.collectAsState()
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()
    // Ranked as the row comes on screen, not while the remote is on it; back from a franchise it stays
    // as it was, or a card that moved would leave the remote nowhere to land.
    val shown = remember(genres) {
        if (state.restore?.startsWith("orders/") == true) state.orders
        else homeOrders(orders, genres, seen, seen + history.mapNotNull { it.imdb } + saved.map { it.imdb }).also { state.orders = it }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll()) {
    LazyRow(
        state = state.row("orders"),
        modifier = Modifier.padding(start = GUTTER - ROW_LEAD).focusRestorer(fallback),
        contentPadding = PaddingValues(start = ROW_LEAD, end = Safe.x),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        item(key = "all") { SeeAll("See all ${WatchOrders.all.size}", state, "orders/all", fallback, onOrders) }
        itemsIndexed(shown, key = { _, o -> o.id }) { _, order ->
            val here = "orders/${order.id}"
            val requester = remember { FocusRequester() }
            Focusable(
                onClick = {
                    state.restore = here
                    onOrder(order)
                },
                modifier = Modifier.width(320.dp).height(CARD_H),
                shape = RoundedCornerShape(8.dp),
                scale = 1.04f,
                origin = TransformOrigin(0f, 0.5f),
                requester = requester,
                onFocus = { onFocus(Title(order.films.first(), "movie", order.name)) },
            ) { OrderBanner(order, seen) }
            if (state.restore == here) {
                requester.claim()
                LaunchedEffect(Unit) { state.restore = null }
            }
        }
    }
    }
}

/** 16:9 art with the name set over a fade, the way Netflix's boxart reads. */
@Composable
private fun LandscapeCard(art: String?, name: String, focused: Boolean, stamp: String? = null, progress: Float? = null, rating: String? = null) {
    Box(Modifier.fillMaxSize()) {
        Artwork(art, name, Modifier.fillMaxSize())
        rating?.takeIf { it.isNotBlank() }?.let { ImdbMark(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
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
private fun ResumeRow(state: HomeState, items: List<Item>, fromStremio: List<Paused>, claimFirst: Boolean, onResume: (Item) -> Unit, onFocus: (Title) -> Unit, onOpen: (Title) -> Unit) {
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
                onClick = { onResume(item) },
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
        // No release to resume from yet: the title opens, and the one picked starts at Stremio's spot.
        itemsIndexed(fromStremio, key = { _, it -> "stremio:${it.imdb}" }) { i, p ->
            val title = Title(p.imdb, p.kind, p.title, year = p.year)
            val first = items.isEmpty() && i == 0
            Focusable(
                onClick = { onOpen(title) },
                modifier = Modifier.width(CARD_W).height(CARD_H),
                shape = RoundedCornerShape(8.dp),
                scale = CARD_GROW,
                origin = TransformOrigin(0f, 0.5f),
                requester = if (first) fallback else null,
                onFocus = { onFocus(title) },
            ) { focused ->
                LandscapeCard(
                    "https://images.metahub.space/background/medium/${p.imdb}/img", p.title, focused,
                    listOfNotNull(p.ep?.let { "S${it.season}:E${it.episode}" }, "${p.progress.toInt()}%").joinToString("  ·  "),
                    (p.progress / 100).toFloat(),
                )
            }
            if (claimFirst && first) fallback.claim()
        }
    }
    }
}

/** Posters, for search results. */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null, requester: FocusRequester? = null) {
    Column(modifier.width(130.dp)) {
        Focusable(onClick, Modifier.width(130.dp).height(195.dp), RoundedCornerShape(8.dp), 1.08f, requester = requester) {
            Artwork(title.poster, title.title, Modifier.fillMaxSize())
            title.rating?.takeIf { it.isNotBlank() }?.let { ImdbMark(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
        }
        Text(title.title, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = Type.meta.copy(color = Ink.faint), maxLines = 1) }
    }
}
