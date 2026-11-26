package com.torrentviewer.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.WatchLog
import com.torrentviewer.core.WatchStats
import java.util.Calendar

private val ORANGE = Color(0xFFF59E0B)
private val BLUE = Color(0xFF3B82F6)
private val GREEN = Color(0xFF22C55E)
private val PINK = Color(0xFFEC4899)
private val TEAL = Color(0xFF14B8A6)
private val PALETTE get() = listOf(Ink.accent, BLUE, GREEN, ORANGE, TEAL, PINK)

/**
 * The phone's Your stats, for the room: this year's hours month by month, the titles and genres watched most,
 * and the runs of days. Each card takes focus, so the D-pad scrolls down through them.
 */
@Composable
fun StatsPage() {
    val history by History.items.collectAsState()
    // What this TV timed, with what Stremio, Nuvio and the trackers say was watched elsewhere (estimated, as they keep
    // what but not how long); streaks and days stay this TV's own. The phone's page, the same way.
    val here = remember { WatchLog.read() }
    val accounts by com.torrentviewer.core.WatchedEverywhere.collectAsState(com.torrentviewer.core.syncedNow())
    val merged = remember(here, accounts, history) { com.torrentviewer.core.AccountWatches.merge(here, accounts, history.mapNotNull { it.imdb }.toSet()) }
    val log = merged.first
    val estimated = merged.second
    val stats = remember(here, log) { com.torrentviewer.core.AccountWatches.stats(here, log) }
    val genres by produceState(emptyList<Pair<String, Double>>(), log) { value = WatchStats.genres(log) { id, kind -> Meta.title(id, kind)?.genres.orEmpty() } }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val saved by SavedStore.items.collectAsState()
    val whole = stats.thisYear.coerceAtLeast(1.0)
    fun percent(seconds: Double) = "${(seconds * 100 / whole).toInt().coerceIn(1, 100)}%"

    Column(Modifier.widthIn(max = 900.dp)) {
        Card {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("$year", style = Type.meta.copy(color = Ink.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp))
                    Text(WatchStats.hours(stats.thisYear), Modifier.padding(top = 4.dp), style = Type.hero)
                    Text("watched this year  ·  ${stats.shows} series  ·  ${stats.films} film${if (stats.films == 1) "" else "s"}", style = Type.label.copy(color = Ink.muted))
                }
                Months(stats.byMonth, Modifier.width(420.dp))
            }
            if (stats.allTime <= 0) Text("Watch something, and your stats start here.", Modifier.padding(top = 14.dp), style = Type.meta.copy(color = Ink.muted))
        }

        Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(L.drawable.lucide_ic_flame, ORANGE, "${stats.streak}", if (stats.streak == 1) "day streak" else "days streak", Modifier.weight(1f))
            Tile(L.drawable.lucide_ic_calendar_days, BLUE, "${stats.activeDays}", if (stats.activeDays == 1) "day watched" else "days watched", Modifier.weight(1f))
            Tile(L.drawable.lucide_ic_clock, GREEN, WatchStats.hours(stats.thisMonth), "this month", Modifier.weight(1f))
            Tile(L.drawable.lucide_ic_crown, PINK, "${stats.longestStreak}", "longest streak", Modifier.weight(1f))
        }

        if (stats.top.isNotEmpty()) Card("Most watched", L.drawable.lucide_ic_trophy, ORANGE) {
            val ids = remember(log) { log.titles.entries.associate { it.value.name to it.key } }
            stats.top.forEachIndexed { i, (name, kind, seconds) ->
                val id = ids[name]
                val known = history.firstOrNull { it.imdb == id }?.poster ?: saved.firstOrNull { it.imdb == id }?.poster
                // An account's title may come with no name (it is then its IMDb id): Cinemeta has it, with the poster.
                val meta by produceState<Title?>(null, id) { if (id?.startsWith("tt") == true && (known == null || name == id)) value = Meta.title(id, kind) }
                val poster = known ?: meta?.poster
                val shown = if (name == id) meta?.title ?: "" else name
                Row(Modifier.padding(top = if (i == 0) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(28.dp), style = Type.title.copy(color = if (i == 0) ORANGE else Ink.faint, fontWeight = FontWeight.Bold))
                    Artwork(poster, shown, Modifier.size(width = 44.dp, height = 66.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(shown, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (kind == "tv") "Series" else "Film") + "  ·  " + WatchStats.hours(seconds) + "  ·  " + percent(seconds) + " of your year", style = Type.meta.copy(color = Ink.muted), maxLines = 1)
                        Meter((seconds / whole).toFloat(), PALETTE[i % PALETTE.size], Modifier.padding(top = 8.dp))
                    }
                }
            }
        }

        if (genres.isNotEmpty()) Card("Favourite genres", L.drawable.lucide_ic_clapperboard, Ink.accent) {
            genres.forEachIndexed { i, (genre, seconds) ->
                val colour = PALETTE[i % PALETTE.size]
                Column(Modifier.padding(top = if (i == 0) 0.dp else 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
                        Text(genre, Modifier.padding(start = 10.dp).weight(1f), style = Type.label)
                        Text(percent(seconds) + "  ·  " + WatchStats.hours(seconds), style = Type.meta.copy(color = Ink.muted))
                    }
                    Meter((seconds / whole).toFloat(), colour, Modifier.padding(top = 8.dp))
                }
            }
        }

        Card("Habits", L.drawable.lucide_ic_trending_up, BLUE) {
            stats.busiestWeekday?.let { Fact("You watch most on", it) }
            Fact("All time", WatchStats.hours(stats.allTime))
            if (here.base > 0) Text("All time includes about ${WatchStats.hours(here.base)} from before this log began.", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint))
            if (estimated > 0) Text(
                "Includes about ${WatchStats.hours(estimated)} watched on your Stremio, Nuvio and tracker accounts, estimated at a typical film and episode length. Streaks and days watched count what this TV played.",
                Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint),
            )
        }
    }
}

