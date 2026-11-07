package com.torrentviewer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    /** Its Stremio type — "movie", "series", "channel", "tv" or an addon's own — which addons are asked by. */
    val type: String = if (kind == "movie") "movie" else "series",
) {
    val isSeries get() = kind == "tv"
    val art get() = backdrop ?: poster

    /** 16:9 art for a landscape card; MetaHub carries a background for nearly every IMDb id. */
    val wide get() = backdrop ?: poster?.takeIf { !imdb.startsWith("tt") } ?: "https://images.metahub.space/background/medium/$imdb/img"

    /** Cinemeta files anime under "Animation"; nyaa answers nothing for a Western cartoon anyway. */
    val isAnime get() = genres.any { it == ANIME_GENRE || it == "Animation" }

    /** One Piece's thousand episodes in arc "seasons": listed by the number everyone knows them by instead. */
    val numberedThrough get() = isAnime && episodes.count { it.season > 0 } > 100

    /** "2021 · 2h 35m · Sci-Fi · Drama" */
    fun facts(): List<String> = listOfNotNull(year?.take(9), runtime?.let(::runtimeLabel)) + genres.take(3)
}

/** "107 min" as "1h 47m" from the hour up; anything else as it came. */
fun runtimeLabel(runtime: String): String {
    val minutes = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).matchEntire(runtime.trim())?.groupValues?.get(1)?.toInt() ?: return runtime
    return when {
        minutes < 60 -> runtime
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/** Not a Cinemeta genre: asking for it switches to the Kitsu anime catalogue. */
const val ANIME_GENRE = "Anime"

/** Before a genre, or alone for all of them: [Meta.catalog] answers with the best by IMDb rating. */
const val TOP_SHELF = "top:"

/** The IMDb rating as a number; null when it has none yet. */
val Title.imdbScore get() = rating?.toDoubleOrNull()

private const val TOP_SIZE = 60

/** Best first, each title once. Unrated titles can't be ranked, so they aren't. */
fun bestRated(titles: List<Title>): List<Title> =
    titles.filter { it.imdbScore != null }.distinctBy { it.imdb }.sortedByDescending { it.imdbScore }.take(TOP_SIZE)

/** One from each list in turn: no list crowds out the others. */
fun <T> turns(lists: List<List<T>>): List<T> =
    (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }

/** [turns], each title once. */
fun interleave(lists: List<List<Title>>): List<Title> = turns(lists).distinctBy { it.imdb }

/** A chart over several genres: each gets its share of the [size] places, then they rank by rating together. */
fun mixTop(lists: List<List<Title>>, size: Int): List<Title> = interleave(lists).take(size).sortedByDescending { it.imdbScore }

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
    private const val ANIME_TOP = "https://anime-kitsu.strem.fun/catalog/series/kitsu-anime-rating"
    /** 300 of the most popular: past that a genre has nothing left rated high enough to rank. */
    private const val TOP_PAGES = 6

    /** A day: charts move slowly and ratings slower. */
    private const val TTL = 24 * 60 * 60 * 1000L
    /** Bumped whenever Title gains a field, so old snapshots are never read back. */
    private const val STORE = "meta:tv7:"

    private val memory = ConcurrentHashMap<String, Any>()

    /**
     * Memory over disk over network; a stale disk copy is served if the fetch fails. Off the main
     * thread past the memory: callers are on it, and a chart alone parses megabytes of JSON, which
     * on the TV held pages still and left a third of the frames late.
     */
    private suspend fun <T> remember(key: String, read: (String) -> T, write: (T) -> String, fetch: suspend () -> T): T {
        @Suppress("UNCHECKED_CAST")
        (memory[key] as T?)?.let { return it }
        return withContext(Dispatchers.Default) {
            var stale: T? = null
            Kv.cached(STORE + key)?.let { raw ->
                runCatching {
                    val o = JSONObject(raw)
                    val value = read(o.getString("value"))
                    if (System.currentTimeMillis() - o.getLong("at") < TTL) {
                        memory[key] = value as Any
                        return@withContext value
                    }
                    stale = value
                }
            }
            try {
                val value = fetch()
                memory[key] = value as Any
                Kv.cache(STORE + key, JSONObject().put("at", System.currentTimeMillis()).put("value", write(value)).toString())
                value
            } catch (e: Exception) {
                stale ?: throw e
            }
        }
    }

    /** Shelves already in memory, for painting a remount on its first frame. */
    @Suppress("UNCHECKED_CAST")
    fun peekCatalog(kind: String, genre: String?): List<Title>? = memory["shelf:$kind:${genre.orEmpty()}"] as List<Title>?

    /** A [genre] starting [ADDON_SHELF] is one of the viewer's addons' catalogues, by [Addons.shelves]' key. */
    suspend fun catalog(kind: String, genre: String? = null): List<Title> = runCatching {
        remember("shelf:$kind:${genre.orEmpty()}", ::titlesFrom, ::titlesJson) {
            if (genre?.startsWith(ADDON_SHELF) == true) {
                val (addon, list) = Addons.shelf(genre) ?: error("addon gone")
                val metas = JSONObject(Net.get("${addon.url}/catalog/${list.type}/${enc(list.id)}.json", quiet = true)).optJSONArray("metas") ?: JSONArray()
                return@remember toTitles(metas, kindOfType(list.type), anyId = true, type = list.type)
            }
            if (genre?.startsWith(TOP_SHELF) == true) return@remember topRated(kind, genre.removePrefix(TOP_SHELF).ifEmpty { null })
            val type = if (kind == "tv") "series" else "movie"
            val url = if (genre == ANIME_GENRE) ANIME else "$CATALOG/$type/top${genre?.let { "/genre=${enc(it)}" }.orEmpty()}.json"
            toTitles(JSONObject(Net.get(url)).optJSONArray("metas") ?: JSONArray(), kind)
        }
    }.getOrDefault(emptyList())

    /**
     * IMDb's best of a genre. Cinemeta has no list by rating (its imdbRating catalogue is new releases), so
     * this reads well down the popular list and ranks that: being popular is what rules out a 9.8 from forty votes.
     */
    private suspend fun topRated(kind: String, genre: String?): List<Title> = coroutineScope {
        val type = if (kind == "tv") "series" else "movie"
        val pages = if (genre == ANIME_GENRE && kind == "tv") (0 until 5).map { "$ANIME_TOP/skip=${it * 20}.json" }
        else {
            val named = (if (genre == ANIME_GENRE) "Animation" else genre)?.let { "genre=${enc(it)}&" }.orEmpty()
            (0 until TOP_PAGES).map { "$CATALOG/$type/top/${named}skip=${it * 50}.json" }
        }
        pages.map { url ->
            async { runCatching { toTitles(JSONObject(Net.get(url, quiet = true)).optJSONArray("metas") ?: JSONArray(), kind) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().let(::bestRated).also { check(it.isNotEmpty()) { "no top list" } }
    }

    /** The chart for several [genres] at once. */
    suspend fun topOf(kind: String, genres: List<String>): List<Title> = mixTop(genres.map { catalog(kind, TOP_SHELF + it) }, TOP_SIZE)

    /** The same, if every genre's list is already in memory. */
    fun peekTopOf(kind: String, genres: List<String>): List<Title>? =
        mixTop(genres.map { peekCatalog(kind, TOP_SHELF + it) ?: return null }, TOP_SIZE)

    /** A card's title in full, for its IMDb rating and art; not kept, as the whole answer is. */
    private suspend fun cardTitle(card: TmdbCard): Title? {
        val imdb = Tmdb.imdbOf(card) ?: return null
        val type = if (card.kind == "tv") "series" else "movie"
        return runCatching { toTitles(JSONArray().put(JSONObject(Net.get("$BASE/$type/$imdb.json", quiet = true)).getJSONObject("meta").put("imdb_id", imdb)), card.kind).first() }
            .getOrElse { Title(imdb, card.kind, card.title, year = card.year, poster = card.poster) }
    }

    /**
     * What to watch next, from TMDB's recommendations for the viewer's own titles ([seeds], newest first,
     * as IMDb id and kind). Empty with no key. Kept a day, for that set of titles.
     */
    suspend fun forYou(seeds: List<Pair<String, String>>): ForYou = runCatching {
        remember("foryou:" + seeds.joinToString(",") { it.first }, ::forYouFrom, ::forYouJson) {
            coroutineScope {
                val asked = seeds.map { (imdb, kind) -> async { title(imdb, kind)?.title to Tmdb.title(imdb, kind)?.similar.orEmpty() } }.awaitAll()
                val plan = planForYou(asked.mapNotNull { (name, cards) -> name?.takeIf { it.isNotEmpty() && cards.isNotEmpty() }?.let { it to cards } })
                val gate = Semaphore(8)
                val found = (plan.picks.map { it.first } + plan.rails.flatMap { it.second }).distinct()
                    .map { card -> async { gate.withPermit { card to cardTitle(card) } } }.awaitAll()
                    .mapNotNull { (card, title) -> title?.takeIf(::worthSuggesting)?.let { card to it } }.toMap()
                ForYou(
                    plan.picks.mapNotNull { (card, because) -> found[card]?.let { Pick(it, because) } },
                    plan.rails.map { (name, cards) -> name to cards.mapNotNull { found[it] } },
                ).also { check(it.picks.isNotEmpty()) { "nothing to suggest" } }
            }
        }
    }.getOrDefault(ForYou())

    /**
     * A title's full entry: from the viewer's meta addons first, in their order and by the types they
     * list for the id, so an addon's own ids ("kitsu:…", a channel's) open too; Cinemeta for an IMDb id
     * none of them knows.
     */
    suspend fun title(imdb: String, kind: String): Title? = runCatching {
        remember("title:$kind:$imdb", { titlesFrom(it).first() }, { titlesJson(listOf(it)) }) {
            fromAddons(imdb, kind)?.let { return@remember it }
            require(imdb.startsWith("tt")) { "no meta addon for $imdb" }
            val type = if (kind == "tv") "series" else "movie"
            val meta = JSONObject(Net.get("$BASE/$type/$imdb.json")).getJSONObject("meta")
            meta.put("imdb_id", imdb)
            toTitles(JSONArray().put(meta), kind, withEpisodes = true).first().let { if (it.numberedThrough) withStills(it) else it }
        }
    }.getOrNull()

    /** Just a film's genres, for [WatchOrders.tag]: no addons asked, and nothing built or kept but the names. */
    suspend fun genresOf(imdb: String): List<String> = runCatching {
        val meta = JSONObject(Net.get("$BASE/movie/$imdb.json", quiet = true)).getJSONObject("meta")
        (meta.optJSONArray("genre") ?: meta.optJSONArray("genres"))?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun fromAddons(id: String, kind: String): Title? {
        val first = if (kind == "tv") "series" else "movie"
        for (a in Addons.list.value) for (type in a.metaTypes(id, first)) {
            val meta = runCatching { a.get("meta", type, id).optJSONObject("meta") }.getOrNull() ?: continue
            // Kept under the id it was asked by: History, My List and the page all know it so.
            meta.put("imdb_id", id)
            val real = meta.str("type") ?: type
            return toTitles(JSONArray().put(meta), kindOfType(real), withEpisodes = true, anyId = true, type = real).firstOrNull() ?: continue
        }
        return null
    }

    /** Every addon catalogue that takes a search, at once; one failing is normal. */
    suspend fun searchAddons(query: String): List<Title> = coroutineScope {
        Addons.searchable().map { (a, c) ->
            async {
                runCatching {
                    val metas = JSONObject(Net.get("${a.url}/catalog/${c.type}/${enc(c.id)}/search=${enc(query)}.json", quiet = true)).optJSONArray("metas")
                    toTitles(metas ?: JSONArray(), kindOfType(c.type), anyId = true, type = c.type)
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

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

    /**
     * IMDb ids in Cinemeta's Family catalogues, most popular first: 1000 films and 500 series.
     * Those go by IMDb's full genre list; a title's own is cut to three, and Toy Story's has no Family.
     * Cached a day like the shelves, as ids only.
     */
    suspend fun familyIds(): Set<String> = runCatching {
        remember("family-ids", { raw -> JSONArray(raw).let { a -> (0 until a.length()).map(a::getString).toSet() } }, { JSONArray(it.toList()).toString() }) {
            coroutineScope {
                // Fifty to a page; a page that fails just leaves its titles out.
                val pages = (0 until 20).map { "movie" to it * 50 } + (0 until 10).map { "series" to it * 50 }
                pages.map { (type, skip) ->
                    async {
                        runCatching {
                            JSONObject(Net.get("$CATALOG/$type/top/genre=Family&skip=$skip.json", quiet = true))
                                .optJSONArray("metas")?.objects().orEmpty().mapNotNull { it.str("imdb_id") ?: it.str("id") }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten().toSet().also { check(it.isNotEmpty()) { "no family titles" } }
            }
        }
    }.getOrDefault(emptySet())

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

/**
 * Rows without art are unusable on a shelf. One row per id. Episodes only from /meta.
 * Cinemeta's own lists keep IMDb ids only; an addon's ([anyId]) are whatever it uses.
 */
fun toTitles(metas: JSONArray, kind: String, withEpisodes: Boolean = false, anyId: Boolean = false, type: String? = null): List<Title> {
    val seen = HashSet<String>()
    return metas.objects().mapNotNull { m ->
        val id = m.str("imdb_id") ?: m.str("id") ?: return@mapNotNull null
        if (!anyId && !Regex("^tt\\d+$").matches(id) || m.str("poster") == null && !withEpisodes || !seen.add(id)) return@mapNotNull null
        val stremioType = m.str("type") ?: type ?: if (kind == "movie") "movie" else "series"
        val name = m.optString("name")
        Title(
            imdb = id,
            kind = kind,
            type = stremioType,
            title = name,
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
            episodes = if (!withEpisodes) emptyList() else toEpisodes(m.optJSONArray("videos")).ifEmpty {
                // A type of an addon's own with no videos plays its own id, as one "episode".
                if (kind == "tv" && stremioType != "series") listOf(Episode(id, 1, 1, name, null, null, null)) else emptyList()
            },
        )
    }
}

/* ---------------------------------------------------------- cast, awards */

private val photos = ConcurrentHashMap<String, String>()

/**
 * An actor's photo, by name, from Wikipedia — Cinemeta lists the cast without pictures. The same
 * source as the Windows app, through Wikipedia's public API; its page images are freely licensed
 * ones only. A name whose page isn't clearly a performer's (a disambiguation, or someone else with
 * that name) gets none, and keeps its initials. Null when there is none.
 */
suspend fun castPhoto(name: String): String? {
    photos[name]?.let { return it.ifEmpty { null } }
    val found = runCatching {
        photoFrom(JSONObject(Net.get("https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageimages%7Cdescription&pithumbsize=200&titles=${enc(name)}", quiet = true)))
    }.getOrNull()
    photos[name] = found.orEmpty()
    return found
}

private val PERFORMER = Regex(
    "\\b(actor|actress|comedian|performer|voice|entertainer|singer|rapper|model|filmmaker|director|presenter|personality|wrestler)\\b",
    RegexOption.IGNORE_CASE,
)

/** The thumbnail of the page in a Wikipedia answer whose description names a performer, or null. */
fun photoFrom(answer: JSONObject): String? {
    val pages = answer.optJSONObject("query")?.optJSONObject("pages") ?: return null
    return pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }
        .firstOrNull { PERFORMER.containsMatchIn(it.str("description").orEmpty()) }
        ?.optJSONObject("thumbnail")?.str("source")
}

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
 * Jujutsu Kaisen S2E5 is "- 29". Specials (and an "episode 0" inside a season) don't count.
 * A season whose numbers already carry on past the last one's (S21 starting at 892) is taken as is.
 *
 * A season starting over at 1 carries on from the highest number reached before it, not from
 * how many entries came before: the metadata of a thousand-episode show lists an episode twice
 * here and skips one there, and counting entries put every later episode one off ("1001" fetched 1002).
 */
fun absoluteNumber(episodes: List<Episode>, season: Int, number: Int): Int? {
    if (season <= 0 || number <= 0) return null
    if (episodes.none { it.season == season && it.episode > 0 }) return null
    var last = 0
    for ((s, inSeason) in episodes.filter { it.season in 1..season && it.episode > 0 }.groupBy { it.season }.toSortedMap()) {
        val offset = if (inSeason.minOf { it.episode } > last) 0 else last
        if (s == season) return offset + number
        last = maxOf(last, offset + inSeason.maxOf { it.episode })
    }
    return null
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
    var last = 0
    return episodes.filter { it.season > 0 && it.episode > 0 }.groupBy { it.season }.toSortedMap().flatMap { (_, inSeason) ->
        val offset = if (inSeason.minOf { it.episode } > last) 0 else last
        last = maxOf(last, offset + inSeason.maxOf { it.episode })
        inSeason.map { it to offset + it.episode }
    }.toMap()
}

/**
 * Broadcast order. Season 0 (specials) keeps its own group. A channel's videos carry no
 * numbers: they become one season, numbered in the addon's own order.
 */
fun toEpisodes(videos: JSONArray?): List<Episode> {
    val all = videos?.objects().orEmpty()
    val numbered = all.filter { it.has("season") && it.has("episode") && it.optInt("season", -1) >= 0 }
    if (numbered.isEmpty()) return all.filter { it.str("id") != null }.mapIndexed { i, v ->
        Episode(v.getString("id"), 1, i + 1, v.str("title") ?: v.str("name") ?: "Video ${i + 1}", v.str("overview") ?: v.str("description"), v.str("thumbnail"), v.str("released"))
    }
    return numbered.map {
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
    }.sortedWith(compareBy({ it.season }, { it.episode }))
}

internal fun titlesJson(list: List<Title>): String = JSONArray(list.map { t ->
    JSONObject().put("imdb_id", t.imdb).put("kind", t.kind).put("type", t.type).put("name", t.title).put("year", t.year).put("imdbRating", t.rating)
        .put("genre", JSONArray(t.genres)).put("poster", t.poster).put("description", t.plot).put("cast", JSONArray(t.cast))
        .put("director", JSONArray(t.directors)).put("awards", t.awards).put("background", t.backdrop).put("logo", t.logo)
        .put("runtime", t.runtime).put("trailer", t.trailer)
        .put("videos", JSONArray(t.episodes.map { e ->
            JSONObject().put("id", e.id).put("season", e.season).put("episode", e.episode).put("name", e.name)
                .put("overview", e.overview).put("thumbnail", e.thumbnail).put("released", e.released)
        }))
}).toString()

internal fun titlesFrom(raw: String): List<Title> {
    val a = JSONArray(raw)
    return a.objects().flatMap { o -> toTitles(JSONArray().put(o), o.optString("kind", "movie"), withEpisodes = true, anyId = true) }
}
