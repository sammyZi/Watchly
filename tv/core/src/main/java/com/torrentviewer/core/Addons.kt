package com.torrentviewer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One resource an addon serves, for these types and ids starting so; null prefixes is any id. */
data class AddonResource(val name: String, val types: List<String>, val idPrefixes: List<String>?)

/** A list an addon offers. [required] needs an extra (a genre, a search) before it lists anything; [search] takes a query. */
data class AddonCatalog(val type: String, val id: String, val name: String, val required: Boolean, val search: Boolean)

/** Watchly's own kind for a Stremio type: "movie" plays the id itself — films, and live "tv" channels — the rest hold videos. */
fun kindOfType(type: String) = if (type == "movie" || type == "tv") "movie" else "tv"

/** The home row's key for one of an addon's catalogues: a hash, so the link (and any key in it) stays out of storage keys. */
const val ADDON_SHELF = "addon:"

/**
 * A Stremio-protocol addon, added by its manifest link. Watchly ships none: the streams and
 * subtitles it plays come from these — public addons, a debrid service's, or the viewer's own
 * media server. [url] can hold the viewer's own config (a debrid key), so the UI shows [host] only.
 */
data class Addon(
    val url: String,
    val name: String,
    val resources: List<AddonResource>,
    val on: Boolean,
    val manifest: String,
    val catalogs: List<AddonCatalog> = emptyList(),
) {
    val host: String get() = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

    fun serves(resource: String, type: String, id: String) = on && resources.any { r ->
        r.name == resource && type in r.types && (r.idPrefixes == null || r.idPrefixes.any { id.startsWith(it) })
    }

    /** The types it answers /meta for this id with, [first] tried first. */
    fun metaTypes(id: String, first: String) = if (!on) emptyList() else resources
        .filter { r -> r.name == "meta" && (r.idPrefixes == null || r.idPrefixes.any { id.startsWith(it) }) }
        .flatMap { it.types }.distinct().sortedBy { if (it == first) 0 else 1 }

    fun shelfKey(c: AddonCatalog) = ADDON_SHELF + "$url|${c.type}|${c.id}".hashCode().toUInt().toString(36)

    /** /{resource}/{type}/{id}.json — the protocol's one shape of request. */
    suspend fun get(resource: String, type: String, id: String): JSONObject =
        JSONObject(Net.get("$url/$resource/$type/${enc(id)}.json", quiet = true))

    /** What it offers, for its row in Settings. */
    val offers: String get() = (resources.map { it.name } + if (catalogs.isNotEmpty()) listOf("catalog") else emptyList())
        .distinct().joinToString(" · ") { if (it == "stream") "streams" else it }
}

/** "movie" → "Films": a home row's second half. */
fun typeLabel(type: String) = when (type) {
    "movie" -> "Films"
    "series" -> "Series"
    "tv" -> "Live TV"
    "channel" -> "Channels"
    else -> type.replaceFirstChar { it.uppercase() }
}

/** "stremio://host/x/manifest.json", "https://host/x/" or "host/x" → "https://host/x"; null for any other scheme. */
fun addonBase(raw: String): String? {
    var s = raw.trim()
    if (s.startsWith("stremio://", ignoreCase = true)) s = "https://" + s.substring("stremio://".length)
    if (!s.contains("://")) s = "https://$s"
    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) return null
    s = s.substringBefore('#').trimEnd('/').removeSuffix("/manifest.json").trimEnd('/')
    return s.takeIf { runCatching { java.net.URI(it).host }.getOrNull().orEmpty().isNotEmpty() }
}

/** Which site an addon link is for, to ask before adding it: not the path, which can carry the viewer's keys. */
fun addonHost(raw: String): String? = addonBase(raw)?.let { runCatching { java.net.URI(it).host }.getOrNull() }

/**
 * The TV page a `watchly://tv-addons?to=…` link names, from the TV's QR. Only a page on the home
 * network, in the TV's own shape, is ever sent the addons — their links can carry keys, and any
 * web page could hand the phone such a link.
 */
fun tvDropUrl(link: String): String? {
    val to = runCatching { java.net.URI(link).rawQuery }.getOrNull()?.split('&')
        ?.firstOrNull { it.startsWith("to=") }?.substringAfter('=')
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: return null
    return to.takeIf { TV_DROP.matches(it) }
}

