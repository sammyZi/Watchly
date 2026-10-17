package com.torrentviewer.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.torrentviewer.core.History
import com.torrentviewer.core.Kv
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.TorrentFile
import com.torrentviewer.core.objects
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.pickFile
import com.torrentviewer.core.seasonsIn
import com.torrentviewer.core.speed
import com.torrentviewer.core.str
import com.torrentviewer.core.withTrackers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.URL

/**
 * One download as stored — the Expo build's "torrent-active-download" queue and field
 * names, so a transfer in flight carries over the upgrade.
 */
data class DownloadJob(
    val uri: String,
    /** The release's name. */
    val title: String,
    /** "The Matrix" or "Show · S01E02": the label, and (before the dot) the folder. */
    val titleName: String? = null,
    val poster: String? = null,
    val imdb: String? = null,
    val kind: String? = null,
    /** Episodes wanted out of a pack ("S01E02"); none takes the main video. */
    val tags: List<String> = emptyList(),
    /** A whole season out of a pack. */
    val season: Int? = null,
    /** Chosen file indexes, once the metadata has said which they are. */
    val files: List<Int> = emptyList(),
    /** Indexes already copied out to Movies/Watchly. */
    val saved: List<Int> = emptyList(),
    val paused: Boolean = false,
    /** Resume pressed while waiting for Wi-Fi: carry on over mobile data. */
    val mobileData: Boolean = false,
) {
    val label get() = titleName ?: title

    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("titleName", titleName).put("poster", poster)
        .put("imdb", imdb).put("kind", kind).put("tags", JSONArray(tags)).put("season", season)
        .put("fileIndices", JSONArray(files)).put("savedIndices", JSONArray(saved)).put("paused", paused).put("mobileData", mobileData)

    companion object {
        fun from(o: JSONObject): DownloadJob {
            fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty().filter { it >= 0 }
            val auto = o.opt("auto") as? String
            return DownloadJob(
                uri = o.getString("uri"),
                title = o.optString("title").ifBlank { "Download" },
                titleName = o.str("titleName"),
                poster = o.str("poster"),
                imdb = o.str("imdb"),
                kind = o.str("kind"),
                tags = (o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() + listOfNotNull(auto)).distinct(),
                season = if (o.has("season") && !o.isNull("season")) o.optInt("season") else null,
                files = ints("fileIndices"),
                saved = ints("savedIndices"),
                paused = o.optBoolean("paused"),
                mobileData = o.optBoolean("mobileData"),
            )
        }
    }
}

enum class DlState(val label: String) { RESOLVING("Finding peers"), DOWNLOADING("Downloading"), PAUSED("Paused"), WIFI("Waiting for Wi-Fi"), FAILED("Failed") }

/** One file of a download; in a pack, [wanted] false is an episode the viewer skipped. */
data class DlFile(val index: Int, val name: String, val size: Long, val done: Long, val saved: Boolean, val wanted: Boolean = true)

data class LiveDownload(
    val job: DownloadJob,
    val state: DlState,
    val files: List<DlFile> = emptyList(),
    val speed: Int = 0,
    val peers: Int = 0,
    val error: String? = null,
) {
    // Skipped episodes count for nothing.
    val total get() = files.filter { it.wanted }.sumOf { it.size }
    val done get() = files.filter { it.wanted }.sumOf { if (it.saved) it.size else it.done }
    val ratio get() = if (total > 0) done.toFloat() / total else 0f
}

/** A file kept in Movies/Watchly: the Expo build's "torrent-downloads". */
data class Finished(val name: String, val size: Long, val at: Long, val path: String, val poster: String?, val titleName: String?, val imdb: String?, val kind: String?) {
    val file get() = File(path.removePrefix("file://"))
    val label get() = titleName ?: name
}

