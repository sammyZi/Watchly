@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torrentviewer.mobile

import dev.chrisbanes.haze.hazeSource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.torrentviewer.core.Updates
import com.torrentviewer.core.savedPicture
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.torrentviewer.core.Backup
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.latestPerTitle
import com.torrentviewer.core.left
import com.torrentviewer.core.remoteArt
import com.torrentviewer.core.titleKey
import com.torrentviewer.core.LANGS
import com.torrentviewer.core.Phase
import com.torrentviewer.core.SUB_SIZES
import com.torrentviewer.core.langName
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.PROFILE_ANIMALS
import com.torrentviewer.core.PROFILE_ICONS
import com.torrentviewer.core.PROFILE_PEOPLE
import androidx.compose.foundation.clickable
import com.torrentviewer.core.personOf
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.CACHE_CAPS
import com.torrentviewer.core.cacheCapLabel
import com.torrentviewer.core.COLOURS
import com.torrentviewer.core.GENRES
import com.torrentviewer.core.GUEST
import com.torrentviewer.core.Hit
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Item
import com.torrentviewer.core.Lc
import com.torrentviewer.core.ACCENTS
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Note
import com.torrentviewer.core.Profile
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.Searches
import com.torrentviewer.core.Secrets
import com.torrentviewer.core.Stremio
import com.torrentviewer.core.TorBox
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Kids
import com.torrentviewer.core.Addons
import com.torrentviewer.core.bytes
import com.torrentviewer.core.playedSize
import com.torrentviewer.core.DeviceCode
import com.torrentviewer.core.Settings
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Sort
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.ago
import com.torrentviewer.core.AvatarImage
import com.torrentviewer.core.Avatars
import androidx.compose.foundation.border
import com.torrentviewer.core.colorOf
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.searchStreams
import com.torrentviewer.core.sortTitles
import com.torrentviewer.core.ADDON_SHELF
import com.torrentviewer.core.TOP_SHELF
import com.torrentviewer.core.imdbScore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** Poster columns: three across a phone held upright. */
private val POSTER = GridCells.Adaptive(104.dp)

private fun subOf(t: Title) = listOfNotNull(if (t.isSeries) "Series" else "Film", t.year?.take(4)).joinToString("  ·  ")

/* -------------------------------------------------------------- catalog */

/** A whole shelf as a grid, with the phone's four orders. */
@Composable
fun CatalogScreen(shelf: Shelf, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    var sort by rememberSaveable { mutableStateOf(Sort.POPULAR) }
    // Top rated is the genre's real best, not just its fifty most popular reordered. An addon's list is only itself.
    val source = if (sort == Sort.RATING && shelf.genre?.startsWith(ADDON_SHELF) != true) TOP_SHELF + shelf.genre.orEmpty() else shelf.genre
    var titles by remember(source) { mutableStateOf(Meta.peekCatalog(shelf.kind, source)) }
    LaunchedEffect(source) { if (titles.isNullOrEmpty()) titles = Meta.catalog(shelf.kind, source) }
    val shown = remember(titles, sort) { sortTitles(Kids.only(titles.orEmpty()), sort) }
    val grid = rememberLazyGridState()
    val haze = dev.chrisbanes.haze.rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            POSTER,
            state = grid,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
            contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 24.dp + LocalTabBarInset.current),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Heading(shelf.title, Modifier.padding(start = 48.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Sort.entries.forEach { s -> Chip(s.label, s == sort, onClick = { sort = s }) }
                    }
                }
            }
            if (titles == null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = 40.dp), contentAlignment = Alignment.Center) { Spinner(size = 36.dp) } }
            else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Note("Nothing on this shelf right now.") }
            items(shown, key = { it.imdb }) { t -> PosterCard(t, { onOpen(t) }, sub = subOf(t)) }
        }
        FrostBackBar(haze, grid.scrolled(), shelf.title, onBack)
    }
}

/* --------------------------------------------------------------- search */

/** "2020s"… and before 1990 one bucket; the year a title's releaseInfo starts with. */
private val DECADES = listOf(2020 to "2020s", 2010 to "2010s", 2000 to "2000s", 1990 to "1990s", 0 to "Before 1990")

private fun decadeOf(t: Title) = t.year?.take(4)?.toIntOrNull()?.let { if (it < 1990) 0 else it / 10 * 10 }

/** Which filter's list is up. */
private enum class Picking { KIND, GENRE, DECADE, RATING, SORT, QUALITY, ORDER }

/** How releases are ordered: the ranking (seeds, then fit), or by size. */
private enum class HitOrder(val label: String) { BEST("Best match"), BIGGEST("Biggest"), SMALLEST("Smallest") }

/**
 * Results arrive as the letters do; the Torrents switch searches release names instead.
 * Filters narrow either list — film or series, genre, decade and order for titles; quality
 * and size for releases. A genre with nothing typed browses that genre's shelf.
 */
/** The last search's answer, kept past the screen leaving: opening a title from it and coming back asks nothing again. */
private object LastSearch {
    var key: List<Any?>? = null
    var results: List<Title>? = null
    var hits: List<Hit>? = null
}

@Composable
fun SearchScreen(watch: Watch, onOpen: (Title) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // Off again whenever kids mode changes, so a kids profile never lands on the torrent list.
    var torrents by rememberSaveable(Kids.on) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(Searches.load()) }
    // Filters: null is "any".
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var decade by rememberSaveable { mutableStateOf<Int?>(null) }
    var atLeast by rememberSaveable { mutableStateOf<Int?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.POPULAR) }
    var quality by rememberSaveable { mutableStateOf<String?>(null) }
    var order by rememberSaveable { mutableStateOf(HitOrder.BEST) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val filtered = !torrents && (kind != null || genre != null || decade != null || atLeast != null || sort != Sort.POPULAR) || torrents && (quality != null || order != HitOrder.BEST)
    /** Going by rating: browse the best of the genre, or of everything, not only what's popular this week. */
    val byRating = sort == Sort.RATING || atLeast != null
    // Back from a title, the same search: what it found is there at once, so the grid goes back to its place
    // rather than to an empty page that fills in from the top.
    val searchKey = listOf<Any?>(query.trim(), torrents, genre, kind, byRating, Kids.on)
    var results by remember { mutableStateOf(LastSearch.results.takeIf { LastSearch.key == searchKey }) }
    var hits by remember { mutableStateOf(LastSearch.hits.takeIf { LastSearch.key == searchKey }) }
    fun keep() {
        LastSearch.key = searchKey
        LastSearch.results = results
        LastSearch.hits = hits
    }

    LaunchedEffect(query, torrents, genre, kind, byRating) {
        if (LastSearch.key == searchKey && (results != null || hits != null)) return@LaunchedEffect
        val q = query.trim()
        if (q.length < 2) {
            hits = null
            // Nothing typed, a genre picked: that genre's shelf, films and series together unless one is chosen.
            val g = if (byRating) TOP_SHELF + genre.orEmpty() else genre
            results = if (!torrents && g != null) {
                busy = true
                coroutineScope {
                    val films = async { if (kind == null || kind == "movie") Meta.catalog("movie", g) else emptyList() }
                    val series = async { if (kind == null || kind == "tv") Meta.catalog("tv", g) else emptyList() }
                    // Turn about, so films and series both lead: one after the other, the series only began past
                    // every film. Cinemeta's film lists carry some series too (Cyberpunk: Edgerunners): those go from
                    // the films, or the film's copy could come first and open with no seasons.
                    val shows = series.await()
                    val ids = shows.mapTo(HashSet()) { it.imdb }
                    Kids.only(com.torrentviewer.core.interleave(listOf(shows, films.await().filter { it.imdb !in ids })))
                }
            } else null
            busy = false
            keep()
            return@LaunchedEffect
        }
        delay(400)
        busy = true
        // A kids profile has no torrent search (a release name can't be vetted) and sees family titles only.
        if (torrents && !Kids.on) {
            hits = searchStreams(q) { hits = it }
        } else {
            // Films, series and every addon catalogue that searches, as Stremio asks them all: most wanted first.
            results = Kids.only(Meta.searchAll(q))
        }
        busy = false
        keep()
    }
    // A search's answers carry no IMDb score (a catalogue's do): with a rating asked for, each one's is looked up —
    // kept once known, four at a time — or "IMDb 7+" found nothing at all.
    LaunchedEffect(results, atLeast) {
        val list = results ?: return@LaunchedEffect
        if (atLeast == null || list.none { it.rating == null && it.imdb.startsWith("tt") }) return@LaunchedEffect
        busy = true
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val rated = coroutineScope {
            list.map { t ->
                async {
                    if (t.rating != null || !t.imdb.startsWith("tt")) t
                    else gate.withPermit { Meta.ratingOf(t.imdb, t.kind)?.let { t.copy(rating = it) } ?: t }
                }
            }.awaitAll()
        }
        busy = false
        // Unchanged when none had a score to find: no new list, so this doesn't run again for it.
        if (rated != list) { results = rated; keep() }
    }
    val browsing = query.trim().length < 2
    val titles = results?.let { list ->
        sortTitles(
            list.filter { t ->
                (kind == null || t.kind == kind) && (decade == null || decadeOf(t) == decade) &&
                    atLeast.let { it == null || (t.imdbScore ?: 0.0) >= it } &&
                    // A genre's own shelf is that genre already; a search result says so in its genres.
                    (genre == null || browsing || genre in t.genres)
            },
            sort,
        )
    }
    val releases = hits?.let { list ->
        val q = list.filter { quality == null || it.quality == quality }
        when (order) {
            HitOrder.BEST -> q
            HitOrder.BIGGEST -> q.sortedByDescending { it.size }
            HitOrder.SMALLEST -> q.sortedBy { it.size }
        }
    }

    val grid = rememberLazyGridState()
    val haze = dev.chrisbanes.haze.rememberHazeState()
    // The box stays put at the top, on frost, and the results run on under it.
    var pinned by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val pinnedDp = with(androidx.compose.ui.platform.LocalDensity.current) { pinned.toDp() }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        POSTER,
        state = grid,
        modifier = Modifier.fillMaxSize().imePadding().hazeSource(haze),
        contentPadding = PaddingValues(start = EDGE, end = EDGE, top = pinnedDp, bottom = 24.dp + LocalTabBarInset.current),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                // A kids profile searches titles only.
                if (!Kids.on) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Titles", !torrents, onClick = { torrents = false; hits = null })
                    Chip("Streams", torrents, onClick = { torrents = true; results = null }, icon = L.drawable.lucide_ic_layers)
                }
                // One chip per filter, red while it narrows anything.
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!torrents) {
                        Chip(when (kind) { "movie" -> "Films"; "tv" -> "Series"; else -> "Films & series" }, kind != null, onClick = { picking = Picking.KIND })
                        Chip(genre ?: "Any genre", genre != null, onClick = { picking = Picking.GENRE })
                        Chip(DECADES.find { it.first == decade }?.second ?: "Any year", decade != null, onClick = { picking = Picking.DECADE })
                        Chip(atLeast?.let { "IMDb $it+" } ?: "Any rating", atLeast != null, onClick = { picking = Picking.RATING })
                        Chip(if (sort == Sort.POPULAR) "Best match" else sort.label, sort != Sort.POPULAR, onClick = { picking = Picking.SORT })
                    } else {
                        Chip(quality ?: "Any quality", quality != null, onClick = { picking = Picking.QUALITY })
                        Chip(order.label, order != HitOrder.BEST, onClick = { picking = Picking.ORDER })
                    }
                    if (filtered) Chip("Clear", false, icon = L.drawable.lucide_ic_x, onClick = {
                        kind = null; genre = null; decade = null; atLeast = null; sort = Sort.POPULAR; quality = null; order = HitOrder.BEST
                    })
                }
                if (recents.isNotEmpty() && results == null && hits == null) {
                    Row(Modifier.padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", Modifier.weight(1f), style = Type.title.copy(fontWeight = FontWeight.Bold))
                        Tap({ recents = Searches.clear() }, shape = CircleShape) { Text("Clear", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = Type.meta) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recents.forEach { r -> Chip(r, false, onClick = { query = r }, icon = L.drawable.lucide_ic_history) }
                    }
                }
                val q = query.trim()
                val heading = when {
                    torrents && releases != null -> if (releases.isEmpty()) if (busy) "Searching…" else if (hits!!.isNotEmpty()) "No releases match the filters" else "No releases for “$q”" else "${releases.size} releases" + if (busy) "  ·  finding more…" else ""
                    !torrents && titles != null -> when {
                        titles.isNotEmpty() -> if (browsing) genre ?: "Top results" else "Top results"
                        results!!.isNotEmpty() -> "Nothing matches the filters"
                        else -> "Nothing found for “$q”"
                    }
                    else -> null
                }
                heading?.let { Text(it, Modifier.padding(top = 24.dp), style = Type.title.copy(fontWeight = FontWeight.Bold)) }
            }
        }
        if (torrents) {
            items(releases.orEmpty(), key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { hit ->
                Source(hit, onDownload = {
                    Downloads.add(downloadJob(hit, null))
                    watch.notes.tryEmit("Downloading ${hit.name} — see Downloads")
                }) {
                    recents = Searches.remember(query)
                    watch.stream(hit, null)
                }
            }
        } else {
            items(titles.orEmpty(), key = { it.imdb }) { t ->
                PosterCard(t, {
                    if (!browsing) recents = Searches.remember(query)
                    onOpen(t)
                }, sub = subOf(t))
            }
        }
    }
    FrostTop(haze, grid.scrolled(), Modifier.onSizeChanged { pinned = it.height }) {
        Column(Modifier.padding(start = EDGE, end = EDGE, bottom = 12.dp)) {
            Heading("Search")
            Field(
                query,
                { query = it },
                if (torrents) "A film, or a link to play" else "Movies, shows, anime",
                Modifier.fillMaxWidth(),
                leading = L.drawable.lucide_ic_search,
                onSubmit = { recents = Searches.remember(query) },
                trailing = {
                    when {
                        busy -> Spinner(size = 20.dp)
                        query.isNotEmpty() -> Tap({ query = "" }, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(4.dp), tint = Ink.muted, size = 18.dp) }
                    }
                },
            )
        }
    }
    }

    // Frosted over the results, as a film's language list is over its page.
    picking?.let { which -> androidx.compose.runtime.CompositionLocalProvider(LocalPageHaze provides haze) {
        val close = { picking = null }
        when (which) {
            Picking.KIND -> Sheet("Show", listOf(Option<String?>(null, "Films & series"), Option("movie", "Films only"), Option("tv", "Series only")), kind, { kind = it; close() }, close)
            Picking.GENRE -> Sheet("Genre", listOf(Option<String?>(null, "Any genre")) + GENRES.map { Option<String?>(it, it) }, genre, { genre = it; close() }, close)
            Picking.DECADE -> Sheet("Year", listOf(Option<Int?>(null, "Any year")) + DECADES.map { (y, label) -> Option<Int?>(y, label) }, decade, { decade = it; close() }, close)
            Picking.RATING -> Sheet("IMDb rating", listOf(Option<Int?>(null, "Any rating")) + listOf(6, 7, 8, 9).map { Option<Int?>(it, "IMDb $it+") }, atLeast, { atLeast = it; close() }, close)
            Picking.SORT -> Sheet("Order", Sort.entries.map { Option(it, if (it == Sort.POPULAR) "Best match" else it.label) }, sort, { sort = it; close() }, close)
            Picking.QUALITY -> Sheet("Quality", listOf(Option<String?>(null, "Any quality")) + listOf("4K", "1080p", "720p").map { Option<String?>(it, it) }, quality, { quality = it; close() }, close)
            Picking.ORDER -> Sheet("Order", HitOrder.entries.map { Option(it, it.label) }, order, { order = it; close() }, close)
        }
    } }
}

