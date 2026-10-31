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
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = if (focused) 0.16f else 0.09f)).border(2.dp, ring, RoundedCornerShape(8.dp)).padding(horizontal = 18.dp),
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
                    .onFocusChanged { focused = it.isFocused },
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
            hits = searchTorrents(q) { hits = it }
        } else {
            results = coroutineScope {
                val films = async { Meta.search(q, "movie") }
                val series = async { Meta.search(q, "tv") }
                Kids.only((films.await() + series.await()).distinctBy { it.imdb }.sortedBy { relevance(it.title, q) })
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
                        if (torrents) "Release or torrent name" else "Movies, shows, anime",
                        Modifier.weight(1f).widthIn(max = 640.dp),
                        requester = field,
                        leading = L.drawable.lucide_ic_search,
                        onSubmit = { recents = Searches.remember(query) },
                        imeAction = ImeAction.Search,
                        trailing = { if (busy) Spinner(size = 22.dp) },
                    )
                    if (!Kids.on) {
                        Spacer(Modifier.width(14.dp))
                        Chip("Torrents", torrents, onClick = { torrents = !torrents; results = null; hits = null }, icon = L.drawable.lucide_ic_layers)
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
    // After a reinstall this TV's history is empty; what Simkl has still belongs here.
    val simklLibrary by Simkl.library.collectAsState()
    val fromSimkl = remember(simklLibrary, history, Kids.on, Kids.allowed) { Kids.only(simklLibrary.notIn(history)) { Title(it.imdb, it.kind, it.title) } }
    var focusedUri by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    var releases by remember { mutableStateOf<List<Item>?>(null) }
    val simkl by Simkl.connected.collectAsState()
    /** Per profile; the guest is offered nothing it would lose on exit. */
    var simklLater by remember(me.id) { mutableStateOf(me.isGuest || Kv.get(SIMKL_LATER) != null) }

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
        if (Simkl.available && !simkl && !simklLater) item(span = { GridItemSpan(maxLineSpan) }) {
            SimklSection(me, onLater = {
                Kv.set(SIMKL_LATER, "1")
                simklLater = true
            })
        }
        if (simkl) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("✓ Simkl connected — finished films and episodes are marked watched", style = Type.meta.copy(color = Ink.faint))
        }
        if (items.isEmpty() && fromSimkl.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
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
        if (fromSimkl.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("From Simkl", Modifier.padding(top = 14.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        }
        // Never played on this TV: no release to resume, so the title opens; one picked there starts at Simkl's spot.
        items(fromSimkl, key = { "simkl:${it.imdb}" }) { s ->
            val paused = simklLibrary.paused.find { it.imdb == s.imdb }
            Focusable(
                onClick = { onOpen(Title(s.imdb, s.kind, s.title, year = s.year)) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shape = RoundedCornerShape(8.dp),
                scale = 1.08f,
                requester = if (items.isEmpty() && s == fromSimkl.first()) first else null,
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
                    Text("$state  ·  on Simkl", style = Type.meta.copy(fontSize = 11.sp), maxLines = 1)
                    paused?.let {
                        Spacer(Modifier.height(5.dp))
                        ProgressLine((it.progress / 100).toFloat())
                    }
                }
            }
        }
    }
    }
    if (items.isNotEmpty() || fromSimkl.isNotEmpty()) first.claim()
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

    Column(
        Modifier.fillMaxSize()
            // In from the rail: the PIN box is leftmost, so it won the focus search and opened the keyboard.
            .focusProperties { onEnter = { first.requestFocus() } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(start = GUTTER, end = Safe.x, top = Safe.y, bottom = 120.dp),
    ) {
        Text("Settings", style = Type.display)
        // Up here, not at the foot: the D-pad only scrolls as far as the last thing it can focus.
        Text(
            "Version ${BuildConfig.VERSION_NAME} · built " +
                java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date(BuildConfig.BUILD_TIME)),
            Modifier.padding(top = 4.dp, bottom = 26.dp),
            style = Type.meta.copy(color = Ink.faint),
        )

        Section("Profile", if (me.isGuest) "Guest keeps nothing — history and My List go when the app closes." else "Library, My List and resume points belong to this profile.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me, 44.dp)
                Spacer(Modifier.width(16.dp))
                TvButton("Watching as ${me.name} — switch", onSwitchProfile, icon = L.drawable.lucide_ic_users, requester = first)
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

        Section(
            "SubDL key",
            if (subdl.isNotEmpty()) "Saved, encrypted on this TV — Find subtitles searches SubDL too. Enter another to replace it."
            else "A free key from subdl.com adds its subtitles to Find subtitles. It stays encrypted on this TV.",
        ) {
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
        }

        Section(
            "TorBox",
            if (torbox.isNotEmpty()) "Releases TorBox has cached say Instant, and play from its servers — no peers to wait for, seek anywhere. Anything else streams from the swarm as before."
            else "With a torbox.app API key, releases TorBox has cached play from its servers instead of from peers. The key is under Settings on torbox.app, and stays encrypted on this TV.",
        ) {
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
        }

        if (Simkl.available) SimklSection(me)

        Section("Extra trackers","Attach public trackers to bare magnet links so they resolve faster.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("On", settings.addTrackers, onClick = { set(settings.copy(addTrackers = true)) })
                Chip("Off", !settings.addTrackers, onClick = { set(settings.copy(addTrackers = false)) })
            }
        }

        Section("Genres on the home screen", "Leave all off to build the home screen from what you have watched.") {
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
    first.claim()
}

/** The Library's Simkl card was put off with "Not now"; Settings still offers it. */
private const val SIMKL_LATER = "simkl:later"

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
 * Connect by device code: the code shows here, the viewer approves on their phone, this page notices.
 * [onLater] makes it the Library's card, with a Not now; without it, a Settings section.
 */
@Composable
fun SimklSection(me: Profile, onLater: (() -> Unit)? = null) {
    val connected by Simkl.connected.collectAsState()
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        note = null
        job?.cancel()
        job = scope.launch {
            try {
                val c = Simkl.start()
                code = c
                if (!Simkl.await(c)) note = "That code ran out — connect again for a new one."
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) note = e.message ?: "Couldn't reach Simkl."
            } finally {
                code = null
            }
        }
    }

    val hint = when {
        connected -> "Connected — films and episodes you watch to the end are marked watched on Simkl."
        me.isGuest -> "Marks what you finish as watched on your Simkl account. As Guest it's forgotten when the app closes."
        else -> "Films and episodes you finish get marked watched on your Simkl account, and Continue Watching follows you to other devices."
    }
    val body: @Composable () -> Unit = {
        Column {
            val c = code
            if (c != null) Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // Scanned, it opens Simkl with the code filled in; the code itself is for typing on any device.
                Qr(c.uriComplete, 168.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.widthIn(max = 560.dp)) {
                    Text("Scan with your phone's camera — or go to ${c.uri.removePrefix("https://")} and enter", style = Type.meta.copy(color = Ink.faint))
                    Text(c.userCode, Modifier.padding(vertical = 8.dp), style = Type.display)
                    Text("Signing in? Simkl emails that account a link — open it and confirm, then press Allow.", style = Type.meta.copy(color = Ink.faint))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    connected -> TvButton("Disconnect", { Simkl.disconnect() }, icon = L.drawable.lucide_ic_link)
                    c != null -> TvButton("Cancel", { job?.cancel() })
                    else -> {
                        TvButton("Connect Simkl", ::connect, icon = L.drawable.lucide_ic_link)
                        onLater?.let {
                            Spacer(Modifier.width(10.dp))
                            TvButton("Not now", it)
                        }
                    }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.faint)) }
        }
    }
    if (onLater == null) Section("Simkl", hint, body)
    else Column(Modifier.fillMaxWidth().background(Ink.surface, RoundedCornerShape(14.dp)).padding(24.dp)) {
        Text("Sync your history with Simkl", style = Type.title.copy(fontWeight = FontWeight.Bold))
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
    var icon by remember { mutableStateOf(profile?.let { Avatars.of(it).id } ?: AVATARS[0]) }
    var colour by remember { mutableStateOf(profile?.colour ?: COLOURS[0]) }
    var kids by remember { mutableStateOf(profile?.kids ?: false) }
    val field = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Safe.x)) {
        Avatar(Profile("preview", name.ifBlank { "?" }, colour, icon), 160.dp)
        Spacer(Modifier.width(48.dp))
        Column(Modifier.width(520.dp)) {
            Text(if (profile == null) "Add a profile" else "Edit profile", style = Type.display)
            Text(if (profile == null) "Its own library, My List and resume points." else "Change the name, the face or the colour.", Modifier.padding(top = 4.dp, bottom = 20.dp), style = Type.meta)
            Field(name, { name = it.take(20) }, "Name", Modifier.fillMaxWidth(), requester = field)
            Text("ICON", Modifier.padding(top = 22.dp, bottom = 10.dp), style = Type.overline)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AVATARS.forEach { a ->
                    Focusable({ icon = a }, Modifier.size(56.dp), RoundedCornerShape(12.dp), 1.12f) {
                        AvatarTile(Avatars.art(a), colorOf(colour), 56.dp)
                        if (a == icon) Box(Modifier.fillMaxSize().border(3.dp, Ink.accent, RoundedCornerShape(12.dp)))
                    }
                }
            }
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
                if (kids) "Family films and series only. No torrent search or links from other apps — give your own profile a PIN so it stays that way."
                else "Every title, torrent search included.",
                Modifier.padding(top = 8.dp),
                style = Type.meta.copy(color = Ink.faint),
            )
            Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

