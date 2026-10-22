package com.torrentviewer.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
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
import com.torrentviewer.core.absoluteNumber
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.pickTag
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
    tags = listOfNotNull(episode?.let { pickTag(it.season, it.episode, from?.let { t -> absoluteNumber(t.episodes, it.season, it.episode) }) }),
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
        // Settings has it too; here is where it is looked for. A change applies to running downloads.
        item {
            var sequential by remember { mutableStateOf(Downloads.sequential) }
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Fastest", !sequential, onClick = { sequential = false; Downloads.sequential = false })
                Chip("In order", sequential, onClick = { sequential = true; Downloads.sequential = true })
            }
            Note(
                if (sequential) "In order: each file fills from the start, so it can be watched while it downloads. Slower."
                else "Fastest: pieces from wherever the swarm has them. Switch to In order to watch while it downloads.",
                Modifier.padding(bottom = 8.dp),
            )
        }
        if (live.isEmpty() && finished.isEmpty()) item {
            Note("Nothing downloaded yet. Download from a title's page, and it plays here without a connection.")
        }
        if (live.isNotEmpty()) item { Label("Downloading") }
        items(live, key = { "live-" + it.job.uri }) { row ->
            ActiveRow(
                row,
                onPause = { Downloads.pause(row.job.uri) },
                onResume = { Downloads.resume(row.job.uri) },
                onCancel = { cancelling = row },
                onWanted = { index, on -> Downloads.setWanted(row.job.uri, index, on) },
            )
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

/**
 * One download as a card. A pack folds its episodes away under a count; opened, they are
 * listed in episode order, each with its own progress and a circle to skip it or put it back.
 */
@Composable
private fun ActiveRow(row: LiveDownload, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onWanted: (Int, Boolean) -> Unit) {
    val pct = (row.ratio * 100).toInt()
    val pack = row.files.size > 1
    val wanted = row.files.filter { it.wanted }
    val savedCount = wanted.count { it.saved }
    var open by rememberSaveable(row.job.uri) { mutableStateOf(false) }
    /** Seasons unfolded in a multi-season pack; the one downloading now starts open. */
    var openSeasons by remember(row.job.uri) {
        mutableStateOf(setOf(row.files.firstOrNull { it.wanted && !it.saved }?.let { parseEpisode(it.name)?.season }))
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(Ink.surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(row.job.poster, row.job.label, Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.job.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when (row.state) {
                        DlState.RESOLVING -> "Finding peers…"
                        DlState.DOWNLOADING -> listOfNotNull("$pct%", speed(row.speed), "${row.peers} peers").joinToString("  ·  ")
                        DlState.PAUSED -> "Paused  ·  $pct%"
                        DlState.WIFI -> "Waiting for Wi-Fi  ·  $pct%"
                        DlState.FAILED -> row.error ?: "Failed"
                    },
                    Modifier.padding(top = 2.dp),
                    style = Type.meta.copy(fontSize = 12.sp, color = if (row.state == DlState.FAILED) Ink.danger else Ink.muted),
                    maxLines = 2,
                )
                if (row.total > 0) Text(
                    listOfNotNull(if (pack) "$savedCount of ${wanted.size} episodes saved" else null, "${bytes(row.done)} of ${bytes(row.total)}").joinToString("  ·  "),
                    style = Type.meta.copy(fontSize = 12.sp, color = Ink.faint),
                    maxLines = 1,
                )
            }
            when (row.state) {
                DlState.PAUSED, DlState.FAILED -> Tap(onResume, shape = CircleShape) { Lc(L.drawable.lucide_ic_play, Modifier.padding(10.dp), tint = Ink.text, size = 20.dp) }
                DlState.WIFI -> Unit
                else -> Tap(onPause, shape = CircleShape) { Lc(L.drawable.lucide_ic_pause, Modifier.padding(10.dp), tint = Ink.text, size = 20.dp) }
            }
            Tap(onCancel, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(10.dp), tint = Ink.muted, size = 20.dp) }
        }
        Spacer(Modifier.height(10.dp))
        ProgressLine(row.ratio, height = 3.dp)
        // Waiting is a choice the viewer can overrule for this one download.
        if (row.state == DlState.WIFI) Chip("Use mobile data", false, onClick = onResume, Modifier.padding(top = 10.dp))

        if (pack) {
            Tap({ open = !open }, Modifier.fillMaxWidth().padding(top = 6.dp), RoundedCornerShape(6.dp)) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Episodes  ·  ${wanted.size} of ${row.files.size} chosen", Modifier.weight(1f), style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold))
                    Lc(if (open) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, tint = Ink.muted, size = 18.dp)
                }
            }
            if (open) {
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All", wanted.size == row.files.size, onClick = { row.files.filter { !it.wanted }.forEach { onWanted(it.index, true) } })
                    // Skips everything not on the phone yet; what is saved stays.
                    Chip("None", wanted.none { !it.saved }, onClick = { row.files.filter { it.wanted && !it.saved }.forEach { onWanted(it.index, false) } })
                }
                val next = wanted.firstOrNull { !it.saved }?.index
                // Seasons as their own groups: a complete-series pack is a hundred lines otherwise.
                val seasons = row.files.groupBy { parseEpisode(it.name)?.season }
                if (seasons.size <= 1) {
                    row.files.forEach { f -> EpisodeLine(f, isNext = f.index == next && row.state == DlState.DOWNLOADING) { onWanted(f.index, !f.wanted) } }
                } else {
                    seasons.forEach { (season, files) ->
                        SeasonGroup(
                            season,
                            files,
                            expanded = season in openSeasons,
                            onExpand = { openSeasons = if (season in openSeasons) openSeasons - season else openSeasons + season },
                            onWanted = onWanted,
                        ) { f -> EpisodeLine(f, isNext = f.index == next && row.state == DlState.DOWNLOADING) { onWanted(f.index, !f.wanted) } }
                    }
                }
            }
        }
    }
}

