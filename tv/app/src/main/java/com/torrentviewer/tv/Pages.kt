@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.tv
import com.torrentviewer.core.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/* ---------------------------------------------------------------- input */

/** A text box the remote can land on; OK opens the television's keyboard. */
@Composable
fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    requester: FocusRequester? = null,
    secret: Boolean = false,
    digits: Boolean = false,
    onSubmit: () -> Unit = {},
    /** The keyboard's action key: Done, or Search on the search page. */
    imeAction: ImeAction = ImeAction.Done,
    leading: Int? = null,
    /** False: landing on it leaves the keyboard down until OK — for boxes passed on the way down a page. */
    keyboardOnFocus: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateColorAsState(if (focused) Color.White else Color.Transparent, Motion.effects(), label = "ring")
    // Passed on the way down a page, the box itself takes focus and the text field stays shut until
    // OK: some TV keyboards ignore showKeyboardOnFocus = false and opened at every key box.
    var editing by remember { mutableStateOf(keyboardOnFocus) }
    val text = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing && !keyboardOnFocus) runCatching { text.requestFocus() } }
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = if (focused) 0.16f else 0.09f)).border(2.dp, ring, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.hasFocus }
            .then(
                if (keyboardOnFocus) Modifier
                else Modifier.focusProperties { canFocus = !editing }.clickable(remember { MutableInteractionSource() }, indication = null) { editing = true },
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Lc(it, tint = if (focused) Ink.text else Ink.faint, size = 22.dp)
            Spacer(Modifier.width(14.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Type.title.copy(color = Ink.faint, fontWeight = FontWeight.Medium))
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = Type.title.copy(fontWeight = FontWeight.Medium, letterSpacing = if (secret) 8.sp else 0.sp),
                cursorBrush = SolidColor(Ink.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Text, imeAction = imeAction, showKeyboardOnFocus = keyboardOnFocus),
                // Then what the key does anyway: Done puts the keyboard away.
                keyboardActions = KeyboardActions(onAny = { onSubmit(); defaultKeyboardAction(imeAction) }),
                modifier = Modifier.fillMaxWidth()
                    .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                    .focusRequester(text)
                    .focusProperties { canFocus = editing }
                    // Left again: shut until the next OK.
                    .onFocusChanged { if (!it.isFocused && editing && !keyboardOnFocus) editing = false },
            )
        }
        trailing()
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

/**
 * Results arrive as the letters do — the remote's keyboard is slow — and both films
 * and series are searched at once. The Torrents switch searches release names instead.
 */
@Composable
fun SearchScreen(watch: Watch, onOpen: (Title) -> Unit) {
    var query by remember { mutableStateOf("") }
    // Off again whenever kids mode changes, so a kids profile never lands on the torrent list.
    var torrents by remember(Kids.on) { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Title>?>(null) }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(Searches.load()) }
    val field = remember { FocusRequester() }
    field.claim()

    LaunchedEffect(query, torrents) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            hits = null
            busy = false
            return@LaunchedEffect
        }
        delay(450)
        busy = true
        // A kids profile has no torrent search (a release name can't be vetted) and sees family titles only.
        if (torrents && !Kids.on) {
            hits = searchStreams(q) { hits = it }
        } else {
            results = coroutineScope {
                val films = async { Meta.search(q, "movie") }
                val series = async { Meta.search(q, "tv") }
                // And every addon catalogue that searches, as Stremio asks them all.
                val mine = async { Meta.searchAddons(q) }
                Kids.only((films.await() + series.await() + mine.await()).distinctBy { it.imdb }.sortedBy { relevance(it.title, q) })
            }
        }
        busy = false
    }

    // The page holds still while focus moves between cards already on screen, and scrolls
    // only enough to show the next one with its title. Pinning every row to the top shoved
    // the whole page, header and all, on each move.
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberMinimalScroll(60.dp)) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = GUTTER, end = Safe.x, top = Safe.y, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Search", Modifier.padding(bottom = 18.dp), style = Type.display)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        query,
                        { query = it },
                        if (torrents) "A film, or a link to play" else "Movies, shows, anime",
                        Modifier.weight(1f).widthIn(max = 640.dp),
                        requester = field,
                        leading = L.drawable.lucide_ic_search,
                        onSubmit = { recents = Searches.remember(query) },
                        imeAction = ImeAction.Search,
                        trailing = { if (busy) Spinner(size = 22.dp) },
                    )
                    if (!Kids.on) {
                        Spacer(Modifier.width(14.dp))
                        Chip("Streams", torrents, onClick = { torrents = !torrents; results = null; hits = null }, icon = L.drawable.lucide_ic_layers)
                    }
                }
                if (recents.isNotEmpty() && results == null && hits == null) {
                    Text("Recent searches", Modifier.padding(top = 30.dp, bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        recents.forEach { r -> Chip(r, false, onClick = { query = r }, icon = L.drawable.lucide_ic_history) }
                        Chip("Clear", false, onClick = { recents = Searches.clear() })
                    }
                }
                val heading = when {
                    torrents && hits != null -> if (hits!!.isEmpty()) if (busy) "Searching…" else "No releases for “${query.trim()}”" else "${hits!!.size} releases for “${query.trim()}”" + if (busy) "  ·  finding more…" else ""
                    !torrents && results != null -> if (results!!.isEmpty()) "Nothing found for “${query.trim()}”" else "Top results"
                    else -> null
                }
                heading?.let { Text(it, Modifier.padding(top = 30.dp), style = Type.title.copy(fontWeight = FontWeight.Bold)) }
            }
        }
        if (torrents) {
            items(hits.orEmpty(), key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { hit ->
                Source(hit) {
                    recents = Searches.remember(query)
                    watch.stream(hit, null)
                }
            }
        } else {
            items(results.orEmpty(), key = { it.imdb }) { t ->
                PosterCard(t, onClick = {
                    recents = Searches.remember(query)
                    onOpen(t)
                }, sub = listOfNotNull(if (t.isSeries) "Series" else "Film", t.year?.take(4)).joinToString("  ·  "))
            }
        }
    }
    }
}

