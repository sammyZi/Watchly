package com.torrentviewer.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * A Nuvio sign-in: Supabase's tokens, [expires] in ms, and the Nuvio profile it watches as — [profile]
 * by its index, [addons] the profile whose addons it uses (1 for one that shares the first one's).
 */
data class NuvioSession(
    val access: String, val refresh: String, val expires: Long, val email: String?,
    val profile: Int = 1, val addons: Int = 1, val profileName: String? = null,
)

/** One of the account's Nuvio profiles (up to six). */
data class NuvioProfile(val index: Int, val name: String, val color: String?, val usesPrimaryAddons: Boolean)

fun parseNuvioSession(json: String, now: Long = System.currentTimeMillis()): NuvioSession? = runCatching {
    val o = JSONObject(json)
    NuvioSession(o.getString("access_token"), o.getString("refresh_token"), now + o.optLong("expires_in", 3600) * 1000, o.optJSONObject("user")?.str("email"))
}.getOrNull()

fun parseNuvioProfiles(json: String): List<NuvioProfile> = runCatching { JSONArray(json).objects() }.getOrDefault(emptyList())
    .map { NuvioProfile(it.optInt("profile_index", 1), it.str("name") ?: "Profile ${it.optInt("profile_index", 1)}", it.str("avatar_color_hex"), it.optBoolean("uses_primary_addons")) }
    .sortedBy { it.index }

/** What Nuvio's sign-in says went wrong, in its own words ("Invalid login credentials"). */
fun nuvioError(json: String): String? = runCatching {
    JSONObject(json).let { it.str("msg") ?: it.str("error_description") ?: it.str("message") }
}.getOrNull()

/**
 * Nuvio's watch progress, watched items and library as the app's own [StremioLibrary]. Only IMDb ids:
 * ponytail: a title Nuvio knows only by "tmdb:…" is left out; map it through TMDB if those turn up.
 */
fun parseNuvio(progress: String, watched: String, library: String): StremioLibrary {
    fun rows(json: String) = runCatching { JSONArray(json).objects() }.getOrDefault(emptyList()).filter { it.optString("content_id").startsWith("tt") }
    val lib = rows(library)
    val names = lib.associate { it.optString("content_id") to it.optString("name") }
    val films = mutableSetOf<String>()
    val episodes = mutableMapOf<String, MutableSet<Ep>>()
    val finished = mutableMapOf<String, Long>()
    for (w in rows(watched)) {
        val id = w.optString("content_id")
        val ep = if (w.isNull("season") || w.isNull("episode")) null else Ep(w.optInt("season"), w.optInt("episode"))
        if (w.optString("content_type") == "movie") films += id else ep?.let { episodes.getOrPut(id) { mutableSetOf() } += it }
        finished[id] = maxOf(finished[id] ?: 0L, w.optLong("watched_at"))
    }
    val paused = rows(progress).mapNotNull { p ->
        val id = p.optString("content_id")
        val of = p.optLong("duration").takeIf { it > 0 } ?: return@mapNotNull null
        val part = p.optLong("position").toDouble() / of
        if (part < 0.02 || part >= WATCHED_AT) return@mapNotNull null
        val series = p.optString("content_type") != "movie"
        val ep = if (p.isNull("season") || p.isNull("episode")) epOfVideo(p.str("video_id")) else Ep(p.optInt("season"), p.optInt("episode"))
        if (series && ep == null) return@mapNotNull null
        Paused(id, names[id].orEmpty(), null, if (series) ep else null, part * 100, p.optLong("position") / 1000.0, p.optLong("last_watched"))
    }.sortedByDescending { it.at }
    // Left part-way since it was last finished: not finished, for Continue Watching.
    paused.forEach { p -> if ((finished[p.imdb] ?: 0L) < p.at) finished.remove(p.imdb) }
    val seen = lib.map { Seen(it.optString("content_id"), it.optString("name"), it.str("release_info"), kindOfType(it.optString("content_type", "movie")), isoNow(it.optLong("added_at"))) }
    return StremioLibrary(films, episodes, paused, seen.sortedByDescending { it.at }, finished)
}

/** Calibration knob: a spot sent when it was left lands a moment after the play's own time, and clocks differ a little. */
private const val NUVIO_SLACK_MS = 30_000L

/**
 * What this device played that the Nuvio profile hasn't got — a finish sent as the app was closed, a
 * play offline or from before signing in: per title its latest play, past [WATCHED_AT] as watched, else
 * its spot, unless Nuvio has it as recent ([there] is what was just pulled). As the (watched, progress)
 * rows its push calls take. A play with no IMDb id, or an episode its file doesn't name, can't be placed.
 */
fun nuvioBacklog(items: List<Item>, there: StremioLibrary): Pair<List<JSONObject>, List<JSONObject>> {
    val watched = mutableListOf<JSONObject>()
    val progress = mutableListOf<JSONObject>()
    for (item in latestPerTitle(items)) {
        val id = item.imdb?.takeIf { it.startsWith("tt") } ?: continue
        val i = item.lastFileIndex ?: continue
        val at = item.positions[i] ?: continue
        val of = item.durations[i]?.takeIf { it > 0 } ?: continue
        val r = at / of
        if (r < 0.02) continue
        val film = item.kind == "movie"
        val ep = if (film) null else item.files?.find { it.index == i }?.name?.let(::parseEpisode) ?: continue
        val played = item.playedAt(i)
        val row = JSONObject().put("content_id", id).put("content_type", if (film) "movie" else "series")
        if (ep != null) row.put("season", ep.season).put("episode", ep.episode)
        if (r >= WATCHED_AT) {
            if (if (ep == null) id in there.films else there.episodes[id]?.contains(ep) == true) continue
            watched += row.put("title", item.label.substringBefore(" · ")).put("watched_at", played)
        } else {
            if (there.paused.any { it.imdb == id && it.ep == ep && it.at >= played - NUVIO_SLACK_MS }) continue
            // Finished on another device since: this one's old spot would put it back in Continue Watching.
            if (ep == null && (there.finishedAt[id] ?: 0L) >= played) continue
            progress += row.put("video_id", if (ep == null) id else "$id:${ep.season}:${ep.episode}")
                .put("position", (at * 1000).toLong()).put("duration", (of * 1000).toLong()).put("last_watched", played)
        }
    }
    return watched to progress
}

/** A watch-progress row's key, as Nuvio forms it: the id, and "_s1e2" for an episode. */
fun nuvioKey(imdb: String, ep: Ep?) = if (ep == null) imdb else "${imdb}_s${ep.season}e${ep.episode}"

/**
 * Every page of a paged pull as one array, [get] asked for page 0, 1, … until one comes back short of
 * [size]. Null when [get] is (signed out). [max] pages at most, so a server that never ends can't spin.
 */
suspend fun allPages(size: Int, max: Int = 40, get: suspend (page: Int) -> String?): String? {
    val all = JSONArray()
    for (i in 0 until max) {
        val page = JSONArray(get(i) ?: return null)
        for (j in 0 until page.length()) all.put(page.get(j))
        if (page.length() < size) break
    }
    return all.toString()
}

/**
 * The viewer's Nuvio account, by its public API (nuvio.tv/docs): signed in with email and password —
 * Nuvio has no link code — then one of its profiles is picked, its addons come across, where films were
 * left and what was finished come both ways, and My List is its library. Per Watchly profile, encrypted
 * like Stremio's; the guest's stays in memory.
 */
object Nuvio {
    private const val API = "https://api.nuvio.tv"
    /** Nuvio's publishable key, published in its API docs for public clients — not a secret. */
    private const val KEY = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN"
    /** Nuvio's own clients' page size for the library and history. */
    private const val PAGE = 500

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email

    /** The Nuvio profile this one watches as, for "Watching as". */
    private val _profileName = MutableStateFlow<String?>(null)
    val profileName: StateFlow<String?> = _profileName

    private val _library = MutableStateFlow(StremioLibrary())
    val library: StateFlow<StremioLibrary> = _library

    private var profile = ""
    val profileId get() = profile
    private var guest = false
    private var session: NuvioSession? = null

    private fun slot(id: String) = "nuvio:$id"

    private fun stored(id: String): NuvioSession? = Secrets.get(slot(id))?.let { s ->
        runCatching {
            JSONObject(s).let {
                NuvioSession(
                    it.getString("access"), it.getString("refresh"), it.getLong("expires"), it.str("email"),
                    it.optInt("profile", 1), it.optInt("addons", 1), it.str("profileName"),
                )
            }
        }.getOrNull()
    }

    private fun current(owner: String) = if (owner == profile) session else stored(owner)

    /** On every profile switch, like [Stremio.load]. */
    fun load(id: String, isGuest: Boolean) {
        profile = id
        guest = isGuest
        session = if (isGuest) null else stored(id)
        show(session)
        _library.value = StremioLibrary()
        pull()
    }

    private fun show(s: NuvioSession?) {
        _email.value = s?.email
        _profileName.value = s?.profileName
        _connected.value = s != null
    }

    private fun save(next: NuvioSession?, owner: String = profile) {
        if (!(guest && owner == profile)) Secrets.put(slot(owner), next?.let {
            JSONObject().put("access", it.access).put("refresh", it.refresh).put("expires", it.expires).put("email", it.email)
                .put("profile", it.profile).put("addons", it.addons).put("profileName", it.profileName).toString()
        })
        if (owner == profile) {
            session = next
            show(next)
            if (next == null) _library.value = StremioLibrary()
        }
    }

    fun forget(id: String) = Secrets.put(slot(id), null)

    fun isConnected(id: String) = current(id) != null

    /** Any Watchly profile's, for the profile editor; the one watching's is [profileName]. */
    fun profileNameOf(id: String) = current(id)?.profileName

    private fun headers(s: NuvioSession?) = mapOf("apikey" to KEY) + (s?.let { mapOf("Authorization" to "Bearer ${it.access}") } ?: emptyMap())

    /**
     * Email and password, straight to Nuvio. Throws with Nuvio's own words for a wrong password.
     * The account's profiles come back: with one, it is chosen here and the account is brought in;
     * with more, the caller asks which and calls [choose].
     */
    suspend fun signIn(email: String, password: String, owner: String = profile): List<NuvioProfile> {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val answer = try {
            Net.postJson("$API/auth/v1/token?grant_type=password", body, 20_000, quiet = true, headers = headers(null))
        } catch (e: HttpException) {
            throw IOException(nuvioError(e.body) ?: "Nuvio answered ${e.code}")
        }
        val s = parseNuvioSession(answer)?.let { it.copy(email = it.email ?: email.trim()) } ?: throw IOException("Nuvio sent no session")
        // Asked before anything is saved: saved, the screen turns to "signed in", and the form asking
        // which profile — with this call — went away before the answer came.
        val all = runCatching {
            parseNuvioProfiles(Net.postJson("$API/rest/v1/rpc/sync_pull_profiles", "{}", 20_000, quiet = true, headers = headers(s)))
        }.getOrDefault(emptyList())
        pending = owner to s
        if (all.size <= 1) choose(all.firstOrNull() ?: NuvioProfile(1, "Main", null, false), owner)
        return all
    }

    /** Signed in, waiting for [choose]: not saved, so the screen asking stays up. */
    private var pending: Pair<String, NuvioSession>? = null

    /** The account's profiles, to pick from. */
    suspend fun profiles(owner: String = profile): List<NuvioProfile> =
        parseNuvioProfiles(rpc("sync_pull_profiles", JSONObject(), owner, profiled = false) ?: throw IOException("Not signed in"))

    /**
     * Watch as [p]: its library and where it left films are pulled, and its addons come across — on the
     * app's own scope, so the screen that asked can go without cutting them short.
     */
    fun choose(p: NuvioProfile, owner: String = profile) {
        val s = pending?.takeIf { it.first == owner }?.second ?: current(owner) ?: return
        pending = null
        save(s.copy(profile = p.index, addons = if (p.usesPrimaryAddons) 1 else p.index, profileName = p.name), owner)
        if (owner != profile) return
        _library.value = StremioLibrary()
        pull()
        AppScope.launch { runCatching { importAddons() }.onFailure { Log.w("nuvio", "addons: ${it.message}") } }
    }

    fun disconnect(owner: String = profile) {
        val gone = current(owner)
        save(null, owner)
        gone ?: return
        AppScope.launch { runCatching { Net.postJson("$API/auth/v1/logout", "{}", quiet = true, headers = headers(gone)) } }
    }

    /** A refused refresh means the session was ended there: signed out here too. */
    private suspend fun refreshed(owner: String): NuvioSession? {
        val old = current(owner) ?: return null
        val next = try {
            parseNuvioSession(Net.postJson("$API/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", old.refresh).toString(), 20_000, quiet = true, headers = headers(null)))
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) save(null, owner)
            null
        } ?: return null
        return old.copy(access = next.access, refresh = next.refresh, expires = next.expires).also { save(it, owner) }
    }

    /** [call] with a live token, renewed first if nearly out or refused. Null once signed out. */
    private suspend fun <T> authed(owner: String = profile, call: suspend (Map<String, String>, NuvioSession) -> T): T? {
        val now = current(owner) ?: return null
        val s = (if (now.expires - System.currentTimeMillis() < 60_000) refreshed(owner) else now) ?: return null
        return try {
            call(headers(s), s)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            val again = refreshed(owner) ?: return null
            call(headers(again), again)
        }
    }

    /** One RPC; [profiled] ones are about the chosen Nuvio profile. */
    private suspend fun rpc(name: String, body: JSONObject, owner: String = profile, profiled: Boolean = true): String? = authed(owner) { h, s ->
        if (profiled) body.put("p_profile_id", s.profile)
        Net.postJson("$API/rest/v1/rpc/$name", body.toString(), 30_000, quiet = true, headers = h)
    }

    /**
     * Where films were left, what was finished and the whole library — and, up the other way, whatever
     * this device played that Nuvio hasn't got ([nuvioBacklog]). Every open and sign-in, so a finish lost
     * to the app being closed goes the next time. Off the network, it all stays as it was.
     */
    fun pull() {
        if (session == null) return
        val owner = profile
        AppScope.launch {
            runCatching {
                val progress = rpc("sync_pull_watch_progress", JSONObject().put("p_limit", 200), owner) ?: return@runCatching
                val watched = allPages(PAGE) { i -> rpc("sync_pull_watched_items", JSONObject().put("p_page", i + 1).put("p_page_size", PAGE), owner) } ?: return@runCatching
                val library = allPages(PAGE) { i -> rpc("sync_pull_library", JSONObject().put("p_limit", PAGE).put("p_offset", i * PAGE), owner) } ?: return@runCatching
                if (owner != profile) return@runCatching
                val there = parseNuvio(progress, watched, library)
                _library.value = there
                val (finished, left) = nuvioBacklog(History.items.value, there)
                // Nuvio's own clients send at most 500 to a call.
                finished.chunked(PAGE).forEach { rows ->
                    rpc("sync_push_watched_items", JSONObject().put("p_items", JSONArray(rows)), owner)
                    // Finished: no longer part-way there either.
                    val keys = rows.map { nuvioKey(it.getString("content_id"), if (it.has("season")) Ep(it.getInt("season"), it.getInt("episode")) else null) }
                    rpc("sync_delete_watch_progress", JSONObject().put("p_keys", JSONArray(keys)), owner)
                }
                left.chunked(PAGE).forEach { rpc("sync_push_watch_progress", JSONObject().put("p_entries", JSONArray(it)), owner) }
                if (finished.isNotEmpty() || left.isNotEmpty()) Log.i("nuvio", "sent from this device's history: ${finished.size} watched, ${left.size} part-way")
            }.onFailure { Log.w("nuvio", "pull: ${it.message}") }
        }
    }

    /** The chosen profile's addons, each fetched and added here; one here already is left be. How many came across. */
    suspend fun importAddons(): Int {
        val got = authed { h, s -> Net.get("$API/rest/v1/addons?select=url,enabled&profile_id=eq.${s.addons}&order=sort_order", 20_000, quiet = true, headers = h) }
            ?: throw IOException("Not signed in")
        var added = 0
        for (row in JSONArray(got).objects()) {
            val base = addonBase(row.optString("url")) ?: continue
            // Watchly has its own of Cinemeta, as with Stremio's.
            if ("cinemeta" in base || Addons.list.value.any { it.url == base }) continue
            if (runCatching { Addons.add(base) }.isSuccess) added++
        }
        return added
    }

    /** Fire and forget, so Nuvio being down never troubles playback. */
    private fun send(name: String, body: JSONObject) {
        if (session == null) return
        val owner = profile
        AppScope.launch { runCatching { rpc(name, body, owner) }.onFailure { Log.w("nuvio", "$name: ${it.message}") } }
    }

    private fun typeOf(kind: String?) = if (kind == "movie") "movie" else "series"

    /** Left part-way: Nuvio keeps the spot, for Continue Watching on any device. */
    fun pause(at: Item, ep: Ep?, seconds: Double, duration: Double) {
        val imdb = at.imdb ?: return
        if (session == null || duration <= 0) return
        val now = System.currentTimeMillis()
        _library.value = _library.value.let { l -> l.copy(paused = listOf(Paused(imdb, at.label.substringBefore(" · "), null, ep, seconds / duration * 100, seconds, now)) + l.paused.filterNot { it.imdb == imdb && it.ep == ep }) }
        val entry = JSONObject().put("content_id", imdb).put("content_type", typeOf(at.kind))
            .put("video_id", if (ep == null) imdb else "$imdb:${ep.season}:${ep.episode}")
            .put("position", (seconds * 1000).toLong()).put("duration", (duration * 1000).toLong()).put("last_watched", now)
        if (ep != null) entry.put("season", ep.season).put("episode", ep.episode)
        send("sync_push_watch_progress", JSONObject().put("p_entries", JSONArray().put(entry)))
    }

    /** A film or an episode finished: watched there, and no longer part-way. */
    fun watched(at: Item, season: Int?, episode: Int?) {
        val imdb = at.imdb ?: return
        if (session == null) return
        val ep = if (season != null && episode != null) Ep(season, episode) else null
        _library.value = _library.value.let { l ->
            l.copy(
                films = if (ep == null) l.films + imdb else l.films,
                episodes = if (ep == null) l.episodes else l.episodes + (imdb to (l.episodes[imdb].orEmpty() + ep)),
                paused = l.paused.filterNot { it.imdb == imdb && it.ep == ep },
            )
        }
        val item = JSONObject().put("content_id", imdb).put("content_type", typeOf(at.kind)).put("title", at.label.substringBefore(" · ")).put("watched_at", System.currentTimeMillis())
        if (ep != null) item.put("season", ep.season).put("episode", ep.episode)
        send("sync_push_watched_items", JSONObject().put("p_items", JSONArray().put(item)))
        send("sync_delete_watch_progress", JSONObject().put("p_keys", JSONArray().put(nuvioKey(imdb, ep))))
    }

    /** My List here is the library there: added or taken out together. */
    fun setInLibrary(title: Title, on: Boolean) {
        if (session == null) return
        val type = if (title.type == "movie") "movie" else "series"
        if (on) send(
            "sync_push_library_items",
            JSONObject().put("p_items", JSONArray().put(
                JSONObject().put("content_id", title.imdb).put("content_type", type).put("name", title.title)
                    .put("poster", title.poster ?: JSONObject.NULL).put("release_info", title.year ?: JSONObject.NULL).put("added_at", System.currentTimeMillis()),
            )),
        ) else send("sync_delete_library_items", JSONObject().put("p_keys", JSONArray().put(JSONObject().put("content_id", title.imdb).put("content_type", type))))
    }
}
