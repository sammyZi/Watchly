package com.torrentviewer.core

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A file in a torrent TorBox holds; [name] is its path inside the torrent, folder and all. [id] is TorBox's, or -1 before it was added. */
data class TorBoxFile(val id: Int, val name: String, val size: Long) {
    val shortName get() = name.replace('\\', '/').substringAfterLast('/')
}

/** One torrent in the account, from /torrents/mylist; [ready] once its files can be fetched. */
data class TorBoxTorrent(val id: Int, val hash: String, val name: String, val ready: Boolean, val files: List<TorBoxFile>)

/** TorBox's own words when it says no — `{"success": false, "detail": "…"}` — else null. */
fun torboxError(json: String): String? = runCatching {
    JSONObject(json).takeIf { !it.optBoolean("success", true) }?.let { it.str("detail") ?: it.str("error") ?: "TorBox said no." }
}.getOrNull()

/**
 * The hashes TorBox has cached, lower case, from /torrents/checkcached. `format=list` gives an
 * array of torrents, `format=object` an object keyed by hash; either is read.
 */
fun parseCached(json: String): Map<String, List<TorBoxFile>> {
    val data = runCatching { JSONObject(json).opt("data") }.getOrNull() ?: return emptyMap()
    fun files(o: JSONObject) = o.optJSONArray("files")?.objects()?.map { TorBoxFile(-1, it.optString("name"), it.optLong("size")) }.orEmpty()
    return when (data) {
        is JSONArray -> data.objects().mapNotNull { o -> o.str("hash")?.lowercase()?.let { it to files(o) } }.toMap()
        is JSONObject -> data.keys().asSequence().mapNotNull { k -> data.optJSONObject(k)?.let { o -> (o.str("hash") ?: k).lowercase() to files(o) } }.toMap()
        else -> emptyMap()
    }
}

fun parseTorBoxTorrent(o: JSONObject) = TorBoxTorrent(
    id = o.optInt("id", -1),
    hash = o.optString("hash").lowercase(),
    name = o.optString("name"),
    // A cached torrent is present the moment it is added; one still downloading is not.
    ready = o.optBoolean("download_present") || o.optBoolean("download_finished"),
    files = o.optJSONArray("files")?.objects()?.map { TorBoxFile(it.optInt("id", -1), it.str("name") ?: it.optString("short_name"), it.optLong("size")) }.orEmpty(),
)

/** /torrents/mylist answers one torrent as an object when asked by id, the whole list as an array otherwise. */
fun parseMyList(json: String): List<TorBoxTorrent> = when (val data = runCatching { JSONObject(json).opt("data") }.getOrNull()) {
    is JSONObject -> listOf(parseTorBoxTorrent(data))
    is JSONArray -> data.objects().map(::parseTorBoxTorrent)
    else -> emptyList()
}

/**
 * Which of [files] is the one meant by [path] and [size]: same size and name first, then the only
 * file of that size, then the name alone. TorBox and libtorrent can number a torrent's files
 * differently (libtorrent counts padding files), so the index is never used.
 */
fun matchFile(files: List<TorBoxFile>, path: String, size: Long): TorBoxFile? {
    val name = path.replace('\\', '/').substringAfterLast('/')
    val sameSize = files.filter { it.size == size }
    return sameSize.find { it.shortName == name } ?: sameSize.singleOrNull() ?: files.find { it.shortName == name }
}

/** TorBox's plan numbers, as /user/me gives them. */
fun torboxPlan(plan: Int) = when (plan) {
    0 -> "Free"
    1 -> "Essential"
    2 -> "Pro"
    3 -> "Standard"
    else -> null
}

/** The 40-character hex info hash of a magnet, lower case; null for anything else (a .torrent, a path). */
fun magnetHash(uri: String): String? =
    Regex("btih:([0-9a-f]{40})(?![0-9a-f])", RegexOption.IGNORE_CASE).find(uri)?.groupValues?.get(1)?.lowercase()

