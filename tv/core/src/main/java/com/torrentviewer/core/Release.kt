package com.torrentviewer.core

import org.json.JSONObject

/** Where this project's release files are. An update from anywhere else is not offered. */
internal const val RELEASE_FILES = "https://github.com/sammyZi/Watchly/releases/download/"

/** A published version, and the one file of it for this device. */
data class AppRelease(val version: String, val url: String, val size: Long, val sha256: String?)

/** "v2.4.1" against "2.4.0", number by number: 2.10 is newer than 2.9. */
fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}

/**
 * GitHub's latest release: its [prefix] APK for the first of [abis] it has, else the universal one.
 * The page also holds the other app's APKs and the Windows installer.
 */
fun parseRelease(json: String, abis: List<String>, prefix: String): AppRelease? = runCatching {
    val o = JSONObject(json)
    val version = o.getString("tag_name").removePrefix("v")
    val assets = o.getJSONArray("assets").objects().filter { it.optString("name").startsWith(prefix) }
    (abis + "universal").firstNotNullOfOrNull { abi -> assets.find { it.optString("name").endsWith("-$abi.apk") } }
        // Only this project's own release files, and only with the checksum GitHub states for them.
        ?.takeIf { it.optString("browser_download_url").startsWith(RELEASE_FILES) && it.str("digest") != null }
        ?.let { AppRelease(version, it.getString("browser_download_url"), it.optLong("size"), it.str("digest")?.removePrefix("sha256:")) }
}.getOrNull()
