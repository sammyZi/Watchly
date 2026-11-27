package com.torrentviewer.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.ANIME_GENRE
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.Facts
import com.torrentviewer.core.ImdbBadge
import com.torrentviewer.core.runtimeLabel
import androidx.compose.foundation.border
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.grain
import com.torrentviewer.core.Item
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Net
import com.torrentviewer.core.NewEpisodes
import com.torrentviewer.core.Profile
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Settings
import com.torrentviewer.core.arrangeRows
import com.torrentviewer.core.movedRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.torrentviewer.core.bytes
import com.torrentviewer.core.playedSize
import com.torrentviewer.core.remoteArt
import com.torrentviewer.core.TOP_SHELF
import kotlinx.coroutines.delay
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.drawBehind
import com.composables.icons.lucide.R as L
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.layout.layout
import dev.chrisbanes.haze.hazeSource

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

/** The viewer's latest titles that suggestions are drawn from, and how many of the best to show. */
private const val FOR_YOU_SEEDS = 4
private const val FOR_YOU_PICKS = 15

private const val CHART_SIZE = 15

/** Survives opening a title, so coming back lands on the same row, same card. */
class HomeState {
    val list = LazyListState()
    val rows = HashMap<String, LazyListState>()
    val loaded = mutableStateMapOf<String, List<Title>>()
    var heroCount by mutableIntStateOf(0)
    val hero = PagerState { heroCount }
    var picks by mutableStateOf<List<Shelf>>(emptyList())
    var forYou by mutableStateOf(com.torrentviewer.core.ForYou())
    var fresh by mutableStateOf<List<NewEpisodes.Fresh>>(emptyList())
    var upcoming by mutableStateOf<List<com.torrentviewer.core.Coming>>(emptyList())
    /** Edit Home is open: from its button at the foot of the page, or from Settings → Appearance. */
    var editing by mutableStateOf(false)

    fun row(key: String) = rows.getOrPut(key) { LazyListState() }
}

private fun dayLabel(date: String) = com.torrentviewer.core.Upcoming.label(date)

/** The .ics of [items], handed to whichever calendar app the viewer picks. */
private fun shareCalendar(context: android.content.Context, items: List<com.torrentviewer.core.Coming>) {
    val file = java.io.File(java.io.File(context.filesDir, "calendar").apply { mkdirs() }, "watchly-upcoming.ics")
    file.writeText(com.torrentviewer.core.Upcoming.ics(items))
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val open = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "text/calendar").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/calendar").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Opened straight in a calendar app where one takes .ics; else to Files, mail, Drive…
    runCatching { context.startActivity(android.content.Intent.createChooser(open, "Add to calendar").putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(send))) }
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

/** Home's rows are tall posters: under three across a phone held upright, big enough to read. */
@Composable
fun posterWidth() = if (isWide()) 156.dp else 120.dp

/** Between the cards of a row, the next one peeking in. */
private val CARD_GAP = 12.dp

