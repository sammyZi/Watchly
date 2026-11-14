package com.torrentviewer.core

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Seconds actually watched, per day and title, for the stats page. History keeps only the last
 * 50 titles and where each was left, which is no basis for hours watched or a yearly recap, so
 * the player credits what it plays here as it plays: [add] is called with each step of the picture
 * along the film. Per profile, like everything the viewer accumulates. [base] is the rough total
 * from before the log began (the positions History held), so the page is not empty on day one.
 */
object WatchLog {
    private const val KEY = "watch-log"
    /** A year and a bit: enough for "your year", and the log stays small. */
    private const val KEEP_DAYS = 400

    data class Seen(val name: String, val kind: String)

    /** [days]: date (yyyy-MM-dd) → imdb → seconds. */
    data class Log(val base: Double, val days: Map<String, Map<String, Double>>, val titles: Map<String, Seen>)

    private fun day(now: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    fun read(): Log = runCatching {
        val o = JSONObject(Kv.get(KEY) ?: return@runCatching null)
        val days = o.optJSONObject("days")?.let { d ->
            d.keys().asSequence().associateWith { k -> d.getJSONObject(k).let { t -> t.keys().asSequence().associateWith { t.optDouble(it) } } }
        }.orEmpty()
        val titles = o.optJSONObject("titles")?.let { t ->
            t.keys().asSequence().associateWith { k -> t.getJSONObject(k).let { Seen(it.optString("n"), it.optString("k", "movie")) } }
        }.orEmpty()
        Log(o.optDouble("base", 0.0), days, titles)
    }.getOrNull() ?: Log(0.0, emptyMap(), emptyMap())

    private fun write(log: Log) {
        val days = JSONObject()
        log.days.forEach { (d, m) -> days.put(d, JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }) }
        val titles = JSONObject()
        log.titles.forEach { (k, s) -> titles.put(k, JSONObject().put("n", s.name).put("k", s.kind)) }
        Kv.set(KEY, JSONObject().put("base", log.base).put("days", days).put("titles", titles).toString())
    }

    /** Credit [seconds] of [item] to today. A title with no IMDb id is credited under its own name. */
    @Synchronized
    fun add(item: Item, seconds: Double, now: Long = System.currentTimeMillis()) {
        if (seconds <= 0) return
        val id = item.imdb ?: ("name:" + item.label)
        var log = read()
        if (log.days.isEmpty() && log.base == 0.0) {
            // The first: what History already knew is the best guess at what came before.
            log = log.copy(base = History.items.value.sumOf { it.positions.values.sum() })
        }
        val today = day(now)
        val days = (log.days + (today to (log.days[today].orEmpty() + (id to ((log.days[today]?.get(id) ?: 0.0) + seconds)))))
            .toSortedMap().let { m -> m.keys.toList().let { k -> m.filterKeys { it in k.takeLast(KEEP_DAYS) } } }
        val kept = days.values.flatMap { it.keys }.toSet()
        write(log.copy(days = days, titles = (log.titles + (id to Seen(item.label, item.kind ?: "movie"))).filterKeys { it in kept }))
    }
}

/** What the stats page shows, worked out from a [WatchLog.Log]. Not [Stats], which is the player's own readout. */
data class WatchStats(
    val allTime: Double,
    val thisYear: Double,
    val thisMonth: Double,
    /** Seconds in each month of this year, January first. */
    val byMonth: List<Double>,
    /** Most-watched titles this year: name, kind, seconds. */
    val top: List<Triple<String, String, Double>>,
    /** Days with any watching, and the run of days ending today (or yesterday). */
    val activeDays: Int,
    val streak: Int,
    val longestStreak: Int,
    val busiestWeekday: String?,
    /** Series and films with anything watched this year. */
    val shows: Int,
    val films: Int,
) {
    companion object {
        private val WEEKDAYS = listOf("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")

        fun of(log: WatchLog.Log, now: Long = System.currentTimeMillis()): WatchStats {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH)
            val byMonth = DoubleArray(12)
            val perTitle = HashMap<String, Double>()
            val weekday = DoubleArray(7)
            var all = log.base
            val active = ArrayList<Long>()
            for ((d, titles) in log.days) {
                val total = titles.values.sum()
                if (total <= 0) continue
                val at = runCatching { fmt.parse(d)?.time }.getOrNull() ?: continue
                all += total
                active += at / DAY
                val c = Calendar.getInstance().apply { timeInMillis = at }
                weekday[c.get(Calendar.DAY_OF_WEEK) - 1] += total
                if (c.get(Calendar.YEAR) == year) {
                    byMonth[c.get(Calendar.MONTH)] += total
                    titles.forEach { (id, s) -> perTitle[id] = (perTitle[id] ?: 0.0) + s }
                }
            }
            val seen = perTitle.keys.mapNotNull { log.titles[it] }
            // The run of consecutive days, from today back; one not yet watched today does not break it.
            val days = active.toSortedSet()
            val today = (now + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / DAY
            var streak = 0
            var d = if (today in days) today else today - 1
            while (d in days) { streak++; d-- }
            var longest = 0
            var run = 0
            var prev = Long.MIN_VALUE
            for (x in days) { run = if (x == prev + 1) run + 1 else 1; longest = maxOf(longest, run); prev = x }
            return WatchStats(
                allTime = all,
                thisYear = byMonth.sum(),
                thisMonth = byMonth[month],
                byMonth = byMonth.toList(),
                top = perTitle.entries.sortedByDescending { it.value }.take(5).mapNotNull { (id, s) -> log.titles[id]?.let { Triple(it.name, it.kind, s) } },
                activeDays = days.size,
                streak = streak,
                longestStreak = longest,
                busiestWeekday = weekday.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { WEEKDAYS[it.index] },
                shows = seen.count { it.kind == "tv" },
                films = seen.count { it.kind != "tv" },
            )
        }

        /**
         * This year's seconds by genre, from the twelve most-watched titles — each is a metadata
         * lookup, off its day-long cache. A title counts in each of its first three genres.
         */
        suspend fun genres(log: WatchLog.Log, now: Long = System.currentTimeMillis(), load: suspend (imdb: String, kind: String) -> List<String>): List<Pair<String, Double>> {
            val year = SimpleDateFormat("yyyy", Locale.US).format(Date(now))
            val seconds = HashMap<String, Double>()
            for ((d, titles) in log.days) if (d.startsWith(year)) titles.forEach { (id, s) -> seconds[id] = (seconds[id] ?: 0.0) + s }
            val byGenre = HashMap<String, Double>()
            for ((id, s) in seconds.entries.filter { it.key.startsWith("tt") }.sortedByDescending { it.value }.take(12)) {
                val kind = log.titles[id]?.kind ?: "movie"
                for (g in runCatching { load(id, kind) }.getOrDefault(emptyList()).take(3)) byGenre[g] = (byGenre[g] ?: 0.0) + s
            }
            return byGenre.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        }

        private const val DAY = 86_400_000L

        /** "132h 40m", "3h 05m", "42m". */
        fun hours(seconds: Double): String {
            val m = (seconds / 60).toLong()
            return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m"
        }
    }
}
