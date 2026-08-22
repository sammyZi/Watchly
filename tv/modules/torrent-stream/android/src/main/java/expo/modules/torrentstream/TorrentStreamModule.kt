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

/**
 * Buffers measured in seconds of playback, not megabytes.
 *
 * Every constant above is a byte count, which means a buffer whose size in
 * *time* depends entirely on the bitrate. 128MB of readahead is seven minutes
 * of a 1080p x265 encode and sixteen seconds of a 4K remux — which is why a
 * remux rebuffers constantly on a link that plays everything else. Given a
 * bitrate these turn the byte counts into a floor and size the real buffer by
 * how long it will last.
 */
/**
 * Deliberately small: this one gates `ready`, which is when the player is
 * handed the URL. Sized at eight seconds it made a remux sit on "finding peers"
 * for a minute before showing anything — the viewer reads that as a hang. Three
 * seconds is enough to start, and it is READAHEAD_SECONDS that keeps playback
 * from stalling afterwards.
 */
private const val PREPARE_SECONDS = 3
private const val WINDOW_SECONDS = 15
private const val READAHEAD_SECONDS = 60

/** Ceilings, so a bad bitrate estimate cannot ask for the whole film at once. */
private const val PREPARE_MAX = 24L * 1024 * 1024
private const val READAHEAD_MAX = 768L * 1024 * 1024

/**
 * Pre-roll deadline: how long the engine waits for its first byte before it
 * gives up and says so.
 *
 * Metadata that arrived from the disk cache means play() starts without
 * inspect()'s sixty-second proof that anyone is out there. Without a deadline
 * of its own, a dead swarm or a network that blocks BitTorrent leaves the
 * television on "finding peers" until someone restarts the app.
 */
private const val STALL_NO_DATA_MS = 90_000L

/** While pre-roll has no data, re-ask trackers and DHT this often. */
private const val SHAKE_INTERVAL_MS = 10_000L

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
 * Reader id for a seek the player has not made yet.
 *
 * Real readers are keyed by the HTTP server's connection generation, which
 * counts up from zero, so a negative id cannot collide with one.
 */
private const val SEEK_READER = -1L

/**
 * How long to wait for libtorrent to let go of a torrent before giving up.
 *
 * This runs on the polling thread, so it is a stall in progress reporting for
 * as long as it lasts — long enough to be reliable, short enough not to look
 * like a freeze.
 */
private const val REMOVE_WAIT_MS = 4_000

class PlayOptions : Record {
  /** MB of the file head buffered before the player is handed the URL. The
   *  server blocks on data that has not arrived, so one piece is enough — the
   *  player's own buffering covers the rest. */
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

  /**
   * Bytes per second of the file being played, or 0 when it is not known.
   *
   * Only the caller can work this out — it needs a running time, which the
   * torrent does not carry. With it the buffers are sized in seconds of
   * playback instead of megabytes; without it they stay as they were.
   */
  @Field var bitrate: Double = 0.0

  /**
   * Megabytes of the streamed file allowed on disk before it rolls over.
   *
   * A television has single-digit gigabytes free and a 4K film is tens of them,
   * so the stream cannot simply accumulate. Zero disables the cap. See
   * `rollCache` for what happens when it is reached.
   */
  @Field var cacheCapMb: Int = 0
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
  /** Clock time the last pre-roll byte landed — drives the stall guard. */
  private var lastDataAt = 0L
  /** Clock time of the last forced reannounce while still waiting. */
  private var lastShakeAt = 0L
  /** Last piece of the container header region. */
  private var headEndPiece = 0
  /**
   * The most recent byte a reader asked for, kept after the socket closes.
   *
   * `readers` empties whenever the player has no connection open — every seek,
   * and the whole time it is paused — and the rolling cache still needs to know
   * where playback is.
   */
  private var lastReaderAt = 0L

  /** Bytes of the streamed file allowed on disk; 0 lets it grow to full size. */
  private var cacheCapBytes = 0L
  /** Tick of the last roll, so a cap set too low cannot thrash. */
  private var lastRollTick = -1000

