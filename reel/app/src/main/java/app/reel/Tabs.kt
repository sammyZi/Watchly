package app.reel

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

private data class TabItem(val label: String, val icon: Int)

private val TABS = listOf(
    TabItem("Home", L.drawable.lucide_ic_house),
    TabItem("Library", L.drawable.lucide_ic_library_big),
    TabItem("Settings", L.drawable.lucide_ic_settings),
)

@Composable
fun TabsScreen(
    tab: Int,
    onTab: (Int) -> Unit,
    shared: Shared,
    homeList: LazyListState,
    libraryGrid: LazyGridState,
    addError: String?,
    onAddError: (String?) -> Unit,
    open: (String, String?) -> Unit,
    openFile: (Uri) -> Unit,
) {
    Scaffold(
        containerColor = Ink.bg,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.surface2))
                NavigationBar(containerColor = Ink.bg, tonalElevation = 0.dp) {
                    TABS.forEachIndexed { i, item ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { onTab(i) },
                            icon = { Lc(item.icon, tint = androidx.compose.material3.LocalContentColor.current, size = 22.dp) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Ink.accent, selectedTextColor = Ink.text,
                                indicatorColor = Ink.accentSoft,
                                unselectedIconColor = Ink.text3, unselectedTextColor = Ink.text3,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            tab,
            Modifier.padding(padding),
            transitionSpec = { (fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { it / 24 }) togetherWith fadeOut(Motion.effects()) },
            label = "tab",
        ) { current ->
            when (current) {
                0 -> HomeTab(shared, homeList, addError, onAddError, open, openFile, seeAll = { onTab(1) })
                1 -> LibraryTab(shared, libraryGrid, open)
                else -> SettingsTab()
            }
        }
    }
}

/* ------------------------------------------------------------------ Home */

@Composable
private fun HomeTab(
    shared: Shared,
    list: LazyListState,
    error: String?,
    onError: (String?) -> Unit,
    open: (String, String?) -> Unit,
    openFile: (Uri) -> Unit,
    seeAll: () -> Unit,
) {
    val entries by Library.entries.collectAsState()
    val watching = remember(entries) { entries.filter { it.resumable }.sortedByDescending { it.watched } }
    val recent = remember(entries) { entries.sortedByDescending { it.added } }
    // Entries saved before artwork was found get one more try per launch.
    LaunchedEffect(Unit) { entries.filter { it.art == null }.forEach { findArt(it.source) } }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).enter(0), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Ink.accent), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.logo_mark), null, Modifier.size(15.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("TorrentPlay", style = Type.h2.copy(fontWeight = FontWeight.ExtraBold, fontSize = 21.sp))
            }
        }
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp).enter(1)) {
                Text(greeting(), style = Type.small)
                Text("What's on tonight?", Modifier.padding(top = 4.dp), style = Type.hero)
            }
        }
        item { AddCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp).enter(2), error, onError, open, openFile) }

        if (watching.isNotEmpty()) {
            item { SectionHeader("Continue watching", Modifier.padding(top = 32.dp, bottom = 12.dp).enter(3)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(watching, key = { _, e -> e.source }) { i, e -> ContinueCard(e, shared, Modifier.enter(4 + i)) { open(e.source, e.name) } }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader("Recently added", Modifier.padding(top = 32.dp, bottom = 12.dp).enter(4), action = "See all", onAction = seeAll) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recent.take(12), key = { _, e -> e.source }) { i, e ->
                        PosterCard(e, shared, Modifier.width(116.dp).enter(5 + i)) { open(e.source, e.name) }
                    }
                }
            }
        } else {
            item { EmptyState(Modifier.padding(top = 40.dp).enter(3)) }
        }
    }
}

private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun AddCard(modifier: Modifier, error: String?, onError: (String?) -> Unit, open: (String, String?) -> Unit, openFile: (Uri) -> Unit) {
    val context = LocalContext.current
    var magnet by rememberSaveable { mutableStateOf("") }
    val trimmed = magnet.trim()
    val valid = trimmed.startsWith("magnet:?", true)
    // File managers label .torrent files inconsistently; anything is accepted and parsed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(openFile) }
    fun go() { if (valid) { open(trimmed, magnetName(trimmed)); magnet = "" } }

    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Ink.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(L.drawable.lucide_ic_magnet, tint = Ink.accent, background = Ink.accentSoft, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Add a torrent", style = Type.title.copy(fontSize = 16.sp))
                Text("Paste a magnet link or open a .torrent file", style = Type.caption.copy(color = Ink.text2))
            }
        }
        Row(
            Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surface2).padding(start = 14.dp, end = 6.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(L.drawable.lucide_ic_link, tint = Ink.text3, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = magnet,
                onValueChange = { magnet = it; onError(null) },
                singleLine = true,
                textStyle = Type.small.copy(color = Ink.text),
                cursorBrush = SolidColor(Ink.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (magnet.isEmpty()) Text("magnet:?xt=urn:btih:…", style = Type.small.copy(color = Ink.text3))
                        inner()
                    }
                },
            )
            AnimatedContent(magnet.isEmpty(), transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) }, label = "paste") { empty ->
                if (empty) {
                    Text(
                        "Paste",
                        Modifier.press {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                            if (text.isNullOrEmpty()) onError("Clipboard is empty.") else magnet = text
                        }.clip(CircleShape).background(Ink.accentSoft).padding(horizontal = 14.dp, vertical = 8.dp),
                        style = Type.small.copy(color = Ink.accent, fontWeight = FontWeight.SemiBold),
                    )
                } else {
                    IconCircle(L.drawable.lucide_ic_x, "Clear", size = 36.dp, tint = Ink.text2) { magnet = "" }
                }
            }
        }
        AnimatedVisibility(error != null, enter = expandVertically(Motion.spatial()) + fadeIn(Motion.effects()), exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Lc(L.drawable.lucide_ic_circle_alert, tint = Ink.error, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(error.orEmpty(), style = Type.caption.copy(color = Ink.error))
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Play", Modifier.weight(1f), icon = L.drawable.lucide_ic_play, enabled = valid, onClick = ::go)
            SecondaryButton("Open file", Modifier.weight(1f), icon = L.drawable.lucide_ic_folder_open) { picker.launch(arrayOf("*/*")) }
        }
    }
}

