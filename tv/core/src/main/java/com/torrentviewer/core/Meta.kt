package com.torrentviewer.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class Episode(
    /** Cinemeta's own "tt10986410:1:2". */
    val id: String,
    val season: Int,
    val episode: Int,
    val name: String,
    val overview: String?,
    val thumbnail: String?,
    val released: String?,
)

/** A browsable title, from Stremio's public Cinemeta (no API key needed). */
data class Title(
    val imdb: String,
    /** "movie" or "tv". */
    val kind: String,
    val title: String,
    val year: String? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val poster: String? = null,
    val plot: String? = null,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val awards: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val runtime: String? = null,
    /** YouTube video id, from /meta only. */
    val trailer: String? = null,
    /** Series only, and only from /meta — a catalog row carries no episode list. */
    val episodes: List<Episode> = emptyList(),
) {
    val isSeries get() = kind == "tv"
    val art get() = backdrop ?: poster

    /** 16:9 art for a landscape card; MetaHub carries a background for nearly every id. */
    val wide get() = backdrop ?: "https://images.metahub.space/background/medium/$imdb/img"

    /** Cinemeta files anime under "Animation"; nyaa answers nothing for a Western cartoon anyway. */
    val isAnime get() = genres.any { it == ANIME_GENRE || it == "Animation" }

    /** One Piece's thousand episodes in arc "seasons": listed by the number everyone knows them by instead. */
    val numberedThrough get() = isAnime && episodes.count { it.season > 0 } > 100

    /** "2021 · 2h 35m · Sci-Fi · Drama" */
    fun facts(): List<String> = listOfNotNull(year?.take(9), runtime) + genres.take(3)
}

/** Not a Cinemeta genre: asking for it switches to the Kitsu anime catalogue. */
const val ANIME_GENRE = "Anime"

val GENRES = listOf(
    ANIME_GENRE, "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
    "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western",
)

fun kindOfGenre(genre: String) = if (genre == ANIME_GENRE) "tv" else "movie"

object Meta {
    private const val BASE = "https://v3-cinemeta.strem.io/meta"
    /** Popularity shelves, asked for directly to save a redirect per rail. */
    private const val CATALOG = "https://cinemeta-catalogs.strem.io/top/catalog"
    /** Search has to go through the front door, which actually reads the query. */
    private const val SEARCH = "https://v3-cinemeta.strem.io/catalog"
    private const val ANIME = "https://anime-kitsu.strem.fun/catalog/series/kitsu-anime-trending.json"

    /** A day: charts move slowly and ratings slower. */
    private const val TTL = 24 * 60 * 60 * 1000L
    /** Bumped whenever Title gains a field, so old snapshots are never read back. */
    private const val STORE = "meta:tv6:"

    private val memory = ConcurrentHashMap<String, Any>()

