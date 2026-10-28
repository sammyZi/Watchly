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
        Secrets.init(context)
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
    /** MB of the film allowed on disk before it rolls over at the playhead; 0 is Auto, the free space. */
    val cacheCapMb: Int = 0,
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
    /** Jump past an episode's intro by itself, rather than offer the button. */
    val autoSkipIntro: Boolean = false,
)

/** Subtitle text; [size] is one of [SUB_SIZES]' keys. The Expo build's names and values. */
data class SubtitleStyle(val size: String = "normal", val yellow: Boolean = false, val box: Boolean = false)

/** libVLC's relative font size: the screen height divided by this, so smaller is bigger. */
val SUB_SIZES = linkedMapOf("small" to 20, "normal" to 16, "large" to 12, "huge" to 9)

/** One size up (+1) or down (−1), stopping at the ends. */
fun SubtitleStyle.resized(step: Int): SubtitleStyle {
    val sizes = SUB_SIZES.keys.toList()
    return copy(size = sizes[(sizes.indexOf(size) + step).coerceIn(0, sizes.lastIndex)])
}

/** A size step from the player, kept for the next film too. */
fun SettingsStore.resizeSubtitles(step: Int): SubtitleStyle =
    value.value.let { s -> s.subtitles.resized(step).also { save(s.copy(subtitles = it)) } }

/** 0 is Auto. */
val CACHE_CAPS = listOf(0, 2048, 4096, 8192, 16384)

fun cacheCapLabel(mb: Int) = if (mb == 0) "Auto" else "${mb / 1024} GB"

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
        // Renamed from cacheCapMb so the fixed 4 GB everyone had saved gives way to Auto once.
        cacheCapMb = o.optInt("cacheMb", 0).takeIf { it in CACHE_CAPS } ?: 0,
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
        autoSkipIntro = o.optBoolean("autoSkipIntro", false),
    )

    /** Over what is stored, not instead of it: the TV and the phone each keep fields the other never reads. */
    fun save(next: Settings) {
        _value.value = next
        val o = runCatching { JSONObject(Kv.global("settings") ?: "{}") }.getOrDefault(JSONObject())
        o.put("prepareMb", next.prepareMb).put("addTrackers", next.addTrackers)
            .put("cacheMb", next.cacheCapMb).put("genres", JSONArray(next.genres)).put("vlcPlayer", next.vlcPlayer)
            .put("subLang", next.subLang).put("audioLang", next.audioLang)
            .put("subSize", next.subtitles.size).put("subColor", if (next.subtitles.yellow) "yellow" else "white").put("subBackground", next.subtitles.box)
            .put("playbackRate", next.playbackRate.toDouble()).put("playerScale", next.playerScale).put("streamWholeFile", next.wholeFile).put("themeMode", next.themeMode).put("autoSkipIntro", next.autoSkipIntro)
        Kv.setGlobal("settings", o.toString())
    }
}

/**
 * What the viewer types in that is theirs alone — their SubDL key. AES-GCM under a key that
 * is made in the Android Keystore and never leaves it; only the ciphertext is stored, in a
 * prefs file of its own, out of the settings. If it ever stops decrypting it reads as unset.
 */
object Secrets {
    private const val ALIAS = "watchly-secrets"
    private const val IV = 12
    private lateinit var prefs: SharedPreferences
    private val _subdl = MutableStateFlow("")
    val subdl: StateFlow<String> = _subdl

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("watchly-secrets", Context.MODE_PRIVATE)
        _subdl.value = read("subdl").orEmpty()
    }

    /** Blank forgets it. */
    fun setSubdl(key: String) {
        val k = key.trim()
        write("subdl", k.ifEmpty { null })
        _subdl.value = k
    }

    private fun keystoreKey(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    private fun write(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, keystoreKey()) }
        val blob = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)).apply()
    }

    private fun read(name: String): String? = runCatching {
        val blob = android.util.Base64.decode(prefs.getString(name, null) ?: return null, android.util.Base64.NO_WRAP)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(javax.crypto.Cipher.DECRYPT_MODE, keystoreKey(), javax.crypto.spec.GCMParameterSpec(128, blob, 0, IV)) }
        String(cipher.doFinal(blob, IV, blob.size - IV))
    }.getOrNull()
}

/* -------------------------------------------------------------- profiles */

data class Profile(val id: String, val name: String, val colour: String, val icon: String? = null, val pin: String? = null) {
    val isGuest get() = id == GUEST.id
}

val GUEST = Profile("guest", "Guest", "#5b6472")

/** A fixed set, so profiles are told apart by colour as much as by name. */
val COLOURS = listOf("#e50914", "#2b8cff", "#f5a623", "#3ecf8e", "#a855f7", "#ff6b6b")

/** The faces a profile can pick, by id, in the picker's order; see [Avatars]. */
val AVATARS: List<String> get() = Avatars.pickable.map { it.id }

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
    /** The swarm when this release was picked from search; null if opened another way. */
    val seeders: Int? = null,
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
        .put("seeders", seeders)

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
            seeders = if (o.has("seeders") && !o.isNull("seeders")) o.optInt("seeders") else null,
        )
    }
}

/**
 * What a history entry is *of*: the title (by IMDb id, else the name before " · S01E02"),
 * so two releases of one film, or a show's episodes from several torrents, are one title.
 */
val Item.titleKey: String get() = imdb ?: titleName?.substringBefore(" · ")?.lowercase() ?: uri

/**
 * One entry per title, newest first — one card per film or show, wherever it was played from.
 * Of a title's entries, the newest that was actually started: a second release opened and
 * closed at once must not hide where the first one was left.
 */
