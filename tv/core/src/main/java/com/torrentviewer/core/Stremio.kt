package com.torrentviewer.core

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** What the viewer is shown to connect: [userCode] typed at [uri], or [uriComplete] opened as is. */
data class DeviceCode(val deviceCode: String, val userCode: String, val uri: String, val uriComplete: String, val expiresIn: Int, val interval: Int)

/** Stremio's link sign-in: a short code, and its page on link.stremio.com where a signed-in viewer approves it. */
fun parseLink(json: String): DeviceCode {
    val o = JSONObject(json).let { it.optJSONObject("result") ?: it }
    val code = o.getString("code")
    // ponytail: ten minutes is a guess at the code's life; the poll stops there and the viewer asks again.
    return DeviceCode(code, code, "https://link.stremio.com", o.str("link") ?: "https://link.stremio.com/$code", 600, 3)
}

/** The account's key once the code is approved; null while it waits (Stremio answers an error until then). */
fun parseLinkRead(json: String): String? = runCatching { JSONObject(json).optJSONObject("result")?.str("authKey") }.getOrNull()

/** "tt0944947:1:2" → S1E2; null for a film's id or one that names no episode. */
fun epOfVideo(videoId: String?): Ep? {
    val parts = videoId?.split(':') ?: return null
    if (parts.size < 3) return null
    val s = parts[parts.size - 2].toIntOrNull() ?: return null
    val e = parts.last().toIntOrNull() ?: return null
    return Ep(s, e)
}

/** How far in counts as watched. */
const val WATCHED_AT = 0.8

/**
 * What this device has watched through ([WATCHED_AT], as [Watch] marks it), by id. An episode
 * is known by its file's S/E; a file named only by its number through the show can't say, so it's left out.
 */
fun localWatched(items: List<Item>): StremioLibrary {
    val films = mutableSetOf<String>()
    val episodes = mutableMapOf<String, MutableSet<Ep>>()
    for (item in items) {
        val imdb = item.imdb ?: continue
        for ((index, at) in item.positions) {
            val of = item.durations[index]?.takeIf { it > 0 } ?: continue
            if (at / of < WATCHED_AT) continue
            if (item.kind == "movie") films += imdb
            else item.files?.find { it.index == index }?.name?.let(::parseEpisode)?.let { episodes.getOrPut(imdb) { mutableSetOf() } += it }
        }
    }
    return StremioLibrary(films, episodes)
}

/** Where the viewer stopped, on any device: [progress] is 0–100, [seconds] the spot itself when known. [ep] is null for a film. */
data class Paused(val imdb: String, val title: String, val year: String?, val ep: Ep?, val progress: Double, val seconds: Double? = null) {
    val kind get() = if (ep == null) "movie" else "tv"
}

/** A title in the viewer's Stremio library: [last] is "S01E05" for a show, [at] an ISO time, newest sorting first. */
data class Seen(val imdb: String, val title: String, val year: String?, val kind: String, val at: String, val last: String? = null)

/** What Stremio knows: films watched through, episodes, what was left part-way, and the library itself. */
data class StremioLibrary(
    val films: Set<String> = emptySet(),
    val episodes: Map<String, Set<Ep>> = emptyMap(),
    val paused: List<Paused> = emptyList(),
    /** The library, newest first. */
    val seen: List<Seen> = emptyList(),
) {
    fun pausedAt(imdb: String, ep: Ep?): Double? = paused.find { it.imdb == imdb && it.ep == ep }?.progress

    fun pausedSeconds(imdb: String, ep: Ep?): Double? = paused.find { it.imdb == imdb && it.ep == ep }?.seconds

    /**
     * What the Library adds below this device's own history: Stremio's titles that [items] never played —
     * a reinstall, another device — paused ones first, as they are the ones to go back to.
     */
    fun notIn(items: List<Item>): List<Seen> {
        val here = items.mapNotNull { it.imdb }.toSet()
        val left = paused.map { Seen(it.imdb, it.title, it.year, it.kind, "", it.ep?.let { e -> "S%02dE%02d".format(e.season, e.episode) }) }
        return (left + seen).filter { it.imdb !in here }.distinctBy { it.imdb }
    }
}

/**
 * datastoreGet's library items. Watched through is `timesWatched` or `flaggedWatched`; part-way is a
 * `timeOffset` short of [WATCHED_AT]; the library is what is neither `removed` nor only `temp` (seen once).
 * ponytail: per-episode watched marks live in Stremio's `watched` bitfield (compressed); only the last
 * episode played is read here. Decode it if episode ticks from other devices matter.
 */
