package com.torrentviewer.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.PeerInfo
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.alert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/*
 * The streaming engine, ported from the Expo build's TorrentStreamModule with the
 * phone-only parts (downloads, notifications, casting) removed. Every number below
 * was measured on that build; the reasoning lives in its git history.
 */

private const val TAG = "TorrentStream"

/** Public DHT bootstrap nodes — without these a cold session never finds peers. */
private const val BOOTSTRAP =
    "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401"

/** Same categories qBittorrent asks for, minus the noisy *_log ones. */
private val ALERT_MASK = alert.error_notification
    .or_(alert.status_notification)
    .or_(alert.tracker_notification)
    .or_(alert.peer_notification)
    .or_(alert.port_mapping_notification)
    .or_(alert.dht_notification)
    .to_int()

/** Attached to every torrent — many carry dead trackers or none. */
private val TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://explodie.org:6969/announce",
)

val VIDEO = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "flv", "wmv", "mpg", "mpeg", "ts", "m2ts")

/** Text subtitle files libVLC can load as slaves. */
val SUBTITLE = setOf("srt", "ass", "ssa", "vtt")

/** A dialogue track is well under 200KB; anything past these is a mistake or a zip bomb. */
private const val MAX_SUBTITLE_BYTES = 2 * 1024 * 1024
private const val MAX_SUBTITLE_DOWNLOAD = 8 * 1024 * 1024
private const val MAX_ZIP_ENTRIES = 100

private fun java.io.InputStream.readCapped(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buf, 0, n)
    }
}

private const val MAX_CACHED_PEERS = 200

/** Container index at the end (Matroska Cues, MP4 moov): ~0.5% of the file, bounded. */
private fun tailBytesFor(size: Long) = (size / 200).coerceIn(2L * 1024 * 1024, 16L * 1024 * 1024)

/** Every container puts its header at byte 0. */
private const val HEAD_BYTES = 2L * 1024 * 1024

/** A .torrent describes the payload; past this it is the wrong file. */
private const val MAX_TORRENT_BYTES = 16L * 1024 * 1024

/** How far ahead of the reader pieces are told to hurry. Bytes, since piece sizes vary 100x. */
private const val WINDOW_BYTES = 32L * 1024 * 1024

/** How far ahead of each reader the torrent may fetch at all; everything else is IGNORE. */
private const val READAHEAD_BYTES = 128L * 1024 * 1024

/** The same buffers in seconds of playback, when the bitrate is known. */
private const val PREPARE_SECONDS = 3
private const val WINDOW_SECONDS = 15
private const val READAHEAD_SECONDS = 60
private const val PREPARE_MAX = 24L * 1024 * 1024
private const val READAHEAD_MAX = 768L * 1024 * 1024

/** Nothing at all arriving this long before the first frame: dead swarm or blocked network. */
private const val STALL_NO_DATA_MS = 90_000L
private const val SHAKE_INTERVAL_MS = 10_000L

/** Refuse to start below this much free space rather than dying on a write. */
private const val FREE_SPACE_MIN = 512L * 1024 * 1024

/** Kept behind each reader (at LOW) so a short rewind lands on data already paid for. */
private const val REWIND_BYTES = 32L * 1024 * 1024

/** Reader id for a seek the player has not made yet; real ids count up from zero. */
private const val SEEK_READER = -1L

/** A seek aim the player never followed with a connection is dropped after this long. */
private const val SEEK_AIM_MS = 30_000L

/** A reopened player reads this much of the head (header, then its 4MB read-ahead) before it seeks. */
private const val REOPEN_HEAD_BYTES = 16L * 1024 * 1024

/** A closed reader still counts this long — players hop between two spots in one file. */
private const val READER_LINGER_MS = 10_000L

/** After start, rush only as many pieces as the swarm finishes in this long. */
private const val REACH_SECONDS = 16L

/**
 * Blocked on one piece this long with peers connected: re-ask the swarm. At 20s the TV sat
 * out 20s more than once on a 4K film with 70 seeds, the piece one 16KB block short and that
 * block out with a single slow peer; the player's buffer holds only ~8s of 4K.
 */
private const val STUCK_PIECE_MS = 5_000L
/** The same during pre-roll, once the piece waits only on blocks already out with peers. */
private const val PREROLL_STUCK_MS = 4_000L
/** Waiting this long on a piece narrows the fetch to the rushed pieces alone. */
private const val STARVE_MS = 5_000L

private const val REMOVE_WAIT_MS = 4_000

class TorrentException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class TorrentFile(
    val index: Int,
    val path: String,
    val name: String,
    val size: Long,
    val isVideo: Boolean,
    val isSubtitle: Boolean,
)

data class TorrentMeta(val name: String, val files: List<TorrentFile>)

data class Progress(
    /** 0–100 of the head buffer needed to start. */
    val buffer: Int,
    /** 0–100 of the torrent. */
    val progress: Float,
    /** Bytes per second. */
    val speed: Int,
    val seeds: Int,
    val peers: Int,
    val dhtNodes: Int,
    val state: String,
)

/** [lanUrl]: the stream addressed for a Cast receiver, when the engine serves the network. */
data class Ready(val url: String, val name: String, val subtitleFiles: List<String>, val lanUrl: String? = null)

class PlayOptions(
    /** MB of the head buffered before the player gets the URL; the server blocks past it anyway. */
    val prepareMb: Int = 1,
    /** Where playback begins, 0–1 — buffering a head the viewer skips is dead time. */
    val startRatio: Double = 0.0,
    /** Bytes per second of the file, or 0; sizes the buffers in seconds rather than bytes. */
    val bitrate: Double = 0.0,
    /** MB of the file allowed on disk before it rolls over at the playhead. 0 = no cap. */
    val cacheCapMb: Int = 0,
)

/**
 * One libtorrent session, one stream at a time.
 *
 * `inspect` resolves a torrent's file list; `play` streams one file over a loopback
 * HTTP server that blocks on pieces that have not landed, and announces [ready]
 * once enough of the head (and the resume point) is in. Blocking calls — run them
 * off the main thread.
 */