/**
 * Downloads: keep a file rather than stream it. Its own libtorrent session, beside the
 * streaming engine's, so a download never fights the player's piece deadlines; finished
 * videos are copied into Movies/Watchly and the torrent's data dropped.
 *
 * ponytail: a second session on a random port, unforwarded unless UPnP opens it — a
 * download runs on outgoing peers alone behind a strict router. One shared session is
 * the upgrade if that matters.
 */
object Downloads {
    private const val TAG = "Downloads"
    private const val QUEUE = "torrent-active-download"
    private const val FINISHED = "torrent-downloads"
    private const val METADATA_SECONDS = 120
    private const val BOOTSTRAP = "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401"
    private val VIDEO = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv", "mpg", "mpeg")

    private val _live = MutableStateFlow<List<LiveDownload>>(emptyList())
    val live: StateFlow<List<LiveDownload>> = _live
    private val _finished = MutableStateFlow<List<Finished>>(emptyList())
    val finished: StateFlow<List<Finished>> = _finished

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var session: SessionManager? = null
    private val handles = HashMap<String, TorrentHandle>()
    private val errors = HashMap<String, String>()
    private var poller: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastNote = ""

    /* ------------------------------------------------------------ settings */

    // Booleans in the same preferences file and under the same names as the Expo build's native side.
    private fun prefs() = app.getSharedPreferences("watchly", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs().getBoolean("wifiOnly", false)
        set(v) = prefs().edit().putBoolean("wifiOnly", v).apply()

    var sdCard: Boolean
        get() = prefs().getBoolean("sdCard", false)
        set(v) = prefs().edit().putBoolean("sdCard", v).apply()

    /** The app's own folder on a mounted card: a real path libtorrent can write, needing no permission — gone on uninstall. */
    fun cardRoot(type: String?): File? = app.getExternalFilesDirs(type).drop(1).firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

    val hasCard get() = cardRoot(null) != null

    /** Moving storage under a running transfer would strand its pieces. */
    val busy get() = synchronized(lock) { handles.isNotEmpty() } || _live.value.isNotEmpty()

    private fun pieceDir() = File(if (sdCard) cardRoot(null) ?: app.getExternalFilesDir(null) ?: app.filesDir else app.getExternalFilesDir(null) ?: app.filesDir, "downloads").apply { mkdirs() }

    /** Public Movies/Watchly, visible in the Files app; the card's, if chosen; else the app's own Movies folder. */
    private fun keepDir(): File {
        if (sdCard) cardRoot(Environment.DIRECTORY_MOVIES)?.let { return File(it, "Watchly").apply { mkdirs() } }
        val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Watchly")
        if (runCatching { pub.mkdirs(); pub.exists() && pub.canWrite() }.getOrDefault(false)) return pub
        return File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir, "Watchly").apply { mkdirs() }
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().ifBlank { "Download" }

    /** Movies/Watchly/<show>/<the torrent's own folders, minus a root named after the torrent>/file.mkv */
    private fun destOf(job: DownloadJob, torrentName: String, path: String): File {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }.map(::safe)
        val rest = if (parts.size > 1 && parts.first().equals(safe(torrentName), ignoreCase = true)) parts.drop(1) else parts
        val show = safe(job.titleName?.substringBefore(" · ") ?: torrentName)
        return (listOf(show) + rest).fold(keepDir()) { dir, part -> File(dir, part) }
    }

    /* --------------------------------------------------------------- store */

