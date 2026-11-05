package com.torrentviewer.core

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Far past any real library; a cursor that never ends must not spend the day's requests. */
private const val MAX_PAGES = 30

data class TrackerTokens(val access: String, val refresh: String?, val expires: Long)

/** A device code answer, in the RFC 8628 shape Simkl and MDBList both give. */
fun parseDeviceCode(json: String): DeviceCode {
    val o = JSONObject(json)
    val uri = o.getString("verification_uri")
    return DeviceCode(o.getString("device_code"), o.getString("user_code"), uri, o.str("verification_uri_complete") ?: uri, o.optInt("expires_in", 300), o.optInt("interval", 5))
}

fun parseTokens(json: String, now: Long = System.currentTimeMillis()): TrackerTokens? = runCatching {
    val o = JSONObject(json)
    o.str("access_token")?.let { TrackerTokens(it, o.str("refresh_token"), now + o.optLong("expires_in", 7 * 24 * 3600L) * 1000) }
}.getOrNull()

fun oauthError(json: String): String? = runCatching { JSONObject(json).str("error") }.getOrNull()

/**
 * One film or episode, watched at [at]: the body Simkl's /sync/history and MDBList's /sync/watched both take.
 * [animeSeasons] has Simkl route these season numbers to its own anime entries; on a show that isn't anime it does nothing.
 */
fun historyBody(imdb: String, ep: Ep?, at: String, animeSeasons: Boolean = false): JSONObject {
    val ids = JSONObject().put("imdb", imdb)
    if (ep == null) return JSONObject().put("movies", JSONArray().put(JSONObject().put("ids", ids).put("watched_at", at)))
    val episode = JSONObject().put("number", ep.episode).put("watched_at", at)
    val season = JSONObject().put("number", ep.season).put("episodes", JSONArray().put(episode))
    val show = JSONObject().put("ids", ids).put("seasons", JSONArray().put(season))
    if (animeSeasons) show.put("use_tvdb_anime_seasons", true)
    return JSONObject().put("shows", JSONArray().put(show))
}

/** Everything watched on either side; the paused and library lists are only ever Stremio's. */
operator fun StremioLibrary.plus(other: StremioLibrary) = copy(
    films = films + other.films,
    episodes = (episodes.keys + other.episodes.keys).associateWith { episodes[it].orEmpty() + other.episodes[it].orEmpty() },
    paused = paused + other.paused,
    seen = seen + other.seen,
)

/** Simkl's /sync/all-items, any of its types. Anime episodes carry the season numbers the rest of the app uses under `tvdb`. */
fun parseSimkl(json: String): StremioLibrary {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return StremioLibrary()
    val films = mutableSetOf<String>()
    val episodes = mutableMapOf<String, MutableSet<Ep>>()
    for (type in listOf("movies", "shows", "anime")) o.optJSONArray(type)?.objects().orEmpty().forEach { item ->
        val imdb = (item.optJSONObject("movie") ?: item.optJSONObject("show"))?.optJSONObject("ids")?.str("imdb") ?: return@forEach
        if (type == "movies" || item.str("anime_type") == "movie") {
            if (item.str("status") == "completed") films += imdb
            return@forEach
        }
        item.optJSONArray("seasons")?.objects().orEmpty().forEach { s ->
            s.optJSONArray("episodes")?.objects().orEmpty().forEach { e ->
                if (e.optBoolean("watched", true)) {
                    val tvdb = e.optJSONObject("tvdb")
                    episodes.getOrPut(imdb) { mutableSetOf() } +=
                        if (tvdb != null) Ep(tvdb.optInt("season"), tvdb.optInt("episode")) else Ep(s.optInt("number"), e.optInt("number"))
                }
            }
        }
    }
    return StremioLibrary(films = films, episodes = episodes)
}

/** One page of MDBList's /sync/watched, and the cursor to the next. */
fun parseMdblist(json: String): Pair<StremioLibrary, String?> {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return StremioLibrary() to null
    val films = o.optJSONArray("movies")?.objects().orEmpty().mapNotNull { it.optJSONObject("movie")?.optJSONObject("ids")?.str("imdb") }.toSet()
    val episodes = mutableMapOf<String, MutableSet<Ep>>()
    o.optJSONArray("episodes")?.objects().orEmpty().forEach { row ->
        val e = row.optJSONObject("episode") ?: return@forEach
        val imdb = e.optJSONObject("show")?.optJSONObject("ids")?.str("imdb") ?: return@forEach
        episodes.getOrPut(imdb) { mutableSetOf() } += Ep(e.optInt("season"), e.optInt("number"))
    }
    return StremioLibrary(films = films, episodes = episodes) to o.optJSONObject("pagination")?.str("next_cursor")
}

