package com.torrentviewer.mobile

import com.torrentviewer.core.Note
import com.torrentviewer.core.Inbox
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
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
import androidx.compose.runtime.snapshotFlow
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
import com.torrentviewer.core.Backup
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.COLOURS
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.grain
import com.torrentviewer.core.Item
import com.torrentviewer.core.Party
import com.torrentviewer.core.openParty
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

enum class Tab(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", L.drawable.lucide_ic_house),
    SEARCH("Search", L.drawable.lucide_ic_search),
    LIBRARY("Library", L.drawable.lucide_ic_library),
    SETTINGS("Settings", L.drawable.lucide_ic_settings),
}

/** The tab bar. Addons live in Settings, above Account. */
private fun tabs() = Tab.entries

/** What is stacked over the tabs: a title, a whole shelf, or the downloads (reached from Library). */
sealed interface Page {
    data class Detail(val title: Title, val season: Int? = null) : Page
    data class Catalog(val shelf: Shelf) : Page
    data object Downloads : Page
    data object WatchOrders : Page
    data class WatchOrder(val order: com.torrentviewer.core.WatchOrder) : Page
    data class Person(val id: Int, val name: String) : Page
    data class Top(val kind: String) : Page
    data object Notifications : Page
}

private fun keyOf(index: Int, page: Page) = when (page) {
    is Page.Detail -> "detail:$index:${page.title.imdb}"
    is Page.Catalog -> "shelf:$index:${page.shelf.key}"
    Page.Downloads -> "downloads:$index"
    Page.WatchOrders -> "orders:$index"
    is Page.WatchOrder -> "order:$index:${page.order.id}"
    is Page.Person -> "person:$index:${page.id}"
    is Page.Top -> "top:$index"
    Page.Notifications -> "notifications:$index"
}

/** The profile opened last on this phone, so a phone with one owner never asks. */
private const val LAST_PROFILE = "phone:lastProfile"

/** The first-launch tour has been seen (or skipped). */
private const val TOURED = "phone:toured"
/** The date of the legal text last accepted. */
private const val AGREED = "phone:agreed"

private fun open(profile: Profile) {
    // Before anything is read: every store call below goes through this profile.
    Kv.useProfile(profile.id, profile.isGuest)
    com.torrentviewer.core.Party.profileName = profile.name
    com.torrentviewer.core.SettingsStore.load()
    History.load()
    SavedStore.load()
    Stremio.load(profile.id, profile.isGuest)
    com.torrentviewer.core.Nuvio.load(profile.id, profile.isGuest)
    com.torrentviewer.core.WATCH_TRACKERS.forEach { it.load(profile.id, profile.isGuest) }
    com.torrentviewer.core.AniList.load(profile.id, profile.isGuest)
    Inbox.load()
    Kids.use(profile)
    if (!profile.isGuest) Kv.setGlobal(LAST_PROFILE, profile.id)
}

/**
 * A sign-in asked for as the guest: a profile is made first, as the guest forgets its sign-ins on exit.
 * Any sign-in button sets it; the shell answers with the profile editor.
 */
object NeedProfile {
    var ask by mutableStateOf(false)

    /** True, and the editor is on its way, when [Kv] is the guest's: the caller signs in only on false. */
    fun first(): Boolean = Kv.isGuest.also { if (it) ask = true }
}

/**
 * Who opens without asking: the last one, unless it is locked. None made yet: nobody, so the picker
 * offers Guest or a new profile — the first takes the bare keys, and what was here before stays.
 */
private fun startProfile(list: List<Profile>): Profile? {
    if (list.isEmpty()) return null
    val last = Kv.global(LAST_PROFILE)
    return (list.find { it.id == last } ?: list.singleOrNull())?.takeIf { it.pin == null }
}

/** The TV's red glares at arm's length on a phone screen: a deeper one, unless another colour was picked. */
val PHONE_RED = Color(0xFFB81D24)

fun accentOf(argb: Int) = if (argb == 0) PHONE_RED else Color(argb)