    private fun readQueue(): List<DownloadJob> = runCatching {
        val raw = Kv.global(QUEUE) ?: return emptyList()
        // The Expo build once kept a single job, not a list.
        val any = org.json.JSONTokener(raw).nextValue()
        when (any) {
            is JSONArray -> any.objects().map(DownloadJob::from)
            is JSONObject -> listOf(DownloadJob.from(any))
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun writeQueue(jobs: List<DownloadJob>) = Kv.setGlobal(QUEUE, if (jobs.isEmpty()) null else JSONArray(jobs.map { it.toJson() }).toString())

    private fun jobs() = _live.value.map { it.job }

    private fun update(uri: String, change: (LiveDownload) -> LiveDownload) = synchronized(lock) {
        _live.value = _live.value.map { if (it.job.uri == uri) change(it) else it }
        writeQueue(jobs())
    }

    private fun readFinished(): List<Finished> = runCatching {
        JSONArray(Kv.global(FINISHED) ?: "[]").objects().mapNotNull { o ->
            val path = o.str("path") ?: return@mapNotNull null
            Finished(o.optString("name"), o.optLong("size"), o.optLong("at"), path, o.str("poster"), o.str("titleName"), o.str("imdb"), o.str("kind"))
        }
    }.getOrDefault(emptyList())

    private fun writeFinished(list: List<Finished>) {
        _finished.value = list
        Kv.setGlobal(FINISHED, JSONArray(list.map {
            JSONObject().put("name", it.name).put("size", it.size).put("at", it.at).put("path", it.path).put("poster", it.poster)
                .put("titleName", it.titleName).put("imdb", it.imdb).put("kind", it.kind)
        }).toString())
    }

    /* ----------------------------------------------------------------- API */

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _finished.value = readFinished().filter { it.file.exists() }
        val queue = readQueue()
        _live.value = queue.map { LiveDownload(it, if (it.paused) DlState.PAUSED else DlState.RESOLVING) }
        queue.forEach(::begin)
    }

    /** Another episode out of a torrent already downloading widens it rather than starting it twice. */
    fun add(job: DownloadJob) {
        val existing = _live.value.find { it.job.uri == job.uri }
        if (existing != null) {
            update(job.uri) { it.copy(job = it.job.copy(tags = (it.job.tags + job.tags).distinct(), season = it.job.season ?: job.season)) }
            scope.launch { synchronized(lock) { handles[job.uri] }?.let { h -> choose(job.uri, h) } }
            return
        }
        synchronized(lock) {
            _live.value = _live.value + LiveDownload(job, DlState.RESOLVING)
            writeQueue(jobs())
        }
        begin(job)
    }

    fun pause(uri: String) {
        synchronized(lock) { handles[uri] }?.let { runCatching { it.pause() } }
        update(uri) { it.copy(job = it.job.copy(paused = true), state = DlState.PAUSED, speed = 0) }
    }

    /** Resume on a download held for Wi-Fi is the viewer choosing mobile data for it. */
    fun resume(uri: String) {
        update(uri) { it.copy(job = it.job.copy(paused = false, mobileData = it.job.mobileData || it.state == DlState.WIFI), state = DlState.DOWNLOADING) }
        val h = synchronized(lock) { handles[uri] }
        if (h != null) runCatching { h.resume() } else _live.value.find { it.job.uri == uri }?.let { begin(it.job) }
    }

    fun cancel(uri: String) {
        val h = synchronized(lock) {
            _live.value = _live.value.filter { it.job.uri != uri }
            writeQueue(jobs())
            handles.remove(uri)
        }
        if (h != null) scope.launch { runCatching { session?.remove(h, SessionHandle.DELETE_FILES) } }
        settle()
    }

    fun delete(f: Finished) {
        runCatching { f.file.delete() }
        // Leaves no empty show folder behind.
        var dir = f.file.parentFile
        val root = keepDir().parentFile
        while (dir != null && dir != root && dir.name != "Watchly" && dir.listFiles().isNullOrEmpty()) {
            dir.delete()
            dir = dir.parentFile
        }
        History.drop(f.file.path, Services.engine)
        writeFinished(_finished.value.filter { it.path != f.path })
    }

    /* -------------------------------------------------------------- engine */

    private fun ensureSession(): SessionManager = synchronized(lock) {
        session?.takeIf { it.isRunning }?.let { return it }
        val settings = SettingsPack()
            .setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0,[::]:0")
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), BOOTSTRAP)
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        val s = SessionManager()
        s.start(SessionParams(settings))
        s.startDht()
        session = s
        s
    }

    private fun hashOf(uri: String): Sha1Hash? = runCatching {
        val ec = error_code()
        val p = libtorrent.parse_magnet_uri(uri, ec)
        if (ec.value() != 0) null else Sha1Hash.parseHex(p.getInfo_hashes().get_best().to_hex())
    }.getOrNull()

    private fun begin(job: DownloadJob) {
        scope.launch {
            try {
                startService()
                val s = ensureSession()
                val uri = if (SettingsStore.value.value.addTrackers) withTrackers(job.uri) else job.uri
                val dir = pieceDir()
                val h: TorrentHandle = if (uri.startsWith("magnet:", true)) {
                    val hash = hashOf(uri) ?: error("That magnet link could not be parsed")
                    if (s.find(hash)?.isValid != true) s.download(uri, dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    var found: TorrentHandle? = null
                    for (i in 0 until METADATA_SECONDS * 2) {
                        if (!isActive || _live.value.none { it.job.uri == job.uri }) return@launch
                        val h = s.find(hash)
                        if (h != null && h.isValid && h.status().hasMetadata()) {
                            found = h
                            break
                        }
                        delay(500)
                    }
                    found ?: error("No peers answered. The torrent may be dead, or the network is blocking BitTorrent.")
                } else {
                    val file = if (uri.startsWith("http", true)) File(app.cacheDir, "dl-${uri.hashCode()}.torrent").also { f -> URL(uri).openStream().use { i -> f.outputStream().use { i.copyTo(it) } } } else File(uri.removePrefix("file://"))
                    val ti = TorrentInfo(file)
                    s.download(ti, dir, null, Array(ti.files().numFiles()) { Priority.IGNORE }, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    s.find(ti.infoHash()) ?: error("Couldn't add that torrent")
                }
                // Paused means paused: an auto-managed torrent is resumed by the session's queue.
                runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED) }
                synchronized(lock) { handles[job.uri] = h }
                choose(job.uri, h)
                poll()
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                errors[job.uri] = e.message ?: "Couldn't start that download"
                update(job.uri) { it.copy(state = DlState.FAILED, error = errors[job.uri]) }
                settle()
            }
        }
    }

    private fun filesOf(ti: TorrentInfo): List<TorrentFile> {
        val fs = ti.files()
        return (0 until fs.numFiles()).map { i ->
            val path = fs.filePath(i)
            TorrentFile(i, path, fs.fileName(i), fs.fileSize(i), path.substringAfterLast('.', "").lowercase() in VIDEO, false)
        }
    }

    /** Episode order — S01E02 before S01E10 — whatever order the torrent lists its files in; anything else after, as listed. */
    private fun ord(name: String, index: Int): Long = parseEpisode(name)?.let { it.season * 10_000L + it.episode } ?: (100_000_000L + index)

    /** A season's videos, for the pack's full list — skipped ones included, so they can be put back. */
    private fun seasonVideos(job: DownloadJob, files: List<TorrentFile>): List<Int> =
        job.season?.let { s -> files.filter { it.isVideo && (parseEpisode(it.name)?.season == s || s in seasonsIn(it.path)) }.map { it.index } }.orEmpty()

    /**
     * Episodes arrive in order: the next one unsaved at the top priority, the two after it
     * a step down each, the rest of the wanted at the default; skipped and saved are ignored.
     */
    private fun prioritize(h: TorrentHandle, job: DownloadJob, files: List<TorrentFile>) {
        val order = job.files.filter { it !in job.saved && it in files.indices }.sortedBy { ord(files[it].name, it) }
        val prios = Array(files.size) { Priority.IGNORE }
        order.forEachIndexed { rank, i ->
            prios[i] = when (rank) {
                0 -> Priority.TOP_PRIORITY
                1 -> Priority.SIX
                2 -> Priority.FIVE
                else -> Priority.DEFAULT
            }
        }
        runCatching { h.prioritizeFiles(prios) }
    }

    /** Skip an episode of a pack, or put it back. A saved one stays saved either way. */
    fun setWanted(uri: String, index: Int, on: Boolean) {
        // The tick shows at once; the next poll would otherwise be up to two seconds away.
        update(uri) {
            it.copy(
                job = it.job.copy(files = if (on) (it.job.files + index).distinct() else it.job.files - index),
                files = it.files.map { f -> if (f.index == index) f.copy(wanted = on) else f },
            )
        }
        val h = synchronized(lock) { handles[uri] } ?: return
        scope.launch {
            val job = _live.value.find { it.job.uri == uri }?.job ?: return@launch
            val ti = runCatching { h.torrentFile() }.getOrNull() ?: return@launch
            prioritize(h, job, filesOf(ti))
        }
    }

    /** Which files this job wants, now the torrent has said what it holds. */
    private fun choose(uri: String, h: TorrentHandle) {
        val ti = runCatching { h.torrentFile() }.getOrNull() ?: return
        val job = _live.value.find { it.job.uri == uri }?.job ?: return
        val files = filesOf(ti)
        val season = seasonVideos(job, files)
        val tagged = job.tags.mapNotNull { pickFile(files, it) }
        val wanted = (job.files + season + tagged).distinct().ifEmpty { listOfNotNull(pickFile(files)) }
        if (wanted.isEmpty()) {
            update(uri) { it.copy(state = DlState.FAILED, error = "No video in this torrent") }
            return
        }
        prioritize(h, job.copy(files = wanted), files)
        if (job.paused) runCatching { h.pause() } else runCatching { h.resume() }
        update(uri) { it.copy(job = it.job.copy(files = wanted), state = if (job.paused) DlState.PAUSED else DlState.DOWNLOADING) }
    }

    private fun onMetered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    private fun poll() {
        synchronized(lock) {
            if (poller?.isActive == true) return
            poller = scope.launch {
                while (isActive) {
                    tick()
                    if (synchronized(lock) { handles.isEmpty() } && _live.value.none { it.state == DlState.RESOLVING }) break
                    delay(2000)
                }
                settle()
            }
        }
    }

    private fun tick() {
        val metered = wifiOnly && onMetered()
        for ((uri, h) in synchronized(lock) { handles.toMap() }) {
            val row = _live.value.find { it.job.uri == uri } ?: continue
            if (!h.isValid) continue
            val ti = runCatching { h.torrentFile() }.getOrNull() ?: continue
            val job = row.job

            // Wi-Fi only: hold on mobile data, pick back up on Wi-Fi, unless told to carry on.
            val hold = metered && !job.mobileData && !job.paused
            if (hold && row.state != DlState.WIFI) runCatching { h.pause() }
            if (!hold && row.state == DlState.WIFI) runCatching { h.resume() }

            val st = runCatching { h.status() }.getOrNull() ?: continue
            val fs = ti.files()
            val progress = runCatching { h.fileProgress(TorrentHandle.PIECE_GRANULARITY) }.getOrNull()
            var saved = job.saved
            for (i in job.files) {
                if (i in saved || progress == null || i !in progress.indices) continue
                val size = fs.fileSize(i)
                if (size <= 0 || progress[i] < size) continue
                // Done: copy it out and keep the label it was downloaded under.
                val src = File(pieceDir(), fs.filePath(i))
                val dest = destOf(job, ti.name(), fs.filePath(i))
                try {
                    dest.parentFile?.mkdirs()
                    if (!(dest.exists() && dest.length() == size)) src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                    saved = saved + i
                    val label = if (job.files.size > 1) parseEpisode(fs.fileName(i))?.let { e -> "${job.titleName?.substringBefore(" · ") ?: ti.name()} · S%02dE%02d".format(e.season, e.episode) } ?: fs.fileName(i) else job.label
                    writeFinished(listOf(Finished(fs.fileName(i), size, System.currentTimeMillis(), dest.path, job.poster, label, job.imdb, job.kind)) + _finished.value.filter { it.path != dest.path })
                } catch (e: Exception) {
                    Log.w(TAG, "copy failed: ${e.message}")
                }
            }
            val all = filesOf(ti)
            // One saved: the next episode moves up to the top priority.
            if (saved != job.saved) prioritize(h, job.copy(saved = saved), all)
            // The pack in episode order, skipped episodes included so they can be put back.
            val files = (job.files + seasonVideos(job, all)).distinct().filter { it in all.indices }.sortedBy { ord(all[it].name, it) }
                .map { i -> DlFile(i, fs.fileName(i), fs.fileSize(i), progress?.getOrNull(i) ?: 0, i in saved, i in job.files) }
            val state = when {
                job.paused -> DlState.PAUSED
                hold -> DlState.WIFI
                else -> DlState.DOWNLOADING
            }
            update(uri) { it.copy(job = it.job.copy(saved = saved), state = state, files = files, speed = st.downloadPayloadRate(), peers = st.numPeers()) }

            // Everything wanted is out in Movies: the torrent's own copy can go.
            if (job.files.isNotEmpty() && job.files.all { it in saved }) {
                synchronized(lock) {
                    handles.remove(uri)
                    _live.value = _live.value.filter { it.job.uri != uri }
                    writeQueue(jobs())
                }
                runCatching { session?.remove(h, SessionHandle.DELETE_FILES) }
            }
        }
        notifyProgress()
    }

    val running get() = _live.value.any { it.state == DlState.RESOLVING || it.state == DlState.DOWNLOADING || it.state == DlState.WIFI }

    /** Nothing running: let the service, the wake lock and the session go. */
    private fun settle() {
        if (running) return
        stopService()
        if (synchronized(lock) { handles.isEmpty() }) {
            val s = synchronized(lock) { session.also { session = null } }
            if (s != null) scope.launch { runCatching { s.stop() } }
        }
    }

    /* ------------------------------------------------- service, notification */

    private const val CHANNEL = "downloads"
    const val NOTE_ID = 7

    private fun startService() {
        runCatching {
            val i = Intent(app, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
        }
        if (wake?.isHeld != true) {
            wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchly:downloads").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun stopService() {
        // Not stopService(): stopped before it reached startForeground, a foreground service
        // crashes the app. One still starting sees nothing running and stops itself.
        DownloadService.instance?.stopSelf()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
        lastNote = ""
    }

    fun notification(context: Context, title: String = "Downloading", text: String? = null, percent: Int = 0): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun notifyProgress() {
        val rows = _live.value.filter { it.state != DlState.PAUSED && it.state != DlState.FAILED }
        if (rows.isEmpty()) return
        val total = rows.sumOf { it.total }
        val percent = if (total > 0) (rows.sumOf { it.done } * 100 / total).toInt() else 0
        val rate = rows.sumOf { it.speed }
        val waiting = rows.all { it.state == DlState.WIFI }
        val title = (if (waiting) "Waiting for Wi-Fi" else "Downloading") + " · $percent%"
        val left = rows.sumOf { it.total - it.done }
        val eta = if (rate > 0 && left > 0) (left / rate).let { s -> if (s < 3600) "${s / 60 + 1}m left" else "${s / 3600}h ${s % 3600 / 60}m left" } else "— left"
        val text = "${speed(rate)} · $eta · " + if (rows.size > 1) "${rows.size} downloads" else rows.first().job.label
        val key = "$title|${rate / 25_000}|${rows.size}"
        if (key == lastNote) return
        lastNote = key
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, notification(app, title, text, percent))
    }
}

/** Keeps the process, and so the download, alive with the screen off and the app away. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Restarted by the system after the process died: the queue picks itself back up.
        Services.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = Downloads.notification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(Downloads.NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Downloads.NOTE_ID, note)
        instance = this
        if (!Downloads.running) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Set once it is in the foreground — only then may it be stopped. */
        @Volatile
        var instance: DownloadService? = null
    }

    /** Android 15 caps a data-sync service at six hours a day, and kills an app still in the foreground after this. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