/** A card that takes focus — the D-pad only scrolls as far as what it can land on — under its heading. */
@Composable
private fun Card(title: String? = null, @DrawableRes icon: Int? = null, colour: Color = Ink.text, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 18.dp)) {
        if (title != null) Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Lc(it, tint = colour, size = 20.dp) }
            Text("  $title", style = Type.title.copy(fontWeight = FontWeight.Bold))
        }
        Focusable({}, Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), 1f) {
            Column(Modifier.fillMaxWidth().background(Ink.surface).padding(20.dp)) { content() }
        }
    }
}

@Composable
private fun Tile(@DrawableRes icon: Int, colour: Color, value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(colour.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 14.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(colour.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Lc(icon, tint = colour, size = 18.dp) }
        Text(value, Modifier.padding(top = 10.dp), style = Type.title.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Text(label, style = Type.meta.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Meter(share: Float, colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Ink.surface2)) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0.03f, 1f)).height(6.dp).clip(CircleShape).background(colour))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = Type.label.copy(color = Ink.muted))
        Text(value, style = Type.label.copy(fontWeight = FontWeight.Bold))
    }
}

/** The twelve months as columns of height by hours; this month in the accent. */
@Composable
private fun Months(byMonth: List<Double>, modifier: Modifier) {
    val most = (byMonth.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val now = remember { Calendar.getInstance().get(Calendar.MONTH) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            byMonth.forEachIndexed { i, s ->
                Box(Modifier.weight(1f).height((4 + 66 * (s / most)).dp).clip(RoundedCornerShape(4.dp)).background(if (i == now) Ink.accent else Ink.surface2))
            }
        }
        Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            "JFMAMJJASOND".forEachIndexed { i, c ->
                Text("$c", Modifier.weight(1f), style = Type.meta.copy(color = if (i == now) Ink.text else Ink.faint, fontSize = 12.sp), textAlign = TextAlign.Center)
            }
        }
    }
}
