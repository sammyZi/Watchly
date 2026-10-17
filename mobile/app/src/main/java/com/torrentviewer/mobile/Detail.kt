package com.torrentviewer.mobile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import com.torrentviewer.core.awardsOf
import com.torrentviewer.core.castPhoto
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Facts
import com.torrentviewer.core.Health
import com.torrentviewer.core.Hit
import com.torrentviewer.core.History
import com.torrentviewer.core.latestPerTitle
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.NewEpisodes
import com.torrentviewer.core.Note
import com.torrentviewer.core.OnHits
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.Scope
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.health
import com.torrentviewer.core.heroScrims
import com.torrentviewer.core.quickest
import com.torrentviewer.core.reviewLinks
import com.torrentviewer.core.runtimeSeconds
import com.torrentviewer.core.searchEpisode
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.seasonsIn
import com.torrentviewer.core.searchTorrents
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** The exact strings quality() returns, best first. */
private val QUALITY_ORDER = listOf("4K", "1080p", "720p", "CAM")

/** Episodes listed before "Show all". */
private const val FIRST_EPISODES = 10

/** Past this the list is noise; the best are at the top. */
private const val MAX_SOURCES = 60

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/**
 * One title: the TV's page on a phone. Upright, the backdrop heads the page with the logo
 * on it; turned, it sits behind on the right and the words run down the left, as on the TV.
 */
