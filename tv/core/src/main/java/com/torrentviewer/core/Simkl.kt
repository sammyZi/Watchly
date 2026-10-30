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

data class SimklTokens(val access: String, val refresh: String, val expiresAt: Long) {
    fun json() = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt).toString()

    companion object {
        fun from(json: String) = runCatching { JSONObject(json).let { SimklTokens(it.getString("access"), it.getString("refresh"), it.getLong("expiresAt")) } }.getOrNull()
    }
}

sealed interface Poll {
    data class Done(val tokens: SimklTokens) : Poll
    data object Pending : Poll
    data object SlowDown : Poll
    data object Expired : Poll
    data class Failed(val why: String) : Poll
}

fun parseDevice(json: String): DeviceCode = JSONObject(json).let {
    val uri = it.str("verification_uri") ?: "https://simkl.com/pin"
    val code = it.getString("user_code")
    DeviceCode(it.getString("device_code"), code, uri, it.str("verification_uri_complete") ?: "$uri?user_code=$code", it.optInt("expires_in", 900), it.optInt("interval", 5))
}

fun parseTokens(json: String, now: Long): SimklTokens = JSONObject(json).let {
    // Refresh is non-rotating and may leave the refresh token out; the caller keeps the old one then.
    SimklTokens(it.getString("access_token"), it.optString("refresh_token"), now + it.optLong("expires_in", 7 * 86_400L) * 1000)
}

/** One answer from the token endpoint while the viewer has yet to approve. */
fun parsePoll(status: Int, json: String, now: Long): Poll {
    val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
    if (status in 200..299) {
        // A scope Simkl doesn't know is quietly read-only, which would fail only at the first write.
        if ("media:write" !in o.optString("scope")) return Poll.Failed("Simkl gave read-only access.")
        return runCatching { Poll.Done(parseTokens(json, now)) }.getOrElse { Poll.Failed("Simkl's answer made no sense.") }
    }
    return when (o.optString("error")) {
        "authorization_pending" -> Poll.Pending
        "slow_down" -> Poll.SlowDown
        "expired_token" -> Poll.Expired
        "invalid_client" -> Poll.Failed("This build's Simkl client ID isn't an AUTH V2 app.")
        else -> Poll.Failed(o.str("error_description") ?: "Simkl said no ($status).")
    }
}

/** A film, or one episode of a show; IMDb ids only, which is what Cinemeta hands us. */
fun historyBody(imdb: String, season: Int?, episode: Int?): String? =
    if (season == null || episode == null) historyBody(setOf(imdb), emptyMap()) else historyBody(emptySet(), mapOf(imdb to setOf(Ep(season, episode))))

/** Many at once — what was watched here before Simkl was connected. Null when no IMDb id is left. */
fun historyBody(films: Set<String>, episodes: Map<String, Set<Ep>>): String? {
    val tt = Regex("^tt\\d+$")
    val ids = { imdb: String -> JSONObject().put("imdb", imdb) }
    val movies = JSONArray().also { a -> films.filter(tt::matches).forEach { a.put(JSONObject().put("ids", ids(it))) } }
    val shows = JSONArray().also { a ->
        episodes.filterKeys(tt::matches).forEach { (imdb, eps) ->
            val seasons = JSONArray()
            eps.groupBy { it.season }.toSortedMap().forEach { (s, list) ->
                seasons.put(JSONObject().put("number", s).put("episodes", JSONArray().also { e -> list.sortedBy { it.episode }.forEach { e.put(JSONObject().put("number", it.episode)) } }))
            }
            if (eps.isNotEmpty()) a.put(JSONObject().put("ids", ids(imdb)).put("seasons", seasons))
        }
    }
    if (movies.length() == 0 && shows.length() == 0) return null
    return JSONObject().apply {
        if (movies.length() > 0) put("movies", movies)
        if (shows.length() > 0) put("shows", shows)
    }.toString()
}

