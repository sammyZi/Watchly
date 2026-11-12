package com.torrentviewer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A franchise. [films] is release order; [story], when not empty, the order events happen in. [more]:
 * another is on the way. [fromTmdb]: not from the list, so its page has to credit TMDB. [genres]: its
 * films', once [WatchOrders.tag] has them.
 */
data class WatchOrder(val id: String, val name: String, val films: List<String>, val story: List<String> = emptyList(), val more: Boolean = false, val fromTmdb: Boolean = false, val genres: List<String> = emptyList()) {
    /** Medium posters straight from the id: a card needs no title loaded first. */
    fun posters(count: Int = 3) = films.take(count).map { "https://images.metahub.space/poster/medium/$it/img" }

    /** The first film's backdrop, behind the card. */
    val art get() = "https://images.metahub.space/background/medium/${films.first()}/img"

    /** "spider man", "spiderman" and "Spider-Man" are one name: only letters and digits count. */
    fun matches(query: String): Boolean {
        fun squash(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
        return squash(query) in squash(name)
    }
}

/** A hand-kept list in watch-orders.json: no key, works offline, and the order is one a person chose. */
object WatchOrders {
    val all: List<WatchOrder> by lazy {
        val text = WatchOrders::class.java.getResourceAsStream("/watch-orders.json")!!.bufferedReader().use { it.readText() }
        parseWatchOrders(text)
    }

    /** The first order a film belongs to, for the link on its page. */
    fun of(imdb: String): WatchOrder? = all.firstOrNull { imdb in it.films }

    private const val GENRES = "order-genres"
    /** Cinemeta often gives one film a single genre: The Dark Knight's first is only Crime. */
    private const val FILMS_ASKED = 2
    private const val AT_ONCE = 4
    /** Home gets to load first: on a 2GB TV this was competing with it for the CPU. */
    private const val SETTLE_MS = 5_000L

    private fun keptGenres() = runCatching { JSONObject(Kv.cached(GENRES) ?: "{}") }.getOrDefault(JSONObject())

    private fun withGenres(kept: JSONObject) = all.map { it.copy(genres = kept.strings(it.id)) }

    // What was kept is there from the first frame: the TV ranks its row once, as it comes on screen.
    private val _tagged by lazy { MutableStateFlow(withGenres(keptGenres())) }

    /** [all] with the genres known so far. */
    val tagged: StateFlow<List<WatchOrder>> get() = _tagged

    /** Each franchise's genres, from its first films. Asked for once and kept: a film's genres don't change. */
    suspend fun tag() = withContext(Dispatchers.Default) {
        val kept = keptGenres()
        val missing = all.filterNot { kept.has(it.id) }
        if (missing.isEmpty()) return@withContext
        delay(SETTLE_MS)
        missing.chunked(AT_ONCE).forEach { batch ->
            batch.map { o ->
                async { o.id to o.films.take(FILMS_ASKED).flatMap { Meta.genresOf(it) }.distinct() }
            }.awaitAll().filter { it.second.isNotEmpty() }.forEach { (id, genres) -> kept.put(id, JSONArray(genres)) }
            Kv.cache(GENRES, kept.toString())
        }
        _tagged.value = withGenres(kept)
    }
}

fun parseWatchOrders(json: String): List<WatchOrder> =
    JSONObject(json).getJSONArray("orders").objects().map {
        WatchOrder(it.getString("id"), it.getString("name"), it.strings("films"), it.strings("story"), it.optBoolean("more"))
    }

/**
 * Franchises for one viewer: those [begun] and not finished, then [genres] taking turns, so one with many
 * franchises can't crowd out the others, then the rest. Those all [seen] go last.
 */
fun rankOrders(orders: List<WatchOrder>, genres: List<String>, seen: Set<String> = emptySet(), begun: Set<String> = emptySet()): List<WatchOrder> {
    val open = orders.filterNot { o -> o.films.all { it in seen } }
    val going = open.filter { o -> o.films.any { it in begun } }
    val chosen = turns(genres.map { g -> open.filter { g in it.genres } })
    return (going + chosen + open + orders).distinct()
}

/** The row on Home: only what fits the viewer; everything when nothing does, or the genres haven't come yet. */
fun homeOrders(orders: List<WatchOrder>, genres: List<String>, seen: Set<String>, begun: Set<String>, size: Int = 12): List<WatchOrder> {
    val ranked = rankOrders(orders, genres, seen, begun)
    val fits = ranked.filter { o -> !o.films.all { it in seen } && (o.genres.any { it in genres } || o.films.any { it in begun }) }
    return (if (genres.isEmpty() || orders.all { it.genres.isEmpty() }) ranked else fits.ifEmpty { ranked }).take(size)
}

/** A TMDB collection: what is out, oldest first, by TMDB id; [more] when a part has no date yet or one still to come. */
data class TmdbCollection(val name: String, val parts: List<Int>, val more: Boolean)

/** [today] as yyyy-MM-dd, which sorts as it reads. */
fun parseCollection(json: String, today: String): TmdbCollection {
    val o = JSONObject(json)
    val parts = o.getJSONArray("parts").objects()
    val out = parts.filter { it.optString("release_date").let { d -> d.isNotBlank() && d <= today } }.sortedBy { it.optString("release_date") }
    return TmdbCollection(o.optString("name").removeSuffix(" Collection"), out.map { it.optInt("id") }, out.size < parts.size)
}

/**
 * Any film's collection, for what the list doesn't have. Release order only, and films only: TMDB has
 * no collections of series. A few small calls the first time, then a week from what was kept, an
 * answer of none included.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L

    /** From the app's build; empty leaves this out. */
    var key = ""

