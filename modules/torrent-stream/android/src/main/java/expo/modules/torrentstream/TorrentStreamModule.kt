package expo.modules.torrentstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
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
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

private const val TAG = "TorrentStream"
private const val NOTIF_CHANNEL = "torrent_downloads_v2"
private const val NOTIF_ID = 42001

/** Public DHT bootstrap nodes — without these a cold session never finds peers. */
private const val BOOTSTRAP =
  "router.bittorrent.com:6881,router.utorrent.com:6881," +
    "dht.transmissionbt.com:6881,dht.libtorrent.org:25401"

/** Same categories qBittorrent asks for, minus the noisy *_log ones. */
private val ALERT_MASK = alert.error_notification
  .or_(alert.status_notification)
  .or_(alert.tracker_notification)
  .or_(alert.peer_notification)
  .or_(alert.port_mapping_notification)
  .or_(alert.dht_notification)
  .to_int()

/** Attached to every torrent — many .torrent files carry dead or no trackers. */
private val TRACKERS = listOf(
  "udp://tracker.opentrackr.org:1337/announce",
  "udp://open.stealth.si:80/announce",
  "udp://tracker.torrent.eu.org:451/announce",
  "udp://exodus.desync.com:6969/announce",
  "udp://open.demonii.com:1337/announce",
  "udp://explodie.org:6969/announce"
)

private val VIDEO = setOf(
  "mp4", "mkv", "avi", "mov", "m4v", "webm", "flv", "wmv", "mpg", "mpeg", "ts", "m2ts"
)

/** Text subtitle files libVLC can load as slaves. */
private val SUBTITLE = setOf("srt", "ass", "ssa", "vtt")

/**
 * Limits on anything fetched from a subtitle host.
 *
 * A dialogue track for a three-hour film is well under 200KB, and an archive
 * holding one is smaller still. Everything past that is either a mistake or an
 * attempt to fill the disk, and neither is worth unpacking.
 */
private const val MAX_SUBTITLE_BYTES = 2 * 1024 * 1024
private const val MAX_SUBTITLE_DOWNLOAD = 8 * 1024 * 1024
private const val MAX_ZIP_ENTRIES = 100

/** Read the whole stream, or nothing if it runs past `limit`. */
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

/** Trackers hand back ~200 at a time and most are stale by the next session;
 *  keeping a wide list is what makes a warm start find a swarm immediately. */
private const val MAX_CACHED_PEERS = 200

/**
 * Tail fetched up-front: players read the container index from the end of the
 * file (Matroska Cues, non-faststart MP4 moov) before the first frame.
 *
 * It has to scale with the file. An MP4 moov carries a table entry per frame,
 * so a 2.9GB feature runs to double-digit MB — a fixed 2MB tail hands the player
 * the end of the index and nothing else, and it stalls fetching the rest at low
 * priority. Roughly half a percent of the file, bounded both ways.
 */
private fun tailBytesFor(size: Long) =
  (size / 200).coerceIn(2L * 1024 * 1024, 16L * 1024 * 1024)

/**
 * Head fetched up-front no matter where playback starts: every container puts
 * its header at byte 0, and the player reads that before it can seek anywhere.
 */
private const val HEAD_BYTES = 2L * 1024 * 1024

/** A .torrent is a description, not the payload — even a huge multi-file one
 *  with a million pieces stays in the low MB. Anything past this is the wrong
 *  file, and reading it whole is how you OOM. */
private const val MAX_TORRENT_BYTES = 16L * 1024 * 1024

/**
 * Deadline window, in bytes — how far ahead of the viewer pieces are marked
 * time-critical. Not the same as the zone (READAHEAD_BYTES), which is what the
 * torrent is *allowed* to fetch; this is only what it is told to hurry.
 *
 * Sized in bytes because piece size varies hugely between torrents: a fixed
 * piece count is a 3MB window on one and a 300MB window on another.
 */
private const val WINDOW_BYTES = 32L * 1024 * 1024

/**
 * The zone: how far past each reader the torrent may fetch *at all*. Everything
 * outside is IGNORE.
 *
 * Bytes, not pieces. Piece size varies by two orders of magnitude between
 * torrents, so a fixed count is a 3MB window on one and a 700MB window on
 * another — the old 240-piece floor was 686MB of readahead on a feature film,
 * and every byte of it stays on disk for the session.
 *
 * The piece floor that used to sit under this is gone, and it is worth saying
 * why: it only ever bound on torrents with *large* pieces, which is precisely
 * where it hurt. A complete-series pack has 8MB pieces, so a 48-piece floor
 * meant 384MB of the file was wanted while the player was still waiting on its
 * first 8MB — the swarm spread itself over all of it and the opening piece took
 * over a minute to land. The floor existed to keep the wanted set big enough
 * that peers are not all holding the same few blocks (permanent end-game), and
 * that is a question of blocks, not pieces: 128MB is 8192 blocks however they
 * are grouped.
 *
 * 128MB is roughly six minutes of a 1080p release, one minute of 4K.
 */
private const val READAHEAD_BYTES = 128L * 1024 * 1024

/** Refuse to start below this much free space rather than dying on a write. */
private const val FREE_SPACE_MIN = 512L * 1024 * 1024


/**
 * Kept behind each reader as well as ahead.
 *
 * A short rewind is the one seek that lands on data the session already paid
 * for, and it was the worst-served: everything behind the reader went to
 * IGNORE the moment it passed, so stepping back a single piece put the reader
 * outside its zone and re-aimed every zone in the torrent — which clears every
 * piece deadline and re-prioritises the whole file mid-playback.
 *
 * The band itself is LOW, never DEFAULT: it must not take bandwidth from the
 * pieces the viewer is about to need. It only spends what the forward window
 * is not using.
 */
private const val REWIND_BYTES = 32L * 1024 * 1024

/**
 * How long a reader still counts after its connection closes. See `lately` —
 * long enough to span a player hopping between two parts of one file, short
 * enough that a real seek lets go of the old spot quickly.
 */
private const val READER_LINGER_MS = 10_000L

/** After start, rush only as many pieces as the swarm finishes in this long. */
private const val REACH_SECONDS = 16L

/**
 * The player blocked on one piece this long, with the swarm connected, is a
 * piece whose requests are stuck with a peer that is not delivering. libVLC
 * gives up on its connection at about forty seconds and restarts reading
 * further on, so this has to act well before then.
 */
private const val STUCK_PIECE_MS = 20_000L

/** Seconds of playback the pre-roll has to carry before the swarm catches up. */
private const val PREROLL_LEAD_S = 10L

/** Runtime used to guess a file's bitrate before the player has read it. Short
 *  on purpose: over-estimating bitrate only means buffering a little more. */
private const val ASSUMED_RUNTIME_S = 40L * 60

/**
 * How much of the start has to be on disk before the URL goes out.
 *
 * Playback eats roughly size/runtime a second; the swarm adds `rate`. Start
 * once what is here covers the shortfall over the next PREROLL_LEAD_S. A link
 * faster than the file needs only one piece; a crawling one waits for the
 * whole `ceiling` (the user's buffer setting). `rate` is 0 for the first tick
 * or two, so this starts at the ceiling and drops as the swarm warms up.
 */
// ponytail: bitrate guessed from size, pass the real duration when history has one
internal fun prerollNeed(fileSize: Long, rate: Long, pieceLen: Long, ceiling: Long): Long {
  val shortfall = (fileSize / ASSUMED_RUNTIME_S - rate) * PREROLL_LEAD_S
  return shortfall.coerceIn(pieceLen, maxOf(pieceLen, ceiling))
}

class PlayOptions : Record {
  /** Most MB of the file head buffered before the player is handed the URL —
   *  a fast swarm starts sooner, see prerollNeed. The server blocks on data
   *  that has not arrived, so the player's own buffering covers the rest. */
  @Field val prepareMb: Int = 1

  /** Pieces kept hot ahead of the playback head. Zero derives it from
   *  WINDOW_BYTES, which is what every caller wants. */
  @Field val windowPieces: Int = 0
  @Field val removeFilesAfterStop: Boolean = true

  /** Where playback will actually begin, 0-1 through the file. Buffering the
   *  head of a file the viewer is going to skip past is pure dead time. */
  @Field val startRatio: Double = 0.0

  /** Fetch all of the selected file instead of a window around the reader.
   *  See `fullFile` in the JS PlayOptions. */
  @Field val fullFile: Boolean = false

  /** Extra files to fetch with `fullFile` — comma-separated indexes.
   *  A JS number[] on a Record arrives empty on Android, so this is a string. */
  @Field var fileList: String = ""

  /** When >= 0, fetch every video in this season, not just `index`. */
  @Field var season: Int = -1

  /** Public Movies/Watchly/<this>/filename — a show + season label. */
  @Field var saveFolder: String = ""
}

class TorrentStreamModule : Module() {
  private val session = SessionManager()

  private var pollThread: HandlerThread? = null
  private var poller: Handler? = null
  private var server: LocalFileServer? = null

  private var info: TorrentInfo? = null
  private var handle: TorrentHandle? = null
  private var infoHashHex: String? = null
  private var fileIndex = -1
  /** Fetch the selected file end to end rather than follow a reader. Never the
   *  whole torrent: the file priorities set in play() exclude every other file
   *  in it, download or not. */
  private var fullFile = false
  /**
   * Streaming, but fetch the rest of the file too (the player's *Whole file*),
   * front to back: everything outside the zone goes from IGNORE to LOW and the
   * torrent turns sequential, so the downloaded part grows as one stretch from
   * the start rather than in scattered pieces. Playback does not wait on that
   * order — the pieces under the playhead carry deadlines, and libtorrent's
   * time-critical path serves those ahead of any picker order.
   *
   * The viewer's preference, not a property of one stream: teardown leaves it
   * alone, because the player sends it as it opens, which can be before play()
   * has finished setting up — resetting it there silently dropped the choice.
   * Off is the default: a window is lighter on data, disk and battery.
   */
  private var wholeStream = false
  /** Movies/Watchly/<saveFolder>/ — set by play() for download mode. */
  private var saveFolder = ""
  private var firstPiece = 0
  private var lastPiece = 0
  /** Where the player is going to start reading — the resume point, not the
   *  start of the file. Everything pre-roll is aimed here. */
  private var startPiece = 0
  private var headPiece = 0
  private var tailPiece = 0
  private var ticks = 0
  private var readyAt = 0L
  /** Last piece of the container header region. */
  private var headEndPiece = 0
  /** Starts of the currently applied readahead zones; empty until first apply. */
  private var zoneStarts = emptyList<Int>()
  private var zoneWidth = -1
  /** Whether the last applyZone() treated the full tail as DEFAULT. */
  private var zoneRushedTail = false
  /** Replaced by play() before anything reads it; see WINDOW_BYTES. */
  private var windowPieces = 4
  private var prepareBytes = 3L * 1024 * 1024
  private var removeFilesAfterStop = true
  private var announcedReady = false
  private var peerSaveTick = 0
  private val deadlined = mutableSetOf<Int>()
  /** Absolute paths of subtitle files found in the same folder as the video. */
  private var subtitleFiles = emptyList<String>()
  /** Video + matching sidecar subs — the only files whose pieces are wanted. */
  private var wantedFiles = intArrayOf()
  /** Wanted videos already copied into Downloads while the torrent is still up. */
  private val savedFiles = ConcurrentHashMap.newKeySet<Int>()

  /** One full-file download. Stream playback still uses the singleton handle above. */
  private class Dl(
    val uri: String,
    var info: TorrentInfo,
    var handle: TorrentHandle,
    val hash: String,
    var wantedFiles: IntArray,
    var fileIndex: Int,
    var saveFolder: String,
    val savedFiles: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
    var userPaused: Boolean = false,
    var announcedReady: Boolean = false,
  )

  private val downloads = LinkedHashMap<String, Dl>()
  private val downloadByUri = ConcurrentHashMap<String, String>()

  /** User paused the transfer from the Downloads UI. */
  private var userPaused = false
  /** Bumped on stop() so an in-flight play() does not put the torrent back. */
  @Volatile private var playGen = 0
  /** Show an ongoing notification while downloading (Download mode). */
  private var showNotify = false
  private var lastNotifPct = -1
  private var lastNotifPaused: Boolean? = null
  private var lastNotifRate = -1
  /** Survives React context teardown so we can still cancel the tray icon. */
  private var appCtx: Context? = null

  /** Byte offset each live HTTP reader is sitting at, keyed by connection id.
   *  The player runs more than one at a time — playback at the head plus a probe
   *  reading the container index at the tail — and a single shared window meant
   *  they fought over it every 150ms, resetting the scheduler faster than
   *  libtorrent could complete a piece for either. */
  private val readers = ConcurrentHashMap<Long, Long>()

  /** Readers that closed recently: connection id → (offset, closed at). */
  private val lately = ConcurrentHashMap<Long, Pair<Long, Long>>()

  /**
   * Where the *Whole file* front band starts: the first piece of the file not
   * yet on disk, as of the last applyZone. See the band in applyZone.
   */
  private var zoneFront = -1

  /** The piece the player is waiting on, and since when. See STUCK_PIECE_MS. */
  private var blockingPiece = -1
  private var blockingSince = 0L
  private var lastKickAt = 0L

  /**
   * Metadata is expensive to fetch; keep the last one so play() need not
   * refetch. Only valid while its torrent is still in the session — see
   * forgetMeta.
   */
  private var cachedUri: String? = null
  private var cached: TorrentInfo? = null