/* -------------------------------------------------------------- library */

private enum class Shelfs(val label: String) { HISTORY("History"), LATER("My List"), WATCHED("Watched"), FAVORITE("Favourites") }

/**
 * [all] in two sections, Movies then Series, each under its heading — a section with nothing in it is left out.
 * [prefix] keeps keys apart where a shelf shows two such lists.
 */
private fun <T> androidx.compose.foundation.lazy.grid.LazyGridScope.inSections(
    prefix: String, all: List<T>, isSeries: (T) -> Boolean, key: (T) -> Any, content: @Composable (T) -> Unit,
) {
    listOf("Movies" to all.filterNot(isSeries), "Series" to all.filter(isSeries)).forEach { (name, part) ->
        if (part.isEmpty()) return@forEach
        item(key = "$prefix:h:$name", span = { GridItemSpan(maxLineSpan) }) {
            Text(name, Modifier.padding(top = 6.dp), style = Type.label.copy(color = Ink.muted, fontWeight = FontWeight.SemiBold))
        }
        items(part, key = { "$prefix:${key(it)}" }) { content(it) }
    }
}

/** The History's "This phone" chip: what was played here, none of the accounts'. */
private const val PHONE = "phone"

/** Everything played (the only way back to a magnet watched once), and the two saved lists. */
@Composable
fun LibraryScreen(watch: Watch, me: Profile, onOpen: (Title) -> Unit, onDownloads: () -> Unit) {
    val downloading by Downloads.live.collectAsState()
    val stremio by Stremio.connected.collectAsState()
    /** Per profile; the guest is offered nothing it would lose on exit. */
    var stremioLater by remember(me.id) { mutableStateOf(me.isGuest || Kv.get(STREMIO_LATER) != null) }
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()
    var shelf by rememberSaveable { mutableStateOf(Shelfs.HISTORY) }
    var menu by remember { mutableStateOf<Item?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var releases by remember { mutableStateOf<List<Item>?>(null) }
    // One row per title, however many torrents it was played from: its latest one.
    val items = latestPerTitle(history)

    // Each signed-in account and tracker, with what it has as watched: the History's own chips pick one.
    val accounts = listOf(
        "Stremio" to (stremio to Stremio.library.collectAsState().value),
        "Nuvio" to (com.torrentviewer.core.Nuvio.connected.collectAsState().value to com.torrentviewer.core.Nuvio.library.collectAsState().value),
        com.torrentviewer.core.Simkl.name to (com.torrentviewer.core.Simkl.connected.collectAsState().value to com.torrentviewer.core.Simkl.library.collectAsState().value),
        com.torrentviewer.core.Mdblist.name to (com.torrentviewer.core.Mdblist.connected.collectAsState().value to com.torrentviewer.core.Mdblist.library.collectAsState().value),
        com.torrentviewer.core.AniList.NAME to (com.torrentviewer.core.AniList.connected.collectAsState().value to com.torrentviewer.core.AniList.library.collectAsState().value),
    ).filter { (_, s) -> s.first }.map { (name, s) -> name to s.second }
    /** null is everything; [PHONE] what was played here; else an account's name. */
    var from by rememberSaveable(me.id) { mutableStateOf<String?>(null) }
    if (from != null && from != PHONE && accounts.none { it.first == from }) from = null
    val allPaused = accounts.flatMap { it.second.paused }
    // After a reinstall this phone's history is empty; what the accounts have still belongs here. Each title once, the first account that has it.
    val fromAccounts = remember(accounts, history, from, Kids.on, Kids.allowed) {
        val picked = when (from) {
            null -> accounts.flatMap { (name, lib) -> lib.notIn(history).map { it to name } }.distinctBy { it.first.imdb }
            PHONE -> emptyList()
            else -> accounts.find { it.first == from }?.let { (name, lib) -> lib.notIn(emptyList()).map { it to name } }.orEmpty()
        }
        Kids.only(picked) { Title(it.first.imdb, it.first.kind, it.first.title) }
    }
    val fromStremio = fromAccounts.map { it.first }
    val sources = history.groupingBy { it.titleKey }.eachCount()
    // My List's own chips: Stremio's and Nuvio's libraries are saved lists like this one (the trackers' are what was watched).
    val lists = accounts.filter { it.first == "Stremio" || it.first == "Nuvio" }
    /** null is this phone's list and the accounts' together; [PHONE] this phone's alone; else an account's name. */
    var listFrom by rememberSaveable(me.id) { mutableStateOf<String?>(null) }
    if (listFrom != null && listFrom != PHONE && lists.none { it.first == listFrom }) listFrom = null
    val synced by com.torrentviewer.core.Synced.collectAsState(com.torrentviewer.core.syncedNow())
    // Films seen through, here or on an account: off My List, on the Watched shelf.
    val watched = com.torrentviewer.core.watchedFilms(history, synced)
    val listed = when {
        shelf == Shelfs.FAVORITE -> saved.filter { it.list == "favorite" }.map { it.asTitle() }
        shelf == Shelfs.WATCHED -> com.torrentviewer.core.watchedShelf(history, synced)
        listFrom == null -> com.torrentviewer.core.myList(saved, lists.flatMap { it.second.seen }, watched)
        listFrom == PHONE -> com.torrentviewer.core.myList(saved, emptyList(), watched)
        else -> com.torrentviewer.core.myList(emptyList(), lists.find { it.first == listFrom }?.second?.seen.orEmpty(), watched)
    }

    val grid = rememberLazyGridState()
    val haze = dev.chrisbanes.haze.rememberHazeState()
    // The heading and the shelf chips stay put at the top, on frost, and the shelf runs on under them.
    var pinned by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val pinnedDp = with(androidx.compose.ui.platform.LocalDensity.current) { pinned.toDp() }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        if (shelf == Shelfs.HISTORY) GridCells.Adaptive(340.dp) else POSTER,
        state = grid,
        modifier = Modifier.fillMaxSize().hazeSource(haze),
        contentPadding = PaddingValues(start = EDGE, end = EDGE, top = pinnedDp, bottom = 24.dp + LocalTabBarInset.current),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                // Where the history is, the history's sync is offered.
                if (Stremio.available && !stremio && !stremioLater) {
                    StremioSection(me, onLater = {
                        Kv.set(STREMIO_LATER, "1")
                        stremioLater = true
                    })
                }
            }
        }
        if (shelf == Shelfs.HISTORY) {
            val local = if (from == null || from == PHONE) items else emptyList()
            if (local.isEmpty() && fromStremio.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Note(if (from == null || from == PHONE) "Nothing here yet. Anything you play turns up in this list." else "Nothing watched on $from yet.", Modifier.padding(top = 16.dp))
            }
            // Movies, then Series: what was played here, and after it what the accounts have, each split the same way.
            inSections("here", local, { it.kind == "tv" }, { it.titleKey }) { item ->
                HistoryRow(item, sources[item.titleKey] ?: 1, onPlay = {
                    // Played from more than one release: ask which, as Continue watching does.
                    val all = history.filter { it.titleKey == item.titleKey }.sortedByDescending { it.at }
                    if (all.size > 1) releases = all else watch.resume(item)
                }, onMore = { menu = item })
            }
            if (from == null && local.isNotEmpty() && fromAccounts.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("From your accounts", Modifier.padding(top = 18.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
            }
            inSections("account", fromAccounts, { it.first.kind == "tv" }, { it.first.imdb }) { (s, source) ->
                StremioRow(s, source, allPaused.find { it.imdb == s.imdb }) { onOpen(Title(s.imdb, s.kind, s.title, year = s.year)) }
            }
        } else {
            if (listed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Note(
                    when (shelf) {
                        Shelfs.LATER -> "Titles you add to My List wait here."
                        Shelfs.WATCHED -> "Films you watch to the end, and series you have watched episodes of, collect here."
                        else -> "Titles you like collect here."
                    },
                    Modifier.padding(top = 16.dp),
                )
            }
            inSections(shelf.name, listed, { it.isSeries }, { it.imdb }) { t -> PosterCard(t, { onOpen(t) }, sub = subOf(t)) }
        }
    }
    FrostTop(haze, grid.scrolled(), Modifier.onSizeChanged { pinned = it.height }) {
        Column(Modifier.padding(start = EDGE, end = EDGE, bottom = 12.dp)) {
            Heading("Library") {
                if (shelf == Shelfs.HISTORY && items.isNotEmpty()) Tap({ clearing = true }, shape = CircleShape) {
                    Lc(L.drawable.lucide_ic_trash_2, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp)
                }
                // Downloads live here now, off the tab bar; the dot says one is running.
                Tap(onDownloads, shape = CircleShape) {
                    Box {
                        Lc(L.drawable.lucide_ic_download, Modifier.padding(8.dp), tint = Ink.muted, size = 22.dp)
                        if (downloading.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).clip(CircleShape).background(Ink.accent))
                    }
                }
            }
            // The four shelves as tabs, each as wide as its word and spread edge to edge with even gaps — equal shares of
            // the width ran the long ones together — the open one underlined across its word in the accent.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Shelfs.entries.forEach { s ->
                    val on = s == shelf
                    Tap({ shelf = s }, shape = RoundedCornerShape(6.dp)) {
                        Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).padding(top = 6.dp)) {
                            Text(
                                s.label, maxLines = 1,
                                style = Type.label.copy(fontSize = 15.sp, color = if (on) Ink.text else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                            )
                            Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) Ink.accent else Color.Transparent))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.stroke.copy(alpha = 0.5f)))
            // Whose: one picker, not a row of chips. History: this phone or any signed-in account or tracker;
            // My List: this phone, Stremio or Nuvio. Only with one signed in.
            val pickFrom = when (shelf) {
                Shelfs.HISTORY -> accounts.map { it.first }
                Shelfs.LATER -> lists.map { it.first }
                else -> emptyList()
            }
            if (pickFrom.isNotEmpty()) {
                val current = if (shelf == Shelfs.HISTORY) from else listFrom
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Showing", Modifier.padding(end = 10.dp), style = Type.meta.copy(color = Ink.faint))
                    Dropdown(
                        when (current) { null -> "All"; PHONE -> "This phone"; else -> current },
                        listOf("All", "This phone") + pickFrom,
                    ) { picked ->
                        val next = when (picked) { "All" -> null; "This phone" -> PHONE; else -> picked }
                        if (shelf == Shelfs.HISTORY) from = next else listFrom = next
                    }
                }
            }
        }
    }
    }

    menu?.let { item ->
        val name = item.titleName?.substringBefore(" · ") ?: item.label
        Sheet(
            title = name,
            options = listOfNotNull(
                Option("play", "Resume"),
                item.imdb?.let { Option("info", "Details") },
                Option("remove", "Remove from Library", if ((sources[item.titleKey] ?: 1) > 1) "With every release it was played from" else null),
            ),
            selected = null,
            onPick = {
                menu = null
                when (it) {
                    "play" -> watch.resume(item)
                    "info" -> item.imdb?.let { id -> onOpen(Title(id, item.kind ?: "movie", name, poster = item.poster, backdrop = item.backdrop)) }
                    "remove" -> History.dropTitle(item.titleKey, watch.engine)
                }
            },
            onDismiss = { menu = null },
        )
    }
    releases?.let { all -> ReleasesSheet(all, watch) { releases = null } }
    if (clearing) {
        Sheet(
            title = "Clear everything you have played?",
            options = listOf(Option(true, "Clear all", "Resume points and downloaded subtitles go too"), Option(false, "Cancel")),
            selected = null,
            onPick = {
                if (it) History.clear(watch.engine)
                clearing = false
            },
            onDismiss = { clearing = false },
        )
    }
}

/** Where a title was left: "S1:E3 · 23m left", "Watched", or nothing yet. */
fun whereLeft(item: Item): String? {
    val index = item.lastFileIndex
    val ep = index?.let { i -> item.files?.find { it.index == i } }?.let { parseEpisode(it.name) }?.let { "S${it.season}:E${it.episode}" }
    val r = item.ratio
    val state = when {
        r >= 0.95 -> "Watched"
        r > 0.0 -> index?.let { i -> item.durations[i]?.let { d -> item.positions[i]?.let { left(d - it) } } }
        else -> null
    }
    return listOfNotNull(ep, state).joinToString("  ·  ").ifEmpty { null }
}

/**
 * A title an account or tracker has as watched — [source] names it. No release to resume, so it opens
 * the title; one picked there starts where that account says it was paused.
 */
