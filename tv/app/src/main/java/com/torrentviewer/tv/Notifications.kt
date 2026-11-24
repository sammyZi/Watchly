package com.torrentviewer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.AniList
import com.torrentviewer.core.Inbox
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.NewEpisodes
import com.torrentviewer.core.Note
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import kotlinx.coroutines.delay

/** AniList's own blue, so its rows read as its at a glance. */
private val ANILIST_BLUE = Color(0xFF02A9FF)
private const val DAY = 24 * 60 * 60 * 1000L
/** How often the TV looks for new episodes while it is on — the phone's background check runs as often. */
private const val CHECK_MS = 12 * 60 * 60 * 1000L

/** Unseen in either source: the rail's dot. */
@Composable
fun unreadNotifications(): Int {
    val items by Inbox.items.collectAsState()
    val readAt by Inbox.readAt.collectAsState()
    val ani by AniList.unread.collectAsState()
    return ani + items.count { it.at > readAt }
}

/**
 * New episodes of followed series, into each profile's notifications: the phone's EpisodeAlerts, run while
 * the TV is on (a TV shows no notifications of its own). [onNew] hears of those for the profile in use.
 */
@Composable
fun EpisodeAlertsLoop(profile: String?, onNew: (String) -> Unit) {
    val on = SettingsStore.value.collectAsState().value.episodeAlerts
    val current by androidx.compose.runtime.rememberUpdatedState(profile)
    LaunchedEffect(on) {
        while (on) {
            val alerts = runCatching { NewEpisodes.alerts() }.getOrDefault(emptyList())
            alerts.forEach { a ->
                val e = a.episode
                val text = (if (a.count > 1) "${a.count} new episodes, the latest is " else "") + "S${e.season}:E${e.episode}" +
                    (e.name.takeIf { it.isNotBlank() && !it.startsWith("Episode") }?.let { " · $it" } ?: "")
                Inbox.addFor(a.profile, Note("ep:${a.title.imdb}:${e.season}:${e.episode}", a.title.title, "New episode: $text", System.currentTimeMillis(), "watchly://title/${a.title.imdb}?kind=tv&s=${e.season}"))
            }
            Inbox.load()
            alerts.firstOrNull { it.profile == current }?.let { onNew("New episode of ${it.title.title}") }
            delay(CHECK_MS)
        }
    }
}

private class Line(val key: String, val title: String?, val text: String, val at: Long, val fromAniList: Boolean, val image: String?, val unread: Boolean, val open: (() -> Unit)?)

/** Watchly's own notifications and AniList's, newest first; opening it marks both read. */
@Composable
fun NotificationsScreen(onOpen: (Title) -> Unit) {
    val notes by Inbox.items.collectAsState()
    val connected by AniList.connected.collectAsState()
    val settings by SettingsStore.value.collectAsState()
    // As they were on opening: what is new stays marked while the page is up.
    val seenBefore = remember { Inbox.readAt.value }
    val aniUnread = remember { AniList.unread.value }
    var ani by remember { mutableStateOf<List<AniList.Notice>?>(null) }
    var aniError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Inbox.markRead() }
    LaunchedEffect(connected) {
        if (connected) runCatching { AniList.notices(read = true) }.fold({ ani = it }, { aniError = "Couldn't reach AniList" })
    }
    // A watchly://title/<imdb>?kind=…&s=… link opens the series' page.
    fun openLink(link: String, name: String) {
        val uri = android.net.Uri.parse(link)
        val imdb = uri.pathSegments.firstOrNull() ?: return
        onOpen(Title(imdb, uri.getQueryParameter("kind") ?: "tv", name))
    }
    val lines = (notes.map { n ->
        Line("w:${n.id}", n.title, n.text, n.at, false, null, n.at > seenBefore, n.link?.takeIf { it.startsWith("watchly://title/") }?.let { l -> { openLink(l, n.title) } })
    } + ani.orEmpty().mapIndexed { i, n ->
        Line("a:${n.id}", null, n.text, n.at, true, n.poster, i < aniUnread, n.imdb?.let { id -> { onOpen(Title(id, n.kind ?: "tv", "")) } })
    }).sortedByDescending { it.at }
    val first = remember { FocusRequester() }

    Page {
        Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notifications", style = Type.display)
                val fresh = lines.count { it.unread }
                Text(if (fresh > 0) "$fresh new" else "All caught up", style = Type.meta.copy(color = if (fresh > 0) Ink.accent else Ink.faint))
            }
            Chip(if (settings.episodeAlerts) "New-episode alerts: On" else "New-episode alerts: Off", settings.episodeAlerts, onClick = {
                SettingsStore.save(settings.copy(episodeAlerts = !settings.episodeAlerts))
            }, icon = L.drawable.lucide_ic_tv, requester = first)
            if (notes.isNotEmpty()) {
                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                Chip("Clear", false, onClick = { Inbox.clear() }, icon = L.drawable.lucide_ic_trash_2)
            }
        }
        if (lines.isEmpty()) {
            Text(
                "New episodes of shows you follow show here" + if (connected) ", with your AniList notifications." else ". Sign in to AniList in Settings to see its notifications too.",
                Modifier.widthIn(max = 640.dp), style = Type.body.copy(color = Ink.faint),
            )
            aniError?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.danger)) }
        } else {
            val today = remember { java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis }
            LazyColumn(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
                lines.groupBy { if (it.at >= today) "Today" else if (it.at >= today - 6 * DAY) "This week" else "Earlier" }.forEach { (name, rows) ->
                    item(key = "h:$name") { Text(name.uppercase(), Modifier.padding(top = 10.dp, bottom = 2.dp), style = Type.overline) }
                    items(rows, key = { it.key }) { NoticeRow(it) }
                }
            }
        }
    }
    first.claim()
}

@Composable
private fun NoticeRow(line: Line) {
    val mark = if (line.fromAniList) ANILIST_BLUE else Ink.accent
    // Every row takes focus, so the list scrolls; one that opens nothing does nothing.
    Focusable(line.open ?: {}, Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), 1.02f) {
        Row(Modifier.fillMaxWidth().background(if (line.unread) Ink.surface2 else Ink.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 48.dp, height = if (line.image != null) 66.dp else 48.dp).clip(RoundedCornerShape(10.dp)).background(mark.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                if (line.image != null) coil3.compose.AsyncImage(line.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Lc(if (line.key.startsWith("w:ep:")) L.drawable.lucide_ic_tv else L.drawable.lucide_ic_bell, tint = mark, size = 22.dp)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                line.title?.let { Text(it, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(line.text, style = Type.meta.copy(color = if (line.title == null) Ink.text else Ink.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text((if (line.fromAniList) AniList.NAME else "Watchly") + " · " + ago(line.at), Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint))
            }
            if (line.unread) Box(Modifier.padding(start = 10.dp).size(10.dp).clip(CircleShape).background(mark))
        }
    }
}

/** "now", "5 min ago", "3 h ago", "2 d ago". */
private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} d ago"
    }
}
