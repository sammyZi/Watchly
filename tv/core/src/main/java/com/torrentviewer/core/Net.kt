package com.torrentviewer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Work that outlives a screen. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * [body] is what the service said back — an OAuth error is in there, not in the status.
 * [retryAfterMs] is how long it asked to be left alone, with a 429 that says.
 */
class HttpException(val code: Int, message: String, val body: String = "", val retryAfterMs: Long = 0) : IOException(message)

const val USER_AGENT = "WatchlyTV/2"

/**
 * Who is asking, as Wikimedia's User-Agent policy wants it for Wikipedia and its pictures: a name, a
 * version, and where to find the people behind it. A bare name is the kind it throttles first.
 * Only sent there: every other service has been answering the plain one.
 */
const val WIKI_AGENT = "Watchly/2 (https://github.com/sammyZi/Watchly)"

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/**
 * HTTP with a timeout and the connectivity bookkeeping. Only a request that never
 * got a status counts as offline — a 404 means the network is fine and the service is not.
 */
object Net {
    private const val PROBE = "https://v3-cinemeta.strem.io/manifest.json"
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    private val probing = AtomicBoolean(false)

    /** A service that says "too many requests" is asked again this many times, after a wait. */
    private const val BUSY_RETRIES = 2
    private const val BUSY_WAIT_MS = 800L
    private const val BUSY_WAIT_MAX_MS = 5_000L

    suspend fun get(url: String, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        request(url, "GET", null, timeoutMs, quiet, headers = headers)

    suspend fun postJson(url: String, body: String, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", body, timeoutMs, quiet, headers = headers)

    suspend fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 15_000): String =
        request(url, "POST", fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }, timeoutMs, false, "application/x-www-form-urlencoded")

    /** multipart/form-data, text fields only — what TorBox's create endpoints take. */
    suspend fun postMultipart(url: String, fields: Map<String, String>, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String {
        val boundary = "watchly${System.nanoTime()}"
        val body = buildString {
            fields.forEach { (k, v) -> append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            append("--$boundary--\r\n")
        }
        return request(url, "POST", body, timeoutMs, quiet, "multipart/form-data; boundary=$boundary", headers)
    }

    /**
     * A 429 used to be a failure like any other: the shelf, cast list or suggestion that drew it stayed
     * empty until the app was restarted. Now it waits as long as the service asks (a few seconds at
     * most) and asks again, twice.
     */
    private suspend fun request(
        url: String, method: String, body: String?, timeoutMs: Int, quiet: Boolean,
        type: String = "application/json", headers: Map<String, String> = emptyMap(),
    ): String {
        var tries = 0
        while (true) {
            try {
                return once(url, method, body, timeoutMs, quiet, type, headers)
            } catch (e: HttpException) {
                if (e.code != 429 || tries >= BUSY_RETRIES) throw e
                tries++
                delay(e.retryAfterMs.coerceIn(BUSY_WAIT_MS * tries, BUSY_WAIT_MAX_MS))
            }
        }
    }

    private suspend fun once(
        url: String, method: String, body: String?, timeoutMs: Int, quiet: Boolean,
        type: String, headers: Map<String, String>,
    ): String =
        withContext(Dispatchers.IO) {
            val conn = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = method
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json, text/xml, */*")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", type)
                        outputStream.use { it.write(body.toByteArray()) }
                    }
                }
            } catch (e: IOException) {
                if (!quiet) lost()
                throw e
            }
            try {
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    if (!quiet) lost()
                    throw e
                }
                _online.value = true
                if (code !in 200..299) {
                    val said = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                    // Seconds; the other form, a date, is rare enough to take the default wait.
                    val wait = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 0L
                    throw HttpException(code, "${url.substringBefore('?')} returned $code", said, wait)
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

    /** Poll until something answers, then flip back. One loop at a time. */
    private fun lost() {
        _online.value = false
        if (!probing.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            var wait = 2000L
            while (true) {
                delay(wait)
                if (runCatching { request(PROBE, "HEAD", null, 8000, true) }.isSuccess) break
                wait = minOf(wait * 2, 30_000)
            }
            probing.set(false)
            _online.value = true
        }
    }
}