@Composable
private fun StremioRow(s: com.torrentviewer.core.Seen, source: String, paused: com.torrentviewer.core.Paused?, onOpen: () -> Unit) {
    Tap(onOpen, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) { pressed ->
        Row(Modifier.background(if (pressed) Ink.surface2 else Ink.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
                Artwork("https://images.metahub.space/poster/medium/${s.imdb}/img", s.title, Modifier.fillMaxSize())
                paused?.takeIf { it.progress > 0 }?.let { ProgressLine((it.progress / 100).toFloat(), Modifier.align(Alignment.BottomStart), height = 3.dp) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val state = when {
                    // No progress: the next episode up, from AniList.
                    paused != null && paused.progress <= 0 -> listOfNotNull("Up next", paused.ep?.let { "S${it.season}:E${it.episode}" }).joinToString("  ·  ")
                    paused != null -> listOfNotNull(s.last, "${paused.progress.toInt()}% in").joinToString("  ·  ")
                    s.kind == "movie" -> "Watched"
                    else -> s.last?.let { "Last watched $it" } ?: "Watched"
                }
                Text(state, Modifier.padding(top = 3.dp), style = Type.meta.copy(color = if (paused == null) Ink.good else Ink.text.copy(alpha = 0.8f)), maxLines = 1)
                Text(
                    listOfNotNull(s.year?.take(4), com.torrentviewer.core.isoMs(s.at)?.let { ago(it) }, "on $source").joinToString("  ·  "),
                    Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 12.sp, color = Ink.faint), maxLines = 1,
                )
            }
            Lc(L.drawable.lucide_ic_chevron_right, tint = Ink.muted, size = 20.dp)
        }
    }
}

/** One title in the Library: its poster with the progress along the foot, where it was left, when, and a menu. */
@Composable
private fun HistoryRow(item: Item, releases: Int, onPlay: () -> Unit, onMore: () -> Unit) {
    Tap(onPlay, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), onLongClick = onMore) { pressed ->
        Row(Modifier.background(if (pressed) Ink.surface2 else Ink.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
                Artwork(remoteArt(item.poster) ?: item.art(), item.label, Modifier.fillMaxSize())
                if (item.ratio > 0) ProgressLine(item.ratio.toFloat(), Modifier.align(Alignment.BottomStart), height = 3.dp)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.titleName?.substringBefore(" · ") ?: item.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                whereLeft(item)?.let { Text(it, Modifier.padding(top = 3.dp), style = Type.meta.copy(color = if (item.ratio >= 0.95) Ink.good else Ink.text.copy(alpha = 0.8f)), maxLines = 1) }
                Text(
                    listOfNotNull(ago(item.at), item.playedSize()?.let(::bytes), if (releases > 1) "$releases releases" else null).joinToString("  ·  "),
                    Modifier.padding(top = 2.dp),
                    style = Type.meta.copy(fontSize = 12.sp, color = Ink.faint),
                    maxLines = 1,
                )
            }
            Box(Modifier.size(38.dp).clip(CircleShape).background(Ink.accent), contentAlignment = Alignment.Center) {
                Lc(L.drawable.lucide_ic_play, tint = Ink.onAccent, size = 18.dp)
            }
            Tap(onMore, shape = CircleShape) { Lc(L.drawable.lucide_ic_ellipsis_vertical, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp) }
        }
    }
}

/* ------------------------------------------------------------- settings */