fun darkFor(mode: String, systemDark: Boolean) = when (mode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

/** The page's palette, and status and navigation bar icons that read on it. */
fun ComponentActivity.applyTheme(dark: Boolean, amoled: Boolean = SettingsStore.value.value.amoled) {
    Ink.amoled = amoled
    Ink.use(dark)
    val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    enableEdgeToEdge(bars, bars)
}

/** What the download notification opens. */
const val DOWNLOADS_LINK = "watchly://downloads"

/** watchly://title/<imdb>?kind=tv&s=<season>&name=…: a title's page, as a new-episode notification opens it. */
const val TITLE_LINK_HOST = "title"

@Composable
fun App(watch: Watch, incoming: MutableState<Uri?>) {
    val settings by SettingsStore.value.collectAsState()
    val dark = darkFor(settings.themeMode, isSystemInDarkTheme())
    val activity = LocalActivity.current as? ComponentActivity
    // The screen as last drawn, and where the finger last came down: a new theme spreads out from the touch
    // as a circle over a still of the old one, the way Telegram's does.
    val screen = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var touch by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
    var old by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    // Live, for the setting and the system's own switch; the player keeps its dark while it is up.
    LaunchedEffect(dark, settings.amoled) {
        if (watch.phase == Phase.PLAYING) return@LaunchedEffect
        // Not at launch, and not if it is already in this theme: nothing to change from.
        val animate = started && (Ink.isDark != dark || (dark && Ink.amoled != settings.amoled)) && screen.size.width > 0
        started = true
        // Before the palette changes, while the layer still holds the old one.
        if (animate) old = runCatching { screen.toImageBitmap() }.getOrNull()
        activity?.applyTheme(dark, settings.amoled)
        if (old != null) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            old = null
        }
    }
    Ink.accent = accentOf(settings.accent)
    // By the palette in use, so Material's colours change in the same frame as the page's.
    val scheme = if (Ink.isDark) darkColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.surface) else lightColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.surface)
    MaterialTheme(colorScheme = scheme) {
        // The pages, which the tab bar frosts over — and the toast and the rejoin card floating over them.
        val pageHaze = dev.chrisbanes.haze.rememberHazeState()
        // Over the film there is no page to frost: the video is its own surface, which the blur can't see.
        val glass = pageHaze.takeIf { watch.phase != Phase.PLAYING }
        Box(
            Modifier.fillMaxSize()
                // Seen first, and let through: only where the touch was is kept.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) touch = e.changes.first().position
                        }
                    }
                },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .drawWithContent {
                        screen.record { this@drawWithContent.drawContent() }
                        drawLayer(screen)
                    }
                    .background(Ink.bg).grain(),
            ) {
                Shell(watch, incoming, pageHaze)
                // An invite opened: its own screen, over everything, until Join or Not now.
                Party.room?.takeIf { !it.leader && !it.accepted }?.let { JoinPartyScreen(it) }
                // Left one — by Back, Leave, or its leader ending it: the way back in, until taken or dismissed.
                Party.last?.takeIf { Party.room == null && Party.enabled }?.let { RejoinBar(it, top = watch.phase == Phase.PLAYING, haze = glass) }
                Toast(watch, glass)
            }
            old?.let { still ->
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val from = if (touch.isSpecified) touch else center
                    // To the farthest corner, so the circle has covered everything when it ends.
                    val far = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).maxOf { (it - from).getDistance() }
                    val hole = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(from, far * reveal.value)) }
                    clipPath(hole, androidx.compose.ui.graphics.ClipOp.Difference) { drawImage(still) }
                }
            }
        }
    }
}

