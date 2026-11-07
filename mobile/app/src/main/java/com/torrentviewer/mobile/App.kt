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
import com.torrentviewer.core.Stremio
import com.torrentviewer.core.Kids
import com.torrentviewer.core.Addons
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Net
import com.torrentviewer.core.addonHost
import com.torrentviewer.core.tvDropUrl
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
    ADDONS("Addons", L.drawable.lucide_ic_puzzle),
    LIBRARY("Library", L.drawable.lucide_ic_library),
    SETTINGS("Settings", L.drawable.lucide_ic_settings),
}

/** A kids profile has no say in where streams come from. */
private fun tabs() = Tab.entries.filter { it != Tab.ADDONS || !Kids.on }

/** What is stacked over the tabs: a title, a whole shelf, or the downloads (reached from Library). */
sealed interface Page {
    data class Detail(val title: Title) : Page
    data class Catalog(val shelf: Shelf) : Page
    data object Downloads : Page
    data object WatchOrders : Page
    data class WatchOrder(val order: com.torrentviewer.core.WatchOrder) : Page
    data class Person(val id: Int, val name: String) : Page
    data class Top(val kind: String) : Page
}

private fun keyOf(index: Int, page: Page) = when (page) {
    is Page.Detail -> "detail:$index:${page.title.imdb}"
    is Page.Catalog -> "shelf:$index:${page.shelf.key}"
    Page.Downloads -> "downloads:$index"
    Page.WatchOrders -> "orders:$index"
    is Page.WatchOrder -> "order:$index:${page.order.id}"
    is Page.Person -> "person:$index:${page.id}"
    is Page.Top -> "top:$index"
}

/** The profile opened last on this phone, so a phone with one owner never asks. */
private const val LAST_PROFILE = "phone:lastProfile"

/** The first-launch tour has been seen (or skipped). */
private const val TOURED = "phone:toured"
/** The date of the legal text last accepted. */
private const val AGREED = "phone:agreed"

/** Before profiles there were the bare keys; with none made, the phone keeps using them. */
private val OWNER = Profile("", "Me", COLOURS[0])