/** A backdrop card: where you were, and how much is left. */
@Composable
private fun ContinueCard(entry: Entry, shared: Shared, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.width(272.dp).press(pressedScale = 0.97f, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(18.dp))) {
            Artwork(entry.art?.backdrop ?: entry.art?.poster, entry.title, Modifier.fillMaxSize().shared("backdrop-${entry.source}", shared), RoundedCornerShape(18.dp), fallbackText = false)
            Box(Modifier.fillMaxSize().background(scrim(0.35f)))
            Box(
                Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { Lc(L.drawable.lucide_ic_play, tint = Color.White, size = 20.dp) }
            ProgressLine(entry.progress, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
        Text(entry.title, Modifier.padding(top = 10.dp), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(left(entry.length - entry.position), style = Type.caption)
    }
}

@Composable
private fun PosterCard(entry: Entry, shared: Shared, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(modifier.press(pressedScale = 0.96f, onLongClick = onLongClick, onClick = onClick)) {
        Artwork(entry.art?.poster, entry.title, Modifier.fillMaxWidth().aspectRatio(2 / 3f).shared("poster-${entry.source}", shared))
        Text(entry.title, Modifier.padding(top = 8.dp), style = Type.title.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(entry.art?.year ?: bytes(entry.size), style = Type.caption, maxLines = 1)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, title: String = "Nothing here yet", text: String = "Add a magnet link or a .torrent file above. Posters, details and your place in each film show up here.") {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(L.drawable.lucide_ic_popcorn, tint = Ink.accent, background = Ink.accentSoft, size = 64.dp)
        Text(title, Modifier.padding(top = 16.dp), style = Type.h2)
        Text(text, Modifier.padding(top = 6.dp), style = Type.small.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}

/* --------------------------------------------------------------- Library */

@Composable
private fun LibraryTab(shared: Shared, grid: LazyGridState, open: (String, String?) -> Unit) {
    val entries by Library.entries.collectAsState()
    var filter by rememberSaveable { mutableStateOf("All") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val shown = remember(entries, filter) {
        entries.sortedByDescending { maxOf(it.added, it.watched) }.filter {
            when (filter) {
                "Movies" -> it.art?.kind == "movie"
                "Series" -> it.art?.kind == "series"
                else -> true
            }
        }
    }

    LazyVerticalGrid(
        GridCells.Adaptive(108.dp),
        Modifier.fillMaxSize(),
        state = grid,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Library", style = Type.hero)
                    Spacer(Modifier.width(10.dp))
                    Text("${entries.size}", Modifier.padding(bottom = 5.dp), style = Type.small.copy(color = Ink.text3))
                }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Movies", "Series").forEach { f -> FilterPill(f, f == filter) { filter = f } }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Modifier.padding(top = 48.dp), "No titles", if (entries.isEmpty()) "Everything you play is kept here, with where you left off." else "Nothing in this filter yet.")
            }
        }
        items(shown, key = { it.source }) { e ->
            Box(Modifier.animateItem(fadeInSpec = Motion.effects(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effects())) {
                PosterCard(e, shared, Modifier.fillMaxWidth(), onLongClick = { menuFor = e.source }) { open(e.source, e.name) }
                if (e.resumable) ProgressLine(e.progress, Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 8.dp, end = 8.dp))
                DropdownMenu(menuFor == e.source, onDismissRequest = { menuFor = null }, containerColor = Ink.surface2, shape = RoundedCornerShape(14.dp)) {
                    DropdownMenuItem(
                        text = { Text("Remove from library", style = Type.small.copy(color = Ink.text)) },
                        leadingIcon = { Lc(L.drawable.lucide_ic_trash_2, tint = Ink.error, size = 18.dp) },
                        onClick = { Library.remove(e.source); menuFor = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Ink.text else Ink.surface2, Motion.effects(), label = "pill")
    val ink by androidx.compose.animation.animateColorAsState(if (selected) Ink.bg else Ink.text2, Motion.effects(), label = "pillText")
    Text(
        text,
        Modifier.press(onClick = onClick).clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 8.dp),
        style = Type.small.copy(color = ink, fontWeight = FontWeight.SemiBold),
    )
}

/* -------------------------------------------------------------- Settings */

@Composable
private fun SettingsTab() {
    val entries by Library.entries.collectAsState()
    var resume by remember { mutableStateOf(Library.resume) }
    var cache by remember { mutableLongStateOf(0L) }
    var nodes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            cache = withContext(Dispatchers.IO) { Engine.cacheBytes() }
            nodes = Engine.dhtNodes()
            delay(2000)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Text("Settings", Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, top = 16.dp, bottom = 8.dp), style = Type.hero)
        }
        item {
            Group("Appearance", Modifier.enter(0)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(L.drawable.lucide_ic_sun_moon, tint = Ink.text, size = 38.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Theme", style = Type.title)
                            Text("The player is always dark", style = Type.caption.copy(color = Ink.text2))
                        }
                    }
                    ThemePicker(Modifier.padding(top = 14.dp))
                }
            }
        }
        item {
            Group("Playback", Modifier.enter(0)) {
                SettingRow(L.drawable.lucide_ic_history, "Resume where you left off", "Pick a film back up at the last position") {
                    Switch(
                        resume,
                        onCheckedChange = { resume = it; Library.resume = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Ink.accent, uncheckedTrackColor = Ink.surface2, uncheckedBorderColor = Ink.stroke, uncheckedThumbColor = Ink.text3),
                    )
                }
            }
        }
        item {
            Group("Storage", Modifier.enter(1)) {
                SettingRow(L.drawable.lucide_ic_hard_drive, "Stream cache", if (cache == 0L) "Empty" else bytes(cache)) {
                    if (cache > 0) ConfirmText("Clear") { Engine.clearCache(); cache = 0 }
                }
                Divider()
                SettingRow(L.drawable.lucide_ic_library_big, "Library", "${entries.size} title${if (entries.size == 1) "" else "s"} · positions and artwork") {
                    if (entries.isNotEmpty()) ConfirmText("Clear") { Library.clear() }
                }
            }
        }
        item {
            Group("Network", Modifier.enter(2)) {
                SettingRow(L.drawable.lucide_ic_radio_tower, "Incoming port", "6883 · opened on the router with UPnP")
                Divider()
                SettingRow(L.drawable.lucide_ic_network, "DHT nodes", if (nodes > 0) "$nodes known — finds peers without trackers" else "Connecting…")
            }
        }
        item {
            Group("About", Modifier.enter(3)) {
                SettingRow(L.drawable.lucide_ic_info, "TorrentPlay", "Version 1.0 · streams torrents, keeps nothing")
                Divider()
                SettingRow(L.drawable.lucide_ic_file_text, "Open-source licenses", "libtorrent · libVLC · Coil · Lucide icons · Google Sans Flex (OFL)")
            }
        }
    }
}

/** System / Light / Dark as one segmented control; the highlight slides to the choice. */
@Composable
private fun ThemePicker(modifier: Modifier) {
    val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
    val icons = listOf(L.drawable.lucide_ic_smartphone, L.drawable.lucide_ic_sun, L.drawable.lucide_ic_moon)
    val selected = options.indexOfFirst { it.first == Library.theme }.coerceAtLeast(0)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface2).padding(4.dp)) {
        val segment = maxWidth / options.size
        val x by androidx.compose.animation.core.animateDpAsState(segment * selected, Motion.spatial(), label = "segment")
        Box(Modifier.padding(start = x).width(segment).height(40.dp).clip(RoundedCornerShape(11.dp)).background(Ink.surface))
        Row {
            options.forEachIndexed { i, (key, label) ->
                val on = i == selected
                val ink by androidx.compose.animation.animateColorAsState(if (on) Ink.text else Ink.text2, Motion.effects(), label = "seg$i")
                Row(
                    Modifier.width(segment).height(40.dp).press(pressedScale = 0.96f) { Library.chooseTheme(key) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Lc(icons[i], tint = ink, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = Type.small.copy(color = ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(top = 20.dp)) {
        Text(title, Modifier.padding(start = 4.dp, bottom = 8.dp), style = Type.small.copy(color = Ink.text3))
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Ink.surface)) { content() }
    }
}

@Composable
private fun SettingRow(icon: Int, title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint = Ink.text, size = 38.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = Type.title)
            Text(subtitle, style = Type.caption.copy(color = Ink.text2))
        }
        trailing()
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(Ink.surface2))

/** Asks twice before anything destructive: "Clear" turns into "Sure?" for a few seconds. */
@Composable
private fun ConfirmText(text: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    Text(
        if (armed) "Sure?" else text,
        Modifier.press { if (armed) { onConfirm(); armed = false } else armed = true }
            .clip(CircleShape).background(if (armed) Ink.error.copy(alpha = 0.18f) else Ink.surface2)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = Type.small.copy(color = if (armed) Ink.error else Ink.text, fontWeight = FontWeight.SemiBold),
    )
}
