package com.torrentviewer.tv

import com.torrentviewer.core.*
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

enum class Tab(val label: String, @DrawableRes val icon: Int) {
    SEARCH("Search", L.drawable.lucide_ic_search),
    HOME("Home", L.drawable.lucide_ic_house),
    LIBRARY("Library", L.drawable.lucide_ic_history),
    SETTINGS("Settings", L.drawable.lucide_ic_settings),
}

/** This profile has been offered Simkl once, on its first open. */
private const val SIMKL_ASKED = "simkl:asked"

@Composable
fun App(watch: Watch, incoming: MutableState<Uri?>) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.surface)) {
        // The one full-screen background. Pages draw none of their own and the window none
        // at all: every extra full-screen fill was another pass for a TV GPU already behind.
        Box(Modifier.fillMaxSize().background(Ink.bg)) {
            Shell(watch, incoming)
            Toast(watch)
        }
    }
}

@Composable
private fun Shell(watch: Watch, incoming: MutableState<Uri?>) {
    val profiles by Profiles.list.collectAsState()
    var who by remember { mutableStateOf<Profile?>(null) }
    // An edit to the profile in use — its name, face or colour — shows at once.
    LaunchedEffect(profiles) { who = who?.let { w -> profiles.find { it.id == w.id } ?: w } }
    var switching by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var detail by remember { mutableStateOf<Title?>(null) }
    // A detail page keeps its season and episode while the player is up, and when gone back to.
    val pages = rememberSaveableStateHolder()
    val home = remember { HomeState() }

    fun enter(profile: Profile) {
        // Before anything is read: every store call below goes through this profile.
        Kv.useProfile(profile.id, profile.isGuest)
        History.load()
        SavedStore.load()
        Simkl.load(profile.id, profile.isGuest)
        who = profile
        switching = false
    }

    // Engine failures would otherwise be invisible: the player just goes away.
    LaunchedEffect(Unit) { watch.engine.errors.collect { watch.fail(it) } }

    // Another app handed us a torrent.
    LaunchedEffect(incoming.value, who) {
        val uri = incoming.value ?: return@LaunchedEffect
        if (who == null) return@LaunchedEffect
        incoming.value = null
        detail = null
        val raw = uri.toString()
        if (!isTorrentSource(raw)) return@LaunchedEffect
        val path = runCatching { withContext(Dispatchers.IO) { watch.engine.importSource(raw) } }
            .getOrElse { return@LaunchedEffect watch.fail(it.message ?: "Couldn't read that file.") }
        val item = History.put(Item(path, titleOf(raw), System.currentTimeMillis()))
        watch.resume(item)
    }

    // Who is watching, decided before anything else — even with one profile, since
    // whoever picked up the remote is not necessarily whoever put it down.
    if (who == null || switching) {
        ProfilesScreen(
            profiles = profiles,
            onPick = ::enter,
            onCancel = if (who != null) ({ switching = false }) else null,
            onRemoved = { if (who?.id == it.id) who = null },
        )
        return
    }

    if (watch.phase == Phase.PLAYING) {
        PlayerScreen(watch)
        return
    }

    BackHandler(detail != null) { detail = null }
    BackHandler(detail == null && tab != Tab.HOME) { tab = Tab.HOME }

    val rail = remember { FocusRequester() }
    /** The page as one focus group that remembers its last card — where Right from the rail goes. */
    val page = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Box(Modifier.fillMaxSize()) {
        // One page at a time, faded in. A cross-fade kept the old page composed for the
        // length of the fade, and its focus claims fought the new page's.
        val pageKey = detail?.imdb ?: tab.name
        key(pageKey) {
            val alpha = remember { Animatable(0f) }
            LaunchedEffect(Unit) { alpha.animateTo(1f, tween(220)) }
            Box(
                Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }
                    .focusRequester(page)
                    .focusRestorer()
                    .focusGroup()
                    // Left off the edge of a page always reaches the rail, whatever lies between.
                    .onPreviewKeyEvent { e ->
                        if (detail != null || e.type != KeyEventType.KeyDown || e.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                        if (!focusManager.moveFocus(FocusDirection.Left)) runCatching { rail.requestFocus() }
                        true
                    },
            ) {
                val open = detail
                if (open != null) {
                    pages.SaveableStateProvider(open.imdb) { DetailScreen(open, watch, onOpen = { detail = it }) }
                } else {
                    when (tab) {
                        Tab.HOME -> HomeScreen(home, watch, onOpen = { detail = it })
                        Tab.SEARCH -> SearchScreen(watch, onOpen = { detail = it })
                        Tab.LIBRARY -> LibraryScreen(watch, who!!, onOpen = { detail = it })
                        Tab.SETTINGS -> SettingsScreen(who!!, onSwitchProfile = { switching = true })
                    }
                }
            }
        }
        if (detail == null) NavRail(tab, who!!, rail, page, onTab = { tab = it }, onProfile = { switching = true })

        if (watch.phase == Phase.INSPECTING) Opening(watch)

        // Once per profile, the first time it opens: Simkl is otherwise easy never to find.
        who?.let { me ->
            var askSimkl by remember(me.id) { mutableStateOf(Simkl.available && !me.isGuest && Kv.get(SIMKL_ASKED) == null) }
            val simkl by Simkl.connected.collectAsState()
            if (askSimkl && !simkl) {
                LaunchedEffect(me.id) { Kv.set(SIMKL_ASKED, "1") }
                androidx.compose.ui.window.Dialog(onDismissRequest = { askSimkl = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    Box(Modifier.width(820.dp)) { SimklSection(me, onLater = { askSimkl = false }) }
                }
            }
        }

        watch.choose?.let { (item, meta) ->
            Sheet(
                title = "Which file?",
                options = meta.files.filter { it.isVideo }.map { Option(it.index, it.name, bytes(it.size)) },
                selected = null,
                empty = "This torrent holds no video.",
                onPick = { watch.pick(item, meta, it) },
                onDismiss = { watch.choose = null },
            )
        }
    }
}

/**
 * The navigation rail. Floats over the page so a backdrop runs the full width, and
 * opens with labels only while the remote is in it.
 */
@Composable
private fun NavRail(tab: Tab, who: Profile, current: FocusRequester, page: FocusRequester, onTab: (Tab) -> Unit, onProfile: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    /** Snapping shut to hand focus to the page: the open rail lies over the page's
     *  first column, so focus search from it found nothing to the right. */
    var leaving by remember { mutableStateOf(false) }
    val open = focused && !leaving
    val animated by animateDpAsState(if (open) 230.dp else NAV_W, Motion.spatial(), label = "rail")
    val width = if (leaving) NAV_W else animated
    val scope = rememberCoroutineScope()
    fun leave() {
        leaving = true
        scope.launch {
            // Two frames: one to lay the collapsed rail out, one for focus to see it.
            withFrameNanos { }
            withFrameNanos { }
            // Straight back to the card that was left, not a geometric guess from the rail.
            runCatching { page.requestFocus() }
            leaving = false
        }
    }
    Box(
        Modifier.fillMaxHeight().width(if (open) 640.dp else NAV_W + 24.dp).background(
            if (open) Brush.horizontalGradient(0f to Ink.bg, 0.45f to Ink.bg.copy(alpha = 0.9f), 1f to Color.Transparent)
            else Brush.horizontalGradient(0f to Ink.bg.copy(alpha = 0.85f), 1f to Color.Transparent),
        ),
    ) {
        Column(
            Modifier.width(width).fillMaxHeight().padding(start = 14.dp, top = Safe.y, bottom = Safe.y)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                        leave()
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { focused = it.hasFocus }
                .focusRestorer(current)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Focusable(onProfile, Modifier.padding(bottom = 26.dp), RoundedCornerShape(10.dp), 1.08f) { focused ->
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(who, 36.dp)
                    if (open) {
                        Spacer(Modifier.width(14.dp))
                        Text(who.name, style = Type.label.copy(color = if (focused) Ink.text else Ink.muted, fontWeight = FontWeight.SemiBold), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Tab.entries.forEach { t ->
                val on = t == tab
                Focusable(
                    // Choosing a page goes into it, and the rail closes behind.
                    onClick = {
                        onTab(t)
                        leave()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    scale = 1f,
                    ring = false,
                    requester = if (on) current else null,
                ) { focused ->
                    val tint = when {
                        focused -> Color.Black
                        on -> Ink.text
                        else -> Ink.faint
                    }
                    Row(
                        Modifier.background(if (focused) Color.White else Color.Transparent).height(44.dp).fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Lc(t.icon, tint = tint, size = 21.dp)
                        if (open) {
                            Spacer(Modifier.width(16.dp))
                            Text(t.label, style = Type.label.copy(color = tint, fontWeight = if (on || focused) FontWeight.Bold else FontWeight.Medium))
                        }
                    }
                    // The red tick on the page you are on.
                    if (on && !focused) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(Ink.accent))
                }
            }
            Spacer(Modifier.weight(1.4f))
        }
    }
}

/** Resolving a torrent is a DHT round trip — seconds. Say so, over the title's own art. */
@Composable
private fun Opening(watch: Watch) {
    BackHandler { watch.cancel() }
    val title = watch.title
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        title?.art?.let {
            Artwork(it, title.title, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) TitleMark(title, Modifier.widthIn(max = 460.dp).padding(bottom = 28.dp), maxHeight = 96.dp)
            Spinner()
            Text("Opening…", Modifier.padding(top = 18.dp), style = Type.title)
            Text("Finding peers and reading the file list", Modifier.padding(top = 4.dp), style = Type.meta)
        }
    }
}

@Composable
private fun Toast(watch: Watch) {
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        watch.notes.collect {
            note = it
            launch {
                delay(7000)
                if (note == it) note = null
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = Safe.y, end = Safe.x), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(note != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Row(
                Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(10.dp)).background(Ink.surface2).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Lc(L.drawable.lucide_ic_circle_alert, tint = Ink.accent, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(note.orEmpty(), style = Type.label)
            }
        }
    }
}

data class Option<T>(val value: T, val label: String, val hint: String? = null)

/**
 * A choice, down the right edge. Cycling in place would give the viewer no way to
 * see what the options were.
 */
@Composable
fun <T> Sheet(
    title: String,
    options: List<Option<T>>,
    selected: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    empty: String? = null,
    busy: Boolean = false,
) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(shown, enter = slideInHorizontally(Motion.spatial()) { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            Column(
                Modifier.fillMaxHeight().width(440.dp)
                    .background(Brush.horizontalGradient(listOf(Ink.surface.copy(alpha = 0.96f), Color(0xF2101010))))
                    .padding(start = 28.dp, end = Safe.x, top = Safe.y + 8.dp, bottom = Safe.y),
            ) {
                Text(title, style = Type.display.copy(fontSize = 24.sp), modifier = Modifier.padding(bottom = 18.dp))
                when {
                    busy && options.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { Spinner(size = 44.dp) }
                    options.isEmpty() -> Note(empty ?: "Nothing to choose from.")
                    else -> {
                        val start = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.focusRestorer(first)) {
                            itemsIndexed(options) { i, option ->
                                val on = option.value == selected
                                Focusable(
                                    onClick = { onPick(option.value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    scale = 1.02f,
                                    ring = false,
                                    requester = if (i == start) first else null,
                                ) { focused ->
                                    Row(
                                        Modifier.background(if (focused) Color.White else Color.Transparent).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(22.dp)) {
                                            if (on) Lc(L.drawable.lucide_ic_check, tint = if (focused) Color.Black else Ink.text, size = 20.dp)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                option.label,
                                                style = Type.label.copy(color = if (focused) Color.Black else if (on) Ink.text else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            option.hint?.let { Text(it, style = Type.meta.copy(color = if (focused) Color.Black.copy(alpha = 0.6f) else Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        }
                                    }
                                }
                            }
                            if (busy) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(size = 28.dp) } }
                        }
                        first.claim()
                    }
                }
            }
        }
    }
}

