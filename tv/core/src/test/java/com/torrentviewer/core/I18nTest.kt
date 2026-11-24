package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class I18nTest {
    private fun lines(tag: String) = File("src/main/assets/i18n/$tag.txt").readLines()

    @Test fun phrasesWithGapsKeepWhatFillsThem() {
        I18n.use(lines("en"), lines("es"))
        assertEquals("Guardar", tr("Save"))
        // The longer phrase wins over "{} watched", which would swallow it.
        assertEquals("3 de 5 vistos", tr("3 of 5 watched"))
        // A part that is itself a phrase is translated too.
        assertEquals("Porque viste Comedia", tr("Because you watched Comedy"))
        assertEquals("Temporada 2", tr("Season 2"))
        // Not a phrase: a title shows as it is.
        assertEquals("The Matrix", tr("The Matrix"))
        I18n.use(lines("en"), emptyList())
        assertEquals("Save", tr("Save"))
    }

    /** A line missing or a gap dropped would put the wrong words, or a name, in the wrong place. */
    @Test fun everyLanguageMatchesTheEnglishLineForLine() {
        val en = lines("en")
        assertEquals("en.txt has a phrase twice", en.size, en.toSet().size)
        I18n.LANGUAGES.keys.filter { it != "en" }.forEach { tag ->
            val to = lines(tag)
            assertEquals("$tag.txt line count", en.size, to.size)
            en.zip(to).forEachIndexed { i, (e, t) ->
                val where = "$tag.txt line ${i + 1}: $t"
                assertTrue("$where is blank", t.isNotBlank())
                assertTrue("$where uses {} — number it {1}", "{}" !in t)
                val gaps = e.split("{}").size - 1
                (1..gaps).forEach { n -> assertEquals("$where should have {$n} once", 1, t.split("{$n}").size - 1) }
                assertTrue("$where has a {${gaps + 1}}", "{${gaps + 1}}" !in t)
            }
        }
    }
}
