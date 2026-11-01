package com.torrentviewer.core

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import kotlin.math.roundToLong

/* ---------------------------------------------------------------- titles */

private val SCAFFOLD = Regex(
    """\b(?:19\d{2}|20\d{2}|s\d{1,2}e\d{1,2}|season|complete|\d{3,4}p|2160p|4k|bluray|brrip|bdrip|web[ -]?dl|webrip|hdtv|dvdrip|x26[45]|h ?26[45]|hevc|aac|ac3|dts)\b""",
    RegexOption.IGNORE_CASE,
)

/** "The.Matrix.1999.1080p.BluRay.x264-GRP" → "The Matrix": from the year or first quality tag on is scaffolding. */
fun searchableName(name: String): String = name
    .replace(Regex("""\.[a-z0-9]{2,4}$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\[[^\]]*]"""), " ")
    .replace(Regex("[._]+"), " ")
    .split(SCAFFOLD)[0]
    .replace(Regex("""[(\[{].*$"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

fun yearOf(name: String): String? = Regex("""\b(19\d{2}|20\d{2})\b""").find(name)?.groupValues?.get(1)

fun isSeries(name: String) =
    Regex("""\bs\d{1,2}\s?e\d{1,2}\b|\b\d{1,2}x\d{2}\b|\bseason\b|\bcomplete series\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)

private fun safeDecode(s: String) = runCatching { URLDecoder.decode(s.replace("%(?![0-9a-fA-F]{2})".toRegex(), "%25"), "UTF-8") }.getOrDefault(s)

/** A label for a magnet, torrent URL or local path before anything else is known. */
fun titleOf(uri: String): String {
    Regex("[?&]dn=([^&]*)").find(uri)?.groupValues?.get(1)?.let { return safeDecode(it.replace('+', ' ')) }
    Regex("btih:([a-z0-9]+)", RegexOption.IGNORE_CASE).find(uri)?.groupValues?.get(1)?.let { return it.take(16) }
    val file = uri.split('/', '\\').last().substringBefore('?')
    return if (file.isNotEmpty()) safeDecode(file) else uri
}

/** Stable hue for a name, so a card keeps its colour between launches. */
fun hueOf(name: String): Int = name.fold(0) { h, c -> (h * 31 + c.code) % 360 }

/* ---------------------------------------------------------------- magnet */

val PUBLIC_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://explodie.org:6969/announce",
    "wss://tracker.openwebtorrent.com",
)

/** Bare magnets discover peers through DHT alone; trackers give them somewhere to ask at once. */
fun withTrackers(uri: String, trackers: List<String> = PUBLIC_TRACKERS): String {
    if (!uri.lowercase().startsWith("magnet:")) return uri
    val have = Regex("[?&]tr=([^&]+)").findAll(uri).map { safeDecode(it.groupValues[1]) }.toSet()
    val missing = trackers.filter { it !in have }
    return uri + missing.joinToString("") { "&tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
}

fun isTorrentSource(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.startsWith("magnet:") || lower.startsWith("content://")) return true
    return Regex("^(file|https?):").containsMatchIn(lower) && lower.substringBefore('?').endsWith(".torrent")
}

fun magnetFor(hash: String, name: String) =
    withTrackers("magnet:?xt=urn:btih:$hash&dn=${java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")}")

/* -------------------------------------------------------------- episodes */

data class Ep(val season: Int, val episode: Int)

fun episodeTag(season: Int, episode: Int) = "S%02dE%02d".format(season, episode)

/**
 * What picks a file in a pack: the tag, plus the number counted through the
 * show when that differs ("S02E05#29") — see [pickFile].
 */
fun pickTag(season: Int, episode: Int, absolute: Int?) =
    if (absolute != null && absolute != episode) "${episodeTag(season, episode)}#$absolute" else episodeTag(season, episode)

