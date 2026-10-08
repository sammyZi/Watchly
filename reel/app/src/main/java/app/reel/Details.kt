package app.reel

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(opened: Opened, shared: Shared, back: () -> Unit, play: (Torrent, TorrentFile, Long) -> Unit) {
    val torrent by opened.torrent.collectAsState()
    val error by opened.error.collectAsState()
    val entries by Library.entries.collectAsState()
    val entry = entries.find { it.source == opened.source }
    val art = entry?.art
    val title = entry?.title ?: nameOf(opened.source)

    var files by remember { mutableStateOf(torrent?.files().orEmpty()) }
    LaunchedEffect(torrent) {
        val t = torrent ?: return@LaunchedEffect
        while (files.isEmpty()) {
            val found = withContext(Dispatchers.IO) { t.files() }
            val info = t.info()
            if (found.isNotEmpty() && info != null) {
                files = found
                Library.update(opened.source) { (it ?: Entry(opened.source, info.name())).copy(name = info.name(), size = info.totalSize()) }
                // The real name is a better search than a magnet's dn=, or a hash.
                launch { findArt(opened.source, info.name()) }
            } else {
                delay(500)
            }
        }
    }

    val sorted = remember(files) { files.sortedWith(compareByDescending<TorrentFile> { it.playable }.thenByDescending { it.size }) }
    val main = sorted.firstOrNull { it.playable }
    val resumeAt = if (Library.resume && entry != null && entry.resumable && entry.fileIndex == main?.index) entry.position else 0L

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                // 4:3 on a phone; on a wide screen capped, or the backdrop alone fills it.
                val screen = androidx.compose.ui.platform.LocalConfiguration.current
                Box(Modifier.fillMaxWidth().height(minOf(screen.screenWidthDp * 0.75f, screen.screenHeightDp * 0.55f).dp)) {
                    val backdrop = art?.backdrop
                    Artwork(
                        backdrop ?: art?.poster,
                        title,
                        Modifier.fillMaxSize()
                            .shared("backdrop-${opened.source}", shared)
                            // No backdrop: the poster, blurred, still gives the page its colour.
                            .then(if (backdrop == null && Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier),
                        RoundedCornerShape(0.dp),
                        fallbackText = false,
                    )
                    Box(Modifier.fillMaxSize().background(scrim(0.25f)))
                }
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp).pullUp(96.dp), verticalAlignment = Alignment.Bottom) {
                    Artwork(art?.poster, title, Modifier.width(112.dp).aspectRatio(2 / 3f).shared("poster-${opened.source}", shared))
                    Column(Modifier.padding(start = 16.dp, bottom = 4.dp)) {
                        Text(title, style = Type.h1, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(art?.year, art?.runtime, art?.kind?.let { if (it == "series") "Series" else "Film" })
                        if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), Modifier.padding(top = 6.dp), style = Type.small)
                        art?.rating?.let { Box(Modifier.padding(top = 8.dp)) { ImdbBadge(it) } }
                    }
                }
            }
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                    if (!art?.genres.isNullOrEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            art!!.genres.forEach { Chip(it) }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    // No swarm numbers on the title page — only an error, if opening failed.
                    AnimatedVisibility(error != null, enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial()), exit = fadeOut(Motion.effects()) + shrinkVertically(Motion.spatial())) {
                        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Lc(L.drawable.lucide_ic_circle_alert, tint = Ink.error, size = 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(error.orEmpty(), style = Type.small.copy(color = Ink.error))
                        }
                    }
                    PlayActions(torrent, main, files.isEmpty(), error != null, resumeAt, entry) { file, at -> torrent?.let { play(it, file, at) } }
                    art?.plot?.let { Plot(it) }
                }
            }
            if (sorted.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Files", Modifier.weight(1f), style = Type.h2)
                        Text("${files.size} · ${bytes(files.sumOf { it.size })}", style = Type.small.copy(color = Ink.text3))
                    }
                }
                itemsIndexed(sorted, key = { _, f -> f.index }) { i, f ->
                    FileRow(f, f == main, Modifier.enter(i)) { torrent?.let { t -> play(t, f, 0) } }
                }
            }
            item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
        IconCircle(L.drawable.lucide_ic_arrow_left, "Back", Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp), glass = true, onClick = back)
    }
}

@Composable
private fun PlayActions(torrent: Torrent?, main: TorrentFile?, loading: Boolean, failed: Boolean, resumeAt: Long, entry: Entry?, play: (TorrentFile, Long) -> Unit) {
    AnimatedContent(
        Triple(loading, main != null, resumeAt > 0),
        transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) },
        label = "actions",
    ) { (isLoading, hasMain, canResume) ->
        Column {
            when {
                failed -> PrimaryButton("Couldn't open", Modifier.fillMaxWidth(), enabled = false) {}
                isLoading || torrent == null -> PrimaryButton("Fetching files…", Modifier.fillMaxWidth(), enabled = false, busy = true) {}
                !hasMain -> PrimaryButton("Nothing playable", Modifier.fillMaxWidth(), enabled = false) {}
                canResume -> {
                    PrimaryButton("Resume · ${left(entry!!.length - resumeAt)}", Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_play) { play(main!!, resumeAt) }
                    ProgressLine(entry.progress, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), track = Ink.surface2)
                    SecondaryButton("Start from the beginning", Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_rotate_ccw) { play(main!!, 0) }
                }
                else -> PrimaryButton("Play", Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_play) { play(main!!, 0) }
            }
        }
    }
}

/** Three lines, the rest a tap away. */
@Composable
private fun Plot(text: String) {
    var open by remember { mutableStateOf(false) }
    Text(
        text,
        Modifier.padding(top = 20.dp).animateContentSize(Motion.spatial()).press(pressedScale = 0.99f) { open = !open },
        style = Type.body,
        maxLines = if (open) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FileRow(file: TorrentFile, main: Boolean, modifier: Modifier, onPlay: () -> Unit) {
    val subtitle = file.name.substringAfterLast('.', "").lowercase() in setOf("srt", "ass", "ssa", "vtt", "sub")
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (file.playable) 1f else 0.45f)
            .press(enabled = file.playable, pressedScale = 0.98f, onClick = onPlay)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            when {
                file.playable -> L.drawable.lucide_ic_film
                subtitle -> L.drawable.lucide_ic_captions
                else -> L.drawable.lucide_ic_file
            },
            tint = if (main) Ink.accent else Ink.text2,
            background = if (main) Ink.accentSoft else Ink.surface,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(file.name, style = Type.title.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(bytes(file.size), style = Type.caption)
                if (main) {
                    Text(
                        "Main",
                        Modifier.padding(start = 8.dp).clip(CircleShape).background(Ink.accentSoft).padding(horizontal = 8.dp, vertical = 1.dp),
                        style = Type.caption.copy(color = Ink.accent, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        if (file.playable) Lc(L.drawable.lucide_ic_circle_play, tint = if (main) Ink.accent else Ink.text3, size = 22.dp)
    }
}

/** Draws [by] higher and gives that much height back, so the next item isn't pushed down. */
private fun Modifier.pullUp(by: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val px = by.roundToPx()
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
}