    /** Memory over disk over network; a stale disk copy is served if the fetch fails. */
    private suspend fun <T> remember(key: String, read: (String) -> T, write: (T) -> String, fetch: suspend () -> T): T {
        @Suppress("UNCHECKED_CAST")
        (memory[key] as T?)?.let { return it }
        var stale: T? = null
        Kv.global(STORE + key)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                val value = read(o.getString("value"))
                if (System.currentTimeMillis() - o.getLong("at") < TTL) {
                    memory[key] = value as Any
                    return value
                }
                stale = value
            }
        }
        return try {
            val value = fetch()
            memory[key] = value as Any
            Kv.setGlobal(STORE + key, JSONObject().put("at", System.currentTimeMillis()).put("value", write(value)).toString())
            value
        } catch (e: Exception) {
            stale ?: throw e
        }
    }

    /** Shelves already in memory, for painting a remount on its first frame. */
    @Suppress("UNCHECKED_CAST")
    fun peekCatalog(kind: String, genre: String?): List<Title>? = memory["shelf:$kind:${genre.orEmpty()}"] as List<Title>?

    suspend fun catalog(kind: String, genre: String? = null): List<Title> = runCatching {
        val type = if (kind == "tv") "series" else "movie"
        val url = if (genre == ANIME_GENRE) ANIME else "$CATALOG/$type/top${genre?.let { "/genre=${enc(it)}" }.orEmpty()}.json"
        remember("shelf:$kind:${genre.orEmpty()}", ::titlesFrom, ::titlesJson) {
            toTitles(JSONObject(Net.get(url)).optJSONArray("metas") ?: JSONArray(), kind)
        }
    }.getOrDefault(emptyList())

    suspend fun title(imdb: String, kind: String): Title? = runCatching {
        remember("title:$kind:$imdb", { titlesFrom(it).first() }, { titlesJson(listOf(it)) }) {
            val type = if (kind == "tv") "series" else "movie"
            val meta = JSONObject(Net.get("$BASE/$type/$imdb.json")).getJSONObject("meta")
            meta.put("imdb_id", imdb)
            toTitles(JSONArray().put(meta), kind, withEpisodes = true).first().let { if (it.numberedThrough) withStills(it) else it }
        }
    }.getOrNull()

    /**
     * Long anime: Cinemeta's episode stills are mostly 404s, TVmaze has nearly every one.
     * TVmaze counts seasons by year and recaps as episodes, so they pair up by air date
     * (a day either way: Cinemeta's is UTC, TVmaze's local) — 1165 of One Piece's 1179.
     */
    private suspend fun withStills(t: Title): Title = runCatching {
        val show = JSONObject(Net.get("https://api.tvmaze.com/lookup/shows?imdb=${t.imdb}", quiet = true)).getInt("id")
        val byDay = JSONArray(Net.get("https://api.tvmaze.com/shows/$show/episodes", quiet = true)).objects()
            .mapNotNull { o -> o.optJSONObject("image")?.str("medium")?.let { o.optString("airdate") to it } }
            .groupBy({ it.first }, { it.second }).mapValues { ArrayDeque(it.value) }
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        t.copy(episodes = t.episodes.map { e ->
            val aired = e.released?.take(10)?.takeIf { e.season > 0 }?.let { runCatching { day.parse(it) }.getOrNull() } ?: return@map e
            listOf(0, 1, -1).firstNotNullOfOrNull { byDay[day.format(java.util.Date(aired.time + it * 86_400_000L))]?.removeFirstOrNull() }
                ?.let { e.copy(thumbnail = it) } ?: e
        })
    }.getOrDefault(t)

    /** Not persisted: queries are one-off. */
    suspend fun search(query: String, kind: String): List<Title> = runCatching {
        val key = "find:$kind:${query.lowercase()}"
        @Suppress("UNCHECKED_CAST")
        (memory[key] as List<Title>?) ?: run {
            val type = if (kind == "tv") "series" else "movie"
            toTitles(JSONObject(Net.get("$SEARCH/$type/top/search=${enc(query)}.json")).optJSONArray("metas") ?: JSONArray(), kind)
                .also { memory[key] = it }
        }
    }.getOrDefault(emptyList())

    /** What a torrent is, from its release name alone. Null rather than a guess. */
    suspend fun resolve(releaseName: String): Title? = coroutineScope {
        val query = searchableName(releaseName)
        if (query.length < 2) return@coroutineScope null
        val year = yearOf(releaseName)
        val kind = if (isSeries(releaseName)) "tv" else "movie"
        val likely = async { search(query, kind) }
        val other = async { search(query, if (kind == "tv") "movie" else "tv") }
        bestMatch(likely.await(), query, year) ?: bestMatch(other.await(), query, year)
    }

    /** Genres the viewer's own titles are mostly made of, each with the kind it was watched as. */
    suspend fun watchedGenres(seen: List<Pair<String, String>>, limit: Int = 3): List<Pair<String, String>> = coroutineScope {
        val titles = seen.map { (imdb, kind) -> async { title(imdb, kind) } }.awaitAll().filterNotNull()
        topGenres(titles, limit)
    }
}

enum class Sort(val label: String) { POPULAR("Popular"), RATING("Top rated"), YEAR("Newest"), TITLE("A–Z") }

/** A title missing the field goes last, not as zero — unrated is unknown, not bad — in the catalogue's own order. */
fun sortTitles(titles: List<Title>, sort: Sort): List<Title> {
    fun key(t: Title) = (if (sort == Sort.RATING) t.rating else t.year?.take(4))?.toDoubleOrNull()
    return when (sort) {
        Sort.POPULAR -> titles
        Sort.TITLE -> titles.sortedBy { it.title.lowercase() }
        else -> titles.sortedWith(compareBy(nullsLast(reverseOrder())) { t: Title -> key(t) })
    }
}

/** Every genre counts; ties keep input order. Each carries the kind it was mostly seen as. */
fun topGenres(titles: List<Title>, limit: Int = 3): List<Pair<String, String>> {
    val tally = LinkedHashMap<String, IntArray>()
    for (t in titles) for (g in t.genres) {
        val at = tally.getOrPut(g) { IntArray(2) }
        at[0]++
        if (t.kind == "movie") at[1]++
    }
    return tally.entries.sortedByDescending { it.value[0] }.take(limit)
        .map { (g, at) -> g to if (at[1] * 2 >= at[0]) "movie" else "tv" }
}

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * The candidate that is actually the thing asked for, or nothing. Cinemeta answers
 * *something* for any query: same name wins, a prefix (sequel) is second, a name
 * missing a word is out, and a film's year settles ties.
 */