fun watchedJson(l: StremioLibrary): String = JSONObject()
    .put("films", JSONArray(l.films))
    .put("episodes", JSONObject(l.episodes.mapValues { (_, eps) -> JSONArray(eps.map { "${it.season}:${it.episode}" }) }))
    .toString()

fun parseWatched(json: String?): StremioLibrary = runCatching {
    val o = JSONObject(json ?: return StremioLibrary())
    val films = o.getJSONArray("films").let { a -> (0 until a.length()).map(a::getString).toSet() }
    val saved = o.getJSONObject("episodes")
    val episodes = saved.keys().asSequence().associateWith { imdb ->
        saved.getJSONArray(imdb).let { a -> (0 until a.length()).map { i -> a.getString(i).split(":").let { Ep(it[0].toInt(), it[1].toInt()) } }.toSet() }
    }
    StremioLibrary(films = films, episodes = episodes)
}.getOrDefault(StremioLibrary())

/**
 * A watch-tracking account, signed in on its own site by a device code, one per profile.
 * What is finished here goes into a saved queue and is sent from it, so a finish with no network
 * goes the next time there is one; what is watched there comes back as [library].
 */
class Tracker(
    val name: String,
    /** For its logo. */
    val site: String,
    private val api: String,
    private val devicePath: String,
    private val tokenPath: String,
    private val revokePath: String,
    private val historyPath: String,
    private val activitiesPath: String,
    private val scope: String,
    /** Simkl wants the app named on every call, and takes the anime flag. */
    private val isSimkl: Boolean = false,
    private val read: suspend (get: suspend (String) -> String) -> StremioLibrary,
) {
    /** From the app's build; empty leaves this service out. */
    var clientId = ""
    val available get() = clientId.isNotEmpty()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _library = MutableStateFlow(StremioLibrary())
    val library: StateFlow<StremioLibrary> = _library

    private var profile = ""
    private var guest = false
    private var tokens: TrackerTokens? = null
    private var flushing = false
    private var listening = false

    private val key = name.lowercase()
    private val queueKey = "$key-queue"
    private val libraryKey = "$key-library"
    private val activitiesKey = "$key-activities"

    private fun slot(id: String) = "$key:$id"

    private fun url(path: String) = api + path + if (isSimkl) "${if ('?' in path) '&' else '?'}client_id=${enc(clientId)}&app-name=watchly&app-version=2" else ""

    private fun auth(t: TrackerTokens) = mapOf("Authorization" to "Bearer ${t.access}")

    /** On every profile switch, like [Stremio.load]. After Kv has the profile. */
    fun load(id: String, isGuest: Boolean) {
        profile = id
        guest = isGuest
        tokens = if (isGuest) null else Secrets.get(slot(id))?.let { s ->
            runCatching { JSONObject(s).let { TrackerTokens(it.getString("access"), it.str("refresh"), it.getLong("expires")) } }.getOrNull()
        }
        _connected.value = tokens != null
        _library.value = if (tokens != null) parseWatched(Kv.get(libraryKey)) else StremioLibrary()
        if (!listening) {
            listening = true
            // Back online: true again on Net, and true at once on the first collect.
            AppScope.launch { Net.online.collect { if (it) flush() } }
        } else flush()
        pull()
    }

    /** Memory only for the guest. */
    private fun save(next: TrackerTokens?) {
        tokens = next
        _connected.value = next != null
        if (!guest) Secrets.put(slot(profile), next?.let { JSONObject().put("access", it.access).put("refresh", it.refresh).put("expires", it.expires).toString() })
        if (next == null) {
            _library.value = StremioLibrary()
            listOf(queueKey, libraryKey, activitiesKey).forEach { Kv.set(it, null) }
        }
    }

    fun forget(id: String) = Secrets.put(slot(id), null)

    suspend fun start(): DeviceCode = parseDeviceCode(Net.postForm(url(devicePath), mapOf("client_id" to clientId, "scope" to scope)))

    /** Polls until approved (true), or refused or out of time (false). The caller can cancel. */
    suspend fun await(code: DeviceCode): Boolean {
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        var wait = code.interval
        while (System.currentTimeMillis() < deadline) {
            delay(wait * 1000L)
            val answer = try {
                Net.postForm(url(tokenPath), mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to code.deviceCode))
            } catch (e: HttpException) {
                when (oauthError(e.body)) {
                    "slow_down" -> wait += 5
                    "access_denied", "expired_token", "invalid_client", "invalid_grant" -> return false
                }
                continue
            } catch (_: IOException) {
                continue
            }
            parseTokens(answer)?.let {
                save(it)
                pull()
                return true
            }
        }
        return false
    }

    fun disconnect() {
        val gone = tokens ?: return
        save(null)
        AppScope.launch { runCatching { Net.postForm(url(revokePath), mapOf("token" to gone.access, "client_id" to clientId)) } }
    }

    /** Tokens expire: a refused refresh means the viewer took the app away there, so it signs out here too. */
    private suspend fun refreshed(): TrackerTokens? {
        val old = tokens ?: return null
        val refresh = old.refresh ?: return null.also { save(null) }
        val next = try {
            parseTokens(Net.postForm(url(tokenPath), mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh)))
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) save(null)
            null
        } ?: return null
        return next.copy(refresh = next.refresh ?: refresh).also(::save)
    }

    /** [call] with a live token, renewed first if it is nearly out or turns out refused. Null once signed out. */
    private suspend fun <T> authed(call: suspend (TrackerTokens) -> T): T? {
        val now = tokens ?: return null
        val t = (if (now.expires - System.currentTimeMillis() < DAY_MS) refreshed() else now) ?: return null
        return try {
            call(t)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            call(refreshed() ?: return null)
        }
    }

    private fun queued(): List<String> = runCatching { JSONArray(Kv.get(queueKey) ?: "[]").let { a -> (0 until a.length()).map(a::getString) } }.getOrDefault(emptyList())

    private fun setQueue(bodies: List<String>) = Kv.set(queueKey, if (bodies.isEmpty()) null else JSONArray(bodies).toString())

    /** Sends the queue in order; no network or a busy service leaves the rest for next time. */
    private fun flush() {
        if (flushing || tokens == null) return
        flushing = true
        AppScope.launch {
            try {
                val owner = profile
                while (owner == profile) {
                    val body = queued().firstOrNull() ?: break
                    try {
                        authed { Net.postJson(url(historyPath), body, 30_000, quiet = true, headers = auth(it)) } ?: break
                    } catch (e: HttpException) {
                        if (e.code == 429 || e.code >= 500) break
                        Log.w(key, "dropped a watch it refused: ${e.code}")
                    } catch (_: IOException) {
                        break
                    }
                    setQueue(queued().drop(1))
                }
            } finally {
                flushing = false
            }
        }
    }

    /**
     * What is watched there. Asks first whether anything moved, as both services require, then
     * reads it all and keeps it for the next launch.
     * ponytail: a full read when anything moved, so finishing a film costs one at next launch; Simkl's date_from deltas if libraries get huge.
     */
    private fun pull() {
        if (tokens == null) return
        val owner = profile
        AppScope.launch {
            runCatching {
                val activities = authed { Net.get(url(activitiesPath), 15_000, quiet = true, headers = auth(it)) } ?: return@runCatching
                if (activities == Kv.get(activitiesKey)) return@runCatching
                val got = read { path -> authed { Net.get(url(path), 60_000, quiet = true, headers = auth(it)) } ?: throw IOException("Signed out") }
                if (owner != profile || tokens == null) return@runCatching
                _library.value = got
                Kv.set(libraryKey, watchedJson(got))
                Kv.set(activitiesKey, activities)
            }.onFailure { Log.w(key, "pull: ${it.message}") }
        }
    }

    /** A film or an episode finished. */
    fun watched(at: Item, season: Int?, episode: Int?) {
        val imdb = at.imdb ?: return
        if (tokens == null) return
        val ep = if (season != null && episode != null) Ep(season, episode) else null
        setQueue(queued() + historyBody(imdb, ep, isoNow(), animeSeasons = isSimkl).toString())
        flush()
    }
}