fun parseLibrary(json: String): StremioLibrary {
    val items = runCatching { JSONObject(json).optJSONArray("result")?.objects() }.getOrNull().orEmpty()
    val films = mutableSetOf<String>()
    val episodes = mutableMapOf<String, MutableSet<Ep>>()
    val paused = mutableListOf<Pair<String, Paused>>()
    val seen = mutableListOf<Seen>()
    for (it in items) {
        val id = it.str("_id") ?: continue
        val kind = kindOfType(it.optString("type", "movie"))
        val state = it.optJSONObject("state") ?: JSONObject()
        val name = it.optString("name")
        val at = state.str("lastWatched") ?: it.optString("_mtime")
        val through = state.optInt("timesWatched") > 0 || state.optInt("flaggedWatched") > 0
        val ep = if (kind == "tv") epOfVideo(state.str("video_id")) else null
        if (through && kind == "movie") films += id
        val offset = state.optLong("timeOffset")
        val duration = state.optLong("duration")
        val part = if (duration > 0) offset.toDouble() / duration else 0.0
        if (through && ep != null && part == 0.0) episodes.getOrPut(id) { mutableSetOf() } += ep
        if (offset > 0 && part >= 0.02 && part < WATCHED_AT && (kind == "movie" || ep != null)) {
            paused += at to Paused(id, name, null, ep, part * 100, offset / 1000.0)
        }
        if (!it.optBoolean("removed") && !it.optBoolean("temp")) seen += Seen(id, name, null, kind, at, ep?.let { e -> "S%02dE%02d".format(e.season, e.episode) })
    }
    return StremioLibrary(films, episodes, paused.sortedByDescending { it.first }.map { it.second }, seen.sortedByDescending { it.at })
}

private fun utc(pattern: String) = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

private fun isoNow(now: Long = System.currentTimeMillis()): String = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(java.util.Date(now))

/** Stremio's times, with or without the milliseconds; null for anything else. */
fun isoMs(s: String?): Long? {
    s ?: return null
    for (p in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) runCatching { utc(p).parse(s)?.time }.getOrNull()?.let { return it }
    return null
}

/**
 * What this device played that Stremio hasn't heard of — from before signing in, or while offline: per
 * title its latest play, past [WATCHED_AT] as watched, else its paused spot. Stremio's own item wins when
 * it was watched since; the play's own time goes up as lastWatched, so the next open doesn't send it again.
 * [remote] is the account's library by id. A play with no id (a pasted link) or episode can't be placed.
 */
fun backlog(items: List<Item>, remote: Map<String, JSONObject>): List<JSONObject> = latestPerTitle(items).mapNotNull { item ->
    val id = item.imdb ?: return@mapNotNull null
    val i = item.lastFileIndex ?: return@mapNotNull null
    val at = item.positions[i] ?: return@mapNotNull null
    val of = item.durations[i]?.takeIf { it > 0 } ?: return@mapNotNull null
    val r = at / of
    if (r < 0.02) return@mapNotNull null
    val old = remote[id]
    if ((isoMs(old?.optJSONObject("state")?.str("lastWatched")) ?: 0L) >= item.at) return@mapNotNull null
    val film = item.kind == "movie"
    val ep = if (film) null else item.files?.find { it.index == i }?.name?.let(::parseEpisode) ?: return@mapNotNull null
    libraryItem(old, id, item.label.substringBefore(" · "), if (film) "movie" else "series", item.poster, now = item.at) { s ->
        s.put("lastWatched", isoNow(item.at))
        if (ep != null) s.put("video_id", "$id:${ep.season}:${ep.episode}")
        if (r >= WATCHED_AT) s.put("timesWatched", s.optInt("timesWatched") + 1).put("flaggedWatched", 1).put("timeOffset", 0)
        else s.put("timeOffset", (at * 1000).toLong()).put("duration", (of * 1000).toLong())
    }
}

/**
 * A library item as Stremio's own apps read it: [old] as stored with [change] made to its state, or a new
 * one with every field present and typed — an item missing one could break the viewer's library there.
 * A new item is `temp` (in Continue Watching only) unless [inLibrary] says otherwise.
 */
