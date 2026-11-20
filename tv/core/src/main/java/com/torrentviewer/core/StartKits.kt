package com.torrentviewer.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Left unplayed this long, a film's kept start goes. Calibration knob. */
internal const val START_KIT_DAYS = 14L
/** All kept starts together stay under this; the longest unplayed go first. Calibration knob. */
internal const val START_KIT_CAP = 1024L * 1024 * 1024

/**
 * What a stopped film leaves on disk so it opens again at once: its header, its index at the end
 * and the pieces around where it was left — the rest of the file punched out. Without them a
 * second play waited on the swarm for all three again, 7-20s on the phone, before the first picture.
 *
 * One per torrent, by info-hash; [name] is its entry in the save folder (a file, or a pack's folder).
 */
internal class StartKits(private val index: File, private val saveDir: () -> File) {
    /**
     * [blocks]: pieces not yet whole, by the blocks of each already on disk — the header and the resume spot often are.
     * [file], [fileStart], [fileLen], [pieceLen]: where the film sits in the torrent, so [trim] can free the rest of it.
     */
    data class Kit(
        val hash: String, val name: String, val pieces: List<Int>, val bytes: Long, val savedAt: Long,
        val blocks: Map<Int, List<Int>> = emptyMap(),
        val file: String = "", val fileStart: Long = 0, val fileLen: Long = 0, val pieceLen: Long = 0,
    )

    private val kits = load()

    /**
     * A kit whose files are still there. Left on the list while the torrent plays — the play keeps it up
     * to date — so an app closed or killed mid-film still has it: taken off, the launch's sweep wiped it.
     */
    @Synchronized
    fun get(hash: String): Kit? = kits[hash]?.takeIf { File(saveDir(), it.name).exists() && (it.pieces.isNotEmpty() || it.blocks.isNotEmpty()) }

    /** Its pieces are no longer what the list says (the rolling cache punched the file): off the list, files left. */
    @Synchronized
    fun forget(hash: String) {
        if (kits.remove(hash) != null) save()
    }

    @Synchronized
    fun has(hash: String): Boolean = kits[hash]?.let { File(saveDir(), it.name).exists() } == true

    @Synchronized
    fun put(kit: Kit) {
        kits[kit.hash] = kit
        prune()
        save()
    }

    /** Entries in the save folder that belong to a kit: a sweep of leftovers must spare them. */
    @Synchronized
    fun names(): Set<String> = kits.values.mapTo(HashSet()) { it.name }

    /** Too old, or over the cap counting from the newest: their files and their entries go. */
    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        var total = 0L
        val gone = kits.values.sortedByDescending { it.savedAt }.filter { k ->
            total += k.bytes
            now - k.savedAt > START_KIT_DAYS * 24 * 3600 * 1000 || total > START_KIT_CAP || !File(saveDir(), k.name).exists()
        }
        if (gone.isEmpty()) return
        gone.forEach { k ->
            kits.remove(k.hash)
            // A name another kit still uses stays: two releases may share one.
            if (kits.values.none { it.name == k.name }) runCatching { File(saveDir(), k.name).deleteRecursively() }
        }
        save()
    }

    /**
     * Each film cut down to what its kit lists. A film stopped properly already is; one the app was closed or
     * killed over still holds everything it fetched — the read-ahead too, ~100MB a time on the phone — since
     * only a stop punches. Run at launch, before any of them is in the session.
     */
    @Synchronized
    fun trim() {
        kits.values.filter { it.file.isNotEmpty() && it.pieceLen > 0 && it.fileLen > 0 }.forEach { k ->
            val file = File(saveDir(), k.file)
            if (!file.exists()) return@forEach
            // What it takes on disk before and after: the ranges cut are mostly holes already, after a proper stop.
            val before = runCatching { java.nio.file.Files.getAttribute(file.toPath(), "unix:blocks") as Long }.getOrNull()
            keptOnly(k.fileStart, k.fileLen, k.pieceLen, (k.pieces + k.blocks.keys).toSet()).forEach { (from, to) -> Holes.punch(file, from, to - from) }
            val after = runCatching { java.nio.file.Files.getAttribute(file.toPath(), "unix:blocks") as Long }.getOrNull()
            if (before != null && after != null && before > after) android.util.Log.i("TorrentStream", "start: trimmed ${(before - after) * 512 / 1024 / 1024}MB from ${k.file}")
        }
    }

    /** Space is short: every kept start goes. */
    @Synchronized
    fun clear() {
        kits.values.forEach { k -> runCatching { File(saveDir(), k.name).deleteRecursively() } }
        kits.clear()
        save()
    }

    private fun load(): MutableMap<String, Kit> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.associateTo(LinkedHashMap()) { o ->
            val p = o.getJSONArray("pieces")
            val b = o.optJSONObject("blocks")
            val blocks = b?.keys()?.asSequence()?.associate { k -> b.getJSONArray(k).let { arr -> k.toInt() to (0 until arr.length()).map { arr.getInt(it) } } }.orEmpty()
            o.getString("hash") to Kit(
                o.getString("hash"), o.getString("name"), (0 until p.length()).map { p.getInt(it) }, o.getLong("bytes"), o.getLong("savedAt"), blocks,
                o.optString("file"), o.optLong("fileStart"), o.optLong("fileLen"), o.optLong("pieceLen"),
            )
        }
    }.getOrDefault(LinkedHashMap())

    private fun save() {
        runCatching {
            val a = JSONArray()
            kits.values.forEach { k ->
                val blocks = JSONObject().apply { k.blocks.forEach { (piece, done) -> put(piece.toString(), JSONArray(done)) } }
                a.put(
                    JSONObject().put("hash", k.hash).put("name", k.name).put("pieces", JSONArray(k.pieces)).put("bytes", k.bytes).put("savedAt", k.savedAt).put("blocks", blocks)
                        .put("file", k.file).put("fileStart", k.fileStart).put("fileLen", k.fileLen).put("pieceLen", k.pieceLen),
                )
            }
            index.writeText(a.toString())
        }
    }
}

/**
 * The byte ranges of a file, [fileStart] into its torrent, outside the pieces [keep]: what [StartKits.trim]
 * frees. A piece shared with the file before or after it is cut only where it lies in this file.
 */
internal fun keptOnly(fileStart: Long, fileLen: Long, pieceLen: Long, keep: Set<Int>): List<Pair<Long, Long>> {
    val first = (fileStart / pieceLen).toInt()
    val last = ((fileStart + fileLen - 1) / pieceLen).toInt()
    val out = mutableListOf<Pair<Long, Long>>()
    var runFrom = -1
    for (p in first..last + 1) {
        val cut = p <= last && p !in keep
        if (cut && runFrom < 0) runFrom = p
        if (!cut && runFrom >= 0) {
            val from = maxOf(0L, runFrom * pieceLen - fileStart)
            val to = minOf(fileLen, p * pieceLen - fileStart)
            if (to > from) out += from to to
            runFrom = -1
        }
    }
    return out
}