@Composable
fun HomeScreen(state: HomeState, watch: Watch, who: Profile, onOpen: (Title) -> Unit, onShelf: (Shelf) -> Unit, onTop: (String) -> Unit, onProfile: () -> Unit, onNotifications: () -> Unit) {
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()
    val settings by SettingsStore.value.collectAsState()
    val online by Net.online.collectAsState()
    var joiningParty by remember { mutableStateOf(false) }

    // Shelves inferred from what has been watched: the lead pick says so.
    LaunchedEffect(history.size, saved.size, online) {
        val seen = (history.mapNotNull { h -> h.imdb?.let { it to (h.kind ?: "movie") } } + saved.map { it.imdb to it.kind }).take(30)
        state.picks = if (seen.isEmpty()) emptyList() else Meta.watchedGenres(seen).mapIndexed { i, (genre, kind) ->
            Shelf(if (i == 0) "Because you watched ${genre.lowercase()}" else "More ${genre.lowercase()}", kind, genre)
        }
    }
    // What TMDB suggests for the titles this profile last played or saved: not for kids, whose rows are the family ones.
    LaunchedEffect(history.size, saved.size, online, com.torrentviewer.core.Kids.on) {
        val played = history.sortedByDescending { it.at }.mapNotNull { h -> h.imdb?.let { it to (h.kind ?: "movie") } }
        val seeds = (played + saved.map { it.imdb to it.kind }).filter { it.first.startsWith("tt") }.distinctBy { it.first }.take(FOR_YOU_SEEDS)
        if (seeds.isEmpty() || com.torrentviewer.core.Kids.on) state.forYou = com.torrentviewer.core.ForYou()
        else Meta.forYou(seeds).takeIf { it.picks.isNotEmpty() }?.let { state.forYou = it }
    }
    // Followed series — watched or saved — with episodes out since their page was last opened.
    LaunchedEffect(history.size, saved.size, online) {
        val shows = history.filter { it.kind == "tv" }.mapNotNull { it.imdb } + saved.filter { it.kind == "tv" }.map { it.imdb }
        state.fresh = runCatching { NewEpisodes.fresh(shows) }.getOrDefault(state.fresh)
    }
    // What's coming: the same followed series' next episodes, and saved films not out yet.
    LaunchedEffect(history.size, saved.size, online) {
        val shows = history.filter { it.kind == "tv" }.sortedByDescending { it.at }.mapNotNull { it.imdb } + saved.filter { it.kind == "tv" }.map { it.imdb }
        val films = saved.filter { it.kind == "movie" }.map { it.imdb }
        state.upcoming = runCatching { com.torrentviewer.core.Upcoming.load(shows.filter { it.startsWith("tt") }, films.filter { it.startsWith("tt") }) }.getOrDefault(state.upcoming)
    }
    val chosen = settings.genres.map { Shelf(if (it == ANIME_GENRE) "Anime" else it, kindOfGenre(it), it) }
    val kids = com.torrentviewer.core.Kids
    val addons by com.torrentviewer.core.Addons.list.collectAsState()
    val personal = state.forYou.picks.isNotEmpty()
    val shelves = remember(chosen, state.picks, kids.on, addons, personal) {
        if (kids.on) return@remember com.torrentviewer.core.KIDS_SHELVES.map { (title, kind, genre) -> Shelf(title, kind, genre) }
        // The viewer's addons' own lists lead, as on Stremio's board.
        val mine = com.torrentviewer.core.Addons.shelves().map { (title, kind, key) -> Shelf(title, kind, key) }
        val taken = chosen.map { it.key }.toMutableSet()
        // Genres picked: theirs are the only genre rows — not the watched-genre ones, nor the standard list's.
        // Trending and the addons' lists aren't genres, and stay. None picked: every genre, as before.
        val picked = chosen.isNotEmpty()
        // A genre's popular list is no suggestion: it's what TMDB's rows replace, with no key or nothing played.
        val rest = if (personal || picked) emptyList() else state.picks.filter { taken.add(it.key) }
        chosen + mine + rest + SHELVES.filter { it.key !in taken && (!picked || it.genre == null) }
    }
    // The charts draw on every genre the viewer chose, or on all of them with none chosen.
    val tops = if (kids.on) emptyList() else listOf("movie", "tv").flatMap { kind ->
        settings.genres.ifEmpty { listOf("") }.map { Shelf("", kind, TOP_SHELF + it) }
    }

    // Top-down, not all at once: a phone on mobile data and a burst of parallel fetches do not mix.
    // A kids profile's rows are filtered again once the family list is in.
    LaunchedEffect(shelves, tops, online, kids.allowed) {
        // The chosen genres first: the hero is made of them.
        val first = maxOf(1, chosen.size)
        for (shelf in shelves.take(first) + tops + shelves.drop(first)) {
            if (state.loaded[shelf.key].isNullOrEmpty() || kids.on) {
                val titles = kids.only(Meta.peekCatalog(shelf.kind, shelf.genre) ?: Meta.catalog(shelf.kind, shelf.genre))
                if (titles.isNotEmpty()) state.loaded[shelf.key] = titles
            }
        }
    }

    // One card per title — its latest episode or release — and only if that one is part-watched.
    val stremio by com.torrentviewer.core.Synced.collectAsState(com.torrentviewer.core.syncedNow())
    val resumable = latestPerTitle(history).filter { inProgress(it) && !stremio.finishedSince(it) }.take(10)
    // Left part-way somewhere this phone never saw — another device, before a reinstall: Stremio kept the spot.
    // With what is being watched on AniList: the episode after the last one marked there.
    val everywhere by com.torrentviewer.core.WatchedEverywhere.collectAsState(stremio)
    val fromStremio = com.torrentviewer.core.Kids.only(everywhere.paused.filter { p -> history.none { it.imdb == p.imdb } }.distinctBy { it.imdb }) { Title(it.imdb, it.kind, it.title) }.take(10)
    // This phone's list with the Stremio and Nuvio libraries: a title saved in either app shows here too.
    val watchlist = com.torrentviewer.core.myList(saved, stremio.seen, com.torrentviewer.core.watchedFilms(history, stremio))
    var menu by remember { mutableStateOf<Item?>(null) }
    var comingAll by remember { mutableStateOf(false) }
    var releases by remember { mutableStateOf<List<Item>?>(null) }
    // Nothing already played, seen elsewhere or saved is a suggestion.
    val watched = remember(history, everywhere) { com.torrentviewer.core.watchedFilms(history, everywhere) }
    val played = remember(watched, history) { watched + history.mapNotNull { it.imdb } }
    val known = remember(played, saved) { played + saved.map { it.imdb } }
    val picks = state.forYou.picks.filter { it.title.imdb !in known }.take(FOR_YOU_PICKS)
    val because = state.forYou.rails.map { (name, titles) -> name to titles.filter { it.imdb !in known } }.filter { it.second.size >= 3 }
    val charts = tops.groupBy { it.kind }.map { (kind, parts) ->
        val word = if (kind == "tv") "series" else "movies"
        val heading = if (settings.genres.isEmpty()) "Top $word on IMDb" else "Top IMDb $word in your genres"
        Triple(kind, heading, com.torrentviewer.core.mixTop(parts.map { state.loaded[it.key].orEmpty() }, CHART_SIZE))
    }
    // What's suggested, then every chosen genre in turn, so one genre doesn't fill the hero.
    // Saved titles stay: saving one took it off the billboard, and the next one slid in.
    val heroPicks = state.forYou.picks.filter { it.title.imdb !in played }.take(FOR_YOU_PICKS)
    val mixed = com.torrentviewer.core.interleave(listOf(heroPicks.map { it.title }) + chosen.map { state.loaded[it.key].orEmpty() }).filter { it.imdb !in watched }
    val heroes = mixed.ifEmpty { shelves.firstNotNullOfOrNull { state.loaded[it.key]?.takeIf { l -> l.isNotEmpty() } }.orEmpty() }.take(HEROES)
    SideEffect { state.heroCount = heroes.size }
    // A title shows once, in the first row it lands in: genre rows overlap, and the week's
    // big film otherwise led half the page. "See all" still lists a shelf in full. What's been
    // watched is left out, and so is whatever the rows above already suggest.
    val seen = HashSet(watched)
    picks.mapTo(seen) { it.title.imdb }
    because.forEach { (_, titles) -> titles.mapTo(seen) { it.imdb } }
    charts.forEach { (_, _, titles) -> titles.mapTo(seen) { it.imdb } }
    val rows = shelves.associate { s -> s.key to state.loaded[s.key].orEmpty().filter { seen.add(it.imdb) } }
    val visible = shelves.filter { state.loaded[it.key].isNullOrEmpty() || rows[it.key].orEmpty().isNotEmpty() }

    // Every row Home has right now, by a key that stays put, in Home's own order. The profile's order
    // and the rows it turned off go over it: Edit Home, at the foot of the page.
    val homeRows = mutableListOf<HomeRow>()
    if (resumable.isNotEmpty() || fromStremio.isNotEmpty()) homeRows += HomeRow("resume", "Continue Watching") {
        item(key = "resume") {
                Rail("Continue Watching") {
                    LazyRow(state = state.row("resume"), contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(resumable, key = { it.uri }) { item ->
                            Column(Modifier.width(posterWidth())) {
                                Tap({
                                    // Played from more than one release: ask which, each resuming where it was left.
                                    val all = history.filter { it.titleKey == item.titleKey }.sortedByDescending { it.at }
                                    if (all.size > 1) releases = all else watch.resume(item)
                                }, Modifier.fillMaxWidth().aspectRatio(2f / 3f), RoundedCornerShape(6.dp), onLongClick = { menu = item }) {
                                    LandscapeCard(remoteArt(item.poster) ?: item.art(), item.label, resumeStamp(item), item.ratio.toFloat())
                                }
                                Text(item.label.substringBefore(" · "), Modifier.padding(top = 6.dp), style = Type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        // No release to resume from yet: the title opens, and the one picked starts at Stremio's spot.
                        items(fromStremio, key = { "stremio:${it.imdb}" }) { p ->
                            Column(Modifier.width(posterWidth())) {
                                Tap({ onOpen(Title(p.imdb, p.kind, p.title, year = p.year)) }, Modifier.fillMaxWidth().aspectRatio(2f / 3f), RoundedCornerShape(6.dp)) {
                                    LandscapeCard(
                                        "https://images.metahub.space/poster/medium/${p.imdb}/img", p.title,
                                        // No progress: AniList's next episode up, not a spot left part-way.
                                        if (p.progress <= 0) listOfNotNull("Up next", p.ep?.let { "S${it.season}:E${it.episode}" }).joinToString("  ·  ")
                                        else listOfNotNull(p.ep?.let { "S${it.season}:E${it.episode}" }, "${p.progress.toInt()}%").joinToString("  ·  "),
                                        (p.progress / 100).toFloat(),
                                    )
                                }
                                Text(p.title, Modifier.padding(top = 6.dp), style = Type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
        }
    }
    if (state.fresh.isNotEmpty()) homeRows += HomeRow("fresh", "New Episodes") {
        item(key = "fresh") {
            Rail("New Episodes") {
                LazyRow(state = state.row("fresh"), contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                    items(state.fresh, key = { it.title.imdb }) { f ->
                        TitleCard(
                            f.title.poster ?: f.title.wide, f.title.title, { onOpen(f.title) }, Modifier.width(posterWidth()),
                            info = if (f.count == 1) "1 new episode" else "${f.count} new episodes", badge = "NEW", outline = false,
                        )
                    }
                }
            }
        }
    }
    if (state.upcoming.isNotEmpty()) homeRows += HomeRow("upcoming", "Upcoming") {
        item(key = "upcoming") {
            Rail("Upcoming", onMore = { comingAll = true }) {
                LazyRow(state = state.row("upcoming"), contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                    items(state.upcoming.distinctBy { it.title.imdb }, key = { "coming:${it.title.imdb}" }) { c ->
                        TitleCard(c.title.poster ?: c.title.wide, c.title.title, { onOpen(c.title) }, Modifier.width(posterWidth()), info = "${dayLabel(c.date)}  ·  ${c.what}", outline = false)
                    }
                }
            }
        }
    }
    if (watchlist.isNotEmpty()) homeRows += HomeRow("list", "My List") {
        item(key = "list") { Rail("My List") { TitleRow(state.row("list"), watchlist, onOpen) } }
    }
    if (picks.isNotEmpty()) homeRows += HomeRow("picks", "Picked for you") {
        item(key = "picks") { Rail("Picked for you") { PickedRow(state.row("picks"), picks, onOpen) } }
    }
    charts.forEach { (kind, heading, titles) ->
        homeRows += HomeRow("chart:$kind", heading) {
            item(key = "chart:$kind") {
                Rail(heading, onMore = { onTop(kind) }) {
                    if (titles.isEmpty()) Placeholders() else TopRow(state.row("chart:$kind"), titles, onOpen)
                }
            }
        }
    }
    // One switch for them all: which titles they name changes with every film played.
    if (because.isNotEmpty()) homeRows += HomeRow("because", "Because you watched…") {
        items(because, key = { "because:${it.first}" }) { (name, titles) ->
            Rail("Because you watched $name") { TitleRow(state.row("because:$name"), titles, onOpen) }
        }
    }
    visible.forEach { shelf ->
        homeRows += HomeRow("shelf:${shelf.key}", shelf.title) {
            item(key = shelf.key) {
                val titles = rows[shelf.key].orEmpty()
                Rail(shelf.title, onMore = { onShelf(shelf) }) {
                    if (titles.isEmpty()) Placeholders() else TitleRow(state.row(shelf.key), titles, onOpen)
                }
            }
        }
    }
    val order = arrangeRows(homeRows.map { it.key }, settings.homeOrder)
    val byKey = homeRows.associateBy { it.key }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Every row's posters, in the page's order, fetched ahead (gently, on Wi-Fi) so a scroll finds them on disk.
    val ahead = buildMap<String, List<Title>> {
        put("list", watchlist)
        charts.forEach { (kind, _, titles) -> put("chart:$kind", titles) }
        put("because", because.flatMap { it.second })
        visible.forEach { put("shelf:${it.key}", rows[it.key].orEmpty()) }
    }.let { all -> order.filter { it !in settings.homeHidden }.flatMap { all[it].orEmpty() } }.mapNotNull { it.poster ?: it.wide }.distinct()
    LaunchedEffect(ahead) { com.torrentviewer.core.precacheArt(context, ahead) }

    // What the top edge's frost blurs: the rows passing under it.
    val haze = dev.chrisbanes.haze.rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state.list, contentPadding = PaddingValues(bottom = 24.dp + LocalTabBarInset.current), verticalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.hazeSource(haze)) {
            item(key = "hero") {
                if (heroes.isEmpty()) Box(Modifier.fillMaxWidth().height(if (isWide()) 320.dp else (LocalConfiguration.current.screenWidthDp * 1.28f).dp))
                else Hero(state.hero, heroes, onOpen)
            }
            order.filter { it !in settings.homeHidden }.forEach { key -> byKey[key]?.rows?.invoke(this) }
            item(key = "edit") {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    Button("Edit Home", { state.editing = true }, icon = L.drawable.lucide_ic_settings_2, compact = true)
                }
            }
        }

        // The wordmark and who is watching, on no bar of their own: a light shade over the hero; once the rows
        // scroll under it, OpenTune's top edge — the rows blurred and washed with the page's colour, both
        // fading out downwards, so there's no strip and no line.
        val scrolled by remember { derivedStateOf { state.list.firstVisibleItemIndex > 0 || state.list.firstVisibleItemScrollOffset > 240 } }
        val solid by animateFloatAsState(if (scrolled) 1f else 0f, tween(260), label = "bar")
        StatusIconsOverArt(!scrolled)
        val page = Ink.bg
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - solid }.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
            if (solid > 0f) Box(
                Modifier.matchParentSize()
                    .layout { measurable, constraints ->
                        // Drawn a little past the wordmark's row, so it fades out under it rather than at its edge.
                        val h = constraints.maxHeight + 24.dp.roundToPx()
                        val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                    }
                    .hazeEffect(haze) {
                        alpha = solid
                        backgroundColor = page
                        tints = listOf(dev.chrisbanes.haze.HazeTint(page.copy(alpha = 0.35f)))
                        blurRadius = 20.dp
                        noiseFactor = 0f
                        inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.5f)
                        // The whole effect fades, its fill too: fading only the blur left the page colour ending in a line.
                        mask = Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent)
                    },
            )
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = EDGE, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("WATCHLY", Modifier.weight(1f), style = Type.title.copy(color = Ink.accent, fontWeight = FontWeight.Black, letterSpacing = 2.sp))
                // Joining takes the code the leader shared: right here, not three screens into Settings.
                // Not while in one: a second code walked out of the party you were in without a word.
                if (com.torrentviewer.core.Party.available && settings.partyOn && !com.torrentviewer.core.Kids.on && com.torrentviewer.core.Party.room == null) {
                    HeaderTile(L.drawable.lucide_ic_users, "Join a watch party", solid, { joiningParty = true }, haze)
                    Spacer(Modifier.width(10.dp))
                }
                NotificationBell(solid, onNotifications, haze)
                if (!isWide()) Tap(onProfile, Modifier.padding(start = 12.dp), shape = RoundedCornerShape(9.dp)) { Avatar(who, 40.dp) }
            }
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

    releases?.let { all -> ReleasesSheet(all, watch) { releases = null } }
    if (joiningParty) JoinPartySheet(who) { joiningParty = false }
    // Frosted over the page, as a film's sheets are over its page.
    androidx.compose.runtime.CompositionLocalProvider(LocalPageHaze provides haze) {
    if (state.editing) BottomSheet("Edit Home", { state.editing = false }) { HomeEditor(order.map { it to byKey.getValue(it).name }, settings) }
    if (comingAll) BottomSheet("Upcoming", { comingAll = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = EDGE, vertical = 4.dp)) {
            Text("The next ${com.torrentviewer.core.Upcoming.DAYS} days of the series you follow and the films you saved.", Modifier.padding(bottom = 12.dp), style = Type.meta)
            Button("Add to calendar", { shareCalendar(context, state.upcoming) }, icon = L.drawable.lucide_ic_calendar_plus, primary = true, compact = true)
            state.upcoming.groupBy { it.date }.forEach { (date, items) ->
                Text(dayLabel(date), Modifier.padding(top = 18.dp, bottom = 6.dp), style = Type.overline)
                items.forEach { c ->
                    Tap({ comingAll = false; onOpen(c.title) }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(c.title.title, style = Type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.what, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    }
}

/** One of Home's rows: [key] for the profile's order, [name] for Edit Home, [rows] what it puts on the page. */
class HomeRow(val key: String, val name: String, val rows: LazyListScope.() -> Unit)

/** Home's rows in the profile's order, each moved up or down or switched off where it stands. */
@Composable
private fun HomeEditor(rows: List<Pair<String, String>>, settings: Settings) {
    val keys = rows.map { it.first }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = EDGE, vertical = 4.dp)) {
        Text("Move rows up or down, or switch them off. Each profile keeps its own.", Modifier.padding(bottom = 12.dp), style = Type.meta)
        rows.forEachIndexed { i, (key, name) ->
            val on = key !in settings.homeHidden
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = Type.label.copy(color = if (on) Ink.text else Ink.muted, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                RoundIcon(L.drawable.lucide_ic_chevron_up, { if (i > 0) SettingsStore.save(settings.copy(homeOrder = movedRow(keys, key, -1))) }, Modifier.padding(start = 6.dp), 36.dp)
                RoundIcon(L.drawable.lucide_ic_chevron_down, { if (i < keys.lastIndex) SettingsStore.save(settings.copy(homeOrder = movedRow(keys, key, 1))) }, Modifier.padding(start = 6.dp), 36.dp)
                RoundIcon(
                    if (on) L.drawable.lucide_ic_eye else L.drawable.lucide_ic_eye_off,
                    { SettingsStore.save(settings.copy(homeHidden = if (on) settings.homeHidden + key else settings.homeHidden - key)) },
                    Modifier.padding(start = 6.dp), 36.dp,
                )
            }
        }
        Button("Reset to default", { SettingsStore.save(settings.copy(homeOrder = emptyList(), homeHidden = emptyList())) }, Modifier.padding(top = 14.dp, bottom = 8.dp), icon = L.drawable.lucide_ic_rotate_ccw, compact = true)
    }
}

/** Every release a title was played from, each with its size and where it was left; picking one resumes it. */
@Composable
fun ReleasesSheet(all: List<Item>, watch: Watch, onDismiss: () -> Unit) {
    Sheet(
        title = all.first().label.substringBefore(" · "),
        options = all.map {
            val name = it.torrentName ?: it.title
            val hint = listOfNotNull(it.playedSize()?.let(::bytes), it.seeders?.let { s -> "$s seeds" }, resumeStamp(it) ?: "Not started")
            Option(it.uri, name, hint.joinToString("  ·  "), tag = quality(name))
        },
        selected = latestPerTitle(all).first().uri,
        onPick = { uri ->
            onDismiss()
            all.find { it.uri == uri }?.let { watch.resume(it) }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun Rail(heading: String, onMore: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = EDGE, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // "Because you watched …" names a title in full: two lines rather than cut off.
            Text(heading, Modifier.weight(1f), style = Type.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    Row(Modifier.padding(start = EDGE), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        repeat(4) { CardPlaceholder(Modifier.width(posterWidth())) }
    }
}

@Composable
private fun TitleRow(state: LazyListState, titles: List<Title>, onOpen: (Title) -> Unit) {
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        items(titles, key = { it.imdb }) { t ->
            TitleCard(t.poster ?: t.wide, t.title, { onOpen(t) }, Modifier.width(posterWidth()), info = t.year?.take(4), rating = t.rating, outline = false)
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
    // Moves on by itself; a finger on it starts the wait again, and so does every swipe. Not keyed on any
    // scroll, or moving on would cancel itself.
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager.settledPage, count, dragged) {
        if (count <= 1 || dragged) return@LaunchedEffect
        delay(HERO_DWELL_MS.toLong())
        pager.animateScrollToPage((pager.settledPage + 1) % count, animationSpec = tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val wide = isWide()
    // Upright, near poster-tall: the art had the logo, facts and buttons crowding its foot, and at 1.22 it read cut short.
    // ponytail: by width alone; 1.28 leaves Continue Watching's names in view above the dock on a ~20:9 phone. Tune here.
    val height = if (wide) (LocalConfiguration.current.screenHeightDp * 0.82f).dp else (LocalConfiguration.current.screenWidthDp * 1.28f).dp
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { titles.getOrNull(it)?.imdb ?: it }) { page ->
            val start = titles.getOrNull(page) ?: return@HorizontalPager
            // The shelf row carries no logo or runtime; the full entry does.
            val t by produceState(start, start.imdb) { Meta.title(start.imdb, start.kind)?.let { value = it } }
            // The whole billboard opens the title: no Watch button, the card is the button.
            val open = Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(t) }
            if (wide) {
                Box(open) {
                    Artwork(t.wide, t.title, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight().heroScrims(), fill = false)
                    Column(Modifier.align(Alignment.CenterStart).padding(start = EDGE + 8.dp, top = 40.dp).widthIn(max = 380.dp)) {
                        TitleMark(t, Modifier.widthIn(max = 300.dp), maxHeight = 72.dp, style = Type.display)
                        Facts(t.copy(genres = t.genres.take(2)), Modifier.padding(top = 12.dp))
                        t.plot?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.body.copy(color = Ink.text.copy(alpha = 0.78f)), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            } else {
                Box(open) {
                    Artwork(t.wide, t.title, Modifier.fillMaxSize().bottomScrim(0.35f), fill = false)
                    // As Netflix and Disney+ stack it: the logo, one line of facts, the genres under it, with room under
                    // it for the dots.
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = EDGE, end = EDGE, bottom = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.widthIn(max = 260.dp), contentAlignment = Alignment.Center) {
                            TitleMark(t, maxHeight = 88.dp, style = Type.display.copy(textAlign = TextAlign.Center))
                        }
                        HeroFacts(t, Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
        if (count > 1) HeroDots(pager, count, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}

/** How long a billboard stays before the next comes in. */
private const val HERO_DWELL_MS = 8000

/**
 * The billboard's dots, following the finger: as a swipe goes, the dot it heads for widens into a pill and
 * brightens, and the one it leaves narrows and dims, both by how far across it is. Where the swipe is gets
 * read only while laying them out and drawing, so a swipe costs no recomposition.
 */
@Composable
private fun HeroDots(pager: PagerState, count: Int, modifier: Modifier = Modifier) {
    val ink = Ink.text
    fun near(i: Int) = (1f - kotlin.math.abs(pager.currentPage + pager.currentPageOffsetFraction - i)).coerceIn(0f, 1f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val w = (7.dp + 20.dp * near(i)).roundToPx()
                        val h = 7.dp.roundToPx()
                        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        layout(w, h) { placeable.place(0, 0) }
                    }
                    .drawBehind {
                        // The open one in full, the rest faint.
                        drawRoundRect(ink.copy(alpha = 0.3f + 0.7f * near(i)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                    },
            )
        }
    }
}

/** The rating, year and running time on one line; the genres, in the viewer's words, on a quieter one under it. */
@Composable
private fun HeroFacts(t: Title, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            t.rating?.let {
                ImdbBadge(it)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                listOfNotNull(t.year?.take(9), t.runtime?.let(::runtimeLabel)).joinToString("  ·  "),
                style = Type.meta.copy(color = Ink.text.copy(alpha = 0.85f), fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        if (t.genres.isNotEmpty()) Text(
            t.genres.take(3).joinToString("  •  ") { com.torrentviewer.core.tr(it) },
            Modifier.padding(top = 6.dp),
            style = Type.meta.copy(color = Ink.text.copy(alpha = 0.68f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}