fun bestMatch(titles: List<Title>, query: String, year: String?): Title? {
    val want = norm(query)
    if (want.isEmpty()) return null
    val words = want.split(' ')
    var best: Pair<Title, Int>? = null
    for (t in titles) {
        val got = norm(t.title)
        var score = when {
            got == want -> 100
            got.startsWith(want) -> 60
            words.all { it in got.split(' ') } -> 45
            else -> continue
        }
        if (year != null && t.kind == "movie" && t.year != null) score += if (t.year.take(4) == year) 25 else -30
        if (best == null || score > best.second) best = t to score
    }
    return best?.takeIf { it.second >= 45 }?.first
}

/** Rows without art are unusable on a shelf. One row per id. Episodes only from /meta. */
fun toTitles(metas: JSONArray, kind: String, withEpisodes: Boolean = false): List<Title> {
    val seen = HashSet<String>()
    return metas.objects().mapNotNull { m ->
        val id = m.str("imdb_id") ?: m.str("id") ?: return@mapNotNull null
        if (!Regex("^tt\\d+$").matches(id) || m.str("poster") == null || !seen.add(id)) return@mapNotNull null
        Title(
            imdb = id,
            kind = kind,
            title = m.optString("name"),
            // A series' releaseInfo is a range ("2020–2023"); the leading year matters.
            year = m.str("year") ?: m.str("releaseInfo") ?: m.str("released")?.take(4),
            rating = m.str("imdbRating"),
            genres = (m.optJSONArray("genre") ?: m.optJSONArray("genres"))?.let { g -> (0 until minOf(3, g.length())).map { g.optString(it) } }.orEmpty(),
            poster = m.str("poster"),
            plot = m.str("description"),
            cast = m.strings("cast"),
            directors = m.strings("director"),
            awards = m.str("awards"),
            backdrop = m.str("background"),
            logo = m.str("logo"),
            runtime = m.str("runtime"),
            trailer = trailerOf(m),
            episodes = if (withEpisodes) toEpisodes(m.optJSONArray("videos")) else emptyList(),
        )
    }
}

/* ---------------------------------------------------------- cast, awards */

private val photos = ConcurrentHashMap<String, String>()

/**
 * An actor's photo, by name. Cinemeta lists the cast without pictures; IMDb's public
 * search suggestions carry one, keyless. Of everyone with the name, the best-known with a
 * picture — "Simon Baker" the actor, not the camera operator. Null when there is none.
 */
suspend fun castPhoto(name: String): String? {
    photos[name]?.let { return it.ifEmpty { null } }
    val found = runCatching {
        photoFrom(JSONObject(Net.get("https://v3.sg.media-imdb.com/suggestion/x/${enc(name.lowercase())}.json", quiet = true)))
    }.getOrNull()
    photos[name] = found.orEmpty()
    return found
}

/** The most popular person (lowest rank) in a suggestion answer who has a picture, sized for a small avatar. */
fun photoFrom(answer: JSONObject): String? = answer.optJSONArray("d")?.objects().orEmpty()
    .filter { it.optString("id").startsWith("nm") && it.optJSONObject("i")?.str("imageUrl") != null }
    .minByOrNull { it.optInt("rank", Int.MAX_VALUE) }
    ?.optJSONObject("i")?.str("imageUrl")
    // Amazon's image server scales on request: a 300px-tall copy, not the full-size original.
    ?.replace(Regex("\\._V1_.*\\.jpg$"), "._V1_UY300_.jpg")

/** "Won 2 Oscars. 120 wins & 200 nominations total" — the headline, and the two counts. */
data class Awards(val headline: String?, val wins: Int?, val nominations: Int?)