/**
 * One season of a pack: a circle that keeps or skips the whole season, its name, how many
 * of its chosen episodes are saved, and a fold to show or hide them.
 */
@Composable
private fun SeasonGroup(
    season: Int?,
    files: List<DlFile>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onWanted: (Int, Boolean) -> Unit,
    episode: @Composable (DlFile) -> Unit,
) {
    val chosen = files.filter { it.wanted }
    val all = chosen.size == files.size
    val saved = chosen.count { it.saved }
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // All on → skip what is not saved yet; anything off → take the whole season.
            Tap({ files.filter { if (all) !it.saved else !it.wanted }.forEach { onWanted(it.index, !all) } }, shape = CircleShape) {
                Lc(
                    if (chosen.isEmpty()) L.drawable.lucide_ic_circle else if (all) L.drawable.lucide_ic_circle_check else L.drawable.lucide_ic_circle_dashed,
                    Modifier.padding(6.dp),
                    tint = if (chosen.isEmpty()) Ink.faint else Ink.accent,
                    size = 20.dp,
                )
            }
            Tap(onExpand, Modifier.weight(1f), RoundedCornerShape(6.dp)) {
                Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(season?.let { if (it == 0) "Specials" else "Season $it" } ?: "Other files", Modifier.weight(1f), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        if (chosen.isEmpty()) "Skipped" else "$saved of ${chosen.size} saved",
                        Modifier.padding(end = 6.dp),
                        style = Type.meta.copy(fontSize = 12.sp, color = if (chosen.isNotEmpty() && saved == chosen.size) Ink.good else Ink.faint),
                    )
                    Lc(if (expanded) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, tint = Ink.muted, size = 18.dp)
                }
            }
        }
        if (expanded) Column(Modifier.padding(start = 12.dp)) { files.forEach { episode(it) } }
    }
}

/** An episode in a pack: a circle to keep or skip it, its tag, a thin bar, and where it stands. */
@Composable
private fun EpisodeLine(f: DlFile, isNext: Boolean, onToggle: () -> Unit) {
    val ep = parseEpisode(f.name)?.let { "S${it.season}:E${it.episode}" } ?: f.name
    val pct = if (f.size > 0) (f.done * 100 / f.size).toInt() else 0
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // A saved episode is on the phone already; there is nothing to skip.
        Tap({ if (!f.saved) onToggle() }, shape = CircleShape) {
            Lc(
                when {
                    f.saved -> L.drawable.lucide_ic_circle_check
                    f.wanted -> L.drawable.lucide_ic_circle_check
                    else -> L.drawable.lucide_ic_circle
                },
                Modifier.padding(6.dp),
                tint = when {
                    f.saved -> Ink.good
                    f.wanted -> Ink.accent
                    else -> Ink.faint
                },
                size = 20.dp,
            )
        }
        Text(ep, Modifier.width(64.dp).padding(start = 4.dp), style = Type.meta.copy(color = if (f.wanted) Ink.text else Ink.faint, fontWeight = FontWeight.SemiBold), maxLines = 1)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (f.wanted && !f.saved) ProgressLine(pct / 100f, height = 3.dp)
        }
        Text(
            when {
                f.saved -> "Saved"
                !f.wanted -> "Skipped"
                isNext -> "$pct%  ·  next"
                else -> "$pct%"
            },
            Modifier.width(84.dp),
            style = Type.meta.copy(fontSize = 12.sp, color = if (f.saved) Ink.good else Ink.faint, textAlign = TextAlign.End),
            maxLines = 1,
        )
    }
}
