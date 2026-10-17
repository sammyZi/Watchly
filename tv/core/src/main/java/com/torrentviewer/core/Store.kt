package com.torrentviewer.core

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/* ------------------------------------------------------------------ JSON */

/** org.json hands back "" or "null" for a missing field; this is null. */
fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun JSONObject.strings(key: String): List<String> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.numbers(key: String): Map<Int, Double> =
    optJSONObject(key)?.let { o -> o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.optDouble(k) } }.toMap() }.orEmpty()

private fun Map<Int, Double>.json() = JSONObject().also { o -> forEach { (k, v) -> o.put(k.toString(), v) } }

/* ------------------------------------------------------------------ store */

/**
 * Key/value storage, with the Expo build's keys and JSON shapes so its data imports
 * as-is. Everything a viewer accumulates is namespaced by profile; the first profile
 * keeps the bare keys. The guest writes to memory only and is forgotten on exit.
 */
object Kv {
    private lateinit var prefs: SharedPreferences
    private var profile = ""
    private var guest = false
    private val memory = HashMap<String, String>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("watchly", Context.MODE_PRIVATE)
        importExpo(context)
    }

    fun useProfile(id: String, isGuest: Boolean) {
        profile = id
        guest = isGuest
        if (isGuest) memory.clear()
    }

    private fun at(key: String) = if (profile.isNotEmpty()) "$key@$profile" else key

    /** Per-profile. */
    fun get(key: String): String? = at(key).let { if (guest) memory[it] else prefs.getString(it, null) }

    fun set(key: String, value: String?) {
        val k = at(key)
        if (guest) {
            if (value == null) memory.remove(k) else memory[k] = value
            return
        }
        prefs.edit().apply { if (value == null) remove(k) else putString(k, value) }.apply()
    }

    /** Shared across profiles — the profile list itself, settings, caches. */
    fun global(key: String): String? = prefs.getString(key, null)

    fun setGlobal(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    fun wipeProfile(id: String) {
        prefs.edit().apply { prefs.all.keys.filter { it.endsWith("@$id") }.forEach(::remove) }.apply()
    }

    fun globalKeys(): Set<String> = prefs.all.keys

    /**
     * One-time copy of the React Native build's AsyncStorage (SQLite "RKStorage"),
     * so an in-place upgrade keeps profiles, history and lists. Its cached
     * catalogues are skipped — they refetch.
     */
    private fun importExpo(context: Context) {
        if (prefs.getBoolean("imported-expo", false)) return
        val file = context.getDatabasePath("RKStorage")
        if (file.exists()) {
            runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT key, value FROM catalystLocalStorage", null).use { c ->
                        val edit = prefs.edit()
                        while (c.moveToNext()) {
                            val key = c.getString(0) ?: continue
                            if (key.startsWith("meta:")) continue
                            c.getString(1)?.let { edit.putString(key, it) }
                        }
                        edit.apply()
                    }
                }
            }
        }
        prefs.edit().putBoolean("imported-expo", true).apply()
    }
}

/* -------------------------------------------------------------- settings */

data class Settings(
    /** MB buffered before playback starts. */
    val prepareMb: Int = 1,
    /** Attach public trackers to bare magnets so metadata resolves faster. */
    val addTrackers: Boolean = true,
    /** MB of the film allowed on disk before it rolls over at the playhead. */
    val cacheCapMb: Int = 4096,
    /** Genres to lead the home page with, in Cinemeta's spelling. */
    val genres: List<String> = emptyList(),
    /** libVLC from the start instead of Media3, which falls back to it on its own anyway. */
    val vlcPlayer: Boolean = false,
    /** SubDL language code ("EN"): searched online, and a matching built-in track comes on by itself. Empty: off, English search. */
    val subLang: String = "",
    /** Audio track to prefer when a release carries several; empty is the file's default. */
    val audioLang: String = "",
    val subtitles: SubtitleStyle = SubtitleStyle(),
    /** Carried from one video to the next. */
    val playbackRate: Float = 1f,
    /** The player's picture mode, by label ("Best fit"…). */
    val playerScale: String = "Best fit",
    /** Stream by fetching the whole file rather than the next few minutes. */
    val wholeFile: Boolean = false,
    /** "system", "light" or "dark" — the phone's page; the TV is always dark. */
    val themeMode: String = "system",
)

