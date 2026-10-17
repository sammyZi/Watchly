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
  /**
   * Bound to every interface, not just loopback, because a Cast receiver
   * fetches the video itself — "127.0.0.1" is the phone talking to itself and
   * no TV can ever reach it.
   *
   * That does put the file on the local network, so the path carries a random
   * token and requests without it are refused. It is not authentication; it
   * means the stream cannot be found by anyone who did not receive the URL.
   */
  private val server = ServerSocket(0, 4)
  private val token = java.util.UUID.randomUUID().toString().take(12)
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
  private val path: String get() = "/$token/${file.name.urlEncoded()}"

  /** For the player in this app — loopback stays the shortest path. */
  val url: String get() = "http://127.0.0.1:$port$path"

  /**
   * The same stream, addressed so another device on the network can fetch it.
   * Null when there is no local address to offer — no Wi-Fi, no cast.
   */
  val lanUrl: String? get() = lanAddress()?.let { "http://$it:$port$path" }

  /** First non-loopback IPv4 address. Cast receivers are IPv4-only in practice,
   *  and a link-local IPv6 address would not route to one anyway. */
  private fun lanAddress(): String? =
    runCatching {
      java.net.NetworkInterface.getNetworkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
        ?.hostAddress
    }.getOrNull()

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
      val target = requestLine.substringAfter(' ').substringBeforeLast(' ')

      // Anything not carrying this session's token is not a client of ours.
      if (!target.startsWith("/$token/")) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        return
      }

      var rangeHeader: String? = null
      while (true) {
        val line = reader.readLine()
        if (line.isNullOrEmpty()) break
        if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
      }

      val out = socket.getOutputStream()

      val range = parseRange(rangeHeader)
      if (range == null) {
        out.write(
          (
            "HTTP/1.1 416 Range Not Satisfiable\r\n" +
              "Content-Range: bytes */$fileSize\r\n" +
              "Content-Length: 0\r\nConnection: close\r\n\r\n"
            ).toByteArray()
        )
        out.flush()
        return
      }

      val (start, end) = range
      val generationAtStart = generation.incrementAndGet()
      // Where the player reads from is the whole story when a stream stalls
      // with data on either side of the playhead; one line per connection.
      Log.i(
        "TorrentStream",
        "http #$generationAtStart: $method bytes $start-$end " +
          "(${start * 100 / fileSize.coerceAtLeast(1)}% of ${fileSize / 1024 / 1024}MB)"
      )

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
          Thread.sleep(40)
          continue
        }

        raf.seek(position)
        val want = minOf(buffer.size.toLong(), end - position + 1).toInt()
        val read = raf.read(buffer, 0, want)
        if (read <= 0) {
          Thread.sleep(40)
          continue
        }

        out.write(buffer, 0, read)
        position += read
      }
      out.flush()
    }
  }

  /**
   * @return the byte range to serve, or null when the client asked for one that
   *   starts past the end of the file. Players probe exactly there — VLC asks
   *   for `bytes=<fileSize>-` — and the answer is 416, not a clamped range.
   */
  private fun parseRange(header: String?): Pair<Long, Long>? {
    val last = fileSize - 1
    val spec = header?.substringAfter("bytes=", "")?.trim().orEmpty()
    if (spec.isEmpty()) return 0L to last

    // `bytes=-N` is the last N bytes, not bytes 0..N.
    if (spec.startsWith('-')) {
      val n = spec.drop(1).toLongOrNull() ?: return 0L to last
      if (n <= 0 || fileSize <= 0) return null
      return maxOf(0L, fileSize - n) to last
    }

    val requested = spec.substringBefore('-').toLongOrNull() ?: 0L
    if (requested > last) return null

    // Clamp the start *first* and measure the end against that. Reading the
    // unclamped value here is an empty range and an exception, not a bad
    // response — coerceIn(start, last) throws when start > last.
    val start = requested.coerceAtLeast(0)
    val end = (spec.substringAfter('-').toLongOrNull() ?: last).coerceIn(start, last)
    return start to end
  }
}

/** Every reserved character, not just spaces — a `#` or `%` in a release name
 *  otherwise makes a URL a Cast receiver refuses or truncates. */
private fun String.urlEncoded() = android.net.Uri.encode(this)

/** Also used to tell an external player what it is being handed. */
internal fun contentType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
  "mkv" -> "video/x-matroska"
  "mp4", "m4v" -> "video/mp4"
  "webm" -> "video/webm"
  "avi" -> "video/x-msvideo"
  "mov" -> "video/quicktime"
  "ts", "m2ts" -> "video/mp2t"
  else -> "application/octet-stream"
}