@Composable
fun SettingsScreen(watch: Watch, who: Profile, onSwitchProfile: () -> Unit, onEditHome: () -> Unit = {}) {
    val settings by SettingsStore.value.collectAsState()
    val profiles by Profiles.list.collectAsState()
    val me = profiles.find { it.id == who.id } ?: who
    var pin by remember { mutableStateOf("") }
    /** The PIN as it stands, typed to change or remove it. */
    var current by remember { mutableStateOf("") }
    /** "audio" or "subs" while that language list is up. */
    var picking by remember { mutableStateOf<String?>(null) }
    var choosingLanguage by remember { mutableStateOf(false) }
    val subdl by Secrets.subdl.collectAsState()
    /** A SubDL key being typed or pasted, until Save. */
    var key by remember { mutableStateOf("") }
    val torbox by TorBox.key.collectAsState()
    var torboxKey by remember { mutableStateOf("") }
    /** The account the saved key opens, or why it doesn't; null while asking. */
    var torboxAccount by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(torbox) {
        torboxAccount = null
        if (torbox.isNotEmpty()) torboxAccount = runCatching { TorBox.account() }.getOrElse { "Not working: ${it.message}" }
    }
    fun set(next: Settings) = SettingsStore.save(next)
    // New-episode alerts: turned on, Android 13+ asks before a notification can show.
    val alertContext = LocalContext.current
    /** Whether Android lets a notification show; looked at again after each ask. */
    var alertsBlocked by remember { mutableStateOf(EpisodeAlerts.needsPermission(alertContext)) }
    val askAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alertsBlocked = !granted
        // Refused twice, Android stops showing the question: its own settings page is the way left.
        if (!granted) runCatching {
            alertContext.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, alertContext.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    /** The page open, by its title; null is the list of them. */
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val update by Updates.release.collectAsState()
    val installed by Addons.list.collectAsState()
    val addonsHint = if (installed.isEmpty()) "Add one to find streams — Watchly comes with none" else "${installed.size} installed · streams, subtitles and catalogues"
    val appContext = LocalContext.current
    BackHandler(page != null) { page = null }
    // The list keeps its own place, for coming back to; a page always opens at its top.
    val listScroll = rememberScrollState()
    val pageScroll = rememberScrollState()
    LaunchedEffect(page) { if (page != null) pageScroll.scrollTo(0) }

    val haze = dev.chrisbanes.haze.rememberHazeState()
    val scroll = if (page == null) listScroll else pageScroll
    // A page's back and name stay put at the top, on frost; the list's own heading scrolls, and its name comes onto the frost.
    var pinned by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val pinnedDp = with(androidx.compose.ui.platform.LocalDensity.current) { pinned.toDp() }
    // Into a page it comes a little way in from the trailing side, back to the list from the leading side,
    // fading up as it goes: a hint of direction, not a whole slide. Calibration: 36dp of travel, 300ms.
    // A new one per page, starting at 0 in the very frame the page changes: reset from an effect, the new page
    // showed whole for a frame first.
    val arrive = remember(page) { androidx.compose.animation.core.Animatable(0f) }
    val forward = page != null
    LaunchedEffect(page) {
        arrive.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val travel = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val left = 1f - arrive.value
                // Faded per drawing, not through an offscreen copy: that copy was dropped the moment it reached
                // full opacity, and the text redrawn sharper — the flicker at the end.
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                alpha = 1f - 0.8f * left
                translationX = left * travel * (if (forward) 1f else -1f) * (if (rtl) -1f else 1f)
            }
            .hazeSource(haze).verticalScroll(scroll).padding(horizontal = EDGE).padding(bottom = 32.dp + LocalTabBarInset.current),
    ) {
        if (page == null) {
            Heading("Settings")
            Field(query, { query = it }, "Search settings", Modifier.fillMaxWidth().padding(bottom = 12.dp), leading = L.drawable.lucide_ic_search)
            // Up top, where it is seen: as one row among the settings nobody found it.
            if (query.isBlank()) StatsBanner { page = "Your stats" }
            SettingsList(
                listOfNotNull(
                    // Stays here until it is installed, "Not now" or not: the card is the one that goes away.
                    update?.let { r ->
                        listOf(
                            Entry(L.drawable.lucide_ic_download, "Update available", "Version ${r.version}  ·  ${bytes(r.size)}", "new version upgrade", Ink.accent) {
                                downloadUpdate(appContext, r) { watch.notes.tryEmit(it) }
                            },
                        )
                    },
                    // A kids profile has no say in where streams come from.
                    if (!com.torrentviewer.core.Kids.on) listOf(
                        Entry(L.drawable.lucide_ic_puzzle, "Addons", addonsHint, "add-ons install manifest streams stremio") { page = "Addons" },
                    ) else null,
                    listOf(
                        Entry(L.drawable.lucide_ic_user, "Account", "Profile, Stremio, Nuvio and PIN", "lock sign in login") { page = "Account" },
                        Entry(L.drawable.lucide_ic_users, "Switch profile", "Change to a different profile", onClick = onSwitchProfile),
                    ),
                    // Your stats is the banner up top: a row for it here said the same thing twice.
                    listOf(
                        Entry(L.drawable.lucide_ic_palette, "Appearance", "Language, theme, home screen genres and rows", "light dark home rows edit language") { page = "Appearance" },
                    ),
                    listOf(
                        Entry(L.drawable.lucide_ic_radio, "Streaming", "Storage, buffer and trackers", "cache magnet") { page = "Streaming" },
                        Entry(L.drawable.lucide_ic_play, "Playback", "Player, languages and subtitles", "vlc media3 skip intro audio") { page = "Playback" },
                        Entry(L.drawable.lucide_ic_download, "Downloads", "Network, order and storage", "wi-fi sd card battery") { page = "Downloads" },
                    ),
                    if (com.torrentviewer.core.Party.available) listOf(
                        Entry(L.drawable.lucide_ic_popcorn, "Watch party", "Your name, guests' requests and waiting", "together friends group sync invite") { page = "Watch party" },
                    ) else null,
                    listOf(
                        Entry(L.drawable.lucide_ic_link, "Integrations", "Simkl, MDBList and AniList", "tracking watched sync anime anilist") { page = "Integrations" },
                        Entry(L.drawable.lucide_ic_key, "Services", "SubDL and TorBox keys", "subtitles api") { page = "Services" },
                    ),
                    listOf(
                        Entry(L.drawable.lucide_ic_history, "Backup", "Save or restore your library", "export import") { page = "Backup" },
                        Entry(L.drawable.lucide_ic_sparkles, "Welcome tour", "See the first-run tour again", "onboarding") { tourAgain = true },
                        Entry(L.drawable.lucide_ic_refresh_cw, "Check for updates", "You have version ${BuildConfig.VERSION_NAME}", "new version upgrade") { Updates.checkNow { watch.notes.tryEmit(it) } },
                    ),
                    listOf(
                        Entry(L.drawable.lucide_ic_star, "Star on GitHub", "Free and open source — a star helps others find it", "review rate support github like") {
                            StarPrompt.starred()
                            openUrl(alertContext, REPO_URL)
                        },
                    ),
                    listOf(
                        Entry(L.drawable.lucide_ic_scale, "Legal notice", "Watchly is a media player only", "disclaimer copyright dmca") { page = "Legal notice" },
                        Entry(L.drawable.lucide_ic_shield, "Privacy policy", "What stays here and what is sent", "data") { page = "Privacy policy" },
                        Entry(L.drawable.lucide_ic_file_text, "Terms and conditions", "The rules for using Watchly", "agreement licence") { page = "Terms and conditions" },
                    ),
                    // Last, at the foot of Settings: what this version brought, as shown after the update.
                    if (hasWhatsNew) listOf(
                        Entry(L.drawable.lucide_ic_sparkles, "What's new", "New in version ${BuildConfig.VERSION_NAME}", "update changelog release notes features") { whatsNewAgain = true },
                    ) else null,
                ),
                query,
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME} · built " +
                    java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date(BuildConfig.BUILD_TIME)),
                Modifier.padding(start = 4.dp, top = 4.dp),
                style = Type.meta.copy(color = Ink.faint),
            )
            Text(
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                Modifier.padding(start = 4.dp, top = 6.dp),
                style = Type.meta.copy(color = Ink.faint, fontSize = 11.sp),
            )
        } else {
            Spacer(Modifier.height(pinnedDp))
        }

        if (page == "Legal notice") Panel { LegalText(com.torrentviewer.core.Legal.NOTICE) }
        if (page == "Privacy policy") Panel { LegalText(com.torrentviewer.core.Legal.PRIVACY) }
        if (page == "Terms and conditions") Panel { LegalText(com.torrentviewer.core.Legal.TERMS) }

        if (page == "Addons") AddonsPage(watch)

        if (page == "Integrations") Panel {
            Text("What you finish here is marked watched on your accounts, and what they have watched shows here. You sign in on each service's own page, so your password never comes to Watchly.", Modifier.padding(bottom = 18.dp), style = Type.meta.copy(color = Ink.faint))
            com.torrentviewer.core.WATCH_TRACKERS.forEach { TrackerSection(it) }
            AniListSection()
        }

        if (page == "Account") Column {
        // Who is watching, first and largest: everything below belongs to this profile.
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me, 56.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(me.name, style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            me.isGuest -> "Guest keeps nothing — history, My List and sign-ins go when the app closes."
                            profiles.isEmpty() -> "One library on this phone. Add profiles to keep a library, My List and resume points each."
                            else -> "Library, My List and resume points belong to this profile."
                        },
                        Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint),
                    )
                }
            }
            Button(if (profiles.isEmpty()) "Add profiles" else "Switch profile", onSwitchProfile, Modifier.padding(top = 14.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_users)
        }

        // With the profile: each connection is this profile's.
        Text("Accounts", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp), style = Type.overline)
        if (Stremio.available) StremioSection(me)
        NuvioSection(me)

        // Only a saved profile can carry a lock; the phone's owner before profiles has nowhere to keep one.
        if (!me.isGuest && profiles.any { it.id == me.id }) {
            Text("Lock", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp), style = Type.overline)
            Card {
            Section(
                "Profile PIN",
                if (me.pin != null) "Asked for before this profile opens. Remove it to unlock."
                else "Four digits, asked for before this profile opens. A lock for a shared phone — not a password.",
            ) {
                // A lock that anyone holding the phone could lift is no lock: changing or removing it
                // asks for the PIN there is now.
                val locked = me.pin != null
                val unlocked = !locked || current == me.pin
                if (locked) {
                    Text("Current PIN", Modifier.padding(bottom = 6.dp), style = Type.overline)
                    Field(current, { current = it.filter(Char::isDigit).take(4) }, "• • • •", Modifier.widthIn(max = 200.dp).fillMaxWidth(), secret = true, digits = true)
                    Text("New PIN", Modifier.padding(top = 14.dp, bottom = 6.dp), style = Type.overline)
                }
                // The digits and Set side by side; Remove on its own line, so nothing is cut short on a narrow phone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(pin, { pin = it.filter(Char::isDigit).take(4) }, "– – – –", Modifier.weight(1f).widthIn(max = 200.dp), secret = true, digits = true)
                    Spacer(Modifier.width(10.dp))
                    Button(if (locked) "Change PIN" else "Set PIN", {
                        when {
                            !unlocked -> watch.notes.tryEmit("Enter the current PIN first")
                            pin.length != 4 -> watch.notes.tryEmit("Enter four digits for the new PIN")
                            else -> {
                                Profiles.setPin(me.id, pin)
                                pin = ""
                                current = ""
                                watch.notes.tryEmit("PIN set for ${me.name}")
                            }
                        }
                    }, icon = L.drawable.lucide_ic_lock)
                }
                if (locked) Button("Remove PIN", {
                    if (unlocked) {
                        Profiles.setPin(me.id, "")
                        current = ""
                        watch.notes.tryEmit("PIN removed")
                    } else watch.notes.tryEmit(if (current.length < 4) "Enter the current PIN to remove it" else "That's not the PIN")
                }, Modifier.padding(top = 10.dp), icon = L.drawable.lucide_ic_lock_open)
            }
            }
        }
        }

        if (page == "Streaming") Panel {
        Section("Torrent engine", "What streams a torrent. A change starts with the next film.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EngineOption("Watchly", !settings.nuvioEngine, { set(settings.copy(nuvioEngine = false)) }, Modifier.weight(1f)) { WatchlyLogo(32.dp) }
                EngineOption("Nuvio", settings.nuvioEngine, { set(settings.copy(nuvioEngine = true)) }, Modifier.weight(1f)) {
                    com.torrentviewer.core.ServiceLogo("nuvio.tv", "Nuvio", 32.dp)
                }
            }
        }

        // Watchly's own: Nuvio's engine keeps its own cache and starts the player at once.
        if (!settings.nuvioEngine) {
        Section(
            "Storage for one film",
            "How much of a film may sit on this phone at once. When it fills, what you've watched is let go to make room. Auto uses the free space, keeping a few GB spare.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CACHE_CAPS.forEach { mb -> Chip(cacheCapLabel(mb), settings.cacheCapMb == mb, onClick = { set(settings.copy(cacheCapMb = mb)) }) }
            }
        }

        Section("Buffer before playing", "Larger starts slower but is steadier on a weak swarm.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 4, 8, 16).forEach { mb -> Chip("$mb MB", settings.prepareMb == mb, onClick = { set(settings.copy(prepareMb = mb)) }) }
            }
        }
        }

        KeptForReopen()

        Section("Extra trackers","Attach public trackers to bare magnet links so they resolve faster.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("On", settings.addTrackers, onClick = { set(settings.copy(addTrackers = true)) })
                Chip("Off", !settings.addTrackers, onClick = { set(settings.copy(addTrackers = false)) })
            }
        }
        }

        if (page == "Playback") Panel {
        Section("Player","Media3 hands a file it can't play to VLC by itself. VLC from the start plays the most formats and full ASS subtitle styling.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Media3", !settings.vlcPlayer, onClick = { set(settings.copy(vlcPlayer = false)) })
                Chip("VLC", settings.vlcPlayer, onClick = { set(settings.copy(vlcPlayer = true)) })
            }
        }

        Section("Skip intro", "Where an episode's intro is known, jump past it by itself instead of showing the Skip intro button. Once per episode — seek back to watch it.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Automatically", settings.autoSkipIntro, onClick = { set(settings.copy(autoSkipIntro = true)) })
                Chip("Show the button", !settings.autoSkipIntro, onClick = { set(settings.copy(autoSkipIntro = false)) })
            }
        }

        Section("New episode alerts", "A notification when a series you watch or have in My List has a new episode out. Checked twice a day.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("On", settings.episodeAlerts, onClick = {
                    set(settings.copy(episodeAlerts = true))
                    EpisodeAlerts.schedule(alertContext)
                    if (EpisodeAlerts.needsPermission(alertContext)) askAlerts.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                })
                Chip("Off", !settings.episodeAlerts, onClick = {
                    set(settings.copy(episodeAlerts = false))
                    EpisodeAlerts.schedule(alertContext)
                })
            }
            // On, but Android is not letting notifications through: say so, with the way to fix it.
            if (settings.episodeAlerts && alertsBlocked) {
                Text("Notifications are turned off for Watchly, so alerts can't show yet.", Modifier.padding(top = 10.dp, bottom = 8.dp), style = Type.meta.copy(color = Ink.warn))
                Chip("Allow notifications", false, onClick = { askAlerts.launch(android.Manifest.permission.POST_NOTIFICATIONS) })
            }
        }

        Section("Languages", "A matching track comes on by itself when a video opens; picking one yourself wins. Online subtitle search looks in the subtitle language.") {
            // Flowing: side by side, "Subtitles: English" had no room left and showed as "Subtitles:".
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Audio: " + if (settings.audioLang.isEmpty()) "file's default" else langName(settings.audioLang), settings.audioLang.isNotEmpty(), onClick = { picking = "audio" })
                Chip("Subtitles: " + if (settings.subLang.isEmpty()) "off" else langName(settings.subLang), settings.subLang.isNotEmpty(), onClick = { picking = "subs" })
            }
        }

        Section("Subtitle style", "Size, colour and a box behind the words. Media3 shows a change on the next line; VLC from the next video.") {
            val style = settings.subtitles
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SUB_SIZES.keys.forEach { s -> Chip(s.replaceFirstChar(Char::uppercase), style.size == s, onClick = { set(settings.copy(subtitles = style.copy(size = s))) }) }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("White", !style.yellow, onClick = { set(settings.copy(subtitles = style.copy(yellow = false))) })
                Chip("Yellow", style.yellow, onClick = { set(settings.copy(subtitles = style.copy(yellow = true))) })
                Chip("Box behind", style.box, onClick = { set(settings.copy(subtitles = style.copy(box = !style.box))) })
            }
        }
        }

        if (page == "Watch party") Panel {
        Text(
            "Start one with the people button at the top of the player, and send the link. Everyone streams the film on their own device; only play, pause and the spot are shared, encrypted, with no account.",
            Modifier.padding(bottom = 18.dp),
            style = Type.meta.copy(color = Ink.faint),
        )

        Section("Watch party", "Off, the player has no party button and invite links are turned away. Turning it off leaves any party you're in.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("On", settings.partyOn, onClick = { set(settings.copy(partyOn = true)) })
                Chip("Off", !settings.partyOn, onClick = {
                    set(settings.copy(partyOn = false))
                    com.torrentviewer.core.Party.leave()
                    com.torrentviewer.core.Party.last = null
                })
            }
        }
        if (settings.partyOn) {

        Section("Join with a code", "The 8-character code the leader shares from the player's party panel. Home's party button takes it too.") {
            JoinCode()
        }

        Section("Your name", "What the others see when you join or ask for something. Left blank, it is the profile's name, ${me.name}.") {
            Field(settings.partyName, { set(settings.copy(partyName = it.take(com.torrentviewer.core.PARTY_NAME_MAX))) }, me.name, Modifier.fillMaxWidth(), action = androidx.compose.ui.text.input.ImeAction.Done)
        }

        Section("Guests' requests", "When you lead a party and a guest asks to pause, play or jump. Ignored, their buttons tell them only you control it.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ask" to "Ask me", "allow" to "Allow at once", "off" to "Ignore").forEach { (v, label) ->
                    Chip(label, settings.partyAsks == v, onClick = { set(settings.copy(partyAsks = v)) })
                }
            }
        }

        Section("Wait for slow connections", "When you lead, the film pauses for anyone whose stream stops to load and plays on when they are through — for this long at most. Go on, on the banner, stops waiting at any time.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.torrentviewer.core.PARTY_WAITS.forEach { s ->
                    Chip(if (s == 0) "Don't wait" else if (s < 60) "$s s" else "${s / 60} min", settings.partyWaitSec == s, onClick = { set(settings.copy(partyWaitSec = s)) })
                }
            }
        }

        Section("On the picture", "What shows over the film while the party panel is shut. Off, chat still collects in the panel — a dot on the party button says there is something new.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Chat messages", settings.partyShowChat, onClick = { set(settings.copy(partyShowChat = !settings.partyShowChat)) })
                Chip("Reactions", settings.partyShowReactions, onClick = { set(settings.copy(partyShowReactions = !settings.partyShowReactions)) })
            }
        }

        Section("Smooth catch-up", "As a guest, a small gap to the leader closes by playing up to 8% faster or slower, which you won't hear. Off, the player jumps to the leader instead.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("On", settings.partySmooth, onClick = { set(settings.copy(partySmooth = true)) })
                Chip("Off", !settings.partySmooth, onClick = { set(settings.copy(partySmooth = false)) })
            }
        }
        }
        }

        if (page == "Services") Panel {
        Section(
            "SubDL key",
            if (subdl.isNotEmpty()) "Saved, encrypted on this phone — Find subtitles searches SubDL too. Enter another to replace it."
            else "A free key from subdl.com adds its subtitles to Find subtitles. It stays encrypted on this phone.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(key, { key = it.trim() }, if (subdl.isNotEmpty()) "••••••••••••" else "Your SubDL API key", Modifier.weight(1f), secret = true)
                Spacer(Modifier.width(10.dp))
                Button("Save", {
                    if (key.isEmpty()) watch.notes.tryEmit("Paste your SubDL key first")
                    else {
                        Secrets.setSubdl(key)
                        key = ""
                        watch.notes.tryEmit("SubDL key saved")
                    }
                }, icon = L.drawable.lucide_ic_key)
            }
            if (subdl.isNotEmpty()) Button("Remove key", {
                Secrets.setSubdl("")
                watch.notes.tryEmit("SubDL key removed")
            }, Modifier.padding(top = 10.dp))
        }

        Section(
            "TorBox",
            if (torbox.isNotEmpty()) "Releases TorBox has cached say Instant, and play from its servers — no peers to wait for, seek anywhere. Anything else streams from the swarm as before."
            else "With a torbox.app API key, releases TorBox has cached play from its servers instead of from peers. The key is under Settings on torbox.app, and stays encrypted on this phone.",
        ) {
            if (torbox.isNotEmpty()) {
                Text(
                    torboxAccount ?: "Checking the key…",
                    Modifier.padding(bottom = 10.dp),
                    style = Type.meta.copy(color = if (torboxAccount?.startsWith("Not working") == true) Ink.danger else Ink.muted),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(torboxKey, { torboxKey = it.trim() }, if (torbox.isNotEmpty()) "••••••••••••" else "Your TorBox API key", Modifier.weight(1f), secret = true)
                Spacer(Modifier.width(10.dp))
                Button("Save", {
                    if (torboxKey.isEmpty()) watch.notes.tryEmit("Paste your TorBox key first")
                    else {
                        TorBox.setKey(torboxKey)
                        torboxKey = ""
                        watch.notes.tryEmit("TorBox key saved")
                    }
                }, icon = L.drawable.lucide_ic_key)
            }
            if (torbox.isNotEmpty()) Button("Remove key", {
                TorBox.setKey("")
                watch.notes.tryEmit("TorBox key removed")
            }, Modifier.padding(top = 10.dp))
        }
        }

        if (page == "Downloads") Panel {
        Section("Network", "Wi-Fi only holds a download on mobile data — it waits, and carries on when Wi-Fi is back. Use mobile data on a waiting download to let that one through.") {
            var wifiOnly by remember { mutableStateOf(Downloads.wifiOnly) }
            var sdCard by remember { mutableStateOf(Downloads.sdCard) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Wi-Fi only", wifiOnly, onClick = { wifiOnly = true; Downloads.wifiOnly = true })
                Chip("Any network", !wifiOnly, onClick = { wifiOnly = false; Downloads.wifiOnly = false })
            }
            var sequential by remember { mutableStateOf(Downloads.sequential) }
            Text(
                "Fastest takes pieces from wherever the swarm has them. In order fills each file from the start, so you can watch it while it downloads — but it is slower.",
                Modifier.padding(top = 14.dp, bottom = 8.dp), style = Type.meta.copy(color = Ink.faint),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Fastest", !sequential, onClick = { sequential = false; Downloads.sequential = false })
                Chip("In order", sequential, onClick = { sequential = true; Downloads.sequential = true })
            }
            // Re-read on return from the system prompt.
            val context = LocalContext.current
            var unrestricted by remember { mutableStateOf(Downloads.unrestricted(context)) }
            LifecycleResumeEffect(Unit) {
                unrestricted = Downloads.unrestricted(context)
                onPauseOrDispose { }
            }
            if (!unrestricted) {
                Text(
                    "Battery saving can slow or stop downloads with the screen off. Let Watchly run in the background to keep them going.",
                    Modifier.padding(top = 14.dp, bottom = 8.dp), style = Type.meta.copy(color = Ink.faint),
                )
                Chip("Allow background downloads", false, onClick = { Downloads.askUnrestricted(context) })
            }
            if (Downloads.hasCard) {
                Text(
                    "Save to the SD card's app folder instead of the phone. Files there go if the app is uninstalled." +
                        if (Downloads.busy || watch.phase != Phase.IDLE) " Switchable once nothing is downloading or playing." else "",
                    Modifier.padding(top = 14.dp, bottom = 8.dp), style = Type.meta.copy(color = Ink.faint),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Phone", true to "SD card").forEach { (card, label) ->
                        Chip(label, sdCard == card, onClick = {
                            if (Downloads.busy || watch.phase != Phase.IDLE) watch.notes.tryEmit("Finish or stop downloads and playback first.")
                            else {
                                sdCard = card
                                Downloads.sdCard = card
                            }
                        })
                    }
                }
            }
        }
        }

        if (page == "Appearance") AppearancePage(settings, { set(it) }, onEditHome) { choosingLanguage = true }

        if (page == "Your stats") StatsPage()

        if (page == "Backup") Panel {
        Section("Your library", "History, resume points, lists, settings, searches and intro marks as one file. Downloads are not in it — they are files on this phone.") {
            val context = LocalContext.current
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(Backup.export().toByteArray()) } }
                    .onSuccess { watch.notes.tryEmit("Backup saved") }
                    .onFailure { watch.notes.tryEmit("Couldn't write the backup: ${it.message}") }
            }
            val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                runCatching {
                    // A backup is kilobytes; anything far larger is not one.
                    val text = context.contentResolver.openInputStream(uri)?.use { String(it.readNBytesCompat(8 shl 20)) } ?: error("Couldn't read that file")
                    Backup.restore(text)
                }.onSuccess { watch.notes.tryEmit("Restored $it parts of the library") }
                    .onFailure { watch.notes.tryEmit(it.message ?: "Couldn't restore that backup") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Back up", { save.launch("watchly-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json") }, icon = L.drawable.lucide_ic_download)
                Button("Restore", { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, icon = L.drawable.lucide_ic_history)
            }
        }
        }
    }
    val scrolled = scroll.scrolled()
    FrostTop(haze, scrolled, Modifier.onSizeChanged { pinned = it.height }) {
        val name = page
        if (name != null) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = EDGE).padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(L.drawable.lucide_ic_arrow_left, { page = null })
            Text(name, Modifier.padding(start = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        } else androidx.compose.animation.AnimatedVisibility(scrolled, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Text("Settings", Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = EDGE, vertical = 14.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        }
    }
    }

    picking?.let { which ->
        val audio = which == "audio"
        Sheet(
            title = if (audio) "Audio language" else "Subtitle language",
            options = listOf(Option("", if (audio) "The file's default" else "Off")) + LANGS.map { Option(it.code, it.name) },
            selected = if (audio) settings.audioLang else settings.subLang,
            onPick = {
                set(if (audio) settings.copy(audioLang = it) else settings.copy(subLang = it))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
    // Out here, over the page: inside its scrolling column the sheet was laid out under the last card, unseen.
    if (choosingLanguage) {
        val picked = remember { com.torrentviewer.core.I18n.picked(appContext) }
        LanguageSheet(picked, deviceLanguage(), { choosingLanguage = false }) { tag ->
            choosingLanguage = false
            if (tag != picked) {
                com.torrentviewer.core.I18n.pick(appContext, tag)
                // Every screen again, in the new words and, for Arabic, right to left.
                (appContext as? android.app.Activity)?.recreate()
            }
        }
    }
}

/** InputStream.readNBytes is API 33; a cap keeps a wrong pick from filling memory. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Copy, answered as My List is: a green disc springs in behind a tick that pops, then it is copy again. */
@Composable
private fun CopyButton(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val disc by androidx.compose.animation.core.animateFloatAsState(
        if (copied) 1f else 0f,
        if (copied) androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 420f) else androidx.compose.animation.core.tween(180),
        label = "disc",
    )
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        pop.animateTo(1f, androidx.compose.animation.core.keyframes { durationMillis = 360; 0.72f at 100; 1.15f at 230; 1f at 360 })
        delay(1500)
        copied = false
    }
    Tap({
        onCopy()
        copied = true
    }, shape = CircleShape) {
        Box(Modifier.padding(8.dp).size(28.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(28.dp).graphicsLayer {
                    scaleX = disc
                    scaleY = disc
                    alpha = disc.coerceIn(0f, 1f)
                }.clip(CircleShape).background(Ink.good),
            )
            Lc(
                if (copied) L.drawable.lucide_ic_check else L.drawable.lucide_ic_copy,
                Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
                tint = androidx.compose.ui.graphics.lerp(Ink.muted, Color.White, disc.coerceIn(0f, 1f)),
                size = 18.dp,
            )
        }
    }
}

