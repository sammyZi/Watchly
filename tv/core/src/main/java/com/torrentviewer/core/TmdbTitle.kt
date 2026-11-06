package com.torrentviewer.core

import org.json.JSONObject

private const val IMAGES = "https://image.tmdb.org/t/p"

/** Talk shows and news: an actor's every guest spot, and nothing anyone is looking for. */
private val NOT_SHOWS = setOf(10767, 10763)

data class CastMember(val id: Int, val name: String, val role: String?, val photo: String?)

/** A title known by TMDB's id: its IMDb id, which the app goes by, is asked for only when it's opened. */
data class TmdbCard(val id: Int, val kind: String, val title: String, val poster: String?, val year: String?)

data class TmdbTitle(val certificate: String?, val cast: List<CastMember>, val similar: List<TmdbCard>)

data class TmdbPerson(val name: String, val photo: String?, val knownFor: String?, val about: String?, val credits: List<TmdbCard>)

private fun image(size: String, path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMAGES/$size$it" }

private fun cardOf(o: JSONObject, kind: String): TmdbCard? {
    val poster = image("w342", o.str("poster_path")) ?: return null
    val name = o.str("title") ?: o.str("name") ?: return null
    return TmdbCard(o.optInt("id"), kind, name, poster, (o.str("release_date") ?: o.str("first_air_date"))?.take(4))
}

/** The US rating: the one set every title has. */
private fun certificateOf(o: JSONObject, kind: String): String? {
    if (kind == "tv") {
        return o.optJSONObject("content_ratings")?.optJSONArray("results")?.objects().orEmpty()
            .firstOrNull { it.optString("iso_3166_1") == "US" }?.str("rating")
    }
    return o.optJSONObject("release_dates")?.optJSONArray("results")?.objects().orEmpty()
        .firstOrNull { it.optString("iso_3166_1") == "US" }
        ?.optJSONArray("release_dates")?.objects().orEmpty()
        .firstNotNullOfOrNull { it.str("certification") }
}

/** /movie or /tv with credits, its ratings and recommendations appended. [kind] is "movie" or "tv". */
fun parseTmdbTitle(json: String, kind: String): TmdbTitle {
    val o = JSONObject(json)
    val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.objects().orEmpty().take(14).map {
        CastMember(it.optInt("id"), it.optString("name"), it.str("character"), image("w185", it.str("profile_path")))
    }
    val similar = o.optJSONObject("recommendations")?.optJSONArray("results")?.objects().orEmpty()
        .filterNot { it.optBoolean("adult") }.mapNotNull { cardOf(it, kind) }.take(15)
    return TmdbTitle(certificateOf(o, kind), cast, similar)
}

/** /person with combined_credits appended: what they're in, best known first, each title once. */
fun parseTmdbPerson(json: String): TmdbPerson {
    val o = JSONObject(json)
    val credits = o.optJSONObject("combined_credits")?.optJSONArray("cast")?.objects().orEmpty()
        .filterNot { it.optBoolean("adult") }
        .filter { c -> c.optString("media_type") in setOf("movie", "tv") && c.optJSONArray("genre_ids")?.let { g -> (0 until g.length()).none { g.optInt(it) in NOT_SHOWS } } != false }
        .sortedByDescending { it.optDouble("popularity") }
        .mapNotNull { cardOf(it, it.optString("media_type")) }
        .distinctBy { it.kind to it.id }
        .take(60)
    return TmdbPerson(o.optString("name"), image("w342", o.str("profile_path")), o.str("known_for_department"), o.str("biography"), credits)
}

/** Above these a kids profile stops. A title with no rating at all goes by the genre rule, as before. */
private val KIDS_CERTIFICATES = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG")

fun tooOldForKids(certificate: String?): Boolean = certificate != null && certificate !in KIDS_CERTIFICATES
