package com.torrentviewer.core

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { IDLE, INSPECTING, PLAYING }

/** "136 min" → 8160 s: the only duration a never-played torrent has, and bitrate needs one. */
fun runtimeSeconds(runtime: String?): Double =
    Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(runtime.orEmpty())?.groupValues?.get(1)?.toDouble()?.times(60) ?: 0.0

/** A path to a video on disk — a finished download — rather than a magnet, URL or .torrent. */
fun isLocalVideo(uri: String) =
    (uri.startsWith("/") || uri.startsWith("file://")) && uri.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv", "mpg", "mpeg")

private fun bitrateOf(bytes: Long, seconds: Double) = if (bytes > 0 && seconds > 0) bytes / seconds else 0.0

/**
 * One stream at a time: resolve a torrent's files, pick the one meant, play it.
 * The player mounts as soon as `play` starts; the engine announces `ready` later.
 */
class Watch(val engine: TorrentStream) {
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var item by mutableStateOf<Item?>(null)
        private set
    var meta by mutableStateOf<TorrentMeta?>(null)
        private set
    var playingIndex by mutableIntStateOf(0)
        private set
    /** What is playing, for the player's title, logo, synopsis and episode stills. */
    var title by mutableStateOf<Title?>(null)
        private set
    /** Bumped on every play, so the player resets per file. */
    var playId by mutableIntStateOf(0)
        private set
    /** A torrent whose names cannot say which file was asked for. */
    var choose by mutableStateOf<Pair<Item, TorrentMeta>?>(null)

    val notes = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var source: String? = null
    /** Bumped by every open and exit, so a file list that lands after backing out is dropped. */
    private var gen = 0

    fun fail(message: String) {
        Log.w("watch", message)
        notes.tryEmit(message)
        phase = Phase.IDLE
    }

    private fun uriOf(raw: String) = if (SettingsStore.value.value.addTrackers) withTrackers(raw) else raw

    private suspend fun inspect(target: Item): TorrentMeta? {
        val ticket = ++gen
        source = uriOf(target.uri)
        item = target
        meta = null
        phase = Phase.INSPECTING
        return try {
            val found = withContext(Dispatchers.IO) { engine.inspect(source!!, 60) }
            if (ticket != gen) return null
            meta = found
            found
        } catch (e: Exception) {
            if (ticket != gen) return null
            fail(e.message ?: "Couldn't open that torrent.")
            item = null
            null
        }
    }

    private fun remember(uri: String, found: TorrentMeta, index: Int) {
        History.patch(uri) {
            it.copy(
                torrentName = found.name,
                lastFileIndex = index,
                files = found.files.map { f -> StoredFile(f.index, f.name, f.size, f.isVideo, f.isSubtitle, f.path) },
            )
        }
        item = History.find(uri) ?: item
    }

    /** A chosen release becomes a stream. `tag` (S01E02) picks the file inside a pack. */
    fun stream(hit: Hit, from: Title?, tag: String? = null, episode: Episode? = null) = AppScope.launch {
        title = from
        val fresh = History.put(
            Item(
                uri = hit.magnet,
                title = hit.name,
                at = System.currentTimeMillis(),
                imdb = from?.imdb,
                kind = from?.kind,
                poster = from?.poster,
                backdrop = from?.backdrop,
                titleName = if (episode != null && from != null) "${from.title} · ${episodeTag(episode.season, episode.episode)}" else from?.title,
                seeders = hit.seeders,
            ),
        )
        val found = inspect(fresh) ?: return@launch
        if (found.files.none { it.isVideo }) return@launch fail("This torrent holds no video.")
        // A pack of anime names its files by the number through the show: let that pick too.
        val pick = if (episode != null && from != null) pickTag(episode.season, episode.episode, absoluteNumber(from.episodes, episode.season, episode.episode)) else tag
        val index = pickFile(found.files, pick)
        if (index == null) {
            phase = Phase.IDLE
            choose = fresh to found
            return@launch
        }
        remember(fresh.uri, found, index)
        val started = carried(fresh.uri, found, index)
        val size = found.files.find { it.index == index }?.size ?: 0
        play(index, started.resumeRatio(index), bitrateOf(size, started.durations[index] ?: runtimeSeconds(from?.runtime)))
    }

    /** A release never played here takes the spot another release of the title was left at. */
    private fun carried(uri: String, found: TorrentMeta, index: Int): Item {
        val now = History.find(uri) ?: return item!!
        if ((now.positions[index] ?: 0.0) > 0.0) return now
        val name = found.files.find { it.index == index }?.name ?: return now
        val (at, duration) = carryOver(History.items.value, now, name) ?: return now
        History.setPosition(uri, index, at)
        duration?.let { History.setDuration(uri, index, it) }
        return (History.find(uri) ?: now).also { item = it }
    }