    /**
     * The same API under TMDB's other name. Some providers — several in India — don't resolve
     * themoviedb.org at all, and every call simply never answered: no suggestions, cast or ratings.
     */
    private const val API_OTHER = "https://api.tmdb.org/3"
    /** The first name didn't connect this run: the other is asked from then on. */
    @Volatile private var other = false

    private suspend fun get(path: String): String {
        val query = "$path${if ('?' in path) '&' else '?'}api_key=$key"
        if (!other) {
            try {
                // Short: when the name is blocked this is spent in full before the other is tried.
                return Net.get(API + query, 6_000, quiet = true)
            } catch (e: HttpException) {
                // It answered. The other name is the same service and would say the same.
                throw e
            } catch (e: java.io.IOException) {
                // Never connected: the other name.
            }
        }
        return Net.get(API_OTHER + query, 15_000, quiet = true).also { other = true }
    }

    /** This run's answers: a film's page is often opened twice, going back and forth. */
    private val titles = java.util.concurrent.ConcurrentHashMap<String, TmdbTitle>()

    /** A title's rating, cast and recommendations; null with no key, when TMDB doesn't know it, or can't be reached. */
    suspend fun title(imdb: String, kind: String): TmdbTitle? {
        if (key.isEmpty()) return null
        titles[imdb]?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching {
                val found = JSONObject(get("/find/$imdb?external_source=imdb_id"))
                val tv = kind == "tv"
                val id = found.optJSONArray(if (tv) "tv_results" else "movie_results")?.optJSONObject(0)?.optInt("id") ?: return@withContext null
                val json = get(if (tv) "/tv/$id?append_to_response=credits,content_ratings,recommendations" else "/movie/$id?append_to_response=credits,release_dates,recommendations")
                parseTmdbTitle(json, if (tv) "tv" else "movie").also { titles[imdb] = it }
            }.getOrNull()
        }
    }

    private val people = java.util.concurrent.ConcurrentHashMap<Int, TmdbPerson>()

    /** Already asked for this run: going back to a person's page lands where it was, not on an empty one. */
    fun knownPerson(id: Int): TmdbPerson? = people[id]

    suspend fun person(id: Int): TmdbPerson? {
        if (key.isEmpty()) return null
        people[id]?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching { parseTmdbPerson(get("/person/$id?append_to_response=combined_credits")).also { people[id] = it } }.getOrNull()
        }
    }

    /** What the app knows a card by; null if TMDB has none for it, and it can't be opened. */
    suspend fun imdbOf(card: TmdbCard): String? =
        runCatching { JSONObject(get("/${card.kind}/${card.id}/external_ids")).str("imdb_id") }.getOrNull()

    private fun slot(imdb: String) = "tmdb-order:$imdb"

    private fun keep(imdb: String, order: WatchOrder?) = Kv.setGlobal(
        slot(imdb),
        JSONObject().put("at", System.currentTimeMillis()).apply {
            order?.let { put("id", it.id).put("name", it.name).put("films", JSONArray(it.films)).put("more", it.more) }
        }.toString(),
    )

    /** The order this film belongs to, or null for none, for no key, and when TMDB can't be reached. */
    suspend fun orderOf(imdb: String): WatchOrder? {
        if (key.isEmpty()) return null
        runCatching { JSONObject(Kv.global(slot(imdb)) ?: "") }.getOrNull()?.takeIf { System.currentTimeMillis() - it.optLong("at") < KEEP_MS }?.let { kept ->
            return if (kept.has("id")) WatchOrder(kept.getString("id"), kept.getString("name"), kept.strings("films"), more = kept.optBoolean("more"), fromTmdb = true) else null
        }
        return withContext(Dispatchers.Default) { runCatching { fetch(imdb).also { keep(imdb, it) } }.getOrNull() }
    }

    private suspend fun fetch(imdb: String): WatchOrder? {
        val movie = JSONObject(get("/find/$imdb?external_source=imdb_id")).optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id") ?: return null
        val collection = JSONObject(get("/movie/$movie")).optJSONObject("belongs_to_collection")?.optInt("id") ?: return null
        val found = parseCollection(get("/collection/$collection"), isoNow().take(10))
        val films = coroutineScope {
            found.parts.map { part -> async { runCatching { JSONObject(get("/movie/$part/external_ids")).str("imdb_id") }.getOrNull() } }.awaitAll()
        }.filterNotNull()
        return WatchOrder("tmdb-$collection", found.name, films, more = found.more, fromTmdb = true).takeIf { films.size >= 2 && imdb in films }
    }
}

/** Films seen through: played here past [WATCHED_AT], or marked on a connected account. */
fun watchedFilms(history: List<Item>, library: StremioLibrary): Set<String> =
    library.films + history.filter { it.ratio >= WATCHED_AT }.mapNotNull { it.imdb }

/** The first in [order] not seen yet; null once all of them are. */
fun nextUp(order: List<String>, seen: Set<String>): String? = order.firstOrNull { it !in seen }

/** "3 films", then how far along, or that more are coming. */
fun orderLine(order: WatchOrder, seen: Set<String>): String {
    val done = order.films.count { it in seen }
    return listOfNotNull(
        "${order.films.size} films",
        "$done watched".takeIf { done > 0 },
        "more coming".takeIf { order.more },
    ).joinToString("  ·  ")
}

/** "19h 12m": what the order adds up to. */
fun totalRuntime(titles: List<Title>): String? {
    val minutes = titles.sumOf { (runtimeSeconds(it.runtime) / 60).toInt() }
    return if (minutes > 0) "${minutes / 60}h ${minutes % 60}m" else null
}
