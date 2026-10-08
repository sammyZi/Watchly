package app.reel

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What a torrent turned out to be, from Cinemeta. */
data class Art(
    val imdb: String,
    val kind: String,
    val title: String,
    val year: String?,
    val rating: String?,
    val genres: List<String>,
    val runtime: String?,
    val plot: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
)

/** One torrent in the library, and where it was left off. */
data class Entry(
    val source: String,
    val name: String,
    val size: Long = 0,
    val art: Art? = null,
    val fileIndex: Int = -1,
    val position: Long = 0,
    val length: Long = 0,
    val added: Long = System.currentTimeMillis(),
    val watched: Long = 0,
) {
    val title get() = art?.title ?: Meta.searchableName(name).ifBlank { name }
    val progress get() = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f

    /** Past the opening minute and not into the credits: worth offering to resume. */
    val resumable get() = length > 0 && position > 60_000 && position < length - 120_000
}

/** Every torrent ever opened, kept as one JSON array in preferences. */
object Library {
    private lateinit var prefs: SharedPreferences
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        theme = prefs.getString("theme", "system") ?: "system"
        _entries.value = runCatching {
            val array = JSONArray(prefs.getString("entries", "[]"))
            (0 until array.length()).map { entryOf(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    var resume: Boolean
        get() = prefs.getBoolean("resume", true)
        set(value) = prefs.edit().putBoolean("resume", value).apply()

    /** Appearance: "system", "light" or "dark". Compose state, so the whole app follows a change at once. */
    var theme by androidx.compose.runtime.mutableStateOf("system")
        private set

    fun chooseTheme(value: String) {
        theme = value
        prefs.edit().putString("theme", value).apply()
    }

    /** The player's "Whole file" switch, remembered for the next stream like Watchly's. */
    var wholeFile: Boolean
        get() = prefs.getBoolean("wholeFile", false)
        set(value) = prefs.edit().putBoolean("wholeFile", value).apply()

    fun get(source: String) = _entries.value.find { it.source == source }

    @Synchronized
    fun update(source: String, change: (Entry?) -> Entry) {
        val list = _entries.value
        val old = list.find { it.source == source }
        val next = change(old)
        save(if (old == null) listOf(next) + list else list.map { if (it.source == source) next else it })
    }

    @Synchronized
    fun remove(source: String) = save(_entries.value.filter { it.source != source })

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<Entry>) {
        _entries.value = list
        prefs.edit().putString("entries", JSONArray(list.map(::jsonOf)).toString()).apply()
    }

    private fun jsonOf(e: Entry) = JSONObject()
        .put("source", e.source).put("name", e.name).put("size", e.size)
        .put("fileIndex", e.fileIndex).put("position", e.position).put("length", e.length)
        .put("added", e.added).put("watched", e.watched)
        .put("art", e.art?.let { a ->
            JSONObject().put("imdb", a.imdb).put("kind", a.kind).put("title", a.title).put("year", a.year)
                .put("rating", a.rating).put("genres", JSONArray(a.genres)).put("runtime", a.runtime)
                .put("plot", a.plot).put("poster", a.poster).put("backdrop", a.backdrop).put("logo", a.logo)
        })

    private fun entryOf(o: JSONObject) = Entry(
        source = o.getString("source"),
        name = o.getString("name"),
        size = o.optLong("size"),
        art = o.optJSONObject("art")?.let { a ->
            Art(
                a.getString("imdb"), a.getString("kind"), a.getString("title"), a.str("year"), a.str("rating"),
                a.optJSONArray("genres")?.let { g -> (0 until g.length()).map(g::getString) }.orEmpty(),
                a.str("runtime"), a.str("plot"), a.str("poster"), a.str("backdrop"), a.str("logo"),
            )
        },
        fileIndex = o.optInt("fileIndex", -1),
        position = o.optLong("position"),
        length = o.optLong("length"),
        added = o.optLong("added"),
        watched = o.optLong("watched"),
    )
}

/** org.json hands back "" or "null" for a missing field; this is null. */
private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** The `dn=` name a magnet carries, if any — shown until the real file list arrives. */
fun magnetName(magnet: String): String? =
    magnet.substringAfter("dn=", "").substringBefore('&').takeIf { it.isNotBlank() }
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

/** A name for a source before anything else is known about it. */
fun nameOf(source: String): String = magnetName(source)
    ?: source.substringAfter("btih:", "").take(12).ifBlank { null }
    ?: source.substringAfterLast('/').substringBeforeLast('.')

/** Artwork and details for a release name. Ported from the phone app's title.ts / meta.ts. */
object Meta {
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private val SCAFFOLD = Regex(
        """\b(?:19\d{2}|20\d{2}|s\d{1,2}e\d{1,2}|season|complete|\d{3,4}p|2160p|4k|bluray|brrip|bdrip|web[ -]?dl|webrip|hdtv|dvdrip|x26[45]|h ?26[45]|hevc|aac|ac3|dts)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "The.Matrix.1999.1080p.BluRay.x264-GRP" → "The Matrix": everything from the year or first quality tag on is scaffolding. */
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

    data class Hit(val id: String, val name: String, val year: String?, val kind: String)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * The candidate that is actually the thing asked for, or nothing. Cinemeta answers
     * *something* for any query, so the first result is often confidently wrong: the same
     * name wins, a prefix (sequel, spin-off) is second best, a name missing a word is out,
     * and a film's year settles ties.
     */
    fun bestMatch(hits: List<Hit>, query: String, year: String?): Hit? {
        val want = norm(query)
        if (want.isEmpty()) return null
        val words = want.split(' ')
        var best: Pair<Hit, Int>? = null
        for (hit in hits) {
            val got = norm(hit.name)
            var score = when {
                got == want -> 100
                got.startsWith(want) -> 60
                words.all { it in got.split(' ') } -> 45
                else -> continue
            }
            if (year != null && hit.kind == "movie" && hit.year != null) score += if (hit.year.take(4) == year) 25 else -30
            if (best == null || score > best.second) best = hit to score
        }
        return best?.takeIf { it.second >= 45 }?.first
    }

    suspend fun resolve(releaseName: String): Art? = withContext(Dispatchers.IO) {
        val query = searchableName(releaseName)
        if (query.length < 2) return@withContext null
        val year = yearOf(releaseName)
        val (likely, other) = if (isSeries(releaseName)) "series" to "movie" else "movie" to "series"
        val hit = bestMatch(search(query, likely), query, year)
            ?: bestMatch(search(query, other), query, year)
            ?: return@withContext null
        details(hit)
    }

    private fun search(query: String, type: String): List<Hit> = runCatching {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val metas = JSONObject(get("$CINEMETA/catalog/$type/top/search=$q.json")).getJSONArray("metas")
        (0 until metas.length()).map { metas.getJSONObject(it) }.mapNotNull { m ->
            val id = m.str("imdb_id") ?: m.str("id") ?: return@mapNotNull null
            if (!id.startsWith("tt")) return@mapNotNull null
            Hit(id, m.str("name") ?: return@mapNotNull null, m.str("year") ?: m.str("releaseInfo"), type)
        }
    }.getOrDefault(emptyList())

    private fun details(hit: Hit): Art {
        val m = runCatching { JSONObject(get("$CINEMETA/meta/${hit.kind}/${hit.id}.json")).getJSONObject("meta") }.getOrNull()
        val hub = "https://images.metahub.space"
        return Art(
            imdb = hit.id,
            kind = hit.kind,
            title = m?.str("name") ?: hit.name,
            year = m?.str("releaseInfo") ?: m?.str("year") ?: hit.year,
            rating = m?.str("imdbRating"),
            genres = (m?.optJSONArray("genre") ?: m?.optJSONArray("genres"))?.let { g -> (0 until minOf(3, g.length())).map(g::getString) }.orEmpty(),
            runtime = m?.str("runtime"),
            plot = m?.str("description"),
            poster = m?.str("poster") ?: "$hub/poster/medium/${hit.id}/img",
            backdrop = m?.str("background") ?: "$hub/background/medium/${hit.id}/img",
            logo = m?.str("logo"),
        )
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "TorrentPlay")
        try { inputStream.bufferedReader().use { it.readText() } } finally { disconnect() }
    }
}

/** Looks up artwork once for an entry that has none. */
suspend fun findArt(source: String, name: String? = null) {
    val entry = Library.get(source) ?: return
    if (entry.art != null) return
    val art = Meta.resolve(name ?: entry.name) ?: return
    Library.update(source) { (it ?: entry).copy(art = art) }
}