    /**
     * A finished download: a file on disk and no torrent. It goes into History like any
     * other, so it resumes from Continue Watching too; the player is handed its path as
     * though the engine had just announced it.
     */
    fun playFile(path: String, label: String, from: Title? = null, poster: String? = null) = AppScope.launch {
        val file = java.io.File(path.removePrefix("file://"))
        if (!file.exists()) return@launch fail("That file is gone — it may have been deleted or the card removed.")
        gen++
        source = null
        val stored = StoredFile(0, file.name, file.length(), true, false, file.path)
        val target = History.put(
            Item(
                uri = file.path, title = label, at = System.currentTimeMillis(), torrentName = label, files = listOf(stored),
                lastFileIndex = 0, imdb = from?.imdb, kind = from?.kind, poster = poster ?: from?.poster, backdrop = from?.backdrop, titleName = label,
            ),
        )
        title = from
        item = target
        // The full entry — logo, episodes — whatever the caller had to hand.
        target.imdb?.let { id -> launch { Meta.title(id, target.kind ?: "movie")?.let { if (item?.uri == target.uri) title = it } } }
        meta = TorrentMeta(label, listOf(TorrentFile(0, file.path, file.name, file.length(), true, false)))
        playingIndex = 0
        engine.progress.value = null
        engine.ready.value = Ready(android.net.Uri.fromFile(file).toString(), file.name, emptyList())
        playId++
        phase = Phase.PLAYING
    }

    /** Continue watching: the stored file list goes straight to the engine. */
    fun resume(picked: Item) = AppScope.launch {
        if (isLocalVideo(picked.uri)) {
            playFile(picked.uri, picked.label, poster = picked.poster)
            return@launch
        }
        // Played again: to the front of Continue watching.
        val target = History.put(picked.copy(at = System.currentTimeMillis()))
        // Art and synopsis for the player, without holding up the stream.
        title = null
        item = target
        target.imdb?.let { id -> launch { Meta.title(id, target.kind ?: "movie")?.let { if (item?.uri == target.uri) title = it } } }
        val known = target.lastFileIndex
        if (known != null && !target.files.isNullOrEmpty() && target.torrentName != null) {
            ++gen
            source = uriOf(target.uri)
            item = target
            meta = TorrentMeta(target.torrentName, target.files.map { TorrentFile(it.index, it.path ?: it.name, it.name, it.size, it.isVideo, it.isSubtitle) })
            val size = target.files.find { it.index == known }?.size ?: 0
            play(known, target.resumeRatio(known), bitrateOf(size, target.durations[known] ?: 0.0))
            return@launch
        }
        val found = inspect(target) ?: return@launch
        val index = known ?: pickFile(found.files)
        if (index == null) {
            phase = Phase.IDLE
            choose = target to found
            return@launch
        }
        remember(target.uri, found, index)
        val size = found.files.find { it.index == index }?.size ?: 0
        play(index, target.resumeRatio(index), bitrateOf(size, target.durations[index] ?: 0.0))
    }

    /** From the "which file?" sheet. */
    fun pick(target: Item, found: TorrentMeta, index: Int) {
        choose = null
        source = uriOf(target.uri)
        item = target
        meta = found
        remember(target.uri, found, index)
        val started = carried(target.uri, found, index)
        val size = found.files.find { it.index == index }?.size ?: 0
        play(index, started.resumeRatio(index), bitrateOf(size, started.durations[index] ?: 0.0))
    }

    private fun play(index: Int, startRatio: Double, bitrate: Double) {
        val uri = source ?: return
        // Cleared here, not on the engine thread: the player mounts this frame and must
        // not open the previous file's URL while engine.play() is still starting.
        engine.ready.value = null
        engine.progress.value = null
        playingIndex = index
        playId++
        phase = Phase.PLAYING
        val s = SettingsStore.value.value
        AppScope.launch {
            try {
                withContext(Dispatchers.IO) { engine.play(uri, index, PlayOptions(s.prepareMb, startRatio, bitrate, s.cacheCapMb)) }
            } catch (e: Exception) {
                fail(e.message ?: "Couldn't start that stream.")
            }
        }
    }

    val next: TorrentFile? get() = meta?.files?.let { nextEpisode(it, playingIndex) }
    val prev: TorrentFile? get() = meta?.files?.let { prevEpisode(it, playingIndex) }

    /** Same torrent, another file: no inspect, and it starts from the top. */
    fun playSibling(to: TorrentFile?) {
        val current = item ?: return
        to ?: return
        History.setPosition(current.uri, to.index, 0.0)
        item = History.find(current.uri) ?: current
        play(to.index, 0.0, bitrateOf(to.size, current.durations[to.index] ?: 0.0))
    }

    /** The Cinemeta episode a file in this pack is, for its name and still. */
    fun episodeOf(file: TorrentFile?): Episode? {
        val name = file?.name ?: return null
        return episodeOfFile(title?.episodes ?: return null, name)
    }

    val playingFile: TorrentFile? get() = meta?.files?.find { it.index == playingIndex }

    fun savePosition(seconds: Double) = item?.let { History.setPosition(it.uri, playingIndex, seconds, engine.readRatio()) }

    fun saveDuration(seconds: Double) = item?.let { History.setDuration(it.uri, playingIndex, seconds) }

    fun saveSubs(subs: List<SavedSub>) = item?.let { History.setSubs(it.uri, playingIndex, subs) }

    fun savedSubs(): List<SavedSub> = item?.let { History.find(it.uri)?.subtitles?.get(playingIndex) }.orEmpty()

    fun startAt(): Double = item?.let { History.find(it.uri)?.positions?.get(playingIndex) } ?: 0.0

    fun exit() {
        gen++
        phase = Phase.IDLE
        item = null
        meta = null
        source = null
        AppScope.launch(Dispatchers.IO) { engine.stop() }
    }

    /** Back out of "Opening…" before the file list arrives. */
    fun cancel() {
        if (phase == Phase.INSPECTING) exit()
    }
}