/** Which episode a file name belongs to, or null. Up to four digits: long-running anime is released as S01E1071. */
fun parseEpisode(name: String): Ep? {
    val found = SXE.find(name) ?: NXN.find(name)
    return found?.let { Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
}

// Compiled once: these run per release, hundreds of times a search, and a compile each
// time held the TV's main thread past the five-second ANR.
private val SXE = Regex("""\bs(\d{1,2})[ ._-]*e(\d{1,4})\b""", RegexOption.IGNORE_CASE)
private val NXN = Regex("""\b(\d{1,2})x(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val ABSOLUTE = listOf(
    Regex("""(?:^|[\s_\])])-[\s_]*(\d{1,4})(?:v\d)?(?=[\s_.\[(]|$)""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:episode|ep|e)[ ._-]?(\d{1,4})(?:v\d)?\b""", RegexOption.IGNORE_CASE),
    Regex("""(?:^|[\s_])\[(\d{1,4})(?:v\d)?]"""), // "Jujutsu Kaisen S2 [29]"
    // "[yibis]_One_Piece_588_[720p]": a bare number right before the tags (not "Season 12 [1080p]").
    Regex("""(?<!season[\s_]{0,3})[\s_](\d{2,4})(?:v\d)?[\s_]*(?=[\[(]|$)""", RegexOption.IGNORE_CASE),
)
private const val SPAN_MARK = """(?:s\d{1,2}[ ._-]*e|ep?)?"""
private val SPAN = Regex("""(?<![\w.])$SPAN_MARK(\d{1,4})\s*[-~–]\s*$SPAN_MARK(\d{1,4})(?![\w.])""", RegexOption.IGNORE_CASE)
private val SEASON_RANGES = listOf(
    Regex("""\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bseasons?[ ._-]*(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b""", RegexOption.IGNORE_CASE),
)
private val SEASON_ONES = listOf(
    Regex("""\bs(\d{1,2})(?=e\d{1,4}\b|\b)""", RegexOption.IGNORE_CASE),
    Regex("""\bseasons?[ ._-]*(\d{1,2})\b""", RegexOption.IGNORE_CASE),
)

/**
 * The episode number of a release that names no season — how anime is released:
 * "[SubsPlease] One Piece - 1071 (1080p)", "One Piece E1071", "Naruto Shippuden Episode 145".
 * Usually counted straight through the show; see [absoluteNumber].
 */
fun parseAbsolute(name: String): Int? {
    if (parseEpisode(name) != null) return null
    val m = ABSOLUTE.firstNotNullOfOrNull { it.find(name) } ?: return null
    return m.groupValues[1].toInt().takeIf { it in 1..1899 }
}

/** "Show (01-24) [Batch]", "Show EP1144-EP1155", "Show S01E1144-E1155": the episodes a batch claims. */
fun episodeSpan(name: String): IntRange? {
    val m = SPAN.find(name) ?: return null
    val from = m.groupValues[1].toInt()
    val to = m.groupValues[2].toInt()
    return if (from < to && to < 1900) from..to else null
}

/**
 * Does a batch hold this episode? Null when it is no batch. Without a season label the
 * span counts through the show; "S01E1144-E1155" is either that season's or, as season 1
 * of a show listed in arcs, the running number.
 */
fun spanHolds(name: String, season: Int, episode: Int, absolute: Int?): Boolean? {
    val span = episodeSpan(name) ?: return null
    val claims = seasonsIn(name)
    if (claims.isEmpty()) return (absolute ?: episode) in span
    return season in claims && episode in span || 1 in claims && absolute != null && absolute in span
}

/**
 * Is [name] episode [episode] of [season] ([absolute] counting through the show)?
 * Null when it names no single episode. [folder] is where season labels may live instead.
 */
fun namesEpisode(name: String, season: Int, episode: Int, absolute: Int?, folder: String = ""): Boolean? {
    parseEpisode(name)?.let { return it.season == season && it.episode == episode || it.season == 1 && it.episode == absolute }
    val n = parseAbsolute(name) ?: return null
    // "- 29" is the 29th of the show. A season label decides which season:
    // "Season 2 - 05" is the fifth of season 2, "S2 [29]" the 29th overall.
    val claims = seasonsIn("$folder $name")
    return if (claims.isEmpty()) n == (absolute ?: episode) else season in claims && (n == episode || n == absolute)
}

/** Which seasons a release name claims; ranges first, then single tokens. */
fun seasonsIn(name: String): List<Int> {
    val out = sortedSetOf<Int>()
    for (r in SEASON_RANGES) for (m in r.findAll(name)) {
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        for (i in minOf(a, b)..maxOf(a, b)) out.add(i)
    }
    if (out.isNotEmpty()) return out.toList()
    // The bare token allows no separator, so "Ocean's 8" is not season 8.
    for (r in SEASON_ONES) for (m in r.findAll(name)) out.add(m.groupValues[1].toInt())
    return out.toList()
}

enum class Scope(val label: String) { EPISODE("Episode"), SEASON("Season pack"), COMPLETE("Complete series") }

fun scopeOf(name: String): Scope {
    val i = RegexOption.IGNORE_CASE
    if (Regex("""\bs\d{1,2}\s?e\d{1,2}\s*[-–]\s*e?\d{1,2}\b""", i).containsMatchIn(name)) return Scope.SEASON
    if (Regex("""\bs\d{1,2}\s?e\d{1,4}\b|\b\d{1,2}x\d{2}\b""", i).containsMatchIn(name)) return Scope.EPISODE
    // "Show S2 [29]", "Show Season 2 - 05": one anime episode, not the season pack its label suggests.
    if (parseAbsolute(name) != null) return Scope.EPISODE
    if (Regex("""\bseasons?\s*\d{1,2}\s*[-–]\s*\d{1,2}\b|\ball seasons\b|\bcomplete series\b""", i).containsMatchIn(name)) return Scope.COMPLETE
    Regex("""\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b""", i).find(name)?.let {
        if (it.groupValues[1].toInt() != it.groupValues[2].toInt()) return Scope.COMPLETE
    }
    if (Regex("""\bs\d{1,2}\b|\bseason\s*\d{1,2}\b""", i).containsMatchIn(name)) return Scope.SEASON
    return Scope.EPISODE
}

/* ------------------------------------------------------------------ hits */

/** One stream an addon offered: a torrent, or a link that plays as it is. */
data class Hit(
    /** The info hash for a torrent, so two addons' copies are one row; else the link. */
    val id: String,
    val name: String,
    /** A magnet, or the http(s) link itself — a debrid service's, or the viewer's own server's. */
    val uri: String,
    val seeders: Int,
    val leechers: Int,
    val size: Long,
    val quality: String?,
    val kind: String,
    val scope: Scope,
    val imdb: String?,
    /** An addon's size is the one file it found, even inside a pack. */
    val perFileSize: Boolean = false,
    /** The addon it came from, by name. */
    val addon: String = "",
    /** Not ours to play: a YouTube video or a page the addon sends to another app. */
    val external: Boolean = false,
    /** What the link's server wants with every request (an addon's `proxyHeaders`). */
    val headers: Map<String, String> = emptyMap(),
    /** The file the addon meant, by the torrent's own numbering — for when names can't say. */
    val fileIdx: Int? = null,
    /** The manifest link of the addon it came from: a debrid link only plays while that addon (and its key) is still added. */
    val addonUrl: String = "",
    /** The release's own name: a season pack's, where [name] is the one file in it the addon meant. */
    val release: String = name,
) {
    /** Plays straight from its link: no swarm, no seeders to count. */
    val direct get() = !external && isDirectLink(uri)
}

/** The info hash a debrid link names in its path (Torrentio's ".../resolve/torbox/<key>/<hash>/..."), if any. */
fun linkHash(link: String): String? =
    link.substringBefore('?').split('/').firstOrNull { Regex("^[0-9a-fA-F]{40}$").matches(it) }?.lowercase()

/** A video served over http(s), as opposed to a magnet, a .torrent or a path. */
fun isDirectLink(uri: String) = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(uri) && !isTorrentSource(uri)

private val HASH = Regex("^[0-9a-fA-F]{40}$")

fun quality(name: String): String? = when {
    Regex("""2160p|\buhd\b|\b4k\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "4K"
    Regex("1080p", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "1080p"
    Regex("720p", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "720p"
    Regex("""\bcam\b|\bhdts\b|\btelesync\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "CAM"
    else -> null
}

enum class Health(val label: String) { FAST("Fast"), GOOD("Good"), WEAK("Weak"), DEAD("No seeds") }

fun health(seeders: Int) = when {
    seeders >= 100 -> Health.FAST
    seeders >= 20 -> Health.GOOD
    seeders >= 1 -> Health.WEAK
    else -> Health.DEAD
}

private val SEEDERS = Regex("""\x{1F464}\s*(\d+)""")
private val SIZE = Regex("""\x{1F4BE}\s*([\d.]+)\s*(TB|GB|MB|KB)""", RegexOption.IGNORE_CASE)
private val UNIT = mapOf("kb" to 1024.0, "mb" to 1024.0 * 1024, "gb" to 1024.0 * 1024 * 1024, "tb" to 1024.0 * 1024 * 1024 * 1024)

/**
 * An addon's streams, every shape the Stremio protocol has: a torrent by `infoHash` (with its
 * `fileIdx` and `sources` trackers), a `url` that plays as it is (with any `proxyHeaders`), and a
 * `ytId` or `externalUrl` handed to another app. The numbers most addons give sit inside the display
 * text — "name\n👤 84 💾 51.3 GB ⚙️ 1337x" — and `behaviorHints` carry the file's real name and size.
 * ponytail: archive (`rarUrls`…) and `nzbUrl` streams are skipped; few addons send them yet.
 */
fun toStreamHits(streams: JSONArray, kind: String, addon: String = "", addonUrl: String = ""): List<Hit> = streams.objects().mapNotNull { s ->
    val hash = s.optString("infoHash").lowercase().takeIf { HASH.matches(it) }
    val url = s.str("url")?.takeIf { isDirectLink(it) || isTorrentSource(it) }
    val outside = s.str("ytId")?.let { "https://www.youtube.com/watch?v=$it" } ?: s.str("externalUrl")
    if (hash == null && url == null && outside == null) return@mapNotNull null
    val hints = s.optJSONObject("behaviorHints")
    val text = (s.str("title") ?: s.str("description")).orEmpty()
    val label = s.optString("name").replace('\n', ' ').trim()
    val first = text.lineSequence().firstOrNull()?.trim().orEmpty()
    val name = hints?.str("filename") ?: first.ifEmpty { label }
    if (name.isEmpty()) return@mapNotNull null
    // Torrentio and the like name the one file they found and put the release on the first line:
    // "Show.S01.1080p" over "Show.S01E01.mkv" is a season pack, whatever the file says.
    val release = first.takeIf { it.isNotEmpty() && it != name && scopeOf(it) != Scope.EPISODE } ?: name
    val size = hints?.optLong("videoSize")?.takeIf { it > 0 }
        ?: SIZE.find(text)?.let { (it.groupValues[1].toDouble() * (UNIT[it.groupValues[2].lowercase()] ?: 0.0)).roundToLong() } ?: 0L
    val trackers = s.strings("sources").filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
    val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
        ?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
    Hit(
        id = hash ?: url ?: outside!!, name = name,
        uri = when {
            hash != null -> withTrackers(magnetFor(hash, name), trackers)
            url != null -> url
            else -> outside!!
        },
        seeders = SEEDERS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0, leechers = 0, size = size,
        quality = quality(name) ?: quality("$label $text"), kind = kind, scope = scopeOf(release), imdb = null, perFileSize = true, addon = addon,
        external = hash == null && url == null, headers = if (url != null) headers else emptyMap(),
        fileIdx = s.optInt("fileIdx", -1).takeIf { hash != null && it >= 0 },
        addonUrl = addonUrl, release = release,
    )
}

/** A pasted magnet or http(s) video link as a stream of its own: a file on the viewer's own server, say. */
fun linkHit(text: String): Hit? {
    val s = text.trim()
    if (!s.startsWith("magnet:?", ignoreCase = true) && !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(s)) return null
    val name = titleOf(s)
    return Hit(magnetHash(s) ?: s, name, s, 0, 0, 0, quality(name), if (isSeries(name)) "tv" else "movie", scopeOf(name), null, addon = "Link")
}

/** Same torrent from two addons is one row — keep the bigger swarm and the known size. */
fun merge(lists: List<List<Hit>>): List<Hit> {
    val best = LinkedHashMap<String, Hit>()
    for (hit in lists.flatten()) {
        val seen = best[hit.id]
        if (seen == null) {
            best[hit.id] = hit
            continue
        }
        val bigger = if (hit.size > seen.size) hit else seen
        best[hit.id] = (if (hit.seeders > seen.seeders) hit else seen).copy(
            seeders = maxOf(hit.seeders, seen.seeders),
            size = bigger.size,
            perFileSize = bigger.perFileSize,
        )
    }
    return best.values.toList()
}

/** Links first — they play at once — then seeders over leechers, other apps' last; a name matching every word typed doubles. */
fun rank(hits: List<Hit>, query: String = ""): List<Hit> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun score(h: Hit): Double {
        val full = words.isNotEmpty() && words.all { h.name.lowercase().contains(it) }
        return (h.seeders + h.leechers / 4.0) * if (full) 2 else 1
    }
    return hits.sortedWith(compareByDescending<Hit> { it.direct }.thenBy { it.external }.thenByDescending(::score))
}

/** Links first, then this season's pack, then loose episodes, whole-series dumps last. */
fun rankEpisode(hits: List<Hit>, query: String = "", season: Int? = null): List<Hit> {
    val (links, ordered) = rank(hits, query).partition { it.direct }
    fun thisPack(h: Hit): Boolean {
        if (h.seeders < 1 || h.scope != Scope.SEASON) return false
        val c = seasonsIn(h.release)
        return c.size <= 1 && (season == null || c.isEmpty() || season in c)
    }
    fun one(h: Hit) = h.scope == Scope.EPISODE && h.seeders >= 1
    return links + ordered.filter(::thisPack) + ordered.filter(::one) + ordered.filter { !thisPack(it) && !one(it) }
}

typealias OnHits = (List<Hit>) -> Unit

/* ---------------------------------------------------------------- search */

private const val SOURCE_MS = 12000L
private const val PLAY_GRACE_MS = 2000L

private suspend fun source(name: String, ms: Long, job: suspend () -> List<Hit>): List<Hit> {
    val t = System.currentTimeMillis()
    val out = withTimeoutOrNull(ms) { runCatching { withContext(Dispatchers.IO) { job() } }.getOrNull() }
    Log.i("releases", "$name ${System.currentTimeMillis() - t}ms n=${out?.size ?: "FAIL"}")
    return out.orEmpty()
}

/**
 * Every enabled addon that streams [type] for [id], at once; [onHits] hears the merged, ranked list
 * each time one answers, so the page fills as they land. One addon being unreachable is the normal case.
 */
private suspend fun fromAddons(type: String, id: String, lang: Lang?, onHits: OnHits?, order: (List<Hit>) -> List<Hit>): List<Hit> {
    val kind = kindOfType(type)
    val bags = mutableListOf<List<Hit>>()
    // A language is picked by name ("Hindi"), and only streams that say it count.
    fun now() = synchronized(bags) { order(merge(bags.toList())) }.let { all -> if (lang == null) all else all.filter { lang.match.containsMatchIn(it.name) } }
    // Off the main thread: ranking reruns on every addon that lands, and on a TV that froze the remote.
    withContext(Dispatchers.Default) {
        for (a in Addons.serving("stream", type, id)) launch {
            val hits = source(a.name, SOURCE_MS) { toStreamHits(a.get("stream", type, id).optJSONArray("streams") ?: JSONArray(), kind, a.name, a.url) }
            if (hits.isEmpty()) return@launch
            synchronized(bags) { bags.add(hits) }
            onHits?.invoke(now())
        }
    }
    return withContext(Dispatchers.Default) { now() }
}

/**
 * A film's streams from the viewer's addons. Without an id, [title] is looked up by name first;
 * a link pasted instead — a magnet, or a video on the viewer's own server — is the one stream.
 */
suspend fun searchStreams(title: String, imdb: String? = null, year: String? = null, lang: Lang? = null, type: String = "movie", onHits: OnHits? = null): List<Hit> {
    linkHit(title)?.let { return listOf(it).also { one -> onHits?.invoke(one) } }
    val id = imdb ?: Meta.resolve(listOfNotNull(title, year).joinToString(" "))?.takeIf { !it.isSeries }?.imdb ?: return emptyList()
    return fromAddons(type, id, lang, onHits) { rank(it, title) }
}

private val episodeMemo = java.util.concurrent.ConcurrentHashMap<String, List<Hit>>()

/**
 * Does release [name] say it is this episode? Null when it names none (a season pack): a batch
 * holds it or not, a single release names it or another one.
 */
fun releaseIs(name: String, season: Int, episode: Int, absolute: Int?): Boolean? =
    spanHolds(name, season, episode, absolute) ?: namesEpisode(name, season, episode, absolute)

/**
 * The addons number a long anime their own way, and can sit one off the episode list: asked for
 * 1001, they answer with "- 1002". [own] (the answers for this episode's id) keeps all but the
 * releases naming another episode; [near] (the neighbours' answers) gives those naming this one.
 * If that leaves nothing, [own] as it was.
 */
fun sameEpisode(own: List<Hit>, near: List<Hit>, season: Int, episode: Int, absolute: Int): List<Hit> {
    val kept = merge(listOf(own.filter { releaseIs(it.name, season, episode, absolute) != false }, near.filter { releaseIs(it.name, season, episode, absolute) == true }))
    return kept.ifEmpty { own }
}

/**
 * Every stream the addons have for one episode, by the episode's own id ("tt0944947:1:2", or an
 * anime catalogue's): packs included, since a pack streams just as well — only the one file is fetched.
 * Long anime also asks for the episodes either side, and keeps only releases numbered as this one ([sameEpisode]).
 */
suspend fun searchEpisode(title: Title, season: Int, episode: Int, onHits: OnHits? = null, lang: Lang? = null): List<Hit> {
    val own = title.episodes.find { it.season == season && it.episode == episode }
    val id = own?.id ?: "${title.imdb}:$season:$episode"
    val key = "$id:${lang?.code}"
    episodeMemo[key]?.let { return it }
    val order: (List<Hit>) -> List<Hit> = { rankEpisode(it, title.title, season) }
    val numbers = if (own != null && title.numberedThrough) absoluteNumbers(title.episodes) else emptyMap()
    val absolute = own?.let { numbers[it] }
    val hits = if (absolute == null) fromAddons(title.type, id, lang, onHits, order) else {
        val near = numbers.filterValues { it == absolute - 1 || it == absolute + 1 }.keys.map { it.id }.filter { it != id }.distinct()
        val bags = java.util.concurrent.ConcurrentHashMap<String, List<Hit>>()
        fun now() = order(sameEpisode(bags[id].orEmpty(), near.flatMap { bags[it].orEmpty() }, season, episode, absolute))
        coroutineScope {
            for (each in listOf(id) + near) launch {
                bags[each] = fromAddons(title.type, each, lang, { bags[each] = it; onHits?.invoke(now()) }) { it }
            }
        }
        withContext(Dispatchers.Default) { now() }
    }
    return hits.also { if (it.isNotEmpty()) episodeMemo[key] = it }
}

/**
 * The answer as soon as it is good enough to act on: [PLAY_GRACE_MS] after the first
 * rows land, or when the search ends if sooner. [onHits] keeps hearing every update.
 */
fun CoroutineScope.quickest(run: suspend (OnHits) -> List<Hit>, onHits: OnHits): Deferred<List<Hit>> {
    val result = CompletableDeferred<List<Hit>>()
    var latest = emptyList<Hit>()
    launch {
        var timer: Job? = null
        val all = try {
            run { hits ->
                latest = hits
                onHits(hits)
                if (hits.isNotEmpty() && timer == null) timer = launch { delay(PLAY_GRACE_MS); result.complete(latest) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            latest
        }
        timer?.cancel()
        result.complete(all)
    }
    return result
}

/* ------------------------------------------------------------------ pick */

/** Sample, trailer and extras folders sit beside the feature in a lot of releases. */
private val JUNK = Regex("""\b(sample|trailer|extras?|featurette|behind[. _-]?the[. _-]?scenes)\b""", RegexOption.IGNORE_CASE)

/** "Show/Season 2/Show - 05.mkv" → "Show/Season 2": where a pack's season labels live. */
private fun folderOf(f: TorrentFile) = f.path.removeSuffix(f.name).trimEnd('/', '\\')

/**
 * The one file a viewer meant: the tagged episode inside a pack ("S02E05", or
 * "S02E05#29" from [pickTag]), else the biggest video. Null when there is no
 * video, or a pack's names cannot say which episode.
 */
fun pickFile(files: List<TorrentFile>, tag: String? = null): Int? {
    val videos = files.filter { it.isVideo && !JUNK.containsMatchIn(it.name) }
    if (videos.isEmpty()) return null
    if (tag != null) {
        val m = Regex("^s(\\d+)e(\\d+)(?:#(\\d+))?$", RegexOption.IGNORE_CASE).find(tag)
        var match = emptyList<TorrentFile>()
        if (m != null) {
            val season = m.groupValues[1].toInt()
            val episode = m.groupValues[2].toInt()
            val absolute = m.groupValues[3].toIntOrNull()
            match = videos.filter { namesEpisode(it.name, season, episode, absolute, folderOf(it)) == true }
            // A pack of one season numbered from 1 with no season label anywhere: "- 05" is the fifth.
            if (match.isEmpty() && videos.all { seasonsIn(it.path).isEmpty() }) match = videos.filter { parseAbsolute(it.name) == episode }
        }
        if (match.isNotEmpty()) return match.maxBy { it.size }.index
        if (videos.size > 1) return null
    }
    return videos.maxBy { it.size }.index
}

/** Where a file sits in the show: SxxEyy, else its running number (in its folder's season, if labelled). */
private fun episodeKey(f: TorrentFile): Ep? =
    parseEpisode(f.name) ?: parseAbsolute(f.name)?.let { Ep(seasonsIn(f.path).firstOrNull() ?: 0, it) }

/** The episode [step] places from the one playing, in broadcast order, same torrent only. */
fun siblingEpisode(files: List<TorrentFile>, index: Int, step: Int): TorrentFile? {
    val playing = files.find { it.index == index } ?: return null
    if (episodeKey(playing) == null) return null
    val ordered = files.filter { it.isVideo && !JUNK.containsMatchIn(it.name) }
        .mapNotNull { f -> episodeKey(f)?.let { f to it } }
        .sortedWith(compareBy({ it.second.season }, { it.second.episode }))
    val at = ordered.indexOfFirst { it.first.index == index }
    if (at < 0) return null
    return ordered.getOrNull(at + step)?.first
}

fun nextEpisode(files: List<TorrentFile>, index: Int) = siblingEpisode(files, index, 1)
fun prevEpisode(files: List<TorrentFile>, index: Int) = siblingEpisode(files, index, -1)

/* ---------------------------------------------------------------- format */

/** 75 → "1:15", 3675 → "1:01:15" */
fun clock(seconds: Double): String {
    val t = maxOf(0L, seconds.toLong())
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

fun speed(bytes: Int): String = if (bytes > 1_000_000) "%.1f MB/s".format(bytes / 1e6) else "${(bytes / 1e3).toInt()} kB/s"

/** Coarse "how much is left" — seconds are noise next to a progress bar. */
fun left(seconds: Double): String {
    val mins = (maxOf(0.0, seconds) / 60).let { Math.round(it) }
    return when {
        mins < 1 -> "almost done"
        mins < 60 -> "${mins}m left"
        else -> "${mins / 60}h ${mins % 60}m left"
    }
}

fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - ms) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}

fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.2f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.0f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}

/* ------------------------------------------------------------- subtitles */

data class Sub(val release: String, val name: String, val language: String, val url: String, val season: Int?, val episode: Int?) {
    val label get() = "$language · ${release.ifEmpty { name }}"
}

/** SubDL: no download quota and a plain name search; it only serves zips. */
object SubDl {
    private const val API = "https://api.subdl.com/api/v1/subtitles"
    private const val FILES = "https://dl.subdl.com"

    fun toSubs(rows: JSONArray): List<Sub> = rows.objects().filter { it.str("url") != null }.map { r ->
        val url = r.optString("url")
        Sub(
            release = r.str("release_name") ?: r.optString("name"),
            name = r.str("name") ?: r.optString("release_name"),
            language = (r.str("language") ?: r.optString("lang")).uppercase(),
            url = if (url.startsWith("http")) url else "$FILES$url",
            // Guarded: a film would otherwise claim to be S0E0.
            season = r.str("season")?.toIntOrNull(),
            episode = r.str("episode")?.toIntOrNull(),
        )
    }

    suspend fun search(query: String, key: String, imdb: String?, season: Int?, episode: Int?, type: String?, language: String = "EN"): List<Sub> {
        val params = mutableListOf("api_key" to key, "languages" to language.ifBlank { "EN" }, "subs_per_page" to "30")
        when {
            imdb != null -> params += "imdb_id" to imdb
            query.isNotBlank() -> params += "film_name" to query
            else -> return emptyList()
        }
        type?.let { params += "type" to it }
        season?.let { params += "season_number" to it.toString() }
        episode?.let { params += "episode_number" to it.toString() }
        val body = JSONObject(Net.get("$API?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }))
        // A rejected key comes back as 200 with status: false.
        if (body.has("status") && !body.optBoolean("status", true)) throw IllegalStateException(body.optString("error", "SubDL refused the request"))
        return toSubs(body.optJSONArray("subtitles") ?: JSONArray())
    }
}

/**
 * A subtitles addon's answer. Languages are mostly three letters ("eng", "ger"); [LANGS] already
 * knows them, so they come back as the app's codes. The release fields are OpenSubtitles' own extras.
 */
fun toAddonSubs(body: JSONObject): List<Sub> = (body.optJSONArray("subtitles") ?: JSONArray()).objects().mapNotNull { r ->
    val url = r.str("url") ?: return@mapNotNull null
    val lang = r.optString("lang")
    val file = r.str("subtitleFileName")
    val release = r.str("movieReleaseName") ?: file?.substringBeforeLast('.').orEmpty()
    Sub(
        release = release,
        name = file ?: release,
        language = LANGS.find { it.match.containsMatchIn(lang) }?.code ?: lang.uppercase(),
        url = url,
        season = r.optInt("season").takeIf { it > 0 },
        episode = r.optInt("episode").takeIf { it > 0 },
    )
}

/**
 * Subtitles for what is playing: from every subtitles addon the viewer added, and SubDL's too when there is a [subdlKey].
 * Without an IMDb id the file's name is looked up first. In [language] (English when unset) —
 * or every language when there are none in it — one per release, and those whose release
 * shares the most words with [fileName] first: a subtitle cut for the same release keeps its timing.
 * Throws only when every source failed.
 */
suspend fun findSubtitles(
    query: String,
    imdb: String?,
    season: Int?,
    episode: Int?,
    type: String?,
    language: String,
    subdlKey: String,
    fileName: String,
): List<Sub> = coroutineScope {
    val lang = language.ifBlank { "EN" }
    val id = imdb ?: Meta.resolve(fileName.ifBlank { query })?.imdb
    val kind = if (season != null && episode != null) "series" else "movie"
    val video = id?.let { if (kind == "series") "$it:$season:$episode" else it }
    val sources = (
        video?.let { v -> Addons.serving("subtitles", kind, v).map { a -> async { a.name to runCatching { toAddonSubs(a.get("subtitles", kind, v)) } } } }.orEmpty() +
            listOfNotNull(subdlKey.takeIf { it.isNotBlank() }?.let { key -> async { "SubDL" to runCatching { SubDl.search(query, key, id, season, episode, type, lang) } } })
        ).map { it.await() }
    sources.forEach { (name, r) ->
        r.onSuccess { Log.i("Subtitles", "$name: ${it.size} for $id S${season}E$episode") }
            .onFailure { Log.w("Subtitles", "$name failed for $id", it) }
    }
    if (sources.isEmpty()) Log.w("Subtitles", "No IMDb id for \"$query\", nothing to search")
    if (sources.isNotEmpty() && sources.all { it.second.isFailure }) throw sources.first().second.exceptionOrNull()!!
    rankSubtitles(sources.flatMap { it.second.getOrDefault(emptyList()) }, lang, fileName)
}

fun rankSubtitles(subs: List<Sub>, language: String, fileName: String): List<Sub> {
    fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
    val playing = words(fileName)
    val mine = subs.filter { it.language.equals(language, ignoreCase = true) }
    return mine.ifEmpty { subs }
        .distinctBy { (it.language + it.release).lowercase().ifEmpty { it.url } }
        .sortedByDescending { (words(it.release) intersect playing).size }
}