/* -------------------------------------------------------------- library */

/** Every release a title was played from, each with its size and where it was left; picking one resumes it. */
@Composable
fun ReleasesSheet(all: List<Item>, watch: Watch, onDismiss: () -> Unit) {
    Sheet(
        title = all.first().label.substringBefore(" · "),
        options = all.map {
            val hint = listOfNotNull(it.playedSize()?.let(::bytes), it.seeders?.let { s -> "$s seeds" }, it.leftLabel() ?: "Not started")
            Option(it.uri, it.torrentName ?: it.title, hint.joinToString("  ·  "))
        },
        selected = latestPerTitle(all).first().uri,
        onPick = { uri ->
            onDismiss()
            all.find { it.uri == uri }?.let { watch.resume(it) }
        },
        onDismiss = onDismiss,
    )
}

/** Played from more than one release: ask which, each resuming where it was left. */
fun releasesOf(history: List<Item>, item: Item) = history.filter { it.titleKey == item.titleKey }.sortedByDescending { it.at }

/** Everything opened before, newest first — the only way back to a magnet watched once. */
@Composable
fun LibraryScreen(watch: Watch, me: Profile, onOpen: (Title) -> Unit) {
    val history by History.items.collectAsState()
    // One card per title, however many releases it was played from.
    val items = latestPerTitle(history)
    // After a reinstall this TV's history is empty; what Stremio has still belongs here.
    val stremioLibrary by Stremio.library.collectAsState()
    val fromStremio = remember(stremioLibrary, history, Kids.on, Kids.allowed) { Kids.only(stremioLibrary.notIn(history)) { Title(it.imdb, it.kind, it.title) } }
    var focusedUri by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    var releases by remember { mutableStateOf<List<Item>?>(null) }
    val stremio by Stremio.connected.collectAsState()
    /** Per profile; the guest is offered nothing it would lose on exit. */
    var stremioLater by remember(me.id) { mutableStateOf(me.isGuest || Kv.get(STREMIO_LATER) != null) }

    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll(110.dp)) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_W + 10.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = GUTTER, end = Safe.x, top = Safe.y, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Library", style = Type.display)
                    Text("Everything you have played, with where you stopped.", style = Type.meta)
                }
                if (items.isNotEmpty()) TvButton("Clear all", { History.clear(watch.engine) }, icon = L.drawable.lucide_ic_trash_2)
            }
        }
        // Where the history is, the history's sync is offered.
        if (Stremio.available && !stremio && !stremioLater) item(span = { GridItemSpan(maxLineSpan) }) {
            StremioSection(me, onLater = {
                Kv.set(STREMIO_LATER, "1")
                stremioLater = true
            })
        }
        if (stremio) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("✓ Signed in to Stremio — your library and where you stopped are below", style = Type.meta.copy(color = Ink.faint))
        }
        if (items.isEmpty() && fromStremio.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Note("Nothing here yet. Anything you play turns up in this list.", Modifier.padding(top = 20.dp))
        }
        items(items, key = { it.uri }) { item ->
            Column {
                Focusable(
                    onClick = { releasesOf(history, item).let { all -> if (all.size > 1) releases = all else watch.resume(item) } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(8.dp),
                    scale = 1.08f,
                    requester = if (item == items.first()) first else null,
                    onFocus = { focusedUri = item.uri },
                ) {
                    Artwork(item.backdrop ?: item.imdb?.let { "https://images.metahub.space/background/medium/$it/img" } ?: remoteArt(item.poster), item.label, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(item.label, style = Type.label.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val ep = item.lastFileIndex?.let { i -> item.files?.find { it.index == i } }?.let { parseEpisode(it.name) }
                        val count = releasesOf(history, item).size
                        Text(
                            listOfNotNull(ep?.let { "S${it.season}:E${it.episode}" }, ago(item.at), item.playedSize()?.let(::bytes), if (count > 1) "$count releases" else null).joinToString("  ·  "),
                            style = Type.meta.copy(fontSize = 11.sp), maxLines = 1,
                        )
                        if (item.ratio > 0) {
                            Spacer(Modifier.height(5.dp))
                            ProgressLine(item.ratio.toFloat())
                        }
                    }
                }
                // Only under the card the remote is on: forty remove buttons is forty stops.
                if (focusedUri == item.uri) {
                    Chip("Remove", false, onClick = { History.dropTitle(item.titleKey, watch.engine) }, modifier = Modifier.padding(top = 10.dp), icon = L.drawable.lucide_ic_trash_2)
                }
            }
        }
        if (fromStremio.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("From Stremio", Modifier.padding(top = 14.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        }
        // Never played on this TV: no release to resume, so the title opens; one picked there starts at Stremio's spot.
        items(fromStremio, key = { "stremio:${it.imdb}" }) { s ->
            val paused = stremioLibrary.paused.find { it.imdb == s.imdb }
            Focusable(
                onClick = { onOpen(Title(s.imdb, s.kind, s.title, year = s.year)) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shape = RoundedCornerShape(8.dp),
                scale = 1.08f,
                requester = if (items.isEmpty() && s == fromStremio.first()) first else null,
            ) {
                Artwork("https://images.metahub.space/background/medium/${s.imdb}/img", s.title, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    Text(s.title, style = Type.label.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = when {
                        paused != null -> listOfNotNull(s.last, "${paused.progress.toInt()}% in").joinToString("  ·  ")
                        s.kind == "movie" -> "Watched"
                        else -> s.last?.let { "Last watched $it" } ?: "Watched"
                    }
                    Text("$state  ·  on Stremio", style = Type.meta.copy(fontSize = 11.sp), maxLines = 1)
                    paused?.let {
                        Spacer(Modifier.height(5.dp))
                        ProgressLine((it.progress / 100).toFloat())
                    }
                }
            }
        }
    }
    }
    if (items.isNotEmpty() || fromStremio.isNotEmpty()) first.claim()
    releases?.let { all -> ReleasesSheet(all, watch) { releases = null } }
}

/* ------------------------------------------------------------- settings */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(who: Profile, onSwitchProfile: () -> Unit) {
    val settings by SettingsStore.value.collectAsState()
    val profiles by Profiles.list.collectAsState()
    val me = profiles.find { it.id == who.id } ?: who
    var pin by remember { mutableStateOf("") }
    val subdl by Secrets.subdl.collectAsState()
    var key by remember { mutableStateOf("") }
    val torbox by TorBox.key.collectAsState()
    var torboxKey by remember { mutableStateOf("") }
    /** The account the saved key opens, or why it doesn't; null while asking. */
    var torboxAccount by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(torbox) {
        torboxAccount = null
        if (torbox.isNotEmpty()) torboxAccount = runCatching { TorBox.account() }.getOrElse { "Not working: ${it.message}" }
    }
    val first = remember { FocusRequester() }
    fun set(next: Settings) = SettingsStore.save(next)
    /** The page open, by its title; null is the list of them. */
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val update by Updates.release.collectAsState()
    val downloading by Updates.progress.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun say(note: String) = Services.watch.notes.tryEmit(note)
    androidx.activity.compose.BackHandler(page != null) { page = null }
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }

    Column(
        Modifier.fillMaxSize()
            // In from the rail: a box on the left won the focus search and opened the keyboard.
            // Nothing to land on when a search matched nothing: the search box takes it, as it would.
            .focusProperties { onEnter = { runCatching { first.requestFocus() } } }
            .focusGroup()
            .verticalScroll(scroll)
            .padding(start = GUTTER, end = Safe.x, top = Safe.y, bottom = 120.dp),
    ) {
        if (page == null) {
            Text("Settings", style = Type.display)
            // Up here, not at the foot: the D-pad only scrolls as far as the last thing it can focus.
            Text(
                "Version ${BuildConfig.VERSION_NAME} · built " +
                    java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date(BuildConfig.BUILD_TIME)),
                Modifier.padding(top = 4.dp, bottom = 18.dp),
                style = Type.meta.copy(color = Ink.faint),
            )
            Field(query, { query = it }, "Search settings", Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 14.dp), leading = L.drawable.lucide_ic_search, keyboardOnFocus = false)
            SettingsList(
                listOfNotNull(
                    update?.let { r ->
                        val p = downloading
                        listOf(
                            if (p != null) Entry(L.drawable.lucide_ic_x, "Cancel the update", "Downloading version ${r.version}  ·  ${(p * 100).toInt()}%", tint = Ink.accent, onClick = Updates::cancel)
                            else Entry(L.drawable.lucide_ic_download, "Update available", "Version ${r.version}  ·  ${bytes(r.size)}", "new version upgrade", Ink.accent) { Updates.download(context, r, ::say) },
                        )
                    },
                    listOf(
                        Entry(L.drawable.lucide_ic_user, "Account", "Profile, PIN and Stremio account", "lock sign in") { page = "Account" },
                        Entry(L.drawable.lucide_ic_users, "Switch profile", "Change to a different profile", onClick = onSwitchProfile),
                    ),
                    listOf(
                        Entry(L.drawable.lucide_ic_palette, "Appearance", "Genres on the home screen", "theme") { page = "Appearance" },
                    ),
                    listOfNotNull(
                        Entry(L.drawable.lucide_ic_radio, "Streaming", "Storage, buffer and trackers", "cache magnet") { page = "Streaming" },
                        Entry(L.drawable.lucide_ic_play, "Playback", "Player and skip intro", "vlc media3") { page = "Playback" },
                        // A kids profile plays what it can browse to; where streams come from is the grown-ups' call.
                        if (Kids.on) null else Entry(L.drawable.lucide_ic_puzzle, "Addons", "Where streams and subtitles come from", "stremio debrid") { page = "Addons" },
                    ),
                    listOf(
                        Entry(L.drawable.lucide_ic_link, "Integrations", "Simkl and MDBList", "tracking watched sync") { page = "Integrations" },
                        Entry(L.drawable.lucide_ic_key, "Services", "SubDL and TorBox keys", "subtitles api") { page = "Services" },
                    ),
                    listOf(
                        Entry(L.drawable.lucide_ic_refresh_cw, "Check for updates", "You have version ${BuildConfig.VERSION_NAME}", "new version upgrade") { Updates.checkNow(::say) },
                    ),
                ),
                query,
                first,
            )
        } else {
            Row(Modifier.padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("Settings", { page = null }, icon = L.drawable.lucide_ic_arrow_left, requester = first)
                Spacer(Modifier.width(18.dp))
                Text(page.orEmpty(), style = Type.display)
            }
        }

        if (page == "Integrations") {
            Text(
                "What you finish here is marked watched on your accounts, and what they have watched shows here. You sign in on your phone, so your password never comes to Watchly.",
                Modifier.padding(bottom = 24.dp).widthIn(max = 760.dp),
                style = Type.meta.copy(color = Ink.faint),
            )
            WATCH_TRACKERS.forEach { TrackerSection(it) }
        }

        if (page == "Account") {
        StremioSection(me)

        Section("Profile", if (me.isGuest) "Guest keeps nothing — history and My List go when the app closes." else "Library, My List and resume points belong to this profile.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me, 44.dp)
                Spacer(Modifier.width(16.dp))
                TvButton("Watching as ${me.name} — switch", onSwitchProfile, icon = L.drawable.lucide_ic_users)
            }
        }

        if (!me.isGuest) {
            Section(
                "Profile PIN",
                if (me.pin != null) "Asked for before this profile opens. Remove it to unlock."
                else "Four digits, asked for before this profile opens. A lock for a shared TV — not a password.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(pin, { pin = it.filter(Char::isDigit).take(4) }, if (me.pin != null) "••••" else "– – – –", Modifier.width(180.dp), secret = true, digits = true, keyboardOnFocus = false)
                    Spacer(Modifier.width(14.dp))
                    TvButton(if (pin.length == 4) "Set PIN" else "Enter 4 digits", {
                        if (pin.length == 4) {
                            Profiles.setPin(me.id, pin)
                            pin = ""
                        }
                    }, icon = L.drawable.lucide_ic_lock)
                    if (me.pin != null) {
                        Spacer(Modifier.width(10.dp))
                        TvButton("Remove", { Profiles.setPin(me.id, "") })
                    }
                }
            }
        }

        }

        if (page == "Addons") Section(
            "Your addons",
            "Add an addon by its link — a public one, your debrid service's, or your own media server's. Watchly comes with none.",
        ) { AddonsList() }

        if (page == "Streaming") {
        Section(
            "Storage for one film",
            "How much of a film may sit on this TV at once. When it fills, what you've watched is let go to make room. Auto uses the free space, keeping a few GB spare.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CACHE_CAPS.forEach { mb -> Chip(cacheCapLabel(mb), settings.cacheCapMb == mb, onClick = { set(settings.copy(cacheCapMb = mb)) }) }
            }
        }

        Section("Buffer before playing", "Larger starts slower but is steadier on a weak swarm.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1, 4, 8, 16).forEach { mb -> Chip("$mb MB", settings.prepareMb == mb, onClick = { set(settings.copy(prepareMb = mb)) }) }
            }
        }

        Section("Extra trackers","Attach public trackers to bare magnet links so they resolve faster.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("On", settings.addTrackers, onClick = { set(settings.copy(addTrackers = true)) })
                Chip("Off", !settings.addTrackers, onClick = { set(settings.copy(addTrackers = false)) })
            }
        }
        }

        if (page == "Playback") {
        Section(
            "Player",
            "Media3 keeps 4K smooth with subtitles on, and hands a file it can't play to VLC by itself. VLC from the start plays the most formats and full ASS styling, but a weak TV can drop frames each time a subtitle changes.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Media3", !settings.vlcPlayer, onClick = { set(settings.copy(vlcPlayer = false)) })
                Chip("VLC", settings.vlcPlayer, onClick = { set(settings.copy(vlcPlayer = true)) })
            }
        }

        Section("Skip intro", "Where an episode's intro is known, jump past it by itself instead of showing the Skip intro button. Once per episode — rewind to watch it.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Automatically", settings.autoSkipIntro, onClick = { set(settings.copy(autoSkipIntro = true)) })
                Chip("Show the button", !settings.autoSkipIntro, onClick = { set(settings.copy(autoSkipIntro = false)) })
            }
        }
        }

        if (page == "Services") {
        Section(
            "SubDL key",
            if (subdl.isNotEmpty()) "Saved, encrypted on this TV — Find subtitles searches SubDL too. Enter another to replace it."
            else "A free key from subdl.com adds its subtitles to Find subtitles. It stays encrypted on this TV.",
        ) {
            Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(key, { key = it.trim() }, if (subdl.isNotEmpty()) "••••••••••••" else "Your SubDL API key", Modifier.width(420.dp), secret = true, keyboardOnFocus = false)
                Spacer(Modifier.width(14.dp))
                TvButton(if (key.isNotEmpty()) "Save key" else "Enter a key", {
                    if (key.isNotEmpty()) {
                        Secrets.setSubdl(key)
                        key = ""
                    }
                }, icon = L.drawable.lucide_ic_key)
                if (subdl.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    TvButton("Remove", { Secrets.setSubdl("") })
                }
            }
            PhoneKey("SubDL key") { Secrets.setSubdl(it); "Saved — your SubDL key is on the TV now. You can close this page." }
            }
        }

        Section(
            "TorBox",
            if (torbox.isNotEmpty()) "Releases TorBox has cached say Instant, and play from its servers — no peers to wait for, seek anywhere. Anything else streams from the swarm as before."
            else "With a torbox.app API key, releases TorBox has cached play from its servers instead of from peers. The key is under Settings on torbox.app, and stays encrypted on this TV.",
        ) {
            // A column: a section's box draws its children on top of each other.
            Column {
            if (torbox.isNotEmpty()) {
                Text(
                    torboxAccount ?: "Checking the key…",
                    Modifier.padding(bottom = 12.dp),
                    style = Type.meta.copy(color = if (torboxAccount?.startsWith("Not working") == true) Ink.danger else Ink.muted),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(torboxKey, { torboxKey = it.trim() }, if (torbox.isNotEmpty()) "••••••••••••" else "Your TorBox API key", Modifier.width(420.dp), secret = true, keyboardOnFocus = false)
                Spacer(Modifier.width(14.dp))
                TvButton(if (torboxKey.isNotEmpty()) "Save key" else "Enter a key", {
                    if (torboxKey.isNotEmpty()) {
                        TorBox.setKey(torboxKey)
                        torboxKey = ""
                    }
                }, icon = L.drawable.lucide_ic_key)
                if (torbox.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    TvButton("Remove", { TorBox.setKey("") })
                }
            }
            PhoneKey("TorBox key") { TorBox.setKey(it); "Saved — your TorBox key is on the TV now. You can close this page." }
            }
        }

        }

        if (page == "Appearance") Section("Genres on the home screen", "Leave all off to build the home screen from what you have watched.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 900.dp)) {
                GENRES.forEach { g ->
                    val on = g in settings.genres
                    Chip(g, on, icon = if (on) L.drawable.lucide_ic_check else null, onClick = {
                        set(settings.copy(genres = if (on) settings.genres - g else settings.genres + g))
                    })
                }
            }
        }
    }
    first.claim(page)
}

