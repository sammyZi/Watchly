package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTitleTest {
    private val film = """{
      "release_dates":{"results":[
        {"iso_3166_1":"GB","release_dates":[{"certification":"12A"}]},
        {"iso_3166_1":"US","release_dates":[{"certification":""},{"certification":"PG-13"}]}]},
      "credits":{"cast":[{"id":2219,"name":"Tobey Maguire","character":"Peter Parker","profile_path":"/a.jpg"},{"id":2,"name":"No Photo"}]},
      "recommendations":{"results":[
        {"id":1,"title":"Batman","poster_path":"/b.jpg","release_date":"1989-06-23"},
        {"id":2,"title":"No Poster"},
        {"id":3,"title":"Adult","poster_path":"/c.jpg","adult":true}]}}"""

    @Test fun aFilmsRatingCastAndRecommendations() {
        val t = parseTmdbTitle(film, "movie")
        assertEquals("PG-13", t.certificate)
        assertEquals(CastMember(2219, "Tobey Maguire", "Peter Parker", "https://image.tmdb.org/t/p/w185/a.jpg"), t.cast[0])
        assertNull(t.cast[1].photo)
        assertEquals(listOf(TmdbCard(1, "movie", "Batman", "https://image.tmdb.org/t/p/w342/b.jpg", "1989")), t.similar)
    }

    @Test fun aSeriesHasItsRatingElsewhere() {
        val json = """{"content_ratings":{"results":[{"iso_3166_1":"DE","rating":"16"},{"iso_3166_1":"US","rating":"TV-MA"}]}}"""
        assertEquals("TV-MA", parseTmdbTitle(json, "tv").certificate)
    }

    @Test fun noUsRatingIsNone() {
        assertNull(parseTmdbTitle("""{"release_dates":{"results":[{"iso_3166_1":"IN","release_dates":[{"certification":"UA"}]}]}}""", "movie").certificate)
    }

    @Test fun kidsStopAbovePg() {
        listOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG").forEach { assertFalse(it, tooOldForKids(it)) }
        listOf("PG-13", "R", "NC-17", "TV-14", "TV-MA").forEach { assertTrue(it, tooOldForKids(it)) }
    }

    @Test fun aTitleWithNoRatingFallsToTheGenreRule() {
        assertFalse(tooOldForKids(null))
    }

    @Test fun anActorsCreditsAreBestKnownFirstAndOnceEach() {
        val json = """{"name":"Tobey Maguire","profile_path":"/p.jpg","known_for_department":"Acting","biography":"Born 1975.",
          "combined_credits":{"cast":[
            {"id":1,"media_type":"movie","title":"Small","poster_path":"/1.jpg","popularity":1,"genre_ids":[18]},
            {"id":2,"media_type":"movie","title":"Spider-Man","poster_path":"/2.jpg","popularity":90,"release_date":"2002-05-01","genre_ids":[28]},
            {"id":2,"media_type":"movie","title":"Spider-Man","poster_path":"/2.jpg","popularity":90,"genre_ids":[28]},
            {"id":3,"media_type":"tv","name":"Late Show","poster_path":"/3.jpg","popularity":99,"genre_ids":[10767]}]}}"""
        val p = parseTmdbPerson(json)
        assertEquals("Tobey Maguire", p.name)
        assertEquals(listOf("Spider-Man", "Small"), p.credits.map { it.title })
    }
}
