package com.torrentviewer.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * A franchise. [films] is release order; [story], when not empty, the order events happen in. [more]:
 * another is on the way. [fromTmdb]: not from the list, so its page has to credit TMDB.
 */
data class WatchOrder(val id: String, val name: String, val films: List<String>, val story: List<String> = emptyList(), val more: Boolean = false, val fromTmdb: Boolean = false) {
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
}

fun parseWatchOrders(json: String): List<WatchOrder> =
    JSONObject(json).getJSONArray("orders").objects().map {
        WatchOrder(it.getString("id"), it.getString("name"), it.strings("films"), it.strings("story"), it.optBoolean("more"))
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

    private suspend fun get(path: String) =
        Net.get("$API$path${if ('?' in path) '&' else '?'}api_key=$key", 15_000, quiet = true)

    /** This run's answers: a film's page is often opened twice, going back and forth. */
    private val titles = java.util.concurrent.ConcurrentHashMap<String, TmdbTitle>()

    /** A title's rating, cast and recommendations; null with no key, when TMDB doesn't know it, or can't be reached. */
    suspend fun title(imdb: String, kind: String): TmdbTitle? {
        if (key.isEmpty()) return null
        titles[imdb]?.let { return it }
        return runCatching {
            val found = JSONObject(get("/find/$imdb?external_source=imdb_id"))
            val tv = kind == "tv"
            val id = found.optJSONArray(if (tv) "tv_results" else "movie_results")?.optJSONObject(0)?.optInt("id") ?: return null
            val json = get(if (tv) "/tv/$id?append_to_response=credits,content_ratings,recommendations" else "/movie/$id?append_to_response=credits,release_dates,recommendations")
            parseTmdbTitle(json, if (tv) "tv" else "movie").also { titles[imdb] = it }
        }.getOrNull()
    }

    private val people = java.util.concurrent.ConcurrentHashMap<Int, TmdbPerson>()

    /** Already asked for this run: going back to a person's page lands where it was, not on an empty one. */
    fun knownPerson(id: Int): TmdbPerson? = people[id]

    suspend fun person(id: Int): TmdbPerson? {
        if (key.isEmpty()) return null
        people[id]?.let { return it }
        return runCatching { parseTmdbPerson(get("/person/$id?append_to_response=combined_credits")).also { people[id] = it } }.getOrNull()
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
        return runCatching { fetch(imdb).also { keep(imdb, it) } }.getOrNull()
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
