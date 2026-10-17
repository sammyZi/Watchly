package com.torrentviewer.mobile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.ANIME_GENRE
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.Facts
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Item
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Net
import com.torrentviewer.core.NewEpisodes
import com.torrentviewer.core.Profile
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.heroScrims
import com.torrentviewer.core.kindOfGenre
import com.torrentviewer.core.left
import com.torrentviewer.core.latestPerTitle
import com.torrentviewer.core.titleKey
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.quality
import com.torrentviewer.core.remoteArt
import kotlinx.coroutines.delay
import com.composables.icons.lucide.R as L

data class Shelf(val title: String, val kind: String, val genre: String? = null) {
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

/** How many titles of the lead shelf the hero swipes through. */
private const val HEROES = 6

/** Survives opening a title, so coming back lands on the same row, same card. */
class HomeState {
    val list = LazyListState()
    val rows = HashMap<String, LazyListState>()
    val loaded = mutableStateMapOf<String, List<Title>>()
    var heroCount by mutableIntStateOf(0)
    val hero = PagerState { heroCount }
    var picks by mutableStateOf<List<Shelf>>(emptyList())
    var fresh by mutableStateOf<List<NewEpisodes.Fresh>>(emptyList())

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

fun Item.art() = backdrop ?: imdb?.let { "https://images.metahub.space/background/medium/$it/img" } ?: remoteArt(poster)

/** 16:9 cards: under two to a phone's width, the next one peeking so the row says it scrolls. */
@Composable
fun cardWidth() = if (isWide()) 260.dp else 220.dp

/** Home's rows are tall posters: a little over three across a phone held upright. */
@Composable
fun posterWidth() = if (isWide()) 140.dp else 116.dp

@Composable
fun HomeScreen(state: HomeState, watch: Watch, who: Profile, onOpen: (Title) -> Unit, onShelf: (Shelf) -> Unit, onProfile: () -> Unit) {
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()
    val settings by SettingsStore.value.collectAsState()
    val online by Net.online.collectAsState()

    // Shelves inferred from what has been watched: the lead pick says so.
    LaunchedEffect(history.size, saved.size, online) {
        val seen = (history.mapNotNull { h -> h.imdb?.let { it to (h.kind ?: "movie") } } + saved.map { it.imdb to it.kind }).take(30)
        state.picks = if (seen.isEmpty()) emptyList() else Meta.watchedGenres(seen).mapIndexed { i, (genre, kind) ->
            Shelf(if (i == 0) "Because you watched ${genre.lowercase()}" else "More ${genre.lowercase()}", kind, genre)
        }
    }
    // Followed series — watched or saved — with episodes out since their page was last opened.
    LaunchedEffect(history.size, saved.size, online) {
        val shows = history.filter { it.kind == "tv" }.mapNotNull { it.imdb } + saved.filter { it.kind == "tv" }.map { it.imdb }
        state.fresh = runCatching { NewEpisodes.fresh(shows) }.getOrDefault(state.fresh)
    }
    val chosen = settings.genres.map { Shelf(if (it == ANIME_GENRE) "Anime" else it, kindOfGenre(it), it) }
    val shelves = remember(chosen, state.picks) {
        val taken = chosen.map { it.key }.toMutableSet()
        val rest = state.picks.filter { taken.add(it.key) }
        chosen + rest + SHELVES.filter { it.key !in taken }
    }

    // Top-down, not all at once: a phone on mobile data and a burst of parallel fetches do not mix.
    LaunchedEffect(shelves, online) {
        for (shelf in shelves) {
            if (state.loaded[shelf.key].isNullOrEmpty()) {
                val titles = Meta.peekCatalog(shelf.kind, shelf.genre) ?: Meta.catalog(shelf.kind, shelf.genre)
                if (titles.isNotEmpty()) state.loaded[shelf.key] = titles
            }
        }
    }

    // One card per title — its latest episode or release — and only if that one is part-watched.
    val resumable = latestPerTitle(history).filter(::inProgress).take(10)
    val watchlist = saved.filter { it.list == "later" }.map { it.asTitle() }
    val heroes = shelves.firstNotNullOfOrNull { state.loaded[it.key]?.takeIf { l -> l.isNotEmpty() } }.orEmpty().take(HEROES)
    SideEffect { state.heroCount = heroes.size }
    var menu by remember { mutableStateOf<Item?>(null) }
    var releases by remember { mutableStateOf<List<Item>?>(null) }
    // A title shows once, in the first row it lands in: genre rows overlap, and the week's
    // big film otherwise led half the page. "See all" still lists a shelf in full.
    val seen = HashSet<String>()
    val rows = shelves.associate { s -> s.key to state.loaded[s.key].orEmpty().filter { seen.add(it.imdb) } }
    val visible = shelves.filter { state.loaded[it.key].isNullOrEmpty() || rows[it.key].orEmpty().isNotEmpty() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state.list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item(key = "hero") {
                if (heroes.isEmpty()) Box(Modifier.fillMaxWidth().height(if (isWide()) 320.dp else 420.dp))
                else Hero(state.hero, heroes, onOpen)
            }
            if (resumable.isNotEmpty()) item(key = "resume") {
                Rail("Continue Watching") {
                    LazyRow(state = state.row("resume"), contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(resumable, key = { it.uri }) { item ->
                            Tap({
                                // Played from more than one release: ask which, each resuming where it was left.
                                val all = history.filter { it.titleKey == item.titleKey }.sortedByDescending { it.at }
                                if (all.size > 1) releases = all else watch.resume(item)
                            }, Modifier.width(posterWidth()).aspectRatio(2f / 3f), RoundedCornerShape(6.dp), onLongClick = { menu = item }) {
                                LandscapeCard(remoteArt(item.poster) ?: item.art(), item.label, resumeStamp(item), item.ratio.toFloat())
                            }
                        }
                    }
                }
            }
            if (state.fresh.isNotEmpty()) item(key = "fresh") {
                Rail("New Episodes") {
                    LazyRow(state = state.row("fresh"), contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.fresh, key = { it.title.imdb }) { f ->
                            Tap({ onOpen(f.title) }, Modifier.width(posterWidth()).aspectRatio(2f / 3f), RoundedCornerShape(6.dp)) {
                                LandscapeCard(f.title.poster ?: f.title.wide, f.title.title, if (f.count == 1) "1 new episode" else "${f.count} new episodes")
                            }
                        }
                    }
                }
            }
            if (watchlist.isNotEmpty()) item(key = "list") {
                Rail("My List") { TitleRow(state.row("list"), watchlist, onOpen) }
            }
            itemsIndexed(visible, key = { _, s -> s.key }) { _, shelf ->
                val titles = rows[shelf.key].orEmpty()
                Rail(shelf.title, onMore = { onShelf(shelf) }) {
                    if (titles.isEmpty()) Placeholders() else TitleRow(state.row(shelf.key), titles, onOpen)
                }
            }
        }