@Composable
fun DetailScreen(initial: Title, watch: Watch, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var title by remember(initial.imdb) { mutableStateOf(initial) }
    LaunchedEffect(initial.imdb) { Meta.title(initial.imdb, initial.kind)?.let { title = it } }
    val saved by SavedStore.items.collectAsState()
    val history by History.items.collectAsState()
    val inList = saved.any { it.imdb == title.imdb && it.list == "later" }
    val liked = saved.any { it.imdb == title.imdb && it.list == "favorite" }

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
    val shown = hits.orEmpty().filter { quality == null || it.quality == quality }
    var sheet by remember { mutableStateOf(false) }
    var reviews by remember { mutableStateOf(false) }
    var expanded by remember(title.imdb) { mutableStateOf(false) }
    var allEpisodes by remember(title.imdb, season) { mutableStateOf(false) }
    /** "Download season" open: the season, and its packs (null while still looking). */
    var seasonPacks by remember(title.imdb) { mutableStateOf<Pair<Int, List<Hit>?>?>(null) }
    val list = rememberLazyListState()

    fun findSources(ep: Episode?): Deferred<List<Hit>> {
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

    /** Keep a release on the phone, in the Downloads tab. */
    fun keep(hit: Hit, ep: Episode?) {
        Downloads.add(downloadJob(hit, title, ep))
        watch.notes.tryEmit("Downloading ${ep?.let { "${title.title} · ${episodeTag(it.season, it.episode)}" } ?: title.title} — see Downloads")
    }

    fun downloadEpisode(ep: Episode) = scope.launch {
        val ready = if (episode?.id == ep.id && shown.isNotEmpty()) shown else findSources(ep).await()
        ready.firstOrNull()?.let { keep(it, ep) } ?: watch.notes.tryEmit("No sources found for ${episodeTag(ep.season, ep.episode)}.")
    }

    /**
     * A whole season in one go: the best pack that is that season, else a complete-series
     * pack — the download then takes only that season's episodes out of it.
     */
    /** A season pack is this season alone, or a complete series that holds it; the download takes only this season's episodes. */
    fun packsOf(hits: List<Hit>, s: Int) = hits.filter {
        (it.scope == Scope.SEASON && parseEpisode(it.name) == null && s in seasonsIn(it.name)) || it.scope == Scope.COMPLETE
    }

    /** The season's packs, listed to pick one from, as they arrive. */
    fun downloadSeason(s: Int) {
        seasonPacks = s to null
        scope.launch {
            val first = title.episodes.firstOrNull { it.season == s }?.episode ?: 1
            val all = runCatching {
                searchEpisode(title.title, s, first, title.imdb) { partial -> if (seasonPacks?.first == s) seasonPacks = s to packsOf(partial, s) }
            }.getOrDefault(emptyList())
            if (seasonPacks?.first == s) seasonPacks = s to packsOf(all, s)
        }
    }

    fun keepSeason(hit: Hit, s: Int) {
        seasonPacks = null
        Downloads.add(downloadJob(hit, title).copy(season = s))
        watch.notes.tryEmit("Downloading season $s of ${title.title} — see Downloads")
    }

    // Opening a series' page is seeing what is out: its new-episode count starts again from here.
    LaunchedEffect(title.imdb, title.episodes.size) { NewEpisodes.markSeen(title) }

    // A film has one thing to look for: look on arrival.
    LaunchedEffect(title.imdb, title.kind) { if (!title.isSeries) findSources(null) }

    /** What the big button plays: the episode last looked at, or the first of the season. */
    val upNext = episode ?: episodes.firstOrNull()

    fun playEpisode(ep: Episode) = scope.launch {
        val ready = if (episode?.id == ep.id && shown.isNotEmpty()) shown else findSources(ep).await()
        ready.firstOrNull()?.let { watch.stream(it, title, episodeTag(ep.season, ep.episode), ep) }
            ?: watch.notes.tryEmit("No sources found for ${episodeTag(ep.season, ep.episode)}.")
    }

    val wide = isWide()
    Box(Modifier.fillMaxSize()) {
        if (wide) Artwork(title.wide, title.title, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight(0.9f).heroScrims(), fill = false)

        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "head") {
                if (!wide) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f)) {
                        Artwork(title.wide, title.title, Modifier.fillMaxSize().bottomScrim(0.4f), fill = false)
                        TitleMark(title, Modifier.align(Alignment.BottomStart).padding(horizontal = EDGE).widthIn(max = 260.dp), maxHeight = 72.dp, style = Type.display)
                    }
                }
                Column(
                    Modifier.padding(horizontal = EDGE).then(
                        if (wide) Modifier.statusBarsPadding().padding(top = 56.dp).widthIn(max = 460.dp) else Modifier.padding(top = 10.dp),
                    ),
                ) {
                    if (wide) TitleMark(title, Modifier.widthIn(max = 320.dp).padding(bottom = 12.dp), maxHeight = 80.dp, style = Type.display)
                    Facts(title.copy(genres = title.genres.take(2)))
                    Spacer(Modifier.height(16.dp))
                    // Part-way through — whichever release or episode was last played — Resume leads, Start over under it.
                    val last = remember(history, title.imdb) { latestPerTitle(history.filter { it.imdb == title.imdb }).firstOrNull() }
                    when {
                        last != null && last.ratio > 0.02 && last.ratio < 0.95 -> {
                            Button(
                                "Resume" + (whereLeft(last)?.let { "  ·  $it" } ?: ""),
                                { watch.resume(last) },
                                Modifier.fillMaxWidth(),
                                icon = L.drawable.lucide_ic_play,
                                primary = true,
                            )
                            Button("Start over", {
                                val i = last.lastFileIndex ?: 0
                                History.setPosition(last.uri, i, 0.0)
                                watch.resume(History.find(last.uri) ?: last)
                            }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = L.drawable.lucide_ic_rotate_ccw)
                        }
                        !title.isSeries -> Button(
                            label = when {
                                shown.isNotEmpty() -> "Play"
                                busy -> "Finding sources…"
                                else -> "No sources"
                            },
                            onClick = { shown.firstOrNull()?.let { watch.stream(it, title) } },
                            modifier = Modifier.fillMaxWidth(),
                            icon = L.drawable.lucide_ic_play,
                            primary = true,
                        )
                        upNext != null -> Button(
                            label = if (busy && episode?.id == upNext.id && shown.isEmpty()) "Finding sources…" else "Play S${upNext.season}:E${upNext.episode}",
                            onClick = { playEpisode(upNext) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = L.drawable.lucide_ic_play,
                            primary = true,
                        )
                    }
                    title.plot?.let { plot ->
                        Tap({ expanded = !expanded }, Modifier.padding(top = 14.dp), RoundedCornerShape(0.dp)) {
                            Text(plot,style = Type.body.copy(color = Ink.text.copy(alpha = 0.85f)), maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (title.directors.isNotEmpty()) People(if (title.isSeries) "Creators" else "Director", title.directors.take(2).joinToString(", "))
                    // However many actions there are, they share the width evenly.
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        val share = Modifier.weight(1f)
                        // Green tick for My List, red heart for Like — each fills its disc when on.
                        Action(if (inList) L.drawable.lucide_ic_check else L.drawable.lucide_ic_plus, if (inList) "In My List" else "My List", { SavedStore.toggle(title) }, modifier = share, on = inList, onColor = Ink.good)
                        Action(L.drawable.lucide_ic_heart, if (liked) "Liked" else "Like", { SavedStore.toggle(title, "favorite") }, modifier = share, on = liked, onColor = Ink.accent)
                        if (!title.isSeries) Action(L.drawable.lucide_ic_download, "Download", {
                            shown.firstOrNull()?.let { keep(it, null) } ?: watch.notes.tryEmit(if (busy) "Still finding sources…" else "No sources to download.")
                        }, modifier = share)
                        title.trailer?.let { id -> Action(L.drawable.lucide_ic_film, "Trailer", { openUrl(context, "https://www.youtube.com/watch?v=$id") }, modifier = share) }
                        Action(L.drawable.lucide_ic_message_square_text, "Reviews", { reviews = true }, modifier = share)
                    }
                    if (title.cast.isNotEmpty()) CastRow(title.cast)
                    title.awards?.let { AwardsCard(it) }
                }
            }

            if (title.isSeries && seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(Modifier.padding(top = 22.dp)) {
                        Text("Episodes", Modifier.padding(start = EDGE, bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
                        LazyRow(contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        season?.let { s ->
                            Tap({ downloadSeason(s) }, Modifier.padding(start = EDGE - 4.dp, top = 8.dp), RoundedCornerShape(6.dp)) {
                                Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Lc(L.drawable.lucide_ic_download, tint = Ink.muted, size = 16.dp)
                                    Text("  Download season $s", style = Type.meta.copy(fontWeight = FontWeight.SemiBold))
                                }
                            }
                        }
                    }
                }
                // Ten at a time: a long season is a wall of rows otherwise.
                items(if (allEpisodes) episodes else episodes.take(FIRST_EPISODES), key = { "ep-" + it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        fallback = title.wide,
                        busy = busy && episode?.id == ep.id,
                        selected = episode?.id == ep.id,
                        modifier = Modifier.widthIn(max = 720.dp),
                        onPlay = { playEpisode(ep) },
                        onSources = {
                            if (episode?.id != ep.id || hits == null) findSources(ep)
                            sheet = true
                        },
                        onDownload = { downloadEpisode(ep) },
                    )
                }
                if (!allEpisodes && episodes.size > FIRST_EPISODES) item(key = "all-episodes") {
                    Button(
                        "Show all ${episodes.size} episodes",
                        { allEpisodes = true },
                        Modifier.padding(horizontal = EDGE, vertical = 8.dp).fillMaxWidth().widthIn(max = 720.dp),
                        icon = L.drawable.lucide_ic_list_video,
                    )
                }
            } else {
                sources(title, episode, hits, shown, busy, quality, { quality = it }, Modifier.widthIn(max = 720.dp), onDownload = { keep(it, null) }) { hit -> watch.stream(hit, title) }
            }
        }
        // Over the art, a round back button; once the page scrolls under it, a solid bar with the name.
        val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 420 } }
        if (scrolled) {
            Row(Modifier.fillMaxWidth().background(Ink.bg).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
                Text(title.title, Modifier.padding(start = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            BackButton(onBack)
        }
    }

    // An episode's releases come up over the page, rather than at the foot of a long list.
    if (sheet) {
        val ep = episode
        BottomSheet(ep?.let { "S${it.season}:E${it.episode} · ${it.name}" } ?: "Sources", onDismiss = { sheet = false }) {
            LazyColumn {
                sources(title, ep, hits, shown, busy, quality, { quality = it }, Modifier, heading = false, onDownload = { hit ->
                    sheet = false
                    keep(hit, ep)
                }) { hit ->
                    sheet = false
                    watch.stream(hit, title, ep?.let { episodeTag(it.season, it.episode) }, ep)
                }
            }
        }
    }
    // "Download season": the packs to choose from, best first; a tap keeps that one.
    seasonPacks?.let { (s, packs) ->
        BottomSheet("Season $s packs", onDismiss = { seasonPacks = null }) {
            when {
                packs == null -> Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 24.dp)
                    Text("   Asking every index…", style = Type.body)
                }
                packs.isEmpty() -> Note("No season $s pack found yet — download the episodes one by one.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                else -> LazyColumn {
                    items(packs.take(MAX_SOURCES), key = { "pack-" + it.id }) { hit ->
                        Source(hit, Modifier.padding(start = EDGE, end = EDGE, bottom = 8.dp), onDownload = { keepSeason(hit, s) }) { keepSeason(hit, s) }
                    }
                }
            }
        }
    }

    if (reviews) {
        val links = reviewLinks(title)
        Sheet(
            title = "Reviews of ${title.title}",
            // Each site's own logo, by its domain, from Google's favicon service (PNG, one size for all).
            options = links.map { (label, url) -> Option(url, label, image = "https://www.google.com/s2/favicons?sz=64&domain=${android.net.Uri.parse(url).host}") },
            selected = null,
            onPick = {
                reviews = false
                openUrl(context, it)
            },
            onDismiss = { reviews = false },
        )
    }
}

