package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WatchOrdersTest {
    private val orders = parseWatchOrders(File("src/main/resources/watch-orders.json").readText())

    @Test fun everyIdLooksLikeAnImdbId() {
        val bad = orders.flatMap { it.films + it.story }.filterNot { Regex("tt\\d{7,8}").matches(it) }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun orderIdsAreUnique() {
        assertEquals(orders.size, orders.map { it.id }.distinct().size)
    }

    @Test fun noFilmIsListedTwiceInAnOrder() {
        orders.forEach { assertEquals(it.name, it.films.size, it.films.distinct().size) }
    }

    /** A typo in one list would drop a film from it unnoticed. */
    @Test fun aStoryOrderHoldsTheSameFilms() {
        orders.filter { it.story.isNotEmpty() }.forEach { assertEquals(it.name, it.films.toSet(), it.story.toSet()) }
    }

    @Test fun aCollectionIsWhatIsOutOldestFirst() {
        val json = """{"name":"Spider-Man Collection","parts":[
            {"id":3,"release_date":"2007-05-01"},{"id":1,"release_date":"2002-05-01"},{"id":2,"release_date":"2004-06-25"}]}"""
        assertEquals(TmdbCollection("Spider-Man", listOf(1, 2, 3), false), parseCollection(json, "2026-11-06"))
    }

    @Test fun aFilmNotOutYetMeansMoreIsComing() {
        val json = """{"name":"Dune Collection","parts":[
            {"id":1,"release_date":"2021-09-15"},{"id":2,"release_date":"2099-12-18"},{"id":3,"release_date":""}]}"""
        assertEquals(TmdbCollection("Dune", listOf(1), true), parseCollection(json, "2026-11-06"))
    }

    @Test fun nextUpIsTheFirstNotSeen() {
        assertEquals("b", nextUp(listOf("a", "b", "c"), setOf("a", "c")))
        assertNull(nextUp(listOf("a"), setOf("a")))
    }

    @Test fun theLineSaysHowFarAlong() {
        val order = WatchOrder("x", "X", listOf("a", "b", "c"), more = true)
        assertEquals("3 films  ·  more coming", orderLine(order, emptySet()))
        assertEquals("3 films  ·  2 watched  ·  more coming", orderLine(order, setOf("a", "b", "z")))
    }

    @Test fun aFinishedPlayAndAnAccountBothCount() {
        val finished = Item("a", "A", at = 1, imdb = "tt1", lastFileIndex = 0, positions = mapOf(0 to 90.0), durations = mapOf(0 to 100.0))
        val started = Item("b", "B", at = 1, imdb = "tt2", lastFileIndex = 0, positions = mapOf(0 to 10.0), durations = mapOf(0 to 100.0))
        val seen = watchedFilms(listOf(finished, started), StremioLibrary(films = setOf("tt3")))
        assertEquals(setOf("tt1", "tt3"), seen)
    }

    @Test fun runtimesAddUp() {
        assertEquals("3h 30m", totalRuntime(listOf(Title("a", "movie", "A", runtime = "90 min"), Title("b", "movie", "B", runtime = "120 min"))))
        assertNull(totalRuntime(listOf(Title("a", "movie", "A"))))
        assertTrue(orders.size >= 30)
    }
}