        // The wordmark and who is watching: a fade over the hero, a solid bar once the rows scroll under it.
        val scrolled by remember { derivedStateOf { state.list.firstVisibleItemIndex > 0 || state.list.firstVisibleItemScrollOffset > 240 } }
        Row(
            Modifier.fillMaxWidth()
                .background(if (scrolled) Brush.verticalGradient(listOf(Ink.bg, Ink.bg.copy(alpha = 0.94f))) else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = EDGE, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("WATCHLY", Modifier.weight(1f), style = Type.title.copy(color = Ink.accent, fontWeight = FontWeight.Black, letterSpacing = 2.sp))
            if (!isWide()) Tap(onProfile, shape = RoundedCornerShape(6.dp)) { Avatar(who, 30.dp) }
        }
        if (!online) {
            Text(
                "You're offline — showing what was saved",
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 52.dp).background(Ink.surface2, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                style = Type.meta.copy(color = Ink.text),
            )
        }
    }

    menu?.let { item ->
        Sheet(
            title = item.label,
            options = listOfNotNull(
                item.imdb?.let { Option("info", "Details") },
                Option("remove", "Remove from Continue Watching"),
            ),
            selected = null,
            onPick = {
                menu = null
                when (it) {
                    "info" -> item.imdb?.let { id -> onOpen(Title(id, item.kind ?: "movie", item.titleName?.substringBefore(" · ") ?: item.title, poster = item.poster, backdrop = item.backdrop)) }
                    "remove" -> History.dropTitle(item.titleKey, watch.engine)
                }
            },
            onDismiss = { menu = null },
        )
    }

