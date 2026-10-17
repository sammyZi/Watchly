package com.torrentviewer.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Hit
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Note
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.ago
import com.torrentviewer.core.bytes
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.speed
import com.composables.icons.lucide.R as L

/** A release to keep, labelled the way History labels a stream of it. */
fun downloadJob(hit: Hit, from: Title?, episode: Episode? = null) = DownloadJob(
    uri = hit.magnet,
    title = hit.name,
    titleName = if (episode != null && from != null) "${from.title} · ${episodeTag(episode.season, episode.episode)}" else from?.title,
    poster = from?.poster,
    imdb = from?.imdb,
    kind = from?.kind,
    tags = listOfNotNull(episode?.let { episodeTag(it.season, it.episode) }),
)

/** What is downloading, and what is kept on this phone to watch without a connection. */
@Composable
fun DownloadsScreen(watch: Watch) {
    val live by Downloads.live.collectAsState()
    val finished by Downloads.finished.collectAsState()
    var cancelling by remember { mutableStateOf<LiveDownload?>(null) }
    var deleting by remember { mutableStateOf<Finished?>(null) }

    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 24.dp)) {
        item { Heading("Downloads") }
        if (live.isEmpty() && finished.isEmpty()) item {
            Note("Nothing downloaded yet. Download from a title's page, and it plays here without a connection.")
        }
        if (live.isNotEmpty()) item { Label("Downloading") }
        items(live, key = { "live-" + it.job.uri }) { row ->
            ActiveRow(row, onPause = { Downloads.pause(row.job.uri) }, onResume = { Downloads.resume(row.job.uri) }, onCancel = { cancelling = row })
        }
        if (finished.isNotEmpty()) item { Label("On this phone  ·  ${bytes(finished.sumOf { it.size })}") }
        items(finished, key = { "done-" + it.path }) { f ->
            Tap(
                {
                    val from = f.imdb?.let { Title(it, f.kind ?: "movie", f.label.substringBefore(" · "), poster = f.poster) }
                    watch.playFile(f.path, f.label, from, f.poster)
                },
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                RoundedCornerShape(8.dp),
                onLongClick = { deleting = f },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(f.poster, f.label, Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(f.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${bytes(f.size)}  ·  ${ago(f.at)}", Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 12.sp))
                    }
                    Lc(L.drawable.lucide_ic_play, tint = Ink.text, size = 20.dp)
                    Tap({ deleting = f }, shape = CircleShape) { Lc(L.drawable.lucide_ic_trash_2, Modifier.padding(10.dp), tint = Ink.muted, size = 20.dp) }
                }
            }
        }
    }

    cancelling?.let { row ->
        Sheet(
            title = "Stop downloading ${row.job.label}?",
            options = listOf(Option(true, "Stop and delete what has arrived", "Files already saved stay on this phone"), Option(false, "Keep downloading")),
            selected = null,
            onPick = {
                if (it) Downloads.cancel(row.job.uri)
                cancelling = null
            },
            onDismiss = { cancelling = null },
        )
    }
    deleting?.let { f ->
        Sheet(
            title = "Delete ${f.label}?",
            options = listOf(Option(true, "Delete from this phone", bytes(f.size)), Option(false, "Keep it")),
            selected = null,
            onPick = {
                if (it) Downloads.delete(f)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 6.dp, bottom = 10.dp), style = Type.overline.copy(fontSize = 12.sp))
}

@Composable
private fun ActiveRow(row: LiveDownload, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    val pct = (row.ratio * 100).toInt()
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(row.job.poster, row.job.label, Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.job.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when (row.state) {
                        DlState.RESOLVING -> "Finding peers…"
                        DlState.DOWNLOADING -> listOf("$pct%", speed(row.speed), "${row.peers} peers", "${bytes(row.done)} of ${bytes(row.total)}").joinToString("  ·  ")
                        DlState.PAUSED -> "Paused  ·  $pct%"
                        DlState.WIFI -> "Waiting for Wi-Fi  ·  $pct%"
                        DlState.FAILED -> row.error ?: "Failed"
                    },
                    Modifier.padding(top = 2.dp),
                    style = Type.meta.copy(fontSize = 12.sp, color = if (row.state == DlState.FAILED) Ink.danger else Ink.muted),
                    maxLines = 2,
                )
            }
            when (row.state) {
                DlState.PAUSED, DlState.FAILED -> Tap(onResume, shape = CircleShape) { Lc(L.drawable.lucide_ic_play, Modifier.padding(10.dp), tint = Ink.text, size = 20.dp) }
                DlState.WIFI -> Unit
                else -> Tap(onPause, shape = CircleShape) { Lc(L.drawable.lucide_ic_pause, Modifier.padding(10.dp), tint = Ink.text, size = 20.dp) }
            }
            Tap(onCancel, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(10.dp), tint = Ink.muted, size = 20.dp) }
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(row.ratio, height = 3.dp)
        // Waiting is a choice the viewer can overrule for this one download.
        if (row.state == DlState.WIFI) Chip("Use mobile data", false, onClick = onResume, Modifier.padding(top = 10.dp))
        // A pack: each episode and how far it has got.
        if (row.files.size > 1) {
            Column(Modifier.padding(start = 60.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.files.forEach { f ->
                    val ep = parseEpisode(f.name)?.let { "S${it.season}:E${it.episode}" } ?: f.name
                    Text(
                        "$ep  ·  " + if (f.saved) "Saved" else "${if (f.size > 0) f.done * 100 / f.size else 0}%",
                        style = Type.meta.copy(fontSize = 12.sp, color = if (f.saved) Ink.good else Ink.faint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
