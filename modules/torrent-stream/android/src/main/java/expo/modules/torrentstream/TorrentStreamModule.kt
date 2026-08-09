package expo.modules.torrentstream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.app.NotificationCompat
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.PeerInfo
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
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

private const val TAG = "TorrentStream"
private const val NOTIF_CHANNEL = "torrent_downloads"
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

private val SUBTITLE = setOf(
  "srt", "vtt", "sub", "ass", "ssa", "idx"
)

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
 * time-critical. Not the same as the zone (READAHEAD_PIECES), which is what the
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
 * Wide on purpose. Too small a wanted set leaves every peer holding the same few
 * blocks, which puts libtorrent in permanent end-game — a swarm full of seeds
 * delivering duplicates and almost no unique bytes. Bounded in bytes as well as
 * pieces, because piece size varies by two orders of magnitude between torrents
 * and a fixed count is a generous zone on one and a starved one on another.
 */
private const val READAHEAD_PIECES = 240
private const val READAHEAD_MIN_BYTES = 256L * 1024 * 1024

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

class PlayOptions : Record {
  /** MB of the file head buffered before the player is handed the URL. The
   *  server blocks on data that has not arrived, so one piece is enough — the
   *  player's own buffering covers the rest. */
  @Field val prepareMb: Int = 1

  /** Pieces kept hot ahead of the playback head. */
  @Field val windowPieces: Int = 12
  @Field val removeFilesAfterStop: Boolean = true

  /** Where playback will actually begin, 0-1 through the file. Buffering the
   *  head of a file the viewer is going to skip past is pure dead time. */
  @Field val startRatio: Double = 0.0
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
  private var windowPieces = 12
  private var prepareBytes = 3L * 1024 * 1024
  private var removeFilesAfterStop = true
  private var announcedReady = false
  private var peerSaveTick = 0
  private val deadlined = mutableSetOf<Int>()
  /** Absolute paths of subtitle files found in the same folder as the video. */
  private var subtitleFiles = emptyList<String>()

  /** User paused the transfer from the Downloads UI. */
  private var userPaused = false
  /** Show an ongoing notification while downloading (Download mode). */
  private var showNotify = false
  private var lastNotifPct = -1
  private var lastNotifPaused: Boolean? = null

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

  override fun definition() = ModuleDefinition {
    Name("TorrentStream")
    Events("log", "progress", "ready", "error")

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

    AsyncFunction("play") { uri: String, index: Int, options: PlayOptions ->
      play(uri, index, options)
    }

    /**
     * Copy the already-downloaded file to the public Downloads folder.
     * Call after the progress event reports 100% or after stopping playback.
     * `fileName` is the desired filename; defaults to the torrent file name
     * if empty.
     */
    AsyncFunction("copyTo") { fileIndex: Int, fileName: String ->
      val ti = info ?: throw CodedException("No torrent is running")
      val src = File(saveDir, ti.files().filePath(fileIndex))
      if (!src.exists()) throw CodedException("File not found — it may not be downloaded yet")
      val expected = ti.files().fileSize(fileIndex)
      if (src.length() < expected) {
        throw CodedException(
          "Only ${(src.length() * 100 / expected)}% downloaded — wait until playback finishes"
        )
      }
      val name = fileName.ifBlank { ti.files().fileName(fileIndex) }
      val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS
      )
      downloadsDir.mkdirs()
      val dest = File(downloadsDir, name)
      src.copyTo(dest, overwrite = true)
      log("copied ${src.name} → ${dest.absolutePath}")
      notifyComplete(name)
      // file:// so the in-app player can open Completed downloads later.
      "file://${dest.absolutePath}"
    }