/** The Library's Stremio card was put off with "Not now"; Settings still offers it. */
private const val STREMIO_LATER = "stremio:later"

/**
 * Sign in to Stremio by its link code, as on the TV — but a phone has a browser, so Sign in opens
 * link.stremio.com with the code in it, where the viewer signs in and approves; no password comes
 * here. This notices the approval when the viewer comes back, then brings the account's addons.
 * [onLater] makes it the Library's card, with a Not now; without it, a Settings section.
 */
@Composable
fun StremioSection(me: Profile, onLater: (() -> Unit)? = null) {
    val live by Stremio.connected.collectAsState()
    /** Bumped by connect and disconnect: a profile not being watched has no flow to say so. */
    var changed by remember { mutableStateOf(0) }
    val connected = remember(me.id, live, changed) { Stremio.isConnected(me.id) }
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val browser = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current

    /** For typing it on another device. Android 13+ says "Copied" itself. */
    fun copy(text: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Stremio code", text))
        if (android.os.Build.VERSION.SDK_INT < 33) note = "Code copied"
    }

    fun connect() {
        // As the guest: a profile first, which keeps the sign-in.
        if (NeedProfile.first()) return
        note = null
        job?.cancel()
        job = scope.launch {
            try {
                val c = Stremio.start()
                code = c
                // Straight to Stremio with the code filled in; the code stays here for another device.
                runCatching { browser.openUri(c.uriComplete) }
                if (Stremio.await(c, me.id)) changed++ else note = "That code ran out — sign in again for a new one."
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) note = e.message ?: "Couldn't reach Stremio."
            } finally {
                code = null
            }
        }
    }

    val email by Stremio.email.collectAsState()
    val hint = when {
        connected -> "Signed in${email?.takeIf { me.id == Stremio.profileId }?.let { " as $it" }.orEmpty()} — your Stremio library and where you stopped are here, My List is your library there, and what you finish is marked watched."
        me.isGuest -> "Your Stremio library, Continue Watching and addons here. As Guest it's forgotten when the app closes."
        else -> "Your Stremio library, Continue Watching and addons here, kept in step both ways. Sign in on Stremio's own page — your password never comes to Watchly."
    }
    val body: @Composable () -> Unit = {
        val c = code
        if (c != null) {
            Text(
                "Sign in and approve on the page that opened. Or open ${c.uriComplete.removePrefix("https://")} on another device.",
                style = Type.meta.copy(color = Ink.faint),
            )
            // The code and its copy on a line of their own: beside Open Stremio, a narrow card cut both.
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.userCode, style = Type.display, maxLines = 1)
                CopyButton { copy(c.userCode) }
            }
        }
        when {
            // Stacked: side by side, a narrow card cut "Sign out" to "Sign …".
            connected -> Column {
                Button("Import addons", {
                    note = "Bringing your addons…"
                    scope.launch {
                        note = runCatching { Stremio.importAddons() }.fold(
                            { if (it == 0) "Every addon is here already" else "Added $it addon${if (it == 1) "" else "s"} — see Settings → Addons" },
                            { it.message ?: "Couldn't reach Stremio" },
                        )
                    }
                }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_puzzle, primary = true)
                // The other way: the account's list made this phone's, for every device that signs in after.
                Button("Send my addons to Stremio", {
                    note = "Sending your addons…"
                    scope.launch {
                        note = runCatching { Stremio.pushAddons() }.fold(
                            { "Your Stremio account now has this phone's $it addon${if (it == 1) "" else "s"}" },
                            { it.message ?: "Couldn't reach Stremio" },
                        )
                    }
                }, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_upload)
                Button("Sign out", {
                    Stremio.disconnect(me.id)
                    changed++
                }, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_link)
            }
            c != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Open Stremio", { runCatching { browser.openUri(c.uriComplete) } }, icon = L.drawable.lucide_ic_link, primary = true)
                Button("Cancel", { job?.cancel() })
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The card's heading already says Stremio, and a dialog is narrow: "Not now" was cut to "Not …".
                Button(if (onLater != null) "Sign in" else "Sign in to Stremio", ::connect, icon = L.drawable.lucide_ic_link, primary = true)
                onLater?.let { Button("Not now", it) }
            }
        }
        note?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint)) }
    }
    if (onLater == null) AccountCard(
        "stremio.com", "Stremio", connected,
        when { connected -> email?.takeIf { me.id == Stremio.profileId }?.let { "Signed in as $it" } ?: "Signed in"; code != null -> "Waiting for your approval…"; else -> "Not signed in" },
        hint, body,
    )
    else Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).background(Ink.surface, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text("Bring your Stremio library", style = Type.title.copy(fontWeight = FontWeight.Bold))
        Text(hint, Modifier.padding(top = 4.dp, bottom = 12.dp), style = Type.meta.copy(color = Ink.faint))
        body()
    }
}

/**
 * Nuvio's sign-in: email and password, sent only to Nuvio (it has no link code). [tint] is the words'
 * colour — white on the tour. An account with more than one Nuvio profile is asked which; then the
 * addons come across and [onSignedIn] runs.
 */
@Composable
fun NuvioForm(owner: String, tint: Color = Ink.text, onSignedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    /** The account's profiles, once signed in to one with several. */
    var choices by remember { mutableStateOf<List<com.torrentviewer.core.NuvioProfile>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun go() {
        if (busy || NeedProfile.first()) return
        if ('@' !in email || password.isEmpty()) {
            note = "Enter your Nuvio email and password."
            return
        }
        busy = true
        note = null
        scope.launch {
            try {
                val all = com.torrentviewer.core.Nuvio.signIn(email, password, owner)
                password = ""
                if (all.size > 1) choices = all else onSignedIn()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) note = e.message ?: "Couldn't reach Nuvio."
            } finally {
                busy = false
            }
        }
    }

    choices?.let { all ->
        NuvioProfilePicker(all, owner, tint) { choices = null; onSignedIn() }
        return
    }
    Column {
        Field(
            email, { email = it.trim() }, "Email", Modifier.fillMaxWidth(), leading = L.drawable.lucide_ic_mail,
            action = androidx.compose.ui.text.input.ImeAction.Next, tint = tint, keyboard = androidx.compose.ui.text.input.KeyboardType.Email,
        )
        Field(
            password, { password = it }, "Password", Modifier.padding(top = 10.dp).fillMaxWidth(), secret = true, leading = L.drawable.lucide_ic_lock,
            action = androidx.compose.ui.text.input.ImeAction.Done, onSubmit = ::go, tint = tint, keyboard = androidx.compose.ui.text.input.KeyboardType.Password,
        )
        note?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = tint.copy(alpha = 0.7f))) }
        Button(if (busy) "Signing in…" else "Sign in to Nuvio", ::go, Modifier.padding(top = 14.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_log_in, primary = true)
    }
}

/**
 * What sits on the phone so films reopen at once: Watchly's kept start of each film stopped part-way, by
 * its title and size, and Nuvio's engine's own cache. Each can go, or all of them.
 */
@Composable
private fun KeptForReopen() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var kept by remember { mutableStateOf(emptyList<com.torrentviewer.core.KeptStart>()) }
    var nuvio by remember { mutableStateOf(0L) }
    suspend fun reload() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        kept = Services.engine.keptStarts()
        nuvio = NuvioTorrent.cacheBytes(context)
    }
    /** What is being cleared now — a film's hash, "nuvio", "all" — so its row says so while the files go (seconds, for a big film). */
    var clearing by remember { mutableStateOf(emptySet<String>()) }
    fun clear(what: Set<String>, work: () -> Unit) {
        if (clearing.containsAll(what)) return
        clearing = clearing + what
        scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { work() }
                // Nuvio's figure is sampled about once a second.
                delay(1200)
                reload()
            } finally {
                clearing = clearing - what
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    // A film as the history has it, where it was played from: its name, poster and how far in.
    val history by History.items.collectAsState()
    val played = remember(history) { history.mapNotNull { i -> com.torrentviewer.core.magnetHash(i.uri)?.lowercase()?.let { it to i } }.toMap() }
    val total = kept.sumOf { it.bytes } + nuvio

    Section(
        "Kept for a fast reopen",
        if (total == 0L) "Nothing kept. A film you stop part-way keeps its start here, so it opens at once next time."
        else "A film you stop part-way keeps its start here, so it opens at once next time. ${bytes(total)} in all.",
    ) {
        // Rows that went close up smoothly rather than jump.
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kept.forEach { k ->
                val item = played[k.hash.lowercase()]
                // The history's name ("The Matrix"); else the release's, with its scaffolding cut ("The.Matrix.1999.1080p…").
                val name = item?.titleName ?: com.torrentviewer.core.searchableName(k.name).ifBlank { k.name }
                val facts = listOfNotNull(com.torrentviewer.core.yearOf(k.name), com.torrentviewer.core.quality(k.name)).joinToString("  ·  ")
                val going = k.hash in clearing || "all" in clearing
                KeptRow(
                    name, facts, item?.poster, item?.ratio?.takeIf { it > 0.01 },
                    when {
                        k.playing -> "Playing now"
                        going -> "Clearing…"
                        else -> "${bytes(k.bytes)}  ·  left ${ago(k.savedAt)}"
                    },
                    k.playing,
                    busy = going && !k.playing,
                    onClear = if (k.playing) null else ({ clear(setOf(k.hash)) { Services.engine.clearStart(k.hash) } }),
                )
            }
            if (nuvio > 0) {
                val going = "nuvio" in clearing || "all" in clearing
                KeptRow(
                    "Nuvio engine's cache", "Its own store, apart from Watchly's", null, null, if (going) "Clearing…" else bytes(nuvio), false,
                    icon = L.drawable.lucide_ic_hard_drive, busy = going, onClear = { clear(setOf("nuvio")) { NuvioTorrent.clearCache(context) } },
                )
            }
        }
        if (kept.count { !it.playing } + (if (nuvio > 0) 1 else 0) > 1) {
            val all = "all" in clearing
            Button(
                if (all) "Clearing…" else "Clear all", { clear(setOf("all")) { Services.engine.clearStarts(); NuvioTorrent.clearCache(context) } },
                Modifier.padding(top = 12.dp), icon = if (all) L.drawable.lucide_ic_loader_circle else L.drawable.lucide_ic_trash_2, compact = true,
            )
        }
    }
}

/**
 * One film kept: its poster, its name and year and quality, how far in it was left as a bar, and what it
 * takes and when — or Playing now, in the accent, while it is on (and can't go yet).
 */
