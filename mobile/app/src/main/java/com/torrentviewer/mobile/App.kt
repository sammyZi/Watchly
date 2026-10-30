package com.torrentviewer.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import com.torrentviewer.core.SettingsStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.COLOURS
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.grain
import com.torrentviewer.core.Item
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Phase
import com.torrentviewer.core.Profile
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.Simkl
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes
import com.torrentviewer.core.isTorrentSource
import com.torrentviewer.core.titleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

enum class Tab(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", L.drawable.lucide_ic_house),
    SEARCH("Search", L.drawable.lucide_ic_search),
    LIBRARY("Library", L.drawable.lucide_ic_library),
    DOWNLOADS("Downloads", L.drawable.lucide_ic_download),
    SETTINGS("Settings", L.drawable.lucide_ic_settings),
}

/** What is stacked over the tabs: a title, or a whole shelf. */
sealed interface Page {
    data class Detail(val title: Title) : Page
    data class Catalog(val shelf: Shelf) : Page
}

private fun keyOf(index: Int, page: Page) = when (page) {
    is Page.Detail -> "detail:$index:${page.title.imdb}"
    is Page.Catalog -> "shelf:$index:${page.shelf.key}"
}

/** The profile opened last on this phone, so a phone with one owner never asks. */
private const val LAST_PROFILE = "phone:lastProfile"

/** This profile has been offered Simkl once, on its first open. */
private const val SIMKL_ASKED = "simkl:asked"

/** Before profiles there were the bare keys; with none made, the phone keeps using them. */
private val OWNER = Profile("", "Me", COLOURS[0])

private fun open(profile: Profile) {
    // Before anything is read: every store call below goes through this profile.
    Kv.useProfile(profile.id, profile.isGuest)
    History.load()
    SavedStore.load()
    Simkl.load(profile.id, profile.isGuest)
    if (!profile.isGuest) Kv.setGlobal(LAST_PROFILE, profile.id)
}

/** Who opens without asking: the owner when there are no profiles, else the last one unless it is locked. */
private fun startProfile(list: List<Profile>): Profile? {
    if (list.isEmpty()) return OWNER
    val last = Kv.global(LAST_PROFILE)
    return (list.find { it.id == last } ?: list.singleOrNull())?.takeIf { it.pin == null }
}

fun darkFor(mode: String, systemDark: Boolean) = when (mode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

/** The page's palette, and status and navigation bar icons that read on it. */
fun ComponentActivity.applyTheme(dark: Boolean) {
    Ink.use(dark)
    val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    enableEdgeToEdge(bars, bars)
}

@Composable
fun App(watch: Watch, incoming: MutableState<Uri?>) {
    val settings by SettingsStore.value.collectAsState()
    val dark = darkFor(settings.themeMode, isSystemInDarkTheme())
    val activity = LocalActivity.current as? ComponentActivity
    // Live, for the setting and the system's own switch; the player keeps its dark while it is up.
    LaunchedEffect(dark) { if (watch.phase != Phase.PLAYING) activity?.applyTheme(dark) }
    val scheme = if (dark) darkColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.surface) else lightColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.surface)
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(Ink.bg).grain()) {
            Shell(watch, incoming)
            Toast(watch)
        }
    }
}