fun libraryItem(old: JSONObject?, id: String, name: String, type: String, poster: String?, inLibrary: Boolean? = null, now: Long = System.currentTimeMillis(), change: (JSONObject) -> Unit = {}): JSONObject {
    val stamp = isoNow(now)
    val o = old?.let { JSONObject(it.toString()) } ?: JSONObject()
        .put("_id", id).put("name", name).put("type", type).put("poster", poster ?: JSONObject.NULL).put("posterShape", "poster")
        .put("removed", false).put("temp", true).put("_ctime", stamp)
        .put("behaviorHints", JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false))
    val state = o.optJSONObject("state") ?: JSONObject()
    for ((k, v) in listOf("timeWatched" to 0, "timeOffset" to 0, "overallTimeWatched" to 0, "timesWatched" to 0, "flaggedWatched" to 0, "duration" to 0)) if (!state.has(k)) state.put(k, v)
    for (k in listOf("lastWatched", "video_id", "watched")) if (!state.has(k)) state.put(k, JSONObject.NULL)
    if (!state.has("noNotif")) state.put("noNotif", false)
    change(state)
    o.put("state", state).put("_mtime", stamp)
    if (inLibrary != null) o.put("removed", !inLibrary).put("temp", false)
    return o
}

/**
 * The account's addon collection without the one at [url] (by its base, as Watchly keeps it), every
 * other entry exactly as Stremio sent it. Protected ones — Stremio's own — stay whatever the URL.
 */
fun withoutAddon(collection: List<JSONObject>, url: String): List<JSONObject> = collection.filterNot { a ->
    addonBase(a.optString("transportUrl")) == url && a.optJSONObject("flags")?.optBoolean("protected") != true
}

/**
 * [up] with My List added to the account's library: each title saved here that isn't in it — absent,
 * removed, or only seen once (temp) — goes in, unless it was sent before ([sent]): one taken out
 * there on purpose stays out. Nothing is ever removed this way. Merged into [up], by id.
 */
fun withMyList(up: Map<String, JSONObject>, remote: Map<String, JSONObject>, saved: List<Saved>, sent: Set<String>): Map<String, JSONObject> {
    val out = up.toMutableMap()
    for (s in saved) {
        if (s.list != "later" || s.imdb in sent) continue
        val now = out[s.imdb] ?: remote[s.imdb]
        if (now != null && !now.optBoolean("removed") && !now.optBoolean("temp")) continue
        out[s.imdb] = libraryItem(now, s.imdb, s.title, if (s.kind == "movie") "movie" else "series", s.poster, inLibrary = true)
    }
    return out
}

/**
 * The viewer's Stremio account: sign in by a code approved on link.stremio.com (no password here, and
 * the TV needs no keyboard), then the library, where films were left and what was finished come and go
 * both ways, and the account's addons come across. The key is per profile, encrypted like the SubDL key;
 * the guest's stays in memory.
 */
object Stremio {
    private const val API = "https://api.strem.io/api"
    private const val LINK = "https://link.stremio.com/api"

    /** Always: Stremio needs no key of ours, unlike Simkl did. Kept for the screens that ask. */
    val available = true

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** The account's email, for "Signed in as". */
    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email

    /** Pulled once per profile per launch, so a reinstall gets its library and paused films back. */
    private val _library = MutableStateFlow(StremioLibrary())
    val library: StateFlow<StremioLibrary> = _library

    private var profile = ""
    /** The profile [email] and [connected] speak for. */
    val profileId get() = profile
    private var guest = false
    private var key: String? = null

    private fun slot(id: String) = "stremio:$id"

    /** Per profile: My List titles already put in the account's library, so one removed there stays removed. */
    private const val LIST_SENT = "stremio-list-sent"

    private fun listSent(): Set<String> = runCatching { JSONArray(Kv.get(LIST_SENT) ?: "[]").let { a -> (0 until a.length()).map(a::getString).toSet() } }.getOrDefault(emptySet())

    /** On every profile switch, like History.load. */
    fun load(id: String, isGuest: Boolean) {
        profile = id
        guest = isGuest
        val stored = if (isGuest) null else Secrets.get(slot(id))?.let { runCatching { JSONObject(it) }.getOrNull() }
        key = stored?.str("authKey")
        _email.value = stored?.str("email")
        _connected.value = key != null
        _library.value = StremioLibrary()
        pull()
    }

