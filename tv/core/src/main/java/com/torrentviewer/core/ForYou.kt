package com.torrentviewer.core

import org.json.JSONArray
import org.json.JSONObject

/** Below this on IMDb, a suggestion isn't one. A title with no rating yet is new, not bad, and stays. */
private const val SUGGEST_FROM = 6.0

private const val PICKS = 20
private const val PER_RAIL = 10
private const val SMALLEST_RAIL = 4

/** A suggestion, and the viewer's title it mostly came from. */
data class Pick(val title: Title, val because: String)

/** The best suggestions of all, then a row for each of the viewer's titles, by that title's name. */
data class ForYou(val picks: List<Pick> = emptyList(), val rails: List<Pair<String, List<Title>>> = emptyList())

data class ForYouPlan(val picks: List<Pair<TmdbCard, String>>, val rails: List<Pair<String, List<TmdbCard>>>)

fun worthSuggesting(title: Title) = (title.imdbScore ?: SUGGEST_FROM) >= SUGGEST_FROM

/**
 * [seeds]: each of the viewer's titles, newest first, with what TMDB suggests for it, best first. A card
 * suggested by several of them leads, however far down each had it; a newer title counts for more. What
 * the picks took, the rows don't repeat, so every row is something new.
 */
fun planForYou(seeds: List<Pair<String, List<TmdbCard>>>): ForYouPlan {
    val score = LinkedHashMap<TmdbCard, Double>()
    val votes = HashMap<TmdbCard, Int>()
    val because = HashMap<TmdbCard, Pair<String, Double>>()
    seeds.forEachIndexed { s, (name, cards) ->
        cards.forEachIndexed { rank, card ->
            val part = (1.0 - rank.toDouble() / cards.size) / (1.0 + s * 0.25)
            score[card] = (score[card] ?: 0.0) + part
            votes[card] = (votes[card] ?: 0) + 1
            if (part > (because[card]?.second ?: 0.0)) because[card] = name to part
        }
    }
    val picks = score.keys.sortedWith(compareByDescending<TmdbCard> { votes[it] }.thenByDescending { score[it] }).take(PICKS).map { it to because.getValue(it).first }
    val used = picks.mapTo(HashSet()) { it.first }
    val rails = seeds.mapNotNull { (name, cards) ->
        val fresh = cards.filter { it !in used }.take(PER_RAIL)
        if (fresh.size < SMALLEST_RAIL) null else {
            used += fresh
            name to fresh
        }
    }
    return ForYouPlan(picks, rails)
}

internal fun forYouJson(you: ForYou): String = JSONObject()
    .put("picks", titlesJson(you.picks.map { it.title }))
    .put("because", JSONArray(you.picks.map { it.because }))
    .put("rails", JSONArray(you.rails.map { (name, titles) -> JSONObject().put("name", name).put("titles", titlesJson(titles)) }))
    .toString()

internal fun forYouFrom(raw: String): ForYou {
    val o = JSONObject(raw)
    val because = o.getJSONArray("because")
    return ForYou(
        titlesFrom(o.getString("picks")).mapIndexed { i, t -> Pick(t, because.getString(i)) },
        o.getJSONArray("rails").objects().map { it.getString("name") to titlesFrom(it.getString("titles")) },
    )
}
