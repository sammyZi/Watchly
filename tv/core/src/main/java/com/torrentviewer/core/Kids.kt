package com.torrentviewer.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** The Home rows a kids profile gets: family films and series, and the animation among them. */
val KIDS_SHELVES = listOf(
    Triple("Family films", "movie", "Family"),
    Triple("Family series", "tv", "Family"),
    Triple("Animation", "movie", "Animation"),
)

/**
 * A kids profile sees family titles only: those in Cinemeta's Family catalogues ([Meta.familyIds]),
 * or tagged Family themselves. Genres, not age ratings — Cinemeta has none — so it keeps out what
 * is not made for children, not every PG-13. Torrent search and magnets are the apps' to switch off.
 */
object Kids {
    var on by mutableStateOf(false)
        private set
    /** Empty until fetched: nothing passes but what is tagged Family, which errs the safe way. */
    var allowed by mutableStateOf(emptySet<String>())
        private set

    /** On every profile switch, with the profile's own flag. */
    fun use(profile: Profile) {
        on = profile.kids
        if (on && allowed.isEmpty()) AppScope.launch { allowed = Meta.familyIds() }
    }

    fun allows(title: Title): Boolean = allows(title, on, allowed)

    fun <T : Any> only(titles: List<T>, titleOf: (T) -> Title): List<T> = if (on) titles.filter { allows(titleOf(it)) } else titles

    fun only(titles: List<Title>): List<Title> = only(titles) { it }
}

/** The rule itself, apart from the profile in use. */
fun allows(title: Title, kids: Boolean, family: Set<String>): Boolean = !kids || title.imdb in family || "Family" in title.genres
