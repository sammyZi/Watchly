package com.torrentviewer.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Meta
import com.torrentviewer.core.WatchStats
import com.torrentviewer.core.Type
import com.torrentviewer.core.WatchLog
import java.util.Calendar

/**
 * What this profile has watched: hours this year, month by month, the titles and genres it
 * went to most, and the runs of days. From [WatchLog], which starts counting when this version
 * does; what History held before is one rough total ("Before this log").
 */
@Composable
fun StatsPage() {
    val log = remember { WatchLog.read() }
    val stats = remember(log) { WatchStats.of(log) }
    val genres by produceState(emptyList<Pair<String, Double>>(), log) { value = WatchStats.genres(log) { id, kind -> Meta.title(id, kind)?.genres.orEmpty() } }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }

    if (stats.allTime <= 0) {
        Text("Nothing to show yet. Watch something, and it will count from here.", Modifier.padding(bottom = 22.dp), style = Type.body.copy(color = Ink.muted))
        return
    }

    // The headline: the year, and what it is made of.
    Column(Modifier.padding(bottom = 22.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).padding(18.dp)) {
        Text("$year", style = Type.meta.copy(color = Ink.faint, fontWeight = FontWeight.Bold))
        Text(WatchStats.hours(stats.thisYear), Modifier.padding(top = 2.dp), style = Type.display.copy(fontWeight = FontWeight.Bold))
        Text(
            listOfNotNull(
                "${stats.shows} series".takeIf { stats.shows > 0 },
                "${stats.films} film${if (stats.films == 1) "" else "s"}".takeIf { stats.films > 0 },
                "${WatchStats.hours(stats.thisMonth)} this month",
            ).joinToString("  ·  "),
            Modifier.padding(top = 4.dp),
            style = Type.meta.copy(color = Ink.muted),
        )
        Months(stats.byMonth)
    }

    if (stats.top.isNotEmpty()) Block("Most watched") {
        val most = stats.top.first().third
        stats.top.forEach { (name, kind, seconds) ->
            Bar(name, WatchStats.hours(seconds), (seconds / most).toFloat(), if (kind == "tv") "Series" else "Film")
        }
    }

    if (genres.isNotEmpty()) Block("Favourite genres") {
        val most = genres.first().second
        genres.forEach { (genre, seconds) -> Bar(genre, WatchStats.hours(seconds), (seconds / most).toFloat(), null) }
    }

    Block("Habits") {
        Fact("Current streak", if (stats.streak > 0) "${stats.streak} day${if (stats.streak == 1) "" else "s"}" else "—")
        Fact("Longest streak", "${stats.longestStreak} day${if (stats.longestStreak == 1) "" else "s"}")
        Fact("Days watched", "${stats.activeDays}")
        stats.busiestWeekday?.let { Fact("You watch most on", it) }
        Fact("All time", WatchStats.hours(stats.allTime) + if (log.base > 0) " (incl. ${WatchStats.hours(log.base)} before this log)" else "")
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(title, Modifier.padding(bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

/** One row: a label over a bar as long as its share of the largest. */
@Composable
private fun Bar(label: String, value: String, share: Float, tag: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            tag?.let { Text(it, Modifier.padding(end = 10.dp), style = Type.meta.copy(color = Ink.faint)) }
            Text(value, style = Type.meta.copy(color = Ink.muted, fontWeight = FontWeight.SemiBold))
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.surface2)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.accent))
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = Type.label.copy(color = Ink.muted))
        Text(value, style = Type.label.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** The twelve months as columns of height by hours, this month in the accent. */
@Composable
private fun Months(byMonth: List<Double>) {
    val most = (byMonth.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val now = remember { Calendar.getInstance().get(Calendar.MONTH) }
    Row(Modifier.padding(top = 18.dp).fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        byMonth.forEachIndexed { i, s ->
            Box(
                Modifier.weight(1f).height((4 + 60 * (s / most)).dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i == now) Ink.accent else Ink.surface2),
            )
        }
    }
    Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        "JFMAMJJASOND".forEach { Text("$it", Modifier.weight(1f), style = Type.meta.copy(color = Ink.faint), maxLines = 1) }
    }
}
