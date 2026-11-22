package com.torrentviewer.mobile

import android.content.Context
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** Nuvio's torrent engine (NuvioTV's lib-nuvio-engine AAR, GPL-3.0), to compare with Watchly's: Settings → Streaming. */
object NuvioTorrent {
    private var engine: NuvioEngine? = null
    /** Info hash → the engine's torrent id: adding one twice is refused. */
    private val torrents = mutableMapOf<String, String>()
    @Volatile private var streamId: String? = null
    private var poll: Job? = null

    @Synchronized
    fun play(context: Context, magnet: String, torrent: ByteArray, hash: String, index: Int, name: String): String = runBlocking {
        stopLocked()
        val e = engine ?: NuvioEngine.create(
            NuvioEngineConfig(
                dataDirectory = File(context.noBackupFilesDir, "nuvio-engine/state").apply { mkdirs() },
                cacheDirectory = File(context.cacheDir, "nuvio-engine").apply { mkdirs() },
                // ponytail: fixed 2 GB disk cache, tie it to Storage for one film if this engine stays.
                diskCacheCapacityBytes = 2L * 1024 * 1024 * 1024,
                torrentProfile = NuvioTorrentProfile.Balanced,
                uploadMode = NuvioUploadMode.Unlimited,
                streamInactivityTimeoutMilliseconds = 0,
            ),
        ).also { engine = it }
        // The .torrent when there is one: a magnet makes it fetch the metadata from peers first.
        val id = torrents[hash]
            ?: (if (torrent.isNotEmpty()) e.addTorrent(torrent) else e.addMagnet(magnet)).also { torrents[hash] = it }
        Services.engine.note("nuvio: added ${if (torrent.isNotEmpty()) ".torrent (${torrent.size} bytes)" else "magnet"}")
        // From here, not once the stream is ready, so the loading screen shows the swarm too.
        val job = poll(e)
        poll = job
        try {
            e.prepareStream(torrentId = id, fileIndex = index, filenameHint = name).also { streamId = it.id }.url
        } catch (t: Throwable) {
            job.cancel()
            throw t
        }
    }

    /** The player's speed, peers and buffer all read the engine's progress: fill it from Nuvio's stats. */
    private fun poll(e: NuvioEngine): Job = AppScope.launch(Dispatchers.IO) {
        var tick = 0
        while (isActive) {
            val all = e.stats.value
            val route = streamId?.let { id -> runCatching { e.currentStreamStats(id) }.getOrNull() }
            if (!isActive) break
            Services.engine.progress.value = Progress(
                buffer = ((route?.bufferProgress ?: 0f) * 100).toInt().coerceIn(0, 100),
                progress = ((route?.fileProgress ?: 0f) * 100).coerceIn(0f, 100f),
                speed = all.downloadRateBytesPerSecond.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                seeds = all.connectedSeeds,
                peers = all.connectedPeers,
                dhtNodes = 0,
                state = "nuvio",
            )
            // Every 15s, as Watchly's engine logs its own rate.
            if (tick++ % 15 == 5) Services.engine.note("nuvio: rate ${all.downloadRateBytesPerSecond / 1024}kB/s, ${all.connectedPeers} peers (${all.connectedSeeds} seeds)")
            delay(1000)
        }
    }

    @Synchronized
    fun stop() = runBlocking { stopLocked() }

    private fun cacheDir(context: Context) = File(context.cacheDir, "nuvio-engine")

    /** What Nuvio's engine keeps on disk for a fast reopen. Reads files: off the main thread. */
    fun cacheBytes(context: Context): Long =
        engine?.stats?.value?.diskCacheUsedBytes ?: cacheDir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Clears it, unless a film is streaming from it. */
    @Synchronized
    fun clearCache(context: Context) = runBlocking {
        if (streamId != null) return@runBlocking
        val e = engine
        if (e != null) runCatching { e.reclaimDiskCache(0L) } else cacheDir(context).deleteRecursively()
    }

    private suspend fun stopLocked() {
        poll?.cancel()
        poll = null
        val id = streamId ?: return
        streamId = null
        runCatching { engine?.stopStream(id) }
    }
}