fun latestPerTitle(items: List<Item>): List<Item> =
    items.sortedByDescending { it.at }.groupBy { it.titleKey }.values
        .map { group -> group.firstOrNull { it.ratio > 0.0 } ?: group.first() }
        .sortedByDescending { it.at }

/**
 * Where to start [fileName] of [target] when this release has never been played: the spot
 * another release of the same title (and, for a series, the same episode) was left, in
 * seconds, with that release's duration. By time, not bytes — releases differ in size.
 */
fun carryOver(items: List<Item>, target: Item, fileName: String): Pair<Double, Double?>? {
    // Anime goes by its bare number ("One Piece - 1125"): without it, every such file was
    // "no episode" alike, and 1125 opened where 1124 was left.
    fun episodeOf(name: String): Any? = parseEpisode(name) ?: parseAbsolute(name)
    val ep = episodeOf(fileName)
    // A series file that says no episode can't be matched to anything.
    if (ep == null && target.kind == "tv") return null
    val donor = items.filter { it.uri != target.uri && it.titleKey == target.titleKey }.sortedByDescending { it.at }.firstOrNull { d ->
        val i = d.lastFileIndex ?: return@firstOrNull false
        (d.positions[i] ?: 0.0) > 0.0 && d.files?.find { it.index == i }?.name?.let(::episodeOf) == ep
    } ?: return null
    val i = donor.lastFileIndex!!
    val duration = donor.durations[i]
    // Watched through is not a place to start a new copy from.
    if (duration != null && donor.positions[i]!! >= duration * 0.95) return null
    return donor.positions[i]!! to duration
}

/** The episode a file in [list] is: by its S/E, else by the number counted through the show ("One Piece - 1071"). */
fun episodeOfFile(list: List<Episode>, name: String): Episode? {
    parseEpisode(name)?.let { at -> return list.find { it.season == at.season && it.episode == at.episode } }
    val n = parseAbsolute(name) ?: return null
    return absoluteNumbers(list).entries.find { it.value == n }?.key
}

/**
 * What a series' Play button offers: the episode last played if it was left part-way,
 * else the one after it. Null when nothing of [title] was played, or it was the last.
 */
fun continueEpisode(title: Title, items: List<Item>): Episode? {
    val last = items.filter { it.imdb == title.imdb }.sortedByDescending { it.at }.firstNotNullOfOrNull { item ->
        val i = item.lastFileIndex ?: return@firstNotNullOfOrNull null
        val name = item.files?.find { it.index == i }?.name ?: item.title
        episodeOfFile(title.episodes, name)?.let { it to item }
    } ?: return null
    val (ep, item) = last
    val i = item.lastFileIndex!!
    val done = item.durations[i]?.let { d -> d > 0 && (item.positions[i] ?: 0.0) >= d * 0.95 } ?: false
    if (!done) return ep
    val inOrder = title.episodes.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
    return inOrder.getOrNull(inOrder.indexOf(ep) + 1)
}

/** How far through each episode of [imdb] anyone got, 0–1, by its file name — the furthest of every release it was played from. */
fun episodeProgress(items: List<Item>, imdb: String): Map<Ep, Double> {
    val out = mutableMapOf<Ep, Double>()
    for (item in items) if (item.imdb == imdb) for (file in item.files.orEmpty()) {
        val ep = parseEpisode(file.name) ?: continue
        val at = item.positions[file.index] ?: continue
        val of = item.durations[file.index]?.takeIf { it > 0 } ?: continue
        out[ep] = maxOf(out[ep] ?: 0.0, (at / of).coerceIn(0.0, 1.0))
    }
    return out
}

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
            seeders = item.seeders ?: prev?.seeders,
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

/** Where an episode's opening titles sit, in seconds: from IntroDB, or marked on this phone before. */
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

/**
 * IntroDB (introdb.app): community-submitted intro windows per episode, by IMDb id.
 * Reads need no key; an episode nobody has timed answers 404, which is cached like a hit.
 */
object IntroDb {
    private const val BASE = "https://api.introdb.app"
    private val cache = HashMap<String, Intro?>()

    suspend fun intro(imdb: String, season: Int, episode: Int): Intro? {
        if (!imdb.startsWith("tt")) return null
        val key = "$imdb:$season:$episode"
        synchronized(cache) { if (key in cache) return cache[key] }
        val found = try {
            parseIntroDb(Net.get("$BASE/segments?imdb_id=${enc(imdb)}&season=$season&episode=$episode", timeoutMs = 8000, quiet = true))
        } catch (e: HttpException) {
            if (e.code != 404) return null
            null
        } catch (e: Exception) {
            return null
        }
        synchronized(cache) { cache[key] = found }
        return found
    }
}

/** The "intro" segment of a /segments answer, in seconds (start_sec, else start_ms). */
fun parseIntroDb(json: String): Intro? {
    val seg = runCatching { JSONObject(json).optJSONObject("intro") }.getOrNull() ?: return null
    fun seconds(name: String): Double? = when {
        seg.has("${name}_sec") && !seg.isNull("${name}_sec") -> seg.optDouble("${name}_sec")
        seg.has("${name}_ms") && !seg.isNull("${name}_ms") -> seg.optDouble("${name}_ms") / 1000
        else -> null
    }?.takeIf { it.isFinite() }
    val start = seconds("start") ?: return null
    val end = seconds("end") ?: return null
    return if (start >= 0 && end > start) Intro(start, end) else null
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