/** How far in counts as watched — Simkl's own line, where its scrobble marks a title complete. */
const val WATCHED_AT = 0.8

/**
 * What this device has watched through ([WATCHED_AT], as [Watch] marks it), by IMDb id. An episode
 * is known by its file's S/E; a file named only by its number through the show can't say, so it's left out.
 */
fun localWatched(items: List<Item>): SimklLibrary {
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
    return SimklLibrary(films, episodes)
}

/** Where the viewer stopped, on any device: [progress] is 0–100. [ep] is null for a film. */
data class Paused(val imdb: String, val title: String, val year: String?, val ep: Ep?, val progress: Double) {
    val kind get() = if (ep == null) "movie" else "tv"
}

/** A title on the viewer's Simkl, for the Library: [last] is "S01E05" for a show, [at] Simkl's ISO time, newest sorting first. */
data class Seen(val imdb: String, val title: String, val year: String?, val kind: String, val at: String, val last: String? = null)

/** What Simkl knows the viewer has seen: films and episodes by IMDb id, and what they left part-way. */
data class SimklLibrary(
    val films: Set<String> = emptySet(),
    val episodes: Map<String, Set<Ep>> = emptyMap(),
    val paused: List<Paused> = emptyList(),
    /** Watched films and started shows, newest first. */
    val seen: List<Seen> = emptyList(),
) {
    fun pausedAt(imdb: String, ep: Ep?): Double? = paused.find { it.imdb == imdb && it.ep == ep }?.progress

    /**
     * What the Library adds below this device's own history: Simkl's titles that [items] never played —
     * a reinstall, another device — paused ones first, as they are the ones to go back to.
     */
    fun notIn(items: List<Item>): List<Seen> {
        val here = items.mapNotNull { it.imdb }.toSet()
        val left = paused.map { Seen(it.imdb, it.title, it.year, it.kind, "", it.ep?.let { e -> "S%02dE%02d".format(e.season, e.episode) }) }
        return (left + seen).filter { it.imdb !in here }.distinctBy { it.imdb }
    }

    /** What [local] has watched that this doesn't — what still has to go up. */
    fun missing(local: SimklLibrary) = SimklLibrary(
        local.films - films,
        local.episodes.mapValues { (imdb, eps) -> eps - episodes[imdb].orEmpty() }.filterValues { it.isNotEmpty() },
    )
}

fun Map<String, Set<Ep>>.plusSets(more: Map<String, Set<Ep>>): Map<String, Set<Ep>> =
    (keys + more.keys).associateWith { this[it].orEmpty() + more[it].orEmpty() }

private fun JSONObject.imdbOf(): String? = (optJSONObject("movie") ?: optJSONObject("show") ?: optJSONObject("anime"))
    ?.optJSONObject("ids")?.str("imdb")?.takeIf { Regex("^tt\\d+$").matches(it) }

/** /sync/all-items with extended=full: completed films, and every episode recorded on a show or anime. */
fun parseLibrary(json: String): SimklLibrary {
    val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
    val films = o.optJSONArray("movies")?.objects().orEmpty()
        .filter { it.optString("status") == "completed" }.mapNotNull { it.imdbOf() }.toSet()
    val episodes = mutableMapOf<String, MutableSet<Ep>>()
    val seen = mutableListOf<Seen>()
    for (row in o.optJSONArray("movies")?.objects().orEmpty()) {
        if (row.optString("status") != "completed") continue
        val imdb = row.imdbOf() ?: continue
        val m = row.optJSONObject("movie") ?: continue
        seen += Seen(imdb, m.optString("title"), m.str("year"), "movie", row.optString("last_watched_at"))
    }
    for (row in o.optJSONArray("shows")?.objects().orEmpty() + o.optJSONArray("anime")?.objects().orEmpty()) {
        val imdb = row.imdbOf() ?: continue
        for (s in row.optJSONArray("seasons")?.objects().orEmpty()) for (e in s.optJSONArray("episodes")?.objects().orEmpty()) {
            episodes.getOrPut(imdb) { mutableSetOf() } += Ep(s.optInt("number"), e.optInt("number"))
        }
        // Plan to watch is a list, not history.
        if (row.optString("status") == "plantowatch" || (imdb !in episodes && row.optString("status") != "completed")) continue
        val show = row.optJSONObject("show") ?: row.optJSONObject("anime") ?: continue
        seen += Seen(imdb, show.optString("title"), show.str("year"), "tv", row.optString("last_watched_at"), row.str("last_watched"))
    }
    return SimklLibrary(films, episodes, seen = seen.sortedByDescending { it.at })
}

