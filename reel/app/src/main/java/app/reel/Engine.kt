package app.reel

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File

private const val BOOTSTRAP =
    "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401"

/** Attached to every torrent — many magnets carry dead trackers or none. */
private val TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://explodie.org:6969/announce",
)

private val PLAYABLE = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "mp3", "flac", "m4a", "ogg", "opus", "wav")

/** The one libtorrent session. Settings follow the phone app's TorrentStreamModule, where each was measured. */
object Engine {
    private val session = SessionManager()
    private lateinit var files: File
    private lateinit var downloads: File

    fun start(context: Context) {
        if (session.isRunning) return
        files = context.filesDir
        downloads = File(context.cacheDir, "stream")
        // Nothing is kept between launches; whatever a crash or a kill left behind goes now.
        clearCache()
        val settings = SettingsPack()
            .connectionsLimit(200)
            .activeDhtLimit(88)
            .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            // Dial everything at once instead of 30 peers a second.
            .setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), 200)
            .setInteger(settings_pack.int_types.connection_speed.swigValue(), 200)
            .setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 10)
            // Slow peers mustn't book a whole piece the player is about to block on.
            .setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 10)
            // Its own port: the phone (6882), TV (6881) and Windows app (51413) may share the router.
            .setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:6883,[::]:6883")
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), BOOTSTRAP)
            // A torrent sits with every file at "don't download" between its file list arriving
            // and Play. libtorrent counts that as finished and drops seeds as "redundant" — so
            // Play found nobody left to download from (0 peers, not even a connection attempt).
            .setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        val state = File(files, "session.state")
        val params = runCatching { if (state.exists()) SessionParams(state).also { it.setSettings(settings) } else null }.getOrNull()
            ?: SessionParams(settings)
        session.start(params)
        session.startDht()
    }

    /** Warm DHT next launch — a cold one takes a while to find anyone. */
    fun saveState() {
        if (!session.isRunning) return
        runCatching { session.saveState()?.let { File(files, "session.state").writeBytes(it) } }
    }

    /** Copies a picked .torrent into the app so it can be reopened from Recent. */
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read that file.")
        val info = runCatching { TorrentInfo(bytes) }.getOrElse { error("That isn't a .torrent file.") }
        val file = File(files, "torrents/${info.infoHash().toHex()}.torrent")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        file.path
    }

    /** A magnet link, or the path of a .torrent already in the app. */
    suspend fun open(source: String): Torrent = withContext(Dispatchers.IO) {
        // A folder of its own each time: libtorrent deletes a removed torrent's files later, on
        // its disk thread, and in a shared folder that delete landed on the reopened download —
        // pieces marked done read back as zeros (blocky, smeared video).
        val dir = File(downloads, System.currentTimeMillis().toString())
        val hash = if (source.startsWith("magnet:", true)) {
            val hash = runCatching { AddTorrentParams.parseMagnetUri(source).infoHashes.best }
                .getOrElse { error("That magnet link doesn't look right.") }
            settle(hash)
            if (session.find(hash)?.isValid != true) session.download(source, dir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            hash
        } else {
            val info = TorrentInfo(File(source))
            val hash = info.infoHash()
            settle(hash)
            if (session.find(hash)?.isValid != true) {
                session.download(info, dir, null, Array(info.numFiles()) { Priority.IGNORE }, null, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            }
            hash
        }
        // Added asynchronously.
        var handle: TorrentHandle? = null
        for (attempt in 0 until 100) {
            handle = session.find(hash)?.takeIf { it.isValid }
            if (handle != null) break
            delay(50)
        }
        val h = handle ?: error("Couldn't start that torrent.")
        TRACKERS.forEach { runCatching { h.addTracker(AnnounceEntry(it)) } }
        h.resume()
        // Its own save path, not `dir`: a handle still running from before keeps its folder.
        Torrent(hash.toHex(), h, File(h.savePath()))
    }

    /** Stops and deletes what was fetched — this app streams, it doesn't keep. */
    fun close(torrent: Torrent) {
        closing.add(torrent.hash)
        runCatching { session.remove(torrent.handle, SessionHandle.DELETE_FILES) }
    }

    /** Torrents removed but maybe not gone yet: libtorrent removes (and deletes) asynchronously. */
    private val closing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Reopening a title just left found the dying handle, which then vanished under the
     * player (0 peers, nothing served). Wait for the removal to land before adding it again.
     */
    private suspend fun settle(hash: org.libtorrent4j.Sha1Hash) {
        if (!closing.remove(hash.toHex())) return
        for (attempt in 0 until 60) {
            if (session.find(hash)?.isValid != true) return
            delay(50)
        }
    }

    fun dhtNodes(): Long = runCatching { session.dhtNodes() }.getOrDefault(0)

    /** Leftovers on disk — normally empty, since leaving a torrent deletes what it fetched. */
    fun cacheBytes(): Long = downloads.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clearCache() {
        downloads.listFiles()?.forEach { it.deleteRecursively() }
    }
}

data class Swarm(val rate: Int = 0, val peers: Int = 0, val seeds: Int = 0)

data class TorrentFile(val index: Int, val path: String, val name: String, val size: Long) {
    val playable get() = name.substringAfterLast('.', "").lowercase() in PLAYABLE
}

class Torrent(val hash: String, val handle: TorrentHandle, private val saveDir: File) {
    private var info: TorrentInfo? = null

    /** Null until peers have sent the file list (magnets). */
    fun info(): TorrentInfo? = info ?: runCatching { handle.torrentFile() }.getOrNull()?.also { info = it }

    fun swarm(): Swarm = runCatching {
        val s = handle.status()
        Swarm(s.downloadRate(), s.numPeers(), s.numSeeds())
    }.getOrDefault(Swarm())

    fun files(): List<TorrentFile> {
        val fs = info()?.files() ?: return emptyList()
        return (0 until fs.numFiles()).map { TorrentFile(it, fs.filePath(it), fs.fileName(it), fs.fileSize(it)) }
    }

    fun stream(fileIndex: Int) = Stream(this, fileIndex, saveDir)
}

data class StreamState(
    /** Set once enough of the start is on disk for VLC to open it. */
    val url: String? = null,
    /** 0–100: how much of what's needed ahead of the reader has arrived. */
    val buffer: Int = 0,
    /** 0–1 of the whole file. */
    val progress: Float = 0f,
    val swarm: Swarm = Swarm(),
    /** Downloaded stretches of the file, as 0–1 fractions, for the seek bar. */
    val spans: List<Pair<Float, Float>> = emptyList(),
    /** The torrent's own subtitle files that have finished downloading. */
    val subtitles: List<File> = emptyList(),
)

private val SUBTITLE = setOf("srt", "ass", "ssa", "vtt", "sub")

/**
 * One file being played. Not sequential download — libtorrent's own streaming docs advise
 * against it. Pieces ahead of the reader get staggered deadlines (libtorrent's time-critical
 * path); the rest of the file still downloads in the background.
 */
class Stream(private val torrent: Torrent, val fileIndex: Int, private val saveDir: File) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = _state
    private var server: FileServer? = null

    private val info = torrent.info()!!
    private val pieceLen = info.pieceLength().toLong()
    private val offset = info.files().fileOffset(fileIndex)
    private val size = info.files().fileSize(fileIndex)
    private val file = File(saveDir, info.files().filePath(fileIndex))
    private fun pieceAt(pos: Long) = ((offset + pos.coerceIn(0, size - 1)) / pieceLen).toInt()
    private val first = pieceAt(0)
    private val last = pieceAt(size - 1)

    private val h = torrent.handle

    // ponytail: fixed 32 MB look-ahead; scale it with bitrate if 4K remuxes stall.
    private val window = maxOf(4, (32L * 1024 * 1024 / pieceLen).toInt())

    /**
     * Pieces in hand before VLC is given the URL: about 1 MB, at least one piece. The server
     * blocks VLC on anything missing anyway; waiting for 4 MB here only delayed the first frame
     * (Sintel at 3 MB/s sat at "Buffering 44%" for half a minute).
     */
    private val need = maxOf(1, ((1024L * 1024 + pieceLen - 1) / pieceLen).toInt()).coerceAtMost(last - first + 1)

    private val deadlined = HashSet<Int>()
    private var aimedAt = -1

    /** The whole film, or only the next five minutes past the playhead (Watchly's default). */
    @Volatile var wholeFile = false

    /** From VLC once it knows; the five minutes are worked out in bytes from it. */
    @Volatile var durationMs = 0L

    /** Pieces currently allowed in "next 5 min" mode; null when the whole file is wanted. */
    private var zone: IntRange? = null

    private fun filePriorities() = Array(info.numFiles()) {
        when (it) {
            fileIndex -> Priority.DEFAULT
            in subtitleFiles -> Priority.TOP_PRIORITY
            else -> Priority.IGNORE
        }
    }

    /**
     * Next 5 min: everything in the file outside the playhead's window is set to not download.
     * Re-aimed when the playhead leaves the front half of the window (or seeks out of it), not
     * every tick — a full reprioritise costs more than it buys.
     */
    private fun applyZone(reader: Int) {
        if (wholeFile || durationMs <= 0) {
            if (zone != null) { h.prioritizeFiles(filePriorities()); zone = null }
            return
        }
        val bytesPerMs = size.toDouble() / durationMs
        val ahead = maxOf(window, (5 * 60_000 * bytesPerMs / pieceLen).toInt() + 1)
        val current = zone
        if (current != null && reader >= current.first && reader + ahead / 2 <= current.last) return
        val wanted = maxOf(first, reader - 2)..minOf(last, reader + ahead)
        val pieces = h.piecePriorities()
        for (p in first..last) {
            // The tail stays wanted: MKV cues and MP4 moov live there.
            pieces[p] = if (p in wanted || p >= last - 1) Priority.DEFAULT else Priority.IGNORE
        }
        h.prioritizePieces(pieces)
        zone = wanted
    }

    /**
     * Subtitle files shipped in the torrent. A few KB each, so they're fetched first at top
     * priority and handed to VLC as they land — as Watchly does.
     */
    private val subtitleFiles = (0 until info.numFiles()).filter { i ->
        info.files().fileName(i).substringAfterLast('.', "").lowercase() in SUBTITLE && info.files().fileSize(i) < 10_000_000
    }

    /** Every piece of file [i] is on disk. */
    private fun complete(i: Int): Boolean {
        val fs = info.files()
        if (fs.fileSize(i) == 0L) return false
        val from = (fs.fileOffset(i) / pieceLen).toInt()
        val to = ((fs.fileOffset(i) + fs.fileSize(i) - 1) / pieceLen).toInt()
        return (from..to).all { h.havePiece(it) }
    }

    fun start() {
        h.prioritizeFiles(filePriorities())
        h.resume()
        // Ask for peers now, not at the next scheduled announce (up to half an hour away):
        // any that left while nothing was wanted are found again.
        runCatching {
            h.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL)
            h.forceDHTAnnounce()
        }
        scope.launch {
            runCatching { aimTail() }
            // Leaving Files removes the torrent; a tick on a removed handle throws.
            while (isActive && h.isValid) {
                runCatching { tick() }
                delay(400)
            }
        }
    }

    /** MKV cues and MP4 moov often sit at the end, and VLC reads them before it plays. */
    private fun aimTail() {
        for (p in maxOf(first, last - 1)..last) if (!h.havePiece(p) && deadlined.add(p)) h.setPieceDeadline(p, 1500)
    }

    /** Re-aims deadlines at the reader, and opens the server once the start is in. */
    private fun tick() {
        val reader = pieceAt(server?.readerAt ?: 0)
        if (aimedAt >= 0 && (reader < aimedAt || reader > aimedAt + window)) {
            // A seek: the old urgency is for pieces nobody will read soon.
            h.clearPieceDeadlines()
            deadlined.clear()
            aimTail()
        }
        aimedAt = reader
        applyZone(reader)
        // Deadlines are absolute once set, so each is set once and nearer pieces stay ahead.
        for (p in reader..minOf(last, reader + window)) {
            if (!h.havePiece(p) && deadlined.add(p)) h.setPieceDeadline(p, (p - reader) * 250)
        }
        var ahead = 0
        while (ahead < need && reader + ahead <= last && h.havePiece(reader + ahead)) ahead++
        if (server == null && ahead >= need) {
            server = FileServer(file, size) { pos -> h.havePiece(pieceAt(pos)) }.also { it.start() }
        }
        val status = h.status()
        val spans = spans()
        _state.value = StreamState(
            url = server?.url,
            buffer = ahead * 100 / need,
            // Of the file, not of what's wanted: "next 5 min" wants only a slice of it.
            progress = spans.sumOf { (from, to) -> (to - from).toDouble() }.toFloat(),
            swarm = Swarm(status.downloadRate(), status.numPeers(), status.numSeeds()),
            spans = spans,
            subtitles = subtitleFiles.filter(::complete).map { File(saveDir, info.files().filePath(it)) },
        )
    }

    /** Finished pieces of the file, merged into runs. */
    private fun spans(): List<Pair<Float, Float>> {
        val count = (last - first + 1).toFloat()
        val runs = mutableListOf<Pair<Float, Float>>()
        var start = -1
        for (p in first..last + 1) {
            val have = p <= last && h.havePiece(p)
            if (have && start < 0) start = p
            if (!have && start >= 0) {
                runs.add((start - first) / count to (p - first) / count)
                start = -1
            }
        }
        return runs
    }

    fun close() {
        scope.cancel()
        server?.stop()
        runCatching { torrent.handle.clearPieceDeadlines() }
    }
}

/** Main-thread scope for work that outlives a screen. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * A torrent from the moment it's tapped until its page is left. It opens in the background
 * so the page (and its artwork) shows at once; leaving the page removes it.
 */
class Opened(val source: String, hint: String?) {
    val torrent = MutableStateFlow<Torrent?>(null)
    val error = MutableStateFlow<String?>(null)
    @Volatile private var closed = false

    init {
        val isNew = Library.get(source) == null
        Library.update(source) { it ?: Entry(source, hint ?: nameOf(source)) }
        AppScope.launch {
            try {
                val t = Engine.open(source)
                if (closed) Engine.close(t) else torrent.value = t
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't open that."
                if (isNew) Library.remove(source)
            }
        }
        AppScope.launch { findArt(source) }
    }

    fun close() {
        closed = true
        torrent.value?.let(Engine::close)
    }
}