class TorrentStream(
    private val context: Context,
    /** Incoming peers: the TV takes 6881, the phone 6882, Windows 51413 — a router forwards a port to one device. */
    private val port: Int = 6881,
    /** Serve the stream to the network too, so a Cast receiver can fetch it. The phone casts; the TV is the screen. */
    private val lan: Boolean = false,
) {
    val progress = MutableStateFlow<Progress?>(null)
    val ready = MutableStateFlow<Ready?>(null)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val session = SessionManager()

    private var pollThread: HandlerThread? = null
    private var poller: Handler? = null
    private var server: LocalFileServer? = null

    private var info: TorrentInfo? = null
    private var handle: TorrentHandle? = null
    private var infoHashHex: String? = null
    private var fileIndex = -1
    private var firstPiece = 0
    private var lastPiece = 0
    /** Where the player will start reading — the resume point. Pre-roll aims here. */
    private var startPiece = 0
    private var headPiece = 0
    private var tailPiece = 0
    private var ticks = 0
    private var readyAt = 0L
    private var lastDataAt = 0L
    private var lastShakeAt = 0L
    /** Last byte any reader asked for, kept after its socket closes (seeks, pauses). */
    @Volatile private var lastReaderAt = 0L

    private var cacheCapBytes = 0L
    private var lastRollTick = -1000

    private var zoneStarts = emptyList<Int>()
    /**
     * One piece the last applyZone set to IGNORE, or -1. Wanted again means the
     * priorities were rewritten under us — the file-priority update from a fresh
     * add lands after the zone and wants the whole file. Seen on the phone: pieces
     * 0–45 in order at 5MB/s, every request slot full, and the last piece the
     * ready check needs never got one. Checked each tick, so the zone is put back.
     */
    private var zoneIgnored = -1
    private var zoneWidth = -1

    /**
     * Fetch the whole file, not just the minutes past the reader: everything outside the
     * readers' zones is wanted at low priority instead of ignored, and the cache stops
     * rolling (unless the disk runs low). For a thin swarm, or watching on to the end offline.
     */
    @Volatile
    var wholeFile = false
        set(value) {
            field = value
            // The mode lives in the piece priorities: the next tick rewrites them.
            zoneWidth = -1
        }
    private var zoneRushedTail = false
    /** Whether the last zone wanted the rest of the file too (Download ahead → Whole file). */
    private var zoneWhole = false
    private var windowPieces = 4
    private var prepareBytes = 3L * 1024 * 1024
    private var readaheadBytes = READAHEAD_BYTES
    private var announcedReady = false
    private var peerSaveTick = 0
    private val deadlined = mutableSetOf<Int>()
    private var subtitleFiles = emptyList<String>()
    /** Video + matching sidecar subs — the only files whose pieces are wanted. */
    private var wantedFiles = intArrayOf()

    /** Bumped on stop() so an in-flight play() does not put the torrent back. */
    @Volatile private var playGen = 0

    /** Byte offset each live HTTP reader is at, keyed by connection id. */
    private val readers = ConcurrentHashMap<Long, Long>()

    /** Server connections already open when [SEEK_READER] was set; one past these replaces it. */
    @Volatile private var seekAfter = 0L
    @Volatile private var seekSetAt = 0L

    /** Readers that closed recently: connection id → (offset, closed at). */
    private val lately = ConcurrentHashMap<Long, Pair<Long, Long>>()

    private var blockingPiece = -1
    private var blockingSince = 0L
    /** Unlike blockingSince, not reset by the stuck-piece kick. */
    private var blockedFrom = 0L
    private var lastKickAt = 0L

    /** Last metadata, valid only while its torrent is still in the session (see forgetMeta). */
    private var cachedUri: String? = null
    private var cached: TorrentInfo? = null

    /** The torrent inspect() added, left running so play() inherits its peers. */
    private var warm: TorrentHandle? = null

    /**
     * Piece data. Not cacheDir: Android sweeps cache directories when space runs low,
     * which would truncate the film mid-playback. Not external storage either: on phones
     * that is FUSE, which can't punch holes (EOPNOTSUPP), so the rolling cache couldn't
     * free in place. Same partition, so the same free space.
     */
    private val saveDir: File
        get() = File(context.filesDir, "torrents").apply { mkdirs() }

    /** Survives stop/restart: .torrent bytes, peer endpoints, DHT routing table. */
    private val cacheRoot: File get() = File(context.filesDir, "torrent-cache").apply { mkdirs() }
    private val metaDir: File get() = File(cacheRoot, "meta").apply { mkdirs() }
    private val peersDir: File get() = File(cacheRoot, "peers").apply { mkdirs() }
    private val importDir: File get() = File(cacheRoot, "imported").apply { mkdirs() }
    private val subsDir: File get() = File(cacheRoot, "subs").apply { mkdirs() }
    private val sessionFile: File get() = File(cacheRoot, "session.dat")

    init {
        // Nothing is kept between launches; whatever a crash or a kill left behind goes now.
        wipeStreamCache()
    }

    /* ---------------------------------------------------------------- API */

    /** Resolve metadata and list the files inside. Slow for cold magnets. */
    fun inspect(uri: String, timeoutSec: Int = 60): TorrentMeta {
        val ti = resolve(uri, timeoutSec)
        cachedUri = uri
        cached = ti
        val files = ti.files()
        val entries = (0 until files.numFiles()).map { i ->
            val path = files.filePath(i)
            val ext = path.substringAfterLast('.', "").lowercase()
            TorrentFile(i, path, files.fileName(i), files.fileSize(i), ext in VIDEO, ext in SUBTITLE)
        }
        log("inspect: ${ti.name()} — ${entries.size} file(s)")
        return TorrentMeta(ti.name(), entries)
    }

    /**
     * Aim the prefetch window at a point before anyone reads it — resuming an hour in
     * would otherwise fetch the head the player is about to seek past.
     */
    fun setPosition(ratio: Double) {
        val ti = info ?: return
        if (fileIndex < 0) return
        val size = ti.files().fileSize(fileIndex)
        if (size <= 0) return
        val offset = (size * ratio.coerceIn(0.0, 0.999)).toLong()
        // Before the put: pump() must never see the new aim paired with the old count.
        seekAfter = server?.connections() ?: 0L
        seekSetAt = System.currentTimeMillis()
        readers[SEEK_READER] = offset
        log("seek: aiming window at ${(ratio * 100).toInt()}% (byte $offset)")
    }

    /** The player is blocked on a piece that has not arrived — a seek now would wait on it. */
    fun starved(): Boolean = server?.starved() == true

    /** Connections the player has opened to the stream so far. */
    fun connections(): Long = server?.connections() ?: 0L

    fun stop() {
        playGen++
        val h = handle?.takeIf { it.isValid }
        handle = null
        // Readers and the poll thread both look into the torrent's metadata, which the
        // removal below frees: stop them first, or a tick in flight reads freed memory.
        server?.stop()
        server = null
        stopPolling()
        if (h != null) {
            runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED) }
            runCatching { h.pause() }
            info?.let { ti ->
                runCatching { h.prioritizeFiles(Array(ti.files().numFiles()) { Priority.IGNORE }) }
                runCatching { h.prioritizePieces(Array(ti.numPieces()) { Priority.IGNORE }) }
            }
            runCatching { session.remove(h, SessionHandle.DELETE_FILES) }
            // Cached metadata may be a view onto this torrent's memory.
            forgetMeta()
        }
        teardown()
        wipeStreamCache()
        progress.value = null
        ready.value = null
        log("stop: stream removed")
    }

    /** App going away: drop the pieces, keep the DHT table for a warm next launch. */
    fun shutdown() {
        teardown()
        wipeStreamCache()
        persistSession()
        if (session.isRunning) session.stop()
    }

    /** Take a private copy of a content:// source; returns a path that keeps working. */
    fun importSource(uri: String): String {
        if (!uri.startsWith("content://", true)) return uri
        val parsed = Uri.parse(uri)
        val dest = File(importDir, "${uri.hashCode().toUInt().toString(16)}.torrent")
        if (dest.exists() && dest.length() > 0) return dest.absolutePath
        runCatching { context.contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try {
            context.contentResolver.openInputStream(parsed).use { input ->
                requireNotNull(input) { "nothing to read" }
                dest.outputStream().use { input.copyTo(it) }
            }
        } catch (e: SecurityException) {
            dest.delete()
            throw TorrentException("The app that shared this file did not grant access to it.", e)
        } catch (e: Exception) {
            dest.delete()
            throw TorrentException("Could not read the shared file: ${e.message}", e)
        }
        log("import: copied ${dest.length()} bytes to ${dest.name}")
        return dest.absolutePath
    }

    /**
     * Fetch an external subtitle — a bare .srt or a zip holding one — and return a
     * path libVLC can open. Only the bytes of a subtitle entry are ever written, under
     * our own name, so an entry called "../../x" cannot escape the folder.
     */
    fun saveSubtitle(url: String, name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "sub" }
        val connection = URL(url).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val bytes = connection.getInputStream().use { it.readCapped(MAX_SUBTITLE_DOWNLOAD) }
            ?: throw TorrentException("That subtitle download is far too large to be one")
        if (bytes.size < 4) throw TorrentException("Subtitle download was empty")

        // "PK" is a zip; anything else is the subtitle itself, named for its format.
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart('﻿')
            val ext = when {
                head.startsWith("WEBVTT") -> "vtt"
                head.contains("[Script Info]", ignoreCase = true) -> "ass"
                else -> "srt"
            }
            return File(subsDir, "$safe.$ext").also { it.writeBytes(bytes) }.absolutePath
        }
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var scanned = 0
            while (entry != null && scanned < MAX_ZIP_ENTRIES) {
                scanned++
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (!entry.isDirectory && ext in SUBTITLE) {
                    val body = zip.readCapped(MAX_SUBTITLE_BYTES)
                        ?: throw TorrentException("That subtitle unpacks to something far too big")
                    val dest = File(subsDir, "$safe.$ext")
                    dest.writeBytes(body)
                    log("subtitle: ${entry.name} → ${dest.name} (${body.size}B)")
                    return dest.absolutePath
                }
                entry = zip.nextEntry
            }
        }
        throw TorrentException("No subtitle file inside the archive")
    }

    /** Drop downloaded subtitles when their library row goes. Paths outside subs/ are ignored. */
    fun deleteSubtitles(paths: List<String>) {
        val dir = subsDir.absolutePath + File.separator
        for (path in paths) File(path).takeIf { it.absolutePath.startsWith(dir) }?.delete()
    }

    /* ------------------------------------------------------------- session */

    private val alerts = object : AlertListener {
        private var peersSeen = 0

        /** null means every alert — filtering by swig ids here silently matches nothing. */
        override fun types(): IntArray? = null

        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.PEER_CONNECT -> if (++peersSeen <= 5) log("peer connected (#$peersSeen)")
                AlertType.DHT_BOOTSTRAP,
                AlertType.LISTEN_SUCCEEDED,
                AlertType.LISTEN_FAILED,
                AlertType.TRACKER_REPLY,
                AlertType.TRACKER_ERROR,
                AlertType.TRACKER_WARNING,
                AlertType.METADATA_RECEIVED,
                AlertType.TORRENT_ERROR,
                // Whether UPnP / NAT-PMP actually opened the port on the router — incoming peers depend on it.
                AlertType.PORTMAP,
                AlertType.PORTMAP_ERROR,
                AlertType.FILE_ERROR,
                AlertType.HASH_FAILED,
                AlertType.PERFORMANCE -> log("libtorrent: ${alert.message()}")
                else -> Unit
            }
        }
    }

    /**
     * Start the session — DHT bootstrap, port mapping — ahead of the first play, so a cold
     * magnet is not also waiting on an empty routing table. Blocking; call it off the main thread.
     */
    fun warmUp() = runCatching { ensureSession() }

    // Synchronized: a warm-up and a first play may both arrive here at once.
    @Synchronized
    private fun ensureSession() {
        if (session.isRunning) return

        // Peers cost native memory: a 2GB television with 200 connections and a 4K
        // decoder gets killed mid-film. ponytail: two brackets, not a curve.
        val totalRam = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }.getOrDefault(Long.MAX_VALUE)
        val tight = totalRam < 3L * 1024 * 1024 * 1024
        val peers = if (tight) 80 else 200
        if (tight) log("device has ${totalRam / 1024 / 1024}MB RAM — limiting to $peers peers")

        val settings = SettingsPack()
            .connectionsLimit(peers)
            .activeDhtLimit(88)
            .downloadRateLimit(0)
            .uploadRateLimit(0)
            .setInteger(settings_pack.int_types.alert_mask.swigValue(), ALERT_MASK)
            .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            // Dial everything at once instead of 30 peers a second.
            .setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), peers)
            .setInteger(settings_pack.int_types.connection_speed.swigValue(), peers)
            // Dials in flight count against the connections limit, and most tracker peers
            // are dead: at 10s the phone tried ~200 a cycle and sat at 6-16 peers for 30s
            // with 400 in hand. A live peer answers within 5s even after two lost SYNs, so
            // this doubles the dial rate without raising the RAM bracket above.
            .setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 5)
            // Slow peers must not book a whole piece the player is about to block on.
            .setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 10)
            // Re-request an unanswered block after 6s, not 60. A ceiling: libtorrent times each
            // peer by its own replies. At 20s the phone sat on 255 of 256 blocks for 20s, twice
            // in one start, at 150kB/s with fifteen seeds idle.
            .setInteger(settings_pack.int_types.request_timeout.swigValue(), 6)
            // An idle peer may also ask for the last blocks of a piece another peer is
            // sitting on. With strict end-game a 16MB piece of a 4K remux waited 20s on one
            // block (141 of 142 done) while seventy peers had nothing to do — the stalls,
            // and most of a two-minute start. The cost is a few duplicate 16KB blocks.
            .setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), false)
            // "max outstanding piece requests reached", logged every second at 6-7MB/s: the
            // default queue of 500 is too shallow for distant fast seeds.
            .setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 1500)
            // A fast line fills the read-ahead in seconds, and with every wanted piece done
            // libtorrent calls the torrent finished and drops its seeds as "redundant": on
            // the TV, 36 seeds at 6.9MB/s became 0 and 5kB/s just as the zone moved on.
            .setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
            .setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:$port,[::]:$port")
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), BOOTSTRAP)
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)

        val params = try {
            if (sessionFile.exists()) SessionParams(sessionFile).also { it.setSettings(settings) } else SessionParams(settings)
        } catch (e: Exception) {
            log("session: bad cache, starting cold (${e.message})")
            SessionParams(settings)
        }
        session.addListener(alerts)
        session.start(params)
        session.startDht()
        log("session started, dht bootstrapping")
    }

    private fun persistSession() {
        if (!session.isRunning) return
        runCatching { session.saveState()?.let { sessionFile.writeBytes(it) } }
            .onFailure { Log.w(TAG, "session save failed", it) }
    }

    /* ------------------------------------------------------------ metadata */

    private fun resolve(uri: String, timeoutSec: Int): TorrentInfo {
        cached?.takeIf { cachedUri == uri }?.let {
            // Re-add the magnet so play() reuses it rather than serialising the whole
            // TorrentInfo through session.download(ti, …), which OOMs on large files.
            val clean = normalize(uri)
            if (clean.startsWith("magnet:", true)) {
                val hash = infoHashOf(clean)?.let { hex -> runCatching { Sha1Hash.parseHex(hex) }.getOrNull() }
                if (hash != null && session.find(hash) == null) {
                    ensureSession()
                    session.download(clean, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                }
            }
            return it
        }

        val clean = normalize(uri)
        loadMeta(clean)?.let {
            cachedUri = uri
            cached = it
            return it
        }
        ensureSession()

        val (ti, bytes) = when {
            // Written back out as a .torrent, so the next open is a disk read, not DHT.
            clean.startsWith("magnet:", true) -> addMagnet(clean, timeoutSec).let { it to asTorrentFile(it) }
            clean.startsWith("content://", true) -> localTorrent(File(importSource(clean))) to ByteArray(0)
            clean.startsWith("http", true) -> downloadTorrent(clean).let { TorrentInfo.bdecode(it) to it }
            else -> localTorrent(File(clean)) to ByteArray(0)
        }
        // Keyed by what loadMeta looks up: on a hybrid v1+v2 torrent the magnet's hash
        // and ti.infoHash() differ.
        if (bytes.isNotEmpty()) persistMeta(infoHashOf(clean) ?: ti.infoHash().toHex(), bytes)
        cachedUri = uri
        cached = ti
        return ti
    }

    /** Add the magnet as a real torrent and keep it, so play() starts with warm peers. */
    private fun addMagnet(magnet: String, timeoutSec: Int): TorrentInfo {
        val hex = infoHashOf(magnet) ?: throw TorrentException("That magnet link could not be parsed")
        val hash = runCatching { Sha1Hash.parseHex(hex) }.getOrNull()
            ?: throw TorrentException("That magnet link has an info-hash we cannot use")
        dropWarm(hex)
        val already = session.find(hash)
        if (already == null || !already.isValid) {
            log("metadata: adding magnet (timeout ${timeoutSec}s)…")
            session.download(magnet, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }
        val started = System.currentTimeMillis()
        repeat(timeoutSec * 4) {
            val h = session.find(hash)
            if (h != null && h.isValid && h.status().hasMetadata()) {
                val ti = runCatching { h.torrentFile() }.getOrNull()
                if (ti != null && ti.isValid) {
                    warm = h
                    // Want nothing until play() picks a file, or a season pack fetches
                    // from piece 0 while the viewer is still choosing.
                    runCatching { h.prioritizeFiles(Array(ti.files().numFiles()) { Priority.IGNORE }) }
                    log("metadata: ready in ${System.currentTimeMillis() - started}ms, ${h.status().numPeers()} peer(s)")
                    return ti
                }
            }
            Thread.sleep(250)
        }
        dropWarm(null)
        throw TorrentException("No peers answered within ${timeoutSec}s. The torrent may be dead, or the network is blocking BitTorrent.")
    }

    /**
     * A TorrentInfo from a handle is a view onto memory the torrent owns; once the
     * torrent leaves the session it answers with garbage. Any removal invalidates it.
     */
    private fun forgetMeta() {
        cached = null
        cachedUri = null
    }

    /** Let go of a warm torrent that is not the one about to be used. */
    private fun dropWarm(keepHex: String?) {
        val w = warm ?: return
        val hex = runCatching { w.infoHash().toHex() }.getOrNull()
        if (hex != null && (hex == keepHex || hex == infoHashHex)) {
            if (hex != keepHex) warm = null
            return
        }
        if (w.isValid && hex != infoHashHex) {
            log("session: dropping warm torrent $hex")
            runCatching { session.remove(w) }
            forgetMeta()
        }
        warm = null
    }

    /** libtorrent reads the file itself; a 3GB "torrent" read into an array is an OOM. */
    private fun localTorrent(file: File): TorrentInfo {
        if (!file.exists()) throw TorrentException("There is no file at ${file.path}")
        if (file.length() > MAX_TORRENT_BYTES) throw TorrentException("${file.name} is not a .torrent file.")
        return try {
            TorrentInfo(file)
        } catch (e: Exception) {
            throw TorrentException("${file.name} could not be read as a torrent", e)
        }
    }

    private fun downloadTorrent(url: String): ByteArray {
        val limit = MAX_TORRENT_BYTES.toInt()
        URL(url).openStream().use { input ->
            val buffer = ByteArray(limit + 1)
            var filled = 0
            while (filled < buffer.size) {
                val read = input.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            if (filled > limit) throw TorrentException("That URL is too large to be a .torrent file.")
            return buffer.copyOf(filled)
        }
    }

    private fun loadMeta(uri: String): TorrentInfo? {
        val hash = infoHashOf(uri) ?: return null
        val file = File(metaDir, "$hash.torrent")
        if (!file.exists()) return null
        return try {
            log("metadata: disk cache hit ($hash)")
            TorrentInfo(file)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** Metadata that arrived over the wire, back in .torrent form; empty if it cannot be. */
    private fun asTorrentFile(ti: TorrentInfo): ByteArray = runCatching {
        val params = AddTorrentParams(add_torrent_params())
        params.setTorrentInfo(ti)
        Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig()))
    }.getOrElse { ByteArray(0) }

    private fun persistMeta(hash: String, bytes: ByteArray) {
        runCatching { File(metaDir, "$hash.torrent").takeUnless { it.exists() }?.writeBytes(bytes) }
    }

    private fun infoHashOf(uri: String): String? {
        if (!uri.startsWith("magnet:", true)) return null
        return try {
            val ec = error_code()
            val p = libtorrent.parse_magnet_uri(uri, ec)
            if (ec.value() != 0) null else p.getInfo_hashes().get_best().to_hex()
        } catch (_: Exception) {
            null
        }
    }

    /* --------------------------------------------------------------- peers */

    private fun loadPeers(hash: String): ArrayList<TcpEndpoint> {
        val file = File(peersDir, "$hash.peers")
        val out = ArrayList<TcpEndpoint>()
        if (!file.exists()) return out
        runCatching { file.readLines().forEach { line -> parseEndpoint(line.trim())?.let(out::add) } }
        if (out.isNotEmpty()) log("peers: loaded ${out.size} cached endpoint(s)")
        return out
    }

    private fun persistPeers() {
        val h = handle ?: return
        val hash = infoHashHex ?: return
        if (!h.isValid) return
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val peers = h.peerInfo() as List<PeerInfo>
            val lines = peers.map { it.ip() }.filter { it.isNotBlank() }.distinct().take(MAX_CACHED_PEERS)
            if (lines.isNotEmpty()) File(peersDir, "$hash.peers").writeText(lines.joinToString("\n"))
        }
    }

    /** PeerInfo.ip() formats as tcp://host:port or tcp://[v6]:port. */
    private fun parseEndpoint(raw: String): TcpEndpoint? {
        if (raw.isEmpty()) return null
        val body = raw.removePrefix("tcp://")
        return runCatching {
            if (body.startsWith("[")) {
                val end = body.indexOf(']')
                TcpEndpoint(body.substring(1, end), body.substring(end + 2).toInt())
            } else {
                val idx = body.lastIndexOf(':')
                TcpEndpoint(body.substring(0, idx), body.substring(idx + 1).toInt())
            }
        }.getOrNull()
    }

    /* ------------------------------------------------------------ playback */

    /** Start streaming one file. Returns once the torrent is set up; [ready] follows. */
    fun play(uri: String, index: Int, options: PlayOptions) {
        val gen = ++playGen
        val startedAt = System.currentTimeMillis()
        ready.value = null
        progress.value = null
        ensureSession()

        // Resolve before tearing down, so teardown knows whether to keep this torrent.
        val ti = resolve(uri, 60)
        if (gen != playGen) return
        if (!ti.isValid || ti.pieceLength() <= 0 || ti.numPieces() <= 0) {
            forgetMeta()
            throw TorrentException("This torrent's metadata went stale. Open it again.")
        }
        val files = ti.files()
        if (index < 0 || index >= files.numFiles()) throw TorrentException("No file at index $index")

        // Sweep what a killed session left before deciding there is no room.
        var free = saveDir.usableSpace
        if (free < FREE_SPACE_MIN) {
            cleanOrphans(ti)
            free = saveDir.usableSpace
        }
        if (free < FREE_SPACE_MIN) throw TorrentException("Only ${free / 1024 / 1024}MB free. Clear some space and try again.")

        teardown(keep = ti.infoHash().toHex())
        if (gen != playGen) return

        val perSecond = options.bitrate.takeIf { it > 0 }?.toLong() ?: 0L
        // Contiguous from the start, so it advances a piece at a time: two pieces at most.
        prepareBytes = maxOf(
            options.prepareMb.toLong() * 1024 * 1024,
            ti.pieceLength().toLong(),
            minOf(perSecond * PREPARE_SECONDS, PREPARE_MAX),
        ).coerceAtMost(ti.pieceLength().toLong() * 2)
        readaheadBytes = maxOf(READAHEAD_BYTES, minOf(perSecond * READAHEAD_SECONDS, READAHEAD_MAX))
        windowPieces = maxOf(2, (maxOf(WINDOW_BYTES, perSecond * WINDOW_SECONDS) / ti.pieceLength()).toInt())
        log(
            "buffers: ${if (perSecond > 0) "${perSecond / 1024}kB/s source" else "bitrate unknown"}, " +
                "${ti.pieceLength() / 1024 / 1024}MB pieces — prepare ${prepareBytes / 1024 / 1024}MB, readahead ${readaheadBytes / 1024 / 1024}MB",
        )

        // No cap (Auto) is the room there is; a cap the volume cannot honour is clamped to it.
        val room = saveDir.usableSpace - FREE_SPACE_MIN * 3
        cacheCapBytes = options.cacheCapMb.toLong() * 1024 * 1024
        if (room > 0 && (cacheCapBytes <= 0L || cacheCapBytes > room)) {
            log("cache cap ${if (cacheCapBytes <= 0L) "auto" else "${cacheCapBytes / 1024 / 1024}MB"} — using the free space, ${room / 1024 / 1024}MB")
            cacheCapBytes = room
        }
        // A roll deletes the readahead too; never fetch further ahead than the cap keeps.
        if (cacheCapBytes > 0L && readaheadBytes > cacheCapBytes / 2) {
            readaheadBytes = maxOf(ti.pieceLength().toLong() * 4, cacheCapBytes / 2)
        }
        lastRollTick = -1000
        info = ti
        infoHashHex = ti.infoHash().toHex()
        fileIndex = index
        announcedReady = false
        peerSaveTick = 0
        ticks = 0
        zoneStarts = emptyList()
        zoneIgnored = -1
        zoneWidth = -1
        zoneRushedTail = false
        zoneWhole = false
        lately.clear()
        blockingPiece = -1
        lastKickAt = 0L

        // Sidecar subs for this file only — a pack can hold hundreds.
        val dir = files.filePath(index).replace("\\", "/").substringBeforeLast("/", "")
        val stem = files.fileName(index).substringBeforeLast('.').lowercase()
        val subHits = (0 until files.numFiles()).filter { i ->
            if (i == index || files.filePath(i).substringAfterLast('.', "").lowercase() !in SUBTITLE) return@filter false
            val subDir = files.filePath(i).replace("\\", "/").substringBeforeLast("/", "")
            val name = files.fileName(i).substringBeforeLast('.').lowercase()
            (subDir == dir || (dir.isNotEmpty() && subDir.startsWith("$dir/"))) && (name == stem || name.startsWith("$stem."))
        }
        subtitleFiles = subHits.map { File(saveDir, files.filePath(it)).absolutePath }
        wantedFiles = (listOf(index) + subHits).toIntArray()
        val priorities = Array(files.numFiles()) { Priority.IGNORE }
        wantedFiles.forEach { priorities[it] = Priority.DEFAULT }
        log("play: ${files.fileName(index)} (${files.fileSize(index)} bytes), ${subHits.size} subtitle file(s)")

        // Adopt the torrent inspect() left running — re-adding drops every peer.
        val fileDir = File(saveDir, files.filePath(index)).parentFile
        val onDisk = fileDir?.exists() == true
        // libtorrent moving pieces out of the part file into a missing folder is ENOENT,
        // which errors the torrent and stalls it at "finding peers".
        runCatching { fileDir?.mkdirs() }
        val running = session.find(ti.infoHash())?.takeIf { it.isValid && sameFiles(it, files.numFiles()) }
        val h = if (running != null) {
            log("play: reusing running torrent (${running.status().numPeers()} peer(s))")
            // Files wiped from under it: libtorrent still believes it has the pieces. A fresh
            // magnet has nothing on disk yet either, and a recheck would drop its warm peers.
            if (!onDisk && running.status().totalDone() > 0) runCatching { running.forceRecheck() }
            running.prioritizeFiles(priorities)
            running
        } else {
            cleanOrphans(ti)
            val peers = loadPeers(infoHashHex!!)
            session.download(ti, saveDir, null, priorities, peers, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            awaitHandle(ti) ?: throw TorrentException("Torrent could not be added to the session")
        }
        // Piece deadlines do the ordering; sequential mode hands piece 0 to the slowest peer.
        h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        handle = h
        warm = null

        val existing = runCatching { h.trackers().map { it.url() } }.getOrDefault(emptyList())
        TRACKERS.filterNot { it in existing }.forEach { runCatching { h.addTracker(AnnounceEntry(it)) } }
        h.forceReannounce()
        h.forceDHTAnnounce()

        val size = files.fileSize(index)
        firstPiece = ti.mapFile(index, 0, 1).piece()
        lastPiece = ti.mapFile(index, maxOf(0, size - 1), 1).piece()
        tailPiece = maxOf(firstPiece, ti.mapFile(index, maxOf(0, size - tailBytesFor(size)), 1).piece())
        val startAt = (size * options.startRatio.coerceIn(0.0, 0.99)).toLong()
        startPiece = ti.mapFile(index, startAt, 1).piece()
        headPiece = startPiece
        log("play: pieces $firstPiece..$lastPiece, tail from $tailPiece, start $startPiece — setup ${System.currentTimeMillis() - startedAt}ms")

        wantOnly(h, ti, wantedFiles)
        readyAt = System.currentTimeMillis()
        lastDataAt = readyAt
        lastShakeAt = 0L
        startPolling()
    }

    /**
     * Want these files and no others — the file list and every piece.
     * prioritizeFiles does not reliably clear piece priorities left from inspect().
     */
    private fun wantOnly(h: TorrentHandle, ti: TorrentInfo, filesWanted: IntArray) {
        val files = ti.files()
        val filePrio = Array(files.numFiles()) { Priority.IGNORE }
        for (i in filesWanted) if (i in filePrio.indices) filePrio[i] = Priority.DEFAULT
        runCatching { h.prioritizeFiles(filePrio) }
        val n = ti.numPieces()
        val piecePrio = Array(n) { Priority.IGNORE }
        for (i in filesWanted) {
            if (i < 0 || i >= files.numFiles()) continue
            val size = files.fileSize(i)
            if (size <= 0) continue
            val a = ti.mapFile(i, 0, 1).piece()
            val b = ti.mapFile(i, size - 1, 1).piece()
            for (p in a..b) if (p in 0 until n) piecePrio[p] = Priority.DEFAULT
        }
        runCatching { h.prioritizePieces(piecePrio) }
    }

    /** A magnet re-added for reuse has no file list until its metadata arrives. */
    private fun sameFiles(h: TorrentHandle, expected: Int): Boolean =
        runCatching { h.torrentFile()?.files()?.numFiles() }.getOrNull() == expected

    private fun awaitHandle(ti: TorrentInfo): TorrentHandle? {
        repeat(40) {
            val h = session.find(ti.infoHash())
            if (h != null && h.isValid) return h
            Thread.sleep(250)
        }
        return null
    }

    /* ------------------------------------------------------------- polling */

    private fun startPolling() {
        if (poller != null) return
        val thread = HandlerThread("torrent-poll").apply { start() }
        val handler = Handler(thread.looper)
        pollThread = thread
        poller = handler
        val tick = object : Runnable {
            override fun run() {
                try {
                    pump()
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed", e)
                }
                if (handle?.isValid != true) return
                handler.postDelayed(this, if (announcedReady) 500 else 200)
            }
        }
        handler.post(tick)
    }

    /** Ends the poll loop, waiting out a tick in flight — unless called from that tick. */
    private fun stopPolling() {
        val thread = pollThread
        poller?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        poller = null
        pollThread = null
        if (thread != null && Thread.currentThread() !== thread) runCatching { thread.join(REMOVE_WAIT_MS + 2_000L) }
    }

    private fun pump() {
        ticks++

        if (ticks % 20 == 0 && saveDir.usableSpace < FREE_SPACE_MIN) {
            // One last roll before giving up: a stream can almost always free itself.
            val h = handle
            val ti = info
            if (h != null && ti != null && h.isValid) {
                lastRollTick = -1000
                if (runCatching { rollCache(h, ti) }.getOrDefault(false)) return
            }
            failStream("Out of storage space. The stream has been stopped and its files cleared.")
            return
        }

        val h = handle ?: return
        val ti = info ?: return
        if (!h.isValid) {
            failStream("The torrent engine lost this stream. Please try again.")
            return
        }

        if (ticks % 4 == 0 && rollCache(h, ti)) return

        // A seeded seek stands in for a reader that has not connected yet — not for the
        // ones already open, which are where the player is leaving from, nor for a reopened
        // player re-reading the header and the index on its way to the target.
        val clock = System.currentTimeMillis()
        if (readers.containsKey(SEEK_READER)) {
            val arrived = readers.entries.any { it.key > seekAfter && it.value >= REOPEN_HEAD_BYTES && pieceAt(it.value) < tailPiece }
            if (arrived || clock - seekSetAt > SEEK_AIM_MS) readers.remove(SEEK_READER)
        }

        val pieceLen = ti.pieceLength().toLong().coerceAtLeast(1L)
        val prerollPieces = maxOf(1, Math.ceil(prepareBytes.toDouble() / pieceLen).toInt())
        // Before the player attaches: exactly the pieces isReady counts — the head piece and
        // the prepare window from the resume point — each its own one-piece zone. Zones of
        // `reach + 1` pieces per target plus spare fetched 9 pieces (72MB) for 3 needed on
        // the phone; with 8MB pieces and ~50 peers that spread kept piece 0 unfinished for 70s.
        val preroll = (listOf(firstPiece) + (startPiece until startPiece + prerollPieces))
            .filter { it <= lastPiece }.distinct()

        // A reader parked in the tail is the player reading the container index, not
        // playback. Lingering closed readers only add zones, never lead. The newest
        // connection leads: after a seek it is where the picture is waiting, and ranked by
        // offset a forward seek came second to the spot the player had just left.
        lately.entries.removeIf { clock - it.value.second > READER_LINGER_MS }
        val live = readers.entries
            .sortedByDescending { if (it.key == SEEK_READER) Long.MAX_VALUE else it.key }
            .map { pieceAt(it.value) }
            .distinct()
        val open = live.filter { it < tailPiece }.ifEmpty { live }
        val closed = lately.values.map { pieceAt(it.first) }.filter { it < tailPiece && it !in open }.distinct().sorted()
        val targets = (open + closed).ifEmpty { preroll }
        headPiece = targets.first()

        val status = h.status()

        // A file error pauses the torrent for good; it would sit at 0kB/s until the stall timeout.
        if (ticks % 10 == 0) status.errorCode().takeIf { it.isError }?.let {
            log("error: ${it.message} — clearing and resuming")
            runCatching { h.swig().clear_error() }
        }

        // Stuck-piece watchdog. The piece the player waits on is nearly always whole but
        // for a block or two held by one slow peer. It used to pause and resume the torrent
        // to drop that request — which also dropped the swarm: on the TV, 71 peers at
        // 6.3MB/s became 18 at 0.95MB/s, and the second time the player ran dry. Now only
        // that piece is re-armed with a deadline already past, which is what makes
        // libtorrent hand its outstanding blocks to other, faster peers as well.
        // During pre-roll the first missing pre-roll piece: a head piece held by one slow
        // peer's last block stalls the start just as it stalls playback.
        val blocked = (if (announcedReady) targets.take(1) else targets)
            .firstOrNull { it in firstPiece..lastPiece && !h.havePiece(it) } ?: -1
        // Pre-roll re-asks much sooner, but only once every block is done or out with a
        // peer: on the phone piece 0 sat at 170 of 171 blocks for 20s of a 23s start
        // while 60 peers ran at 3MB/s. A piece still handing out blocks is just downloading.
        val stuckAfter = if (announcedReady) STUCK_PIECE_MS else PREROLL_STUCK_MS
        if (blocked != blockingPiece) {
            blockingPiece = blocked
            blockingSince = clock
            blockedFrom = clock
        } else if (
            blocked >= 0 && status.numPeers() > 0 &&
            clock - blockingSince > stuckAfter && clock - lastKickAt > stuckAfter &&
            (announcedReady || allRequested(h, blocked))
        ) {
            lastKickAt = clock
            log("stuck: piece $blocked for ${(clock - blockingSince) / 1000}s (${pieceState(h, blocked)}) — re-asking for it")
            runCatching { h.resetPieceDeadline(blocked) }
            runCatching { h.piecePriority(blocked, Priority.TOP_PRIORITY) }
            runCatching { h.setPieceDeadline(blocked, 0) }
            deadlined.add(blocked)
            blockingSince = clock
        }

        // Pre-roll watchdog: cached metadata skips inspect()'s own timeout, so a dead
        // swarm would otherwise spin "finding peers" all evening.
        if (!announcedReady) {
            if (status.totalWantedDone() > 0 || status.downloadRate() > 0) {
                lastDataAt = clock
            } else {
                if (clock - lastShakeAt >= SHAKE_INTERVAL_MS) {
                    lastShakeAt = clock
                    runCatching { h.forceReannounce() }
                    runCatching { h.forceDHTAnnounce() }
                }
                if (clock - lastDataAt >= STALL_NO_DATA_MS) {
                    failStream("No data arrived from any peer within ${STALL_NO_DATA_MS / 1000}s. The torrent may be dead, or the network is blocking BitTorrent.")
                    return
                }
            }
        }

        // `reach` is what the swarm is told to rush; `width` is what it may fetch at all.
        // Pre-roll targets are already every piece it needs, so nothing past them is rushed.
        val reach = if (!announcedReady) {
            0
        } else {
            val affordable = (status.downloadRate().toLong() * REACH_SECONDS / pieceLen).toInt()
            affordable.coerceIn(2, maxOf(2, windowPieces))
        }
        // A thin swarm spread over the whole readahead starves the piece the player waits
        // on: on the TV, 500kB/s over 17 8MB pieces gave piece 204 ~45kB/s for over a
        // minute, and pre-roll took 72s at 2MB/s for two pieces. Until it lands, fetch
        // only what is rushed; the readahead comes back with the next tick after.
        // Pre-roll is always that narrow, from the first tick: its first 5s used to go to
        // the whole readahead (32-128 pieces) while nothing could play.
        val starving = blocked >= 0 && clock - blockedFrom > STARVE_MS
        val width = when {
            !announcedReady -> 0
            starving -> maxOf(2, reach)
            else -> maxOf(2, (readaheadBytes / pieceLen).toInt())
        }
        val back = if (!announcedReady || starving) 0 else maxOf(8, (REWIND_BYTES / pieceLen).toInt())

        // Download ahead → Whole file: the rest of the film at LOW — but not during pre-roll, nor
        // while the player waits on a piece. With it on from the first tick every peer had LOW
        // pieces queued, so none was idle to take over a slow peer's last blocks: a resume on the
        // phone pulled 12MB/s for 50s (~600MB) while pieces 0 and 1791 sat at 29 of 32 blocks for
        // 20s each, re-asked every 4s.
        val whole = wholeFile && announcedReady && !starving
        // Re-aim with hysteresis: a rewind inside the band already covered is not a new zone.
        val stale = zoneWidth != width ||
            zoneRushedTail != announcedReady ||
            zoneWhole != whole ||
            zoneStarts.size != targets.size ||
            targets.any { t -> zoneStarts.none { t >= it - back && t <= it + width / 2 } }
        val clobbered = !stale && zoneIgnored >= 0 &&
            runCatching { h.piecePriority(zoneIgnored) != Priority.IGNORE }.getOrDefault(false)
        if (clobbered) log("zone: priorities were rewritten (piece $zoneIgnored wanted again) — re-applying")
        if (stale || clobbered) applyZone(h, targets, width, back, rushTail = announcedReady, whole = whole)

        val keep = mutableSetOf<Int>()
        targets.forEachIndexed { rank, target ->
            val end = minOf(target + reach, lastPiece)
            for (p in target..end) {
                keep.add(p)
                if (h.havePiece(p) || !deadlined.add(p)) continue
                h.piecePriority(p, Priority.TOP_PRIORITY)
                // Absolute once set, so set once: the stagger keeps its order. Pre-roll
                // pieces are one window the player needs whole, so they sit 300ms apart.
                h.setPieceDeadline(p, if (announcedReady) rank * 3000 + (p - target) * 300 else rank * 300)
            }
        }
        // During pre-roll only the first ~2MB of the index; after, the whole tail at a distant deadline.
        val tailEnd = if (announcedReady) lastPiece else minOf(lastPiece, tailPiece + maxOf(0, (2L * 1024 * 1024 / pieceLen).toInt() - 1))
        for (p in tailPiece..tailEnd) {
            keep.add(p)
            if (!h.havePiece(p) && deadlined.add(p)) h.setPieceDeadline(p, 10_000 + (p - tailPiece) * 300)
        }
        deadlined.removeAll { piece -> (piece !in keep).also { if (it) runCatching { h.resetPieceDeadline(piece) } } }

        val headNeed = pieceLen
        val headHave = contiguousFrom(h, ti, firstPiece)
        val startHave = if (startPiece == firstPiece) headHave else contiguousFrom(h, ti, startPiece)
        // Resuming the closing minutes leaves fewer pieces than the prepare window asks for.
        val startAvailable = maxOf(1, lastPiece - startPiece + 1).toLong() * pieceLen
        val startNeed = minOf(if (startPiece == firstPiece) maxOf(prepareBytes, headNeed) else prepareBytes, startAvailable)
        val buffered = minOf(startHave, headHave)
        val isReady = headHave >= headNeed && startHave >= startNeed

        session.postSessionStats()
        if (ticks % 2 == 0) reportProgress(h, ((buffered * 100 / maxOf(1L, startNeed)).coerceAtMost(100L)).toInt())

        if (status.numPeers() > 0 && ++peerSaveTick % 30 == 0) {
            persistPeers()
            log(
                "rate ${status.downloadRate() / 1024}kB/s, ${status.numPeers()} peers (${status.numSeeds()} seeds), " +
                    "readers ${targets.joinToString("/")}, reach $reach, ${deadlined.size} deadlined",
            )
        }

        if (!announcedReady && isReady) {
            announcedReady = true
            val path = File(saveDir, ti.files().filePath(fileIndex))
            val size = ti.files().fileSize(fileIndex)
            // HTTP, not file://: the player must block on missing data, not read zeros.
            val srv = LocalFileServer(
                file = path,
                fileSize = size,
                available = { offset -> handle?.havePiece(pieceAt(offset)) == true },
                request = { id, offset ->
                    readers[id] = offset
                    lastReaderAt = offset
                },
                release = { id -> readers.remove(id)?.let { at -> lately[id] = at to System.currentTimeMillis() } },
                lan = lan,
            )
            srv.start()
            server = srv
            log("ready in ${(System.currentTimeMillis() - readyAt) / 1000}s at ${status.downloadRate() / 1024}kB/s -> ${srv.url}")
            ready.value = Ready(srv.url, ti.files().fileName(fileIndex), subtitleFiles, srv.lanUrl)
        }
    }

    /**
     * Free the disk by dropping what has been watched: remove the torrent, punch the file
     * out bar a little behind the viewer, the readahead and the tail (libholes, [rollCuts]),
     * and add it again holding every piece
     * it still has — the readahead under the playhead included, so playback carries on while
     * peers reconnect. Re-adding is also what tells peers those pieces are gone; left alone,
     * the torrent would serve zeros from the holes. Where a hole cannot be punched it falls
     * back to deleting the file and starting over, one rebuffer per roll.
     *
     * @return true when the handle was replaced.
     */
    private fun rollCache(h: TorrentHandle, ti: TorrentInfo): Boolean {
        if (!announcedReady) return false
        if (ticks - lastRollTick < 60) return false
        val status = runCatching { h.status() }.getOrNull() ?: return false
        val onDisk = status.totalDone()
        val low = saveDir.usableSpace < FREE_SPACE_MIN * 3
        if (!low && (wholeFile || cacheCapBytes <= 0L || onDisk < cacheCapBytes)) return false

        val at = readers.values.minOrNull() ?: lastReaderAt.takeIf { it > 0 } ?: return false
        lastRollTick = ticks

        // `ti` may die with the torrent: take a copy that owns itself first.
        val own = runCatching { TorrentInfo.bdecode(asTorrentFile(ti)) }.getOrNull()
            ?: infoHashHex?.let { hex -> runCatching { TorrentInfo(File(metaDir, "$hex.torrent")) }.getOrNull() }
        if (own == null || !own.isValid) {
            log("roll: no standalone copy of the metadata — not rolling")
            return false
        }
        val freeBefore = saveDir.usableSpace
        log("roll: ${onDisk / 1024 / 1024}MB on disk, cap ${cacheCapBytes / 1024 / 1024}MB — restarting at byte $at")

        persistPeers()
        val peers = loadPeers(infoHashHex ?: ti.infoHash().toHex())
        runCatching { h.clearPieceDeadlines() }
        deadlined.clear()
        val had = (0 until own.numPieces()).filter { runCatching { h.havePiece(it) }.getOrDefault(false) }

        try {
            val hash = own.infoHash()
            runCatching { session.remove(h) }
            forgetMeta()
            var waited = 0
            while (session.find(hash) != null && waited < REMOVE_WAIT_MS) {
                Thread.sleep(50)
                waited += 50
            }
            if (session.find(hash) != null) {
                log("roll: torrent would not release after ${waited}ms — left alone")
                return false
            }
            info = own
            val files = own.files()
            val far = readers.values.filter { pieceAt(it) < tailPiece }.maxOrNull() ?: at
            val cuts = rollCuts(files.fileOffset(fileIndex), files.fileSize(fileIndex), at - REWIND_BYTES, maxOf(at, far) + readaheadBytes, tailPiece, own.pieceLength().toLong())
            val file = File(saveDir, files.filePath(fileIndex))
            val err = if (cuts.isEmpty()) -1 else cuts.firstNotNullOfOrNull { c -> Holes.punch(file, c.from, c.to - c.from).takeIf { it != 0 } } ?: 0
            val kept = if (err == 0) had.filterNot { p -> cuts.any { p in it.pieces } } else emptyList()
            if (err != 0) {
                if (cuts.isNotEmpty()) log("roll: could not free in place (error $err) — starting the file over")
                runCatching {
                    File(saveDir, own.name()).deleteRecursively()
                    for (i in 0 until files.numFiles()) File(saveDir, files.filePath(i)).delete()
                }
            }
            val free = saveDir.usableSpace
            if (free < FREE_SPACE_MIN) {
                failStream("Out of storage space. The stream has been stopped.")
                return true
            }
            log("roll: freed ${(free - freeBefore) / 1024 / 1024}MB, kept ${kept.size} piece(s)")

            val priorities = Array(files.numFiles()) { Priority.IGNORE }
            wantedFiles.forEach { if (it in priorities.indices) priorities[it] = Priority.DEFAULT }
            addKeeping(own, priorities, peers, kept)
            val next = awaitHandle(own) ?: throw IllegalStateException("torrent did not come back after re-adding")
            next.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            val existing = runCatching { next.trackers().map { it.url() } }.getOrDefault(emptyList())
            TRACKERS.filterNot { it in existing }.forEach { runCatching { next.addTracker(AnnounceEntry(it)) } }
            runCatching { next.forceReannounce() }
            runCatching { next.forceDHTAnnounce() }

            handle = next
            zoneStarts = emptyList()
            zoneIgnored = -1
            zoneWidth = -1
            zoneRushedTail = false
            zoneWhole = false
            if (readers.isEmpty()) {
                seekAfter = server?.connections() ?: 0L
                seekSetAt = System.currentTimeMillis()
                readers[SEEK_READER] = at
            }
            server?.reopen()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "roll failed", e)
            failStream("The stream could not be restarted after freeing space.")
            return true
        }
    }

    /**
     * SessionManager.download, plus the pieces already on disk ([have]): the torrent comes back
     * owning them without hashing the file again — a 50GB film would take minutes.
     */
    private fun addKeeping(ti: TorrentInfo, priorities: Array<Priority>, peers: List<TcpEndpoint>, have: List<Int>) {
        val p = add_torrent_params()
        p.set_ti(ti.swig())
        p.setSave_path(saveDir.absolutePath)
        p.set_file_priorities(org.libtorrent4j.swig.byte_vector().apply { priorities.forEach { add(it.swig()) } })
        if (peers.isNotEmpty()) p.setPeers(org.libtorrent4j.swig.tcp_endpoint_vector().apply { peers.forEach { add(it.swig()) } })
        if (have.isNotEmpty()) p.set_have_pieces(org.libtorrent4j.swig.bitfield(ti.numPieces()).apply { have.forEach(::set_bit) })
        p.setFlags(p.getFlags().or_(TorrentFlags.SEQUENTIAL_DOWNLOAD))
        session.swig().async_add_torrent(p)
    }

    private fun failStream(message: String) {
        log("fail: $message")
        errors.tryEmit(message)
        runCatching { teardown() }
        runCatching { wipeStreamCache() }
    }

    /**
     * One zone per reader (DEFAULT), a band behind it and spare work past it (LOW),
     * the tail (DEFAULT), everything else in the file IGNORE.
     */
    private fun applyZone(h: TorrentHandle, targets: List<Int>, width: Int, back: Int, rushTail: Boolean, whole: Boolean) {
        val prios = runCatching { h.piecePriorities() }.getOrNull() ?: return
        // A re-added magnet without its metadata drops every priority: try again next tick.
        if (prios.size <= lastPiece) return
        val zones = targets.map { it..minOf(it + width, lastPiece) }
        val behind = targets.mapNotNull { if (it > firstPiece) maxOf(firstPiece, it - back)..(it - 1) else null }
        // Spare work keeps the wanted set from ever completing — a "finished" torrent drops its seeds.
        // At least one piece, so pre-roll's one-piece zones keep it too.
        val spare = targets.map { (it + width + 1)..minOf(it + width + maxOf(1, width / 2), lastPiece) }
        val pieceLen = (info?.pieceLength() ?: 1).toLong().coerceAtLeast(1L)
        val tailEnd = if (rushTail) lastPiece else minOf(lastPiece, tailPiece + maxOf(0, (2L * 1024 * 1024 / pieceLen).toInt() - 1))
        for (p in firstPiece..lastPiece) {
            if (p >= prios.size) break
            prios[p] = when {
                p in tailPiece..tailEnd || zones.any { p in it } -> Priority.DEFAULT
                spare.any { p in it } || behind.any { p in it } -> Priority.LOW
                whole -> Priority.LOW
                else -> Priority.IGNORE
            }
        }
        runCatching { h.prioritizePieces(prios) }
        zoneIgnored = (firstPiece..lastPiece).firstOrNull { prios[it] == Priority.IGNORE } ?: -1
        // A rewrite can bring sequential back with it, and then in-order requests
        // fill every slot ahead of the deadlines.
        runCatching { h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        runCatching { h.clearPieceDeadlines() }
        deadlined.clear()
        zoneStarts = targets
        zoneWidth = width
        zoneRushedTail = rushTail
        zoneWhole = whole
        log("zone: ${zones.joinToString(", ") { "${it.first}..${it.last}" }} (back $back, tail $tailPiece..$tailEnd)")
    }

    private fun contiguousFrom(h: TorrentHandle, ti: TorrentInfo, from: Int): Long {
        var have = 0L
        var p = from
        while (p <= lastPiece && h.havePiece(p)) {
            have += ti.pieceLength()
            p++
        }
        return have
    }

    private fun reportProgress(h: TorrentHandle, buffer: Int) {
        val status = h.status()
        progress.value = Progress(
            buffer = buffer,
            progress = (status.progress() * 100f).coerceIn(0f, 100f),
            speed = status.downloadRate(),
            seeds = status.numSeeds(),
            peers = status.numPeers(),
            dhtNodes = session.stats().dhtNodes().toInt(),
            state = status.state().name,
        )
    }

    /** A partial piece's blocks, for the stall log. */
    private fun pieceState(h: TorrentHandle, piece: Int): String {
        val q = runCatching { h.downloadQueue }.getOrNull()?.firstOrNull { it.pieceIndex() == piece }
            ?: return "nothing requested yet"
        return "${q.finished()} done, ${q.writing()} writing, ${q.requested()} requested of ${q.blocksInPiece()} blocks"
    }

    /** No block of the piece is left to hand out: what remains waits on peers already asked. */
    private fun allRequested(h: TorrentHandle, piece: Int): Boolean {
        val q = runCatching { h.downloadQueue }.getOrNull()?.firstOrNull { it.pieceIndex() == piece } ?: return false
        return q.finished() + q.writing() + q.requested() >= q.blocksInPiece()
    }

    /**
     * Where the player is reading, 0–1 of the file's bytes; -1 with no open reader.
     * Saved with the resume point: a variable-bitrate file is nowhere near as far
     * through its bytes as through its running time. A tail reader is the index.
     */
    fun readRatio(): Double = runCatching {
        val size = info?.files()?.fileSize(fileIndex)?.takeIf { it > 0 }
        val at = readers.values.filter { pieceAt(it) < tailPiece }.minOrNull()
        if (size == null || at == null) -1.0 else at.toDouble() / size
    }.getOrDefault(-1.0)

    /** Piece holding the given byte offset within the selected file. */
    private fun pieceAt(offset: Long): Int {
        val ti = info ?: return firstPiece
        return ti.mapFile(fileIndex, offset.coerceAtLeast(0), 1).piece()
    }

    /* -------------------------------------------------------------- teardown */

    /**
     * @param keep info-hash of a torrent about to be played again — removing and
     *   re-adding it would throw away its peers and pieces.
     */
    private fun teardown(keep: String? = null) {
        persistPeers()
        persistSession()
        server?.stop()
        server = null
        stopPolling()

        val keeping = keep != null && keep == infoHashHex
        // Read the paths before the torrent leaves the session: afterwards it is a
        // use-after-free that takes the process down.
        val doomed: List<String> = if (!keeping && keep != null) {
            info?.let { old ->
                runCatching {
                    val files = old.files()
                    listOf(old.name()) + (0 until files.numFiles()).map { files.filePath(it) }
                }.getOrDefault(emptyList())
            } ?: emptyList()
        } else {
            emptyList()
        }
        handle?.let { h ->
            if (h.isValid) {
                h.clearPieceDeadlines()
                if (!keeping) {
                    session.remove(h)
                    forgetMeta()
                }
            }
        }
        dropWarm(keep)

        // play() may already have the next torrent writing into saveDir: with `keep`,
        // only the outgoing torrent's tree goes.
        if (!keeping) {
            if (keep == null) {
                wipeStreamCache()
            } else if (doomed.isNotEmpty()) {
                runCatching {
                    File(saveDir, doomed.first()).deleteRecursively()
                    doomed.drop(1).forEach { File(saveDir, it).delete() }
                }
            }
        }

        deadlined.clear()
        readers.clear()
        lately.clear()
        blockingPiece = -1
        zoneStarts = emptyList()
        zoneIgnored = -1
        zoneWidth = -1
        zoneRushedTail = false
        zoneWhole = false
        subtitleFiles = emptyList()
        wantedFiles = intArrayOf()
        handle = null
        info = null
        infoHashHex = null
        fileIndex = -1
        announcedReady = false
        readyAt = 0L
        lastDataAt = 0L
        lastShakeAt = 0L
        peerSaveTick = 0
        cacheCapBytes = 0L
        lastRollTick = -1000
        lastReaderAt = 0L
        readaheadBytes = READAHEAD_BYTES
    }

    /* ---------------------------------------------------------------- utils */

    /** Never throws: teardown calls it partway through. */
    private fun wipeStreamCache() {
        runCatching { File(context.cacheDir, "torrents").takeIf { it.exists() }?.deleteRecursively() }
        runCatching { context.getExternalFilesDir(null)?.let { File(it, "torrents") }?.takeIf { it.exists() }?.deleteRecursively() }
        val dir = runCatching { saveDir }.getOrNull() ?: return
        runCatching { if (dir.exists()) dir.deleteRecursively() }
        runCatching { dir.mkdirs() }
    }

    /** Piece data left by sessions that never stopped cleanly; this torrent's tree stays. */
    private fun cleanOrphans(ti: TorrentInfo) {
        val dir = runCatching { saveDir }.getOrNull() ?: return
        val mine = safeName(ti.name())
        dir.listFiles()?.forEach { child ->
            if (child.name == ti.name() || safeName(child.name) == mine) return@forEach
            runCatching { child.deleteRecursively() }
        }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().ifBlank { "download" }

    /** libtorrent takes magnet:/http: URLs or a bare path, not file:// */
    private fun normalize(uri: String) = if (uri.startsWith("file://")) Uri.parse(uri).path ?: uri else uri

    /** Also written here, timestamped, when set: some phones (vivo) keep app logs out of logcat. Restarts past 1 MB. */
    @Volatile
    var logFile: File? = null
    private val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    private fun log(message: String) {
        Log.i(TAG, message)
        val f = logFile ?: return
        synchronized(stamp) {
            runCatching {
                if (f.length() > 1_000_000) f.writeText("")
                f.appendText("${stamp.format(java.util.Date())} $message\n")
            }
        }
    }
}