/** /sync/playback: newest first, as Simkl sends it; IMDb titles only, since that is how Cinemeta finds them. */
fun parsePlayback(json: String): List<Paused> = runCatching { JSONArray(json).objects() }.getOrDefault(emptyList()).mapNotNull { p ->
    val imdb = p.imdbOf() ?: return@mapNotNull null
    val what = p.optJSONObject("movie") ?: p.optJSONObject("show") ?: p.optJSONObject("anime")!!
    val ep = p.optJSONObject("episode")?.let { Ep(it.optInt("season", 1), it.optInt("number")) }
    Paused(imdb, what.optString("title"), what.str("year"), if (p.optString("type") == "movie") null else ep, p.optDouble("progress", 0.0))
}

/**
 * What was left part-way here (one per title, its latest release) that Simkl has neither paused nor
 * as watched — what its Playback Progress Manager should also show. Newest first.
 */
fun pausedBacklog(items: List<Item>, simkl: SimklLibrary): List<Paused> = latestPerTitle(items).mapNotNull { item ->
    val imdb = item.imdb?.takeIf { Regex("^tt\\d+$").matches(it) } ?: return@mapNotNull null
    if (item.ratio <= 0.02 || item.ratio >= WATCHED_AT) return@mapNotNull null
    val ep = if (item.kind == "movie") null else item.files?.find { it.index == item.lastFileIndex }?.name?.let(::parseEpisode) ?: return@mapNotNull null
    val known = simkl.pausedAt(imdb, ep) != null || (ep == null && imdb in simkl.films) || (ep != null && ep in simkl.episodes[imdb].orEmpty())
    if (known) null else Paused(imdb, item.label.substringBefore(" · "), null, ep, item.ratio * 100)
}

/** The scrobble body: a film, or a show and its episode, at [progress] 0–100. */
fun scrobbleBody(imdb: String, ep: Ep?, progress: Double): String? {
    if (!Regex("^tt\\d+$").matches(imdb)) return null
    val ids = JSONObject().put("ids", JSONObject().put("imdb", imdb))
    val o = JSONObject().put("progress", Math.round(progress * 100) / 100.0)
    if (ep == null) o.put("movie", ids) else o.put("show", ids).put("episode", JSONObject().put("season", ep.season).put("number", ep.episode))
    return o.toString()
}

/**
 * Simkl, AUTH V2: what is watched to the end here goes onto the viewer's Simkl history.
 * A TV can't take a password, so it connects by device code — approved on a phone at
 * simkl.com/pin. Tokens are per profile, encrypted like the SubDL key; the guest's stay in memory.
 */
object Simkl {
    private const val API = "https://api.simkl.com"
    private const val DAY = 86_400_000L

    /** Set by the app from its build; empty leaves Simkl off. Public by design — not a secret. */
    var clientId = ""
    val available get() = clientId.isNotEmpty()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Pulled once per profile per launch, so a reinstall gets its watched marks and paused films back. */
    private val _library = MutableStateFlow(SimklLibrary())
    val library: StateFlow<SimklLibrary> = _library

    private var profile = ""
    private var guest = false
    private var tokens: SimklTokens? = null

