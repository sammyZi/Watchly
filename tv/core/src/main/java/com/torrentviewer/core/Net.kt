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

/** [body] is what the service said back — an OAuth error is in there, not in the status. */
class HttpException(val code: Int, message: String, val body: String = "") : IOException(message)

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

    suspend fun get(url: String, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        request(url, "GET", null, timeoutMs, quiet, headers = headers)

    suspend fun postJson(url: String, body: String, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", body, timeoutMs, quiet, headers = headers)

    suspend fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 15_000): String =
        request(url, "POST", fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }, timeoutMs, false, "application/x-www-form-urlencoded")

    private suspend fun request(
        url: String, method: String, body: String?, timeoutMs: Int, quiet: Boolean,
        type: String = "application/json", headers: Map<String, String> = emptyMap(),
    ): String =
        withContext(Dispatchers.IO) {
            val conn = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = method
                    setRequestProperty("User-Agent", "WatchlyTV/2")
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
                    throw HttpException(code, "${url.substringBefore('?')} returned $code", said)
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
