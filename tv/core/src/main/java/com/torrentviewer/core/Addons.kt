package com.torrentviewer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One resource an addon serves, for these types and ids starting so; null prefixes is any id. */
data class AddonResource(val name: String, val types: List<String>, val idPrefixes: List<String>?)

/**
 * A Stremio-protocol addon, added by its manifest link. Watchly ships none: the streams and
 * subtitles it plays come from these — public addons, a debrid service's, or the viewer's own
 * media server. [url] can hold the viewer's own config (a debrid key), so the UI shows [host] only.
 */
data class Addon(val url: String, val name: String, val resources: List<AddonResource>, val on: Boolean, val manifest: String) {
    val host: String get() = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

    fun serves(resource: String, type: String, id: String) = on && resources.any { r ->
        r.name == resource && type in r.types && (r.idPrefixes == null || r.idPrefixes.any { id.startsWith(it) })
    }

    /** /{resource}/{type}/{id}.json — the protocol's one shape of request. */
    suspend fun get(resource: String, type: String, id: String): JSONObject =
        JSONObject(Net.get("$url/$resource/$type/${enc(id)}.json", quiet = true))

    /** What it offers, for its row in Settings. */
    val offers: String get() = resources.map { it.name }.distinct().joinToString(" · ") { if (it == "stream") "streams" else it }
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
    return if (resources.isEmpty()) null else Addon(url, name, resources, on, m.toString())
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
    }

    /** Fetches the manifest and keeps the addon; one already here is refreshed in place. Throws with a message to show. */
    suspend fun add(raw: String): Addon {
        val base = addonBase(raw) ?: throw IllegalArgumentException("That isn't an addon link")
        val host = runCatching { java.net.URI(base).host }.getOrNull()
        // quiet, and our own messages: the link can carry a key, and a failure's own message names the link.
        val body = try {
            Net.get("$base/manifest.json", 15_000, quiet = true)
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't reach $host")
        }
        val addon = runCatching { toAddon(base, JSONObject(body)) }.getOrNull() ?: throw IllegalArgumentException("Not an addon manifest")
        val now = _list.value
        save(if (now.any { it.url == base }) now.map { if (it.url == base) addon.copy(on = it.on) else it } else now + addon)
        return addon
    }

    fun toggle(url: String) = save(_list.value.map { if (it.url == url) it.copy(on = !it.on) else it })

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

    fun serving(resource: String, type: String, id: String) = _list.value.filter { it.serves(resource, type, id) }

    /** Any addon that can find streams at all: without one, there is nothing to play but links. */
    val hasStreams get() = _list.value.any { a -> a.on && a.resources.any { it.name == "stream" } }
}