/** Subtitle text; [size] is one of [SUB_SIZES]' keys. The Expo build's names and values. */
data class SubtitleStyle(val size: String = "normal", val yellow: Boolean = false, val box: Boolean = false)

/** libVLC's relative font size: the screen height divided by this, so smaller is bigger. */
val SUB_SIZES = linkedMapOf("small" to 20, "normal" to 16, "large" to 12, "huge" to 9)

val CACHE_CAPS = listOf(2048, 4096, 8192, 16384)

object SettingsStore {
    private val _value = MutableStateFlow(Settings())
    val value: StateFlow<Settings> = _value

    fun load() {
        _value.value = fromJson(runCatching { JSONObject(Kv.global("settings") ?: "{}") }.getOrDefault(JSONObject()))
    }

    /** Also what a backup restores, so nothing in it is trusted to be in range. */
    fun fromJson(o: JSONObject) = Settings(
        prepareMb = o.optInt("prepareMb", 1).coerceAtLeast(1),
        addTrackers = o.optBoolean("addTrackers", true),
        cacheCapMb = o.optInt("cacheCapMb", 4096).takeIf { it > 0 } ?: 4096,
        genres = o.strings("genres"),
        vlcPlayer = o.optBoolean("vlcPlayer", false),
        subLang = o.optString("subLang", ""),
        audioLang = o.optString("audioLang", ""),
        subtitles = SubtitleStyle(
            size = o.optString("subSize").takeIf { it in SUB_SIZES } ?: "normal",
            yellow = o.optString("subColor") == "yellow",
            box = o.optBoolean("subBackground", false),
        ),
        playbackRate = o.optDouble("playbackRate", 1.0).toFloat().takeIf { it in 0.25f..4f } ?: 1f,
        playerScale = o.optString("playerScale", "Best fit").ifBlank { "Best fit" },
        wholeFile = o.optBoolean("streamWholeFile", false),
        themeMode = o.optString("themeMode").takeIf { it == "light" || it == "dark" } ?: "system",
    )

    /** Over what is stored, not instead of it: the TV and the phone each keep fields the other never reads. */
    fun save(next: Settings) {
        _value.value = next
        val o = runCatching { JSONObject(Kv.global("settings") ?: "{}") }.getOrDefault(JSONObject())
        o.put("prepareMb", next.prepareMb).put("addTrackers", next.addTrackers)
            .put("cacheCapMb", next.cacheCapMb).put("genres", JSONArray(next.genres)).put("vlcPlayer", next.vlcPlayer)
            .put("subLang", next.subLang).put("audioLang", next.audioLang)
            .put("subSize", next.subtitles.size).put("subColor", if (next.subtitles.yellow) "yellow" else "white").put("subBackground", next.subtitles.box)
            .put("playbackRate", next.playbackRate.toDouble()).put("playerScale", next.playerScale).put("streamWholeFile", next.wholeFile).put("themeMode", next.themeMode)
        Kv.setGlobal("settings", o.toString())
    }
}

/* -------------------------------------------------------------- profiles */

data class Profile(val id: String, val name: String, val colour: String, val icon: String? = null, val pin: String? = null) {
    val isGuest get() = id == GUEST.id
}

val GUEST = Profile("guest", "Guest", "#5b6472")

/** A fixed set, so profiles are told apart by colour as much as by name. */
val COLOURS = listOf("#e50914", "#2b8cff", "#f5a623", "#3ecf8e", "#a855f7", "#ff6b6b")

/** Names kept from the Expo build's icon set; each maps to a drawn glyph. */
val AVATARS = listOf("emoticon-happy", "emoticon-cool", "emoticon-excited", "emoticon-wink", "emoticon-tongue", "robot", "cat", "dog", "alien", "penguin")

object Profiles {
    private val _list = MutableStateFlow<List<Profile>>(emptyList())
    val list: StateFlow<List<Profile>> = _list