private val TV_DROP = Regex("""^http://(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}:\d{1,5}/[0-9a-f]{24}$""")

/** A manifest as an addon; null when it isn't one (no name, or nothing it serves). */
fun toAddon(url: String, m: JSONObject, on: Boolean = true): Addon? {
    val name = m.str("name") ?: return null
    val list = m.optJSONArray("resources") ?: return null
    val types = m.strings("types")
    val prefixes = if (m.has("idPrefixes")) m.strings("idPrefixes") else null
    val resources = (0 until list.length()).mapNotNull { i ->
        when (val r = list.opt(i)) {
            is String -> AddonResource(r, types, prefixes)
            is JSONObject -> r.str("name")?.let {
                AddonResource(it, if (r.has("types")) r.strings("types") else types, if (r.has("idPrefixes")) r.strings("idPrefixes") else prefixes)
            }
            else -> null
        }
    }
    val catalogs = m.optJSONArray("catalogs")?.objects().orEmpty().mapNotNull { c ->
        val type = c.str("type") ?: return@mapNotNull null
        val id = c.str("id") ?: return@mapNotNull null
        // Two shapes: `extra` objects, and the older `extraRequired` / `extraSupported` name lists.
        val extra = c.optJSONArray("extra")?.objects().orEmpty()
        val required = extra.any { it.optBoolean("isRequired") } || c.strings("extraRequired").isNotEmpty()
        val search = extra.any { it.optString("name") == "search" } || "search" in c.strings("extraSupported")
        AddonCatalog(type, id, c.str("name") ?: id, required, search)
    }
    return if (resources.isEmpty() && catalogs.isEmpty()) null else Addon(url, name, resources, on, m.toString(), catalogs)
}

/**
 * The viewer's addons, in their order. Kept inside the settings JSON, so a backup carries them
 * from the phone to the TV; [SettingsStore.save] writes over the stored object and leaves them be.
 */
object Addons {
    private const val SETTINGS = "settings"
    private const val KEY = "addons"
    private val _list = MutableStateFlow<List<Addon>>(emptyList())
    val list: StateFlow<List<Addon>> = _list

    fun load() {
        val a = runCatching { JSONObject(Kv.global(SETTINGS) ?: "{}").optJSONArray(KEY) }.getOrNull() ?: JSONArray()
        _list.value = a.objects().mapNotNull { o -> runCatching { toAddon(o.getString("url"), JSONObject(o.getString("manifest")), o.optBoolean("on", true)) }.getOrNull() }
    }

    @Synchronized
    private fun save(next: List<Addon>) {
        _list.value = next
        val o = runCatching { JSONObject(Kv.global(SETTINGS) ?: "{}") }.getOrDefault(JSONObject())
        o.put(KEY, JSONArray(next.map { JSONObject().put("url", it.url).put("on", it.on).put("manifest", it.manifest) }))
        Kv.setGlobal(SETTINGS, o.toString())
        // Added, removed or moved here: the signed-in Stremio account follows, so other devices get the same list.
        Stremio.addonsChanged()
    }