/** One line of the settings list; [words] are what else it answers to in the search. */
private class Entry(@androidx.annotation.DrawableRes val icon: Int, val title: String, val hint: String, val words: String = "", val tint: Color? = null, val onClick: () -> Unit) {
    fun matches(query: String) = listOf(title, hint, words).any { it.contains(query, ignoreCase = true) }
}

/** The phone's settings list for the remote: cards of rows, run together into one by a search. [first] is its first row. */
@Composable
private fun SettingsList(groups: List<List<Entry>>, query: String, first: FocusRequester) {
    val q = query.trim()
    val shown = if (q.isEmpty()) groups else listOf(groups.flatten().filter { it.matches(q) }).filter { it.isNotEmpty() }
    if (shown.isEmpty()) Text("No settings match", Modifier.padding(start = 4.dp, bottom = 12.dp), style = Type.meta.copy(color = Ink.faint))
    val top = shown.firstOrNull()?.firstOrNull()
    shown.forEach { group ->
        // Not clipped: that would cut off the focus ring.
        Column(Modifier.padding(bottom = 14.dp).widthIn(max = 760.dp).fillMaxWidth().background(Ink.surface, RoundedCornerShape(18.dp)).padding(6.dp)) {
            group.forEach { e ->
                Focusable(e.onClick, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), 1.02f, requester = first.takeIf { e === top }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Ink.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            Lc(e.icon, tint = e.tint ?: Ink.text, size = 20.dp)
                        }
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(e.title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.hint, style = Type.meta.copy(color = Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A tracking account: its logo, and Connect, which puts up a QR of its own page with the code filled in. */
@Composable
private fun TrackerSection(t: Tracker) {
    val connected by t.connected.collectAsState()
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // Back while the QR is up closes it right here, rather than leaving the page.
    androidx.activity.compose.BackHandler(code != null) { job?.cancel() }

    fun connect() {
        note = null
        job?.cancel()
        job = scope.launch {
            try {
                val c = t.start()
                code = c
                if (!t.await(c)) note = "That didn't go through — connect again for a new code."
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) note = e.message ?: "Couldn't reach ${t.name}."
            } finally {
                code = null
            }
        }
    }

    Column(Modifier.padding(bottom = 30.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // By its domain, from Google's favicon service, as the review sites are.
            Artwork(
                "https://www.google.com/s2/favicons?sz=64&domain=${t.site}", t.name,
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), scale = androidx.compose.ui.layout.ContentScale.Fit, fill = false,
            )
            Column(Modifier.width(360.dp).padding(horizontal = 16.dp)) {
                Text(t.name, style = Type.title.copy(fontWeight = FontWeight.Bold))
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
            // One button that changes its label: a focused button taken away left focus nowhere.
            if (t.available) TvButton(
                when { connected -> "Disconnect"; code != null -> "Cancel"; else -> "Connect" },
                { when { connected -> t.disconnect(); code != null -> job?.cancel(); else -> connect() } },
                icon = when { connected -> L.drawable.lucide_ic_link; code != null -> L.drawable.lucide_ic_x; else -> L.drawable.lucide_ic_smartphone },
            )
        }
        code?.let { c ->
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Qr(c.uriComplete, 168.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.widthIn(max = 560.dp)) {
                    Text("Scan with your phone's camera and approve, or enter this code on ${t.site}", style = Type.meta.copy(color = Ink.faint))
                    Text(c.userCode, Modifier.padding(vertical = 8.dp), style = Type.display)
                    Text("This page notices by itself once you've approved.", style = Type.meta.copy(color = Ink.faint))
                }
            }
        }
        note?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.faint)) }
    }
}