    /** Resolve a finished download in public Downloads/ for local playback. */
    AsyncFunction("downloadUri") { fileName: String ->
      val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS
      )
      val dest = File(downloadsDir, fileName)
      if (!dest.exists()) {
        throw CodedException("File not found in Downloads/$fileName")
      }
      "file://${dest.absolutePath}"
    }

    AsyncFunction("pause") {
      val h = handle
      if (h != null && h.isValid) {
        h.pause()
        userPaused = true
        lastNotifPaused = null
        emitPausedProgress(h)
        updateDownloadNotification()
        log("paused")
      }
      null
    }

    AsyncFunction("resume") {
      val h = handle
      if (h != null && h.isValid) {
        h.resume()
        userPaused = false
        lastNotifPaused = null
        emitPausedProgress(h)
        updateDownloadNotification()
        log("resumed")
      }
      null
    }

    /** Toggle the ongoing download notification (Download Manager mode). */
    AsyncFunction("setNotify") { enabled: Boolean ->
      showNotify = enabled
      if (!enabled) clearDownloadNotification()
      else {
        ensureNotifyChannel()
        lastNotifPct = -1
        lastNotifPaused = null
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
      showNotify = false
      clearDownloadNotification()
      teardown()
    }

    /** Wipe piece data under cache/torrents (not public Downloads or meta/peers). */
    AsyncFunction("clearCache") {
      if (handle != null) return@AsyncFunction false
      wipeStreamCache()
      true
    }

    OnDestroy {
      teardown()
      // Force-kill / process death often skips JS stop — drop video pieces here.
      if (removeFilesAfterStop) wipeStreamCache()
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

  /** DHT needs a moment before it can answer anything. Waits, and says what it saw. */
  private fun awaitDht(seconds: Int) {
    if (seconds <= 0) return
    repeat(seconds * 2) {
      session.postSessionStats()
      val nodes = session.stats().dhtNodes()
      if (nodes > 0) {
        log("dht ready: $nodes nodes")
        return
      }
      Thread.sleep(500)
    }
    log("dht still empty after ${seconds}s (running=${session.isDhtRunning}) — relying on trackers")
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
        awaitDht(15)
        // No bytes to persist: this path never holds the raw .torrent. Worth it
        // — see addMagnet.
        addMagnet(clean, timeoutSec) to ByteArray(0)
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

    if (bytes.isNotEmpty()) persistMeta(ti.infoHash().toHex(), bytes)
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
   *  touches the torrent currently playing. */
  private fun dropWarm(keepHex: String?) {
    val w = warm ?: return
    val hex = runCatching { w.infoHash().toHex() }.getOrNull()
    if (hex != null && hex == keepHex) return
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

  private fun play(uri: String, index: Int, options: PlayOptions) {
    val startedAt = System.currentTimeMillis()
    ensureSession()

    // Resolve before tearing down, so teardown knows whether the torrent it is
    // about to drop is the one we are about to play.
    val ti = resolve(uri, 60)
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
    teardown(keep = ti.infoHash().toHex())

    // Never less than a piece: nothing is readable until one completes.
    prepareBytes = maxOf(options.prepareMb.toLong() * 1024 * 1024, ti.pieceLength().toLong())
    windowPieces = maxOf(options.windowPieces, (WINDOW_BYTES / ti.pieceLength()).toInt())
    removeFilesAfterStop = options.removeFilesAfterStop
    info = ti
    infoHashHex = ti.infoHash().toHex()
    fileIndex = index
    announcedReady = false
    peerSaveTick = 0
    ticks = 0
    zoneStarts = emptyList()
    zoneWidth = -1

    // Everything except the chosen file is skipped entirely.
    val priorities = Array(files.numFiles()) { Priority.IGNORE }
    priorities[index] = Priority.DEFAULT
    // Also download subtitle files sitting next to the video or in a subfolder
    val targetPath = files.filePath(index).replace("\\", "/")
    val targetDir = targetPath.substringBeforeLast("/", "")
    subtitleFiles = (0 until files.numFiles())
      .filter { i ->
        i != index &&
          files.filePath(i).substringAfterLast('.', "").lowercase() in SUBTITLE
      }
      .filter { i ->
        val subDir = files.filePath(i).replace("\\", "/")
          .substringBeforeLast("/", "")
        // Same folder, or a subfolder relative to the video (e.g. Subs/, subtitles/)
        subDir == targetDir ||
          (targetDir.isEmpty() && subDir.isNotEmpty()) ||
          (targetDir.isNotEmpty() && subDir.startsWith("$targetDir/"))
      }
      .also { indices ->
        indices.forEach { priorities[it] = Priority.DEFAULT }
        if (indices.isNotEmpty()) {
          log("play: found ${indices.size} subtitle file(s) alongside the video")
        }
      }
      .map { File(saveDir, files.filePath(it)).absolutePath }

    log("play: ${files.fileName(index)} (${files.fileSize(index)} bytes)")

    // Adopt the torrent inspect() left running rather than adding it again —
    // re-adding drops every peer connection and starts the search from nothing.
    val running = session.find(ti.infoHash())?.takeIf { it.isValid }
    val h = if (running != null) {
      log("play: reusing running torrent (${running.status().numPeers()} peer(s) connected)")
      running.prioritizeFiles(priorities)
      running
    } else {
      val peers = loadPeers(infoHashHex!!)
      log("play: cold start, ${peers.size} cached peer(s)")
      // With known peers we can start dialing immediately; otherwise wait for DHT.
      if (peers.isEmpty()) awaitDht(15) else awaitDht(2)
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
    h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
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
    val size = files.fileSize(index)
    firstPiece = ti.mapFile(index, 0, 1).piece()
    lastPiece = ti.mapFile(index, maxOf(0, size - 1), 1).piece()
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

    readyAt = System.currentTimeMillis()
    startPolling()
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
        // Twice a second: this is what decides how fast the prefetch window
        // chases a seek. The progress event still only goes out every other tick.
        handler.postDelayed(this, 500)
      }
    }
    handler.post(tick)
  }

  private fun pump() {
    val h = handle ?: return
    val ti = info ?: return
    if (!h.isValid) return

    ticks++

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

    val targets = readers.values.map { pieceAt(it) }.distinct().sorted()
      .ifEmpty { preroll }
    headPiece = targets.first()

    // Two widths, and the difference matters.
    //
    // `reach` is the deadline window: the pieces the swarm is told to rush.
    // Narrow before playback starts, so everything converges on what unblocks
    // the first frame instead of spreading over tens of MB of readahead.
    val reach = if (announcedReady) {
      windowPieces
    } else {
      maxOf(2, Math.ceil(prepareBytes.toDouble() / ti.pieceLength()).toInt())
    }

    // `width` is the zone: everything the torrent is *allowed* to fetch. It
    // stays wide even during pre-roll. Narrowing it to `reach` left about ten
    // pieces wanted in the whole torrent, so every peer held the same few
    // blocks — permanent end-game, a swarm full of seeds, and almost no unique
    // bytes arriving. Priority still puts the urgent pieces first; the rest of
    // the zone just keeps the peers doing something useful.
    val width = maxOf(
      READAHEAD_PIECES,
      (READAHEAD_MIN_BYTES / ti.pieceLength()).toInt()
    )

    /** How far behind each reader stays wanted. See REWIND_BYTES. */
    val back = maxOf(8, (REWIND_BYTES / ti.pieceLength()).toInt())

    // Re-aim the zones when a reader leaves the half of one it was given (a
    // seek), when their number changes, or when the pre-roll ends and they
    // should open up. Before the window loop, because it wipes the piece
    // priorities the loop sets — and with hysteresis, because a full
    // reprioritise every tick would cost more than it buys.
    // `t >= it - back`: a rewind inside the band it already covers is not a new
    // zone. Without that tolerance the cheapest seek in the app was also the
    // one that threw away every deadline in the torrent.
    val stale = zoneWidth != width ||
      zoneStarts.size != targets.size ||
      targets.any { t -> zoneStarts.none { t >= it - back && t <= it + width / 2 } }
    if (stale) applyZone(h, targets, width, back)

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
    // The tail rides along from the start — fetching it only when the player
    // asks makes the first frame wait out a cold download of the end of the
    // file. Normal priority with a distant deadline: present, never ahead of
    // the pieces the viewer is actually about to watch.
    for (p in tailPiece..lastPiece) {
      keep.add(p)
      if (!h.havePiece(p) && deadlined.add(p)) {
        h.setPieceDeadline(p, 10_000 + (p - tailPiece) * 300)
      }
    }
    // Pieces no reader is heading for: stop paying for their urgency.
    deadlined.removeAll { piece ->
      (piece !in keep).also { if (it) runCatching { h.resetPieceDeadline(piece) } }
    }

    // Playback needs the container header *and* the point it resumes from. Two
    // separate runs of pieces, and the readiness of the pre-roll is whichever is
    // further behind.
    val headBytes = maxOf(HEAD_BYTES, ti.pieceLength().toLong())
    val headHave = contiguousFrom(h, ti, firstPiece)
    val startHave =
      if (startPiece == firstPiece) headHave else contiguousFrom(h, ti, startPiece)
    val startNeed = if (startPiece == firstPiece) maxOf(prepareBytes, headBytes) else prepareBytes

    val buffered = minOf(startHave, headHave)
    val ready = headHave >= headBytes && startHave >= startNeed

    // stats() only advances when session stats alerts are posted.
    session.postSessionStats()

    val status = h.status()
    if (ticks % 2 == 0) {
      sendEvent(
        "progress",
        mapOf(
          "buffer" to ((buffered * 100 / maxOf(1L, startNeed)).coerceAtMost(100L)).toInt(),
          "progress" to (status.progress() * 100f),
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
        request = { id, offset -> readers[id] = offset },
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
  private fun applyZone(h: TorrentHandle, targets: List<Int>, width: Int, back: Int) {
    val prios = runCatching { h.piecePriorities() }.getOrNull() ?: return
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
    val spare = targets.map { (it + width + 1)..minOf(it + 2 * width, lastPiece) }

    for (p in firstPiece..lastPiece) {
      if (p >= prios.size) break
      // Nothing outside these gets IGNORE rather than LOW. With sequential mode
      // off, priority ordering is honoured, so LOW would mostly work — but zero
      // is the only value that takes a piece out of the running entirely, and
      // the whole point is that the swarm has exactly one job.
      prios[p] = when {
        p in tailPiece..lastPiece || zones.any { p in it } -> Priority.DEFAULT
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
    log(
      "zone: ${zones.joinToString(", ") { "${it.first}..${it.last}" }} " +
        "(back $back) of $firstPiece..$lastPiece"
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
    clearDownloadNotification()
    showNotify = false
    userPaused = false
    lastNotifPct = -1
    lastNotifPaused = null

    server?.stop()
    server = null
    poller?.removeCallbacksAndMessages(null)
    pollThread?.quitSafely()
    poller = null
    pollThread = null

    val keeping = keep != null && keep == infoHashHex
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

    // play() can already have the *next* torrent writing into saveDir, so when
    // keep != null only delete the outgoing torrent's tree. Full stop wipes all
    // piece data — old.name() alone left multi-file / renamed torrents behind
    // and grew the app to gigabytes.
    if (removeFilesAfterStop && !keeping) {
      if (keep == null) {
        wipeStreamCache()
      } else {
        info?.let { old ->
          File(saveDir, old.name()).deleteRecursively()
          val files = old.files()
          for (i in 0 until files.numFiles()) {
            File(saveDir, files.filePath(i)).delete()
          }
          log("cleaned ${old.name()}")
        }
      }
    }

    deadlined.clear()
    readers.clear()
    zoneStarts = emptyList()
    zoneWidth = -1
    subtitleFiles = emptyList()
    handle = null
    info = null
    infoHashHex = null
    fileIndex = -1
    announcedReady = false
    peerSaveTick = 0
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

  private fun ensureNotifyChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val ctx = appContext.reactContext ?: return
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
    nm.createNotificationChannel(
      NotificationChannel(
        NOTIF_CHANNEL,
        "Downloads",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Torrent download progress"
        setShowBadge(false)
      }
    )
  }

  private fun updateDownloadNotification() {
    if (!showNotify) return
    val ctx = appContext.reactContext ?: return
    val ti = info ?: return
    val name =
      if (fileIndex >= 0) ti.files().fileName(fileIndex) else ti.name()
    val h = handle
    val pct = if (h != null && h.isValid) {
      (h.status().progress() * 100f).toInt().coerceIn(0, 100)
    } else {
      0
    }
    if (pct == lastNotifPct && lastNotifPaused == userPaused) return
    lastNotifPct = pct
    lastNotifPaused = userPaused

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
    val title = if (userPaused) "Paused" else "Downloading"
    val text = if (userPaused) name else "$pct% · $name"
    val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setProgress(100, pct, pct <= 0 && !userPaused)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(pi)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .build()
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIF_ID, notif)
  }

  private fun notifyComplete(name: String) {
    val ctx = appContext.reactContext ?: return
    ensureNotifyChannel()
    clearDownloadNotification()
    showNotify = false
    val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("Download complete")
      .setContentText(name)
      .setAutoCancel(true)
      .setTimeoutAfter(5_000)
      .build()
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIF_ID + 1, notif)
  }

  private fun clearDownloadNotification() {
    val ctx = appContext.reactContext ?: return
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(NOTIF_ID)
  }

  /** Immediate progress tick so the UI flips Pause/Resume without waiting. */
  private fun emitPausedProgress(h: TorrentHandle) {
    if (!h.isValid) return
    val status = h.status()
    sendEvent(
      "progress",
      mapOf(
        "buffer" to 0,
        "progress" to (status.progress() * 100f),
        "speed" to if (userPaused) 0 else status.downloadRate(),
        "seeds" to status.numSeeds(),
        "peers" to status.numPeers(),
        "dhtNodes" to session.stats().dhtNodes().toInt(),
        "state" to status.state().name,
        "paused" to userPaused
      )
    )
  }
}
