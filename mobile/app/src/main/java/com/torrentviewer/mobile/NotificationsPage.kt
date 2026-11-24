package com.torrentviewer.mobile

import com.torrentviewer.core.Inbox
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.AniList
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type
import com.torrentviewer.core.Updates
import com.torrentviewer.core.Watch
import com.torrentviewer.core.ago

/** One line of the notification center, from either source. */
private data class Line(val key: String, val title: String?, val text: String, val at: Long, val fromAniList: Boolean, val icon: Int, val image: String?, val unread: Boolean, val open: () -> Unit)

/** The bell on Home, with a count while either source has something unseen. */
@Composable
fun NotificationBell(solid: Float, onClick: () -> Unit, haze: dev.chrisbanes.haze.HazeState? = null) {
    val items by Inbox.items.collectAsState()
    val readAt by Inbox.readAt.collectAsState()
    val aniUnread by AniList.unread.collectAsState()
    val count = aniUnread + items.count { it.at > readAt }
    HeaderTile(L.drawable.lucide_ic_bell, if (count > 0) "Notifications, $count new" else "Notifications", solid, onClick, haze) {
        if (count > 0) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).height(18.dp).widthIn(min = 18.dp)
                .clip(CircleShape).background(Ink.accent).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 9) "9+" else "$count", style = Type.meta.copy(color = Ink.onAccent, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 10.sp))
        }
    }
}

/**
 * One of Home's header buttons, shaped like the avatar beside them: a glass tile over the art ([solid] 0)
 * that turns the page's own surface as the bar fills in (1). [extra] draws over it, a badge say. Given the
 * page's [haze], the glass is frosted: what's behind it blurred under the tint, with a hairline edge over art.
 */
@Composable
fun HeaderTile(icon: Int, description: String, solid: Float, onClick: () -> Unit, haze: dev.chrisbanes.haze.HazeState? = null, extra: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val white = androidx.compose.ui.graphics.Color.White
    val fill = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color.Black.copy(alpha = if (haze != null) 0.22f else 0.38f), Ink.surface2.copy(alpha = 0.75f), solid)
    val tint = androidx.compose.ui.graphics.lerp(white, Ink.text, solid)
    val shape = RoundedCornerShape(9.dp)
    val page = Ink.bg
    Box(Modifier.size(40.dp).semantics { contentDescription = description }) {
        Tap(onClick, Modifier.matchParentSize(), shape) {
            Box(
                Modifier.matchParentSize()
                    .then(
                        if (haze == null) Modifier.background(fill)
                        else Modifier.hazeEffect(haze) {
                            backgroundColor = page
                            tints = listOf(dev.chrisbanes.haze.HazeTint(fill))
                            blurRadius = 16.dp
                            noiseFactor = 0f
                        },
                    )
                    .border(1.dp, white.copy(alpha = 0.16f * (1f - solid)), shape),
                contentAlignment = Alignment.Center,
            ) {
                Lc(icon, tint = tint, size = 20.dp)
            }
        }
        extra()
    }
}