@Composable
private fun KeptRow(title: String, facts: String, poster: String?, ratio: Double?, detail: String, live: Boolean, icon: Int = L.drawable.lucide_ic_film, busy: Boolean = false, onClear: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    // Being cleared: the row fades back, and a spinner turns where the bin was.
    val fade by androidx.compose.animation.core.animateFloatAsState(if (busy) 0.5f else 1f, label = "clearing")
    Row(Modifier.fillMaxWidth().clip(shape).background(Ink.text.copy(alpha = 0.05f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 44.dp, height = 64.dp).alpha(fade).clip(RoundedCornerShape(8.dp)).background(Ink.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            if (poster != null) coil3.compose.AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Lc(icon, tint = Ink.faint, size = 20.dp)
        }
        Column(Modifier.weight(1f).alpha(fade).padding(horizontal = 12.dp)) {
            Text(title, style = Type.label.copy(color = Ink.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (facts.isNotEmpty()) Text(facts, Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.muted), maxLines = 1)
            ratio?.let { r ->
                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Ink.text.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth(r.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(Ink.accent))
                    }
                    Text("${(r * 100).toInt()}%", Modifier.padding(start = 8.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 11.sp))
                }
            }
            Text(detail, Modifier.padding(top = 5.dp), style = Type.meta.copy(color = if (live) Ink.accent else Ink.faint, fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
        }
        if (busy) Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = Ink.accent, strokeWidth = 2.dp, trackColor = Ink.text.copy(alpha = 0.1f))
        } else onClear?.let {
            Tap(it, Modifier.size(38.dp), CircleShape) {
                Box(Modifier.fillMaxSize().background(Ink.text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                    Lc(L.drawable.lucide_ic_trash_2, tint = Ink.muted, size = 18.dp)
                }
            }
        }
    }
}

/** One torrent engine to pick: its app's logo and name, the picked one ringed in the accent. */
@Composable
private fun EngineOption(name: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, logo: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Tap(onClick, modifier, shape) { pressed ->
        Row(
            Modifier.fillMaxWidth()
                .background(Ink.text.copy(alpha = if (pressed) 0.14f else 0.07f))
                .border(2.dp, if (on) Ink.accent else Color.Transparent, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            logo()
            // No tick: on a phone's half width it cut the name short, and the ring already says which.
            Text(name, Modifier.padding(start = 10.dp), style = Type.label.copy(color = Ink.text, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
        }
    }
}

/** The app's own icon, from its adaptive layers: 108dp each with the art in the middle 72, so shown at 1.5x as a launcher crops them. */
@Composable
private fun WatchlyLogo(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher_background), null,
            Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher_foreground), null,
            Modifier.requiredSize(size * 1.5f),
        )
    }
}

/** Which of the account's Nuvio profiles to watch as: each by its colour and name. */
@Composable
fun NuvioProfilePicker(all: List<com.torrentviewer.core.NuvioProfile>, owner: String, tint: Color = Ink.text, onPicked: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val chosen = com.torrentviewer.core.Nuvio.profileNameOf(owner)
    Column {
        Text("Which Nuvio profile is this?", Modifier.padding(bottom = 10.dp), style = Type.label.copy(color = tint, fontWeight = FontWeight.SemiBold))
        all.forEach { p ->
            val color = p.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Ink.accent
            Tap({ scope.launch { com.torrentviewer.core.Nuvio.choose(p, owner); onPicked() } }, Modifier.padding(bottom = 8.dp).fillMaxWidth(), RoundedCornerShape(10.dp)) { pressed ->
                Row(Modifier.fillMaxWidth().background(tint.copy(alpha = if (pressed) 0.14f else 0.07f)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Text(p.name.take(1).uppercase(), style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    }
                    Text(p.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = Type.label.copy(color = tint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.name == chosen) Lc(L.drawable.lucide_ic_check, tint = Ink.good, size = 18.dp)
                }
            }
        }
    }
}

/** The Account page's Nuvio card, for the profile [me]: the form, or who is signed in, the Nuvio profile and its addons. */
@Composable
fun NuvioSection(me: Profile) {
    val live by com.torrentviewer.core.Nuvio.connected.collectAsState()
    val liveProfile by com.torrentviewer.core.Nuvio.profileName.collectAsState()
    /** Bumped by sign-in and sign-out: a profile not being watched has no flow to say so. */
    var changed by remember { mutableStateOf(0) }
    val connected = remember(me.id, live, changed) { com.torrentviewer.core.Nuvio.isConnected(me.id) }
    val nuvioProfile = remember(me.id, liveProfile, changed) { com.torrentviewer.core.Nuvio.profileNameOf(me.id) }
    val email by com.torrentviewer.core.Nuvio.email.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    /** The account's profiles while Change profile is open. */
    var choices by remember { mutableStateOf<List<com.torrentviewer.core.NuvioProfile>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val signedAs = email?.takeIf { me.id == com.torrentviewer.core.Nuvio.profileId }
    val hint = when {
        connected -> "Where you stopped on Nuvio is in Continue Watching, My List is your Nuvio library, and what you finish is marked watched."
        me.isGuest -> "Your Nuvio library, Continue Watching and addons here. As Guest it's forgotten when the app closes."
        else -> "Your Nuvio library, Continue Watching and addons here, kept in step both ways. Your password goes only to Nuvio."
    }
    val status = when {
        !connected -> "Not signed in"
        nuvioProfile != null -> listOfNotNull(nuvioProfile, signedAs).joinToString(" · ")
        else -> signedAs?.let { "Signed in as $it" } ?: "Signed in"
    }
    AccountCard("nuvio.tv", "Nuvio", connected, status, hint) {
        val open = choices
        if (connected && open != null) NuvioProfilePicker(open, me.id) { choices = null; changed++ }
        else if (connected) Column {
            Button("Import addons", {
                note = "Bringing your addons…"
                scope.launch {
                    note = runCatching { com.torrentviewer.core.Nuvio.importAddons() }.fold(
                        { if (it == 0) "Every addon is here already" else "Added $it addon${if (it == 1) "" else "s"} — see Settings → Addons" },
                        { it.message ?: "Couldn't reach Nuvio" },
                    )
                }
            }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_puzzle, primary = true)
            Button("Change Nuvio profile", {
                note = null
                scope.launch {
                    runCatching { com.torrentviewer.core.Nuvio.profiles(me.id) }.fold(
                        { if (it.size > 1) choices = it else note = "This Nuvio account has one profile" },
                        { note = it.message ?: "Couldn't reach Nuvio" },
                    )
                }
            }, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_users)
            Button("Sign out", {
                com.torrentviewer.core.Nuvio.disconnect(me.id)
                changed++
            }, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_link)
            note?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint)) }
        } else NuvioForm(me.id) { changed++ }
    }
}

/* --------------------------------------------------------------- addons */

/**
 * Settings → Addons: where everything played comes from. Add one by its link (or an addon site's
 * Install button), then switch, order or remove each. Order is the order asked. Drawn inside the
 * Settings page's own scroll, under its back and title.
 */
@Composable
private fun AddonsPage(watch: Watch) {
    val addons by Addons.list.collectAsState()
    var link by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun add() {
        if (adding) return
        if (link.isEmpty()) {
            watch.notes.tryEmit("Paste an addon link first")
            return
        }
        adding = true
        scope.launch {
            runCatching { Addons.add(link) }
                .onSuccess { link = ""; watch.notes.tryEmit("Added ${it.name}") }
                .onFailure { watch.notes.tryEmit(it.message ?: "Couldn't add that addon") }
            adding = false
        }
    }
    Column {
        Text(
            "Streams, subtitles and extra catalogues come from addons — public ones, your debrid service's, or your own media server's. Watchly comes with none.",
            Modifier.padding(bottom = 18.dp), style = Type.meta.copy(color = Ink.faint),
        )
        Group("Add an addon") {
            Column(Modifier.padding(bottom = 18.dp)) {
                Field(link, { link = it.trim() }, "https://…/manifest.json", Modifier.fillMaxWidth(), onSubmit = ::add, leading = L.drawable.lucide_ic_link)
                Button(if (adding) "Adding…" else "Add addon", ::add, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_plus, primary = true)
                Text(
                    "Or tap Install on an addon's site and pick Watchly.",
                    Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp),
                )
            }
        }
        if (addons.isEmpty()) {
            Text("No addons yet", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp), style = Type.overline.copy(color = Ink.faint))
            Note("Add one to find streams for every film and episode. Without any, only links you paste in Search will play.")
            return@Column
        }
        // Stremio's list: a search over what is installed, and a pill per type they serve.
        var query by rememberSaveable { mutableStateOf("") }
        var type by rememberSaveable { mutableStateOf<String?>(null) }
        val types = addons.flatMap { a -> a.resources.flatMap { it.types } + a.catalogs.map { it.type } }.distinct()
        Field(query, { query = it }, "Search installed addons", Modifier.fillMaxWidth(), leading = L.drawable.lucide_ic_search)
        Row(Modifier.padding(top = 12.dp, bottom = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All · ${addons.size}", type == null, onClick = { type = null })
            types.forEach { t -> Chip(com.torrentviewer.core.typeLabel(t), type == t, onClick = { type = t }) }
        }
        val shown = addons.filter { a ->
            (query.isBlank() || a.name.contains(query.trim(), ignoreCase = true)) &&
                (type == null || a.resources.any { type in it.types } || a.catalogs.any { it.type == type })
        }
        if (shown.isEmpty()) Note("No installed addon matches.")
        shown.forEach { a ->
            val at = addons.indexOf(a)
            AddonCard(a, first = at == 0, last = at == addons.lastIndex, onRemoved = { watch.notes.tryEmit("Uninstalled ${a.name}") })
        }
    }
}

/**
 * One addon, as Stremio lists it: logo, name with a tick while it is on, the types it serves and its
 * version, what it says it does, then Uninstall — with its switch and place in the order beside it.
 */
@Composable
private fun AddonCard(a: com.torrentviewer.core.Addon, first: Boolean, last: Boolean, onRemoved: () -> Unit) {
    val m = remember(a.manifest) { runCatching { org.json.JSONObject(a.manifest) }.getOrNull() }
    val types = remember(a) { (a.resources.flatMap { it.types } + a.catalogs.map { it.type }).distinct().joinToString(", ") { com.torrentviewer.core.typeLabel(it) } }
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Ink.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Lc(L.drawable.lucide_ic_puzzle, tint = Ink.muted, size = 24.dp)
                m?.optString("logo")?.takeIf { it.startsWith("http") }?.let { Artwork(it, a.name, Modifier.fillMaxSize(), fill = false) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, Modifier.weight(1f, fill = false), style = Type.title.copy(fontWeight = FontWeight.Bold, color = if (a.on) Ink.text else Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.on) Lc(L.drawable.lucide_ic_circle_check, Modifier.padding(start = 8.dp), tint = Ink.muted, size = 16.dp)
                }
                Text(types.ifEmpty { a.offers }, Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            m?.optString("version")?.takeIf { it.isNotBlank() }?.let {
                Text("v$it", Modifier.padding(start = 8.dp, top = 2.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = Ink.muted))
            }
        }
        m?.optString("description")?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(top = 14.dp), style = Type.body.copy(color = Ink.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        // The host only: the rest of the link can be the viewer's own key.
        Text(a.host, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Tap({ Addons.remove(a.url); onRemoved() }, Modifier.weight(1f), RoundedCornerShape(24.dp)) { pressed ->
                Box(
                    Modifier.fillMaxWidth().height(46.dp).border(1.dp, Ink.text.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                        .background(Ink.text.copy(alpha = if (pressed) 0.12f else 0f)),
                    contentAlignment = Alignment.Center,
                ) { Text("Uninstall", style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = Ink.muted)) }
            }
            Spacer(Modifier.width(8.dp))
            Chip(if (a.on) "On" else "Off", a.on, onClick = { Addons.toggle(a.url) })
            if (!first) Tap({ Addons.move(a.url, -1) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_chevron_up, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp) }
            if (!last) Tap({ Addons.move(a.url, 1) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_chevron_down, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp) }
        }
    }
}

/** A heading and a card holding the settings that belong together. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp), style = Type.overline.copy(color = Ink.faint))
    Panel(content)
}

/** The card itself. */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 22.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).padding(start = 16.dp, end = 16.dp, top = 18.dp)) { content() }
}

/** One card of the Account page. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).padding(16.dp)) { content() }
}

/**
 * A signed-in service on the Account page: its logo, name and who is signed in, opening on a tap to
 * what it brings and its buttons. Shut, the page is a short list of accounts rather than a wall of them.
 */
@Composable
private fun AccountCard(domain: String, name: String, connected: Boolean, status: String, hint: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(name) { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).animateContentSize()) {
        Tap({ open = !open }, Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) { pressed ->
            Row(Modifier.fillMaxWidth().background(if (pressed) Ink.surface2 else Color.Transparent).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                com.torrentviewer.core.ServiceLogo(domain, name, 42.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(name, style = Type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (connected) Ink.good else Ink.faint))
                        Text(status, Modifier.padding(start = 6.dp), style = Type.meta.copy(color = if (connected) Ink.text else Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Lc(if (open) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, tint = Ink.muted, size = 20.dp)
            }
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Text(hint, Modifier.padding(bottom = 12.dp), style = Type.meta.copy(color = Ink.faint))
            content()
        }
    }
}

/** One line of the settings list; [words] are what else it answers to in the search. */
private class Entry(@androidx.annotation.DrawableRes val icon: Int, val title: String, val hint: String, val words: String = "", val tint: Color? = null, val onClick: () -> Unit) {
    fun matches(query: String) = listOf(title, hint, words).any { it.contains(query, ignoreCase = true) }
}

/** The settings as cards of rows; searching runs them together into one card of what matches. */
@Composable
private fun SettingsList(groups: List<List<Entry>>, query: String) {
    val q = query.trim()
    val shown = if (q.isEmpty()) groups else listOf(groups.flatten().filter { it.matches(q) }).filter { it.isNotEmpty() }
    if (shown.isEmpty()) Text("No settings match", Modifier.padding(start = 4.dp, bottom = 12.dp), style = Type.meta.copy(color = Ink.faint))
    shown.forEach { group ->
        Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.surface).padding(vertical = 4.dp)) {
            group.forEach { e ->
                Tap(e.onClick, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Ink.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            Lc(e.icon, tint = e.tint ?: Ink.text, size = 18.dp)
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(e.title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.hint, style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A tracking account: its logo, and Connect, which opens its own page with the code filled in. */
/**
 * AniList, for anime: as the trackers above, but approved on AniList's own page, which comes back to the
 * app by a link. Its token lasts a year; signing out here leaves it on AniList until removed there.
 */
@Composable
private fun AniListSection() {
    val a = com.torrentviewer.core.AniList
    val connected by a.connected.collectAsState()
    val user by a.user.collectAsState()
    var waiting by remember { mutableStateOf(false) }
    val browser = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(connected) { if (connected) waiting = false }

    Column(Modifier.padding(bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                "https://www.google.com/s2/favicons?sz=64&domain=${a.SITE}", a.NAME,
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)), scale = androidx.compose.ui.layout.ContentScale.Fit, fill = false,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(a.NAME, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    when {
                        !a.available -> "Not set up in this build"
                        connected -> user?.let { "Connected as $it" } ?: "Connected"
                        waiting -> "Waiting for your approval…"
                        else -> "Anime only · not connected"
                    },
                    style = Type.meta.copy(color = if (connected) Ink.good else Ink.faint),
                )
            }
            when {
                !a.available -> Unit
                connected -> Button("Disconnect", { a.disconnect() }, compact = true)
                else -> Button(if (waiting) "Open again" else "Connect", {
                    if (NeedProfile.first()) return@Button
                    waiting = true
                    runCatching { browser.openUri(a.authorizeUrl) }
                }, primary = !waiting, compact = true)
            }
        }
        if (waiting && !connected) Text("Approve Watchly on AniList's page; it brings you back here.", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint))
        if (connected) Text("To take Watchly's access away on AniList too, remove it under Settings → Apps there.", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint))
    }
}