/** The releases: a heading, the quality pills, then one row each. */
private fun LazyListScope.sources(
    title: Title,
    episode: Episode?,
    hits: List<Hit>?,
    shown: List<Hit>,
    busy: Boolean,
    quality: String?,
    onQuality: (String?) -> Unit,
    modifier: Modifier,
    heading: Boolean = true,
    onDownload: (Hit) -> Unit,
    onPlay: (Hit) -> Unit,
) {
    val qualities = hits.orEmpty().mapNotNull { it.quality }.distinct().sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 9 else i } }
    item(key = "sources-head") {
        Column(modifier.padding(start = EDGE, end = EDGE, top = if (heading) 26.dp else 4.dp)) {
            if (heading) Text("Sources", Modifier.padding(bottom = 10.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
            if (qualities.size > 1) {
                Row(Modifier.padding(bottom = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All", quality == null, onClick = { onQuality(null) })
                    qualities.forEach { q -> Chip(q, quality == q, onClick = { onQuality(q) }) }
                }
            }
            when {
                busy && shown.isEmpty() -> Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 24.dp)
                    Text("   Asking every index…", style = Type.body)
                }
                busy -> Note("${shown.size} so far · finding more…", Modifier.padding(bottom = 8.dp))
                hits?.isEmpty() == true -> Note("Nothing found. Try another episode, or search for the release by name.")
                hits == null && episode == null && title.isSeries -> Note("Choose an episode to see what is available.")
            }
        }
    }
    items(shown.take(MAX_SOURCES), key = { "src-" + it.id }) { hit ->
        Source(hit, modifier.padding(start = EDGE, end = EDGE, bottom = 8.dp), runtimeSeconds(title.runtime), onDownload = { onDownload(hit) }) { onPlay(hit) }
    }
}