/**
 * The viewer's addons, each a row of buttons the D-pad can reach, then the ways to add one: pasted
 * from a phone (links run long, and often carry a key), or typed for a short one.
 */
@Composable
private fun AddonsList() {
    val addons by Addons.list.collectAsState()
    var link by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun add(raw: String): String = runCatching { Addons.add(raw) }.fold({ "Added ${it.name}." }, { it.message ?: "Couldn't add that addon." })
    Column {
        addons.forEachIndexed { i, a ->
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(420.dp)) {
                    Text(a.name, style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = if (a.on) Ink.text else Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // The host only: the rest of the link can be the viewer's own key.
                    Text("${a.host}   ·   ${a.offers}", style = Type.meta.copy(color = Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(14.dp))
                TvButton(if (a.on) "On" else "Off", { Addons.toggle(a.url) }, icon = if (a.on) L.drawable.lucide_ic_circle_check else L.drawable.lucide_ic_circle)
                Spacer(Modifier.width(10.dp))
                // Kept at the ends, where they do nothing, rather than taken away: focus never lands on nothing.
                TvButton("Up", { Addons.move(a.url, -1) }, icon = L.drawable.lucide_ic_chevron_up)
                Spacer(Modifier.width(10.dp))
                TvButton("Down", { Addons.move(a.url, 1) }, icon = L.drawable.lucide_ic_chevron_down)
                Spacer(Modifier.width(10.dp))
                TvButton("Remove", { Addons.remove(a.url) }, icon = L.drawable.lucide_ic_trash_2)
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(link, { link = it.trim() }, "https://…/manifest.json", Modifier.width(420.dp), keyboardOnFocus = false)
            Spacer(Modifier.width(14.dp))
            TvButton(if (link.isNotEmpty()) "Add addon" else "Type a link", {
                if (link.isNotEmpty()) scope.launch {
                    note = "Checking…"
                    note = add(link)
                    if (note!!.startsWith("Added")) link = ""
                }
            }, icon = L.drawable.lucide_ic_plus)
        }
        note?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.muted)) }
        // One QR for both: the phone app sends its whole list (JSON), or a single link is pasted.
        PhoneKey("addon link", several = true, label = "Copy addons from phone", fromApp = "Copy all addons from Watchly on this phone") { text ->
            (if (text.trimStart().startsWith("[")) runCatching { Addons.importLinks(text) }.getOrElse { "That list didn't read." } else add(text))
                .also { s -> note = s }
        }
    }
}