/**
 * TorBox (torbox.app), a debrid service: a torrent it already has cached streams from its servers
 * over plain HTTPS — no peers, no buffering on a thin swarm, and the player can seek anywhere.
 * With the viewer's API key saved, [Watch] asks here first and falls back to the swarm when the
 * release isn't cached or TorBox fails. The key is kept like the SubDL key: encrypted, per device.
 */
object TorBox {
    private const val BASE = "https://api.torbox.app/v1/api"
    private const val TAG = "torbox"

    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key

    /** Info hashes known to be cached, for the badge on a release. Forgotten with the key. */
    private val _cached = MutableStateFlow<Set<String>>(emptySet())
    val cached: StateFlow<Set<String>> = _cached

    /** Asked and not cached, so a list scrolled past again is not asked twice. */
    private val missing = HashSet<String>()
    private val pending = LinkedHashSet<String>()
    private var flushing = false

    /** Hash → the torrent's id in the account, once added: siblings in a pack don't add it again. */
    private val ids = HashMap<String, Int>()

    val on: Boolean get() = _key.value.isNotEmpty()

    /** After [Secrets.init]. */
    fun load() {
        _key.value = Secrets.get("torbox").orEmpty()
    }

    /** Blank forgets it. */
    fun setKey(value: String) {
        val k = value.trim()
        Secrets.put("torbox", k.ifEmpty { null })
        _key.value = k
        synchronized(this) {
            missing.clear()
            ids.clear()
        }
        _cached.value = emptySet()
    }

    private fun auth() = mapOf("Authorization" to "Bearer ${_key.value}")

    private suspend fun get(path: String, timeoutMs: Int = 12_000): String = said { Net.get("$BASE$path", timeoutMs, quiet = true, headers = auth()) }

    /** TorBox puts its reason in the body, 200 or not. */
    private suspend fun said(call: suspend () -> String): String {
        val json = try {
            call()
        } catch (e: HttpException) {
            throw TorrentException(torboxError(e.body) ?: if (e.code == 401 || e.code == 403) "TorBox didn't accept the API key." else "TorBox answered ${e.code}.")
        }
        torboxError(json)?.let { throw TorrentException(it) }
        return json
    }

    /** "you@example.com · Pro" — and proof the key works. Throws with TorBox's reason when it doesn't. */
    suspend fun account(): String {
        val me = JSONObject(get("/user/me")).optJSONObject("data") ?: throw TorrentException("TorBox's answer made no sense.")
        return listOfNotNull(me.str("email"), torboxPlan(me.optInt("plan", -1))).joinToString("  ·  ").ifEmpty { "Connected" }
    }

    /**
     * Mark [hash] for the cached badge. Asks in batches — a list of sources is one request,
     * not forty — and never throws: a release with no badge just plays from the swarm.
     */
    fun check(hash: String) {
        val h = hash.lowercase()
        if (!on || !Regex("^[0-9a-f]{40}$").matches(h)) return
        synchronized(this) {
            if (h in _cached.value || h in missing || !pending.add(h) || flushing) return
            flushing = true
        }
        AppScope.launch {
            delay(250)
            while (true) {
                val batch = synchronized(this@TorBox) {
                    pending.take(100).also { pending.removeAll(it.toSet()) }.ifEmpty { flushing = false; return@launch }
                }
                val found = runCatching { parseCached(get("/torrents/checkcached?hash=${batch.joinToString(",")}&format=list&list_files=false")) }
                    .onFailure { Log.w(TAG, "checkcached: ${it.message}") }.getOrNull()
                    // Unreachable: leave them unasked, so the next list tries again.
                    ?: return@launch synchronized(this@TorBox) { flushing = false }
                synchronized(this@TorBox) { missing += batch.filter { it !in found } }
                if (found.isNotEmpty()) _cached.value = _cached.value + found.keys
            }
        }
    }