    releases?.let { all ->
        Sheet(
            title = all.first().label,
            options = all.map {
                val name = it.torrentName ?: it.title
                Option(it.uri, name, listOfNotNull(it.seeders?.let { s -> "$s seeds" }, resumeStamp(it) ?: "Not started").joinToString("  ·  "), tag = quality(name))
            },
            selected = latestPerTitle(all).first().uri,
            onPick = { uri ->
                releases = null
                all.find { it.uri == uri }?.let { watch.resume(it) }
            },
            onDismiss = { releases = null },
        )
    }
}

@Composable
private fun Rail(heading: String, onMore: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = EDGE, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(heading, Modifier.weight(1f), style = Type.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onMore != null) Tap(onMore, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("See all", style = Type.meta)
                    Lc(L.drawable.lucide_ic_chevron_right, tint = Ink.muted, size = 16.dp)
                }
            }
        }
        content()
    }
}

@Composable
private fun Placeholders() {
    Row(Modifier.padding(start = EDGE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(4) { Box(Modifier.width(posterWidth()).aspectRatio(2f / 3f).background(Ink.surface, RoundedCornerShape(6.dp))) }
    }
}

@Composable
private fun TitleRow(state: LazyListState, titles: List<Title>, onOpen: (Title) -> Unit) {
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(titles, key = { it.imdb }) { t ->
            Tap({ onOpen(t) }, Modifier.width(posterWidth()).aspectRatio(2f / 3f), RoundedCornerShape(6.dp)) {
                Artwork(t.poster ?: t.wide, t.title, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The TV's billboard, swiped: backdrop, title logo, facts and the two buttons. Portrait
 * stacks the words under the art; landscape sets them beside it, as the TV does.
 */
@Composable
private fun Hero(pager: PagerState, titles: List<Title>, onOpen: (Title) -> Unit) {
    val count = titles.size
    // Moves on by itself, and waits again after every swipe.
    LaunchedEffect(pager.settledPage, count) {
        delay(8000)
        if (count > 1) pager.animateScrollToPage((pager.settledPage + 1) % count)
    }
    val wide = isWide()
    val height = if (wide) (LocalConfiguration.current.screenHeightDp * 0.82f).dp else (LocalConfiguration.current.screenWidthDp * 1.12f).dp
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { titles.getOrNull(it)?.imdb ?: it }) { page ->
            val start = titles.getOrNull(page) ?: return@HorizontalPager
            // The shelf row carries no logo or runtime; the full entry does.
            val t by produceState(start, start.imdb) { Meta.title(start.imdb, start.kind)?.let { value = it } }
            val saved by SavedStore.items.collectAsState()
            val inList = saved.any { it.imdb == t.imdb && it.list == "later" }
            if (wide) {
                Box(Modifier.fillMaxSize()) {
                    Artwork(t.wide, t.title, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight().heroScrims(), fill = false)
                    Column(Modifier.align(Alignment.CenterStart).padding(start = EDGE + 8.dp, top = 40.dp).widthIn(max = 380.dp)) {
                        TitleMark(t, Modifier.widthIn(max = 300.dp), maxHeight = 72.dp, style = Type.display)
                        Facts(t.copy(genres = t.genres.take(2)), Modifier.padding(top = 12.dp))
                        t.plot?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.body.copy(color = Ink.text.copy(alpha = 0.78f)), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        HeroButtons(t, inList, onOpen, Modifier.padding(top = 16.dp))
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    Artwork(t.wide, t.title, Modifier.fillMaxSize().bottomScrim(0.35f), fill = false)
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = EDGE, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.widthIn(max = 280.dp), contentAlignment = Alignment.Center) {
                            TitleMark(t, maxHeight = 84.dp, style = Type.display.copy(textAlign = TextAlign.Center))
                        }
                        Facts(t.copy(genres = t.genres.take(2)), Modifier.padding(top = 12.dp))
                        HeroButtons(t, inList, onOpen, Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
        if (count > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 16.dp else 6.dp, 4.dp).clip(CircleShape).background(if (i == pager.currentPage) Ink.text else Ink.faint))
            }
        }
    }
}

@Composable
private fun HeroButtons(t: Title, inList: Boolean, onOpen: (Title) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button("Watch", { onOpen(t) }, Modifier.width(132.dp), icon = L.drawable.lucide_ic_play, primary = true)
        Button(if (inList) "In My List" else "My List", { SavedStore.toggle(t) }, Modifier.width(132.dp), icon = if (inList) L.drawable.lucide_ic_check else L.drawable.lucide_ic_plus)
    }
}
