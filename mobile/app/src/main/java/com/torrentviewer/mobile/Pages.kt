@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torrentviewer.mobile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.torrentviewer.core.Backup
import com.torrentviewer.core.LANGS
import com.torrentviewer.core.Phase
import com.torrentviewer.core.SUB_SIZES
import com.torrentviewer.core.langName
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.AVATARS
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.CACHE_CAPS
import com.torrentviewer.core.COLOURS
import com.torrentviewer.core.GENRES
import com.torrentviewer.core.GUEST
import com.torrentviewer.core.Hit
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Item
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Note
import com.torrentviewer.core.Profile
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.Searches
import com.torrentviewer.core.Settings
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Sort
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.ago
import com.torrentviewer.core.avatarIcon
import com.torrentviewer.core.colorOf
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.searchTorrents
import com.torrentviewer.core.sortTitles
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import com.composables.icons.lucide.R as L

/** Poster columns: three across a phone held upright. */
private val POSTER = GridCells.Adaptive(104.dp)

private fun subOf(t: Title) = listOfNotNull(if (t.isSeries) "Series" else "Film", t.year?.take(4)).joinToString("  ·  ")

/* -------------------------------------------------------------- catalog */

/** A whole shelf as a grid, with the phone's four orders. */
@Composable
fun CatalogScreen(shelf: Shelf, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    var titles by remember { mutableStateOf(Meta.peekCatalog(shelf.kind, shelf.genre)) }
    LaunchedEffect(shelf.key) { if (titles.isNullOrEmpty()) titles = Meta.catalog(shelf.kind, shelf.genre) }
    var sort by rememberSaveable { mutableStateOf(Sort.POPULAR) }
    val shown = remember(titles, sort) { sortTitles(titles.orEmpty(), sort) }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            POSTER,
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 24.dp),
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
        BackButton(onBack)
    }
}

/* --------------------------------------------------------------- search */

/** How well a title answers what was typed; lower is better. */
private fun relevance(title: String, query: String): Int {
    val a = title.trim().lowercase()
    val q = query.trim().lowercase()
    return when {
        a == q -> 0
        a.startsWith(q) -> 1
        q in a -> 2
        else -> 3
    }
}

/** "2020s"… and before 1990 one bucket; the year a title's releaseInfo starts with. */
private val DECADES = listOf(2020 to "2020s", 2010 to "2010s", 2000 to "2000s", 1990 to "1990s", 0 to "Before 1990")

private fun decadeOf(t: Title) = t.year?.take(4)?.toIntOrNull()?.let { if (it < 1990) 0 else it / 10 * 10 }

/** Which filter's list is up. */
private enum class Picking { KIND, GENRE, DECADE, SORT, QUALITY, ORDER }

/** How releases are ordered: the ranking (seeds, then fit), or by size. */
private enum class HitOrder(val label: String) { BEST("Best match"), BIGGEST("Biggest"), SMALLEST("Smallest") }

/**
 * Results arrive as the letters do; the Torrents switch searches release names instead.
 * Filters narrow either list — film or series, genre, decade and order for titles; quality
 * and size for releases. A genre with nothing typed browses that genre's shelf.
 */