    /**
     * A cached torrent's files, without waiting on the swarm for its metadata. Null when it isn't
     * cached, or TorBox can't be reached: the caller asks the swarm then. Indices are TorBox's order.
     */
    suspend fun meta(uri: String): TorrentMeta? {
        if (!on) return null
        val hash = magnetHash(uri) ?: return null
        val files = runCatching { parseCached(get("/torrents/checkcached?hash=$hash&format=list&list_files=true", 8_000))[hash] }
            .onFailure { Log.w(TAG, "meta: ${it.message}") }.getOrNull()
        if (files.isNullOrEmpty()) return null
        _cached.value = _cached.value + hash
        val name = files.first().name.replace('\\', '/').substringBefore('/').takeIf { files.all { f -> f.name.contains('/') } } ?: titleOf(uri)
        return TorrentMeta(name, files.mapIndexed { i, f ->
            val ext = f.shortName.substringAfterLast('.', "").lowercase()
            TorrentFile(i, f.name, f.shortName, f.size, ext in VIDEO, ext in SUBTITLE)
        })
    }

    /**
     * An HTTPS link to one file of the torrent [uri] — [path] and [size] say which — or null when
     * TorBox doesn't have it cached. Adds the torrent to the account if it isn't there yet (only
     * when cached: a stream must not wait on TorBox downloading it). Throws when TorBox fails.
     */
    suspend fun link(uri: String, path: String, size: Long): String? {
        if (!on) return null
        val hash = magnetHash(uri) ?: return null
        if (hash !in _cached.value) {
            val cached = parseCached(get("/torrents/checkcached?hash=$hash&format=list&list_files=false", 8_000))
            if (hash !in cached) {
                synchronized(this) { missing += hash }
                return null
            }
            _cached.value = _cached.value + hash
        }
        val torrent = added(uri, hash)
        val file = matchFile(torrent.files, path, size) ?: throw TorrentException("TorBox has this torrent but not ${path.substringAfterLast('/')}.")
        val link = JSONObject(get("/torrents/requestdl?token=${enc(_key.value)}&torrent_id=${torrent.id}&file_id=${file.id}&zip_link=false")).str("data")
        return link?.takeIf { it.startsWith("http") } ?: throw TorrentException("TorBox gave no link for that file.")
    }

    /** The torrent in the account, files listed and ready — added now if it isn't there. */
    private suspend fun added(uri: String, hash: String): TorBoxTorrent {
        synchronized(this) { ids[hash] }?.let { id -> mine(id)?.takeIf { it.ready && it.files.isNotEmpty() }?.let { return it } }
        val id = runCatching {
            val made = said {
                Net.postMultipart(
                    "$BASE/torrents/createtorrent",
                    // seed 3: don't seed from the account; no zip, so each file has its own link.
                    mapOf("magnet" to uri, "seed" to "3", "allow_zip" to "false", "add_only_if_cached" to "true"),
                    quiet = true, headers = auth(),
                )
            }
            JSONObject(made).optJSONObject("data")?.optInt("torrent_id", -1)?.takeIf { it >= 0 }
        }.getOrElse { e ->
            // Already in the account is a refusal on some plans; find it there instead.
            Log.w(TAG, "createtorrent: ${e.message}")
            null
        } ?: parseMyList(get("/torrents/mylist?bypass_cache=true")).find { it.hash == hash }?.id
        ?: throw TorrentException("TorBox wouldn't add this torrent.")
        synchronized(this) { ids[hash] = id }
        // Cached is present at once, but the file list can lag the add by a moment.
        repeat(15) {
            mine(id)?.takeIf { it.ready && it.files.isNotEmpty() }?.let { return it }
            delay(1000)
        }
        throw TorrentException("TorBox is still preparing this torrent. Try again in a minute.")
    }

    private suspend fun mine(id: Int): TorBoxTorrent? =
        runCatching { parseMyList(get("/torrents/mylist?id=$id&bypass_cache=true")).firstOrNull() }.getOrNull()
}