  /** Starts of the currently applied readahead zones; empty until first apply. */
  private var zoneStarts = emptyList<Int>()
  private var zoneWidth = -1
  /** Whether the last applyZone() treated the full tail as DEFAULT. */
  private var zoneRushedTail = false
  /** Replaced by play() before anything reads it; see WINDOW_BYTES. */
  private var windowPieces = 4
  private var prepareBytes = 3L * 1024 * 1024
  /** The zone width in bytes, sized from the bitrate when one was given. */
  private var readaheadBytes = READAHEAD_BYTES
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
     * Move the finished file into app Downloads and stop the torrent.
     * The local HTTP server must be stopped first or the file stays locked and
     * rename/copy hangs forever on "Saving to Downloads…".
     */
    AsyncFunction("copyTo") { fileIndex: Int, fileName: String ->
      log("copyTo: begin index=$fileIndex name=$fileName")
      val ti = info ?: throw CodedException("No torrent is running")

      // Drop every reader that might hold the file open.
      server?.stop()
      server = null
      val h = handle
      if (h != null && h.isValid) {
        runCatching { h.pause() }
        userPaused = true
      }
      log("copyTo: server stopped, torrent paused")

      val name = fileName.ifBlank { ti.files().fileName(fileIndex) }
      val videos = wantedFiles.filter { i ->
        i in 0 until ti.files().numFiles() &&
          ti.files().filePath(i).substringAfterLast('.', "").lowercase() in VIDEO
      }.ifEmpty { listOf(fileIndex) }
      val uris = videos.mapNotNull { i ->
        val rel = ti.files().filePath(i)
        val src = File(saveDir, rel)
        val expected = ti.files().fileSize(i)
        var waits = 0
        while (src.length() < expected && waits < 20) {
          Thread.sleep(200)
          waits++
        }
        if (src.length() < expected * 99 / 100) {
          log(
            "copyTo: skip index=$i — only " +
              "${(src.length() * 100 / expected.coerceAtLeast(1))}% on disk"
          )
          return@mapNotNull null
        }
        saveToDownloads(src, rel)
      }
      if (uris.isEmpty()) {
        throw CodedException("No finished files on disk yet")
      }
      log("copyTo: saved ${uris.size} file(s) → ${uris.joinToString()}")
      notifyComplete(if (uris.size == 1) name else "${uris.size} files")
      showNotify = false
      clearDownloadNotification()
      forgetWanted(infoHashHex)
      teardown()
      wipeStreamCache()
      log("copyTo: torn down")
      uris
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

    /** `file://` for a finished episode still in the torrent cache. */
    AsyncFunction("fileUri") { index: Int, id: String ->
      val ti = (if (id.isNotEmpty()) findDl(id)?.info else info)
        ?: throw CodedException("No torrent is running")
      if (index !in 0 until ti.files().numFiles()) {
        throw CodedException("No file at index $index")
      }
      val src = File(saveDir, ti.files().filePath(index))
      val expected = ti.files().fileSize(index)
      if (!src.exists() || (expected > 0 && src.length() < expected * 95 / 100)) {
        throw CodedException("This episode is not ready to play yet")
      }
      "file://${src.absolutePath}"
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
      val bytes = URL(url).openStream().use { it.readCapped(MAX_SUBTITLE_DOWNLOAD) }
        ?: throw CodedException("That subtitle download is far too large to be one")
      if (bytes.size < 4) throw CodedException("Subtitle download was empty")

      // "PK": a zip. Anything else is the subtitle itself.
      if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
        val dest = File(dir, "$safe.srt")
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
        // A long pause during pre-roll must not hand the stall guard an
        // already-expired clock.
        lastDataAt = System.currentTimeMillis()
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

    /** Drop one file from a season download. Remaining files keep going. */
    /**
     * Aim the prefetch window at a point in the file before anyone reads it.
     *
     * Resuming an hour into a film otherwise fetches the head first: the engine
     * has no reader to follow until the player has opened a connection, and the
     * player will not open one at the resume point until it has read the
     * container header. That is a buffer the viewer waits through and then
     * seeks straight past. Seeding a reader here puts the window where playback
     * is about to be.
     */
    AsyncFunction("setPosition") { ratio: Double ->
      val ti = info
      if (ti == null || fileIndex < 0) return@AsyncFunction
      val size = ti.files().fileSize(fileIndex)
      if (size <= 0) return@AsyncFunction
      val offset = (size * ratio.coerceIn(0.0, 0.999)).toLong()
      readers[SEEK_READER] = offset
      log("seek: aiming window at ${(ratio * 100).toInt()}% (byte $offset)")
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
        AlertType.PORTMAP_ERROR -> log("libtorrent: ${alert.message()}")

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
      // Streaming cares about *completing* a piece, not raw throughput: 32MB
      // arrived in the time it took to finish one 2.9MB piece, because blocks
      // were scattered across peers. This is the threshold at which a peer is
      // considered fast enough to be asked for a whole piece at a time — with
      // pieces this large, the 20s default excludes almost every peer.
      .setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 60)
      // request_timeout, piece_timeout and strict_end_game_mode are left at their
      // defaults on purpose. Shortening the first two churns peers off a mobile
      // link — libtorrent counts timeouts against a peer and disconnects it — and
      // relaxing end-game mode is actively harmful here: the wanted set is
      // already tiny because everything outside the zones is IGNORE, so allowing
      // early duplicate requests means many peers sending the same blocks and
      // almost no unique bytes arriving.
      .setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:6881")
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
    // Seconds of playback, floored by the old byte constants so that nothing
    // low-bitrate ends up with a smaller buffer than it used to have.
    val perSecond = options.bitrate.takeIf { it > 0 }?.toLong() ?: 0L
    // `ready` waits for this many bytes *contiguous* from the start, and a
    // contiguous run can only advance a whole piece at a time. An 84GB remux
    // has 8MB pieces, so a 23MB target means four consecutive pieces — and the
    // swarm is spread across the whole readahead zone, so those four took 90
    // seconds while 4MB/s was arriving. Two pieces is the most worth waiting
    // for; libVLC's own cache covers the rest, and the readahead keeps filling
    // behind it.
    prepareBytes = maxOf(
      options.prepareMb.toLong() * 1024 * 1024,
      ti.pieceLength().toLong(),
      minOf(perSecond * PREPARE_SECONDS, PREPARE_MAX)
    ).coerceAtMost(ti.pieceLength().toLong() * 2)
    readaheadBytes = maxOf(
      READAHEAD_BYTES,
      minOf(perSecond * READAHEAD_SECONDS, READAHEAD_MAX)
    )
    if (perSecond > 0) {
      log(
        "buffers: ${perSecond / 1024}kB/s source — prepare ${prepareBytes / 1024 / 1024}MB, " +
          "readahead ${readaheadBytes / 1024 / 1024}MB"
      )
    }
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
      else maxOf(
        2,
        (maxOf(WINDOW_BYTES, perSecond * WINDOW_SECONDS) / ti.pieceLength()).toInt()
      )
    removeFilesAfterStop = options.removeFilesAfterStop
    cacheCapBytes = options.cacheCapMb.toLong() * 1024 * 1024
    lastRollTick = -1000
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
    val running = session.find(ti.infoHash())?.takeIf { it.isValid }
    val h = if (running != null) {
      log("play: reusing running torrent (${running.status().numPeers()} peer(s) connected)")
      running.prioritizeFiles(priorities)
      running
    } else {
      cleanOrphans(ti)
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
    // before it finishes.
    if (fullFile) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
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
    lastDataAt = readyAt
    lastShakeAt = 0L
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

    val running = session.find(ti.infoHash())?.takeIf { it.isValid }
    val h = if (running != null) {
      log("download: reusing running torrent (${running.status().numPeers()} peer(s))")
      running.prioritizeFiles(priorities)
      running
    } else {
      cleanOrphans(ti)
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
      // One last roll before giving up. A stream with a cache cap can almost
      // always free itself and carry on, and tearing the player down is the
      // only outcome here the viewer cannot recover from — so it is worth
      // trying even at the floor, where the roll normally would have run long
      // before.
      val h = handle
      val ti = info
      // No cap is not no protection: a stream nobody bounds must still be
      // able to save itself when the volume runs dry.
      if (h != null && ti != null && h.isValid) {
        lastRollTick = -1000
        if (runCatching { rollCache(h, ti) }.getOrDefault(false)) {
          log("out of space — rolled instead of stopping")
          return
        }
      }
      log("out of space — stopping")
      // Each step guarded on its own: a failure to stop one download must not
      // skip tearing down the rest, or the next tick finds the disk still full
      // and does the whole thing again, forever.
      runCatching {
        sendEvent(
          "error",
          mapOf("message" to "Out of storage space. The stream has been stopped and its files cleared.")
        )
      }
      downloads.keys.toList().forEach { id -> runCatching { stopDownload(id) } }
      if (handle != null) {
        runCatching { teardown() }
        runCatching { wipeStreamCache() }
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
    if (!h.isValid) {
      // A handle libtorrent has dropped would silently skip every tick after
      // this one — the player would spin on a socket nobody will ever feed.
      // Saying so is the only exit the viewer gets.
      failStream("The torrent engine lost this stream. Please try again.")
      return
    }

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

    // Disk is finite and a 4K file is not. A roll replaces the handle, so
    // everything below would be working on a dead one — pick it up next tick.
    if (ticks % 4 == 0 && rollCache(h, ti)) return

    // A seeded seek is a stand-in for a reader that has not connected yet.
    // Once one has, it is the truth and the stand-in only holds a second window
    // open on a region nobody is reading.
    if (readers.keys.any { it >= 0L }) readers.remove(SEEK_READER)

    // One window per live reader, lowest offset first — that is the playback
    // connection, and it gets the tightest deadlines.
    //
    // Before the player attaches there are no readers, and two things it will
    // want the instant it does: the container header at byte 0, and the point it
    // resumes from. Buffering only the resume point leaves it stalling on the
    // header the moment it opens the file.
    // One region when the resume point is close enough to the header to share a
    // window — two overlapping zones a piece apart help nobody.
    val preroll =
      if (startPiece - firstPiece <= windowPieces) listOf(firstPiece)
      else listOf(firstPiece, startPiece)

    // A reader parked in the tail is the player reading the container index
    // (Matroska Cues, a non-faststart moov) the moment it opens the file, not
    // playback. Treating it as a target re-aims the whole zone at the end of
    // the file, throws away the pre-roll that was nearly complete, and costs a
    // second full reprioritise a breath later when it seeks back to byte zero.
    // The tail is already fetched and kept below, so nothing is lost by
    // ignoring it — unless the tail is genuinely all anyone is reading, which
    // is what watching the last minutes looks like.
    val live = readers.values.map { pieceAt(it) }.distinct().sorted()
    val targets = live.filter { it < tailPiece }.ifEmpty { live }.ifEmpty { preroll }
    headPiece = targets.first()

    val status = h.status()
    val pieceLen = ti.pieceLength().toLong().coerceAtLeast(1L)

    // Pre-roll watchdog. Metadata served from the disk cache never passes
    // inspect()'s own timeout, so a dead swarm or a network that blocks
    // BitTorrent used to spin "finding peers" forever. Nothing at all arriving
    // within the deadline is exactly that failure — give up and say so rather
    // than let the spinner run all evening. Paused waits are not stalls.
    if (!announcedReady && !userPaused) {
      val now = System.currentTimeMillis()
      if (status.totalWantedDone() > 0 || status.downloadRate() > 0) {
        lastDataAt = now
      } else {
        // Trackers retry on their own interval, which can be minutes; while no
        // byte has landed, ask early and often instead.
        if (now - lastShakeAt >= SHAKE_INTERVAL_MS) {
          lastShakeAt = now
          runCatching { h.forceReannounce() }
          runCatching { h.forceDHTAnnounce() }
        }
        if (now - lastDataAt >= STALL_NO_DATA_MS) {
          log("stall: no data in ${STALL_NO_DATA_MS / 1000}s — giving up")
          failStream(
            "No data arrived from any peer within ${STALL_NO_DATA_MS / 1000}s. " +
              "The torrent may be dead, or the network is blocking BitTorrent."
          )
          return
        }
      }
    }

    // Two widths, and the difference matters.
    //
    // `reach` is the deadline window: the pieces the swarm is told to rush.
    // Narrow before playback starts, so everything converges on what unblocks
    // the first frame instead of spreading over tens of MB of readahead.
    // After start, a slow swarm stays tight (finish the next piece) and a
    // fast one opens to the full WINDOW_BYTES so playback never catches up.
    val reach = if (!announcedReady) {
      maxOf(1, Math.ceil(prepareBytes.toDouble() / pieceLen).toInt())
    } else {
      val rate = status.downloadRate()
      if (rate < 400_000) maxOf(2, (8L * 1024 * 1024 / pieceLen).toInt())
      else windowPieces
    }

    // `width` is the zone: everything the torrent is *allowed* to fetch. It
    // stays wide even during pre-roll. Narrowing it to `reach` left about ten
    // pieces wanted in the whole torrent, so every peer held the same few
    // blocks — permanent end-game, a swarm full of seeds, and almost no unique
    // bytes arriving. Priority still puts the urgent pieces first; the rest of
    // the zone just keeps the peers doing something useful.
    // Two pieces is the floor purely so the arithmetic cannot produce a zone
    // narrower than the reader plus its next step.
    val width = maxOf(2, (readaheadBytes / pieceLen).toInt())

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
    val stale = zoneWidth != width ||
      zoneRushedTail != announcedReady ||
      zoneStarts.size != targets.size ||
      targets.any { t -> zoneStarts.none { t >= it - back && t <= it + width / 2 } }
    if (stale) applyZone(h, targets, width, back, rushTail = announcedReady)

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
    // Resuming the closing minutes of a film leaves fewer pieces past the
    // resume point than the prepare window asks for — and a run that can never
    // be completed means `ready` can never fire, so the viewer sits on
    // "finding peers" forever. Ask only for what the file holds from here.
    val startAvailable = maxOf(1, lastPiece - startPiece + 1).toLong() * pieceLen
    val startNeed =
      minOf(
        if (startPiece == firstPiece) maxOf(prepareBytes, headNeed) else prepareBytes,
        startAvailable
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
          "head ${headHave / 1024 / 1024}MB start ${startHave / 1024 / 1024}MB"
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
        request = { id, offset ->
          readers[id] = offset
          lastReaderAt = offset
        },
        release = { id -> readers.remove(id) }
      )
      srv.start()
      server = srv

      log(
        "ready in ${(System.currentTimeMillis() - readyAt) / 1000}s: " +
          "header ${headHave / 1024 / 1024}MB, start ${startHave / 1024 / 1024}MB at piece " +
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
   * Free the disk by starting the file over from where the viewer is.
   *
   * A 4K film is tens of gigabytes and a television has single digits free, so
   * a stream cannot accumulate the way it does on a phone. libtorrent has no
   * way to forget one piece — priorities decide what to *fetch*, never what to
   * drop — so the only lever is to remove the torrent, delete its data, and add
   * it again: have-state resets, the sparse file is recreated empty, and the
   * zone re-aims at wherever the readers are on the next tick.
   *
   * Playback survives it. The player's HTTP connection stays open, because the
   * server blocks on `available` — false for every piece until the ones under
   * the playhead arrive again — then reopens the file and carries on. Seeking
   * backwards still works too: it moves a reader, the zone follows it, and
   * those pieces are fetched again rather than read as holes.
   *
   * ponytail: one rebuffer per roll, so the cap wants to be as large as the
   * disk allows. Evicting continuously instead needs FALLOC_FL_PUNCH_HOLE
   * through JNI, and libtorrent would then seed the zeros it still believes are
   * pieces — upgrade path only if the stall becomes the thing people notice.
   *
   * @return true when the handle was replaced, which makes the caller's stale.
   */
  private fun rollCache(h: TorrentHandle, ti: TorrentInfo): Boolean {
    if (!announcedReady) return false
    // ~30s of ticks. A cap set below the readahead window would otherwise roll
    // on every pass and the viewer would never get a frame.
    if (ticks - lastRollTick < 60) return false

    val status = runCatching { h.status() }.getOrNull() ?: return false
    val onDisk = status.totalDone()
    // A cap guessed too high still must not walk into the hard out-of-space
    // stop, which kills the stream outright. Three times the floor, not two:
    // the roll itself needs room to finish, and reaching the floor is the one
    // outcome with no recovery.
    val low = saveDir.usableSpace < FREE_SPACE_MIN * 3
    // A zero cap disables cap-based rolling only — the low watermark still
    // applies, or an unbounded stream fills the disk and dies. Spelled out
    // rather than leaned on: `onDisk < cacheCapBytes` is false for every
    // possible `onDisk` when the cap is zero, so folding the two rules into
    // one comparison rolled the whole stream every 30s on an empty disk.
    if (!low && (cacheCapBytes <= 0L || onDisk < cacheCapBytes)) return false

    // Where playback is. `readers` is only populated while an HTTP connection
    // is actually open, and the player closes one on every seek and while
    // paused — so insisting on a live reader here meant that pausing a remux
    // filled the disk with nobody to roll for, and the out-of-space stop tore
    // the whole stream down. The last known offset is still the right place to
    // restart from; a closed socket does not move the playhead.
    val at = readers.values.minOrNull()
      ?: lastReaderAt.takeIf { it > 0 }
      ?: return false
    lastRollTick = ticks
    val freeBefore = saveDir.usableSpace
    log(
      "roll: ${onDisk / 1024 / 1024}MB on disk, cap ${cacheCapBytes / 1024 / 1024}MB, " +
        "${saveDir.usableSpace / 1024 / 1024}MB free — restarting at byte $at"
    )

    // Keep the swarm across the gap: these are dialled immediately by the new
    // handle, so what the viewer waits for is a re-fetch, not a re-discovery.
    persistPeers()
    val peers = loadPeers(infoHashHex ?: ti.infoHash().toHex())
    runCatching { h.clearPieceDeadlines() }
    deadlined.clear()

    // Everything past this point is recoverable only by finishing it. The
    // torrent is about to leave the session, so any throw in here would leave
    // `handle` pointing at something libtorrent no longer owns — every later
    // tick would return early, the HTTP server would block on data that is
    // never coming, and the film would hang with no error on screen. So the
    // whole thing is wrapped, and every exit tears down honestly instead.
    try {
      // Removed first and deleted by hand rather than with DELETE_FILES: that
      // flag unlinks asynchronously, and the replacement torrent would be
      // writing into the same paths while libtorrent was still deleting them.
      runCatching { session.remove(h) }
      var waited = 0
      while (session.find(ti.infoHash()) != null && waited < REMOVE_WAIT_MS) {
        Thread.sleep(50)
        waited += 50
      }
      if (session.find(ti.infoHash()) != null) {
        // Still libtorrent's. Deleting the files under it corrupts the re-add,
        // and the old handle is still valid, so the honest move is to leave
        // everything alone and let the next roll try again.
        log("roll: torrent would not release after ${waited}ms — left alone")
        return false
      }

      val files = ti.files()
      runCatching {
        File(saveDir, ti.name()).deleteRecursively()
        for (i in 0 until files.numFiles()) File(saveDir, files.filePath(i)).delete()
      }.onFailure { log("roll: delete failed (${it.message})") }

      // Rolling is only worth doing if it actually frees something. When the
      // disk is full of *other* things, re-adding just fills it again in
      // thirty seconds and does so forever — stopping with a message beats
      // grinding.
      val free = saveDir.usableSpace
      if (free < FREE_SPACE_MIN) {
        log("roll: only ${free / 1024 / 1024}MB free after cleaning — giving up")
        failStream("Out of storage space. The stream has been stopped.")
        return true
      }
      log("roll: freed ${(free - freeBefore) / 1024 / 1024}MB")

      val priorities = Array(files.numFiles()) { Priority.IGNORE }
      wantedFiles.forEach { if (it in priorities.indices) priorities[it] = Priority.DEFAULT }
      session.download(ti, saveDir, null, priorities, peers, TorrentFlags.SEQUENTIAL_DOWNLOAD)
      val next = awaitHandle(ti)
        ?: throw IllegalStateException("torrent did not come back after re-adding")

      // Same reasoning as play(): piece deadlines do the ordering, not
      // sequential mode, which hands piece 0 to whichever peer is slowest.
      next.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
      val existing = runCatching { next.trackers().map { it.url() } }.getOrDefault(emptyList())
      TRACKERS.filterNot { it in existing }.forEach {
        runCatching { next.addTracker(AnnounceEntry(it)) }
      }
      runCatching { next.forceReannounce() }
      runCatching { next.forceDHTAnnounce() }

      handle = next
      // The applied zone describes a file that no longer exists; the next tick
      // re-derives it from the readers.
      zoneStarts = emptyList()
      zoneWidth = -1
      zoneRushedTail = false
      server?.reopen()
      return true
    } catch (e: Exception) {
      Log.w(TAG, "roll failed", e)
      failStream("The stream could not be restarted after freeing space.")
      return true
    }
  }

  /**
   * Stop the stream and say so.
   *
   * Used where carrying on is not possible: silence would leave the player
   * spinning on a socket that will never produce another byte.
   */
  private fun failStream(message: String) {
    runCatching { sendEvent("error", mapOf("message" to message)) }
    runCatching { teardown() }
    runCatching { wipeStreamCache() }
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
  ) {
    val prios = runCatching { h.piecePriorities() }.getOrNull() ?: return
    // A magnet re-added for reuse has no piece picker until its metadata
    // arrives, and play() runs the moment the *cached* metadata is read — so
    // the first pass lands on a handle that silently drops every priority and
    // deadline. Recording the zone anyway made `stale` false forever: piece 0
    // never got TOP_PRIORITY, nothing got a deadline, and the pre-roll finished
    // whenever the swarm felt like it. Leave the state untouched so the next
    // tick tries again.
    if (prios.size <= lastPiece) return
    val zones = targets.map { it..minOf(it + width, lastPiece) }
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
        spare.any { p in it } || behind.any { p in it } -> Priority.LOW
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
    log(
      "zone: ${zones.joinToString(", ") { "${it.first}..${it.last}" }} " +
        "(back $back, tail $tailPiece..$tailEnd) of $firstPiece..$lastPiece"
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
      } else {
        // Guarded: this runs on every film-to-film change, and a failure to
        // delete the outgoing torrent's pieces must not abort the rest of
        // teardown. Leaving `handle` and `info` set would strand the next
        // play() on a session state that is already gone.
        info?.let { old ->
          runCatching {
            File(saveDir, old.name()).deleteRecursively()
            val files = old.files()
            for (i in 0 until files.numFiles()) {
              File(saveDir, files.filePath(i)).delete()
            }
          }.onFailure { Log.w(TAG, "cleanup of ${old.name()} failed", it) }
          log("cleaned ${old.name()}")
        }
      }
    }

    deadlined.clear()
    readers.clear()
    zoneStarts = emptyList()
    zoneWidth = -1
    zoneRushedTail = false
    subtitleFiles = emptyList()
    wantedFiles = intArrayOf()
    savedFiles.clear()
    handle = null
    info = null
    infoHashHex = null
    fileIndex = -1
    announcedReady = false
    readyAt = 0L
    lastDataAt = 0L
    lastShakeAt = 0L
    peerSaveTick = 0
    fullFile = false
    cacheCapBytes = 0L
    lastRollTick = -1000
    lastReaderAt = 0L
    readaheadBytes = READAHEAD_BYTES
    if (downloads.isNotEmpty() && poller == null) startPolling()
  }

  /* ---------------------------------------------------------------- utils */

  /** Delete streamed/downloaded piece files. Meta + peer cache stays (tiny). */
  /**
   * Never throws.
   *
   * teardown() calls this partway through, so an IO error here would skip
   * everything after it — the handle and info would be left set, and the next
   * play() would build on a session state that no longer exists. Failing to
   * free disk space is survivable; failing to finish tearing down is not. The
   * directory is recreated whatever happens, because the next stream writes
   * into it.
   */
  private fun wipeStreamCache() {
    // Anything an older build left in cacheDir is stranded otherwise — it can be
    // several GB, and nothing writes there any more to notice it.
    runCatching { legacySaveDir.takeIf { it.exists() }?.deleteRecursively() }

    val dir = runCatching { saveDir }.getOrNull() ?: return
    val ok = runCatching { !dir.exists() || dir.deleteRecursively() }
      .getOrElse { e ->
        Log.w(TAG, "stream cache wipe failed", e)
        false
      }
    runCatching { dir.mkdirs() }
    log(if (ok) "wiped stream cache" else "stream cache wipe incomplete")
  }

  /**
   * Delete piece data left by sessions that never got to stop cleanly.
   *
   * A television that lost power mid-film — or an app the OS reaped while it
   * streamed — leaves up to a full cache cap of pieces under saveDir, and
   * nothing else ever clears them: every other wipe runs only after a clean
   * stop. On a box with single-digit gigabytes free, the next open then fails
   * on space with no way to recover from inside the app.
   *
   * Runs when a torrent is about to be added cold. Anything whose tree belongs
   * to this torrent (or to one still downloading) stays — libtorrent re-checks
   * matching files on add, so a crashed session of the same film restarts warm
   * rather than full.
   */
  private fun cleanOrphans(ti: TorrentInfo) {
    val dir = runCatching { saveDir }.getOrNull() ?: return
    val mine = safeName(ti.name())
    val keep = mutableSetOf<String>()
    downloads.values.forEach { dl ->
      runCatching {
        keep.add(dl.info.name())
        keep.add(safeName(dl.info.name()))
      }
    }
    var freed = 0
    dir.listFiles()?.forEach { child ->
      if (child.name == ti.name() || safeName(child.name) == mine || child.name in keep) {
        return@forEach
      }
      if (runCatching { child.deleteRecursively() }.getOrDefault(false)) freed++
    }
    if (freed > 0) log("cleaned $freed orphaned item(s) from ${dir.name}")
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

  private fun notifyComplete(name: String) {
    val ctx = appContext.reactContext ?: return
    rememberCtx(ctx)
    ensureNotifyChannel()
    watchTask(false)
    clearDownloadNotification()
    showNotify = false
    val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("Download complete")
      .setContentText(name)
      .setAutoCancel(true)
      .setTimeoutAfter(5_000)
      .build()
    val nm = notifyManager() ?: return
    nm.notify(NOTIF_ID + 1, notif)
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

  /**
   * App Downloads folder. Rename when the torrent cache lives on the same
   * volume (the normal case) so a 500MB+ finish is instant; copy otherwise.
   */
  private fun saveToDownloads(src: File, name: String): String {
    val dest = downloadDest(name)
    log("copyTo: dest=${dest.absolutePath}")
    if (dest.exists()) {
      val removed = dest.delete()
      log("copyTo: removed existing dest=$removed")
    }
    dest.parentFile?.mkdirs()
    if (src.renameTo(dest)) {
      log("copyTo: renamed in place")
      return "file://${dest.absolutePath}"
    }
    log("copyTo: rename failed — streaming copy ${src.length()}B")
    src.inputStream().use { input ->
      dest.outputStream().use { output ->
        val buf = ByteArray(1024 * 256)
        var total = 0L
        var n: Int
        var lastLog = 0L
        while (input.read(buf).also { n = it } >= 0) {
          if (n == 0) continue
          output.write(buf, 0, n)
          total += n
          if (total - lastLog > 50L * 1024 * 1024) {
            log("copyTo: copied ${total / (1024 * 1024)}MB…")
            lastLog = total
          }
        }
        output.flush()
      }
    }
    val deleted = src.delete()
    log("copyTo: copy done, srcDeleted=$deleted destLen=${dest.length()}")
    return "file://${dest.absolutePath}"
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