@Composable
private fun TrackerSection(t: com.torrentviewer.core.Tracker) {
    val connected by t.connected.collectAsState()
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val browser = androidx.compose.ui.platform.LocalUriHandler.current

    fun connect() {
        if (NeedProfile.first()) return
        note = null
        job?.cancel()
        job = scope.launch {
            try {
                val c = t.start()
                code = c
                runCatching { browser.openUri(c.uriComplete) }
                if (!t.await(c)) note = "That didn't go through — connect again for a new code."
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) note = e.message ?: "Couldn't reach ${t.name}."
            } finally {
                code = null
            }
        }
    }

    Column(Modifier.padding(bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // By its domain, from Google's favicon service, as the review sites are.
            Artwork(
                "https://www.google.com/s2/favicons?sz=64&domain=${t.site}", t.name,
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)), scale = androidx.compose.ui.layout.ContentScale.Fit, fill = false,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.name, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    when {
                        !t.available -> "Not set up in this build"
                        connected -> "Connected"
                        code != null -> "Waiting for your approval…"
                        else -> "Not connected"
                    },
                    style = Type.meta.copy(color = if (connected) Ink.good else Ink.faint),
                )
            }
            when {
                !t.available -> Unit
                connected -> Button("Disconnect", { t.disconnect() }, compact = true)
                code != null -> Button("Cancel", { job?.cancel() }, compact = true)
                else -> Button("Connect", ::connect, primary = true, compact = true)
            }
        }
        code?.let { c ->
            Tap({ runCatching { browser.openUri(c.uriComplete) } }, Modifier.padding(top = 8.dp)) {
                Text("Approve on the page that opened, or open it again. Code ${c.userCode}", style = Type.meta.copy(color = Ink.faint))
            }
        }
        note?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint)) }
    }
}

/** Custom, then the presets, three to a row; Custom opens a colour square, a hue strip and a hex code under the grid. */
@Composable
private fun AccentPicker(current: Int, onPick: (Int) -> Unit) {
    val custom = ACCENTS.none { it.second == current }
    var editing by remember { mutableStateOf(false) }
    // The custom colour as hue, saturation, value; a soft pink until one is made.
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(if (custom) current else 0xFFF77FA8.toInt(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val mixed = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val tiles = listOf(Triple("Custom", mixed, custom)) + ACCENTS.map { (name, argb) -> Triple(name, argb, argb == current) }
    // Inside a settings card, which draws the rounded surface.
    Column(Modifier.padding(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row {
                row.forEach { (name, argb, on) ->
                    val color = accentOf(argb)
                    Tap({
                        if (name == "Custom") {
                            editing = !editing || !on
                            onPick(mixed)
                        } else {
                            editing = false
                            onPick(argb)
                        }
                    }, Modifier.weight(1f), RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val ring = if (color.luminance() > 0.8f) Ink.muted else color
                            Box(
                                Modifier.size(56.dp).border(2.dp, if (on) ring else Color.Transparent, CircleShape).padding(5.dp)
                                    .clip(CircleShape).background(color).border(1.dp, Ink.stroke, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                val mark = if (on) L.drawable.lucide_ic_check else if (name == "Custom") L.drawable.lucide_ic_pencil else null
                                mark?.let { Lc(it, tint = if (color.luminance() > 0.5f) Color(0xFF141414) else Color.White, size = 20.dp) }
                            }
                            Text(name, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = if (on) Ink.text else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // Saved when the finger lifts, not on every frame of the drag.
        if (editing) ColourMixer(h, s, v, { nh, ns, nv -> h = nh; s = ns; v = nv }) { onPick(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }
    }
}

/**
 * A colour of one's own, laid out as phone pickers do (iOS's spectrum, Material's HSV): a square for strength across
 * and brightness down, a rainbow strip for the hue, and the hex code to type one in exactly.
 */
@Composable
private fun ColourMixer(h: Float, s: Float, v: Float, onChange: (Float, Float, Float) -> Unit, onDone: () -> Unit) {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))
    // The gestures outlive a recomposition: they read these, always the latest.
    val now by rememberUpdatedState(Triple(h, s, v))
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(14.dp))
                .drawBehind {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val at = Offset(s * size.width, (1 - v) * size.height)
                    drawCircle(Color.White, 13.dp.toPx(), at)
                    drawCircle(Color(argb), 10.dp.toPx(), at)
                }
                .track({ p, box -> change(now.first, (p.x / box.width).coerceIn(0f, 1f), 1f - (p.y / box.height).coerceIn(0f, 1f)) }) { done() },
        )
        Box(
            Modifier.padding(top = 14.dp).fillMaxWidth().height(28.dp).clip(CircleShape)
                .drawBehind {
                    drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                    val r = size.height / 2
                    val at = Offset((h / 360f * size.width).coerceIn(r, size.width - r), r)
                    drawCircle(Color.White, r - 1.dp.toPx(), at)
                    drawCircle(pure, r - 4.dp.toPx(), at)
                }
                .track({ p, box -> change((p.x / box.width).coerceIn(0f, 1f) * 359.9f, now.second, now.third) }) { done() },
        )
        // Follows the square and the strip; a full code typed in moves them, and isn't rewritten by its own echo.
        var hex by remember { mutableStateOf(hexOf(argb)) }
        var typedAs by remember { mutableStateOf(argb) }
        LaunchedEffect(argb) { if (argb != typedAs) { hex = hexOf(argb); typedAs = argb } }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(argb)).border(1.dp, Ink.stroke, CircleShape))
            Field(
                hex,
                { typed ->
                    hex = "#" + typed.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                    parseHex(hex)?.let { c ->
                        val f = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
                        typedAs = android.graphics.Color.HSVToColor(f)
                        change(f[0], f[1], f[2])
                        done()
                    }
                },
                "#FF4D6D", Modifier.padding(start = 12.dp).weight(1f), action = ImeAction.Done, capitals = true,
            )
        }
    }
}

/** "#RRGGBB" (the # optional) as an opaque colour; null until all six digits are there. */
internal fun parseHex(text: String): Int? = text.removePrefix("#")
    .takeIf { it.length == 6 && it.all { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' } }
    ?.let { (0xFF000000 or it.toLong(16)).toInt() }

private fun hexOf(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

/** Down and drag, each spot reported with this box's size, [onEnd] as the finger lifts; consumed, so the page doesn't scroll. */
private fun Modifier.track(onMove: (Offset, IntSize) -> Unit, onEnd: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onMove(down.position, size)
        drag(down.id) { it.consume(); onMove(it.position, size) }
        onEnd()
    }
}

@Composable
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(title, style = Type.title.copy(fontWeight = FontWeight.Bold))
        Text(hint, Modifier.padding(top = 4.dp, bottom = 12.dp), style = Type.meta.copy(color = Ink.faint))
        content()
    }
}

/* ----------------------------------------------------------- appearance */

/**
 * Appearance as the phone's own settings lay it out: a card a subject, named above and explained under. The
 * language opens a full list of its own; the theme shows each page as a little picture to pick.
 */
@Composable
private fun AppearancePage(settings: Settings, set: (Settings) -> Unit, onEditHome: () -> Unit, onChooseLanguage: () -> Unit) {
    val context = LocalContext.current
    val i18n = com.torrentviewer.core.I18n
    val picked = remember { i18n.picked(context) }

    SettingsGroup("Language", "Watchly's words in your language. Titles and descriptions come from the catalogues as they are.") {
        LinkRow(
            L.drawable.lucide_ic_languages, "App language",
            if (picked.isEmpty()) com.torrentviewer.core.tr("Device language") + "  ·  " + deviceLanguage() else i18n.LANGUAGES[picked] ?: "English",
            onChooseLanguage,
        )
    }

    SettingsGroup("Theme", "Light or dark pages, or whichever the phone is set to. The player stays dark either way.") {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                ThemeTile(mode, label, settings.themeMode == mode, settings.amoled, Modifier.weight(1f)) { set(settings.copy(themeMode = mode)) }
            }
        }
        RowDivider()
        SwitchRow("AMOLED Black", "Use pure black backgrounds for OLED screens.", settings.amoled) { set(settings.copy(amoled = it)) }
    }

    SettingsGroup("Accent colour", "The colour of buttons, highlights and progress bars, in place of the red.") {
        AccentPicker(settings.accent) { set(settings.copy(accent = it)) }
    }

    SettingsGroup("Home screen") {
        LinkRow(L.drawable.lucide_ic_rows_3, "Home screen rows", "Move rows up or down, or switch them off. Each profile keeps its own.", onEditHome)
        RowDivider()
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Genres on the home screen", Modifier.weight(1f), style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                if (settings.genres.isNotEmpty()) Text("${settings.genres.size} picked", style = Type.meta.copy(color = Ink.accent, fontWeight = FontWeight.SemiBold))
            }
            Text(
                "Only the genres picked here get rows on the home screen. Leave all off to build it from what you have watched.",
                Modifier.padding(top = 4.dp, bottom = 14.dp), style = Type.meta.copy(color = Ink.faint),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GENRES.forEach { g ->
                    val on = g in settings.genres
                    Chip(g, on, icon = if (on) L.drawable.lucide_ic_check else null, onClick = {
                        set(settings.copy(genres = if (on) settings.genres - g else settings.genres + g))
                    })
                }
            }
        }
    }

}

/** The phone's own language, in its own words, where Watchly has it; English otherwise. */
private fun deviceLanguage(): String = com.torrentviewer.core.I18n.LANGUAGES[java.util.Locale.getDefault().language] ?: "English"

/** A settings card: its name above, the rows in it, a word on what it does under it. */
@Composable
private fun SettingsGroup(title: String, footer: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(title, Modifier.padding(start = 6.dp, bottom = 8.dp), style = Type.label.copy(color = Ink.muted, fontWeight = FontWeight.SemiBold))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.surface), content = content)
        footer?.let { Text(it, Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp), style = Type.meta.copy(color = Ink.faint)) }
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Ink.stroke.copy(alpha = 0.5f)))
}

/** A row that opens something: its icon, name and what it is set to, and a chevron pointing on. */
@Composable
private fun LinkRow(@androidx.annotation.DrawableRes icon: Int, title: String, value: String, onClick: () -> Unit) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Ink.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Lc(icon, tint = Ink.text, size = 18.dp)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Points on, which in a right-to-left language is the other way.
            Lc(L.drawable.lucide_ic_chevron_right, Modifier.graphicsLayer { if (rtl) scaleX = -1f }, tint = Ink.faint, size = 20.dp)
        }
    }
}

/** A setting that is on or off: the whole row flips it, as the switch does. */
@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
            Text(hint, Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint))
        }
        androidx.compose.material3.Switch(
            on, onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Ink.accent,
                uncheckedThumbColor = Ink.muted, uncheckedTrackColor = Ink.surface2, uncheckedBorderColor = Ink.faint,
            ),
        )
    }
}

/** A round pick-one mark: a ring, filled with a dot when it is the one. */
@Composable
private fun Radio(on: Boolean) {
    val ring by androidx.compose.animation.animateColorAsState(if (on) Ink.accent else Ink.faint, label = "radio")
    val dot by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, label = "dot")
    Box(Modifier.size(20.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).graphicsLayer { scaleX = dot; scaleY = dot }.clip(CircleShape).background(Ink.accent))
    }
}

/** A theme as a little page in its colours, the phone's own split down the middle, picked by a tap. */
@Composable
private fun ThemeTile(mode: String, label: String, on: Boolean, amoled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val edge by androidx.compose.animation.animateColorAsState(if (on) Ink.accent else Ink.stroke, label = "edge")
    val light = Triple(Color(0xFFF6F2EE), Color.White, Color(0xFFDDD5CE))
    val dark = Triple(if (amoled) Color.Black else Color(0xFF0A0A0A), Color(0xFF1C1C1C), Color(0xFF333333))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
            Box(Modifier.aspectRatio(1.3f).border(2.dp, edge, RoundedCornerShape(14.dp)).padding(4.dp).clip(RoundedCornerShape(10.dp))) {
                when (mode) {
                    "light" -> MiniPage(light)
                    "dark" -> MiniPage(dark)
                    else -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { MiniPage(light) }
                        Box(Modifier.weight(1f).fillMaxHeight()) { MiniPage(dark) }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Radio(on)
            Text(label, Modifier.padding(start = 8.dp), style = Type.label.copy(color = if (on) Ink.text else Ink.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
        }
    }
}

/** A page in miniature: a red pill for the wordmark, a card, two lines of words. */
@Composable
private fun MiniPage(colours: Triple<Color, Color, Color>) {
    val (page, card, line) = colours
    Column(Modifier.fillMaxSize().background(page).padding(8.dp)) {
        Box(Modifier.fillMaxWidth(0.45f).height(6.dp).clip(CircleShape).background(Ink.accent))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().weight(1f).clip(RoundedCornerShape(5.dp)).background(card))
        Box(Modifier.padding(top = 7.dp).fillMaxWidth(0.8f).height(5.dp).clip(CircleShape).background(line))
        Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.55f).height(5.dp).clip(CircleShape).background(line))
    }
}

