package com.torrentviewer.tv

import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A key pasted on a phone instead of typed with a remote. While open, the TV serves one small page
 * on the home network at an address holding a one-time code — the QR beside the field — and the
 * page posts the key straight back. Nothing goes through the internet; a wrong code gets nothing,
 * and it closes once a key arrives.
 * ponytail: plain HTTP on the home network, as any TV's own pairing page is; TLS would need a
 * certificate the phone trusts.
 */
class KeyDrop(private val what: String, private val onKey: (String) -> Unit) {
    private val token = ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    @Volatile private var server: ServerSocket? = null

    /** Opens it; the address for the QR, or null when the TV has no network. */
    fun open(): String? {
        val ip = lanAddress() ?: return null
        val socket = runCatching { ServerSocket(0, 4, InetAddress.getByName(ip)) }.getOrNull() ?: return null
        server = socket
        thread(name = "key-drop") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                runCatching { client.use(::answer) }
            }
        }
        return "http://$ip:${socket.localPort}/$token"
    }

    fun close() {
        runCatching { server?.close() }
    }

    private fun answer(client: Socket) {
        client.soTimeout = 15_000
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return
        val (method, path) = parts
        val length = lines.firstNotNullOfOrNull { l -> l.takeIf { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() } ?: 0
        val out = client.getOutputStream()
        fun reply(status: String, html: String) {
            val body = html.toByteArray()
            out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        if (path.substringBefore('?') != "/$token") return reply("404 Not Found", page("Nothing here", "<p>This link isn't open on the TV any more. Scan the code on the TV again.</p>"))
        when (method) {
            "GET" -> reply("200 OK", form(null))
            "POST" -> {
                val body = String(readBody(input, minOf(length, 8192)))
                val key = body.split('&').firstOrNull { it.startsWith("key=") }?.substringAfter('=')
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim().orEmpty()
                if (key.isEmpty()) return reply("200 OK", form("Paste the key first."))
                Handler(Looper.getMainLooper()).post { onKey(key) }
                reply("200 OK", page("Saved", "<p>Your $what key is on the TV now. You can close this page.</p>"))
                close()
            }
            else -> reply("405 Method Not Allowed", page("Not that", "<p>Open the page from the QR code on the TV.</p>"))
        }
    }

    private fun form(problem: String?) = page(
        "$what key",
        (problem?.let { "<p class=bad>$it</p>" } ?: "") +
            """<form method=post action="/$token"><label for=k>Paste your $what key, then Save. It goes straight to the TV.</label>""" +
            """<input id=k name=key autocomplete=off autocapitalize=off spellcheck=false autofocus><button>Save</button></form>""",
    )

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>Watchly TV · $title</title><style>
body{margin:0;padding:28px 20px;background:#0b0b0d;color:#f2f2f2;font:16px/1.45 system-ui,sans-serif}
h1{font-size:22px;margin:0 0 6px}small{color:#8a8a90}label{display:block;margin:18px 0 10px;color:#c9c9cf}
input{box-sizing:border-box;width:100%;padding:14px;border-radius:10px;border:1px solid #333;background:#16161a;color:#fff;font:15px ui-monospace,monospace}
button{margin-top:14px;width:100%;padding:14px;border:0;border-radius:10px;background:#e50914;color:#fff;font-size:16px;font-weight:600}
.bad{color:#ff6b6b}</style></head><body><small>Watchly TV</small><h1>$title</h1>$body</body></html>"""

    /** The request line and headers, up to the blank line; null past 16 KB or a closed socket. */
    private fun readHead(input: InputStream): String? {
        val bytes = StringBuilder()
        while (bytes.length < 16_384) {
            val b = input.read()
            if (b < 0) return null
            bytes.append(b.toChar())
            if (bytes.endsWith("\r\n\r\n")) return bytes.toString()
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(buf, at, length - at)
            if (n < 0) break
            at += n
        }
        return buf.copyOf(at)
    }

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