/** The Library's Stremio card was put off with "Not now"; Settings still offers it. */
private const val STREMIO_LATER = "stremio:later"

/**
 * A key or link pasted on a phone rather than typed with the remote: a QR of a page the TV serves on
 * the home network ([KeyDrop]), open only while this shows. [onKey]'s answer goes back to the phone;
 * it closes once one arrives, unless [several] (addons: paste one after another). [fromApp] is the
 * page's button that has the phone app send what it has instead.
 */
@Composable
private fun PhoneKey(what: String, several: Boolean = false, label: String = "Paste from phone", fromApp: String? = null, onKey: suspend (String) -> String) {
    var open by remember { mutableStateOf(false) }
    // One button that changes its label, not a button swapped for another: a focused button taken
    // away left focus nowhere, and the page sent it back to the top.
    TvButton(if (open) (if (several) "Done" else "Cancel") else label, { open = !open }, Modifier.padding(top = 12.dp), icon = if (open) L.drawable.lucide_ic_x else L.drawable.lucide_ic_smartphone)
    // Back closes the QR here, rather than leaving the page and losing the place on it.
    androidx.activity.compose.BackHandler(open) { open = false }
    if (!open) return
    var address by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val drop = KeyDrop(what, fromApp) {
            onKey(it).also { if (!several) open = false }
        }
        address = drop.open()
        tried = true
        onDispose { drop.close() }
    }
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        address?.let { Qr(it, 168.dp) }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.widthIn(max = 520.dp)) {
            Text(
                when {
                    address != null && fromApp != null -> "Scan with your phone's camera and tap \"$fromApp\" on the page that opens — or paste one $what there. Your phone needs to be on the same Wi-Fi as the TV."
                    address != null -> "Scan with your phone's camera, paste your $what on the page that opens, and tap Save. Your phone needs to be on the same Wi-Fi as the TV."
                    tried -> "The TV isn't on a network a phone can reach."
                    else -> "Opening…"
                },
                style = Type.meta.copy(color = Ink.muted),
            )
            address?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.faint)) }
        }
    }
}