    /** Fetches the manifest and keeps the addon; one already here is refreshed in place. Throws with a message to show. */
    suspend fun add(raw: String): Addon {
        val base = addonBase(raw) ?: throw IllegalArgumentException("That isn't an addon link")
        val host = runCatching { java.net.URI(base).host }.getOrNull()
        // quiet, and our own messages: the link can carry a key, and a failure's own message names the link.
        val body = try {
            Net.get("$base/manifest.json", 15_000, quiet = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller went away: that is no answer from the addon.
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException(
                when (e) {
                    is HttpException -> if (e.code == 404) "No addon at that link — check it" else "$host answered ${e.code}"
                    is java.net.UnknownHostException -> "Couldn't find $host — check the link and your connection"
                    is java.net.SocketTimeoutException -> "$host took too long to answer"
                    else -> "Couldn't reach $host"
                },
            )
        }
        val addon = runCatching { toAddon(base, JSONObject(body)) }.getOrNull() ?: throw IllegalArgumentException("Not an addon manifest")
        val now = _list.value
        save(if (now.any { it.url == base }) now.map { if (it.url == base) addon.copy(on = it.on) else it } else now + addon)
        return addon
    }

    /** The list as links only, for another device to fetch afresh: [importLinks] reads it. */
    fun linksJson(): String = JSONArray(_list.value.map { JSONObject().put("url", it.url).put("on", it.on) }).toString()

    /**
     * Another device's [linksJson]: each addon is fetched and added (one here already is refreshed),
     * switched off if it was off there. One at a time, as [add] reads and writes the whole list.
     * What came across, and how many of how many, for the sender to be told.
     */
    suspend fun importLinks(json: String): String {
        val links = JSONArray(json).objects().mapNotNull { o -> o.str("url")?.let { it to o.optBoolean("on", true) } }
        var copied = 0
        for ((url, on) in links) {
            val added = runCatching { add(url) }.getOrNull() ?: continue
            if (!on && _list.value.find { it.url == added.url }?.on == true) toggle(added.url)
            copied++
        }
        return if (copied == links.size) "Copied $copied addon${if (copied == 1) "" else "s"} to the TV." else "Copied $copied of ${links.size} addons — the rest didn't answer."
    }

    /** Addons already read elsewhere (a Stremio account's), added after these; one here already is left be. How many were new. */
    fun merge(more: List<Addon>): Int {
        val now = _list.value
        val fresh = more.filter { a -> now.none { it.url == a.url } }.distinctBy { it.url }
        if (fresh.isNotEmpty()) save(now + fresh)
        return fresh.size
    }

    fun toggle(url: String) = save(_list.value.map { if (it.url == url) it.copy(on = !it.on) else it })

    /** Gone here — and from the Stremio account too, as every change is ([save]), so an import won't bring it back. */
    fun remove(url: String) = save(_list.value.filterNot { it.url == url })

    /** One place up (−1) or down (+1); order is the order streams are asked for and listed in. */
    fun move(url: String, step: Int) {
        val l = _list.value.toMutableList()
        val at = l.indexOfFirst { it.url == url }
        val to = at + step
        if (at < 0 || to !in l.indices) return
        l.add(to, l.removeAt(at))
        save(l)
    }

    /**
     * Would [link] — a stream an addon handed over, a debrid one with the viewer's key in it — still
     * come from an addon that is added and on? By [manifest] when it was kept; else (entries from before
     * it was) an addon on the link's host whose own link holds every key-like part of [link]'s path.
     * Torrentio with TorBox swapped for plain Torrentio is the same host without that key: no.
     */
    fun stillServes(link: String, manifest: String?): Boolean {
        val on = _list.value.filter { it.on }
        if (manifest != null) return on.any { it.url == manifest }
        val uri = runCatching { java.net.URI(link) }.getOrNull() ?: return true
        val host = uri.host ?: return true
        val keys = uri.rawPath.orEmpty().split('/', '=', '|', ',', ';').filter { part ->
            part.length >= 20 && part.any(Char::isDigit) && part.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
                !Regex("^[0-9a-fA-F]{40}$").matches(part)
        }
        return on.any { a -> runCatching { java.net.URI(a.url).host }.getOrNull() == host && keys.all { a.url.contains(it) } }
    }

    fun serving(resource: String, type: String, id: String) = _list.value.filter { it.serves(resource, type, id) }

    /** Every list that shows without a genre or search picked first, as (row title, kind, shelf key). */
    fun shelves(): List<Triple<String, String, String>> = _list.value.filter { it.on }.flatMap { a ->
        a.catalogs.filter { !it.required }.map { c -> Triple("${c.name} · ${typeLabel(c.type)}", kindOfType(c.type), a.shelfKey(c)) }
    }

    /** The addon and catalogue behind a home row's key. */
    fun shelf(key: String): Pair<Addon, AddonCatalog>? =
        _list.value.firstNotNullOfOrNull { a -> a.catalogs.find { a.shelfKey(it) == key }?.let { a to it } }

    /** Catalogues that take a search query. */
    fun searchable(): List<Pair<Addon, AddonCatalog>> = _list.value.filter { it.on }.flatMap { a -> a.catalogs.filter { it.search }.map { a to it } }

    /** Any addon that can find streams at all: without one, there is nothing to play but links. */
    val hasStreams get() = _list.value.any { a -> a.on && a.resources.any { it.name == "stream" } }

    /** What to say while [hasStreams] is false. */
    const val NONE_YET = "No add-ons found yet. Search Google for popular Stremio add-ons to discover available streaming sources."
}
