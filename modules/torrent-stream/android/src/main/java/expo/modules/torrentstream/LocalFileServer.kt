package expo.modules.torrentstream

import android.util.Log
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
 * Serves one partially-downloaded file over HTTP on localhost.
 *
 * Handing a sparse file straight to the player does not work: the parts that
 * have not arrived read back as zeros rather than blocking, so the player sees
 * corrupt data. Range requests also let the player fetch the tail of a Matroska
 * file for its cues, which tells us which pieces to prioritise.
 *
 * @param available true when the byte at this offset is on disk
 * @param request   reports where reader `id` is now reading, so the torrent can
 *                  keep a window hot ahead of it
 * @param release   reader `id` is finished; stop fetching for it
 */
class LocalFileServer(
  private val file: File,
  private val fileSize: Long,
  private val available: (Long) -> Boolean,
  private val request: (Long, Long) -> Unit,
  private val release: (Long) -> Unit
) {
  private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
  @Volatile private var running = true

  /**
   * Identifies a connection, newest highest.
   *
   * A seek makes the player open a fresh connection and abandon the old one,
   * but the old reader is usually parked waiting for a piece — where it cannot
   * notice the closed socket. Left alone it holds a prefetch window open on a
   * region nobody is going to read.
   *
   * Only *parked* readers are dropped, not every older one: the player keeps its
   * main connection open while it opens a second to read the container index at
   * the end of the file, and cutting that main response short mid-body reads as
   * a corrupt stream.
   */
  private val generation = AtomicLong()

  val port: Int get() = server.localPort
  val url: String get() = "http://127.0.0.1:$port/${file.name.urlEncoded()}"

  fun start() {
    thread(name = "torrent-http") {
      while (running) {
        val client = try {
          server.accept()
        } catch (e: Exception) {
          break // socket closed by stop()
        }
        thread(name = "torrent-http-conn") { handle(client) }
      }
    }
  }

  fun stop() {
    running = false
    runCatching { server.close() }
  }

  private fun handle(client: Socket) {
    client.use { socket ->
      socket.soTimeout = 30_000
      val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

      val requestLine = reader.readLine() ?: return
      val method = requestLine.substringBefore(' ')

      var rangeHeader: String? = null
      while (true) {
        val line = reader.readLine()
        if (line.isNullOrEmpty()) break
        if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
      }

      val (start, end) = parseRange(rangeHeader)
      val generationAtStart = generation.incrementAndGet()
      val out = socket.getOutputStream()

      val length = end - start + 1
      val header = buildString {
        append(if (rangeHeader == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
        append("Content-Type: ${contentType(file.name)}\r\n")
        append("Accept-Ranges: bytes\r\n")
        append("Content-Length: $length\r\n")
        if (rangeHeader != null) append("Content-Range: bytes $start-$end/$fileSize\r\n")
        append("Connection: close\r\n\r\n")
      }
      out.write(header.toByteArray())
      out.flush()

      if (method.equals("HEAD", true)) return

      try {
        stream(out, start, end, generationAtStart)
      } catch (e: Exception) {
        Log.i("TorrentStream", "http: client gone (${e.javaClass.simpleName})")
      } finally {
        release(generationAtStart)
      }
    }
  }

  /** Copies bytes out, waiting on the torrent whenever it runs past what has arrived. */
  private fun stream(out: OutputStream, start: Long, end: Long, generationAtStart: Long) {
    RandomAccessFile(file, "r").use { raf ->
      val buffer = ByteArray(64 * 1024)
      var position = start

      while (position <= end && running) {
        // Report on every pass, not only when starved: the window has to stay
        // ahead of a reader that is currently keeping up, or it falls behind the
        // moment the reader catches the download.
        request(generationAtStart, position)

        if (!available(position)) {
          // Superseded and stalled — this reader is going nowhere; let go of its
          // window instead of competing with the connection that replaced it.
          if (generation.get() != generationAtStart) return
          Thread.sleep(150)
          continue
        }

        raf.seek(position)
        val want = minOf(buffer.size.toLong(), end - position + 1).toInt()
        val read = raf.read(buffer, 0, want)
        if (read <= 0) {
          Thread.sleep(150)
          continue
        }

        out.write(buffer, 0, read)
        position += read
      }
      out.flush()
    }
  }

  private fun parseRange(header: String?): Pair<Long, Long> {
    val last = fileSize - 1
    val spec = header?.substringAfter("bytes=", "")?.trim().orEmpty()
    if (spec.isEmpty()) return 0L to last

    val start = spec.substringBefore('-').toLongOrNull() ?: 0L
    val end = spec.substringAfter('-').toLongOrNull() ?: last
    return start.coerceIn(0, last) to end.coerceIn(start, last)
  }
}

private fun String.urlEncoded() = replace(" ", "%20")

private fun contentType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
  "mkv" -> "video/x-matroska"
  "mp4", "m4v" -> "video/mp4"
  "webm" -> "video/webm"
  "avi" -> "video/x-msvideo"
  "mov" -> "video/quicktime"
  "ts", "m2ts" -> "video/mp2t"
  else -> "application/octet-stream"
}