fun awardsOf(text: String): Awards {
    val wins = Regex("(\\d+) wins?", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    val noms = Regex("(\\d+) nominations?", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    // The headline is the sentence about a named prize, not the tally.
    val headline = text.split('.').map { it.trim() }.firstOrNull { it.isNotEmpty() && !Regex("^\\d+ (wins?|nominations?)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    return Awards(headline, wins, noms)
}

/** Two shapes in the wild: `trailerStreams` on newer records, `trailers` (with clips mixed in) on older ones. */
fun trailerOf(m: JSONObject): String? = m.str("trailer")
    ?: m.optJSONArray("trailerStreams")?.objects()?.firstNotNullOfOrNull { it.str("ytId") }
    ?: m.optJSONArray("trailers")?.objects()?.firstOrNull { it.optString("type") == "Trailer" }?.str("source")

/** Where to read what other people thought: IMDb by id, the rest by search, since a guessed slug is worse than a search page. */
fun reviewLinks(title: Title): List<Pair<String, String>> {
    val q = enc("${title.title} ${title.year?.take(4).orEmpty()}".trim())
    return listOf(
        "IMDb" to "https://www.imdb.com/title/${title.imdb}/reviews",
        "Rotten Tomatoes" to "https://www.rottentomatoes.com/search?search=$q",
        "Metacritic" to "https://www.metacritic.com/search/$q/",
        "Letterboxd" to "https://letterboxd.com/search/$q/",
    )
}

/**
 * The episode's number counted straight through the show, the way anime is released:
 * Jujutsu Kaisen S2E5 is "- 29". Specials don't count. A season whose numbers already
 * carry on from the last one (not starting at 1) is taken as is.
 */
fun absoluteNumber(episodes: List<Episode>, season: Int, number: Int): Int? {
    if (season <= 0) return null
    val inSeason = episodes.filter { it.season == season }
    if (inSeason.isEmpty()) return null
    return if (inSeason.minOf { it.episode } > 1) number else episodes.count { it.season in 1 until season } + number
}

/** An episode as the player names it: long anime by its number through the show ("Episode 1071"), else "S21:E5". */
fun episodeLabel(title: Title?, season: Int, episode: Int): String =
    title?.takeIf { it.numberedThrough }?.let { absoluteNumber(it.episodes, season, episode) }?.let { "Episode $it" } ?: "S$season:E$episode"

/** [episodeLabel] from a file's name; an anime release that names no season ("One Piece - 1071") goes by its bare number. */
fun fileEpisodeLabel(title: Title?, name: String): String? =
    parseEpisode(name)?.let { episodeLabel(title, it.season, it.episode) }
        ?: parseAbsolute(name)?.takeIf { title?.isSeries != false }?.let { "Episode $it" }

/** [absoluteNumber] for every regular episode in one pass: per episode it is quadratic, a second a call on One Piece. */
fun absoluteNumbers(episodes: List<Episode>): Map<Episode, Int> {
    var before = 0
    return episodes.filter { it.season > 0 }.groupBy { it.season }.toSortedMap().flatMap { (_, inSeason) ->
        val offset = if (inSeason.minOf { it.episode } > 1) 0 else before
        before += inSeason.size
        inSeason.map { it to offset + it.episode }
    }.toMap()
}

/** Broadcast order. Season 0 (specials) keeps its own group. */
fun toEpisodes(videos: JSONArray?): List<Episode> = videos?.objects().orEmpty()
    .filter { it.has("season") && it.has("episode") && it.optInt("season", -1) >= 0 }
    .map {
        val s = it.optInt("season")
        val e = it.optInt("episode")
        Episode(
            id = it.str("id") ?: "$s:$e",
            season = s,
            episode = e,
            name = it.str("name") ?: it.str("title") ?: "Episode $e",
            overview = it.str("overview") ?: it.str("description"),
            thumbnail = it.str("thumbnail"),
            released = it.str("released"),
        )
    }
    .sortedWith(compareBy({ it.season }, { it.episode }))

private fun titlesJson(list: List<Title>): String = JSONArray(list.map { t ->
    JSONObject().put("imdb_id", t.imdb).put("kind", t.kind).put("name", t.title).put("year", t.year).put("imdbRating", t.rating)
        .put("genre", JSONArray(t.genres)).put("poster", t.poster).put("description", t.plot).put("cast", JSONArray(t.cast))
        .put("director", JSONArray(t.directors)).put("awards", t.awards).put("background", t.backdrop).put("logo", t.logo)
        .put("runtime", t.runtime).put("trailer", t.trailer)
        .put("videos", JSONArray(t.episodes.map { e ->
            JSONObject().put("id", e.id).put("season", e.season).put("episode", e.episode).put("name", e.name)
                .put("overview", e.overview).put("thumbnail", e.thumbnail).put("released", e.released)
        }))
}).toString()

private fun titlesFrom(raw: String): List<Title> {
    val a = JSONArray(raw)
    return a.objects().flatMap { o -> toTitles(JSONArray().put(o), o.optString("kind", "movie"), withEpisodes = true) }
}