    /** One call to Stremio's API; a session it no longer knows signs this profile out. */
    private suspend fun call(method: String, body: JSONObject, owner: String = profile): JSONObject {
        val k = (if (owner == profile) key else stored(owner)) ?: throw IOException("Not signed in")
        val o = JSONObject(Net.postJson("$API/$method", body.put("authKey", k).toString(), 30_000, quiet = true))
        o.optJSONObject("error")?.let { e ->
            if (e.optInt("code") == 1) save(null, null, owner)
            throw IOException(e.optString("message", "Stremio said no"))
        }
        return o
    }

    private fun stored(id: String) = Secrets.get(slot(id))?.let { runCatching { JSONObject(it).str("authKey") }.getOrNull() }

    /** Memory only for the guest watching; [owner]'s own entry otherwise, and live if it is the one watching. */
    private fun save(next: String?, email: String?, owner: String = profile) {
        if (!(guest && owner == profile)) Secrets.put(slot(owner), next?.let { JSONObject().put("authKey", it).put("email", email).toString() })
        if (owner == profile) {
            key = next
            _email.value = email
            _connected.value = next != null
            if (next == null) _library.value = StremioLibrary()
        }
    }

    fun forget(id: String) = Secrets.put(slot(id), null)

    /** Any profile's, not only the one watching — the profile editor connects whichever it has open. */
    fun isConnected(id: String) = if (id == profile) key != null else stored(id) != null

    /**
     * The viewer's library and paused playbacks — and, up the other way, whatever this device played that
     * the account hasn't got ([backlog]), in one write. Every open and every sign-in, so plays from before
     * signing in, or offline, need no remembering. Off the network, it all stays as it was.
     */
    fun pull() {
        if (key == null) return
        val owner = profile
        AppScope.launch {
            runCatching {
                val got = call("datastoreGet", JSONObject().put("collection", "libraryItem").put("ids", JSONArray()).put("all", true), owner)
                val theirs = got.optJSONArray("result")?.objects().orEmpty().associateBy { it.optString("_id") }
                val played = backlog(History.items.value, theirs).associateBy { it.optString("_id") }
                // My List from before signing in goes into the library once; after that, toggles go up as they happen.
                val listed = SavedStore.items.value.filter { it.list == "later" }
                val up = withMyList(played, theirs, listed, listSent())
                if (owner != profile) return@runCatching
                if (up.isNotEmpty()) {
                    call("datastorePut", JSONObject().put("collection", "libraryItem").put("changes", JSONArray(up.values)), owner)
                    Log.i("stremio", "sent ${up.size}: ${played.size} from this device's history, the rest from My List")
                }
                if (owner == profile) Kv.set(LIST_SENT, JSONArray(listSent() + listed.map { it.imdb }).toString())
                val merged = theirs + up
                if (owner == profile) _library.value = parseLibrary(JSONObject().put("result", JSONArray(merged.values)).toString())
            }.onFailure { Log.w("stremio", "pull: ${it.message}") }
        }
    }

    suspend fun start(): DeviceCode = parseLink(Net.get("$LINK/create?type=Create", 15_000, quiet = true))