/** Watchly's own notifications and AniList's, newest first; opening it marks both read. */
@Composable
fun NotificationsScreen(watch: Watch, onLink: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val notes by Inbox.items.collectAsState()
    val connected by AniList.connected.collectAsState()
    // As they were on opening: what is new stays marked while the page is up.
    val seenBefore = remember { Inbox.readAt.value }
    val aniUnread = remember { AniList.unread.value }
    var ani by remember { mutableStateOf<List<AniList.Notice>?>(null) }
    var aniError by remember { mutableStateOf<String?>(null) }
    var only by rememberSaveable { mutableStateOf<Boolean?>(null) } // null all, false Watchly, true AniList
    LaunchedEffect(Unit) { Inbox.markRead() }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        runCatching { AniList.notices(read = true) }.fold({ ani = it }, { aniError = "Couldn't reach AniList" })
    }

    val lines = buildList {
        notes.forEach { n ->
            val icon = when {
                n.id.startsWith("ep:") -> L.drawable.lucide_ic_tv
                n.id.startsWith("dl:") -> L.drawable.lucide_ic_download
                n.id.startsWith("update:") -> L.drawable.lucide_ic_circle_arrow_up
                else -> L.drawable.lucide_ic_bell
            }
            add(Line("w:${n.id}", n.title, n.text, n.at, false, icon, null, n.at > seenBefore) {
                when {
                    n.link != null -> onLink(n.link!!)
                    n.id.startsWith("update:") -> Updates.release.value?.takeIf { "update:${it.version}" == n.id }
                        ?.let { r -> downloadUpdate(context, r) { watch.notes.tryEmit(it) } }
                        ?: watch.notes.tryEmit("That version is no longer the latest")
                }
            })
        }
        ani.orEmpty().forEachIndexed { i, n ->
            add(Line("a:${n.id}", null, n.text, n.at, true, L.drawable.lucide_ic_bell, n.poster, i < aniUnread) {
                val imdb = n.imdb
                if (imdb != null) onLink(
                    android.net.Uri.Builder().scheme("watchly").authority(TITLE_LINK_HOST).appendPath(imdb)
                        .appendQueryParameter("kind", n.kind ?: "tv").apply { n.season?.let { appendQueryParameter("s", it.toString()) } }.build().toString(),
                )
                else openUrl(context, n.url)
            })
        }
    }.filter { only == null || it.fromAniList == only }.sortedByDescending { it.at }

    val fresh = lines.count { it.unread }
    // Today, the week before it, and the rest: the way a phone's own notification history reads.
    val today = remember { java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis }
    val sections = lines.groupBy { if (it.at >= today) "Today" else if (it.at >= today - 6 * DAY) "This week" else "Earlier" }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = EDGE).padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Notifications", style = Type.title.copy(fontWeight = FontWeight.Bold))
                Text(if (fresh > 0) "$fresh new" else "All caught up", style = Type.meta.copy(color = if (fresh > 0) Ink.accent else Ink.faint))
            }
            if (notes.isNotEmpty() && only != true) Chip("Clear", false, { Inbox.clear() }, icon = L.drawable.lucide_ic_trash_2, compact = true)
        }
        if (connected) Row(Modifier.padding(horizontal = EDGE).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All", only == null, { only = null })
            Chip("Watchly", only == false, { only = false })
            Chip(AniList.NAME, only == true, { only = true })
        }
        if (lines.isEmpty() && (ani != null || aniError != null || !connected || only == false)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp).padding(bottom = 80.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Ink.surface2), contentAlignment = Alignment.Center) {
                    Lc(L.drawable.lucide_ic_bell, tint = Ink.muted, size = 32.dp)
                }
                Text("No notifications yet", Modifier.padding(top = 16.dp), style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    "New episodes of shows you follow, finished downloads and updates show here" + if (connected) ", with your AniList notifications." else ". Sign in to AniList in Settings to see its notifications too.",
                    Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.faint), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                aniError?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.danger)) }
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 32.dp + LocalTabBarInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (connected && only != false && (ani == null || aniError != null)) item(key = "ani-state") {
                Text(aniError ?: "Asking AniList…", style = Type.meta.copy(color = if (aniError != null) Ink.danger else Ink.faint), modifier = Modifier.padding(bottom = 4.dp))
            }
            sections.forEach { (name, rows) ->
                item(key = "h:$name") {
                    Text(name.uppercase(), Modifier.padding(top = 10.dp, bottom = 2.dp), style = Type.meta.copy(color = Ink.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp))
                }
                items(rows, key = { it.key }) { line -> NoticeRow(line) }
            }
        }
    }
}

private const val DAY = 24 * 60 * 60 * 1000L
/** AniList's own blue, so its rows read as its at a glance. */
private val ANILIST_BLUE = androidx.compose.ui.graphics.Color(0xFF02A9FF)

@Composable
private fun NoticeRow(line: Line) {
    val shape = RoundedCornerShape(14.dp)
    val mark = if (line.fromAniList) ANILIST_BLUE else Ink.accent
    Tap(line.open, Modifier.fillMaxWidth(), shape) {
        Row(Modifier.background(if (line.unread) Ink.surface2 else Ink.surface).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            // A cover is a poster: taller than wide. An icon sits on its source's colour, faint.
            val tile = if (line.image != null) Modifier.size(width = 44.dp, height = 60.dp) else Modifier.size(44.dp)
            Box(tile.clip(RoundedCornerShape(10.dp)).background(mark.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                if (line.image != null) coil3.compose.AsyncImage(line.image, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Lc(line.icon, tint = mark, size = 20.dp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                line.title?.let { Text(it, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(line.text, style = if (line.title == null) Type.body.copy(fontWeight = if (line.unread) FontWeight.Medium else FontWeight.Normal) else Type.meta.copy(color = Ink.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(mark))
                    Text((if (line.fromAniList) AniList.NAME else "Watchly") + " · " + ago(line.at), Modifier.padding(start = 6.dp), style = Type.meta.copy(color = Ink.faint))
                }
            }
            if (line.unread) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(mark))
            }
        }
    }
}