  /** The torrent inspect() added, left running so play() inherits its peers
   *  instead of finding the swarm a second time. */
  private var warm: TorrentHandle? = null

  /**
   * Piece data — safe to wipe on stop, and wiped on startup, on backgrounding
   * while idle, and in OnDestroy.
   *
   * Deliberately not cacheDir. Android sweeps cache directories when the device
   * runs low on space, and a multi-GB film being read live is the largest thing
   * there — so the OS would truncate the file mid-playback, as would the user
   * tapping "Clear cache". App-specific external storage is the same flash at
   * the same speed, needs no permission, goes on uninstall, and is not eligible
   * for that sweep. It returns null only while the volume is unmounted.
   */
  private val saveDir: File
    get() {
      val ctx = appContext.reactContext!!
      return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "torrents")
        .apply { mkdirs() }
    }

  /** Where pieces lived before the move off cacheDir. Wiped, never written. */
  private val legacySaveDir: File
    get() = File(appContext.reactContext!!.cacheDir, "torrents")

  /** Survives stop/restart: .torrent bytes, peer endpoints, DHT routing table. */
  private val cacheRoot: File
    get() = File(appContext.reactContext!!.filesDir, "torrent-cache").apply { mkdirs() }
  private val metaDir: File
    get() = File(cacheRoot, "meta").apply { mkdirs() }
  private val peersDir: File
    get() = File(cacheRoot, "peers").apply { mkdirs() }
  private val importDir: File
    get() = File(cacheRoot, "imported").apply { mkdirs() }
  private val sessionFile: File
    get() = File(cacheRoot, "session.dat")

  private fun wantedStore(hex: String): File {
    val root = runCatching { cacheRoot }.getOrNull()
      ?: File((appCtx ?: appContext.reactContext)!!.filesDir, "torrent-cache").apply { mkdirs() }
    return File(root, "wanted-$hex")
  }

  private fun persistWanted() {
    val hex = infoHashHex ?: return
    persistWanted(hex, wantedFiles)
  }

  private fun persistWanted(hex: String, files: IntArray) {
    runCatching { wantedStore(hex).writeText(files.joinToString(",")) }
  }

  private fun loadWanted(hex: String): List<Int> {
    val f = runCatching { wantedStore(hex) }.getOrNull() ?: return emptyList()
    if (!f.exists()) return emptyList()
    return f.readText().split(',').mapNotNull { it.trim().toIntOrNull() }
  }

  private fun forgetWanted(hex: String?) {
    hex ?: return
    runCatching { wantedStore(hex).delete() }
  }

  override fun definition() = ModuleDefinition {
    Name("TorrentStream")
    Events("log", "progress", "ready", "error")
    current = this@TorrentStreamModule

    /** Take a private copy of a shared source; returns a path safe to keep. */
    AsyncFunction("importSource") { uri: String -> importSource(uri) }

    /** Resolve metadata and list what is inside the torrent. Blocking; runs off the UI thread. */
    AsyncFunction("inspect") { uri: String, timeoutSec: Int ->
      val ti = resolve(uri, timeoutSec)
      cachedUri = uri
      cached = ti

      val files = ti.files()
      val entries = (0 until files.numFiles()).map { i ->
        val path = files.filePath(i)
        val ext = path.substringAfterLast('.', "").lowercase()
        mapOf(
          "index" to i,
          "path" to path,
          "name" to files.fileName(i),
          "size" to files.fileSize(i),
          "isVideo" to (ext in VIDEO),
          "isSubtitle" to (ext in SUBTITLE)
        )
      }
      log("inspect: ${ti.name()} — ${entries.size} file(s)")
      mapOf("name" to ti.name(), "files" to entries)
    }

    AsyncFunction("play") { uri: String, index: Int, options: PlayOptions, extraFiles: String ->
      play(uri, index, options, extraFiles)
    }

    /**
     * Copy every wanted video that is complete on disk into Downloads.
     * Leaves the torrent running so later episodes can keep fetching.
     */
    AsyncFunction("saveReady") {
      downloads.values.flatMap { saveReadyFiles(it) }
    }

    AsyncFunction("pauseDownload") { id: String ->
      val dl = findDl(id) ?: return@AsyncFunction null
      if (dl.handle.isValid) {
        dl.handle.pause()
        dl.userPaused = true
        emitDlProgress(dl)
        if (downloads.values.none { !it.userPaused }) {
          watchTask(false)
          clearDownloadNotification()
        }
        log("paused download ${dl.hash}")
      }
      null
    }

    AsyncFunction("resumeDownload") { id: String ->
      val dl = findDl(id) ?: return@AsyncFunction null
      if (dl.handle.isValid) {
        dl.handle.resume()
        dl.userPaused = false
        emitDlProgress(dl)
        if (showNotify) {
          watchTask(true)
          updateDownloadNotification()
        }
        log("resumed download ${dl.hash}")
      }
      null
    }

    AsyncFunction("stopDownload") { id: String ->
      stopDownload(id)
      null
    }

    AsyncFunction("skipDownload") { id: String, index: Int ->
      val dl = findDl(id) ?: return@AsyncFunction null
      dl.wantedFiles = dl.wantedFiles.filter { it != index }.toIntArray()
      if (dl.handle.isValid) applyFocus(dl)
      persistWanted(dl.hash, dl.wantedFiles)
      log("skip download ${dl.hash} file $index")
      null
    }

    AsyncFunction("includeDownload") { id: String, index: Int ->
      val dl = findDl(id) ?: return@AsyncFunction null
      if (index !in dl.wantedFiles) dl.wantedFiles += index
      if (dl.handle.isValid) applyFocus(dl)
      persistWanted(dl.hash, dl.wantedFiles)
      if (dl.userPaused) {
        dl.handle.resume()
        dl.userPaused = false
      }
      log("include download ${dl.hash} file $index")
      null
    }

    /** `file://` for a finished episode: its Movies/Watchly copy, else the torrent cache. */
    AsyncFunction("fileUri") { index: Int, id: String ->
      val dl = if (id.isNotEmpty()) findDl(id) else null
      val ti = (if (id.isNotEmpty()) dl?.info else info)
        ?: throw CodedException("No torrent is running")
      val h = if (id.isNotEmpty()) dl?.handle else handle
      if (index !in 0 until ti.files().numFiles()) {
        throw CodedException("No file at index $index")
      }
      val rel = ti.files().filePath(index)
      val expected = ti.files().fileSize(index)
      // Saved in an earlier session means the cache copy is long gone.
      val folder = dl?.saveFolder ?: saveFolder
      if (destComplete(rel, expected, folder, ti.name())) {
        return@AsyncFunction "file://${downloadDest(rel, folder, ti.name()).absolutePath}"
      }
      // Hashed pieces, not File.length() — a sparse file is full-size from the first write.
      val src = File(saveDir, rel)
      if (!src.exists() || h == null || !h.isValid || !fileComplete(h, ti, index)) {
        throw CodedException("This episode is not ready to play yet")
      }
      "file://${src.absolutePath}"
    }

    /**
     * The playing file's finished pieces, as [from, to] spans of 0–1 along it —
     * the shaded stretches on the seek bar, as the Windows player draws them.
     * Empty when nothing is streaming (a file already on disk has no gaps).
     */
    AsyncFunction("downloadedSpans") {
      val none = emptyList<List<Double>>()
      val ti = info ?: return@AsyncFunction none
      val h = handle?.takeIf { it.isValid } ?: return@AsyncFunction none
      val index = fileIndex
      if (index < 0 || index >= ti.files().numFiles()) return@AsyncFunction none
      val size = ti.files().fileSize(index)
      if (size <= 0) return@AsyncFunction none
      runCatching {
        val first = ti.mapFile(index, 0, 1).piece()
        val last = ti.mapFile(index, size - 1, 1).piece()
        val count = (last - first + 1).toDouble()
        val spans = mutableListOf<List<Double>>()
        var start = -1
        for (p in first..last + 1) {
          val done = p <= last && h.havePiece(p)
          if (done && start < 0) {
            start = p
          } else if (!done && start >= 0) {
            spans.add(listOf((start - first) / count, (p - first) / count))
            start = -1
          }
        }
        spans
      }.getOrDefault(none)
    }

    /** Resolve a finished download for local playback. */
    AsyncFunction("downloadUri") { fileName: String ->
      findDownload(fileName)
        ?: throw CodedException("File not found in Downloads/$fileName")
    }

    /** Remove a Completed entry's file from Downloads. */
    AsyncFunction("deleteDownload") { fileName: String ->
      deleteDownloadFile(fileName)
      true
    }

    /**
     * Fetch an external subtitle and return a path the player can open.
     *
     * SubDL only ever serves zip archives and the JS side has no way to unpack
     * one, but `java.util.zip` has shipped with Android since the beginning —
     * so the download, the extract and the write all happen here. A plain
     * subtitle body is written straight out, so this works for any source.
     */
    AsyncFunction("saveSubtitle") { url: String, name: String ->
      val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "sub" }
      val dir = File(cacheRoot, "subs").apply { mkdirs() }
      // A host that accepts the connection and then goes quiet would otherwise
      // hold the subtitle spinner forever.
      val connection = URL(url).openConnection().apply {
        connectTimeout = 15_000
        readTimeout = 30_000
      }
      val bytes = connection.getInputStream().use { it.readCapped(MAX_SUBTITLE_DOWNLOAD) }
        ?: throw CodedException("That subtitle download is far too large to be one")
      if (bytes.size < 4) throw CodedException("Subtitle download was empty")

      // "PK": a zip. Anything else is the subtitle itself — named for what it
      // is, since libVLC picks the parser by extension.
      if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart('﻿')
        val ext = when {
          head.startsWith("WEBVTT") -> "vtt"
          head.contains("[Script Info]", ignoreCase = true) -> "ass"
          else -> "srt"
        }
        val dest = File(dir, "$safe.$ext")
        dest.writeBytes(bytes)
        return@AsyncFunction dest.absolutePath
      }

      ZipInputStream(bytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        var scanned = 0
        while (entry != null && scanned < MAX_ZIP_ENTRIES) {
          scanned++
          val current = entry!!
          val ext = current.name.substringAfterLast('.', "").lowercase()
          // Archives routinely carry a readme, an ad, and whatever else the
          // uploader felt like shipping. Only .srt is ever written out — same
          // rule as the torrent side, and for the same reason.
          //
          // Nothing from the archive reaches the filesystem but its bytes: the
          // destination is built from our own `safe` name, so an entry called
          // "../../databases/x" cannot escape the cache directory. Note this
          // is not a malware scan — it is a guarantee that what lands is a
          // text file, in app-private storage, that nothing ever executes.
          if (!current.isDirectory && ext in SUBTITLE) {
            // Read before writing, and capped: a zip bomb declares a few KB in
            // its header and unpacks to gigabytes.
            val body = zip.readCapped(MAX_SUBTITLE_BYTES)
              ?: throw CodedException("That subtitle unpacks to something far too big")
            val dest = File(dir, "$safe.$ext")
            dest.writeBytes(body)
            log("subtitle: ${current.name} → ${dest.name} (${body.size}B)")
            // Bare path — the player opens File(path); file:// broke attach.
            return@AsyncFunction dest.absolutePath
          }
          entry = zip.nextEntry
        }
      }
      throw CodedException("No subtitle file inside the archive")
    }

    /** Drop SubDL files when their library row goes. Paths outside subs/ are ignored. */
    AsyncFunction("deleteSubtitles") { paths: List<String> ->
      val dir = File(cacheRoot, "subs").absolutePath + File.separator
      for (path in paths) {
        val file = File(path)
        if (file.absolutePath.startsWith(dir)) file.delete()
      }
      null
    }

    AsyncFunction("pause") {
      val h = handle
      if (h != null && h.isValid) {
        h.pause()
        userPaused = true
        lastNotifPaused = null
        watchTask(false)
        clearDownloadNotification()
        emitPausedProgress(h)
        log("paused")
      }
      null
    }

    AsyncFunction("resume") {
      val h = handle
      if (h != null && h.isValid) {
        h.resume()
        userPaused = false
        lastNotifPct = -1
        lastNotifPaused = null
        emitPausedProgress(h)
        if (showNotify) {
          watchTask(true)
          updateDownloadNotification()
        }
        log("resumed")
      }
      null
    }

    /** The player's *Whole file* / *Next 5 min* switch; takes effect on the next tick. */
    AsyncFunction("setWholeFile") { on: Boolean ->
      wholeStream = on
      val h = handle
      if (h != null && h.isValid && !fullFile) {
        if (on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
      }
      // The mode lives in the piece priorities applyZone writes, so the next
      // tick has to rewrite them — re-prioritising the files instead, as this
      // used to, marked the whole file wanted and left it that way after the
      // switch went back to the window.
      zoneWidth = -1
      log("stream mode: ${if (on) "whole file, front to back" else "window around the reader"}")
      null
    }

    AsyncFunction("skipFile") { index: Int ->
      wantedFiles = wantedFiles.filter { it != index }.toIntArray()
      val h = handle
      if (h != null && h.isValid) applyFocus(h)
      persistWanted()
      log("skip file $index — still wanting ${wantedFiles.size}")
      null
    }

    /** Put a skipped file back into a season download. */
    AsyncFunction("includeFile") { index: Int ->
      if (index !in wantedFiles) wantedFiles += index
      val h = handle
      if (h != null && h.isValid) applyFocus(h)
      persistWanted()
      log("include file $index — wanting ${wantedFiles.size}")
      null
    }

    /** Toggle the ongoing download notification (Download Manager mode). */
    AsyncFunction("setNotify") { enabled: Boolean ->
      appContext.reactContext?.let { rememberCtx(it) }
      showNotify = enabled
      if (!enabled) {
        watchTask(false)
        clearDownloadNotification()
      } else {
        ensureNotifyChannel()
        lastNotifPct = -1
        lastNotifPaused = null
        lastNotifRate = -1
        watchTask(true)
        updateDownloadNotification()
      }
    }

    /**
     * Hand the running stream to another player — VLC, MX Player, whatever is
     * installed. They can reach the server because loopback is device-wide, not
     * per-app, and they get the same blocking range requests our own player
     * does. The torrent has to stay up while they read, so this deliberately
     * does not touch the session.
     *
     * `positionSeconds` carries the playback point across. The extra follows
     * VLC's convention; players that read `position` as an Int rather than a
     * Long (MX Player among them) will ignore it and start from the beginning.
     */
    AsyncFunction("openExternally") { url: String, positionSeconds: Double ->
      val context = appContext.reactContext!!
      val name = Uri.decode(Uri.parse(url).lastPathSegment ?: "")
      val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), contentType(name))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("title", name)
        // VideoLAN's documented contract for third-party launches: milliseconds,
        // as a Long. Without it the other player has no idea where we were and
        // opens at zero. `from_start` stops VLC preferring its own saved point
        // for a file it has seen before.
        if (positionSeconds > 0) {
          putExtra("position", (positionSeconds * 1000).toLong())
          putExtra("from_start", false)
        }
      }
      val chooser = Intent.createChooser(view, "Play with").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      try {
        context.startActivity(chooser)
      } catch (e: android.content.ActivityNotFoundException) {
        throw CodedException("No player on this device can open this stream.", e)
      }
      log("handed off to an external player")
    }

    AsyncFunction("stop") {
      playGen++
      if (downloads.isEmpty()) {
        showNotify = false
        watchTask(false)
        clearDownloadNotification()
      }
      userPaused = true
      forgetWanted(infoHashHex)
      val h = handle?.takeIf { it.isValid }
      handle = null
      if (h != null) {
        val hex = runCatching { h.infoHash().toHex() }.getOrNull()
        if (hex == null || !downloads.containsKey(hex)) {
          runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED) }
          runCatching { h.pause() }
          info?.let { ti ->
            runCatching {
              h.prioritizeFiles(Array(ti.files().numFiles()) { Priority.IGNORE })
            }
            runCatching {
              h.prioritizePieces(Array(ti.numPieces()) { Priority.IGNORE })
            }
          }
          runCatching { session.remove(h, SessionHandle.DELETE_FILES) }
          // The cached metadata may be a view onto this torrent's memory; kept,
          // the next open of the same title read it back as garbage — a file
          // list with no files and a name of random bytes, then a native crash.
          forgetMeta()
        }
      }
      teardown()
      if (downloads.isEmpty()) wipeStreamCache()
      log("stop: stream removed")
    }

    /** Wipe piece data under cache/torrents (not public Downloads or meta/peers). */
    AsyncFunction("clearCache") {
      if (handle != null || downloads.isNotEmpty()) return@AsyncFunction false
      wipeStreamCache()
      true
    }

    OnDestroy {
      showNotify = false
      clearDownloadNotification()
      watchTask(false)
      downloads.keys.toList().forEach { stopDownload(it) }
      teardown()
      // Force-kill / process death often skips JS stop — drop video pieces here.
      if (removeFilesAfterStop && downloads.isEmpty()) wipeStreamCache()
      persistSession()
      if (session.isRunning) session.stop()
    }
  }

  /* ------------------------------------------------------------- session */

  /** Surfaces libtorrent's own view of the network — the only way to tell a dead
   *  torrent from a blocked one. */
  private val alerts = object : AlertListener {
    private var peersSeen = 0

    /** null means "every alert" — filtering by swig ids here silently matches nothing. */
    override fun types(): IntArray? = null

    override fun alert(alert: Alert<*>) {
      when (alert.type()) {
        AlertType.PEER_CONNECT ->
          // One line per peer would drown the log once a swarm is found.
          if (++peersSeen <= 5) log("peer connected (#$peersSeen)")

        AlertType.DHT_BOOTSTRAP,
        AlertType.LISTEN_SUCCEEDED,
        AlertType.LISTEN_FAILED,
        AlertType.TRACKER_REPLY,
        AlertType.TRACKER_ERROR,
        AlertType.TRACKER_WARNING,
        AlertType.METADATA_RECEIVED,
        AlertType.TORRENT_ERROR,
        AlertType.PORTMAP_ERROR,
        // Invisible until now, and each can freeze a stream on its own: a piece
        // that never lands on disk, or one that keeps arriving corrupt.
        AlertType.FILE_ERROR,
        AlertType.HASH_FAILED,
        AlertType.PERFORMANCE -> log("libtorrent: ${alert.message()}")

        else -> Unit
      }
    }
  }

  private fun ensureSession() {
    if (session.isRunning) return

    val settings = SettingsPack()
      .connectionsLimit(200)
      .activeDhtLimit(88)
      .downloadRateLimit(0)
      .uploadRateLimit(0)
      // libtorrent defaults the mask to errors only, so tracker/peer/stats alerts
      // never arrive — and SessionManager.stats() stays at zero, since it is fed
      // by the stats alert. Curated like qBittorrent's: the *_log categories in
      // alert_category_t.all() would flood the bridge.
      .setInteger(settings_pack.int_types.alert_mask.swigValue(), ALERT_MASK)
      // A torrent with ten trackers announces only to the first working tier by
      // default; if that one is dead you wait out the announce interval.
      .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
      .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
      // Swarm ramp. Defaults dial 30 peers on the first tracker reply and 30 more
      // per second, which is why a cold start crawls from 20 peers to 130 over a
      // couple of minutes — most of the wait before the first frame is simply not
      // having enough peers yet. We usually have 175 cached endpoints and the
      // trackers return 200 more, so dial them all at once.
      .setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), 200)
      .setInteger(settings_pack.int_types.connection_speed.swigValue(), 200)
      // Half those cached endpoints are stale by the next session, and 15s each
      // is a long time to hold a slot open for a peer that is gone. Not much
      // below that though: a mobile handshake to a distant peer genuinely takes
      // a few seconds, and 5 was cutting off peers that would have connected.
      .setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 10)
      // The threshold at which a peer is fast enough to be handed a whole piece
      // at a time. It was 60s, for fewer scattered blocks — but on 4-8MB pieces
      // that let a 70kB/s peer book an entire piece, and when that piece became
      // the one the player was blocked on, every block was already requested
      // from it: libtorrent will not ask anyone else for a fully-requested
      // piece, so a swarm of fifty seeds sat idle while the film froze. Ten
      // seconds keeps whole pieces for peers that can actually deliver them.
      .setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 10)
      // request_timeout, piece_timeout and strict_end_game_mode are left at their
      // defaults on purpose. Shortening the first two churns peers off a mobile
      // link — libtorrent counts timeouts against a peer and disconnects it — and
      // relaxing end-game mode is actively harmful here: the wanted set is
      // already tiny because everything outside the zones is IGNORE, so allowing
      // early duplicate requests means many peers sending the same blocks and
      // almost no unique bytes arriving.
      // One fixed port, IPv4 and IPv6, which UPnP/NAT-PMP (below) forward on the router. Each
      // app its own: a router forwards a port to one device, and the phone, the TV (6881) and
      // the Windows app (51413) can all be running on the same network at once.
      .setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:6882,[::]:6882")
      .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), BOOTSTRAP)
      .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
      .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
      .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
      .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)

    // Warm start from the last DHT routing table when we have one.
    val params = try {
      if (sessionFile.exists()) {
        log("session: loading cached DHT state")
        SessionParams(sessionFile).also { it.setSettings(settings) }
      } else {
        SessionParams(settings)
      }
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
    try {
      val data = session.saveState() ?: return
      sessionFile.writeBytes(data)
      log("session: saved DHT state (${data.size} bytes)")
    } catch (e: Exception) {
      Log.w(TAG, "session save failed", e)
    }
  }

  /* -------------------------------------------------------------- import */

  /**
   * Copy a shared source into app storage and return a plain path.
   *
   * A `content://` URI is a permission grant attached to the intent that
   * delivered it, not a location. The grant is gone by the next launch — and
   * often sooner, since a dev client relaunches the app with the URI as a bare
   * string and the grant does not survive that — so anything that reads it more
   * than once, or resumes it from history later, fails with a SecurityException.
   * Read it exactly once, while it is still ours, and never look at it again.
   */
  private fun importSource(uri: String): String {
    if (!uri.startsWith("content://", true)) return uri

    val context = appContext.reactContext!!
    val parsed = Uri.parse(uri)
    val dest = File(importDir, "${uri.hashCode().toUInt().toString(16)}.torrent")
    if (dest.exists() && dest.length() > 0) {
      log("import: reusing ${dest.name}")
      return dest.absolutePath
    }

    // Free when the sending app offered a persistable grant; most do not.
    runCatching {
      context.contentResolver
        .takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
      context.contentResolver.openInputStream(parsed).use { input ->
        requireNotNull(input) { "nothing to read" }
        dest.outputStream().use { input.copyTo(it) }
      }
    } catch (e: SecurityException) {
      dest.delete()
      throw CodedException(
        "The app that shared this file did not grant access to it. Save the " +
          ".torrent to your device first, then open it from here.",
        e
      )
    } catch (e: Exception) {
      dest.delete()
      throw CodedException("Could not read the shared file: ${e.message}", e)
    }

    log("import: copied ${dest.length()} bytes to ${dest.name}")
    return dest.absolutePath
  }

  /* ------------------------------------------------------------ metadata */

  private fun resolve(uri: String, timeoutSec: Int): TorrentInfo {
    cached?.takeIf { cachedUri == uri }?.let {
      log("metadata: memory cache hit")
      // Re-add magnet to the session so play() can reuse it instead of calling
      // session.download(ti,…), which serialises the full TorrentInfo and OOMs
      // on large files.
      val clean = normalize(uri)
      if (clean.startsWith("magnet:", true)) {
        val hex = infoHashOf(clean)
        if (hex != null) {
          val hash = runCatching { Sha1Hash.parseHex(hex) }.getOrNull()
          if (hash != null && session.find(hash) == null) {
            ensureSession()
            log("metadata: re-adding magnet torrent for reuse")
            session.download(clean, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
          }
        }
      }
      return it
    }

    val clean = normalize(uri)

    // Disk cache — skip magnet fetch entirely on re-open.
    loadMeta(clean)?.let {
      cachedUri = uri
      cached = it
      return it
    }

    ensureSession()

    val (ti, bytes) = when {
      clean.startsWith("magnet:", true) -> {
        // Trackers answer without DHT. Waiting here delays the first announce
        // and is the longest stretch of the open-to-player spinner.
        val fetched = addMagnet(clean, timeoutSec)
        // This is the path that costs five to ten seconds of DHT and peer
        // chatter, and it used to pay that on every single open: metadata from
        // a magnet lives in memory the torrent owns, so stopping playback threw
        // it away with the torrent (see forgetMeta) and re-opening the same
        // release started from nothing again. Writing it back out as a .torrent
        // is what makes the second open a disk read.
        fetched to asTorrentFile(fetched)
      }

      // Anything still holding a content:// URI at this point never went through
      // importSource — take the copy now, and reuse it on the next call.
      clean.startsWith("content://", true) ->
        localTorrent(File(importSource(clean))) to ByteArray(0)

      clean.startsWith("http", true) -> {
        log("metadata: downloading .torrent")
        val data = downloadTorrent(clean)
        TorrentInfo.bdecode(data) to data
      }

      else -> {
        log("metadata: reading local .torrent")
        localTorrent(File(clean)) to ByteArray(0)
      }
    }

    // Keyed by the same function `loadMeta` looks it up with, not by the
    // torrent's own hash: on a hybrid v1+v2 torrent a magnet resolves to the v2
    // hash and `ti.infoHash()` is the v1 one, so the file would be written
    // where the reader never looks and the cache would silently never hit.
    if (bytes.isNotEmpty()) persistMeta(infoHashOf(clean) ?: ti.infoHash().toHex(), bytes)
    cachedUri = uri
    cached = ti
    return ti
  }

  /**
   * Add the magnet as a real torrent and wait for its metadata to arrive,
   * leaving it in the session.
   *
   * `fetchMagnet()` does its own add/remove: by the time it returns, every peer
   * it connected to while pulling the info dict has been disconnected, and
   * play() has to find the swarm all over again — which is most of the wait
   * before the first frame. Adding it once and keeping it means play() starts
   * with a warm set of connections.
   *
   * The cost is the on-disk metadata cache, which needs the raw .torrent bytes
   * this path never sees. The peer cache still shortcuts the next open, and the
   * info dict comes back from peers we are already talking to.
   */
  private fun addMagnet(magnet: String, timeoutSec: Int): TorrentInfo {
    val hex = infoHashOf(magnet)
      ?: throw CodedException("That magnet link could not be parsed")
    val hash = runCatching { Sha1Hash.parseHex(hex) }.getOrNull()
      ?: throw CodedException("That magnet link has an info-hash we cannot use")

    dropWarm(hex)

    val already = session.find(hash)
    if (already == null || !already.isValid) {
      log("metadata: adding magnet to the session (timeout ${timeoutSec}s)…")
      // Flag is irrelevant here — no pieces are picked while the info dict is
      // still arriving — and play() unsets it before any of them are. The
      // overload requires one.
      session.download(magnet, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
    } else {
      log("metadata: torrent already in the session")
    }

    val started = System.currentTimeMillis()
    repeat(timeoutSec * 4) {
      val h = session.find(hash)
      if (h != null && h.isValid && h.status().hasMetadata()) {
        val ti = runCatching { h.torrentFile() }.getOrNull()
        if (ti != null && ti.isValid) {
          warm = h
          // Magnet add wants every file until play() picks one. Sequential
          // then fetches from piece 0 — the start of a season pack — while
          // the user is still choosing an episode.
          val skip = Array(ti.files().numFiles()) { Priority.IGNORE }
          runCatching { h.prioritizeFiles(skip) }
          val status = h.status()
          log(
            "metadata: ready in ${System.currentTimeMillis() - started}ms, " +
              "${status.numPeers()} peer(s) already connected"
          )
          return ti
        }
      }
      Thread.sleep(250)
    }

    dropWarm(null)
    throw CodedException(
      "No peers answered within ${timeoutSec}s. The torrent may be dead, " +
        "or the network is blocking BitTorrent."
    )
  }

  /**
   * Drop the metadata cache.
   *
   * A TorrentInfo obtained from a handle — everything the magnet path returns —
   * is a view onto memory the *torrent* owns. Remove the torrent from the
   * session and that memory goes with it, leaving a Java object that still
   * answers questions with garbage: a piece length of zero (divide by zero in
   * play), or a file count large enough to OOM allocating the priority array.
   * So any removal invalidates it.
   */
  private fun forgetMeta() {
    cached = null
    cachedUri = null
  }

  /** Let go of a warm torrent that is not the one we are about to use. Never
   *  touches the torrent currently playing or an active download. */
  private fun dropWarm(keepHex: String?) {
    val w = warm ?: return
    val hex = runCatching { w.infoHash().toHex() }.getOrNull()
    if (hex != null && (hex == keepHex || hex == infoHashHex || downloads.containsKey(hex))) {
      if (hex != keepHex) warm = null
      return
    }
    // ponytail: leftover inspect torrents stay while anything is downloading
    if (downloads.isNotEmpty()) {
      warm = null
      return
    }
    if (w.isValid && hex != infoHashHex) {
      log("session: dropping warm torrent $hex")
      runCatching { session.remove(w) }
      forgetMeta()
    }
    warm = null
  }

  /**
   * Parse a `.torrent` sitting on disk.
   *
   * libtorrent reads the file itself, so a path that turns out to be something
   * else — the media file, say — cannot be pulled into a byte array first.
   * Doing that is an instant OOM: a 2.9GB allocation on a heap capped at a few
   * hundred MB, thrown from inside play() where it reads as an engine failure
   * rather than "you handed me the wrong file".
   */
  private fun localTorrent(file: File): TorrentInfo {
    if (!file.exists()) throw CodedException("There is no file at ${file.path}")
    if (file.length() > MAX_TORRENT_BYTES) {
      throw CodedException(
        "${file.name} is ${file.length() / 1024 / 1024}MB, so it is not a .torrent file — " +
          "a torrent describes the download, it does not contain it."
      )
    }
    return try {
      TorrentInfo(file)
    } catch (e: Exception) {
      throw CodedException("${file.name} could not be read as a torrent", e)
    }
  }

  /** Same ceiling for a remote .torrent, where the size is not known up front. */
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
      if (filled > limit) {
        throw CodedException("That URL is too large to be a .torrent file.")
      }
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
      log("metadata: bad cache file, ignoring (${e.message})")
      file.delete()
      null
    }
  }

  /**
   * Metadata that arrived over the wire, back in `.torrent` form.
   *
   * Empty on any failure — a v2 torrent whose piece layers never arrived cannot
   * be written out, and that is fine. This is a cache, so the cost of not
   * having it is the fetch we would have paid anyway.
   */
  private fun asTorrentFile(ti: TorrentInfo): ByteArray =
    runCatching {
      val params = AddTorrentParams(add_torrent_params())
      params.setTorrentInfo(ti)
      Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig()))
    }.getOrElse {
      log("metadata: not cacheable (${it.message})")
      ByteArray(0)
    }

  private fun persistMeta(hash: String, bytes: ByteArray) {
    try {
      val dest = File(metaDir, "$hash.torrent")
      if (!dest.exists()) {
        dest.writeBytes(bytes)
        log("metadata: cached $hash (${bytes.size} bytes)")
      }
    } catch (e: Exception) {
      Log.w(TAG, "metadata cache write failed", e)
    }
  }

  /** Best-effort info-hash from a magnet; null for .torrent paths/urls. */
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
    if (!file.exists()) return ArrayList()
    val out = ArrayList<TcpEndpoint>()
    try {
      file.readLines().forEach { line ->
        parseEndpoint(line.trim())?.let { out.add(it) }
      }
      if (out.isNotEmpty()) log("peers: loaded ${out.size} cached endpoint(s)")
    } catch (e: Exception) {
      Log.w(TAG, "peers load failed", e)
    }
    return out
  }

  private fun persistPeers() {
    val h = handle ?: return
    val hash = infoHashHex ?: return
    persistPeers(h, hash)
  }

  private fun persistPeers(h: TorrentHandle, hash: String) {
    if (!h.isValid) return
    try {
      @Suppress("UNCHECKED_CAST")
      val peers = h.peerInfo() as List<PeerInfo>
      val lines = peers.map { it.ip() }.filter { it.isNotBlank() }.distinct().take(MAX_CACHED_PEERS)
      if (lines.isEmpty()) return
      File(peersDir, "$hash.peers").writeText(lines.joinToString("\n"))
      log("peers: cached ${lines.size} endpoint(s)")
    } catch (e: Exception) {
      Log.w(TAG, "peers save failed", e)
    }
  }

  /** PeerInfo.ip() formats as tcp://host:port or tcp://[v6]:port. */
  private fun parseEndpoint(raw: String): TcpEndpoint? {
    if (raw.isEmpty()) return null
    val body = raw.removePrefix("tcp://")
    return try {
      if (body.startsWith("[")) {
        val end = body.indexOf(']')
        if (end < 0) return null
        val host = body.substring(1, end)
        val port = body.substring(end + 2).toInt()
        TcpEndpoint(host, port)
      } else {
        val idx = body.lastIndexOf(':')
        if (idx < 0) return null
        TcpEndpoint(body.substring(0, idx), body.substring(idx + 1).toInt())
      }
    } catch (_: Exception) {
      null
    }
  }

  /* ------------------------------------------------------------ playback */

  private val JUNK_FILE =
    Regex("""\b(sample|trailer|featurette|behind[. _-]?the[. _-]?scenes)\b""", RegexOption.IGNORE_CASE)
  private val EP_SEASON =
    Regex("""\bs(\d{1,2})(?=e\d{1,2}\b|\b)""", RegexOption.IGNORE_CASE)
  private val NAMED_SEASON =
    Regex("""\bseasons?[ ._-]*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

  private fun seasonsIn(hay: String): Set<Int> {
    val out = mutableSetOf<Int>()
    EP_SEASON.findAll(hay).forEach { it.groupValues[1].toIntOrNull()?.let(out::add) }
    NAMED_SEASON.findAll(hay).forEach { it.groupValues[1].toIntOrNull()?.let(out::add) }
    return out
  }

  /** Video indexes that belong to `season`, or every video if none are tagged. */
  private fun seasonVideos(ti: TorrentInfo, season: Int): List<Int> {
    val files = ti.files()
    val videos = (0 until files.numFiles()).filter { i ->
      val name = files.fileName(i)
      files.filePath(i).substringAfterLast('.').lowercase() in VIDEO &&
        !JUNK_FILE.containsMatchIn(name)
    }
    val hit = videos.filter { i ->
      season in seasonsIn(files.fileName(i)) || season in seasonsIn(files.filePath(i))
    }
    return hit.ifEmpty { videos }
  }

  private fun play(uri: String, index: Int, options: PlayOptions, extraFiles: String = ""): List<Int> {
    val download = options.fullFile
    val gen = if (download) playGen else ++playGen
    val startedAt = System.currentTimeMillis()
    ensureSession()

    // Resolve before tearing down, so teardown knows whether the torrent it is
    // about to drop is the one we are about to play.
    val ti = resolve(uri, 60)
    if (!download && gen != playGen) return emptyList()
    // Metadata that outlived its torrent answers with garbage rather than
    // failing, and the first thing to notice is a division a few lines down.
    // Say so here instead.
    if (!ti.isValid || ti.pieceLength() <= 0 || ti.numPieces() <= 0) {
      forgetMeta()
      throw CodedException("This torrent's metadata went stale. Open it again.")
    }
    val files = ti.files()
    if (index < 0 || index >= files.numFiles()) {
      throw CodedException("No file at index $index")
    }

    // Nothing can free the middle of a file once libtorrent has written it, so
    // a session keeps every piece it fetches until it stops. Check the room up
    // front instead of failing on a write half an hour in.
    val free = saveDir.usableSpace
    if (free < FREE_SPACE_MIN) {
      throw CodedException(
        "Only ${free / 1024 / 1024}MB free. Clear some space and try again."
      )
    }
    val fileBytes = files.fileSize(index)
    if (free < fileBytes) {
      log(
        "warning: ${free / 1024 / 1024}MB free for a ${fileBytes / 1024 / 1024}MB " +
          "file — playback will stop if you watch far enough to fill the disk"
      )
    }
    if (download) return playDownload(uri, ti, index, options, extraFiles)
    teardown(keep = ti.infoHash().toHex())
    if (gen != playGen) return emptyList()

    // Never less than a piece: nothing is readable until one completes.
    prepareBytes = maxOf(options.prepareMb.toLong() * 1024 * 1024, ti.pieceLength().toLong())
    // Bytes, with the option as an override rather than a floor.
    //
    // As a floor it was the reason playback stalled on a fast connection: a
    // complete-series pack has 8MB pieces, so a floor of 12 made the "32MB"
    // deadline window 96MB. Twelve pieces were rushed at once, the swarm split
    // itself across all of them, and the one piece the viewer was actually
    // waiting on arrived at a twelfth of the line rate — 2.5MB/s of traffic
    // moving the contiguous head forward at 0.8MB/s.
    windowPieces =
      if (options.windowPieces > 0) options.windowPieces
      else maxOf(2, (WINDOW_BYTES / ti.pieceLength()).toInt())
    removeFilesAfterStop = options.removeFilesAfterStop
    info = ti
    infoHashHex = ti.infoHash().toHex()
    fileIndex = index
    fullFile = options.fullFile
    saveFolder = options.saveFolder
    announcedReady = false
    peerSaveTick = 0
    savedFiles.clear()
    ticks = 0
    zoneStarts = emptyList()
    zoneWidth = -1
    zoneRushedTail = false
    zoneFront = -1
    lately.clear()
    blockingPiece = -1
    lastKickAt = 0L

    // Everything except the chosen file(s) is skipped entirely.
    val priorities = Array(files.numFiles()) { Priority.IGNORE }
    val extra = (extraFiles + "," + options.fileList)
      .split(',')
      .mapNotNull { it.trim().toIntOrNull() }
    val fromSeason =
      if (options.season >= 0) seasonVideos(ti, options.season) else emptyList()
    val videos = (listOf(index) + extra + fromSeason)
      .distinct()
      .filter { it in 0 until files.numFiles() }
    log(
      "play: wanting ${videos.size} video(s) [${videos.joinToString()}] " +
        "season=${options.season} extra='$extraFiles'"
    )
    videos.forEach { priorities[it] = Priority.DEFAULT }
    // Sidecar subs for *this* file only. A season pack can hold hundreds of
    // .srt/.ass files; attaching all of them makes VLC miss the matching one
    // and the picker overflow the screen.
    fun besideVideo(dir: String, i: Int): Boolean {
      val subDir = files.filePath(i).replace("\\", "/")
        .substringBeforeLast("/", "")
      return subDir == dir || (dir.isNotEmpty() && subDir.startsWith("$dir/"))
    }
    fun matchesStem(stem: String, i: Int): Boolean {
      val name = files.fileName(i).substringBeforeLast('.').lowercase()
      return name == stem || name.startsWith("$stem.")
    }
    val subHits = videos.flatMap { vid ->
      val stem = files.fileName(vid).substringBeforeLast('.').lowercase()
      val dir = files.filePath(vid).replace("\\", "/").substringBeforeLast("/", "")
      (0 until files.numFiles()).filter { i ->
        i !in videos &&
          files.filePath(i).substringAfterLast('.', "").lowercase() in SUBTITLE &&
          besideVideo(dir, i) &&
          matchesStem(stem, i)
      }
    }.distinct()
    subtitleFiles = subHits.map { File(saveDir, files.filePath(it)).absolutePath }
    wantedFiles = (videos + subHits).toIntArray()
    wantedFiles.forEach { priorities[it] = Priority.DEFAULT }
    persistWanted()
    adoptSaved(ti)
    if (subHits.isNotEmpty()) {
      log("play: found ${subHits.size} subtitle file(s) alongside the video")
    }

    log("play: ${files.fileName(index)} (${files.fileSize(index)} bytes)")

    // Adopt the torrent inspect() left running rather than adding it again —
    // re-adding drops every peer connection and starts the search from nothing.
    /**
     * Only reuse a torrent whose files are still on disk.
     *
     * Stopping a stream wipes the cache but the torrent can outlive it in the
     * session. Adopting that handle points libtorrent at a tree that is no
     * longer there: every read fails with "End of file", nothing is wanted, the
     * torrent reports FINISHED with zero bytes, and the film never starts. A
     * cold start costs the peer connections and is the only thing that works.
     */
    val onDisk = File(saveDir, files.filePath(index)).parentFile?.exists() == true
    val running = session.find(ti.infoHash())
      ?.takeIf { it.isValid && sameFiles(it, files.numFiles()) }
    val h = if (running != null) {
      log("play: reusing running torrent (${running.status().numPeers()} peer(s) connected)")
      /**
       * Files wiped from under a torrent the session still holds.
       *
       * Stopping a stream clears the cache but the torrent can outlive it, and
       * libtorrent goes on believing it has the pieces — every read then fails
       * with "End of file" and nothing is ever wanted. A recheck is what makes
       * it look again.
       *
       * Deliberately not a remove-and-re-add: `ti` here is backed by this very
       * torrent, so removing it frees the metadata still being held, and the
       * add that followed saw a torrent with no files at all
       * ("priorities count should be equals to the number of files").
       */
      if (!onDisk) {
        log("play: files are gone — rechecking")
        runCatching { running.forceRecheck() }
      }
      running.prioritizeFiles(priorities)
      running
    } else {
      val peers = loadPeers(infoHashHex!!)
      log("play: cold start, ${peers.size} cached peer(s)")
      // Dial immediately. DHT bootstraps in parallel; trackers do not need it.
      session.download(ti, saveDir, null, priorities, peers, TorrentFlags.SEQUENTIAL_DOWNLOAD)
      awaitHandle(ti) ?: throw CodedException("Torrent could not be added to the session")
    }
    // Sequential mode is the wrong tool for streaming, by libtorrent's own
    // account: it hands out pieces in index order regardless of peer speed, so
    // "piece 0 may be requested from a slow peer, and fast peers will get
    // requests for later-index pieces". Piece deadlines are the documented
    // alternative — they assign the most urgent pieces to the best peers — and
    // everything below drives them. Left on, sequential also makes the picker
    // treat any non-zero priority as equally eligible, so a piece near the start
    // of the file outranks the one the viewer is waiting on.
    // Sequential is right for a download, though: nobody is waiting on a
    // particular piece, and writing the file front to back keeps it playable
    // before it finishes. Likewise *Whole file* while streaming — the viewer
    // asked for the file, and the deadlines still carry the playhead.
    if (fullFile || wholeStream) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    handle = h
    warm = null

    // A torrent whose own trackers are dead still has to find a swarm somehow.
    val existing = runCatching { h.trackers().map { it.url() } }.getOrDefault(emptyList())
    log("trackers in torrent: ${existing.size}")
    TRACKERS.filterNot { it in existing }.forEach {
      runCatching { h.addTracker(AnnounceEntry(it)) }
    }
    h.forceReannounce()
    h.forceDHTAnnounce()

    // Byte offsets inside the file map onto a contiguous range of pieces.
    // A season download spans every selected video, so the sequential picker
    // walks that range instead of one episode.
    val size = files.fileSize(index)
    firstPiece = videos.minOf { ti.mapFile(it, 0, 1).piece() }
    lastPiece = videos.maxOf {
      val sz = files.fileSize(it)
      ti.mapFile(it, maxOf(0, sz - 1), 1).piece()
    }
    tailPiece =
      maxOf(firstPiece, ti.mapFile(index, maxOf(0, size - tailBytesFor(size)), 1).piece())
    headEndPiece = ti.mapFile(index, minOf(HEAD_BYTES, maxOf(0, size - 1)), 1).piece()

    // Resuming seeks the player straight to this point the moment it attaches,
    // so buffering from byte 0 onwards would be thrown away. The header at byte
    // 0 is still needed — that is handled separately, as its own pre-roll region.
    val startAt = (size * options.startRatio.coerceIn(0.0, 0.99)).toLong()
    startPiece = ti.mapFile(index, startAt, 1).piece()
    headPiece = startPiece

    log(
      "play: pieces $firstPiece..$lastPiece of ${ti.numPieces()}, header to $headEndPiece, " +
        "tail from $tailPiece, starting at $startPiece " +
        "— setup took ${System.currentTimeMillis() - startedAt}ms"
    )

    // Pin the picker to the current file's pieces — a season download
    // fetches one episode at a time, in order.
    applyFocus(h)

    readyAt = System.currentTimeMillis()
    startPolling()
    return videos
  }

  private fun findDl(id: String): Dl? {
    if (id.isEmpty()) return null
    downloads[id]?.let { return it }
    downloadByUri[id]?.let { downloads[it] }?.let { return it }
    return downloads.values.find { it.uri == id || it.hash.equals(id, ignoreCase = true) }
  }

  private fun stopDownload(id: String) {
    val dl = findDl(id) ?: return
    downloads.remove(dl.hash)
    downloadByUri.remove(dl.uri)
    forgetWanted(dl.hash)
    if (dl.handle.isValid) {
      runCatching { session.remove(dl.handle, SessionHandle.DELETE_FILES) }
      // Same as stop(): cached metadata may belong to the torrent just removed.
      forgetMeta()
    }
    log("stop download ${dl.hash}")
    if (downloads.isEmpty()) {
      if (handle == null) {
        poller?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        poller = null
        pollThread = null
        watchTask(false)
        showNotify = false
        clearDownloadNotification()
      }
    } else {
      updateDownloadNotification()
    }
  }

  private fun playDownload(
    uri: String,
    ti: TorrentInfo,
    index: Int,
    options: PlayOptions,
    extraFiles: String,
  ): List<Int> {
    val hash = ti.infoHash().toHex()
    val files = ti.files()
    val extra = (extraFiles + "," + options.fileList)
      .split(',')
      .mapNotNull { it.trim().toIntOrNull() }
    val fromSeason =
      if (options.season >= 0) seasonVideos(ti, options.season) else emptyList()
    val videos = (listOf(index) + extra + fromSeason)
      .distinct()
      .filter { it in 0 until files.numFiles() }
    log(
      "download: wanting ${videos.size} video(s) [${videos.joinToString()}] " +
        "season=${options.season} extra='$extraFiles'"
    )
    fun besideVideo(dir: String, i: Int): Boolean {
      val subDir = files.filePath(i).replace("\\", "/")
        .substringBeforeLast("/", "")
      return subDir == dir || (dir.isNotEmpty() && subDir.startsWith("$dir/"))
    }
    fun matchesStem(stem: String, i: Int): Boolean {
      val name = files.fileName(i).substringBeforeLast('.').lowercase()
      return name == stem || name.startsWith("$stem.")
    }
    val subHits = videos.flatMap { vid ->
      val stem = files.fileName(vid).substringBeforeLast('.').lowercase()
      val dir = files.filePath(vid).replace("\\", "/").substringBeforeLast("/", "")
      (0 until files.numFiles()).filter { i ->
        i !in videos &&
          files.filePath(i).substringAfterLast('.', "").lowercase() in SUBTITLE &&
          besideVideo(dir, i) &&
          matchesStem(stem, i)
      }
    }.distinct()
    val wanted = (videos + subHits).toIntArray()
    val priorities = Array(files.numFiles()) { Priority.IGNORE }
    wanted.forEach { if (it in priorities.indices) priorities[it] = Priority.DEFAULT }

    // Same guard as play(): a handle without its metadata has no files to
    // prioritise, and offering it a full array is refused outright.
    val running = session.find(ti.infoHash())
      ?.takeIf { it.isValid && sameFiles(it, files.numFiles()) }
    val h = if (running != null) {
      log("download: reusing running torrent (${running.status().numPeers()} peer(s))")
      running.prioritizeFiles(priorities)
      running
    } else {
      // No removal here either — see play(): `ti` belongs to whatever is in the
      // session, and taking that out from under it is what broke the add.
      val peers = loadPeers(hash)
      log("download: cold start, ${peers.size} cached peer(s)")
      session.download(ti, saveDir, null, priorities, peers, TorrentFlags.SEQUENTIAL_DOWNLOAD)
      awaitHandle(ti) ?: throw CodedException("Torrent could not be added to the session")
    }
    h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    val existing = runCatching { h.trackers().map { it.url() } }.getOrDefault(emptyList())
    TRACKERS.filterNot { it in existing }.forEach {
      runCatching { h.addTracker(AnnounceEntry(it)) }
    }
    h.forceReannounce()
    h.forceDHTAnnounce()

    val prev = downloads[hash]
    val dl = Dl(
      uri = uri,
      info = ti,
      handle = h,
      hash = hash,
      wantedFiles = wanted,
      fileIndex = index,
      saveFolder = options.saveFolder,
      savedFiles = prev?.savedFiles ?: ConcurrentHashMap.newKeySet(),
    )
    downloads[hash] = dl
    downloadByUri[uri] = hash
    persistWanted(hash, wanted)
    adoptSaved(dl)
    applyFocus(dl)
    startPolling()
    log("download: ${files.fileName(index)} — ${downloads.size} active")
    return videos
  }

  private fun isVideoFile(i: Int, files: org.libtorrent4j.FileStorage) =
    files.filePath(i).substringAfterLast('.', "").lowercase() in VIDEO

  /** S01E02 before S01E03, even when the torrent lists them out of order. */
  private fun episodeOrd(name: String, index: Int): Long {
    val m =
      Regex("""(?i)s(\d{1,2})[ ._-]*e(\d{1,2})""").find(name)
        ?: Regex("""(?i)\b(\d{1,2})x(\d{1,2})\b""").find(name)
    return if (m != null) m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong()
    else 1_000_000L + index
  }

  /** Pieces hashed on disk — not File.length(), which is full-size for sparse files. */
  private fun fileComplete(h: TorrentHandle, ti: TorrentInfo, index: Int): Boolean {
    val expected = ti.files().fileSize(index)
    if (expected <= 0) return false
    val progress = runCatching { h.fileProgress(TorrentHandle.PIECE_GRANULARITY) }.getOrNull()
    if (progress != null && index in progress.indices) {
      return progress[index] >= expected * 99L / 100L
    }
    val status = runCatching { h.status() }.getOrNull() ?: return false
    val wanted = status.totalWanted()
    return wanted > 0 && status.totalWantedDone() >= wanted * 99 / 100
  }

  /** Already copied into Movies/Watchly — do not fetch or copy again. */
  private fun destComplete(
    relPath: String,
    expected: Long,
    folder: String = saveFolder,
    torrentName: String = info?.name() ?: "",
  ): Boolean {
    if (expected <= 0) return false
    val dest = downloadDest(relPath, folder, torrentName)
    return dest.exists() && dest.length() >= expected * 99L / 100L
  }

  /**
   * The handle knows the same files we do.
   *
   * A magnet re-added for reuse carries no torrent_info until its metadata
   * arrives, so its file count is zero while our cached metadata says
   * otherwise. `prioritizeFiles` refuses that outright — "priorities count
   * should be equals to the number of files" — and the whole play call was
   * rejected with it. Anything that does not match is not worth adopting: a
   * cold start re-adds it with the metadata already in hand.
   */
  private fun sameFiles(h: TorrentHandle, expected: Int): Boolean =
    runCatching { h.torrentFile()?.files()?.numFiles() }.getOrNull() == expected

  private fun adoptSaved(ti: TorrentInfo) {
    if (!fullFile) return
    val files = ti.files()
    var n = 0
    for (i in wantedFiles) {
      if (!isVideoFile(i, files)) continue
      if (destComplete(files.filePath(i), files.fileSize(i))) {
        savedFiles.add(i)
        n++
      }
    }
    if (n > 0) log("play: $n file(s) already in Movies — skipping")
  }

  private fun adoptSaved(dl: Dl) {
    val files = dl.info.files()
    var n = 0
    for (i in dl.wantedFiles) {
      if (!isVideoFile(i, files)) continue
      if (destComplete(files.filePath(i), files.fileSize(i), dl.saveFolder, dl.info.name())) {
        dl.savedFiles.add(i)
        n++
      }
    }
    if (n > 0) log("download: $n file(s) already in Movies — skipping")
  }

  /** Next unfinished wanted video (+ its sidecar subs). One file at a time. */
  private fun sequentialFocus(): IntArray {
    val ti = info ?: return wantedFiles
    return sequentialFocus(ti, wantedFiles, savedFiles)
  }

  private fun sequentialFocus(dl: Dl): IntArray =
    sequentialFocus(dl.info, dl.wantedFiles, dl.savedFiles)

  private fun sequentialFocus(
    ti: TorrentInfo,
    wanted: IntArray,
    saved: Set<Int>,
  ): IntArray {
    val files = ti.files()
    val next = wanted
      .filter { i ->
        i in 0 until files.numFiles() && isVideoFile(i, files) && i !in saved
      }
      .minByOrNull { episodeOrd(files.fileName(it), it) } ?: return intArrayOf()
    val dir = files.filePath(next).replace("\\", "/").substringBeforeLast("/", "")
    val stem = files.fileName(next).substringBeforeLast('.').lowercase()
    val subs = wanted.filter { i ->
      i != next &&
        i in 0 until files.numFiles() &&
        files.filePath(i).substringAfterLast('.', "").lowercase() in SUBTITLE &&
        files.filePath(i).replace("\\", "/").substringBeforeLast("/", "") == dir &&
        files.fileName(i).substringBeforeLast('.').lowercase()
          .let { it == stem || it.startsWith("$stem.") }
    }
    return (listOf(next) + subs).toIntArray()
  }

  private fun applyFocus(h: TorrentHandle) {
    val ti = info ?: return
    wantOnly(h, ti, if (fullFile) sequentialFocus() else wantedFiles)
  }

  private fun applyFocus(dl: Dl) {
    wantOnly(dl.handle, dl.info, sequentialFocus(dl))
  }

  /**
   * Want these files and no others — both the file list and every piece.
   * `prioritizeFiles` does not reliably clear piece priorities left over from
   * inspect(), so a season-pack download would fetch the whole torrent.
   */
  private fun wantOnly(h: TorrentHandle, ti: TorrentInfo, filesWanted: IntArray) {
    val files = ti.files()
    val filePrio = Array(files.numFiles()) { Priority.IGNORE }
    for (i in filesWanted) if (i in filePrio.indices) filePrio[i] = Priority.DEFAULT
    runCatching { h.prioritizeFiles(filePrio) }

    val n = ti.numPieces()
    val piecePrio = Array(n) { Priority.IGNORE }
    var expect = 0L
    for (i in filesWanted) {
      if (i < 0 || i >= files.numFiles()) continue
      val size = files.fileSize(i)
      if (size <= 0) continue
      expect += size
      val a = ti.mapFile(i, 0, 1).piece()
      val b = ti.mapFile(i, size - 1, 1).piece()
      for (p in a..b) if (p in 0 until n) piecePrio[p] = Priority.DEFAULT
    }
    runCatching { h.prioritizePieces(piecePrio) }
    val wanted = runCatching { h.status().totalWanted() }.getOrNull()
    log(
      "want ${filesWanted.size} file(s), ${expect / 1024 / 1024}MB selected, " +
        "torrent ${ti.totalSize() / 1024 / 1024}MB, picker ${wanted?.let { "${it / 1024 / 1024}MB" } ?: "?"}"
    )
  }

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
        val live = handle?.isValid == true || downloads.isNotEmpty()
        if (!live) return
        handler.postDelayed(this, if (announcedReady || downloads.isNotEmpty()) 500 else 200)
      }
    }
    handler.post(tick)
  }

  private fun pumpDownload(dl: Dl) {
    if (!dl.announcedReady) {
      dl.announcedReady = true
      val name = dl.info.files().fileName(dl.fileIndex)
      log("download ready: $name")
      sendEvent(
        "ready",
        mapOf(
          "url" to "",
          "lanUrl" to null,
          "name" to name,
          "subtitleFiles" to emptyList<Map<String, Any>>(),
          "uri" to dl.uri,
          "id" to dl.hash,
        )
      )
    }
    if (ticks % 10 == 1 && !dl.userPaused) applyFocus(dl)
    if (ticks % 2 == 0) emitDlProgress(dl)
    if (ticks % 30 == 0) persistPeers(dl.handle, dl.hash)
  }

  private fun pump() {
    ticks++

    if (ticks % 20 == 0 && saveDir.usableSpace < FREE_SPACE_MIN) {
      log("out of space — stopping")
      sendEvent(
        "error",
        mapOf("message" to "Out of storage space. The stream has been stopped and its files cleared.")
      )
      downloads.keys.toList().forEach { stopDownload(it) }
      if (handle != null) {
        teardown()
        wipeStreamCache()
      }
      return
    }

    session.postSessionStats()
    for (dl in downloads.values.toList()) {
      if (!dl.handle.isValid) continue
      pumpDownload(dl)
    }

    val gen = playGen
    val h = handle ?: return
    val ti = info ?: return
    if (!h.isValid) return

    // A download wants every piece of the selected file, so none of the
    // windowing below applies: there is no reader to follow, the zone would
    // stay pinned at the head, and the swarm would saturate the link fetching a
    // few MB and then sit there while the file never finishes — high speed,
    // motionless progress. The file priorities set in play() already exclude
    // every other file in the torrent, so this stays one file either way.
    //
    // Still fire `ready` once so JS has the file name — downloads never open
    // the HTTP server, but the finish→copyTo path keys off that event.
    if (fullFile) {
      if (!announcedReady) {
        announcedReady = true
        val name = ti.files().fileName(fileIndex)
        log("download ready: $name")
        sendEvent(
          "ready",
          mapOf(
            "url" to "",
            "lanUrl" to null,
            "name" to name,
            "subtitleFiles" to emptyList<Map<String, Any>>()
          )
        )
      }
      // Inspect/magnet leftovers can creep back in; keep the picker on this file.
      if (ticks % 10 == 1) {
        if (gen != playGen) return
        val h2 = handle?.takeIf { it.isValid }
        if (h2 != null) applyFocus(h2)
      }
      session.postSessionStats()
      if (ticks % 2 == 0) reportProgress(h, 100)
      return
    }

    // One window per live reader, lowest offset first — that is the playback
    // connection, and it gets the tightest deadlines.
    //
    // Before the player attaches there are no readers, and two things it will
    // want the instant it does: the container header at byte 0, and the point it
    // resumes from. Buffering only the resume point leaves it stalling on the
    // header the moment it opens the file.
    // One region only when the resume point falls inside the header's own
    // pre-roll deadlines. Merging on the wider zone instead (it used to be
    // `windowPieces`) left the resume point in the zone but with no deadline —
    // on 4MB pieces the header is two pieces, the resume point three past it,
    // and the first frame waited 30s for a piece nobody was hurrying.
    val pieceLen = ti.pieceLength().toLong().coerceAtLeast(1L)
    val prerollPieces = maxOf(1, Math.ceil(prepareBytes.toDouble() / pieceLen).toInt())
    val preroll =
      if (startPiece - firstPiece <= prerollPieces) listOf(firstPiece)
      else listOf(firstPiece, startPiece)

    // A reader parked in the tail is the player reading the container index
    // (Matroska Cues, a non-faststart moov) the moment it opens the file, not
    // playback. Treating it as a target re-aims the whole zone at the end of
    // the file, throws away the pre-roll that was nearly complete, and costs a
    // second full reprioritise a breath later when it seeks back to byte zero.
    // The tail is already fetched and kept below, so nothing is lost by
    // ignoring it — unless the tail is genuinely all anyone is reading, which
    // is what watching the last minutes looks like.
    //
    // A reader that closed within READER_LINGER_MS still gets a zone. Some files
    // are read in two places at once — an AVI whose audio sits far from its
    // video — and the player gets there by closing one connection and opening
    // another, over and over. Counting only open sockets re-aimed every zone on
    // each hop and cleared every deadline, so neither place finished a piece.
    //
    // Open connections always come first, and a lingering one only adds a zone:
    // letting it take part in the tail rule above would hide an MKV's index read
    // (the header connection has just closed, so the tail reader looks like a
    // bystander). And rank 0 gets the tightest deadlines, which belong to the
    // connection playing now, not to the spot a seek just left.
    val now = System.currentTimeMillis()
    lately.entries.removeIf { now - it.value.second > READER_LINGER_MS }
    val live = readers.values.map { pieceAt(it) }.distinct().sorted()
    val open = live.filter { it < tailPiece }.ifEmpty { live }
    val closed = lately.values.map { pieceAt(it.first) }
      .filter { it < tailPiece && it !in open }.distinct().sorted()
    val targets = (open + closed).ifEmpty { preroll }
    headPiece = targets.first()

    val status = h.status()

    // Stuck-piece watchdog: the player is sitting on a piece that has not landed
    // in STUCK_PIECE_MS while peers are connected. Pausing and resuming drops
    // every outstanding request and re-dials the swarm from the peer cache, so
    // the piece is asked for afresh from whoever answers first. The deadlines
    // are set again next tick — the pause clears libtorrent's list of them.
    val blocked = if (announcedReady && !userPaused) {
      targets.first().takeIf { it in firstPiece..lastPiece && !h.havePiece(it) } ?: -1
    } else {
      -1
    }
    if (blocked != blockingPiece) {
      blockingPiece = blocked
      blockingSince = now
    } else if (
      blocked >= 0 &&
      status.numPeers() > 0 &&
      now - blockingSince > STUCK_PIECE_MS &&
      now - lastKickAt > STUCK_PIECE_MS
    ) {
      lastKickAt = now
      log(
        "stuck: piece $blocked for ${(now - blockingSince) / 1000}s " +
          "(${pieceState(h, blocked)}, ${status.downloadRate() / 1024}kB/s, " +
          "${status.numPeers()} peers) — re-asking the swarm"
      )
      persistPeers()
      runCatching { h.pause() }
      runCatching { h.resume() }
      runCatching { h.clearPieceDeadlines() }
      deadlined.clear()
      blockingSince = now
    }

    // Two widths, and the difference matters.
    //
    // `reach` is the deadline window: the pieces the swarm is told to rush.
    // Narrow before playback starts, so everything converges on what unblocks
    // the first frame instead of spreading over tens of MB of readahead.
    // After start, a slow swarm stays tight (finish the next piece) and a
    // fast one opens to the full WINDOW_BYTES so playback never catches up.
    // After start, as many pieces as the swarm finishes in REACH_SECONDS: rushing
    // more than that splits the peers across pieces that all land late, so the
    // one the player is blocked on arrives at a single peer's speed. The old
    // cut-over at 400kB/s jumped straight to the full window, which on 4MB
    // pieces was eight at once at 500kB/s — the next piece took a minute.
    val reach = if (!announcedReady) {
      prerollPieces
    } else {
      val affordable = (status.downloadRate().toLong() * REACH_SECONDS / pieceLen).toInt()
      affordable.coerceIn(2, maxOf(2, windowPieces))
    }

    // `width` is the zone: everything the torrent is *allowed* to fetch. It
    // stays wide even during pre-roll. Narrowing it to `reach` left about ten
    // pieces wanted in the whole torrent, so every peer held the same few
    // blocks — permanent end-game, a swarm full of seeds, and almost no unique
    // bytes arriving. Priority still puts the urgent pieces first; the rest of
    // the zone just keeps the peers doing something useful.
    // Two pieces is the floor purely so the arithmetic cannot produce a zone
    // narrower than the reader plus its next step.
    val width = maxOf(2, (READAHEAD_BYTES / pieceLen).toInt())

    /** How far behind each reader stays wanted. See REWIND_BYTES. */
    val back = maxOf(8, (REWIND_BYTES / pieceLen).toInt())

    // Re-aim the zones when a reader leaves the half of one it was given (a
    // seek), when their number changes, or when the pre-roll ends and they
    // should open up. Before the window loop, because it wipes the piece
    // priorities the loop sets — and with hysteresis, because a full
    // reprioritise every tick would cost more than it buys.
    // `t >= it - back`: a rewind inside the band it already covers is not a new
    // zone. Without that tolerance the cheapest seek in the app was also the
    // one that threw away every deadline in the torrent.
    //
    // *Whole file* adds a front band — see applyZone — which moves on once the
    // first missing piece is half a band past where it was last aimed, not on
    // every piece that lands.
    val front = if (wholeStream) {
      var p = maxOf(firstPiece, zoneFront)
      while (p <= lastPiece && h.havePiece(p)) p++
      p
    } else {
      -1
    }
    val stale = zoneWidth != width ||
      zoneRushedTail != announcedReady ||
      wholeStream != (zoneFront >= 0) ||
      (wholeStream && front > zoneFront + width / 2) ||
      zoneStarts.size != targets.size ||
      targets.any { t -> zoneStarts.none { t >= it - back && t <= it + width / 2 } }
    if (stale) applyZone(h, targets, width, back, rushTail = announcedReady, front = front)

    val keep = mutableSetOf<Int>()
    targets.forEachIndexed { rank, target ->
      val end = minOf(target + reach, lastPiece)
      for (p in target..end) {
        keep.add(p)
        if (h.havePiece(p) || !deadlined.add(p)) continue

        h.piecePriority(p, Priority.TOP_PRIORITY)
        // Every piece in the window gets one. This is libtorrent's time-critical
        // path, and it is the whole ordering mechanism now that sequential mode
        // is off: it sorts by deadline and gives the most urgent pieces the best
        // queue slots across all peers. Deadlines are absolute once set, so the
        // stagger keeps its order even after they all fall into the past —
        // which is why they are set once and never refreshed.
        h.setPieceDeadline(p, rank * 3000 + (p - target) * 300)
      }
    }
    // During preroll, only the first ~2MB of the index — enough for VLC to
    // parse Cues/moov — so the rest of a 16MB tail does not steal the opening
    // piece. After ready, the full tail stays wanted at a distant deadline.
    val tailEnd = if (announcedReady) lastPiece
      else minOf(lastPiece, tailPiece + maxOf(0, (2L * 1024 * 1024 / pieceLen).toInt() - 1))
    for (p in tailPiece..tailEnd) {
      keep.add(p)
      if (!h.havePiece(p) && deadlined.add(p)) {
        h.setPieceDeadline(p, 10_000 + (p - tailPiece) * 300)
      }
    }
    // Pieces no reader is heading for: stop paying for their urgency.
    deadlined.removeAll { piece ->
      (piece !in keep).also { if (it) runCatching { h.resetPieceDeadline(piece) } }
    }

    // First complete piece of the header (and of the resume point) is enough
    // to hand the URL over. The HTTP server blocks if the demuxer asks for
    // more; waiting for 2MB of header here just delayed first frame.
    val headNeed = pieceLen
    val headHave = contiguousFrom(h, ti, firstPiece)
    val startHave =
      if (startPiece == firstPiece) headHave else contiguousFrom(h, ti, startPiece)
    val startNeed = prerollNeed(
      fileSize = ti.files().fileSize(fileIndex),
      rate = status.downloadRate().toLong(),
      pieceLen = pieceLen,
      ceiling = prepareBytes,
    )

    val buffered = minOf(startHave, headHave)
    val ready = headHave >= headNeed && startHave >= startNeed

    // stats() only advances when session stats alerts are posted.
    session.postSessionStats()

    if (ticks % 2 == 0) {
      reportProgress(h, ((buffered * 100 / maxOf(1L, startNeed)).coerceAtMost(100L)).toInt())
    }

    // Refresh the peer cache every ~15s once anyone is connected.
    if (status.numPeers() > 0 && ++peerSaveTick % 30 == 0) {
      persistPeers()
      log(
        "rate ${status.downloadRate() / 1024}kB/s, ${status.numPeers()} peers " +
          "(${status.numSeeds()} seeds), readers ${targets.joinToString("/")}, " +
          "reach $reach, ${deadlined.size} deadlined, " +
          "head ${headHave / 1024 / 1024}MB start ${startHave / 1024 / 1024}MB" +
          if (blockingPiece >= 0) {
            ", waiting on $blockingPiece for ${(now - blockingSince) / 1000}s " +
              "(${pieceState(h, blockingPiece)})"
          } else {
            ""
          }
      )
    }

    if (!announcedReady && ready) {
      announcedReady = true
      val path = File(saveDir, ti.files().filePath(fileIndex))
      val size = ti.files().fileSize(fileIndex)

      // Served over HTTP rather than as file:// — the player must block on data
      // that has not arrived instead of reading zeros out of the sparse file.
      val srv = LocalFileServer(
        file = path,
        fileSize = size,
        available = { offset -> handle?.havePiece(pieceAt(offset)) == true },
        request = { id, offset -> readers[id] = offset },
        release = { id ->
          readers.remove(id)?.let { at -> lately[id] = at to System.currentTimeMillis() }
        }
      )
      srv.start()
      server = srv

      log(
        "ready in ${(System.currentTimeMillis() - readyAt) / 1000}s: " +
          "header ${headHave / 1024 / 1024}MB, start ${startHave / 1024 / 1024}MB " +
          "(needed ${startNeed / 1024}kB) at piece " +
          "$startPiece, at ${status.downloadRate() / 1024}kB/s (${status.numPeers()} peers) " +
          "-> ${srv.url}"
      )
      sendEvent("ready", mapOf(
        "url" to srv.url,
        // Same stream, addressed for the network — a Cast receiver fetches it
        // itself and cannot reach loopback. Null off Wi-Fi.
        "lanUrl" to srv.lanUrl,
        "name" to ti.files().fileName(fileIndex),
        "subtitleFiles" to subtitleFiles.also {
          if (it.isNotEmpty()) log("ready: including ${it.size} subtitle file(s)")
        }
      ))
    }
  }

  /**
   * Point the download at where the readers actually are.
   *
   * One zone per reader, each `width` pieces of it, plus a `back` band behind
   * it at LOW and the tail at DEFAULT. Without this
   * the picker is free to work anywhere in the file, and a seek leaves the
   * swarm grinding through everything between the old position and the target
   * while the viewer waits.
   *
   * Pieces outside the selected file keep whatever the file priorities gave
   * them — only this file's range is touched.
   */
  private fun applyZone(
    h: TorrentHandle,
    targets: List<Int>,
    width: Int,
    back: Int,
    rushTail: Boolean,
    /** First piece of the file not on disk, or -1 outside *Whole file*. */
    front: Int,
  ) {
    val prios = runCatching { h.piecePriorities() }.getOrNull() ?: return
    val zones = targets.map { it..minOf(it + width, lastPiece) }
    // *Whole file*: one zone's width from the first missing piece, at LOW, and
    // nothing past it. Lifting the whole rest of the file to LOW instead left
    // the order to libtorrent, which picks rarest-first whenever it is not in
    // sequential mode — the film filled in as scattered specks. A band that
    // only moves on once its start has landed makes it grow front to back
    // whatever order the picker takes inside it.
    val band = if (front in firstPiece..lastPiece) front..minOf(front + width, lastPiece)
      else IntRange.EMPTY
    // Behind each reader, kept only at LOW — a rewind of a few seconds should
    // find the data already there, but never at the cost of the pieces the
    // viewer is about to reach.
    val behind = targets.mapNotNull {
      if (it > firstPiece) maxOf(firstPiece, it - back)..(it - 1) else null
    }
    // A band of spare work just past each zone, kept at LOW.
    //
    // It exists for two reasons. Spare bandwidth goes to the data the viewer is
    // heading towards rather than nowhere. And more importantly it stops the
    // wanted set from ever being fully satisfied: a torrent that completes
    // everything it wants goes to "finished" and drops its seeds, so pausing
    // playback long enough to fill the window would disconnect the swarm and
    // stall on resume.
    val spare = targets.map { (it + width + 1)..minOf(it + width + width / 2, lastPiece) }
    val pieceLen = (info?.pieceLength() ?: 1).toLong().coerceAtLeast(1L)
    val tailEnd = if (rushTail) lastPiece
      else minOf(lastPiece, tailPiece + maxOf(0, (2L * 1024 * 1024 / pieceLen).toInt() - 1))

    for (p in firstPiece..lastPiece) {
      if (p >= prios.size) break
      // Nothing outside these gets IGNORE rather than LOW. With sequential mode
      // off, priority ordering is honoured, so LOW would mostly work — but zero
      // is the only value that takes a piece out of the running entirely, and
      // the whole point is that the swarm has exactly one job.
      prios[p] = when {
        p in tailPiece..tailEnd || zones.any { p in it } -> Priority.DEFAULT
        spare.any { p in it } || behind.any { p in it } || p in band -> Priority.LOW
        else -> Priority.IGNORE
      }
    }
    runCatching { h.prioritizePieces(prios) }

    // Deadlines set for the old zones are aimed at pieces nobody is waiting for.
    runCatching { h.clearPieceDeadlines() }
    deadlined.clear()
    zoneStarts = targets
    zoneWidth = width
    zoneRushedTail = rushTail
    zoneFront = front
    log(
      "zone: ${zones.joinToString(", ") { "${it.first}..${it.last}" }} " +
        "(back $back, tail $tailPiece..$tailEnd" +
        "${if (front >= 0) ", whole-file front $front" else ""}) of $firstPiece..$lastPiece"
    )
  }

  /** Bytes available in an unbroken run starting at `from`. */
  private fun contiguousFrom(h: TorrentHandle, ti: TorrentInfo, from: Int): Long {
    var have = 0L
    var p = from
    while (p <= lastPiece && h.havePiece(p)) {
      have += ti.pieceLength()
      p++
    }
    return have
  }

  /**
   * The one progress event. `buffer` is how much of the pre-roll has landed —
   * meaningless for a download, which reports it full so the UI never shows a
   * transfer waiting on a buffer nobody is reading.
   *
   * Downloads use totalWanted/Done: whole-torrent progress never reaches 100%
   * when other files in the pack are ignored, so the finish step never ran.
   */
  private fun reportProgress(h: TorrentHandle, buffer: Int) {
    val status = h.status()
    sendEvent(
      "progress",
      mapOf(
        "buffer" to buffer,
        "progress" to downloadProgress(status),
        "speed" to if (userPaused) 0 else status.downloadRate(),
        "seeds" to status.numSeeds(),
        "peers" to status.numPeers(),
        "dhtNodes" to session.stats().dhtNodes().toInt(),
        "state" to status.state().name,
        "paused" to userPaused
      )
    )
    updateDownloadNotification()
  }

  /** 0–100. For a full-file download, only the pieces we asked for. */
  private fun downloadProgress(status: org.libtorrent4j.TorrentStatus, wantedOnly: Boolean = fullFile): Float {
    if (wantedOnly) {
      val wanted = status.totalWanted()
      if (wanted <= 0) return 0f
      return (status.totalWantedDone().toDouble() / wanted * 100.0)
        .toFloat()
        .coerceIn(0f, 100f)
    }
    return (status.progress() * 100f).coerceIn(0f, 100f)
  }

  private fun emitDlProgress(dl: Dl) {
    if (!dl.handle.isValid) return
    val status = dl.handle.status()
    sendEvent(
      "progress",
      mapOf(
        "buffer" to 100,
        "progress" to downloadProgress(status, true),
        "speed" to if (dl.userPaused) 0 else status.downloadRate(),
        "seeds" to status.numSeeds(),
        "peers" to status.numPeers(),
        "dhtNodes" to session.stats().dhtNodes().toInt(),
        "state" to status.state().name,
        "paused" to dl.userPaused,
        "uri" to dl.uri,
        "id" to dl.hash,
      )
    )
    updateDownloadNotification()
  }

  /** Piece holding the given byte offset *within the selected file*. */
  /** A partial piece's blocks, for the stall log: who is holding it up. */
  private fun pieceState(h: TorrentHandle, piece: Int): String {
    val q = runCatching { h.downloadQueue }.getOrNull()
      ?.firstOrNull { it.pieceIndex() == piece }
      ?: return "nothing requested yet"
    return "${q.finished()} done, ${q.writing()} writing, ${q.requested()} requested " +
      "of ${q.blocksInPiece()} blocks"
  }

  private fun pieceAt(offset: Long): Int {
    val ti = info ?: return firstPiece
    return ti.mapFile(fileIndex, offset.coerceAtLeast(0), 1).piece()
  }

  /* -------------------------------------------------------------- teardown */

  /**
   * @param keep info-hash of a torrent about to be played again — switching
   *   files inside one torrent, or picking up what inspect() started. Removing
   *   and re-adding it would throw away the peers and the pieces already on disk.
   */
  private fun teardown(keep: String? = null) {
    persistPeers()
    persistSession()
    if (downloads.isEmpty()) {
      clearDownloadNotification()
      userPaused = false
      lastNotifPct = -1
      lastNotifPaused = null
      lastNotifRate = -1
    }

    server?.stop()
    server = null
    if (downloads.isEmpty()) {
      poller?.removeCallbacksAndMessages(null)
      pollThread?.quitSafely()
      poller = null
      pollThread = null
    }

    val keeping = keep != null && keep == infoHashHex
    val keepDownload = infoHashHex != null && downloads.containsKey(infoHashHex)

    /**
     * Read the paths before the torrent leaves the session.
     *
     * `session.remove` frees the native torrent_info that this `TorrentInfo`
     * wraps, so asking it for a file path afterwards is a use-after-free — and
     * it does not throw, it takes the whole process down inside
     * `file_storage::file_path`. Strings survive the removal; the object does
     * not. Same list as before, gathered a few lines earlier.
     */
    val doomed: List<String> =
      if (removeFilesAfterStop && !keeping && !keepDownload && keep != null) {
        info?.let { old ->
          runCatching {
            val files = old.files()
            listOf(old.name()) +
              (0 until files.numFiles()).map { files.filePath(it) }
          }.getOrDefault(emptyList())
        } ?: emptyList()
      } else {
        emptyList()
      }

    handle?.let { h ->
      if (h.isValid) {
        h.clearPieceDeadlines()
        if (!keeping && !keepDownload) {
          session.remove(h)
          forgetMeta()
        }
      }
    }
    dropWarm(keep)

    // play() can already have the *next* torrent writing into saveDir, so when
    // keep != null only delete the outgoing torrent's tree. Full stop wipes all
    // piece data — old.name() alone left multi-file / renamed torrents behind
    // and grew the app to gigabytes.
    if (removeFilesAfterStop && !keeping && !keepDownload) {
      if (keep == null) {
        if (downloads.isEmpty()) wipeStreamCache()
      } else if (doomed.isNotEmpty()) {
        // Plain strings now — nothing here touches libtorrent.
        val name = doomed.first()
        File(saveDir, name).deleteRecursively()
        doomed.drop(1).forEach { File(saveDir, it).delete() }
        log("cleaned $name")
      }
    }

    deadlined.clear()
    readers.clear()
    lately.clear()
    zoneStarts = emptyList()
    zoneWidth = -1
    zoneRushedTail = false
    zoneFront = -1
    blockingPiece = -1
    subtitleFiles = emptyList()
    wantedFiles = intArrayOf()
    savedFiles.clear()
    handle = null
    info = null
    infoHashHex = null
    fileIndex = -1
    announcedReady = false
    peerSaveTick = 0
    fullFile = false
    // Not wholeStream: it is the viewer's setting, and the player may already
    // have sent it for the stream this teardown is making way for.
    if (downloads.isNotEmpty() && poller == null) startPolling()
  }

  /* ---------------------------------------------------------------- utils */

  /** Delete streamed/downloaded piece files. Meta + peer cache stays (tiny). */
  private fun wipeStreamCache() {
    // Anything an older build left in cacheDir is stranded otherwise — it can be
    // several GB, and nothing writes there any more to notice it.
    legacySaveDir.takeIf { it.exists() }?.deleteRecursively()

    val dir = saveDir
    if (!dir.exists()) return
    val ok = dir.deleteRecursively()
    dir.mkdirs()
    log(if (ok) "wiped stream cache" else "stream cache wipe incomplete")
  }

  /** jlibtorrent takes magnet:/http: URLs or a bare filesystem path, not file:// */
  private fun normalize(uri: String) =
    if (uri.startsWith("file://")) Uri.parse(uri).path ?: uri else uri

  private fun log(message: String) {
    Log.i(TAG, message)
    sendEvent("log", mapOf("message" to message))
  }

  /* ---------------------------------------------------------- notifications */

  private var wakeLock: PowerManager.WakeLock? = null

  private fun watchTask(on: Boolean) {
    val ctx = appContext.reactContext ?: appCtx ?: return
    rememberCtx(ctx)
    holdWake(on)
    val intent = Intent(ctx, DownloadTaskWatch::class.java)
    runCatching {
      if (on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          ctx.startForegroundService(intent)
        } else {
          ctx.startService(intent)
        }
      } else {
        ctx.stopService(intent)
      }
    }
  }

  private fun holdWake(on: Boolean) {
    if (!on) {
      wakeLock?.let { if (it.isHeld) it.release() }
      wakeLock = null
      return
    }
    if (wakeLock?.isHeld == true) return
    val ctx = appCtx ?: appContext.reactContext ?: return
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchly:download").apply {
      setReferenceCounted(false)
      acquire()
    }
  }

  private fun rememberCtx(ctx: Context) {
    appCtx = ctx.applicationContext
  }

  private fun notifyManager(): NotificationManager? {
    val ctx = appContext.reactContext ?: appCtx ?: return null
    rememberCtx(ctx)
    return ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  }

  private fun ensureNotifyChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val ctx = appContext.reactContext ?: appCtx ?: return
    rememberCtx(ctx)
    val nm = notifyManager() ?: return
    if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
    nm.createNotificationChannel(
      NotificationChannel(
        NOTIF_CHANNEL,
        "Downloads",
        NotificationManager.IMPORTANCE_DEFAULT
      ).apply {
        description = "Torrent download progress"
        setShowBadge(false)
      }
    )
  }

  private fun updateDownloadNotification() {
    if (!showNotify) return
    val running = downloads.values.filter { !it.userPaused }
    if (running.isEmpty()) return
    val ctx = appContext.reactContext ?: appCtx ?: return
    rememberCtx(ctx)
    var pct = 0
    var rate = 0
    var leftBytes = 0L
    for (dl in running) {
      if (!dl.handle.isValid) continue
      val status = dl.handle.status()
      pct += downloadProgress(status, true).toInt().coerceIn(0, 100)
      rate += status.downloadRate()
      if (status.totalWanted() > 0) {
        leftBytes += (status.totalWanted() - status.totalWantedDone()).coerceAtLeast(0)
      }
    }
    pct /= running.size.coerceAtLeast(1)
    val name =
      if (downloads.size > 1) "${downloads.size} downloads"
      else running.first().info.name()
    val paused = false
    val rateKey = rate / 25_000
    if (pct == lastNotifPct && lastNotifPaused == paused && rateKey == lastNotifRate) return
    lastNotifPct = pct
    lastNotifPaused = paused
    lastNotifRate = rateKey

    val speedText = formatRate(rate)
    val etaText = formatEta(leftBytes, rate)

    ensureNotifyChannel()
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pi = PendingIntent.getActivity(
      ctx,
      0,
      launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val title = "Downloading · $pct%"
    val text = "$speedText · $etaText\n$name"
    val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText("$speedText · $etaText")
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setProgress(100, pct, pct <= 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(pi)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .build()
    val nm = notifyManager() ?: return
    nm.notify(NOTIF_ID, notif)
    DownloadTaskWatch.promote(ctx, notif)
  }

  private fun formatRate(bytesPerSec: Int): String {
    if (bytesPerSec <= 0) return "—/s"
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    if (bytesPerSec < 1024 * 1024) return "${bytesPerSec / 1024} KB/s"
    return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
  }

  private fun formatEta(remainingBytes: Long, bytesPerSec: Int): String {
    if (bytesPerSec <= 0 || remainingBytes <= 0) return "— left"
    val secs = (remainingBytes / bytesPerSec).toInt()
    if (secs < 60) return "${secs}s left"
    if (secs < 3600) return "${secs / 60}m left"
    return "${secs / 3600}h ${(secs % 3600) / 60}m left"
  }

  private fun clearDownloadNotification() {
    val nm = notifyManager() ?: return
    nm.cancel(NOTIF_ID)
    nm.cancel(NOTIF_ID + 1)
  }

  /** Public Movies/Watchly — visible in the system Files app. */
  private fun watchlyDir(): File {
    val pub = File(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
      "Watchly"
    )
    try {
      pub.mkdirs()
      if (pub.exists() && pub.canWrite()) return pub
    } catch (_: Exception) {}
    val ctx = appContext.reactContext!!
    return File(
      ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir,
      "Watchly"
    ).apply { mkdirs() }
  }

  /** Movies/Watchly plus leftover Downloads/Watchly from earlier builds. */
  private fun watchlyRoots(): List<File> {
    val old = File(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
      "Watchly"
    )
    return listOf(watchlyDir(), old)
  }

  private fun safeName(name: String) =
    name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().ifBlank { "download" }

  /**
   * Movies/Watchly/<show>/<torrent folders>/file.mkv
   * Keeps the torrent's own folders (minus a duplicate torrent-name root).
   * Does not invent a Season N folder.
   */
  private fun downloadDest(
    relPath: String,
    folder: String = saveFolder,
    torrentName: String = info?.name() ?: "",
  ): File {
    val parts = relPath.replace('\\', '/').split('/').filter { it.isNotBlank() }
      .map { safeName(it) }
    val torrentRoot = safeName(torrentName)
    val rest =
      if (parts.size > 1 && parts.first().equals(torrentRoot, ignoreCase = true)) {
        parts.drop(1)
      } else {
        parts
      }
    val show = safeName(folder.ifBlank { torrentRoot.ifBlank { "Download" } })
    return (listOf(show) + rest).fold(watchlyDir()) { dir, part -> File(dir, part) }
  }

  /**
   * Copy one finished file into Downloads without removing the torrent cache
   * copy — libtorrent still needs that path for the rest of the season.
   */
  private fun copyKeep(
    src: File,
    name: String,
    folder: String = saveFolder,
    torrentName: String = info?.name() ?: "",
  ): String {
    val dest = downloadDest(name, folder, torrentName)
    dest.parentFile?.mkdirs()
    if (dest.exists() && dest.length() >= src.length() * 99 / 100 && dest.length() > 0) {
      return "file://${dest.absolutePath}"
    }
    if (dest.exists()) dest.delete()
    src.inputStream().use { input ->
      dest.outputStream().use { input.copyTo(it) }
    }
    return "file://${dest.absolutePath}"
  }

  private fun saveReadyFiles(dl: Dl): List<Map<String, Any>> {
    val h = dl.handle.takeIf { it.isValid } ?: return emptyList()
    val ti = dl.info
    val files = ti.files()
    val out = mutableListOf<Map<String, Any>>()
    fun emit(i: Int, fileUri: String) {
      out.add(
        mapOf(
          "index" to i,
          "name" to files.fileName(i),
          "uri" to fileUri,
          "size" to files.fileSize(i),
          "id" to dl.uri,
        )
      )
    }
    for (i in dl.wantedFiles) {
      if (!isVideoFile(i, files) || i in dl.savedFiles) continue
      val path = files.filePath(i)
      if (!destComplete(path, files.fileSize(i), dl.saveFolder, ti.name())) continue
      if (!dl.savedFiles.add(i)) continue
      emit(i, "file://${downloadDest(path, dl.saveFolder, ti.name()).absolutePath}")
    }
    val focus = sequentialFocus(dl).firstOrNull { isVideoFile(it, files) }
    if (focus != null && focus !in dl.savedFiles && fileComplete(h, ti, focus)) {
      val src = File(saveDir, files.filePath(focus))
      if (src.exists() && dl.savedFiles.add(focus)) {
        try {
          emit(focus, copyKeep(src, files.filePath(focus), dl.saveFolder, ti.name()))
        } catch (e: Exception) {
          dl.savedFiles.remove(focus)
          log("saveReady: copy failed ${files.fileName(focus)} — ${e.message}")
        }
      }
    }
    if (out.isNotEmpty()) {
      log("saveReady: ${out.joinToString { it["name"].toString() }}")
      applyFocus(dl)
    }
    return out
  }

  private fun locateDownload(fileName: String): File? {
    val safe = safeName(fileName.substringAfterLast('/').substringAfterLast('\\'))
    fun walk(dir: File): File? {
      dir.listFiles()?.forEach { child ->
        if (child.isFile && child.name == safe) return child
        if (child.isDirectory) walk(child)?.let { return it }
      }
      return null
    }
    for (root in watchlyRoots()) walk(root)?.let { return it }
    val pub = File(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
      safe
    )
    if (pub.exists()) return pub
    return null
  }

  private fun findDownload(fileName: String): String? =
    locateDownload(fileName)?.let { "file://${it.absolutePath}" }

  private fun deleteDownloadFile(fileName: String) {
    val safe = safeName(fileName)
    val found = locateDownload(fileName)
    if (found != null) {
      val stem = found.name.substringBeforeLast('.')
      found.parentFile?.listFiles()?.forEach { child ->
        if (child.isFile &&
          child.name.substringBeforeLast('.').let { it == stem || it.startsWith("$stem.") }
        ) {
          child.delete()
        }
      }
      found.delete()
      pruneEmptyDirs(found.parentFile)
    }
    File(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
      safe
    ).delete()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val ctx = appContext.reactContext ?: return
      ctx.contentResolver.delete(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(safe)
      )
    }
  }

  /** Drop empty Season / show folders left after a file delete. Stops at Watchly. */
  private fun pruneEmptyDirs(start: File?) {
    val roots = watchlyRoots().map {
      try {
        it.canonicalFile
      } catch (_: Exception) {
        it
      }
    }
    var cur = start
    while (cur != null) {
      val dir = try {
        cur.canonicalFile
      } catch (_: Exception) {
        cur
      }
      val root = roots.firstOrNull { dir == it || dir.path.startsWith(it.path + File.separator) }
      if (root == null || dir == root) break
      if (!dir.isDirectory) break
      val kids = dir.listFiles()
      if (kids != null && kids.isNotEmpty()) break
      val parent = dir.parentFile
      dir.delete()
      cur = parent
    }
  }

  /** Immediate progress tick so the UI flips Pause/Resume without waiting. */
  private fun emitPausedProgress(h: TorrentHandle) {
    if (!h.isValid) return
    val status = h.status()
    sendEvent(
      "progress",
      mapOf(
        "buffer" to 0,
        "progress" to downloadProgress(status),
        "speed" to if (userPaused) 0 else status.downloadRate(),
        "seeds" to status.numSeeds(),
        "peers" to status.numPeers(),
        "dhtNodes" to session.stats().dhtNodes().toInt(),
        "state" to status.state().name,
        "paused" to userPaused
      )
    )
  }

  companion object {
    @Volatile
    private var current: TorrentStreamModule? = null

    @JvmStatic
    fun keepAlive(): Boolean =
      current?.let { it.showNotify && it.downloads.values.any { d -> !d.userPaused } } == true

    @JvmStatic
    fun dismissNotify(ctx: Context) {
      if (current?.showNotify == true) return
      current?.showNotify = false
      val nm = ctx.applicationContext
        .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.cancel(NOTIF_ID)
      nm.cancel(NOTIF_ID + 1)
      runCatching { ctx.stopService(Intent(ctx, DownloadTaskWatch::class.java)) }
    }

    @JvmStatic
    fun stubNotification(ctx: Context): Notification {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
          nm.createNotificationChannel(
            NotificationChannel(
              NOTIF_CHANNEL,
              "Downloads",
              NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
              description = "Torrent download progress"
              setShowBadge(false)
            }
          )
        }
      }
      val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      val pi = PendingIntent.getActivity(
        ctx, 0, launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      return NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(pi)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()
    }
  }
}

/** Foreground service so a background download is not killed with the UI. */
class DownloadTaskWatch : Service() {
  override fun onBind(intent: Intent?) = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    instance = this
    goForeground(TorrentStreamModule.stubNotification(this))
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Leave the download running.
  }

  /** Android 15 caps a dataSync service at six hours a day, and crashes the app
   *  if it is still in the foreground a few seconds after this call. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    instance = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  private fun goForeground(notif: Notification) {
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(
        NOTIF_ID,
        notif,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      )
    } else {
      startForeground(NOTIF_ID, notif)
    }
  }

  companion object {
    @Volatile
    private var instance: DownloadTaskWatch? = null

    fun promote(ctx: Context, notif: Notification) {
      val svc = instance
      if (svc != null) {
        svc.goForeground(notif)
      }
    }
  }
}
