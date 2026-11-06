package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouTest {
    private fun card(id: Int) = TmdbCard(id, "movie", "Film $id", "p$id", "2000")

    @Test fun whatSeveralTitlesSuggestLeads() {
        val plan = planForYou(listOf("A" to listOf(card(1), card(2), card(3)), "B" to listOf(card(4), card(5), card(3))))
        assertEquals(3, plan.picks.first().first.id)
    }

    @Test fun aPickSaysWhichTitleItMostlyCameFrom() {
        val plan = planForYou(listOf("A" to listOf(card(9), card(1)), "B" to listOf(card(1), card(8))))
        assertEquals("B", plan.picks.first { it.first.id == 1 }.second)
    }

    @Test fun aNewerTitleCountsForMore() {
        val plan = planForYou(listOf("New" to listOf(card(1)), "Old" to listOf(card(2))))
        assertEquals(listOf(1, 2), plan.picks.map { it.first.id })
    }

    @Test fun rowsRepeatNothingThePicksOrEachOtherHave() {
        val many = (1..40).map(::card)
        val plan = planForYou(listOf("A" to many, "B" to many.reversed()))
        val shown = plan.picks.map { it.first } + plan.rails.flatMap { it.second }
        assertEquals(shown.size, shown.distinct().size)
    }

    @Test fun aRowOfNextToNothingIsDropped() {
        assertTrue(planForYou(listOf("A" to listOf(card(1), card(2)))).rails.isEmpty())
    }

    @Test fun lowRatedIsNoSuggestionButUnratedIs() {
        assertFalse(worthSuggesting(Title("tt1", "movie", "Bad", rating = "4.2")))
        assertTrue(worthSuggesting(Title("tt2", "movie", "Good", rating = "7.1")))
        assertTrue(worthSuggesting(Title("tt3", "movie", "New")))
    }

    @Test fun theTopListGoesByRatingAndLeavesOutTheUnrated() {
        val list = listOf(
            Title("tt1", "movie", "Fine", rating = "7.0"),
            Title("tt2", "movie", "Best", rating = "9.1"),
            Title("tt3", "movie", "Unrated"),
            Title("tt2", "movie", "Best", rating = "9.1"),
        )
        assertEquals(listOf("Best", "Fine"), bestRated(list).map { it.title })
    }

    private fun rated(id: Int, rating: String) = Title("tt$id", "movie", "Film $id", rating = rating)

    @Test fun oneGenreRatedHigherDoesNotTakeTheWholeChart() {
        val anime = (1..10).map { rated(it, "9.$it") }
        val mystery = (11..20).map { rated(it, "7.${it - 10}") }
        val chart = mixTop(listOf(anime, mystery), 6)
        assertEquals(3, chart.count { it in anime })
        assertEquals(3, chart.count { it in mystery })
        assertEquals(chart.sortedByDescending { it.imdbScore }, chart)
    }

    @Test fun takingTurnsShowsATitleOnce() {
        val shared = rated(1, "8.0")
        assertEquals(listOf("tt1", "tt2", "tt3"), interleave(listOf(listOf(shared, rated(3, "7.0")), listOf(rated(2, "7.5"), shared))).map { it.imdb })
    }

    @Test fun aWatchOrderIsFoundHoweverItsNameIsTyped() {
        val order = WatchOrder("x", "Spider-Man: Home Trilogy", listOf("tt1"))
        listOf("spider man", "spiderman", "Spider-Man", "SPIDER", "home trilogy", "").forEach { assertTrue(it, order.matches(it)) }
        assertFalse(order.matches("batman"))
    }

    @Test fun whatWasKeptReadsBackTheSame() {
        val you = ForYou(listOf(Pick(Title("tt1", "movie", "A", poster = "p"), "Seed")), listOf("Seed" to listOf(Title("tt2", "tv", "B", poster = "q"))))
        val back = forYouFrom(forYouJson(you))
        assertEquals("Seed", back.picks.single().because)
        assertEquals("tt1", back.picks.single().title.imdb)
        assertEquals("B", back.rails.single().second.single().title)
    }
}
