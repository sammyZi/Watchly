package com.torrentviewer.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/* ------------------------------------------------------------- languages */

/**
 * A language the viewer can prefer. [code] is SubDL's; [match] is how a player names a
 * track — "English", "Track 2 - [English]", "eng" — so the English name and the ISO 639-2
 * codes. No two-letter codes: "it" and "no" are words, and would match a commentary.
 */
data class Lang(val code: String, val name: String, val match: Regex)

/**
 * A whole word: no letter, digit or underscore either side of it. Spelled out rather than `\b`,
 * which on a desktop JVM since Java 19 only knows ASCII letters — "Српски" has no word boundary
 * there at all, and the unit tests run on one. Android's own regex has always counted every script.
 */
private fun lang(code: String, name: String, words: String) =
    Lang(code, name, Regex("(?<![\\p{L}\\p{N}_])($words)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE))

val LANGS = listOf(
    lang("EN", "English", "english|eng"),
    lang("ES", "Spanish", "spanish|español|espanol|spa|castellano|latino"),
    lang("FR", "French", "french|français|francais|fre|fra"),
    lang("DE", "German", "german|deutsch|ger|deu"),
    lang("IT", "Italian", "italian|italiano|ita"),
    lang("PT", "Portuguese", "portuguese|português|portugues|por"),
    lang("BR_PT", "Portuguese (Brazil)", "brazil\\w*|pt-br|pob"),
    lang("RU", "Russian", "russian|rus"),
    lang("AR", "Arabic", "arabic|ara"),
    lang("HI", "Hindi", "hindi|hin"),
    lang("TR", "Turkish", "turkish|tur"),
    lang("NL", "Dutch", "dutch|nederlands|dut|nld"),
    lang("PL", "Polish", "polish|polski|pol"),
    // ISO 639-2 has old codes for these too (scr, scc), still in some files' track names.
    lang("HR", "Croatian", "croatian|hrvatski|hrv|scr"),
    lang("SR", "Serbian", "serbian|srpski|српски|srp|scc"),
    lang("BS", "Bosnian", "bosnian|bosanski|bos"),
    lang("JA", "Japanese", "japanese|jpn"),
    lang("KO", "Korean", "korean|kor"),
    lang("ZH", "Chinese", "chinese|mandarin|chi|zho"),
    lang("ID", "Indonesian", "indonesian|ind"),
    lang("VI", "Vietnamese", "vietnamese|vie"),
    lang("TH", "Thai", "thai|tha"),
    lang("FA", "Persian", "persian|farsi|fas"),
    lang("HE", "Hebrew", "hebrew|heb"),
    lang("EL", "Greek", "greek|gre|ell"),
    lang("SV", "Swedish", "swedish|swe"),
    lang("RO", "Romanian", "romanian|rum|ron"),
    lang("TA", "Tamil", "tamil|tam"),
    lang("TE", "Telugu", "telugu|tel"),
    lang("ML", "Malayalam", "malayalam|mal"),
    lang("BN", "Bengali", "bengali|bangla|ben"),
)

fun langName(code: String) = LANGS.find { it.code == code }?.name ?: code

/**
 * The track in [code]'s language, preferring a full one: "forced" subtitles only cover
 * the foreign lines and a commentary is not the film — a match only when nothing else is.
 */
fun trackForLang(tracks: List<Track>, code: String): Track? {
    val lang = LANGS.find { it.code == code } ?: return null
    val hits = tracks.filter { lang.match.containsMatchIn(it.name) }
    return hits.firstOrNull { !Regex("\\b(forced|commentary|signs)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.name) } ?: hits.firstOrNull()
}

/* ---------------------------------------------------------------- backup */

/**
 * The library as one JSON file, in the Expo build's format so either restores the
 * other's: history and resume points, lists, settings, searches, episode links, intros.
 * Finished downloads stay out — they name files on this phone's disk.
 */
object Backup {
    /** Per profile; settings are the device's. */
    private val PROFILE_KEYS = listOf("torrent-history", "saved", "searches", "episodes", "intros", "aired-counts", GENRES_KEY)
    private const val SETTINGS = "settings"

    /** What a phone can pick to send to a TV: a label, and the keys it covers. */
    val GROUPS = linkedMapOf(
        "Settings and addons" to setOf(SETTINGS),
        "History and resume points" to setOf("torrent-history", "intros"),
        "My List, searches and home genres" to setOf("saved", "searches", "episodes", "aired-counts", GENRES_KEY),
    )

    /** [only]: just those keys; null is everything. */
    fun export(now: Long = System.currentTimeMillis(), only: Set<String>? = null): String {
        val data = JSONObject()
        PROFILE_KEYS.filter { only == null || it in only }.forEach { k -> Kv.get(k)?.let { data.put(k, it) } }
        if (only == null || SETTINGS in only) Kv.global(SETTINGS)?.let { data.put(SETTINGS, it) }
        return JSONObject().put("app", "watchly").put("version", 1).put("at", now).put("data", data).toString()
    }

    /** Only known keys, and only values that are JSON themselves: a picked file is untrusted. */
    fun parse(text: String): List<Pair<String, String>> {
        val file = runCatching { JSONObject(text) }.getOrNull()
        val data = file?.optJSONObject("data")
        if (file?.optString("app") != "watchly" || data == null) throw IllegalArgumentException("That file is not a Watchly backup")
        val pairs = (PROFILE_KEYS + SETTINGS).mapNotNull { k ->
            val v = data.opt(k) as? String ?: return@mapNotNull null
            val json = runCatching { org.json.JSONTokener(v).nextValue() }.getOrNull()
            if (json is JSONObject || json is org.json.JSONArray) k to v else null
        }
        if (pairs.isEmpty()) throw IllegalArgumentException("That backup is empty")
        return pairs
    }

    /** Replaces what is stored, key by key, into the profile in use. Returns how many keys came back. */
    fun restore(text: String): Int {
        val pairs = parse(text)
        pairs.forEach { (k, v) -> if (k == SETTINGS) Kv.setGlobal(k, v) else Kv.set(k, v) }
        History.load()
        SavedStore.load()
        SettingsStore.load()
        // The addons live inside the settings JSON.
        Addons.load()
        return pairs.size
    }
}

/* ---------------------------------------------------------- new episodes */

/**
 * Followed series with episodes out since they were last looked at: a count of aired
 * episodes per show, taken the first time a show is seen and again whenever its page
 * opens. Checked when Home builds, off the metadata's day-long cache.
 */
object NewEpisodes {
    private const val KEY = "aired-counts"
    /** Each show is one /meta request on a cold cache. */
    private const val MAX_SHOWS = 25

    data class Fresh(val title: Title, val count: Int)

    private fun day() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Specials (season 0) are not new episodes, and Cinemeta lists episodes ahead of their air date. */
    fun airedCount(t: Title, now: Long = System.currentTimeMillis()): Int {
        val fmt = day()
        return t.episodes.count { e ->
            e.season > 0 && e.released?.take(10)?.let { runCatching { fmt.parse(it)?.time }.getOrNull() }?.let { it <= now } == true
        }
    }

    /** Which titles moved past their baseline, and the baselines to keep. */
    fun compare(titles: List<Title>, known: Map<String, Int>, now: Long = System.currentTimeMillis()): Pair<List<Fresh>, Map<String, Int>> {
        val fresh = mutableListOf<Fresh>()
        val next = known.toMutableMap()
        for (t in titles) {
            if (t.episodes.isEmpty()) continue
            val aired = airedCount(t, now)
            val seen = known[t.imdb]
            if (seen == null) next[t.imdb] = aired else if (aired > seen) fresh += Fresh(t, aired - seen)
        }
        return fresh to next
    }

    private fun parse(text: String?): Map<String, Int> = runCatching {
        val o = JSONObject(text ?: "{}")
        o.keys().asSequence().associateWith { o.optInt(it) }
    }.getOrDefault(emptyMap())

    private fun read(): Map<String, Int> = parse(Kv.get(KEY))

    private fun write(counts: Map<String, Int>) = Kv.set(KEY, JSONObject(counts).toString())

    /** The newest episode that has aired, by date and then number; null when none has. */
    fun latestAired(t: Title, now: Long = System.currentTimeMillis()): Episode? {
        val fmt = day()
        return t.episodes
            .filter { e -> e.season > 0 && e.released?.take(10)?.let { runCatching { fmt.parse(it)?.time }.getOrNull() }?.let { it <= now } == true }
            .maxWithOrNull(compareBy({ it.released?.take(10) }, { it.season }, { it.episode }))
    }

    /** A notification: [episode] is the newest of [count] out since the viewer was last told. */
    data class Alert(val profile: String, val title: Title, val episode: Episode, val count: Int)

    private const val ALERTED = "alerted-counts"

    /**
     * What to tell each profile about: its followed series with episodes out since it was last
     * told, against a baseline of its own (the in-app row on Home has another, moved when a page is
     * opened). A show met for the first time only sets its baseline, so turning alerts on never
     * floods. Read-only on the profiles' own data except that baseline.
     */
    suspend fun alerts(): List<Alert> {
        val out = mutableListOf<Alert>()
        for (profile in Profiles.list.value.filter { !it.isGuest && !it.kids }) {
            val shows = followed(profile.id)
            if (shows.isEmpty()) continue
            val titles = shows.take(MAX_SHOWS).mapNotNull { Meta.title(it, "tv") }
            val known = parse(Kv.getFor(profile.id, ALERTED))
            val (fresh, next) = compare(titles, known)
            for (f in fresh) latestAired(f.title)?.let { out += Alert(profile.id, f.title, it, f.count) }
            // Told, so the baseline moves for those too.
            val told = next + fresh.associate { it.title.imdb to airedCount(it.title) }
            if (told != known) Kv.setFor(profile.id, ALERTED, JSONObject(told).toString())
        }
        return out
    }

    /** Series the profile has watched or saved, newest first. */
    private fun followed(profile: String): List<String> {
        val watched = runCatching {
            JSONArray(Kv.getFor(profile, "torrent-history") ?: "[]").objects()
                .filter { it.optString("kind") == "tv" }.sortedByDescending { it.optLong("at") }.mapNotNull { it.str("imdb") }
        }.getOrDefault(emptyList())
        val saved = runCatching {
            JSONArray(Kv.getFor(profile, "saved") ?: "[]").objects().filter { it.optString("kind") == "tv" }.mapNotNull { it.str("imdb") }
        }.getOrDefault(emptyList())
        return (watched + saved).filter { it.startsWith("tt") }.distinct()
    }

    suspend fun fresh(imdbs: List<String>): List<Fresh> {
        val ids = imdbs.distinct().take(MAX_SHOWS)
        if (ids.isEmpty()) return emptyList()
        val titles = ids.mapNotNull { Meta.title(it, "tv") }
        val known = read()
        val (fresh, next) = compare(titles, known)
        if (next.size != known.size) write(next)
        return fresh
    }

    /** Seen what is out. Only with the full episode list — a shelf row has none, and a zero baseline would make every episode new. */
    fun markSeen(t: Title) {
        if (!t.isSeries || t.episodes.isEmpty()) return
        val known = read()
        val aired = airedCount(t)
        if (known[t.imdb] != aired) write(known + (t.imdb to aired))
    }
}

/* -------------------------------------------------------------- upcoming */

/** A date ahead, yyyy-MM-dd: an episode of a followed series, or a saved film's release ([episode] null). */
data class Coming(val title: Title, val date: String, val episode: Episode?) {
    /** "S2 E5 · The Long Night", or "Film" for a release. */
    val what get() = episode?.let { "S${it.season} E${it.episode}" + (it.name.takeIf { n -> n.isNotBlank() && !n.startsWith("Episode") }?.let { n -> " · $n" } ?: "") } ?: "Film"
}

/**
 * What's coming for the profile: its followed series' next episodes and the films it saved before they
 * were out, from the metadata's day-long cache. Cinemeta lists episodes with their air dates ahead of time.
 */
object Upcoming {
    /** How far ahead the page and the calendar file look. */
    const val DAYS = 60
    private const val MAX_TITLES = 40

    private fun day() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun today(now: Long = System.currentTimeMillis()): String = day().format(Date(now))

    /** "Thu 20 Nov" for a yyyy-MM-dd day; today and tomorrow by name. */
    fun label(date: String, now: Long = System.currentTimeMillis()): String {
        val today = today(now)
        if (date == today) return "Today"
        if (date == nextDay(today)) return "Tomorrow"
        return runCatching {
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(day().parse(date)!!)
        }.getOrDefault(date)
    }

    /** The day after [date], for an all-day event's end. */
    fun nextDay(date: String): String = runCatching {
        val fmt = day()
        fmt.format(Date(fmt.parse(date)!!.time + 24 * 60 * 60 * 1000L))
    }.getOrDefault(date)

    /** From [today] to [until], soonest first. Specials (season 0) are left out, as New Episodes leaves them. */
    fun ahead(titles: List<Title>, today: String, until: String): List<Coming> = titles.flatMap { t ->
        if (t.isSeries) t.episodes.filter { e -> e.season > 0 && e.released?.take(10)?.let { it in today..until } == true }.map { Coming(t, it.released!!.take(10), it) }
        else listOfNotNull(t.released?.take(10)?.takeIf { it in today..until }?.let { Coming(t, it, null) })
    }.sortedWith(compareBy({ it.date }, { it.title.title }, { it.episode?.season }, { it.episode?.episode }))

    suspend fun load(shows: List<String>, films: List<String>, now: Long = System.currentTimeMillis()): List<Coming> {
        val today = today(now)
        val until = day().format(Date(now + DAYS * 24 * 60 * 60 * 1000L))
        val titles = shows.distinct().take(MAX_TITLES).mapNotNull { Meta.title(it, "tv") } +
            films.distinct().take(MAX_TITLES).mapNotNull { Meta.title(it, "movie") }
        return ahead(titles, today, until)
    }

    /**
     * An iCalendar file of [items], one all-day event each, for a calendar app to import. Each event's UID
     * stays the same between exports, so importing again updates the same events rather than doubling them.
     */
    fun ics(items: List<Coming>, now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
        fun text(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        // Lines over 75 characters go on, after a break and a space (RFC 5545 §3.1).
        fun fold(line: String) = line.chunked(73).joinToString("\r\n ")
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Watchly//Upcoming//EN", "CALSCALE:GREGORIAN", "X-WR-CALNAME:Watchly upcoming")
        for (c in items) {
            val id = c.episode?.let { "${c.title.imdb}-${it.season}-${it.episode}" } ?: c.title.imdb
            lines += listOf(
                "BEGIN:VEVENT",
                "UID:$id@watchly",
                "DTSTAMP:$stamp",
                "DTSTART;VALUE=DATE:${c.date.replace("-", "")}",
                "DTEND;VALUE=DATE:${nextDay(c.date).replace("-", "")}",
                "SUMMARY:${text(if (c.episode != null) "${c.title.title} — ${c.what}" else "${c.title.title} (release)")}",
                "TRANSP:TRANSPARENT",
                "END:VEVENT",
            )
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }
}