/** [text] as a QR code: black squares on white, with the one-module quiet zone a camera needs. */
@Composable
private fun Qr(text: String, size: androidx.compose.ui.unit.Dp) {
    val matrix = remember(text) {
        com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    }
    androidx.compose.foundation.Canvas(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, androidx.compose.ui.geometry.Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell, cell))
        }
    }
}

/**
 * Sign in to Stremio by its link code: a QR of link.stremio.com with the code in it, where the viewer
 * signs in on their phone and approves; this page notices, then brings the account's addons. No
 * password, and no typing with the remote.
 * [onLater] makes it the Library's card, with a Not now; without it, a Settings section.
 * [requester] is its first button, where focus lands coming in from the rail.
 */
@Composable
fun StremioSection(me: Profile, onLater: (() -> Unit)? = null, requester: FocusRequester? = null) {
    val connected by Stremio.connected.collectAsState()
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // Back while the QR is up closes it right here, rather than leaving the page (which went back to its top).
    androidx.activity.compose.BackHandler(code != null) { job?.cancel() }
    val main = requester ?: remember { FocusRequester() }

    fun importAddons() {
        note = "Bringing your addons…"
        scope.launch {
            note = runCatching { Stremio.importAddons() }.fold(
                { if (it == 0) "Every addon is here already." else "Added $it addon${if (it == 1) "" else "s"}." },
                { it.message ?: "Couldn't reach Stremio." },
            )
        }
    }

    fun connect() {
        note = null
        job?.cancel()
        job = scope.launch {
            try {
                val c = Stremio.start()
                code = c
                if (!Stremio.await(c)) note = "That code ran out — sign in again for a new one."
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) note = e.message ?: "Couldn't reach Stremio."
            } finally {
                code = null
            }
        }
    }

    val email by Stremio.email.collectAsState()
    val hint = when {
        connected -> "Signed in${email?.let { " as $it" }.orEmpty()} — your Stremio library and where you stopped are here, My List is your library there, and what you finish is marked watched."
        me.isGuest -> "Your Stremio library, Continue Watching and addons on this TV. As Guest it's forgotten when the app closes."
        else -> "Your Stremio library, Continue Watching and addons on this TV, kept in step both ways. You sign in on your phone — no password on the TV."
    }
    val body: @Composable () -> Unit = {
        Column {
            val c = code
            if (c != null) Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // Scanned, it opens Stremio's page with the code in it; the address is for typing on any device.
                Qr(c.uriComplete, 168.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.widthIn(max = 560.dp)) {
                    Text("Scan with your phone's camera, sign in to Stremio and approve — or go to", style = Type.meta.copy(color = Ink.faint))
                    Text(c.uriComplete.removePrefix("https://"), Modifier.padding(vertical = 8.dp), style = Type.display)
                    Text("This page notices by itself once you've approved.", style = Type.meta.copy(color = Ink.faint))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One button that changes its label and action, not one swapped for another: a focused
                // button taken away left focus nowhere, and the rail opened.
                TvButton(
                    when { connected -> "Import addons"; c != null -> "Cancel"; else -> "Sign in to Stremio" },
                    { when { connected -> importAddons(); c != null -> job?.cancel(); else -> connect() } },
                    icon = when { connected -> L.drawable.lucide_ic_puzzle; c != null -> L.drawable.lucide_ic_x; else -> L.drawable.lucide_ic_link },
                    requester = main,
                )
                if (connected) {
                    Spacer(Modifier.width(10.dp))
                    // The other way: the account's list made this TV's, for every device that signs in after.
                    TvButton("Send my addons to Stremio", {
                        note = "Sending your addons…"
                        scope.launch {
                            note = runCatching { Stremio.pushAddons() }.fold(
                                { "Your Stremio account now has this TV's $it addon${if (it == 1) "" else "s"}." },
                                { it.message ?: "Couldn't reach Stremio." },
                            )
                        }
                    }, icon = L.drawable.lucide_ic_upload)
                    Spacer(Modifier.width(10.dp))
                    // Gone once pressed: focus goes back to the first button, not off to the rail.
                    TvButton("Sign out", { Stremio.disconnect(); main.requestFocus() }, icon = L.drawable.lucide_ic_link)
                }
                if (!connected && c == null) onLater?.let {
                    Spacer(Modifier.width(10.dp))
                    TvButton("Not now", it)
                }
            }
            note?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.faint)) }
        }
    }
    if (onLater == null) Section("Stremio account", hint, body)
    else Column(Modifier.fillMaxWidth().background(Ink.surface, RoundedCornerShape(14.dp)).padding(24.dp)) {
        Text("Bring your Stremio library", style = Type.title.copy(fontWeight = FontWeight.Bold))
        Text(hint, Modifier.padding(top = 4.dp, bottom = 16.dp).widthIn(max = 760.dp), style = Type.meta.copy(color = Ink.faint))
        body()
    }
}

