package com.torrentviewer.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's words in the viewer's language. The English is the key, so code reads as it shows: assets/i18n/en.txt
 * lists every phrase, one a line, with {} where a name or number goes in; each <tag>.txt has the same lines in the
 * same order, translated, with {1} {2}… for those parts so a language can put them in its own order.
 * Translated as it is drawn: a phrase not in the list, a film's title, say, shows as it is.
 */
object I18n {
    /** Offered in Settings, each in its own words. */
    val LANGUAGES = linkedMapOf(
        "en" to "English", "es" to "Español", "pt" to "Português", "fr" to "Français", "de" to "Deutsch",
        "it" to "Italiano", "ru" to "Русский", "tr" to "Türkçe", "ar" to "العربية", "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia", "ja" to "日本語", "ko" to "한국어", "zh" to "简体中文",
    )

    private const val PREFS = "i18n"
    private const val KEY = "language"

    @Volatile var language: String? = null
        private set
    @Volatile private var exact: Map<String, String> = emptyMap()
    @Volatile private var templates: List<Pair<Regex, String>> = emptyList()

    /** What each string shown so far became: the same few hundred are drawn again and again. */
    // ponytail: LRU of 3000; titles and overviews pass through too, so it can't grow without bound.
    private val seen = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 3000
    }

    /** The picked language; "" follows the phone's. */
    fun picked(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun pick(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        language = null
    }

    /** The language to show: the picked one, else the phone's if there is a translation, else English. */
    fun resolve(context: Context): String = picked(context).ifEmpty {
        Locale.getDefault().language.takeIf { it in LANGUAGES } ?: "en"
    }

    /** Loads the words once per language; cheap to call again. */
    fun ensure(context: Context) {
        val tag = resolve(context)
        if (tag == language) return
        val app = context.applicationContext ?: context
        use(lines(app, "en"), if (tag == "en") emptyList() else lines(app, tag))
        language = tag
    }

    /** [en] and its translation [to], line for line. */
    internal fun use(en: List<String>, to: List<String>) {
        val words = HashMap<String, String>()
        val forms = ArrayList<Pair<Regex, String>>()
        // A file a line short would shift every word after it; the test keeps them level, and here nothing is shown wrong.
        if (to.size == en.size) en.zip(to).forEach { (e, t) ->
            if ("{}" in e) forms += Regex(e.split("{}").joinToString("(.+?)", "^", "$") { Regex.escape(it) }) to t
            else words[e] = t
        }
        // Most words first: "{} of {} watched" before "{} watched", which would swallow it.
        forms.sortByDescending { it.first.pattern.replace("(.+?)", "").length }
        synchronized(seen) {
            exact = words
            templates = forms
            seen.clear()
        }
    }

    private fun lines(context: Context, tag: String): List<String> =
        runCatching { context.assets.open("i18n/$tag.txt").bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())

    /** [base] in the picked language: its layout direction too, right to left for Arabic. */
    fun wrap(base: Context): Context {
        val tag = picked(base).ifEmpty { return base }
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    fun tr(s: String): String {
        if (exact.isEmpty() || s.isBlank()) return s
        synchronized(seen) { seen[s]?.let { return it } }
        val out = exact[s] ?: s.trim().let { t -> exact[t]?.let { s.replace(t, it) } } ?: fill(s) ?: s
        synchronized(seen) { seen[s] = out }
        return out
    }

    /** A phrase with a name or number in it; the parts that are themselves phrases ("Drama") are translated too. */
    private fun fill(s: String): String? {
        // Overviews and the like are long; no phrase with a gap is.
        if (s.length > 200) return null
        for ((form, to) in templates) {
            val m = form.matchEntire(s) ?: continue
            var out = to
            m.groupValues.drop(1).forEachIndexed { i, part -> out = out.replace("{${i + 1}}", exact[part] ?: part) }
            return out
        }
        return null
    }
}

/** [s] in the viewer's language. */
fun tr(s: String): String = I18n.tr(s)