@Composable
private fun Shell(watch: Watch, incoming: MutableState<Uri?>, barHaze: dev.chrisbanes.haze.HazeState) {
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

    // Once: the permission new-episode alerts need. They are on by default, and waiting for a series
    // to turn up in the history left the setting saying On with Android never asked. Refused, the
    // setting itself offers it again.
    LaunchedEffect(Unit) {
        if (!SettingsStore.value.value.episodeAlerts || !EpisodeAlerts.needsPermission(context)) return@LaunchedEffect
        if (Kv.global("asked-episode-alerts-2") != null) return@LaunchedEffect
        Kv.setGlobal("asked-episode-alerts-2", "1")
        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
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
    /** The TV asked for the whole library and settings, not just the addons. */
    var sendAll by remember { mutableStateOf(false) }
    /** The TV asked for this phone's AniList sign-in: it has no browser for AniList's own page. */
    var sendAniList by remember { mutableStateOf(false) }
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
        // A new-episode notification: the show's page, at that season.
        if (uri.scheme == "watchly" && uri.host == TITLE_LINK_HOST) {
            val imdb = uri.lastPathSegment?.takeIf { it.startsWith("tt") } ?: return@LaunchedEffect
            clearPages()
            tab = Tab.HOME
            pages.add(Page.Detail(Title(imdb, uri.getQueryParameter("kind") ?: "tv", uri.getQueryParameter("name").orEmpty()), uri.getQueryParameter("s")?.toIntOrNull()))
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
            sendAll = uri.getQueryParameter("all") == "1"
            sendAniList = uri.getQueryParameter("what") == "anilist"
            return@LaunchedEffect
        }
        if (raw.startsWith("watchly://party", ignoreCase = true)) {
            if (!Party.available) return@LaunchedEffect watch.fail("This build of Watchly has no watch party.")
            if (!Party.enabled) return@LaunchedEffect watch.fail("Watch party is turned off — turn it on in Settings → Watch party.")
            Party.join(raw) ?: return@LaunchedEffect watch.fail("That watch party link is incomplete.")
            watch.notes.tryEmit("Joining the watch party…")
            return@LaunchedEffect
        }
        if (!isTorrentSource(raw)) return@LaunchedEffect
        val path = runCatching { withContext(Dispatchers.IO) { watch.engine.importSource(raw) } }
            .getOrElse { return@LaunchedEffect watch.fail(it.message ?: "Couldn't read that file.") }
        val item = History.put(Item(path, titleOf(raw), System.currentTimeMillis()))
        watch.resume(item)
    }

    // A guest opens whatever the party's leader is playing — at the start, and again when the leader
    // moves on to the next episode or another film. The player keeps the two in step from there.
    // Any page can hand over a party link: what it would play is shown first, on its own screen (App's
    // JoinPartyScreen), and nothing opens until the viewer says yes.
    val room = Party.room
    LaunchedEffect(room, room?.accepted, room?.ownSource) {
        if (room == null || room.leader) return@LaunchedEffect
        var opened: String? = null
        launch {
            room.ended.collect {
                // Still at the join screen: it says "closed" itself, and Close leaves.
                if (room.accepted) Party.leave(ended = true)
            }
        }
        // Rejoined a party that has long closed: nothing to wait for.
        launch {
            snapshotFlow { room.over }.collect { over ->
                if (over && room.accepted && opened == null) {
                    Party.leave(ended = true)
                    Party.last = null
                    watch.notes.tryEmit("That watch party has closed.")
                }
            }
        }
        room.states.collect { s ->
            // A guest on a copy of their own picks it on the film's page (below), not here.
            if (!room.accepted || room.ownSource) return@collect
            if (s.media.file == opened) return@collect
            opened = s.media.file
            watch.openParty(s)
        }
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

    // After the terms: nothing is asked of GitHub until they are accepted.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) { com.torrentviewer.core.Updates.check(appContext, BuildConfig.VERSION_NAME, "Watchly-phone-", BuildConfig.DEBUG) }
    // A new version, kept in the notification center too: the card at the top can be put off.
    val release by com.torrentviewer.core.Updates.release.collectAsState()
    LaunchedEffect(release, who) {
        val r = release ?: return@LaunchedEffect
        if (who != null) Inbox.add(Note("update:${r.version}", "Update available", "Watchly ${r.version} · ${com.torrentviewer.core.bytes(r.size)} · tap to download", System.currentTimeMillis(), null))
    }
    // Subtitles from OpenSubtitles out of the box, through Stremio's addon.
    LaunchedEffect(Unit) { Addons.seedSubtitles() }

    // The tour, once, on a fresh install: anyone with a library or a profile already knows the app.
    // Decided before the profile step below, which makes a profile on a fresh install.
    var toured by remember { mutableStateOf(Kv.global(TOURED) != null || Profiles.list.value.isNotEmpty() || History.items.value.isNotEmpty()) }

    // Who first, before the tour: its genres and sign-in then belong to someone. On a fresh install
    // that opens on making a profile; back from there, Guest is still on offer.
    val me = who
    if (me == null || switching || NeedProfile.ask) {
        ProfilesScreen(
            profiles = profiles,
            onPick = { NeedProfile.ask = false; enter(it) },
            onCancel = when {
                // The guest asked to sign in: back, it stays the guest, signed in to nothing.
                NeedProfile.ask -> ({ NeedProfile.ask = false })
                me != null -> ({ switching = false })
                else -> null
            },
            onRemoved = { gone ->
                if (who?.id == gone.id) who = startProfile(Profiles.list.value)?.also(::open)
            },
            create = profiles.isEmpty() || NeedProfile.ask,
            note = if (NeedProfile.ask) "Make a profile to keep your sign-ins — Guest forgets them when the app closes." else null,
        )
        return
    }

    if (!toured || tourAgain) {
        Onboarding {
            Kv.setGlobal(TOURED, "1")
            toured = true
            tourAgain = false
        }
        return
    }

    if (watch.phase == Phase.PLAYING) {
        PlayerScreen(watch)
        return
    }

    BackHandler(pages.isNotEmpty()) { pop() }
    BackHandler(pages.isEmpty() && tab != Tab.HOME) { tab = Tab.HOME }

    val openTitle: (Title) -> Unit = { pages.add(Page.Detail(it)) }
    // A party guest picking their own copy: the film's page, its sources there to choose from.
    LaunchedEffect(ownSourceFor) { ownSourceFor?.let { openTitle(it); ownSourceFor = null } }
    val openOrder: (com.torrentviewer.core.WatchOrder) -> Unit = { pages.add(Page.WatchOrder(it)) }
    val body: @Composable () -> Unit = {
      // A page opening — a title's, mostly — fades up from just under full size; going back, it shrinks away over the one under it.
      AnimatedContent(
        pages.lastIndex to pages.lastOrNull(),
        transitionSpec = {
            val grow = tween<Float>(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            // Deeper, or a page put in place of the one on top: in. Only a shallower stack is going back.
            if (targetState.first >= initialState.first) {
                (fadeIn(tween(220)) + androidx.compose.animation.scaleIn(grow, initialScale = 0.94f)) togetherWith fadeOut(tween(200))
            } else {
                (fadeIn(tween(220)) togetherWith (fadeOut(tween(220)) + androidx.compose.animation.scaleOut(grow, targetScale = 0.94f)))
                    .apply { targetContentZIndex = -1f }
            }
        },
        contentKey = { (i, p) -> p?.let { keyOf(i, it) } },
        label = "pages",
      ) { (index, top) ->
        if (top != null) {
            holder.SaveableStateProvider(keyOf(index, top)) {
                when (top) {
                    is Page.Detail -> DetailScreen(top.title, watch, onOrder = openOrder, onOpen = openTitle, onPerson = { pages.add(Page.Person(it.id, it.name)) }, onBack = ::pop, startSeason = top.season)
                    is Page.Person -> PersonScreen(top.id, top.name, onOpen = openTitle, onBack = ::pop)
                    is Page.Top -> TopScreen(top.kind, onOpen = openTitle, onBack = ::pop)
                    is Page.Catalog -> CatalogScreen(top.shelf, onOpen = openTitle, onBack = ::pop)
                    Page.Downloads -> DownloadsScreen(watch, onBack = ::pop)
                    Page.WatchOrders -> WatchOrdersScreen(onOpen = openOrder, onBack = ::pop)
                    Page.Notifications -> NotificationsScreen(watch, onLink = { incoming.value = android.net.Uri.parse(it) }, onBack = ::pop)
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
                        Tab.HOME -> HomeScreen(home, watch, me, onOpen = openTitle, onShelf = { pages.add(Page.Catalog(it)) }, onTop = { pages.add(Page.Top(it)) }, onProfile = { switching = true }, onNotifications = { pages.add(Page.Notifications) })
                        Tab.SEARCH -> SearchScreen(watch, onOpen = openTitle)
                        Tab.LIBRARY -> LibraryScreen(watch, me, onOpen = openTitle, onDownloads = { pages.add(Page.Downloads) })
                        Tab.SETTINGS -> SettingsScreen(watch, me, onSwitchProfile = { switching = true }, onEditHome = { home.editing = true; tab = Tab.HOME })
                    }
                }
            }
        }
      }
    }
    val choose: (Tab) -> Unit = { t ->
        clearPages()
        tab = t
    }
    // Scrolling down shrinks the dock to its icons, scrolling up brings it back in full; a tab change leaves it as it is.
    val dockThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    val dockScroll = remember { DockScroll(dockThreshold) }

    Box(Modifier.fillMaxSize()) {
        if (isWide()) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))) {
                NavRail(tab, me, choose, onProfile = { switching = true })
                Box(Modifier.weight(1f).fillMaxHeight().hazeSource(barHaze)) { body() }
            }
        } else {
            // Every page runs on under the dock, which frosts what passes behind it, and leaves room at its foot
            // for it (LocalTabBarInset). A title's page has the screen to itself: the dock sinks away while it is
            // open, and that page keeps only the system bar's room.
            val onTitle = pages.lastOrNull() is Page.Detail
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bar = if (onTitle) navBar else DOCK_HEIGHT + DOCK_GAP * 2 + navBar
            androidx.compose.runtime.CompositionLocalProvider(LocalTabBarInset provides bar) {
                Box(Modifier.fillMaxSize().nestedScroll(dockScroll).hazeSource(barHaze)) { body() }
            }
            AnimatedVisibility(
                !onTitle,
                Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
            ) {
                TabBar(tab, choose, barHaze, dockScroll.compact)
            }
        }

        UpdateCard(watch, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        // The one ask for a star: on Home, never over a film, and not beside an update.
        val updating by com.torrentviewer.core.Updates.release.collectAsState()
        if (tab == Tab.HOME && watch.phase == Phase.IDLE && updating == null) StarCard(Modifier.align(Alignment.TopCenter).statusBarsPadding())

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
                        watch.notes.tryEmit(runCatching { Addons.add(link) }.fold({ "Added ${it.name} — see Settings → Addons" }, { it.message ?: "Couldn't add that addon" }))
                    }
                },
                onDismiss = { pendingAddon = null },
            )
        }

        // The TV asked for the addons: asked first, since their links can carry the viewer's keys.
        sendTo?.let { to ->
            val all = sendAll
            val n = Addons.list.value.size
            val tv = to.substringAfter("//").substringBefore(':')
            val aniUser = com.torrentviewer.core.AniList.user.collectAsState().value
            val aniOn = com.torrentviewer.core.AniList.connected.collectAsState().value
            // "" cancels, "*" is everything, "addons" the addon list alone, "anilist" this sign-in; the rest are Backup.GROUPS' labels.
            Sheet(
                title = if (sendAniList) "Sign the TV in to AniList?" else if (all) "What should the TV copy?" else "Copy your addons to the TV?",
                options = if (sendAniList) {
                    listOf(
                        Option("anilist", if (aniOn) "Send your AniList sign-in" else "Not signed in to AniList on this phone", if (aniOn) listOfNotNull(aniUser, tv).joinToString(" · ") else "Connect it in Settings → Integrations first"),
                        Option("", "Cancel"),
                    )
                } else if (all) {
                    Backup.GROUPS.keys.map { Option(it, it, "Replaces the TV's, for this profile") } +
                        Option("*", "Everything", "All of the above") + Option("", "Cancel")
                } else {
                    listOf(
                        Option("addons", if (n == 0) "No addons on this phone yet" else "Send $n addon${if (n == 1) "" else "s"}", tv),
                        Option("", "Cancel"),
                    )
                },
                selected = null,
                onPick = { pick ->
                    sendTo = null
                    sendAll = false
                    sendAniList = false
                    val body = when {
                        pick.isEmpty() || pick == "addons" && n == 0 -> null
                        pick == "anilist" -> com.torrentviewer.core.AniList.shareable()
                        pick == "addons" -> Addons.linksJson()
                        pick == "*" -> Backup.export()
                        else -> Backup.export(only = Backup.GROUPS[pick])
                    }
                    if (body != null) AppScope.launch {
                        watch.notes.tryEmit("Sending to the TV…")
                        // The TV fetches each addon before it answers: give it time.
                        runCatching { Net.postForm(to, mapOf("key" to body), 120_000) }
                            .onSuccess { page ->
                                val said = Regex("<p class=note>(.*?)</p>").find(page)?.groupValues?.get(1)?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
                                watch.notes.tryEmit(said ?: "Sent to the TV")
                            }
                            .onFailure { watch.notes.tryEmit("Couldn't reach the TV — is the phone on the same Wi-Fi?") }
                    }
                },
                onDismiss = { sendTo = null; sendAll = false; sendAniList = false },
            )
        }
    }
}