@Composable
fun SearchScreen(watch: Watch, onOpen: (Title) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var torrents by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Title>?>(null) }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(Searches.load()) }
    // Filters: null is "any".
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var decade by rememberSaveable { mutableStateOf<Int?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.POPULAR) }
    var quality by rememberSaveable { mutableStateOf<String?>(null) }
    var order by rememberSaveable { mutableStateOf(HitOrder.BEST) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val filtered = !torrents && (kind != null || genre != null || decade != null || sort != Sort.POPULAR) || torrents && (quality != null || order != HitOrder.BEST)

    LaunchedEffect(query, torrents, genre, kind) {
        val q = query.trim()
        if (q.length < 2) {
            hits = null
            // Nothing typed, a genre picked: that genre's shelf, films and series together unless one is chosen.
            val g = genre
            results = if (!torrents && g != null) {
                busy = true
                coroutineScope {
                    listOf("movie", "tv").filter { kind == null || it == kind }.map { k -> async { Meta.catalog(k, g) } }.flatMap { it.await() }.distinctBy { it.imdb }
                }
            } else null
            busy = false
            return@LaunchedEffect
        }
        delay(400)
        busy = true
        if (torrents) {
            hits = searchTorrents(q) { hits = it }
        } else {
            results = coroutineScope {
                val films = async { Meta.search(q, "movie") }
                val series = async { Meta.search(q, "tv") }
                (films.await() + series.await()).distinctBy { it.imdb }.sortedBy { relevance(it.title, q) }
            }
        }
        busy = false
    }
    val browsing = query.trim().length < 2
    val titles = results?.let { list ->
        sortTitles(
            list.filter { t ->
                (kind == null || t.kind == kind) && (decade == null || decadeOf(t) == decade) &&
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

    LazyVerticalGrid(
        POSTER,
        state = rememberLazyGridState(),
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Heading("Search")
                Field(
                    query,
                    { query = it },
                    if (torrents) "Release or torrent name" else "Movies, shows, anime",
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
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Titles", !torrents, onClick = { torrents = false; hits = null })
                    Chip("Torrents", torrents, onClick = { torrents = true; results = null }, icon = L.drawable.lucide_ic_layers)
                }
                // One chip per filter, red while it narrows anything.
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!torrents) {
                        Chip(when (kind) { "movie" -> "Films"; "tv" -> "Series"; else -> "Films & series" }, kind != null, onClick = { picking = Picking.KIND })
                        Chip(genre ?: "Any genre", genre != null, onClick = { picking = Picking.GENRE })
                        Chip(DECADES.find { it.first == decade }?.second ?: "Any year", decade != null, onClick = { picking = Picking.DECADE })
                        Chip(if (sort == Sort.POPULAR) "Best match" else sort.label, sort != Sort.POPULAR, onClick = { picking = Picking.SORT })
                    } else {
                        Chip(quality ?: "Any quality", quality != null, onClick = { picking = Picking.QUALITY })
                        Chip(order.label, order != HitOrder.BEST, onClick = { picking = Picking.ORDER })
                    }
                    if (filtered) Chip("Clear", false, icon = L.drawable.lucide_ic_x, onClick = {
                        kind = null; genre = null; decade = null; sort = Sort.POPULAR; quality = null; order = HitOrder.BEST
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

    picking?.let { which ->
        val close = { picking = null }
        when (which) {
            Picking.KIND -> Sheet("Show", listOf(Option<String?>(null, "Films & series"), Option("movie", "Films"), Option("tv", "Series")), kind, { kind = it; close() }, close)
            Picking.GENRE -> Sheet("Genre", listOf(Option<String?>(null, "Any genre")) + GENRES.map { Option<String?>(it, it) }, genre, { genre = it; close() }, close)
            Picking.DECADE -> Sheet("Year", listOf(Option<Int?>(null, "Any year")) + DECADES.map { (y, label) -> Option<Int?>(y, label) }, decade, { decade = it; close() }, close)
            Picking.SORT -> Sheet("Order", Sort.entries.map { Option(it, if (it == Sort.POPULAR) "Best match" else it.label) }, sort, { sort = it; close() }, close)
            Picking.QUALITY -> Sheet("Quality", listOf(Option<String?>(null, "Any quality")) + listOf("4K", "1080p", "720p").map { Option<String?>(it, it) }, quality, { quality = it; close() }, close)
            Picking.ORDER -> Sheet("Order", HitOrder.entries.map { Option(it, it.label) }, order, { order = it; close() }, close)
        }
    }
}

/* -------------------------------------------------------------- library */

private enum class Shelfs(val label: String) { HISTORY("History"), LATER("My List"), FAVORITE("Favourites") }

/** Everything played (the only way back to a magnet watched once), and the two saved lists. */
@Composable
fun LibraryScreen(watch: Watch, onOpen: (Title) -> Unit) {
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()
    var shelf by rememberSaveable { mutableStateOf(Shelfs.HISTORY) }
    var menu by remember { mutableStateOf<Item?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val items = history.sortedByDescending { it.at }
    val listed = saved.filter { it.list == if (shelf == Shelfs.FAVORITE) "favorite" else "later" }.map { it.asTitle() }

    LazyVerticalGrid(
        if (shelf == Shelfs.HISTORY) GridCells.Adaptive(cardWidth()) else POSTER,
        state = rememberLazyGridState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Heading("Library") {
                    if (shelf == Shelfs.HISTORY && items.isNotEmpty()) Tap({ clearing = true }, shape = CircleShape) {
                        Lc(L.drawable.lucide_ic_trash_2, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp)
                    }
                }
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Shelfs.entries.forEach { s -> Chip(s.label, s == shelf, onClick = { shelf = s }) }
                }
            }
        }
        if (shelf == Shelfs.HISTORY) {
            if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Note("Nothing here yet. Anything you play turns up in this list.", Modifier.padding(top = 16.dp)) }
            items(items, key = { it.uri }) { item ->
                val ep = item.lastFileIndex?.let { i -> item.files?.find { it.index == i } }?.let { parseEpisode(it.name) }
                Tap({ watch.resume(item) }, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(6.dp), onLongClick = { menu = item }) {
                    LandscapeCard(
                        item.art(),
                        item.label,
                        listOfNotNull(ep?.let { "S${it.season}:E${it.episode}" }, ago(item.at)).joinToString("  ·  "),
                        item.ratio.toFloat().takeIf { it > 0f },
                    )
                }
            }
        } else {
            if (listed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Note(if (shelf == Shelfs.LATER) "Titles you add to My List wait here." else "Titles you like collect here.", Modifier.padding(top = 16.dp))
            }
            items(listed, key = { it.imdb }) { t -> PosterCard(t, { onOpen(t) }, sub = subOf(t)) }
        }
    }

    menu?.let { item ->
        Sheet(
            title = item.label,
            options = listOf(Option(true, "Remove from Library")),
            selected = null,
            onPick = {
                History.drop(item.uri, watch.engine)
                menu = null
            },
            onDismiss = { menu = null },
        )
    }
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

/* ------------------------------------------------------------- settings */

@Composable
fun SettingsScreen(watch: Watch, who: Profile, onSwitchProfile: () -> Unit) {
    val settings by SettingsStore.value.collectAsState()
    val profiles by Profiles.list.collectAsState()
    val me = profiles.find { it.id == who.id } ?: who
    var pin by remember { mutableStateOf("") }
    /** "audio" or "subs" while that language list is up. */
    var picking by remember { mutableStateOf<String?>(null) }
    fun set(next: Settings) = SettingsStore.save(next)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = EDGE).padding(bottom = 32.dp)) {
        Heading("Settings")

        Section(
            "Profile",
            when {
                me.isGuest -> "Guest keeps nothing — history and My List go when the app closes."
                profiles.isEmpty() -> "One library on this phone. Add profiles to keep a library, My List and resume points each."
                else -> "Library, My List and resume points belong to this profile."
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me, 44.dp)
                Spacer(Modifier.width(14.dp))
                Button(if (profiles.isEmpty()) "Add profiles" else "Switch profile", onSwitchProfile, icon = L.drawable.lucide_ic_users)
            }
        }

        // Only a saved profile can carry a lock; the phone's owner before profiles has nowhere to keep one.
        if (!me.isGuest && profiles.any { it.id == me.id }) {
            Section(
                "Profile PIN",
                if (me.pin != null) "Asked for before this profile opens. Remove it to unlock."
                else "Four digits, asked for before this profile opens. A lock for a shared phone — not a password.",
            ) {
                // The digits and Set side by side; Remove on its own line, so nothing is cut short on a narrow phone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(pin, { pin = it.filter(Char::isDigit).take(4) }, if (me.pin != null) "• • • •" else "– – – –", Modifier.weight(1f).widthIn(max = 200.dp), secret = true, digits = true)
                    Spacer(Modifier.width(10.dp))
                    Button(if (me.pin != null) "Change PIN" else "Set PIN", {
                        if (pin.length == 4) {
                            Profiles.setPin(me.id, pin)
                            pin = ""
                            watch.notes.tryEmit("PIN set for ${me.name}")
                        } else watch.notes.tryEmit("Enter four digits first")
                    }, icon = L.drawable.lucide_ic_lock)
                }
                if (me.pin != null) Button("Remove PIN", { Profiles.setPin(me.id, "") }, Modifier.padding(top = 10.dp), icon = L.drawable.lucide_ic_lock_open)
            }
        }

        Section("Appearance", "Light or dark pages, or whichever the phone is set to. The player stays dark either way.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                    Chip(label, settings.themeMode == mode, onClick = { set(settings.copy(themeMode = mode)) })
                }
            }
        }

        Section(
            "Storage for one film",
            "How much of a film may sit on this phone at once. When the cap is reached the stream restarts from where you are — a pause of a few seconds.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CACHE_CAPS.forEach { mb -> Chip("${mb / 1024} GB", settings.cacheCapMb == mb, onClick = { set(settings.copy(cacheCapMb = mb)) }) }
            }
        }

        Section("Buffer before playing", "Larger starts slower but is steadier on a weak swarm.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 4, 8, 16).forEach { mb -> Chip("$mb MB", settings.prepareMb == mb, onClick = { set(settings.copy(prepareMb = mb)) }) }
            }
        }

        Section("Player", "Media3 hands a file it can't play to VLC by itself. VLC from the start plays the most formats and full ASS subtitle styling.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Media3", !settings.vlcPlayer, onClick = { set(settings.copy(vlcPlayer = false)) })
                Chip("VLC", settings.vlcPlayer, onClick = { set(settings.copy(vlcPlayer = true)) })
            }
        }

        Section("Extra trackers", "Attach public trackers to bare magnet links so they resolve faster.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("On", settings.addTrackers, onClick = { set(settings.copy(addTrackers = true)) })
                Chip("Off", !settings.addTrackers, onClick = { set(settings.copy(addTrackers = false)) })
            }
        }

        Section("Languages", "A matching track comes on by itself when a video opens; picking one yourself wins. Online subtitle search looks in the subtitle language.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Section("Downloads", "Wi-Fi only holds a download on mobile data — it waits, and carries on when Wi-Fi is back. Use mobile data on a waiting download to let that one through.") {
            var wifiOnly by remember { mutableStateOf(Downloads.wifiOnly) }
            var sdCard by remember { mutableStateOf(Downloads.sdCard) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Wi-Fi only", wifiOnly, onClick = { wifiOnly = true; Downloads.wifiOnly = true })
                Chip("Any network", !wifiOnly, onClick = { wifiOnly = false; Downloads.wifiOnly = false })
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

        Section("Backup", "History, resume points, lists, settings, searches and intro marks as one file. Downloads are not in it — they are files on this phone.") {
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

        Section("Genres on the home screen", "Leave all off to build the home screen from what you have watched.") {
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

@Composable
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 28.dp)) {
        Text(title, style = Type.title.copy(fontWeight = FontWeight.Bold))
        Text(hint, Modifier.padding(top = 4.dp, bottom = 12.dp), style = Type.meta.copy(color = Ink.faint))
        content()
    }
}

/* ------------------------------------------------------------- profiles */

private sealed interface ProfilesMode {
    data object Pick : ProfilesMode
    data object Manage : ProfilesMode
    data class Edit(val profile: Profile?) : ProfilesMode
    data class Pin(val profile: Profile) : ProfilesMode
}

/** Who's watching — asked only when the phone can't tell: several profiles, or a locked one. */
@Composable
fun ProfilesScreen(profiles: List<Profile>, onPick: (Profile) -> Unit, onCancel: (() -> Unit)?, onRemoved: (Profile) -> Unit) {
    var mode by remember { mutableStateOf<ProfilesMode>(if (profiles.isEmpty()) ProfilesMode.Edit(null) else ProfilesMode.Pick) }
    BackHandler(mode != ProfilesMode.Pick || onCancel != null) {
        when {
            mode != ProfilesMode.Pick && profiles.isNotEmpty() -> mode = ProfilesMode.Pick
            onCancel != null -> onCancel()
        }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = EDGE, vertical = 24.dp), contentAlignment = Alignment.Center) {
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
                canCancel = profiles.isNotEmpty() || onCancel != null,
                onSave = { name, icon, colour ->
                    if (m.profile == null) onPick(Profiles.add(name, icon, colour))
                    else {
                        Profiles.edit(m.profile.id, name, icon, colour)
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
                onCancel = { if (profiles.isEmpty()) onCancel?.invoke() else mode = ProfilesMode.Pick },
            )
            is ProfilesMode.Pin -> PinPad(m.profile, onOk = { onPick(m.profile) }, onBack = { mode = ProfilesMode.Pick })
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
private fun Editor(profile: Profile?, canCancel: Boolean, onSave: (String, String, String) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var icon by remember { mutableStateOf(profile?.icon ?: AVATARS[0]) }
    var colour by remember { mutableStateOf(profile?.colour ?: COLOURS[0]) }
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(Profile("preview", name.ifBlank { "?" }, colour, icon), 110.dp)
        Text(if (profile == null) "Add a profile" else "Edit profile", Modifier.padding(top = 18.dp), style = Type.display)
        Text(if (profile == null) "Its own library, My List and resume points." else "Change the name, the face or the colour.", Modifier.padding(top = 4.dp, bottom = 18.dp), style = Type.meta, textAlign = TextAlign.Center)
        Field(name, { name = it.take(20) }, "Name", Modifier.fillMaxWidth())
        Text("ICON", Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), style = Type.overline)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AVATARS.forEach { a ->
                Tap({ icon = a }, Modifier.size(46.dp), CircleShape) {
                    Box(Modifier.background(if (a == icon) Ink.accent else Ink.surface2), contentAlignment = Alignment.Center) { Lc(avatarIcon(a), tint = if (a == icon) Color.White else Ink.text, size = 22.dp) }
                }
            }
        }
        Text("COLOUR", Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), style = Type.overline)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            COLOURS.forEach { c ->
                Tap({ colour = c }, Modifier.size(40.dp), CircleShape) {
                    Box(Modifier.background(colorOf(c)), contentAlignment = Alignment.Center) { if (c == colour) Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 18.dp) }
                }
            }
        }
        Button("Save", { onSave(name, icon, colour) }, Modifier.fillMaxWidth().padding(top = 28.dp), icon = L.drawable.lucide_ic_check, primary = true)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canCancel) Button("Cancel", onCancel)
            if (profile != null) Button("Delete profile", onDelete, icon = L.drawable.lucide_ic_trash_2)
        }
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
