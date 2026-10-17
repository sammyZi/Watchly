package com.torrentviewer.tv

import com.torrentviewer.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** The exact strings quality() returns, best first. */
private val QUALITY_ORDER = listOf("4K", "1080p", "720p", "CAM")

/** Past forty it is D-pad scrolling nobody finishes. */
private const val MAX_SOURCES = 40

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(initial: Title, watch: Watch, onOpen: (Title) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember(initial.imdb) { mutableStateOf(initial) }
    LaunchedEffect(initial.imdb) { Meta.title(initial.imdb, initial.kind)?.let { title = it } }
    val saved by SavedStore.items.collectAsState()
    val inList = saved.any { it.imdb == title.imdb && it.list == "later" }

    val seasons = remember(title.episodes) { title.episodes.map { it.season }.filter { it > 0 }.distinct().sorted() }
    var season by remember(title.imdb) { mutableStateOf<Int?>(null) }
    LaunchedEffect(seasons) { if (season == null || season !in seasons) season = seasons.firstOrNull() }
    val episodes = title.episodes.filter { it.season == season }

    var episode by remember(title.imdb) { mutableStateOf<Episode?>(null) }
    var hits by remember(title.imdb) { mutableStateOf<List<Hit>?>(null) }
    var busy by remember(title.imdb) { mutableStateOf(false) }
    var quality by remember(title.imdb) { mutableStateOf<String?>(null) }
    /** Which request the visible sources belong to; answers arrive out of order. */
    var ticket by remember { mutableIntStateOf(0) }
    val qualities = hits.orEmpty().mapNotNull { it.quality }.distinct().sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 9 else i } }
    val shown = hits.orEmpty().filter { quality == null || it.quality == quality }

    val list = rememberLazyListState()
    val play = remember { FocusRequester() }

    fun findSources(ep: Episode?): kotlinx.coroutines.Deferred<List<Hit>> {
        val mine = ++ticket
        busy = true
        hits = null
        quality = null
        episode = ep
        val run: suspend (OnHits) -> List<Hit> = { onHits ->
            try {
                if (ep != null) searchEpisode(title.title, ep.season, ep.episode, title.imdb, onHits)
                else searchTorrents(title.title, title.imdb, title.year?.take(4), onHits = onHits)
            } finally {
                if (mine == ticket) busy = false
            }
        }
        return scope.quickest(run) { partial -> if (mine == ticket) hits = partial }.also { d ->
            scope.launch { val all = d.await(); if (mine == ticket && hits.isNullOrEmpty()) hits = all }
        }
    }

    // A film has one thing to look for: look on arrival.
    LaunchedEffect(title.imdb, title.kind) { if (!title.isSeries) findSources(null) }

    /** What the big button plays: the episode being looked at, or the first of the season. */
    val upNext = episode ?: episodes.firstOrNull()

    fun playEpisode(ep: Episode) = scope.launch {
        val ready = if (episode?.id == ep.id && shown.isNotEmpty()) shown else findSources(ep).await()
        ready.firstOrNull()?.let { watch.stream(it, title, episodeTag(ep.season, ep.episode), ep) }
            ?: watch.notes.tryEmit("No sources found for ${episodeTag(ep.season, ep.episode)}.")
    }

    Box(Modifier.fillMaxSize()) {
        Artwork(title.wide, title.title, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.8f).fillMaxHeight(0.82f).heroScrims(), fill = false)

        // Scroll only what would be off-screen: the TV default pivots focus to a third of
        // the way down, which pushed the logo off the top when the page opened.
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        // The page moves a section at a time, as Netflix's does: the header in full at the
        // top, or the season row and its episodes in full. A free scroll left both cut in
        // half after going down to the episodes and back up.
        val hasEpisodes = title.isSeries && seasons.isNotEmpty()
        val sourcesAt = if (hasEpisodes) 2 else 1
        val topPad = with(LocalDensity.current) { (Safe.y + 72.dp).roundToPx() }
        fun pin(index: Int) {
            // Past the list's top padding (the room above the logo), or the section above
            // would still show its last lines over this one.
            scope.launch { list.animateScrollToItem(index, if (index == 0) 0 else topPad) }
        }
        // Room below the last section, so any section can snap to the top of the screen.
        LazyColumn(state = list, contentPadding = PaddingValues(top = Safe.y + 72.dp, bottom = 420.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "head") {
                Column(Modifier.onFocusEnter { pin(0) }.padding(start = Safe.x + 12.dp, end = Safe.x).widthIn(max = 600.dp)) {
                    TitleMark(title, Modifier.widthIn(max = 440.dp), maxHeight = 110.dp)
                    Facts(title, Modifier.padding(top = 16.dp))
                    title.plot?.let { Text(it, Modifier.padding(top = 12.dp), style = Type.body.copy(color = Ink.text.copy(alpha = 0.82f)), maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    if (title.cast.isNotEmpty()) People("Starring", title.cast.take(4).joinToString(", "))
                    if (title.directors.isNotEmpty()) People(if (title.isSeries) "Creators" else "Director", title.directors.take(2).joinToString(", "))
                    title.awards?.let { People("Awards", it) }
                    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!title.isSeries) {
                            // Never disabled: a disabled view cannot hold focus, and the remote would open elsewhere.
                            TvButton(
                                label = when {
                                    shown.isNotEmpty() -> "Play"
                                    busy -> "Finding sources…"
                                    else -> "No sources"
                                },
                                onClick = { shown.firstOrNull()?.let { watch.stream(it, title) } },
                                icon = L.drawable.lucide_ic_play,
                                primary = true,
                                requester = play,
                            )
                        } else if (upNext != null) {
                            TvButton(
                                label = if (busy && episode?.id == upNext.id && shown.isEmpty()) "Finding sources…" else "Play S${upNext.season}:E${upNext.episode}",
                                onClick = { playEpisode(upNext) },
                                icon = L.drawable.lucide_ic_play,
                                primary = true,
                                requester = play,
                            )
                        }
                        TvButton(
                            label = if (inList) "In My List" else "My List",
                            onClick = { SavedStore.toggle(title) },
                            icon = if (inList) L.drawable.lucide_ic_check else L.drawable.lucide_ic_plus,
                            requester = if (title.isSeries && upNext == null) play else null,
                        )
                    }
                }
                play.claim(title.imdb to (upNext != null))
                // Open at the top, logo and buttons in view, whatever focus scrolled.
                LaunchedEffect(title.imdb, upNext != null) { list.scrollToItem(0) }
            }

            if (hasEpisodes) {
                // Seasons and episodes are one section, so both are on screen together.
                item(key = "episodes") {
                    Column(Modifier.onFocusEnter { pin(1) }.padding(top = Safe.y)) {
                        Text("Episodes", Modifier.padding(start = Safe.x + 12.dp, bottom = 14.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
                        LazyRow(contentPadding = PaddingValues(start = Safe.x + 12.dp, end = Safe.x), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(seasons) { s ->
                                Chip("Season $s", s == season, onClick = {
                                    season = s
                                    // Retire whatever search is out: it belongs to the season being left.
                                    ticket++
                                    busy = false
                                    hits = null
                                    episode = null
                                })
                            }
                        }
                        key(season) {
                            val fallback = remember { FocusRequester() }
                            LazyRow(
                                Modifier.padding(top = 18.dp).focusRestorer(fallback),
                                contentPadding = PaddingValues(start = Safe.x + 12.dp, end = Safe.x),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
                                    EpisodeCard(
                                        ep,
                                        fallback = title.wide,
                                        selected = episode?.id == ep.id,
                                        busy = busy && episode?.id == ep.id,
                                        requester = if (i == 0) fallback else null,
                                        // The sources land just below, already in view; one press down reaches them.
                                        onClick = { findSources(ep) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "sources-head") {
                Column(Modifier.onFocusEnter { pin(sourcesAt) }.padding(start = Safe.x + 12.dp, end = Safe.x, top = 34.dp)) {
                    Text(
                        episode?.let { "Sources for S${it.season}:E${it.episode}" } ?: "Sources",
                        Modifier.padding(bottom = 12.dp),
                        style = Type.title.copy(fontWeight = FontWeight.Bold),
                    )
                    if (qualities.size > 1) {
                        Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Chip("All", quality == null, onClick = { quality = null })
                            qualities.forEach { q -> Chip(q, quality == q, onClick = { quality = q }) }
                        }
                    }
                    when {
                        busy && shown.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(size = 28.dp)
                            Text("   Asking every index…", style = Type.body)
                        }
                        busy -> Note("${shown.size} so far · finding more…", Modifier.padding(bottom = 10.dp))
                        hits?.isEmpty() == true -> Note("Nothing found. Try another episode, or search for the release by name.")
                        hits == null && title.isSeries -> Note("Choose an episode to see what is available.")
                    }
                }
            }
            itemsIndexed(shown.take(MAX_SOURCES), key = { _, it -> "src-" + it.id }) { i, hit ->
                // Arriving from above, the list starts at its heading rather than half in view.
                Source(hit, Modifier.then(if (i == 0) Modifier.onFocusEnter { pin(sourcesAt) } else Modifier).padding(start = Safe.x + 12.dp, end = Safe.x, bottom = 8.dp), runtimeSeconds(title.runtime)) {
                    watch.stream(hit, title, episode?.let { episodeTag(it.season, it.episode) }, episode)
                }
            }
        }
        }
    }
}

@Composable
private fun People(label: String, value: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text("$label: ", style = Type.meta.copy(color = Ink.faint))
        Text(value, style = Type.meta.copy(color = Ink.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Netflix's episode tile: the still, a number and name, and the synopsis under it. */
@Composable
private fun EpisodeCard(ep: Episode, fallback: String?, selected: Boolean, busy: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    Column(Modifier.width(240.dp)) {
        Focusable(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            RoundedCornerShape(8.dp),
            1.07f,
            requester = requester,
        ) {
            Artwork(ep.thumbnail ?: fallback, ep.name, Modifier.fillMaxSize())
            if (busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Spinner(size = 30.dp) }
            if (selected && !busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Ink.accent))
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ep.episode}", style = Type.title.copy(color = Ink.muted, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(10.dp))
            Text(ep.name, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ep.overview?.let { Text(it, Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Past this a release outruns most home connections — and a 100 Mb/s TV port outright. */
private const val HEAVY_MBPS = 40.0
private const val TOO_HEAVY_MBPS = 80.0

/**
 * One release: what it is, how big, and how well it will stream — in words, not a seed count.
 * With [runtimeSeconds], a release too big to stream as it plays says what it needs.
 */
@Composable
fun Source(hit: Hit, modifier: Modifier = Modifier, runtimeSeconds: Double = 0.0, onClick: () -> Unit) {
    val grade = health(hit.seeders)
    // A pack's size is many episodes; only a single file's size over the runtime is a bitrate.
    val mbps = if (runtimeSeconds > 0 && hit.size > 0 && (hit.scope == Scope.EPISODE || hit.perFileSize)) hit.size * 8 / runtimeSeconds / 1_000_000 else 0.0
    val tint = when (grade) {
        Health.FAST, Health.GOOD -> Ink.good
        Health.WEAK -> Ink.warn
        Health.DEAD -> Ink.danger
    }
    Focusable(onClick, modifier.fillMaxWidth().widthIn(max = 860.dp), RoundedCornerShape(8.dp), 1.015f, ring = false) { focused ->
        Row(
            Modifier.background(if (focused) Color.White else Ink.surface.copy(alpha = 0.92f)).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hit.quality?.let {
                Text(
                    it,
                    Modifier.background(if (focused) Color.Black else Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    style = Type.overline.copy(color = Color.White, letterSpacing = 0.5.sp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(hit.name, style = Type.label.copy(color = if (focused) Color.Black else Ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // A film's scope is "Episode" only by default — it says nothing there.
                    listOfNotNull(hit.scope.label.takeIf { hit.kind == "tv" || hit.scope != Scope.EPISODE }, bytes(hit.size), "${hit.seeders} seeds").joinToString("   ·   "),
                    style = Type.meta.copy(color = if (focused) Color.Black.copy(alpha = 0.6f) else Ink.faint),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(grade.label, style = Type.meta.copy(color = if (focused) Color.Black else tint, fontWeight = FontWeight.Bold))
                if (mbps >= HEAVY_MBPS) {
                    Text(
                        "Needs ${mbps.toInt()} Mb/s",
                        style = Type.meta.copy(color = if (focused) Color.Black.copy(alpha = 0.7f) else if (mbps >= TOO_HEAVY_MBPS) Ink.danger else Ink.warn),
                    )
                }
            }
        }
    }
}