/** The dock's height, and the room round it: it floats this far off the screen's edges. */
private val DOCK_HEIGHT = 64.dp
private val DOCK_GAP = 12.dp
private val DOCK_INSET = 6.dp
private val LENS_SHAPE = RoundedCornerShape(26.dp)

/** Room a tab's page leaves at its foot for the tab bar it runs on under; 0 where nothing is under it. */
val LocalTabBarInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/**
 * Portrait: OpenTune's dock (BottomChrome.kt, GPL-3.0 — see THIRD_PARTY_NOTICES.md). A glass pill floating
 * over the page, frosted with what passes under it, lit along its top edge and casting a soft shadow. The
 * open tab sits on a lens of the accent that stretches to a new tab rather than sliding: the edge in the
 * direction of travel leads on a stiff spring and the far one follows on a soft one, both critically damped,
 * so it reaches over and settles without overshooting. The icon and name ease to the accent as it arrives.
 */
@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit, haze: dev.chrisbanes.haze.HazeState, compact: Boolean, modifier: Modifier = Modifier) {
    val all = tabs()
    val shown = all.indexOf(tab).coerceAtLeast(0)
    val pill = RoundedCornerShape(percent = 50)
    val page = Ink.bg
    val dark = Ink.isDark
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 0 the full dock, 1 shrunk to its icons: one spring every piece reads its size and place from, so a
    // scroll that changes its mind mid-way just turns it around.
    val fold by androidx.compose.animation.core.animateFloatAsState(
        if (compact) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 380f),
        label = "dockFold",
    )
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = DOCK_GAP + 4.dp + DOCK_SHRINK_SIDES * fold, vertical = DOCK_GAP)
            .height(DOCK_HEIGHT - (DOCK_HEIGHT - DOCK_COMPACT_HEIGHT) * fold),
    ) {
        val slot = (maxWidth - DOCK_INSET * 2) / all.size
        // Which way the last move went, so the leading edge gets the stiff spring. In slots, not dp, so the
        // dock shrinking under it doesn't knock it off its tab.
        var from by remember { androidx.compose.runtime.mutableIntStateOf(shown) }
        val right = shown >= from
        androidx.compose.runtime.SideEffect { from = shown }
        val lead = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 520f)
        val follow = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 170f)
        // Read only while laying the lens out, so its stretch costs no recomposition of the dock.
        val leftSlot = androidx.compose.animation.core.animateFloatAsState(shown.toFloat(), if (right) follow else lead, label = "lensLeft")
        val endSlot = androidx.compose.animation.core.animateFloatAsState(shown + 1f, if (right) lead else follow, label = "lensRight")
        val light = Color.White.copy(alpha = if (dark) 0.22f else 0.6f)
        Box(
            Modifier.fillMaxSize()
                // A soft drop shadow, so the dock floats over the page.
                .shadow(18.dp, pill, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(pill)
                .hazeEffect(haze) {
                    backgroundColor = page
                    tints = listOf(dev.chrisbanes.haze.HazeTint(Ink.surface.copy(alpha = 0.72f)))
                    blurRadius = 24.dp
                    noiseFactor = if (Ink.pureBlack) 0f else 0.03f
                    // Blurred at half size: under this much blur it looks the same, and it is a quarter of the
                    // work on every frame a page scrolls under the dock — what an older phone runs short of.
                    inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.5f)
                }
                // A hairline of light along the top edge, fading down: the glass reads as lit from above.
                .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(light, light.copy(alpha = 0f), light.copy(alpha = 0f))), pill)
                .padding(DOCK_INSET),
        ) {
            val a = Ink.accent
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val from = (slot * leftSlot.value).roundToPx()
                        val w = (slot * (endSlot.value - leftSlot.value)).roundToPx().coerceAtLeast(0)
                        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, constraints.maxHeight))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(from, 0) }
                    }
                    .clip(LENS_SHAPE)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(a.copy(alpha = 0.24f), a.copy(alpha = 0.14f))))
                    .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(a.copy(alpha = 0.45f), a.copy(alpha = 0.08f))), LENS_SHAPE),
            )
            Row(Modifier.fillMaxSize()) {
                all.forEachIndexed { i, t ->
                    DockItem(t, i == shown, Modifier.width(slot).fillMaxHeight(), collapse = { fold }) {
                        if (i != shown) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onTab(t)
                    }
                }
            }
        }
    }
}