private fun open(profile: Profile) {
    // Before anything is read: every store call below goes through this profile.
    Kv.useProfile(profile.id, profile.isGuest)
    com.torrentviewer.core.SettingsStore.load()
    History.load()
    SavedStore.load()
    Stremio.load(profile.id, profile.isGuest)
    com.torrentviewer.core.WATCH_TRACKERS.forEach { it.load(profile.id, profile.isGuest) }
    Kids.use(profile)
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

/** What the download notification opens. */
const val DOWNLOADS_LINK = "watchly://downloads"

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
    LaunchedEffect(profiles) {
        who = who?.let { w -> profiles.find { it.id == w.id } ?: w }
        // Kids switched on or off for the profile in use: at once, not at the next switch.
        who?.let(Kids::use)
    }
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
        // Rows loaded for the last profile: a kids profile must not inherit a grown-up's, nor the reverse.
        if (who?.kids != profile.kids) home.loaded.clear()
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
    // An addon's YouTube or outside link: whichever app takes it.
    LaunchedEffect(Unit) {
        watch.opens.collect { link ->
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(link))) }
                .onFailure { watch.notes.tryEmit("No app here can open that link") }
        }
    }

    /** The TV page asking for this phone's addons, from its QR, until the viewer says yes or no. */
    var sendTo by remember { mutableStateOf<String?>(null) }
    /** A stremio:// link that wants to add an addon, until the viewer says yes or no. */
    var pendingAddon by remember { mutableStateOf<String?>(null) }

    // Another app handed us a torrent, or an addon page's Install button a stremio:// link,
    // or the TV's QR page its watchly:// link asking for the addons.
    LaunchedEffect(incoming.value, who) {
        val uri = incoming.value ?: return@LaunchedEffect
        if (who == null) return@LaunchedEffect
        incoming.value = null
        if (uri.toString() == DOWNLOADS_LINK) {
            clearPages()
            tab = Tab.LIBRARY
            pages.add(Page.Downloads)
            return@LaunchedEffect
        }
        // A magnet is anything at all: a kids profile plays only what it can browse to.
        if (Kids.on) return@LaunchedEffect watch.fail("A kids profile can't open torrents or addons from other apps.")
        val raw = uri.toString()
        if (raw.startsWith("stremio://", ignoreCase = true)) {
            // Asked first, below: any web page can send one of these links.
            pendingAddon = raw
            return@LaunchedEffect
        }
        if (raw.startsWith("watchly://tv-addons", ignoreCase = true)) {
            sendTo = tvDropUrl(raw) ?: return@LaunchedEffect watch.fail("That isn't a Watchly TV on your home network.")
            return@LaunchedEffect
        }
        if (!isTorrentSource(raw)) return@LaunchedEffect
        val path = runCatching { withContext(Dispatchers.IO) { watch.engine.importSource(raw) } }
            .getOrElse { return@LaunchedEffect watch.fail(it.message ?: "Couldn't read that file.") }
        val item = History.put(Item(path, titleOf(raw), System.currentTimeMillis()))
        watch.resume(item)
    }

    // Before anything else, for everyone, whatever they already have here, and again whenever the
    // words change: what was accepted is kept by its date, so a new date in Legal asks again.
    var accepted by remember { mutableStateOf(Kv.global(AGREED)) }
    if (accepted != com.torrentviewer.core.Legal.UPDATED) {
        Agreement(changed = accepted != null) {
            Kv.setGlobal(AGREED, com.torrentviewer.core.Legal.UPDATED)
            accepted = com.torrentviewer.core.Legal.UPDATED
        }
        return
    }

    // The tour, once, on a fresh install: anyone with a library or a profile already knows the app.
    var toured by remember { mutableStateOf(Kv.global(TOURED) != null || Profiles.list.value.isNotEmpty() || History.items.value.isNotEmpty()) }
    if (!toured || tourAgain) {
        Onboarding {
            Kv.setGlobal(TOURED, "1")
            toured = true
            tourAgain = false
        }
        return
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
    val openOrder: (com.torrentviewer.core.WatchOrder) -> Unit = { pages.add(Page.WatchOrder(it)) }
    val body: @Composable () -> Unit = {
        val top = pages.lastOrNull()
        if (top != null) {
            holder.SaveableStateProvider(keyOf(pages.lastIndex, top)) {
                when (top) {
                    is Page.Detail -> DetailScreen(top.title, watch, onOrder = openOrder, onOpen = openTitle, onPerson = { pages.add(Page.Person(it.id, it.name)) }, onBack = ::pop)
                    is Page.Person -> PersonScreen(top.id, top.name, onOpen = openTitle, onBack = ::pop)
                    is Page.Top -> TopScreen(top.kind, onOpen = openTitle, onBack = ::pop)
                    is Page.Catalog -> CatalogScreen(top.shelf, onOpen = openTitle, onBack = ::pop)
                    Page.Downloads -> DownloadsScreen(watch, onBack = ::pop)
                    Page.WatchOrders -> WatchOrdersScreen(onOpen = openOrder, onBack = ::pop)
                    is Page.WatchOrder -> WatchOrderScreen(top.order, onOpen = openTitle, onBack = ::pop)
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
                        Tab.HOME -> HomeScreen(home, watch, me, onOpen = openTitle, onShelf = { pages.add(Page.Catalog(it)) }, onTop = { pages.add(Page.Top(it)) }, onOrders = { pages.add(Page.WatchOrders) }, onOrder = openOrder, onProfile = { switching = true })
                        Tab.SEARCH -> SearchScreen(watch, onOpen = openTitle)
                        Tab.ADDONS -> AddonsScreen(watch)
                        Tab.LIBRARY -> LibraryScreen(watch, me, onOpen = openTitle, onDownloads = { pages.add(Page.Downloads) })
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

        UpdateCard(watch, Modifier.align(Alignment.TopCenter).statusBarsPadding())

        if (watch.phase == Phase.INSPECTING) Opening(watch)

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

        // An addon decides what the app is offered to play, so one from a link is added only on a yes.
        pendingAddon?.let { link ->
            Sheet(
                title = "Add this addon?",
                options = listOf(
                    Option(true, "Add ${addonHost(link) ?: "this addon"}", "Only add addons you trust: it chooses what you are offered to play."),
                    Option(false, "Cancel"),
                ),
                selected = null,
                onPick = { yes ->
                    pendingAddon = null
                    if (yes) AppScope.launch {
                        watch.notes.tryEmit(runCatching { Addons.add(link) }.fold({ "Added ${it.name} — see the Addons tab" }, { it.message ?: "Couldn't add that addon" }))
                    }
                },
                onDismiss = { pendingAddon = null },
            )
        }

        // The TV asked for the addons: asked first, since their links can carry the viewer's keys.
        sendTo?.let { to ->
            val n = Addons.list.value.size
            Sheet(
                title = "Copy your addons to the TV?",
                options = listOf(
                    Option(true, if (n == 0) "No addons on this phone yet" else "Send $n addon${if (n == 1) "" else "s"}", to.substringAfter("//").substringBefore(':')),
                    Option(false, "Cancel"),
                ),
                selected = null,
                onPick = { yes ->
                    sendTo = null
                    if (yes && n > 0) AppScope.launch {
                        watch.notes.tryEmit("Sending to the TV…")
                        // The TV fetches each addon before it answers: give it time.
                        runCatching { Net.postForm(to, mapOf("key" to Addons.linksJson()), 120_000) }
                            .onSuccess { page ->
                                val said = Regex("<p class=note>(.*?)</p>").find(page)?.groupValues?.get(1)?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
                                watch.notes.tryEmit(said ?: "Sent $n addons to the TV")
                            }
                            .onFailure { watch.notes.tryEmit("Couldn't reach the TV — is the phone on the same Wi-Fi?") }
                    }
                },
                onDismiss = { sendTo = null },
            )
        }
    }
}

/** Portrait: the tabs along the bottom, the one you are on white with the TV rail's red tick. */
@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Ink.bg).grain().navigationBarsPadding().height(58.dp)) {
        tabs().forEach { t ->
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
        tabs().forEach { t ->
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