@Composable
private fun People(label: String, value: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text("$label: ", style = Type.meta.copy(color = Ink.faint))
        Text(value, style = Type.meta.copy(color = Ink.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The cast as faces: a round photo over each name, scrolled sideways. Photos arrive one by one; initials until they do. */
@Composable
private fun CastRow(names: List<String>) {
    Column(Modifier.padding(top = 18.dp)) {
        Text("Cast", Modifier.padding(bottom = 10.dp), style = Type.title.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(names.take(12)) { name ->
                val photo by produceState<String?>(null, name) { value = castPhoto(name) }
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Ink.surface2), contentAlignment = Alignment.Center) {
                        Text(name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), style = Type.label.copy(fontWeight = FontWeight.Bold, color = Ink.muted))
                        photo?.let { Artwork(it, name, Modifier.fillMaxSize(), fill = false) }
                    }
                    Text(name, Modifier.padding(top = 6.dp), style = Type.meta.copy(fontSize = 12.sp, color = Ink.text, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Awards as a card: a trophy, the headline prize, and the tally as two pills. */
@Composable
private fun AwardsCard(text: String) {
    val a = remember(text) { awardsOf(text) }
    Row(
        Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Ink.imdb.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Lc(L.drawable.lucide_ic_trophy, tint = Ink.imdb, size = 22.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(a.headline ?: if (a.wins == null && a.nominations == null) text else "Awards", style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.wins != null || a.nominations != null) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.wins?.let { Pill("$it ${if (it == 1) "win" else "wins"}", Ink.imdb) }
                    a.nominations?.let { Pill("$it ${if (it == 1) "nomination" else "nominations"}", Ink.muted) }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, tint: Color) {
    Text(text, Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp), style = Type.meta.copy(fontSize = 12.sp, color = tint, fontWeight = FontWeight.SemiBold))
}

/** Netflix's phone episode row: still on the left, number and name beside it, the synopsis under both. */
@Composable
private fun EpisodeRow(ep: Episode, fallback: String?, busy: Boolean, selected: Boolean, modifier: Modifier, onPlay: () -> Unit, onSources: () -> Unit, onDownload: () -> Unit) {
    Column(modifier.padding(horizontal = EDGE, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tap(onPlay, Modifier.width(132.dp).aspectRatio(16f / 9f), RoundedCornerShape(6.dp)) {
                Box {
                    Artwork(ep.thumbnail ?: fallback, ep.name, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (busy) 0.5f else 0.2f)), contentAlignment = Alignment.Center) {
                        if (busy) Spinner(size = 26.dp)
                        else Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(8.dp)) { Lc(L.drawable.lucide_ic_play, tint = Color.White, size = 16.dp) }
                    }
                    if (selected && !busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Ink.accent))
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("${ep.episode}. ${ep.name}", style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                ep.released?.take(10)?.let { Text(it, Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp)) }
            }
            // Every release for this episode, to pick one by hand.
            Tap(onSources, shape = CircleShape) { Lc(L.drawable.lucide_ic_list_video, Modifier.padding(10.dp), tint = Ink.muted, size = 22.dp) }
            Tap(onDownload, shape = CircleShape) { Lc(L.drawable.lucide_ic_download, Modifier.padding(10.dp), tint = Ink.muted, size = 22.dp) }
        }
        ep.overview?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Past this a release outruns most home connections. */
private const val HEAVY_MBPS = 40.0
private const val TOO_HEAVY_MBPS = 80.0

/**
 * One release: what it is, how big, and how well it will stream — in words, not a seed count.
 * With [runtimeSeconds], a release too big to stream as it plays says what it needs.
 */
@Composable
fun Source(hit: Hit, modifier: Modifier = Modifier, runtimeSeconds: Double = 0.0, onDownload: (() -> Unit)? = null, onClick: () -> Unit) {
    val grade = health(hit.seeders)
    // A pack's size is many episodes; only a single file's size over the runtime is a bitrate.
    val mbps = if (runtimeSeconds > 0 && hit.size > 0 && (hit.scope == Scope.EPISODE || hit.perFileSize)) hit.size * 8 / runtimeSeconds / 1_000_000 else 0.0
    val tint = when (grade) {
        Health.FAST, Health.GOOD -> Ink.good
        Health.WEAK -> Ink.warn
        Health.DEAD -> Ink.danger
    }
    Tap(onClick, modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) { pressed ->
        Row(
            Modifier.background(if (pressed) Ink.surface2 else Ink.surface.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hit.quality?.let {
                Text(
                    it,
                    Modifier.background(Ink.text.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                    style = Type.overline.copy(color = Ink.text, letterSpacing = 0.5.sp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                // The whole release name, on one line that scrolls through it — the codec and group at the end are what tell releases apart.
                Text(
                    hit.name,
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2000, velocity = 40.dp),
                    style = Type.label.copy(fontSize = 14.sp),
                    maxLines = 1,
                )
                Text(
                    // A film's scope is "Episode" only by default — it says nothing there.
                    listOfNotNull(hit.scope.label.takeIf { hit.kind == "tv" || hit.scope != Scope.EPISODE }, bytes(hit.size), "${hit.seeders} seeds").joinToString("  ·  "),
                    Modifier.padding(top = 2.dp),
                    style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(grade.label, style = Type.meta.copy(color = tint, fontWeight = FontWeight.Bold))
                if (mbps >= HEAVY_MBPS) Text("Needs ${mbps.toInt()} Mb/s", style = Type.meta.copy(fontSize = 11.sp, color = if (mbps >= TOO_HEAVY_MBPS) Ink.danger else Ink.warn))
            }
            if (onDownload != null) Tap(onDownload, Modifier.padding(start = 4.dp), CircleShape) { Lc(L.drawable.lucide_ic_download, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp) }
        }
    }
}
