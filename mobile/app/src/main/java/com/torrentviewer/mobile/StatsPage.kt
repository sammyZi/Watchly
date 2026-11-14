package com.torrentviewer.mobile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.torrentviewer.core.Type
import com.torrentviewer.core.WatchLog
import com.torrentviewer.core.WatchStats
import java.util.Calendar

/** A colour each for the tiles and the genres: told apart at a glance, as year-in-review pages are. */
private val ORANGE = Color(0xFFF59E0B)
private val BLUE = Color(0xFF3B82F6)
private val GREEN = Color(0xFF22C55E)
private val PINK = Color(0xFFEC4899)
private val TEAL = Color(0xFF14B8A6)
/** The app's own red leads. No gradient and no violet: flat cards, as the rest of the app is. */
private val PALETTE get() = listOf(Ink.accent, BLUE, GREEN, ORANGE, TEAL, PINK)

/** The card at the top of Settings: this year's hours, and the way in. */
@Composable
fun StatsBanner(onOpen: () -> Unit) {
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val stats = remember { WatchStats.of(WatchLog.read()) }
    Tap(onOpen, Modifier.padding(bottom = 12.dp).fillMaxWidth(), RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().background(Ink.surface).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Ink.accent), contentAlignment = Alignment.Center) {
                Lc(L.drawable.lucide_ic_chart_no_axes_column, tint = Color.White, size = 20.dp)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text("Your stats", style = Type.label.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (stats.allTime > 0) "${WatchStats.hours(stats.thisYear)} watched in $year  ·  ${stats.streak}-day streak" else "Hours watched, favourites and streaks",
                    Modifier.padding(top = 2.dp),
                    style = Type.meta.copy(color = Ink.muted),
                )
            }
            Lc(L.drawable.lucide_ic_chevron_right, tint = Ink.faint, size = 20.dp)
        }
    }
}

/**
 * What this profile has watched: hours this year, month by month, the titles and genres it
 * went to most, and the runs of days. From [WatchLog], which starts counting when this version
 * does; what History held before is one rough total ("before this log").
 */
