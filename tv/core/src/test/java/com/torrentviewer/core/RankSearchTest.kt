package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RankSearchTest {
    private fun film(id: String, name: String) = Title(id, "movie", name)
    private fun show(id: String, name: String) = Title(id, "tv", name)

    @Test fun theOneEveryoneWantsLeads() {
        // As Cinemeta answers "batman": the 1966 series is an exact name, but nobody typing it means that.
        val films = listOf(film("begins", "Batman Begins"), film("b89", "Batman"), film("the", "The Batman"), film("lego", "The Lego Batman Movie"), film("dk", "The Dark Knight"))
        val series = listOf(show("s66", "Batman"), show("tas", "Batman: The Animated Series"))
        val ranked = rankSearch("batman", films, series, popularFilms = listOf("dk", "the", "begins"))
        assertEquals(listOf("the", "begins", "dk"), ranked.take(3).map { it.imdb })
    }

    @Test fun aPopularSeriesBeatsFilmsThatShareItsName() {
        val films = listOf(film("away", "Breaking Away"), film("dawn", "Breaking Dawn"))
        val series = listOf(show("bb", "Breaking Bad"), show("bear", "Breaking Bear"))
        assertEquals("bb", rankSearch("breaking", films, series, popularSeries = listOf("x", "bb")).first().imdb)
    }

    @Test fun withNoPopularListTheWholeNameStillWins() {
        val films = listOf(film("a", "Dune: Part Two"), film("b", "Dune"))
        assertEquals("b", rankSearch("dune", films, emptyList()).first().imdb)
    }

    @Test fun addonsComeAfterAndNothingTwice() {
        val films = listOf(film("a", "Alien"))
        val addons = listOf(Title("kitsu:1", "tv", "Alien Stage"), film("a", "Alien"))
        assertEquals(listOf("a", "kitsu:1"), rankSearch("alien", films, emptyList(), addons).map { it.imdb })
    }
}