/**
 * Every language, each in its own words with its name in the reader's under it. The list fades out at its foot
 * while there is more below, so a list that runs past the sheet reads as one to scroll.
 */
@Composable
private fun LanguageSheet(picked: String, device: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val i18n = com.torrentviewer.core.I18n
    val english = mapOf(
        "en" to "English", "es" to "Spanish", "pt" to "Portuguese (Brazil)", "fr" to "French", "de" to "German", "it" to "Italian",
        "ru" to "Russian", "tr" to "Turkish", "ar" to "Arabic", "hi" to "Hindi", "id" to "Indonesian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
    )
    val rows = listOf(Triple("", com.torrentviewer.core.tr("Device language"), device)) +
        i18n.LANGUAGES.map { (tag, name) -> Triple(tag, name, com.torrentviewer.core.tr(english[tag] ?: name).takeIf { it != name }) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val surface = Ink.surface
    BottomSheet("App language", onDismiss) {
        Text("${i18n.LANGUAGES.size} languages", Modifier.padding(start = 20.dp, bottom = 6.dp), style = Type.meta.copy(color = Ink.faint))
        Box {
            androidx.compose.foundation.lazy.LazyColumn(state = list) {
                rows.forEach { (tag, name, hint) ->
                    item(key = tag) {
                        val on = tag == picked
                        Tap({ onPick(tag) }, Modifier.fillMaxWidth(), RoundedCornerShape(0.dp)) { pressed ->
                            Row(
                                Modifier.background(if (pressed) Ink.text.copy(alpha = 0.08f) else Color.Transparent).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = Type.label.copy(fontSize = 16.sp, color = if (on) Ink.text else Ink.text.copy(alpha = 0.9f), fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
                                    hint?.let { Text(it, style = Type.meta.copy(color = Ink.faint), maxLines = 1) }
                                }
                                Radio(on)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (list.canScrollForward) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))),
            )
        }
    }
}

/* ------------------------------------------------------------- profiles */

private sealed interface ProfilesMode {
    data object Pick : ProfilesMode
    data object Manage : ProfilesMode
    data class Edit(val profile: Profile?) : ProfilesMode
    data class Pin(val profile: Profile) : ProfilesMode
}

/**
 * Who's watching — asked when the phone can't tell: none made yet, several, or a locked one. [create] opens on
 * a new profile, with [note] saying why: a sign-in that needs one.
 */
@Composable
fun ProfilesScreen(profiles: List<Profile>, onPick: (Profile) -> Unit, onCancel: (() -> Unit)?, onRemoved: (Profile) -> Unit, create: Boolean = false, note: String? = null) {
    var mode by remember(create) { mutableStateOf<ProfilesMode>(if (create) ProfilesMode.Edit(null) else ProfilesMode.Pick) }
    /** Asked for by a sign-in: back is the caller's, and the guest stays the guest. Otherwise back is the picker, Guest on it. */
    val forSignIn = note != null
    BackHandler(mode != ProfilesMode.Pick || onCancel != null) {
        when {
            forSignIn -> onCancel?.invoke()
            mode != ProfilesMode.Pick -> mode = ProfilesMode.Pick
            onCancel != null -> onCancel()
        }
    }
    // The editor scrolls its own form so Save stays on screen; the rest scroll whole.
    val scroll = if (mode is ProfilesMode.Edit) Modifier else Modifier.verticalScroll(rememberScrollState())
    // What's on, behind the profiles: the welcome screen's wall, washed back. The editor is a form: plain page.
    Box(Modifier.fillMaxSize()) {
    if (mode is ProfilesMode.Edit) Box(Modifier.fillMaxSize().background(Ink.bg)) else ProfilesPosterWall()
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().then(scroll).padding(horizontal = EDGE, vertical = if (mode is ProfilesMode.Edit) 8.dp else 24.dp), contentAlignment = Alignment.Center) {
        when (val m = mode) {
            ProfilesMode.Pick, ProfilesMode.Manage -> Picker(
                profiles, m == ProfilesMode.Manage, onCancel,
                open = { p ->
                    when {
                        m == ProfilesMode.Manage -> mode = ProfilesMode.Edit(p)
                        p.pin != null -> mode = ProfilesMode.Pin(p)
                        else -> onPick(p)
                    }
                },
                onAdd = { mode = ProfilesMode.Edit(null) },
                onManage = { mode = if (m == ProfilesMode.Manage) ProfilesMode.Pick else ProfilesMode.Manage },
                onGuest = { onPick(GUEST) },
            )
            is ProfilesMode.Edit -> Editor(
                m.profile,
                // The picker is always there to go back to; for a sign-in, back to where it was asked.
                canCancel = !forSignIn || onCancel != null,
                note = note.takeIf { m.profile == null },
                onSave = { name, icon, colour, kids ->
                    if (m.profile == null) onPick(Profiles.add(name, icon, colour, kids))
                    else {
                        Profiles.edit(m.profile.id, name, icon, colour, kids)
                        mode = ProfilesMode.Pick
                    }
                },
                onDelete = {
                    m.profile?.let {
                        Profiles.remove(it.id)
                        onRemoved(it)
                    }
                    mode = ProfilesMode.Pick
                },
                onCancel = { if (forSignIn) onCancel?.invoke() else mode = ProfilesMode.Pick },
            )
            is ProfilesMode.Pin -> PinPad(m.profile, onOk = { onPick(m.profile) }, onBack = { mode = ProfilesMode.Pick })
        }
    }
    }
}

@Composable
private fun Picker(
    profiles: List<Profile>,
    managing: Boolean,
    onCancel: (() -> Unit)?,
    open: (Profile) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onGuest: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (managing) "Manage profiles" else "Who's watching?", style = Type.display, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        FlowRow(Modifier.widthIn(max = 520.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            profiles.forEach { p ->
                Tile(p.name, { open(p) }) {
                    Avatar(p, 96.dp)
                    if (managing || p.pin != null) Badge(if (managing) L.drawable.lucide_ic_pencil else L.drawable.lucide_ic_lock)
                }
            }
            if (!managing) Tile("Guest", onGuest) { Avatar(GUEST, 96.dp) }
            if (!managing && profiles.size < 6) Tile("Add profile", onAdd) {
                Box(Modifier.size(96.dp).background(Ink.surface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_plus, tint = Ink.muted, size = 36.dp) }
            }
        }
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (profiles.isNotEmpty()) Button(if (managing) "Done" else "Manage", onManage, icon = if (managing) L.drawable.lucide_ic_check else L.drawable.lucide_ic_pencil)
            if (onCancel != null && !managing) Button("Back", onCancel)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Badge(icon: Int) {
    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Lc(icon, tint = Color.White, size = 14.dp)
    }
}

@Composable
private fun Tile(name: String, onClick: () -> Unit, face: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
        Tap(onClick, shape = RoundedCornerShape(12.dp)) { Box { face() } }
        Text(name, Modifier.padding(top = 10.dp), style = Type.label.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Editor(profile: Profile?, canCancel: Boolean, onSave: (String, String, String, Boolean) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit, note: String? = null) {
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var icon by remember { mutableStateOf(profile?.let { p -> p.icon?.takeIf { it.startsWith("/") } ?: personOf(p) } ?: PROFILE_ICONS[0]) }
    var colour by remember { mutableStateOf(profile?.colour ?: COLOURS[0]) }
    var kids by remember { mutableStateOf(profile?.kids ?: false) }
    var animals by remember { mutableStateOf(icon in PROFILE_ANIMALS) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** The address being typed; null while that box is closed. */
    var link by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun usePicture(data: Any) {
        failed = false
        loading = true
        scope.launch {
            val saved = savedPicture(context, data)
            loading = false
            if (saved != null) {
                icon = saved
                link = null
            } else failed = true
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::usePicture) }
    val fromPhone = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
        // Top bar, as Netflix's and Disney+'s editors: close on the left, the title in the middle.
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            if (canCancel) Tap(onCancel, Modifier.align(Alignment.CenterStart), CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(8.dp), tint = Ink.text, size = 24.dp) }
            Text(if (profile == null) "Add profile" else "Edit profile", style = Type.title.copy(fontWeight = FontWeight.Bold))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The preview; a tap picks a photo, as the pencil says.
            Tap(fromPhone, Modifier.padding(top = 12.dp), RoundedCornerShape(16.dp)) {
                Box {
                    Avatar(Profile("preview", name.ifBlank { "?" }, colour, icon), 120.dp)
                    Badge(L.drawable.lucide_ic_pencil)
                }
            }
            Text(note ?: if (profile == null) "Its own library, My List and resume points." else "Change the name, the face or the colour.", Modifier.padding(top = 12.dp, bottom = 20.dp), style = Type.meta.copy(color = Ink.faint), textAlign = TextAlign.Center)
            Field(name, { name = it.take(20) }, "Profile name", Modifier.fillMaxWidth(), action = androidx.compose.ui.text.input.ImeAction.Done)
            // People or animals, a set at a time as Disney+ groups its avatars.
            Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CHOOSE AN ICON", Modifier.weight(1f), style = Type.overline)
                Chip("People", !animals, { animals = false }, compact = true)
                Spacer(Modifier.width(8.dp))
                Chip("Animals", animals, { animals = true }, compact = true)
            }
            // Four across, in even rows.
            Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (if (animals) PROFILE_ANIMALS else PROFILE_PEOPLE).chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { a ->
                            val on = a == icon
                            val ring by androidx.compose.animation.animateColorAsState(if (on) Ink.text else Color.Transparent, label = "ring")
                            Box(Modifier.weight(1f).aspectRatio(1f).border(2.5.dp, ring, RoundedCornerShape(20.dp)).padding(5.dp)) {
                                Tap({ icon = a }, Modifier.fillMaxSize(), RoundedCornerShape(15.dp)) {
                                    androidx.compose.foundation.layout.BoxWithConstraints { AvatarImage(a, colorOf(colour), maxWidth) }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("From phone", fromPhone, Modifier.weight(1f), icon = L.drawable.lucide_ic_image)
                Button("From URL", { link = if (link == null) "" else null }, Modifier.weight(1f), icon = L.drawable.lucide_ic_link)
            }
            link?.let { typed ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(typed, { link = it }, "Link to an image", Modifier.weight(1f), onSubmit = { usePicture(typed.trim()) })
                    Button(if (loading) "Loading" else "Use", { if (!loading) usePicture(typed.trim()) }, primary = true, compact = true)
                }
            }
            if (failed) Text("Couldn't load that picture.", Modifier.fillMaxWidth().padding(top = 8.dp), style = Type.meta.copy(color = Ink.danger))
            Text("COLOUR", Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), style = Type.overline)
            // Spread across the width so all six always show; the picked one gets a ring.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                COLOURS.forEach { c ->
                    val ring by androidx.compose.animation.animateColorAsState(if (c == colour) Ink.text else Color.Transparent, label = "ring")
                    Box(Modifier.size(46.dp).border(2.5.dp, ring, CircleShape).padding(5.dp)) {
                        Tap({ colour = c }, Modifier.fillMaxSize(), CircleShape) { Box(Modifier.background(colorOf(c))) }
                    }
                }
            }
            // Kids: one switch in a card, as the streaming apps all do it.
            Row(
                Modifier.fillMaxWidth().padding(top = 28.dp).clip(RoundedCornerShape(14.dp)).background(Ink.surface).clickable { kids = !kids }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Kids profile", style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        if (kids) "Family films and series only. No addons, stream search or links from other apps — give your own profile a PIN so it stays that way."
                        else "Every title, addons and stream search included.",
                        Modifier.padding(top = 4.dp),
                        style = Type.meta.copy(color = Ink.faint),
                    )
                }
                androidx.compose.material3.Switch(
                    kids, { kids = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Ink.accent,
                        uncheckedThumbColor = Ink.muted, uncheckedTrackColor = Ink.surface2, uncheckedBorderColor = Ink.faint,
                    ),
                )
            }
            // A profile not saved yet has no id to keep a connection under.
            if (profile != null) Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                if (Stremio.available) StremioSection(profile)
                NuvioSection(profile)
            }
            if (profile != null) Tap(onDelete, Modifier.padding(top = 12.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lc(L.drawable.lucide_ic_trash_2, tint = Ink.danger, size = 18.dp)
                    Text("Delete profile", Modifier.padding(start = 8.dp), style = Type.label.copy(color = Ink.danger, fontWeight = FontWeight.SemiBold))
                }
            }
        }
        Button("Save", { onSave(name, icon, colour, kids) }, Modifier.fillMaxWidth().padding(bottom = 8.dp), icon = L.drawable.lucide_ic_check, primary = true)
    }
}

@Composable
private fun PinPad(profile: Profile, onOk: () -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(profile, 96.dp)
        Text(profile.name, Modifier.padding(top = 14.dp), style = Type.display)
        Text(if (wrong) "That's not the PIN. Try again." else "Enter your PIN to open this profile.", Modifier.padding(top = 6.dp, bottom = 20.dp), style = Type.body.copy(color = if (wrong) Ink.danger else Ink.muted), textAlign = TextAlign.Center)
        Field(pin, { v ->
            val digits = v.filter(Char::isDigit).take(4)
            pin = digits
            wrong = false
            // Opens on the fourth digit — nobody wants to reach for OK after a PIN.
            if (digits.length == 4) {
                if (digits == profile.pin) onOk() else {
                    wrong = true
                    pin = ""
                }
            }
        }, "• • • •", Modifier.width(200.dp), secret = true, digits = true)
        Spacer(Modifier.height(20.dp))
        Button("Back", onBack)
    }
}