@Composable
fun StatsPage() {
    val log = remember { WatchLog.read() }
    val stats = remember(log) { WatchStats.of(log) }
    val genres by produceState(emptyList<Pair<String, Double>>(), log) { value = WatchStats.genres(log) { id, kind -> Meta.title(id, kind)?.genres.orEmpty() } }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val history by History.items.collectAsState()
    val saved by SavedStore.items.collectAsState()

    // The headline: the year, what it is made of, and its months.
    Column(Modifier.padding(bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ink.surface).padding(20.dp)) {
        Text("$year", style = Type.meta.copy(color = Ink.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp))
        Text(WatchStats.hours(stats.thisYear), Modifier.padding(top = 4.dp), style = Type.display.copy(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold))
        Text("watched this year", style = Type.label.copy(color = Ink.muted))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(L.drawable.lucide_ic_tv, "${stats.shows} series")
            Pill(L.drawable.lucide_ic_film, "${stats.films} film${if (stats.films == 1) "" else "s"}")
        }
        Months(stats.byMonth)
        if (stats.allTime <= 0) Text("Watch something, and your stats start here.", Modifier.padding(top = 14.dp), style = Type.meta.copy(color = Ink.muted))
    }

    // Three numbers, a colour each.
    Row(Modifier.padding(bottom = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(L.drawable.lucide_ic_flame, ORANGE, "${stats.streak}", if (stats.streak == 1) "day streak" else "days streak", Modifier.weight(1f))
        Tile(L.drawable.lucide_ic_calendar_days, BLUE, "${stats.activeDays}", if (stats.activeDays == 1) "day watched" else "days watched", Modifier.weight(1f))
        Tile(L.drawable.lucide_ic_clock, GREEN, WatchStats.hours(stats.thisMonth), "this month", Modifier.weight(1f))
    }

    // Every bar is a share of the year's watching, and says its percentage: measured against the
    // largest instead, one show watched drew three full bars that meant nothing.
    val whole = stats.thisYear.coerceAtLeast(1.0)
    fun percent(seconds: Double) = "${(seconds * 100 / whole).toInt().coerceIn(1, 100)}%"

    if (stats.top.isNotEmpty()) Block("Most watched", L.drawable.lucide_ic_trophy, ORANGE) {
        // The poster of each, from wherever the app already has it.
        val ids = remember(log) { log.titles.entries.associate { it.value.name to it.key } }
        stats.top.forEachIndexed { i, (name, kind, seconds) ->
            val id = ids[name]
            val known = history.firstOrNull { it.imdb == id }?.poster ?: saved.firstOrNull { it.imdb == id }?.poster
            val poster by produceState(known, id) { if (value == null && id?.startsWith("tt") == true) value = Meta.title(id, kind)?.poster }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(22.dp), style = Type.title.copy(color = if (i == 0) ORANGE else Ink.faint, fontWeight = FontWeight.Bold))
                Artwork(poster, name, Modifier.size(width = 42.dp, height = 62.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(name, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (kind == "tv") "Series" else "Film") + "  ·  " + WatchStats.hours(seconds) + "  ·  " + percent(seconds) + " of your year", Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Meter((seconds / whole).toFloat(), PALETTE[i % PALETTE.size], Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    if (genres.isNotEmpty()) Block("Favourite genres", L.drawable.lucide_ic_clapperboard, Ink.accent) {
        Text("How much of your watching was each genre. A title counts in each of its genres.", style = Type.meta.copy(color = Ink.faint))
        genres.forEachIndexed { i, (genre, seconds) ->
            val colour = PALETTE[i % PALETTE.size]
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
                    Text(genre, Modifier.padding(start = 10.dp).weight(1f), style = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(percent(seconds), style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.Bold))
                    Text("  ·  " + WatchStats.hours(seconds), style = Type.meta.copy(color = Ink.muted))
                }
                Meter((seconds / whole).toFloat(), colour, Modifier.padding(top = 8.dp))
            }
        }
    }

    Block("Habits", L.drawable.lucide_ic_trending_up, BLUE) {
        Fact(L.drawable.lucide_ic_crown, ORANGE, "Longest streak", "${stats.longestStreak} day${if (stats.longestStreak == 1) "" else "s"}")
        stats.busiestWeekday?.let { Fact(L.drawable.lucide_ic_popcorn, PINK, "You watch most on", it) }
        Fact(L.drawable.lucide_ic_timer, TEAL, "All time", WatchStats.hours(stats.allTime))
        if (log.base > 0) Text("All time includes about ${WatchStats.hours(log.base)} from before this log began.", style = Type.meta.copy(color = Ink.faint))
    }
}

/** A chip in the headline card. */
@Composable
private fun Pill(@DrawableRes icon: Int, text: String) {
    Row(Modifier.clip(CircleShape).background(Ink.surface2).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Lc(icon, tint = Ink.muted, size = 14.dp)
        Text("  $text", style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold))
    }
}

/** One number in its own colour: the icon, the value, what it counts. */
@Composable
private fun Tile(@DrawableRes icon: Int, colour: Color, value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(colour.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 14.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(colour.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Lc(icon, tint = colour, size = 17.dp) }
        Text(value, Modifier.padding(top = 10.dp), style = Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Text(label, style = Type.meta.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Block(title: String, @DrawableRes icon: Int, colour: Color, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Lc(icon, tint = colour, size = 18.dp)
            Text("  $title", style = Type.title.copy(fontWeight = FontWeight.Bold))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** A bar as long as its share of the largest. */
@Composable
private fun Meter(share: Float, colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Ink.surface2)) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0.03f, 1f)).height(6.dp).clip(CircleShape).background(colour))
    }
}

@Composable
private fun Fact(@DrawableRes icon: Int, colour: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(colour.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Lc(icon, tint = colour, size = 16.dp) }
        Text(label, Modifier.padding(start = 12.dp).weight(1f), style = Type.label.copy(color = Ink.muted))
        Text(value, style = Type.label.copy(fontWeight = FontWeight.Bold))
    }
}

/** The twelve months as columns of height by hours; this month in the app's red. */
@Composable
private fun Months(byMonth: List<Double>) {
    val most = (byMonth.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val now = remember { Calendar.getInstance().get(Calendar.MONTH) }
    Row(Modifier.padding(top = 20.dp).fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        byMonth.forEachIndexed { i, s ->
            Box(Modifier.weight(1f).height((4 + 56 * (s / most)).dp).clip(RoundedCornerShape(4.dp)).background(if (i == now) Ink.accent else Ink.surface2))
        }
    }
    Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        "JFMAMJJASOND".forEachIndexed { i, c ->
            Text("$c", Modifier.weight(1f), style = Type.meta.copy(color = if (i == now) Ink.text else Ink.faint, fontSize = 11.sp), textAlign = TextAlign.Center, maxLines = 1)
        }
    }
    Spacer(Modifier.height(2.dp))
}