/** One tab of the dock: its icon over its name, in the accent while open, lifted a little; pressed, it gives a little. */
@Composable
private fun DockItem(t: Tab, selected: Boolean, modifier: Modifier, collapse: () -> Float, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        when {
            pressed -> 0.9f
            selected -> 1.06f
            else -> 1f
        },
        androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 320f),
        label = "dockScale",
    )
    val fg by androidx.compose.animation.animateColorAsState(
        if (selected) Ink.accent else Ink.muted,
        tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "dockFg",
    )
    Column(
        modifier.clip(LENS_SHAPE)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { role = androidx.compose.ui.semantics.Role.Tab; this.selected = selected; contentDescription = t.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Shrinking, the icon drops to the middle as the name fades and sinks away.
        Lc(t.icon, Modifier.graphicsLayer { scaleX = scale; scaleY = scale; translationY = 8.dp.toPx() * collapse() }, tint = fg, size = 23.dp)
        // One weight either way, so the name doesn't change width as it's picked.
        Text(
            t.label,
            Modifier.padding(top = 2.dp).graphicsLayer {
                val c = collapse()
                alpha = (1f - c * 2.5f).coerceIn(0f, 1f)
                translationY = 6.dp.toPx() * c
            },
            style = Type.meta.copy(fontSize = 11.sp, color = fg, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

/** The dock shrunk while a page scrolls down: this tall, and this much further in from each side. */
private val DOCK_COMPACT_HEIGHT = 54.dp
private val DOCK_SHRINK_SIDES = 36.dp

/**
 * Scrolling a page down shrinks the dock; scrolling up brings it back. The direction has to hold for
 * [thresholdPx] before anything changes, so a wobble mid-scroll doesn't flicker it, and only what the page
 * really scrolled counts — a swipe at a list's end leaves it be. Nothing is consumed: the page keeps every
 * pixel. After OpenTune's ChromeScrollConnection.
 */
private class DockScroll(private val thresholdPx: Float) : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    var compact by mutableStateOf(false)
        private set
    private var run = 0f

    fun expand() {
        compact = false
        run = 0f
    }

    override fun onPostScroll(
        consumed: androidx.compose.ui.geometry.Offset,
        available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
    ): androidx.compose.ui.geometry.Offset {
        val dy = consumed.y
        if (dy == 0f) return androidx.compose.ui.geometry.Offset.Zero
        if (run * dy < 0) run = 0f
        run += dy
        if (run <= -thresholdPx && !compact) {
            compact = true
            run = 0f
        } else if (run >= thresholdPx && compact) {
            compact = false
            run = 0f
        }
        return androidx.compose.ui.geometry.Offset.Zero
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
private fun Toast(watch: Watch, haze: dev.chrisbanes.haze.HazeState?) {
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
                Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(10.dp)).glass(haze, Ink.surface2).padding(horizontal = 16.dp, vertical = 12.dp),
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