    private fun key(id: String) = "simkl:$id"
    private fun now() = System.currentTimeMillis()
    private fun params() = "client_id=${enc(clientId)}&app-name=watchly&app-version=2"

    /** On every profile switch, like History.load. */
    fun load(id: String, isGuest: Boolean) {
        profile = id
        guest = isGuest
        tokens = if (isGuest) null else Secrets.get(key(id))?.let(SimklTokens::from)
        _connected.value = tokens != null
        _library.value = SimklLibrary()
        pull()
    }

    /**
     * The viewer's Simkl library and paused playbacks. Off the network, it stays as it was.
     * ponytail: the whole library each time; /sync/activities + date_from deltas if a large one makes this slow.
     */
    fun pull() {
        if (tokens == null) return
        val owner = profile
        AppScope.launch {
            runCatching {
                val auth = { t: String -> mapOf("Authorization" to "Bearer $t") }
                val seen = authed(owner) { Net.get("$API/sync/all-items?extended=full&include_all_episodes=yes&${params()}", 30_000, true, auth(it)) }
                val paused = authed(owner) { Net.get("$API/sync/playback?${params()}", 15_000, true, auth(it)) }
                if (owner != profile) return@runCatching
                var library = parseLibrary(seen).copy(paused = parsePlayback(paused))
                // Whatever this device watched that Simkl hasn't heard of — from before connecting, a restored backup, a
                // send that failed offline — goes up now. Every open, so it never needs remembering what was sent.
                val missing = library.missing(localWatched(History.items.value))
                historyBody(missing.films, missing.episodes)?.let { body ->
                    authed(owner) { Net.postJson("$API/sync/history?${params()}", body, headers = auth(it)) }
                    library = library.copy(films = library.films + missing.films, episodes = library.episodes.plusSets(missing.episodes))
                }
                _library.value = library
                sendPauses(owner)
            }.onFailure { Log.w("simkl", "pull: ${it.message}") }
        }
    }

    fun forget(id: String) = Secrets.put(key(id), null)

    /** Any profile's, not only the one watching — the profile editor connects whichever it has open. */
    fun isConnected(id: String) = if (id == profile) tokens != null else Secrets.get(key(id)) != null

    private fun tokensOf(id: String) = if (id == profile) tokens else Secrets.get(key(id))?.let(SimklTokens::from)

    /** Memory only for the guest watching; [owner]'s own entry otherwise, and live if it is the one watching. */
    private fun save(next: SimklTokens?, owner: String = profile) {
        if (!(guest && owner == profile)) Secrets.put(key(owner), next?.json())
        if (owner == profile) {
            tokens = next
            _connected.value = next != null
        }
    }

    suspend fun start(): DeviceCode =
        parseDevice(Net.postForm("$API/oauth2/device?${params()}", mapOf("client_id" to clientId, "scope" to "media:read media:write")))

