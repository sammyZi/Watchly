package com.torrentviewer.core

/**
 * Programs, scripts, installers and shortcuts. A film or series has no reason to carry one: beside a video it marks
 * a fake release (the video says to install "codec.exe"). Never fetched in any case — a stream or download asks
 * only for videos and subtitles — but said, so the viewer knows to pick another release.
 */
val RUNNABLE = setOf(
    "exe", "scr", "com", "pif", "bat", "cmd", "msi", "msp", "lnk", "url", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta",
    "ps1", "psm1", "jar", "cpl", "reg", "dll", "apk", "xapk", "apks", "aab", "app", "dmg", "pkg", "sh", "run", "bin", "deb",
)

/** The file names in [names] that are [RUNNABLE] — trailing dots and spaces dropped, as Windows drops them ("setup.exe. "). */
fun runnableFiles(names: List<String>): List<String> =
    names.filter { it.trimEnd(' ', '.').substringAfterLast('.', "").lowercase() in RUNNABLE }

/** What to tell the viewer about a release carrying [runnable] files; null when it carries none. */
fun runnableWarning(runnable: List<String>): String? = runnable.takeIf { it.isNotEmpty() }?.let { r ->
    val names = r.take(2).joinToString(", ") { it.trimEnd(' ', '.').substringAfterLast('/') } + if (r.size > 2) " and ${r.size - 2} more" else ""
    "This release also holds a program ($names). Releases like that are usually fake or carry malware — Watchly never downloads it, but consider another release."
}