@Composable
private fun Shell(watch: Watch, incoming: MutableState<Uri?>) {
    val profiles by Profiles.list.collectAsState()
    var who by remember { mutableStateOf(startProfile(Profiles.list.value)?.also(::open)) }
    // An edit to the profile in use — its name, face or colour — shows at once.
    LaunchedEffect(profiles) { who = who?.let { w -> profiles.find { it.id == w.id } ?: w } }
    var switching by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val pages = remember { mutableStateListOf<Page>() }
    val holder = rememberSaveableStateHolder()
    val home = remember { HomeState() }

    fun pop() {
        val i = pages.lastIndex
        if (i < 0) return
        holder.removeState(keyOf(i, pages[i]))
        pages.removeAt(i)
    }

    fun clearPages() {
        while (pages.isNotEmpty()) pop()
    }

    fun enter(profile: Profile) {
        open(profile)
        who = profile
        switching = false
        clearPages()
        tab = Tab.HOME
    }

    // A download's progress lives in a notification; Android 13+ asks before showing one.
    val context = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val downloading by Downloads.live.collectAsState()
    LaunchedEffect(downloading.isNotEmpty()) {
        if (downloading.isNotEmpty() && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Engine failures would otherwise be invisible: the player just goes away.
    LaunchedEffect(Unit) { watch.engine.errors.collect { watch.fail(it) } }

    // Another app handed us a torrent.
    LaunchedEffect(incoming.value, who) {
        val uri = incoming.value ?: return@LaunchedEffect
        if (who == null) return@LaunchedEffect
        incoming.value = null
        val raw = uri.toString()
        if (!isTorrentSource(raw)) return@LaunchedEffect
        val path = runCatching { withContext(Dispatchers.IO) { watch.engine.importSource(raw) } }
            .getOrElse { return@LaunchedEffect watch.fail(it.message ?: "Couldn't read that file.") }
        val item = History.put(Item(path, titleOf(raw), System.currentTimeMillis()))
        watch.resume(item)
    }

    val me = who
    if (me == null || switching) {
        ProfilesScreen(
            profiles = profiles,
            onPick = ::enter,
            onCancel = if (me != null) ({ switching = false }) else null,
            onRemoved = { gone ->
                if (who?.id == gone.id) who = startProfile(Profiles.list.value)?.also(::open)
            },
        )
        return
    }

    if (watch.phase == Phase.PLAYING) {
        PlayerScreen(watch)
        return
    }

    BackHandler(pages.isNotEmpty()) { pop() }
    BackHandler(pages.isEmpty() && tab != Tab.HOME) { tab = Tab.HOME }

    val openTitle: (Title) -> Unit = { pages.add(Page.Detail(it)) }
    val body: @Composable () -> Unit = {
        val top = pages.lastOrNull()
        if (top != null) {
            holder.SaveableStateProvider(keyOf(pages.lastIndex, top)) {
                when (top) {
                    is Page.Detail -> DetailScreen(top.title, watch, onBack = ::pop)
                    is Page.Catalog -> CatalogScreen(top.shelf, onOpen = openTitle, onBack = ::pop)
                }
            }
        } else {
            // Tabs slide in from the side they sit on: Settings from the right of Home, Home from the left of it.
            AnimatedContent(
                tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(280)) { it * dir } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(280)) { -it * dir / 3 } + fadeOut(tween(200)))
                },
                label = "tabs",
            ) { t ->
                holder.SaveableStateProvider(t.name) {
                    when (t) {
                        Tab.HOME -> HomeScreen(home, watch, me, onOpen = openTitle, onShelf = { pages.add(Page.Catalog(it)) }, onProfile = { switching = true })
                        Tab.SEARCH -> SearchScreen(watch, onOpen = openTitle)
                        Tab.LIBRARY -> LibraryScreen(watch, me, onOpen = openTitle)
                        Tab.DOWNLOADS -> DownloadsScreen(watch)
                        Tab.SETTINGS -> SettingsScreen(watch, me, onSwitchProfile = { switching = true })
                    }
                }
            }
        }
    }
    val choose: (Tab) -> Unit = { t ->
        clearPages()
        tab = t
    }

    Box(Modifier.fillMaxSize()) {
        if (isWide()) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))) {
                NavRail(tab, me, choose, onProfile = { switching = true })
                Box(Modifier.weight(1f).fillMaxHeight()) { body() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { body() }
                TabBar(tab, choose)
            }
        }

        if (watch.phase == Phase.INSPECTING) Opening(watch)

        // Once per profile, the first time it opens: Simkl is otherwise easy never to find.
        var askSimkl by remember(me.id) { mutableStateOf(Simkl.available && !me.isGuest && Kv.get(SIMKL_ASKED) == null) }
        val simkl by Simkl.connected.collectAsState()
        if (askSimkl && !simkl) {
            LaunchedEffect(me.id) { Kv.set(SIMKL_ASKED, "1") }
            androidx.compose.ui.window.Dialog(onDismissRequest = { askSimkl = false }) {
                Box(Modifier.background(Ink.bg, RoundedCornerShape(16.dp))) { SimklSection(me, onLater = { askSimkl = false }) }
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

/** Portrait: the tabs along the bottom, the one you are on white with the TV rail's red tick. */
@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Ink.bg).grain().navigationBarsPadding().height(58.dp)) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Tap({ onTab(t) }, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) {
                Box {
                    if (on) Box(Modifier.align(Alignment.TopCenter).width(22.dp).height(3.dp).clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)).background(Ink.accent))
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Lc(t.icon, tint = if (on) Ink.text else Ink.faint, size = 22.dp)
                        Text(t.label, Modifier.padding(top = 3.dp), style = Type.meta.copy(fontSize = 11.sp, color = if (on) Ink.text else Ink.faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
                    }
                }
            }
        }
    }
}

/** Landscape: the TV's collapsed rail down the left — avatar on top, the tabs in the middle. */
@Composable
private fun NavRail(tab: Tab, who: Profile, onTab: (Tab) -> Unit, onProfile: () -> Unit) {
    Column(
        Modifier.width(76.dp).fillMaxHeight().background(Ink.bg).grain().statusBarsPadding().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tap(onProfile, shape = RoundedCornerShape(8.dp)) { Avatar(who, 34.dp) }
        Spacer(Modifier.weight(1f))
        Tab.entries.forEach { t ->
            val on = t == tab
            Tap({ onTab(t) }, Modifier.fillMaxWidth().height(60.dp), RoundedCornerShape(0.dp)) {
                Box {
                    if (on) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Ink.accent))
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Lc(t.icon, tint = if (on) Ink.text else Ink.faint, size = 22.dp)
                        Text(t.label, Modifier.padding(top = 3.dp), style = Type.meta.copy(fontSize = 11.sp, color = if (on) Ink.text else Ink.faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1.4f))
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
        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) TitleMark(title, Modifier.widthIn(max = 320.dp).padding(bottom = 28.dp), maxHeight = 80.dp)
            Spinner()
            // White on the blacked-out art, whatever the page's palette.
            Text("Opening…", Modifier.padding(top = 18.dp), style = Type.title.copy(color = Color.White))
            Text("Finding peers and reading the file list", Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f)), textAlign = TextAlign.Center)
        }
        BackButton { watch.cancel() }
    }
}

@Composable
private fun Toast(watch: Watch) {
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        watch.notes.collect {
            note = it
            launch {
                delay(6000)
                if (note == it) note = null
            }
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp, start = EDGE, end = EDGE), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(note != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Row(
                Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(10.dp)).background(Ink.surface2).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Lc(L.drawable.lucide_ic_circle_alert, tint = Ink.accent, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(note.orEmpty(), style = Type.label)
            }
        }
    }
}
