package com.torrentviewer.core

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/* ------------------------------------------------------------- languages */

/**
 * A language the viewer can prefer. [code] is SubDL's; [match] is how a player names a
 * track — "English", "Track 2 - [English]", "eng" — so the English name and the ISO 639-2
 * codes. No two-letter codes: "it" and "no" are words, and would match a commentary.
 */
data class Lang(val code: String, val name: String, val match: Regex)

private fun lang(code: String, name: String, words: String) = Lang(code, name, Regex("\\b($words)\\b", RegexOption.IGNORE_CASE))

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
    private val PROFILE_KEYS = listOf("torrent-history", "saved", "searches", "episodes", "intros", "aired-counts")
    private const val SETTINGS = "settings"

    fun export(now: Long = System.currentTimeMillis()): String {
        val data = JSONObject()
        PROFILE_KEYS.forEach { k -> Kv.get(k)?.let { data.put(k, it) } }
        Kv.global(SETTINGS)?.let { data.put(SETTINGS, it) }
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

    private fun read(): Map<String, Int> = runCatching {
        val o = JSONObject(Kv.get(KEY) ?: "{}")
        o.keys().asSequence().associateWith { o.optInt(it) }
    }.getOrDefault(emptyMap())

    private fun write(counts: Map<String, Int>) = Kv.set(KEY, JSONObject(counts).toString())

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
