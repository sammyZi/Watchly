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

private fun magnetFor(hash: String, name: String) =
    withTrackers("magnet:?xt=urn:btih:$hash&dn=${java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")}")

/* -------------------------------------------------------------- episodes */

data class Ep(val season: Int, val episode: Int)

fun episodeTag(season: Int, episode: Int) = "S%02dE%02d".format(season, episode)

/** Which episode a file name belongs to, or null. */
fun parseEpisode(name: String): Ep? {
    val found = Regex("""\bs(\d{1,2})[ ._-]*e(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(name)
        ?: Regex("""\b(\d{1,2})x(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(name)
    return found?.let { Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
}

/** Which seasons a release name claims; ranges first, then single tokens. */
fun seasonsIn(name: String): List<Int> {
    val out = sortedSetOf<Int>()
    val ranges = listOf(
        Regex("""\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b""", RegexOption.IGNORE_CASE),
        Regex("""\bseasons?[ ._-]*(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b""", RegexOption.IGNORE_CASE),
    )
    for (r in ranges) for (m in r.findAll(name)) {
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        for (i in minOf(a, b)..maxOf(a, b)) out.add(i)
    }
    if (out.isNotEmpty()) return out.toList()
    // The bare token allows no separator, so "Ocean's 8" is not season 8.
    val ones = listOf(
        Regex("""\bs(\d{1,2})(?=e\d{1,2}\b|\b)""", RegexOption.IGNORE_CASE),
        Regex("""\bseasons?[ ._-]*(\d{1,2})\b""", RegexOption.IGNORE_CASE),
    )
    for (r in ones) for (m in r.findAll(name)) out.add(m.groupValues[1].toInt())
    return out.toList()
}

enum class Scope(val label: String) { EPISODE("Episode"), SEASON("Season pack"), COMPLETE("Complete series") }

fun scopeOf(name: String): Scope {
    val i = RegexOption.IGNORE_CASE
    if (Regex("""\bs\d{1,2}\s?e\d{1,2}\s*[-–]\s*e?\d{1,2}\b""", i).containsMatchIn(name)) return Scope.SEASON
    if (Regex("""\bs\d{1,2}\s?e\d{1,2}\b|\b\d{1,2}x\d{2}\b""", i).containsMatchIn(name)) return Scope.EPISODE
    if (Regex("""\bseasons?\s*\d{1,2}\s*[-–]\s*\d{1,2}\b|\ball seasons\b|\bcomplete series\b""", i).containsMatchIn(name)) return Scope.COMPLETE
    Regex("""\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b""", i).find(name)?.let {
        if (it.groupValues[1].toInt() != it.groupValues[2].toInt()) return Scope.COMPLETE
    }
    if (Regex("""\bs\d{1,2}\b|\bseason\s*\d{1,2}\b""", i).containsMatchIn(name)) return Scope.SEASON
    return Scope.EPISODE
}

/* ------------------------------------------------------------------ hits */

data class Hit(
    /** The info hash — the one id every index agrees on. */
    val id: String,
    val name: String,
    val magnet: String,
    val seeders: Int,
    val leechers: Int,
    val size: Long,
    val quality: String?,
    val kind: String,
    val scope: Scope,
    val imdb: String?,
    /** Torrentio's size is the one file it found, even inside a pack. */
    val perFileSize: Boolean = false,
)

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

/** apibay subcategories: 201/202/204/207/209 films, 205/208 series. */
fun kindOf(category: Int) = when (category) {
    201, 202, 204, 207, 209 -> "movie"
    205, 208 -> "tv"
    else -> "other"
}

private fun JSONObject.num(key: String): Long = optString(key).toDoubleOrNull()?.toLong() ?: 0L

fun toHits(rows: JSONArray): List<Hit> = rows.objects()
    .filter { it.optString("id") != "0" && HASH.matches(it.optString("info_hash")) }
    .filter { it.num("category") in 200..299 }
    .map { r ->
        val name = r.optString("name")
        val hash = r.optString("info_hash").lowercase()
        Hit(
            id = hash, name = name, magnet = magnetFor(hash, name),
            seeders = r.num("seeders").toInt(), leechers = r.num("leechers").toInt(), size = r.num("size"),
            quality = quality(name), kind = kindOf(r.num("category").toInt()), scope = scopeOf(name),
            imdb = r.optString("imdb").takeIf { Regex("^tt\\d+$").matches(it) },
        )
    }

private val SEEDERS = Regex("""\x{1F464}\s*(\d+)""")
private val SIZE = Regex("""\x{1F4BE}\s*([\d.]+)\s*(TB|GB|MB|KB)""", RegexOption.IGNORE_CASE)
private val UNIT = mapOf("kb" to 1024.0, "mb" to 1024.0 * 1024, "gb" to 1024.0 * 1024 * 1024, "tb" to 1024.0 * 1024 * 1024 * 1024)

/** Torrentio's numbers sit inside a display string: "name\n👤 84 💾 51.3 GB ⚙️ 1337x". */
fun toTorrentioHits(streams: JSONArray, kind: String): List<Hit> = streams.objects()
    .filter { HASH.matches(it.optString("infoHash")) }
    .mapNotNull { s ->
        val lines = s.optString("title").split('\n')
        val name = lines.firstOrNull()?.trim().orEmpty().ifEmpty { s.optString("name").replace('\n', ' ') }
        if (name.isEmpty()) return@mapNotNull null
        val stats = lines.drop(1).joinToString(" ")
        val size = SIZE.find(stats)
        val hash = s.optString("infoHash").lowercase()
        Hit(
            id = hash, name = name, magnet = magnetFor(hash, name),
            seeders = SEEDERS.find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0, leechers = 0,
            size = size?.let { (it.groupValues[1].toDouble() * (UNIT[it.groupValues[2].lowercase()] ?: 0.0)).roundToLong() } ?: 0L,
            quality = quality(name), kind = kind, scope = scopeOf(name), imdb = null, perFileSize = true,
        )
    }

fun toYtsHits(movies: JSONArray): List<Hit> = movies.objects().flatMap { movie ->
    val title = movie.str("title_long") ?: movie.optString("title")
    val imdb = movie.optString("imdb_code").takeIf { Regex("^tt\\d+$").matches(it) }
    movie.optJSONArray("torrents")?.objects().orEmpty().mapNotNull { t ->
        val hash = t.optString("hash").lowercase()
        if (!HASH.matches(hash)) return@mapNotNull null
        val name = listOfNotNull(title, t.str("quality"), t.str("type")).joinToString(" ")
        Hit(hash, name, magnetFor(hash, name), t.num("seeds").toInt(), t.num("peers").toInt(), t.num("size_bytes"), quality(name), "movie", Scope.EPISODE, imdb)
    }
}

fun toEztvHits(torrents: JSONArray): List<Hit> = torrents.objects()
    .filter { HASH.matches(it.optString("hash")) && it.optString("title").isNotEmpty() }
    .map { t ->
        val name = t.optString("title")
        val hash = t.optString("hash").lowercase()
        Hit(hash, name, magnetFor(hash, name), t.num("seeds").toInt(), t.num("peers").toInt(), t.num("size_bytes"), quality(name), "tv", scopeOf(name), null)
    }

fun toCsvHits(rows: JSONArray): List<Hit> = rows.objects()
    .filter { HASH.matches(it.str("infohash") ?: it.optString("info_hash")) && it.optString("name").isNotEmpty() }
    .map { r ->
        val name = r.optString("name")
        val hash = (r.str("infohash") ?: r.optString("info_hash")).lowercase()
        Hit(
            hash, name, magnetFor(hash, name), r.num("seeders").toInt(), r.num("leechers").toInt(),
            if (r.has("size_bytes")) r.num("size_bytes") else r.num("size"), quality(name),
            if (parseEpisode(name) != null) "tv" else "movie", scopeOf(name), null,
        )
    }

/** Knaben mixes in music, books and games. */
private val WATCHABLE = Regex("^(tv|movies|anime)\\b", RegexOption.IGNORE_CASE)

fun toKnabenHits(rows: JSONArray): List<Hit> = rows.objects()
    .filter { HASH.matches(it.optString("hash")) && WATCHABLE.containsMatchIn(it.optString("category")) && it.optString("title").isNotEmpty() }
    .map { r ->
        val name = r.optString("title")
        val hash = r.optString("hash").lowercase()
        // Our own magnet: theirs still points at trackers that died years ago.
        Hit(
            hash, name, magnetFor(hash, name), r.num("seeders").toInt(), r.num("peers").toInt(), r.num("bytes"), quality(name),
            if (r.optString("category").lowercase().startsWith("movies")) "movie" else "tv", scopeOf(name), null,
        )
    }

private val NYAA_SIZE = Regex("""([\d.]+)\s*([KMGT])i?B""", RegexOption.IGNORE_CASE)

private fun unescapeXml(s: String) = Regex("&(#\\d+|[a-z]+);", RegexOption.IGNORE_CASE).replace(s) { m ->
    val code = m.groupValues[1]
    if (code.startsWith("#")) code.drop(1).toIntOrNull()?.toChar()?.toString() ?: m.value
    else mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")[code.lowercase()] ?: m.value
}

/** Nyaa's RSS feed, plain enough to read with a pattern. */
fun toNyaaHits(xml: String): List<Hit> = Regex("<item>([\\s\\S]*?)</item>").findAll(xml).mapNotNull { m ->
    val item = m.groupValues[1]
    fun field(tag: String) = Regex("<$tag>([\\s\\S]*?)</$tag>").find(item)?.groupValues?.get(1)?.trim().orEmpty()
    val hash = field("nyaa:infoHash").lowercase()
    val name = unescapeXml(field("title"))
    if (!HASH.matches(hash) || name.isEmpty()) return@mapNotNull null
    val size = NYAA_SIZE.find(field("nyaa:size"))
    Hit(
        hash, name, magnetFor(hash, name), field("nyaa:seeders").toIntOrNull() ?: 0, field("nyaa:leechers").toIntOrNull() ?: 0,
        size?.let { (it.groupValues[1].toDouble() * (UNIT["${it.groupValues[2].lowercase()}b"] ?: 0.0)).roundToLong() } ?: 0L,
        quality(name), "tv", scopeOf(name), null,
    )
}.toList()

/** Same torrent from two indexes is one row — keep the bigger swarm and the known size. */
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

/** Seeders outweigh leechers; a name matching every word typed doubles. */
fun rank(hits: List<Hit>, query: String = ""): List<Hit> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun score(h: Hit): Double {
        val full = words.isNotEmpty() && words.all { h.name.lowercase().contains(it) }
        return (h.seeders + h.leechers / 4.0) * if (full) 2 else 1
    }
    return hits.sortedByDescending(::score)
}

/** This season's pack first, then loose episodes, whole-series dumps last. */
fun rankEpisode(hits: List<Hit>, query: String = "", season: Int? = null): List<Hit> {
    val ordered = rank(hits, query)
    fun thisPack(h: Hit): Boolean {
        if (h.seeders < 1 || h.scope != Scope.SEASON) return false
        val c = seasonsIn(h.name)
        return c.size <= 1 && (season == null || c.isEmpty() || season in c)
    }
    fun one(h: Hit) = h.scope == Scope.EPISODE && h.seeders >= 1
    return ordered.filter(::thisPack) + ordered.filter(::one) + ordered.filter { !thisPack(it) && !one(it) }
}

/** "Dune: Part One" is filed as "Dune 2021" everywhere: the part before the subtitle. */
fun aliasOf(title: String): String? {
    val short = title.split(Regex(":| - "))[0].trim()
    return short.takeIf { it.isNotEmpty() && !it.equals(title.trim(), ignoreCase = true) }
}

private fun tokens(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

/** Every word of the title appears, as a word — keeps "IMDb Top 250" packs out. */
fun namesTitle(release: String, title: String): Boolean {
    val want = tokens(title)
    if (want.isEmpty()) return true
    val got = tokens(release).toSet()
    return want.all { it in got }
}

private val RELEASE_MARK = Regex(
    "^(?:s\\d{1,2}(?:e\\d{1,3})?|e\\d{1,3}|\\d{1,2}x\\d{2}|(?:19|20)\\d{2}|season|seasons|complete|series|\\d{3,4}p|4k|uhd|hdr|bluray|blu|bdrip|brrip|web|webrip|webdl|hdtv|dvdrip|dvd|x264|x265|h264|h265|hevc|avc|xvid|aac|ac3|eac3|dts|ddp?5|truehd|atmos|remux|proper|repack|extended|imax|uncut|limited|internal|multi|dual|hindi|eng|english|subbed|dubbed)$",
    RegexOption.IGNORE_CASE,
)

/** The part of a release name that is the title, in order. */
fun releaseTitle(release: String): List<String> = tokens(release).takeWhile { !RELEASE_MARK.matches(it) }

/** This release is *this* title — "House" must not match "House of the Dragon". Short tags allowed. */
fun sameTitle(release: String, title: String): Boolean {
    val want = tokens(title)
    if (want.isEmpty()) return true
    val got = releaseTitle(release)
    if (got.size < want.size) return false
    for (i in want.indices) if (got[i] != want[i]) return false
    return got.drop(want.size).all { it.length <= 2 }
}

typealias OnHits = (List<Hit>) -> Unit

/* ---------------------------------------------------------------- search */

private const val API = "https://apibay.org/q.php"
private const val CSV = "https://torrents-csv.com/service/search"
private const val TORRENTIO = "https://torrentio.strem.fun/stream"
private const val YTS = "https://yts.mx/api/v2/list_movies.json"
private const val EZTV = "https://eztv.tf/api/get-torrents"
private const val KNABEN = "https://api.knaben.org/v1"
private const val NYAA = "https://nyaa.si/"
private const val SOURCE_MS = 8000L
private const val KNABEN_MS = 15000L
private const val PLAY_GRACE_MS = 2000L

private suspend fun source(name: String, ms: Long, job: suspend () -> List<Hit>): List<Hit> {
    val t = System.currentTimeMillis()
    val out = withTimeoutOrNull(ms) { runCatching { withContext(Dispatchers.IO) { job() } }.getOrNull() }
    Log.i("releases", "$name ${System.currentTimeMillis() - t}ms n=${out?.size ?: "FAIL"}")
    return out.orEmpty()
}

private suspend fun fromApibay(q: String) = toHits(JSONArray(Net.get("$API?q=${enc(q)}&cat=200", quiet = true)))
private suspend fun fromCsv(q: String) = JSONObject(Net.get("$CSV?q=${enc(q)}&size=50", quiet = true)).optJSONArray("torrents")?.let(::toCsvHits).orEmpty()
private suspend fun fromYts(q: String) = JSONObject(Net.get("$YTS?query_term=${enc(q)}&limit=50", quiet = true)).optJSONObject("data")?.optJSONArray("movies")?.let(::toYtsHits).orEmpty()
private suspend fun fromEztv(imdb: String) = JSONObject(Net.get("$EZTV?imdb_id=${imdb.removePrefix("tt")}&limit=100", quiet = true)).optJSONArray("torrents")?.let(::toEztvHits).orEmpty()
private suspend fun fromTorrentio(path: String, kind: String) = toTorrentioHits(JSONObject(Net.get("$TORRENTIO/$path.json", quiet = true)).optJSONArray("streams") ?: JSONArray(), kind)
private suspend fun fromNyaa(q: String) = toNyaaHits(Net.get("$NYAA?page=rss&c=1_0&f=0&q=${enc(q)}", quiet = true))
private suspend fun fromKnaben(q: String): List<Hit> {
    val body = JSONObject().put("search_type", "100%").put("search_field", "title").put("query", q).put("order_by", "seeders")
        .put("order_direction", "desc").put("size", 100).put("hide_unsafe", true).put("hide_xxx", true)
    return JSONObject(Net.postJson(KNABEN, body.toString(), 16_000, quiet = true)).optJSONArray("hits")?.let(::toKnabenHits).orEmpty()
}

/** Everything so far as one ranked, on-topic list: strict title match, then words, then all. */
private fun settle(bags: List<List<Hit>>, full: String, title: String, alias: String?, imdb: String?): List<Hit> {
    val merged = merge(bags).let { all -> if (imdb != null) all.filter { it.imdb == null || it.imdb == imdb } else all }
    val found = rank(merged, full)
    val exact = found.filter { sameTitle(it.name, alias ?: title) }
    val named = found.filter { namesTitle(it.name, alias ?: title) }
    return exact.ifEmpty { named.ifEmpty { found } }
}

/**
 * Every index at once; [onHits] hears the merged, ranked list each time one answers,
 * so the page fills as results land. One source being unreachable is the normal case.
 */
suspend fun searchTorrents(
    title: String,
    imdb: String? = null,
    year: String? = null,
    suffix: String? = null,
    series: Boolean = false,
    onHits: OnHits? = null,
): List<Hit> {
    val tail = listOfNotNull(suffix, year).joinToString(" ")
    val full = listOf(title, tail).filter { it.isNotEmpty() }.joinToString(" ")
    val alias = aliasOf(title)
    val aliasQuery = alias?.let { listOf(it, tail).filter { s -> s.isNotEmpty() }.joinToString(" ") }
    val bags = mutableListOf<List<Hit>>()
    fun now() = synchronized(bags) { settle(bags.toList(), full, title, alias, imdb) }
    fun add(hits: List<Hit>) {
        if (hits.isEmpty()) return
        synchronized(bags) { bags.add(hits) }
        onHits?.invoke(now())
    }
    // The alias is a looser question; its answers have to carry the year to count.
    fun addAliased(hits: List<Hit>) = add(if (year != null) hits.filter { year in it.name } else hits)

    coroutineScope {
        launch { add(source("apibay", SOURCE_MS) { fromApibay(full) }) }
        launch { add(source("csv", SOURCE_MS) { fromCsv(full) }) }
        launch { add(source("knaben", KNABEN_MS) { fromKnaben(full) }) }
        launch { add(source("nyaa", SOURCE_MS) { fromNyaa(full) }) }
        if (aliasQuery != null) {
            launch { addAliased(source("apibay-alias", SOURCE_MS) { fromApibay(aliasQuery) }) }
            launch { addAliased(source("knaben-alias", KNABEN_MS) { fromKnaben(aliasQuery) }) }
        }
        if (suffix == null && !series) {
            launch { add(source("yts", SOURCE_MS) { fromYts(imdb ?: full) }) }
            if (imdb != null) launch { add(source("torrentio", SOURCE_MS) { fromTorrentio("movie/$imdb", "movie") }) }
        }
    }
    return now()
}

private val episodeMemo = java.util.concurrent.ConcurrentHashMap<String, List<Hit>>()

/**
 * Every release that can play one episode, packs included: a pack streams just as
 * well (only the one file is fetched) and usually has far more seeders.
 */
suspend fun searchEpisode(show: String, season: Int, episode: Int, imdb: String?, onHits: OnHits? = null): List<Hit> {
    val key = "${imdb ?: show}:$season:$episode"
    episodeMemo[key]?.let { return it }
    fun plays(h: Hit): Boolean {
        parseEpisode(h.name)?.let { return it.season == season && it.episode == episode }
        val claims = seasonsIn(h.name)
        return claims.isEmpty() || season in claims
    }
    var loose = emptyList<Hit>()
    var packs = emptyList<Hit>()
    var byId = emptyList<Hit>()
    val lock = Any()
    fun current() = synchronized(lock) { rankEpisode(merge(listOf(loose, packs, byId)).filter(::plays), show, season) }
    val tag = episodeTag(season, episode)
    coroutineScope {
        launch {
            val hits = searchTorrents(show, imdb, suffix = tag) { synchronized(lock) { loose = it }; onHits?.invoke(current()) }
            synchronized(lock) { loose = hits }
        }
        // "Prison Break Season 1 Complete" carries no episode tag: ask for the show by name too.
        launch {
            val hits = searchTorrents(show, imdb, series = true) { synchronized(lock) { packs = it }; onHits?.invoke(current()) }
            synchronized(lock) { packs = hits }
        }
        if (imdb != null) {
            launch {
                val hits = source("torrentio", SOURCE_MS) { fromTorrentio("series/$imdb:$season:$episode", "tv") }
                if (hits.isNotEmpty()) { synchronized(lock) { byId = merge(listOf(byId, hits)) }; onHits?.invoke(current()) }
            }
            launch {
                val hits = source("eztv", SOURCE_MS) { fromEztv(imdb) }
                if (hits.isNotEmpty()) { synchronized(lock) { byId = merge(listOf(byId, hits)) }; onHits?.invoke(current()) }
            }
        }
    }
    return current().also { if (it.isNotEmpty()) episodeMemo[key] = it }
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

private fun episodePattern(tag: String): Regex? {
    val m = Regex("^s(\\d+)e(\\d+)$", RegexOption.IGNORE_CASE).find(tag) ?: return null
    val s = m.groupValues[1].toInt()
    val e = m.groupValues[2].toInt()
    return Regex("(?:s0*$s[ ._-]*e0*$e|\\b0*${s}x0*$e)\\b", RegexOption.IGNORE_CASE)
}

/**
 * The one file a viewer meant: the tagged episode inside a pack, else the biggest
 * video. Null when there is no video, or a pack's names cannot say which episode.
 */
fun pickFile(files: List<TorrentFile>, tag: String? = null): Int? {
    val videos = files.filter { it.isVideo && !JUNK.containsMatchIn(it.name) }
    if (videos.isEmpty()) return null
    if (tag != null) {
        val wanted = episodePattern(tag)
        val match = if (wanted != null) videos.filter { wanted.containsMatchIn(it.name) } else emptyList()
        if (match.isNotEmpty()) return match.maxBy { it.size }.index
        if (videos.size > 1) return null
    }
    return videos.maxBy { it.size }.index
}

/** The episode [step] places from the one playing, in broadcast order, same torrent only. */
fun siblingEpisode(files: List<TorrentFile>, index: Int, step: Int): TorrentFile? {
    val playing = files.find { it.index == index } ?: return null
    if (parseEpisode(playing.name) == null) return null
    val ordered = files.filter { it.isVideo && !JUNK.containsMatchIn(it.name) }
        .mapNotNull { f -> parseEpisode(f.name)?.let { f to it } }
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

/** OpenSubtitles through Stremio's add-on: no key, by IMDb id, and plain UTF-8 .srt files. */
object OpenSubs {
    private const val API = "https://opensubtitles-v3.strem.io/subtitles"

    /** Its languages are three letters ("eng", "ger"); [LANGS] already knows them, so they come back as the app's codes. */
    fun toSubs(body: JSONObject): List<Sub> = (body.optJSONArray("subtitles") ?: JSONArray()).objects().mapNotNull { r ->
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

    suspend fun search(imdb: String, season: Int?, episode: Int?): List<Sub> {
        if (!imdb.startsWith("tt")) return emptyList()
        val path = if (season != null && episode != null) "series/$imdb:$season:$episode" else "movie/$imdb"
        return toSubs(JSONObject(Net.get("$API/$path.json")))
    }
}

/**
 * Subtitles for what is playing: OpenSubtitles always, SubDL's too when the build has [subdlKey].
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
    val sources = listOfNotNull(
        id?.let { async { "OpenSubtitles" to runCatching { OpenSubs.search(it, season, episode) } } },
        subdlKey.takeIf { it.isNotBlank() }?.let { key -> async { "SubDL" to runCatching { SubDl.search(query, key, id, season, episode, type, lang) } } },
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
