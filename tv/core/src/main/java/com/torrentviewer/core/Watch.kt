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

/**
 * A TorBox stream whose picture hasn't moved in this long goes to Watchly's own engine. No error
 * comes for it: a video the device can't decode left VLC silent, the film never starting. 45 s, not
 * 20: VLC reads an MKV's index at the end, then buffers seconds of a large remux over HTTPS first,
 * which outlasted 20 s on the TV while peers (whose start and end are fetched before the timer) played.
 * Calibration knob: shorter gives up on slow-starting streams, longer leaves a dead one on screen.
 */
const val TORBOX_STALL_MS = 45_000L

/** What the viewer is told then: the swarm may stall the same way if the video is the trouble. */
const val TORBOX_STALL_NOTE = "TorBox didn't start — trying peers. If this won't play either, this release's video may not suit this device: try another."

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
    /** "TorBox" while the playing file streams from there; null is the swarm (or a file on disk). */
    var via by mutableStateOf<String?>(null)
        private set
    /** Asking TorBox for a link, before the swarm is tried — for the loading screen. */
    var askingTorBox by mutableStateOf(false)
        private set

    val notes = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var source: String? = null
    /** Bumped by every open and exit, so a file list that lands after backing out is dropped. */
    private var gen = 0
    /**
     * [meta]'s files are numbered as TorBox listed them. libtorrent can number them otherwise
     * (it counts padding files), so the swarm is never handed one of these indices — see [swarmIndex].
     */
    private var torboxIndexed = false

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
        // A release TorBox has cached lists its files at once; the swarm can take a minute.
        TorBox.meta(target.uri)?.let { found ->
            if (ticket != gen) return null
            torboxIndexed = true
            meta = found
            return found
        }
        return try {
            val found = withContext(Dispatchers.IO) { engine.inspect(source!!, 60) }
            if (ticket != gen) return null
            torboxIndexed = false
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
                torboxFiles = torboxIndexed,
            )
        }
        item = History.find(uri) ?: item
    }

    /**
     * The swarm's own index for the file TorBox numbered [index], by size and name. From then
     * on this release goes by the swarm's numbering: the file list and every saved spot move over.
     */
    private suspend fun swarmIndex(uri: String, index: Int): Int {
        val want = meta?.files?.find { it.index == index } ?: return index
        val real = withContext(Dispatchers.IO) { engine.inspect(uri, 60) }
        fun twin(f: TorrentFile) = real.files.filter { it.size == f.size }.let { same -> same.find { it.name == f.name } ?: same.singleOrNull() }
        val moved = meta?.files.orEmpty().mapNotNull { f -> twin(f)?.let { f.index to it.index } }.toMap()
        val at = moved[index] ?: throw TorrentException("Couldn't find ${want.name} in the torrent.")
        val key = item?.uri
        if (key != null) History.patch(key) {
            fun <T> Map<Int, T>.renumbered() = entries.mapNotNull { (k, v) -> moved[k]?.let { it to v } }.toMap()
            it.copy(
                files = real.files.map { f -> StoredFile(f.index, f.name, f.size, f.isVideo, f.isSubtitle, f.path) },
                torrentName = real.name,
                lastFileIndex = at,
                positions = it.positions.renumbered(),
                durations = it.durations.renumbered(),
                byteAts = it.byteAts.renumbered(),
                subtitles = it.subtitles.renumbered(),
                torboxFiles = false,
            )
        }
        torboxIndexed = false
        meta = real
        playingIndex = at
        item = key?.let(History::find) ?: item
        return at
    }

    /** A chosen release becomes a stream. `tag` (S01E02) picks the file inside a pack. */
    fun stream(hit: Hit, from: Title?, tag: String? = null, episode: Episode? = null) = AppScope.launch {
        title = from
        val fresh = History.put(
            Item(
                uri = hit.magnet,
                title = hit.name,
                at = System.currentTimeMillis(),
                // Played straight from search: the index's own IMDb tag, so Simkl can still place it.
                imdb = from?.imdb ?: hit.imdb,
                kind = from?.kind ?: hit.imdb?.let { hit.kind },
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
        val (at, duration) = carryOver(History.items.value, now, name) ?: return fromSimkl(now, index, name)
        History.setPosition(uri, index, at)
        duration?.let { History.setDuration(uri, index, it) }
        return (History.find(uri) ?: now).also { item = it }
    }

    /**
     * Nothing here has played this title — a reinstall, another device — but Simkl kept where it
     * was paused. Its percent of the runtime Cinemeta gives: close, not to the second.
     */
    private fun fromSimkl(now: Item, index: Int, name: String): Item {
        val imdb = now.imdb ?: return now
        val ep = if (now.kind == "movie") null else (episodeOfFile(title?.episodes.orEmpty(), name)?.let { Ep(it.season, it.episode) } ?: parseEpisode(name) ?: return now)
        val percent = Simkl.library.value.pausedAt(imdb, ep) ?: return now
        val runtime = runtimeSeconds(title?.runtime).takeIf { it > 0 } ?: return now
        History.setPosition(now.uri, index, runtime * percent / 100)
        return (History.find(now.uri) ?: now).also { item = it }
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
        via = null
        torboxIndexed = false
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
            torboxIndexed = target.torboxFiles
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

    /**
     * TorBox's link failed mid-film: the same file from Watchly's own engine, from where it got to.
     * False when it wasn't playing from TorBox — then the failure is the player's to report.
     */
    fun leaveTorBox(seconds: Double, note: String = "TorBox stopped — streaming from peers instead."): Boolean {
        if (via == null || phase != Phase.PLAYING) return false
        val current = item ?: return false
        if (seconds > 0) savePosition(seconds)
        notes.tryEmit(note)
        val started = History.find(current.uri) ?: current
        val size = meta?.files?.find { it.index == playingIndex }?.size ?: 0
        play(playingIndex, started.resumeRatio(playingIndex), bitrateOf(size, started.durations[playingIndex] ?: 0.0), torbox = false)
        return true
    }

    /** [torbox] false goes straight to the swarm: TorBox already failed this file. */
    private fun play(index: Int, startRatio: Double, bitrate: Double, torbox: Boolean = true) {
        val uri = source ?: return
        // Cleared here, not on the engine thread: the player mounts this frame and must
        // not open the previous file's URL while engine.play() is still starting.
        engine.ready.value = null
        engine.progress.value = null
        playingIndex = index
        playId++
        phase = Phase.PLAYING
        via = null
        val ticket = playId
        val s = SettingsStore.value.value
        val file = meta?.files?.find { it.index == index }
        AppScope.launch {
            try {
                if (torbox && file != null && TorBox.on && magnetHash(uri) != null) {
                    askingTorBox = true
                    val link = try {
                        TorBox.link(uri, file.path, file.size)
                    } catch (e: Exception) {
                        if (ticket == playId) notes.tryEmit("TorBox: ${e.message?.trimEnd('.') ?: "no link"} — streaming from peers instead.")
                        null
                    } finally {
                        if (ticket == playId) askingTorBox = false
                    }
                    if (ticket != playId || phase != Phase.PLAYING) return@launch
                    if (link != null) {
                        // Whatever the swarm was streaming before (the last episode) goes.
                        withContext(Dispatchers.IO) { engine.stop() }
                        if (ticket != playId || phase != Phase.PLAYING) return@launch
                        via = "TorBox"
                        engine.ready.value = Ready(link, file.name, emptyList(), lanUrl = link)
                        return@launch
                    }
                }
                val at = if (torboxIndexed) swarmIndex(uri, index) else index
                if (ticket != playId || phase != Phase.PLAYING) return@launch
                withContext(Dispatchers.IO) { engine.play(uri, at, PlayOptions(s.prepareMb, startRatio, bitrate, s.cacheCapMb)) }
            } catch (e: Exception) {
                if (ticket == playId) fail(e.message ?: "Couldn't start that stream.")
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

    fun savePosition(seconds: Double) {
        val at = item ?: return
        History.setPosition(at.uri, playingIndex, seconds, if (via != null) -1.0 else engine.readRatio())
        markWatched(at, seconds)
    }

    /** The episode the playing file is, by Cinemeta's list or its name; null for a film or a file that can't say. */
    private fun playingEp(at: Item): Ep? {
        if (at.kind == "movie") return null
        val name = playingFile?.name ?: return null
        return episodeOf(playingFile)?.let { Ep(it.season, it.episode) } ?: parseEpisode(name)
    }

    /** Closed part-way: Simkl keeps the spot, for a reinstall or another device. Finished goes as watched instead. */
    private fun pauseOnSimkl() {
        val at = item ?: return
        if (marked == playId) return
        val now = History.find(at.uri) ?: return
        val of = now.durations[playingIndex]?.takeIf { it > 0 } ?: return
        val r = (now.positions[playingIndex] ?: return) / of
        if (r < 0.02 || r >= WATCHED_AT) return
        val ep = playingEp(at)
        if (at.kind != "movie" && ep == null) return
        Simkl.pause(at.imdb, ep, r * 100, at.label.substringBefore(" · "))
    }

    /** The play that has gone to Simkl, so a film is marked once however often its position is saved. */
    private var marked = -1

    /** [WATCHED_AT] in is watched: the credits are not worth waiting for. */
    private fun markWatched(at: Item, seconds: Double) {
        if (marked == playId) return
        val of = History.find(at.uri)?.durations?.get(playingIndex) ?: return
        if (of <= 0 || seconds / of < WATCHED_AT) return
        marked = playId
        if (at.kind == "movie") return Simkl.watched(at.imdb, null, null)
        val ep = playingEp(at) ?: return
        Simkl.watched(at.imdb, ep.season, ep.episode)
    }

    fun saveDuration(seconds: Double) = item?.let { History.setDuration(it.uri, playingIndex, seconds) }

    fun saveSubs(subs: List<SavedSub>) = item?.let { History.setSubs(it.uri, playingIndex, subs) }

    fun savedSubs(): List<SavedSub> = item?.let { History.find(it.uri)?.subtitles?.get(playingIndex) }.orEmpty()

    fun startAt(): Double = item?.let { History.find(it.uri)?.positions?.get(playingIndex) } ?: 0.0

    fun exit() {
        pauseOnSimkl()
        gen++
        phase = Phase.IDLE
        item = null
        meta = null
        source = null
        via = null
        askingTorBox = false
        AppScope.launch(Dispatchers.IO) { engine.stop() }
    }

    /** Back out of "Opening…" before the file list arrives. */
    fun cancel() {
        if (phase == Phase.INSPECTING) exit()
    }
}