    /**
     * Polls until approved (true), or the code runs out (false). Approved, the account's addons come
     * across and its library is pulled. The caller can cancel.
     */
    suspend fun await(code: DeviceCode, owner: String = profile): Boolean {
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.interval * 1000L)
            val authKey = runCatching { parseLinkRead(Net.get("$LINK/read?type=Read&code=${enc(code.deviceCode)}", 15_000, quiet = true)) }.getOrNull()
                ?: continue
            val email = runCatching {
                JSONObject(Net.postJson("$API/getUser", JSONObject().put("type", "GetUser").put("authKey", authKey).toString(), 15_000, quiet = true))
                    .optJSONObject("result")?.str("email")
            }.getOrNull()
            save(authKey, email, owner)
            if (owner == profile) {
                pull()
                runCatching { importAddons() }
            }
            return true
        }
        return false
    }

    /**
     * The account's installed addons, added here (one already here is left be). Cinemeta and Stremio's
     * local-files addon stay out: Watchly has its own of both. How many came across.
     */
    suspend fun importAddons(): Int {
        val got = call("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("update", true))
        val addons = got.optJSONObject("result")?.optJSONArray("addons")?.objects().orEmpty().mapNotNull { a ->
            val manifest = a.optJSONObject("manifest") ?: return@mapNotNull null
            if (manifest.optString("id") in setOf("com.linvo.cinemeta", "org.stremio.local")) return@mapNotNull null
            val base = addonBase(a.optString("transportUrl")) ?: return@mapNotNull null
            if (Regex("^https?://(127\\.0\\.0\\.1|localhost)").containsMatchIn(base)) return@mapNotNull null
            toAddon(base, manifest)
        }
        return Addons.merge(addons)
    }

    /**
     * An addon removed here goes from the account's collection too, so the next import doesn't bring it
     * back: the collection as it stands, less that one, written back whole. Fire and forget.
     */
    fun removeAddon(url: String) {
        if (key == null) return
        val owner = profile
        AppScope.launch {
            runCatching {
                val got = call("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("update", true), owner)
                val all = got.optJSONObject("result")?.optJSONArray("addons")?.objects() ?: return@runCatching
                val keep = withoutAddon(all, url)
                if (keep.size == all.size) return@runCatching
                call("addonCollectionSet", JSONObject().put("type", "AddonCollectionSet").put("addons", JSONArray(keep)), owner)
                Log.i("stremio", "removed an addon from the account")
            }.onFailure { Log.w("stremio", "remove addon: ${it.message}") }
        }
    }

    fun disconnect(owner: String = profile) {
        val gone = if (owner == profile) key else stored(owner)
        save(null, null, owner)
        gone ?: return
        AppScope.launch { runCatching { Net.postJson("$API/logout", JSONObject().put("type", "Logout").put("authKey", gone).toString(), quiet = true) } }
    }

    /** Read, change and write back one library item; fire and forget, so Stremio being down never troubles playback. */
    private fun update(id: String, name: String, type: String, poster: String?, inLibrary: Boolean? = null, change: (JSONObject) -> Unit = {}) {
        if (key == null) return
        val owner = profile
        AppScope.launch {
            runCatching {
                val old = call("datastoreGet", JSONObject().put("collection", "libraryItem").put("ids", JSONArray().put(id)).put("all", false), owner)
                    .optJSONArray("result")?.objects()?.firstOrNull()
                val item = libraryItem(old, id, name, type, poster, inLibrary, change = change)
                call("datastorePut", JSONObject().put("collection", "libraryItem").put("changes", JSONArray().put(item)), owner)
            }.onFailure { Log.w("stremio", "update: ${it.message}") }
        }
    }

    private fun typeOf(kind: String?) = if (kind == "movie") "movie" else "series"

    /** A film or an episode finished: watched once more, and no longer part-way. */
    fun watched(at: Item, season: Int?, episode: Int?) {
        val imdb = at.imdb ?: return
        if (key == null) return
        val ep = if (season != null && episode != null) Ep(season, episode) else null
        _library.value = _library.value.let { l ->
            l.copy(
                films = if (ep == null) l.films + imdb else l.films,
                episodes = if (ep == null) l.episodes else l.episodes + (imdb to (l.episodes[imdb].orEmpty() + ep)),
                paused = l.paused.filterNot { it.imdb == imdb && it.ep == ep },
            )
        }
        update(imdb, at.label.substringBefore(" · "), typeOf(at.kind), at.poster) { s ->
            s.put("timesWatched", s.optInt("timesWatched") + 1).put("flaggedWatched", 1).put("timeOffset", 0).put("lastWatched", isoNow())
            if (ep != null) s.put("video_id", "$imdb:${ep.season}:${ep.episode}")
        }
    }

    /** Left part-way: Stremio keeps the spot, and Continue Watching on any device picks it up. */
    fun pause(at: Item, ep: Ep?, seconds: Double, duration: Double) {
        val imdb = at.imdb ?: return
        if (key == null || duration <= 0) return
        val title = at.label.substringBefore(" · ")
        _library.value = _library.value.let { l -> l.copy(paused = listOf(Paused(imdb, title, null, ep, seconds / duration * 100, seconds)) + l.paused.filterNot { it.imdb == imdb && it.ep == ep }) }
        update(imdb, title, typeOf(at.kind), at.poster) { s ->
            s.put("timeOffset", (seconds * 1000).toLong()).put("duration", (duration * 1000).toLong()).put("lastWatched", isoNow())
            if (ep != null) s.put("video_id", "$imdb:${ep.season}:${ep.episode}")
        }
    }

    /** My List here is the library there: added or taken out together. */
    fun setInLibrary(title: Title, on: Boolean) = update(title.imdb, title.title, title.type, title.poster, inLibrary = on)
}