@Composable
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 34.dp)) {
        Text(title, style = Type.title.copy(fontWeight = FontWeight.Bold))
        Text(hint, Modifier.padding(top = 4.dp, bottom = 14.dp).widthIn(max = 760.dp), style = Type.meta.copy(color = Ink.faint))
        // Right off a row's end found the section below; left still reaches the rail by the page's own key handler.
        Box(
            Modifier.focusProperties {
                onExit = { if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
            }.focusGroup(),
        ) { content() }
    }
}

/* ------------------------------------------------------------- profiles */

private sealed interface ProfilesMode {
    data object Pick : ProfilesMode
    data object Manage : ProfilesMode
    data class Edit(val profile: Profile?) : ProfilesMode
    data class Pin(val profile: Profile) : ProfilesMode
}

/** Who's watching — every launch, since the TV is the one screen a household shares. */
@Composable
fun ProfilesScreen(profiles: List<Profile>, onPick: (Profile) -> Unit, onCancel: (() -> Unit)?, onRemoved: (Profile) -> Unit) {
    var mode by remember { mutableStateOf<ProfilesMode>(if (profiles.isEmpty()) ProfilesMode.Edit(null) else ProfilesMode.Pick) }
    androidx.activity.compose.BackHandler(mode != ProfilesMode.Pick || onCancel != null) {
        when {
            mode != ProfilesMode.Pick && profiles.isNotEmpty() -> mode = ProfilesMode.Pick
            onCancel != null -> onCancel()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val m = mode) {
            ProfilesMode.Pick, ProfilesMode.Manage -> Picker(profiles, m == ProfilesMode.Manage, onCancel,
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
            is ProfilesMode.Edit -> Editor(m.profile, canCancel = profiles.isNotEmpty(),
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
                onCancel = { mode = ProfilesMode.Pick },
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
    val first = remember { FocusRequester() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (managing) "Manage profiles" else "Who's watching?", style = Type.hero.copy(fontSize = 44.sp))
        Spacer(Modifier.height(44.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            profiles.forEachIndexed { i, p -> Tile(p.name, { open(p) }, if (i == 0) first else null) { Avatar(p, 130.dp); if (managing || p.pin != null) Badge(if (managing) L.drawable.lucide_ic_pencil else L.drawable.lucide_ic_lock) } }
            if (!managing) Tile("Guest", onGuest, if (profiles.isEmpty()) first else null) { Avatar(GUEST, 130.dp) }
            if (!managing && profiles.size < 6) Tile("Add profile", onAdd) {
                Box(Modifier.size(130.dp).background(Ink.surface, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_plus, tint = Ink.muted, size = 48.dp) }
            }
        }
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton(if (managing) "Done" else "Manage profiles", onManage, icon = if (managing) L.drawable.lucide_ic_check else L.drawable.lucide_ic_pencil)
            if (onCancel != null && !managing) TvButton("Back", onCancel)
        }
    }
    first.claim(managing)
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Badge(icon: Int) {
    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Lc(icon, tint = Color.White, size = 16.dp)
    }
}

@Composable
private fun Tile(name: String, onClick: () -> Unit, requester: FocusRequester? = null, face: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(140.dp)) {
        Focusable(onClick, Modifier, RoundedCornerShape(16.dp), 1.1f, requester = requester, onFocus = { focused = true }) { f ->
            LaunchedEffect(f) { focused = f }
            Box { face() }
        }
        Text(name, Modifier.padding(top = 14.dp), style = Type.title.copy(color = if (focused) Ink.text else Ink.faint, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(profile: Profile?, canCancel: Boolean, onSave: (String, String, String, Boolean) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var icon by remember { mutableStateOf(profile?.let { it.icon ?: Avatars.of(it).id } ?: EMOJI_ICONS[0]) }
    var colour by remember { mutableStateOf(profile?.colour ?: COLOURS[0]) }
    var kids by remember { mutableStateOf(profile?.kids ?: false) }
    val field = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Safe.x, vertical = Safe.y)) {
        Avatar(Profile("preview", name.ifBlank { "?" }, colour, icon), 160.dp)
        Spacer(Modifier.width(48.dp))
        Column(Modifier.width(520.dp)) {
            // Taller than a TV screen since Kids: the form scrolls with focus, Save stays in sight below it.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(if (profile == null) "Add a profile" else "Edit profile", style = Type.display)
                Text(if (profile == null) "Its own library, My List and resume points." else "Change the name, the face or the colour.", Modifier.padding(top = 4.dp, bottom = 20.dp), style = Type.meta)
                Field(name, { name = it.take(20) }, "Name", Modifier.fillMaxWidth(), requester = field)
                Text("ICON", Modifier.padding(top = 22.dp, bottom = 10.dp), style = Type.overline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EMOJI_ICONS.forEach { a ->
                        Focusable({ icon = a }, Modifier.size(56.dp), RoundedCornerShape(12.dp), 1.12f) {
                            AvatarImage(a, colorOf(colour), 56.dp)
                            if (a == icon) Box(Modifier.fillMaxSize().border(3.dp, Ink.accent, RoundedCornerShape(12.dp)))
                        }
                    }
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(link, { link = it.trim() }, "Link to an image", Modifier.width(340.dp), keyboardOnFocus = false)
                    Spacer(Modifier.width(12.dp))
                    TvButton(if (link.isNotEmpty()) "Use link" else "Type a link", {
                        if (link.isNotEmpty()) scope.launch {
                            val saved = savedPicture(context, link)
                            failed = saved == null
                            if (saved != null) {
                                icon = saved
                                link = ""
                            }
                        }
                    }, icon = L.drawable.lucide_ic_link)
                }
                PhoneKey("image link") { pasted ->
                    savedPicture(context, pasted.trim())?.let {
                        icon = it
                        "Saved — that picture is on the TV now. You can close this page."
                    } ?: "Couldn't load that picture."
                }
                if (failed) Text("Couldn't load that picture.", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.danger))
                Text("COLOUR", Modifier.padding(top = 20.dp, bottom = 10.dp), style = Type.overline)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    COLOURS.forEach { c ->
                        Focusable({ colour = c }, Modifier.size(42.dp), CircleShape, 1.15f) {
                            Box(Modifier.fillMaxSize().background(colorOf(c)), contentAlignment = Alignment.Center) {
                                if (c == colour) Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 20.dp)
                            }
                        }
                    }
                }
                Text("KIDS", Modifier.padding(top = 20.dp, bottom = 10.dp), style = Type.overline)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("Kids profile", kids, onClick = { kids = true }, icon = if (kids) L.drawable.lucide_ic_check else null)
                    Chip("Everything", !kids, onClick = { kids = false })
                }
                Text(
                    if (kids) "Family films and series only. No addons, stream search or links from other apps — give your own profile a PIN so it stays that way."
                    else "Every title, addons and stream search included.",
                    Modifier.padding(top = 8.dp),
                    style = Type.meta.copy(color = Ink.faint),
                )
            }
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Save", { onSave(name, icon, colour, kids) }, icon = L.drawable.lucide_ic_check, primary = true)
                if (canCancel) TvButton("Cancel", onCancel)
                if (profile != null) TvButton("Delete profile", onDelete, icon = L.drawable.lucide_ic_trash_2)
            }
        }
    }
    field.claim()
}

@Composable
private fun PinPad(profile: Profile, onOk: () -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(profile, 110.dp)
        Text(profile.name, Modifier.padding(top = 16.dp), style = Type.display)
        Text(if (wrong) "That's not the PIN. Try again." else "Enter your PIN to open this profile.", Modifier.padding(top = 6.dp, bottom = 22.dp), style = Type.body.copy(color = if (wrong) Ink.danger else Ink.muted), textAlign = TextAlign.Center)
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
        }, "• • • •", Modifier.width(220.dp), requester = field, secret = true, digits = true)
        Spacer(Modifier.height(24.dp))
        TvButton("Back", onBack)
    }
    field.claim()
}