val Simkl = Tracker(
    "Simkl", "simkl.com", "https://api.simkl.com", "/oauth2/device", "/oauth2/token", "/oauth2/revoke", "/sync/history", "/sync/activities", "media:read media:write", isSimkl = true,
) { get ->
    parseSimkl(get("/sync/all-items/movies/completed?extended=ids_only")) +
        parseSimkl(get("/sync/all-items/shows?extended=full&include_all_episodes=yes")) +
        parseSimkl(get("/sync/all-items/anime?extended=full_anime_seasons&include_all_episodes=yes"))
}

val Mdblist = Tracker(
    "MDBList", "mdblist.com", "https://api.mdblist.com", "/oauth/device-authorization/", "/oauth/token/", "/oauth/revoke_token/", "/sync/watched", "/sync/last_activities", "write",
) { get ->
    var all = StremioLibrary()
    for (type in listOf("movie", "episode")) {
        var cursor: String? = null
        for (page in 1..MAX_PAGES) {
            val (got, next) = parseMdblist(get("/sync/watched?mediatype=$type&limit=1000" + cursor?.let { "&cursor=${enc(it)}" }.orEmpty()))
            all += got
            cursor = next ?: break
        }
    }
    all
}

val WATCH_TRACKERS = listOf(Simkl, Mdblist)

/** Stremio's library with what the tracking accounts say has been watched. */
val WatchedEverywhere: Flow<StremioLibrary> = combine(Stremio.library, Simkl.library, Mdblist.library) { a, b, c -> a + b + c }
