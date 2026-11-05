package com.torrentviewer.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Lc
import androidx.core.content.FileProvider
import com.torrentviewer.core.AppRelease
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Net
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes
import com.torrentviewer.core.isNewerVersion
import com.torrentviewer.core.parseRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest

/**
 * New versions from the GitHub releases, with no store: asked at each launch, downloaded here, and
 * handed to Android's installer, which asks the viewer to confirm. It only takes an APK signed with
 * the same key and a higher versionCode.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/sammyZi/Watchly/releases/latest"
    private const val PREFIX = "Watchly-phone-"
    /** The version "Not now" was said to. */
    private const val SKIPPED = "update-skipped"

    private val _release = MutableStateFlow<AppRelease?>(null)
    /** A version newer than this one, once found. */
    val release: StateFlow<AppRelease?> = _release

    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden

    private val _progress = MutableStateFlow<Float?>(null)
    /** 0–1 while downloading. */
    val progress: StateFlow<Float?> = _progress

    private var job: Job? = null

    private fun file(context: Context) = File(context.filesDir, "update.apk")

    private suspend fun latest(): AppRelease? {
        val json = Net.get(LATEST, 15_000, quiet = true, headers = mapOf("Accept" to "application/vnd.github+json"))
        return parseRelease(json, Build.SUPPORTED_ABIS.toList(), PREFIX)?.takeIf { isNewerVersion(it.version, BuildConfig.VERSION_NAME) }
    }

    /** At launch, quietly. The debug build is another app beside this one: an update would not replace it. */
    fun check(context: Context) {
        if (BuildConfig.DEBUG) return
        // What an update that went through left behind.
        file(context).delete()
        AppScope.launch {
            val found = runCatching { latest() }.getOrNull() ?: return@launch
            _hidden.value = Kv.global(SKIPPED) == found.version
            _release.value = found
        }
    }

    /** From Settings: brings back a version put off with "Not now". */
    fun checkNow(say: (String) -> Unit) {
        AppScope.launch {
            val found = try {
                latest()
            } catch (_: IOException) {
                return@launch say("Couldn't check for updates")
            }
            _release.value = found
            _hidden.value = false
            if (found == null) say("You're on the latest version")
        }
    }

    fun skip(version: String) {
        Kv.setGlobal(SKIPPED, version)
        _hidden.value = true
    }

    fun cancel() {
        job?.cancel()
    }

    fun download(context: Context, r: AppRelease, say: (String) -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = AppScope.launch {
            _progress.value = 0f
            try {
                withContext(Dispatchers.IO) { fetch(r, file(app)) }
                install(app, file(app))
            } catch (e: CancellationException) {
                file(app).delete()
                throw e
            } catch (e: Exception) {
                file(app).delete()
                say(e.message ?: "Couldn't download the update")
            } finally {
                _progress.value = null
            }
        }
    }

    private suspend fun fetch(r: AppRelease, to: File) {
        val sha = MessageDigest.getInstance("SHA-256")
        var done = 0L
        URL(r.url).openStream().use { input ->
            to.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    sha.update(buffer, 0, n)
                    done += n
                    if (r.size > 0) _progress.value = done.toFloat() / r.size
                }
            }
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        if (r.size > 0 && done != r.size || r.sha256 != null && got != r.sha256) throw IOException("The update didn't download properly. Try again.")
    }

    /** The first time, Android stops to ask for leave to install from this app, then carries on by itself. */
    private fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

/** Floating at the top of the screen while a new version is waiting, and for as long as it downloads. */
@Composable
fun UpdateCard(watch: Watch, modifier: Modifier = Modifier) {
    val release by Updates.release.collectAsState()
    val hidden by Updates.hidden.collectAsState()
    val progress by Updates.progress.collectAsState()
    val r = release ?: return
    val p = progress
    if (hidden && p == null) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 8.dp).shadow(8.dp, shape).clip(shape).background(Ink.surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (p != null) "Downloading update" else "Update available", style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (p != null) "Version ${r.version}  ·  ${(p * 100).toInt()}%" else "Version ${r.version}  ·  ${bytes(r.size)}",
                    style = Type.meta.copy(color = Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (p != null) Button("Cancel", Updates::cancel)
            else {
                Button("Update", { Updates.download(context, r) { watch.notes.tryEmit(it) } }, primary = true)
                Spacer(Modifier.width(4.dp))
                // A cross rather than a "Not now" button: side by side, the two left the words three lines.
                Tap({ Updates.skip(r.version) }, Modifier.size(36.dp), CircleShape) {
                    Lc(L.drawable.lucide_ic_x, Modifier.align(Alignment.Center), tint = Ink.muted, size = 18.dp)
                }
            }
        }
        if (p != null) ProgressLine(p, Modifier.padding(top = 8.dp))
    }
}