    /**
     * Polls until approved (true), or the code runs out (false). A viewer who declines looks
     * exactly like one still deciding, so the code's lifetime is the only end; the caller can cancel.
     */
    suspend fun await(code: DeviceCode, owner: String = profile): Boolean {
        val deadline = now() + code.expiresIn * 1000L
        var wait = code.interval * 1000L
        while (now() < deadline) {
            delay(wait)
            val fields = mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to code.deviceCode)
            val poll = try {
                parsePoll(200, Net.postForm("$API/oauth2/token?${params()}", fields), now())
            } catch (e: HttpException) {
                parsePoll(e.code, e.body, now())
            } catch (e: IOException) {
                Poll.Pending
            }
            when (poll) {
                is Poll.Done -> {
                    save(poll.tokens, owner)
                    if (owner == profile) pull()
                    return true
                }
                // The rejected poll restarts Simkl's timer, so actually wait the longer interval.
                Poll.SlowDown -> wait += 5000
                Poll.Expired -> return false
                is Poll.Failed -> throw IOException(poll.why)
                Poll.Pending -> {}
            }
        }
        return false
    }

    fun disconnect(owner: String = profile) {
        val gone = tokensOf(owner) ?: return
        save(null, owner)
        if (owner == profile) _library.value = SimklLibrary()
        // Either half revokes the whole grant; a 200 proves nothing, so nothing waits on it.
        AppScope.launch { runCatching { Net.postForm("$API/oauth2/revoke?${params()}", mapOf("client_id" to clientId, "token" to gone.refresh)) } }
    }

    /** A film or an episode finished. Fire and forget: Simkl being down never troubles playback. */
    fun watched(imdb: String?, season: Int?, episode: Int?) {
        if (tokens == null) return
        val body = historyBody(imdb ?: return, season, episode) ?: return
        val owner = profile
        // Here at once, as Simkl will have it: watched, and no longer paused.
        val ep = if (season != null && episode != null) Ep(season, episode) else null
        _library.value = _library.value.let { l ->
            l.copy(
                films = if (ep == null) l.films + imdb else l.films,
                episodes = if (ep == null) l.episodes else l.episodes + (imdb to (l.episodes[imdb].orEmpty() + ep)),
                paused = l.paused.filterNot { it.imdb == imdb && it.ep == ep },
            )
        }
        AppScope.launch {
            runCatching {
                authed(owner) { Net.postJson("$API/sync/history?${params()}", body, headers = mapOf("Authorization" to "Bearer $it")) }
            }.onFailure { Log.w("simkl", "history: ${it.message}") }
        }
    }

    /**
     * Everything left part-way here that Simkl hasn't got, newest first. Simkl locks scrobbling for
     * 20 s per user, so they go one at a time, 21 s apart; leaving the profile stops the rest till next time.
     */
    private suspend fun sendPauses(owner: String) {
        for (p in pausedBacklog(History.items.value, _library.value)) {
            if (owner != profile) return
            val body = scrobbleBody(p.imdb, p.ep, p.progress) ?: continue
            val sent = runCatching { authed(owner) { Net.postJson("$API/scrobble/pause?${params()}", body, headers = mapOf("Authorization" to "Bearer $it")) } }
            if (sent.isSuccess && owner == profile) _library.value = _library.value.let { it.copy(paused = it.paused + p) }
            else Log.w("simkl", "pause ${p.imdb}: ${sent.exceptionOrNull()?.message}")
            delay(21_000)
        }
    }

    /** Left part-way: saved on Simkl as a paused playback, which any device can resume from. */
    fun pause(imdb: String?, ep: Ep?, progress: Double, title: String) {
        if (tokens == null) return
        val body = scrobbleBody(imdb ?: return, ep, progress) ?: return
        val owner = profile
        _library.value = _library.value.let { l -> l.copy(paused = listOf(Paused(imdb, title, null, ep, progress)) + l.paused.filterNot { it.imdb == imdb && it.ep == ep }) }
        AppScope.launch {
            runCatching {
                authed(owner) { Net.postJson("$API/scrobble/pause?${params()}", body, headers = mapOf("Authorization" to "Bearer $it")) }
            }.onFailure { Log.w("simkl", "pause: ${it.message}") }
        }
    }

    /** Refreshed a day early, or on a 401 once; a refresh Simkl refuses means the viewer must connect again. */
    private suspend fun <T> authed(owner: String, call: suspend (String) -> T): T {
        var t = tokens ?: throw IOException("Not connected")
        if (t.expiresAt - now() < DAY) t = refresh(t, owner)
        return try {
            call(t.access)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            call(refresh(t, owner).access)
        }
    }

    private suspend fun refresh(old: SimklTokens, owner: String): SimklTokens {
        val fields = mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to old.refresh)
        val fresh = try {
            parseTokens(Net.postForm("$API/oauth2/token?${params()}", fields), now())
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) save(null, owner)
            throw e
        }
        return fresh.copy(refresh = fresh.refresh.ifEmpty { old.refresh }).also { save(it, owner) }
    }
}