    fun load() {
        _list.value = runCatching {
            JSONArray(Kv.global("profiles") ?: "[]").objects().map {
                Profile(it.optString("id"), it.optString("name"), it.str("colour") ?: COLOURS[0], it.str("icon"), it.str("pin"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(next: List<Profile>) {
        _list.value = next
        Kv.setGlobal(
            "profiles",
            JSONArray(next.map { JSONObject().put("id", it.id).put("name", it.name).put("colour", it.colour).put("icon", it.icon).put("pin", it.pin) }).toString(),
        )
    }

    /** The very first takes the bare keys, so a library from before profiles survives. */
    fun add(name: String, icon: String, colour: String): Profile {
        val list = _list.value
        val profile = Profile(
            id = if (list.isEmpty()) "" else "p${System.currentTimeMillis().toString(36)}",
            name = name.trim().ifBlank { "Me" },
            colour = colour,
            icon = icon,
        )
        write(list + profile)
        return profile
    }

    fun edit(id: String, name: String, icon: String, colour: String) =
        write(_list.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, icon = icon, colour = colour) else it })

    /** Four digits sets a lock; anything else clears it. A lock, not security. */
    fun setPin(id: String, pin: String) {
        val clean = pin.filter(Char::isDigit).take(4)
        write(_list.value.map { if (it.id == id) it.copy(pin = clean.takeIf { c -> c.length == 4 }) else it })
    }

    fun remove(id: String) {
        write(_list.value.filter { it.id != id })
        Kv.wipeProfile(id)
    }
}

/* --------------------------------------------------------------- history */

data class StoredFile(val index: Int, val name: String, val size: Long, val isVideo: Boolean, val isSubtitle: Boolean, val path: String?)

data class SavedSub(val path: String, val label: String)

/** One torrent ever opened, and where each of its files was left. */
data class Item(
    /** Magnet, URL or local .torrent path — the identity. */
    val uri: String,
    val title: String,
    /** Last opened, ms. */
    val at: Long,
    val torrentName: String? = null,
    val files: List<StoredFile>? = null,
    val lastFileIndex: Int? = null,
    /** Seconds, per file index. */
    val positions: Map<Int, Double> = emptyMap(),
    val durations: Map<Int, Double> = emptyMap(),
    /** Where the player was reading at the resume point, 0–1 of the file's bytes. */
    val byteAts: Map<Int, Double> = emptyMap(),
    val subtitles: Map<Int, List<SavedSub>> = emptyMap(),
    val imdb: String? = null,
    val kind: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    /** "The Matrix", not "The.Matrix.1999.1080p.BluRay.x264-GRP". */
    val titleName: String? = null,
) {
    val label get() = titleName ?: title

    /** 0–1 through the last file played. */
    val ratio: Double
        get() {
            val i = lastFileIndex ?: return 0.0
            val at = positions[i] ?: return 0.0
            val of = durations[i] ?: return 0.0
            return if (of > 0) at / of else 0.0
        }

    fun resumeRatio(index: Int): Double {
        val p = positions[index] ?: return 0.0
        val d = durations[index] ?: return 0.0
        if (p <= 0 || d <= 0) return 0.0
        // Measured beats guessed: a 4K MKV resumed at 39% of its time was at 32%
        // of its bytes, so the time guess pre-buffered the wrong spot for 44s.
        return (byteAts[index] ?: (p / d)).coerceIn(0.0, 0.99)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri).put("title", title).put("at", at)
        .put("torrentName", torrentName)
        .put("files", files?.let { fs ->
            JSONArray(fs.map { JSONObject().put("index", it.index).put("name", it.name).put("size", it.size).put("isVideo", it.isVideo).put("isSubtitle", it.isSubtitle).put("path", it.path) })
        })
        .put("lastFileIndex", lastFileIndex)
        .put("positions", positions.json())
        .put("durations", durations.json())
        .put("byteAts", byteAts.json())
        .put("subtitles", JSONObject().also { o ->
            subtitles.forEach { (k, v) -> o.put(k.toString(), JSONArray(v.map { JSONObject().put("path", it.path).put("label", it.label) })) }
        })
        .put("imdb", imdb).put("kind", kind).put("poster", poster).put("backdrop", backdrop).put("titleName", titleName)

    companion object {
        fun from(o: JSONObject) = Item(
            uri = o.getString("uri"),
            title = o.optString("title"),
            at = o.optLong("at"),
            torrentName = o.str("torrentName"),
            files = o.optJSONArray("files")?.objects()?.map {
                StoredFile(it.optInt("index"), it.optString("name"), it.optLong("size"), it.optBoolean("isVideo"), it.optBoolean("isSubtitle"), it.str("path"))
            },
            lastFileIndex = if (o.has("lastFileIndex") && !o.isNull("lastFileIndex")) o.optInt("lastFileIndex") else null,
            positions = o.numbers("positions"),
            durations = o.numbers("durations"),
            byteAts = o.numbers("byteAts"),
            subtitles = o.optJSONObject("subtitles")?.let { s ->
                s.keys().asSequence().mapNotNull { k ->
                    k.toIntOrNull()?.let { i -> i to (s.optJSONArray(k)?.objects()?.map { SavedSub(it.optString("path"), it.optString("label")) }.orEmpty()) }
                }.toMap()
            }.orEmpty(),
            imdb = o.str("imdb"),
            kind = o.str("kind"),
            poster = o.str("poster"),
            backdrop = o.str("backdrop"),
            titleName = o.str("titleName"),
        )
    }
}

/**
 * What a history entry is *of*: the title (by IMDb id, else the name before " · S01E02"),
 * so two releases of one film, or a show's episodes from several torrents, are one title.
 */
val Item.titleKey: String get() = imdb ?: titleName?.substringBefore(" · ")?.lowercase() ?: uri

/** The most recent entry of each title, newest first — one card per film or show, wherever it was played from. */
fun latestPerTitle(items: List<Item>): List<Item> = items.sortedByDescending { it.at }.distinctBy { it.titleKey }

/** Prefer a real http(s) URL over a missing or local one. */
fun remoteArt(vararg urls: String?): String? = urls.firstOrNull { it != null && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }

object History {
    private const val KEY = "torrent-history"
    private const val MAX = 50
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    fun load() {
        _items.value = runCatching { JSONArray(Kv.get(KEY) ?: "[]").objects().map(Item::from) }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(items: List<Item>) {
        val capped = items.take(MAX)
        _items.value = capped
        Kv.set(KEY, JSONArray(capped.map { it.toJson() }).toString())
    }

    fun find(uri: String) = _items.value.find { it.uri == uri }

    /** Insert or move to the top, keeping what was known; a sibling episode lends its art. */
    @Synchronized
    fun put(item: Item): Item {
        val rest = _items.value
        val prev = rest.find { it.uri == item.uri }
        val kin = rest.find { x ->
            x.uri != item.uri && remoteArt(x.poster) != null &&
                ((item.imdb != null && x.imdb == item.imdb) || (prev?.imdb != null && x.imdb == prev.imdb) || (item.titleName != null && x.titleName == item.titleName))
        }
        val merged = (prev ?: item).copy(
            title = item.title,
            at = item.at,
            poster = remoteArt(item.poster, prev?.poster, kin?.poster),
            backdrop = remoteArt(item.backdrop, prev?.backdrop, kin?.backdrop),
            imdb = item.imdb ?: prev?.imdb ?: kin?.imdb,
            kind = item.kind ?: prev?.kind ?: kin?.kind,
            titleName = item.titleName ?: prev?.titleName ?: kin?.titleName,
        )
        write(listOf(merged) + rest.filter { it.uri != item.uri })
        return merged
    }

    @Synchronized
    fun patch(uri: String, change: (Item) -> Item) = write(_items.value.map { if (it.uri == uri) change(it) else it })

    /** `byteAt` < 0 (no open reader, e.g. on close) keeps the last one seen. */
    fun setPosition(uri: String, index: Int, seconds: Double, byteAt: Double = -1.0) = patch(uri) {
        it.copy(
            lastFileIndex = index,
            positions = it.positions + (index to seconds),
            byteAts = if (byteAt >= 0) it.byteAts + (index to byteAt) else it.byteAts,
        )
    }

    fun setDuration(uri: String, index: Int, seconds: Double) = patch(uri) { it.copy(durations = it.durations + (index to seconds)) }

    fun setSubs(uri: String, index: Int, subs: List<SavedSub>) = patch(uri) { it.copy(subtitles = it.subtitles + (index to subs)) }

    fun drop(uri: String, engine: TorrentStream) {
        find(uri)?.let { gone -> engine.deleteSubtitles(gone.subtitles.values.flatten().map { it.path }) }
        write(_items.value.filter { it.uri != uri })
    }

    /** Every entry of one title — each torrent it was played from — with their subtitles. */
    fun dropTitle(key: String, engine: TorrentStream) = _items.value.filter { it.titleKey == key }.forEach { drop(it.uri, engine) }

    fun clear(engine: TorrentStream) {
        engine.deleteSubtitles(_items.value.flatMap { it.subtitles.values.flatten() }.map { it.path })
        write(emptyList())
    }
}

/* ----------------------------------------------------------------- saved */

data class Saved(
    val imdb: String,
    val kind: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val rating: String?,
    val list: String,
    val at: Long,
) {
    fun asTitle() = Title(imdb = imdb, kind = kind, title = title, year = year, rating = rating, poster = poster)
}

/** "later" — My List. */
object SavedStore {
    private const val KEY = "saved"
    private val _items = MutableStateFlow<List<Saved>>(emptyList())
    val items: StateFlow<List<Saved>> = _items

    fun load() {
        _items.value = runCatching {
            JSONArray(Kv.get(KEY) ?: "[]").objects().map {
                Saved(it.optString("imdb"), it.optString("kind", "movie"), it.optString("title"), it.str("poster"), it.str("year"), it.str("rating"), it.optString("list", "later"), it.optLong("at"))
            }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }

    fun has(imdb: String, list: String = "later") = _items.value.any { it.imdb == imdb && it.list == list }

    fun toggle(title: Title, list: String = "later") {
        val all = _items.value
        val without = all.filter { !(it.imdb == title.imdb && it.list == list) }
        val next = if (without.size == all.size) {
            listOf(Saved(title.imdb, title.kind, title.title, title.poster, title.year, title.rating, list, System.currentTimeMillis())) + without
        } else {
            without
        }
        _items.value = next
        Kv.set(
            KEY,
            JSONArray(next.map {
                JSONObject().put("imdb", it.imdb).put("kind", it.kind).put("title", it.title).put("poster", it.poster)
                    .put("year", it.year).put("rating", it.rating).put("list", it.list).put("at", it.at)
            }).toString(),
        )
    }
}

/* ---------------------------------------------------------------- intros */

/** Where a show's opening titles sit, in seconds, marked once by the viewer. */
data class Intro(val start: Double, val end: Double) {
    /** Inside the intro, with a moment's lead so the button is up as it starts. */
    fun covers(at: Double) = at >= start - 2 && at < end - 1
}

/**
 * The Expo build's "intros" key: one window per show (imdb id, else its name).
 * ponytail: a cold open that varies in length lands a little early or late; per-episode
 * detection (audio fingerprinting) is the upgrade if that matters.
 */
object Intros {
    private const val KEY = "intros"

    private fun all(): JSONObject = runCatching { JSONObject(Kv.get(KEY) ?: "{}") }.getOrDefault(JSONObject())

    fun get(show: String): Intro? = all().optJSONObject(show)?.let { Intro(it.optDouble("start"), it.optDouble("end")) }

    fun set(show: String, intro: Intro) = Kv.set(KEY, all().put(show, JSONObject().put("start", intro.start).put("end", intro.end)).toString())
}

/* -------------------------------------------------------------- searches */

object Searches {
    private const val KEY = "searches"
    private const val MAX = 8

    fun load(): List<String> = runCatching {
        JSONArray(Kv.get(KEY) ?: "[]").let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
    }.getOrDefault(emptyList()).take(MAX)

    fun remember(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return load()
        val next = (listOf(q) + load().filter { !it.equals(q, ignoreCase = true) }).take(MAX)
        Kv.set(KEY, JSONArray(next).toString())
        return next
    }

    fun clear(): List<String> {
        Kv.set(KEY, null)
        return emptyList()
    }
}
