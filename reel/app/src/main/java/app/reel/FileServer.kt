package app.reel

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Serves one partially-downloaded file to libVLC over loopback HTTP.
 *
 * A sparse file handed straight to the player reads zeros where pieces haven't arrived;
 * here a read blocks until the piece is on disk. Range requests let VLC read a Matroska
 * file's cues at the end, and tell the torrent where the reader is.
 *
 * Adapted from the phone app's LocalFileServer, loopback only.
 */
class FileServer(
    private val file: File,
    private val fileSize: Long,
    private val available: (Long) -> Boolean,
) {
    // Not getLoopbackAddress(): on Android that is ::1, and VLC dials 127.0.0.1 — connection refused.
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val token = java.util.UUID.randomUUID().toString().take(12)
    @Volatile private var running = true

    /** Newest connection wins: a seek opens a fresh one and abandons the old. */
    private val generation = AtomicLong()

    /** Byte offset the newest reader is at — where the torrent should be fetching. */
    @Volatile var readerAt = 0L
        private set

    val url: String get() = "http://127.0.0.1:${server.localPort}/$token/${android.net.Uri.encode(file.name)}"

    fun start() {
        thread(name = "reel-http") {
            while (running) {
                val client = try { server.accept() } catch (e: Exception) { break }
                thread(name = "reel-http-conn") { handle(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun handle(client: Socket): Unit = client.use { socket ->
        socket.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ')
        val target = requestLine.substringAfter(' ').substringBeforeLast(' ')
        val out = socket.getOutputStream()
        if (!target.startsWith("/$token/")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
        }
        val range = parseRange(rangeHeader, fileSize)
        if (range == null) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileSize\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val (start, end) = range
        val id = generation.incrementAndGet()
        val header = buildString {
            append(if (rangeHeader == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ${end - start + 1}\r\n")
            if (rangeHeader != null) append("Content-Range: bytes $start-$end/$fileSize\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.flush()
        if (method.equals("HEAD", true)) return
        try { stream(out, start, end, id) } catch (_: Exception) { } // player hung up: a seek, or closed
    }

    /** Copies bytes out, waiting on the torrent whenever it runs past what has arrived. */
    private fun stream(out: OutputStream, start: Long, end: Long, id: Long) {
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(64 * 1024)
            var position = start
            while (position <= end && running) {
                if (generation.get() == id) readerAt = position
                if (!available(position)) {
                    // Superseded and stalled: a seek replaced this reader.
                    if (generation.get() != id) return
                    Thread.sleep(40)
                    continue
                }
                raf.seek(position)
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), end - position + 1).toInt())
                if (read <= 0) { Thread.sleep(40); continue }
                out.write(buffer, 0, read)
                position += read
            }
            out.flush()
        }
    }
}

/**
 * The byte range to serve, or null when it starts past the end — VLC probes
 * `bytes=<size>-` and the answer is 416, not a clamped range.
 */
internal fun parseRange(header: String?, fileSize: Long): Pair<Long, Long>? {
    val last = fileSize - 1
    val spec = header?.substringAfter("bytes=", "")?.trim().orEmpty()
    if (spec.isEmpty()) return 0L to last
    if (spec.startsWith('-')) {
        val n = spec.drop(1).toLongOrNull() ?: return 0L to last
        if (n <= 0 || fileSize <= 0) return null
        return maxOf(0L, fileSize - n) to last
    }
    val start = (spec.substringBefore('-').toLongOrNull() ?: 0L).coerceAtLeast(0)
    if (start > last) return null
    val end = (spec.substringAfter('-').toLongOrNull() ?: last).coerceIn(start, last)
    return start to end
}
