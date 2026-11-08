package com.torrentviewer.mobile

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
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
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.AppRelease
import com.torrentviewer.core.Type
import com.torrentviewer.core.Updates
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes

/**
 * The APK through Android's download manager, whose notification installs it when tapped: the system's
 * Downloads app hands it to the installer. This app has no leave to install — Play Protect called a
 * sideloaded app that installs APKs itself "possibly harmful". Android still only takes an APK signed
 * with this app's key, so the checksum the in-app download checked isn't needed for safety.
 */
fun downloadUpdate(context: Context, r: AppRelease, say: (String) -> Unit) {
    val name = "Watchly-${r.version}.apk"
    // App-specific storage: no storage permission on Android 7–9, and the same name every tap, not "-1", "-2".
    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(name)?.delete()
    runCatching {
        context.getSystemService(DownloadManager::class.java).enqueue(
            DownloadManager.Request(Uri.parse(r.url))
                .setTitle("Watchly ${r.version}")
                .setDescription("Tap to install")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name),
        )
    }.fold({ say("Downloading. Tap the notification when it's done to install.") }, { say("Couldn't start the download") })
}

/** Floating at the top of the screen while a new version is waiting. */
@Composable
fun UpdateCard(watch: Watch, modifier: Modifier = Modifier) {
    val release by Updates.release.collectAsState()
    val hidden by Updates.hidden.collectAsState()
    val r = release ?: return
    if (hidden) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 8.dp).shadow(8.dp, shape).clip(shape).background(Ink.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Update available", style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Version ${r.version}  ·  ${bytes(r.size)}", style = Type.meta.copy(color = Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button("Update", { downloadUpdate(context, r) { watch.notes.tryEmit(it) } }, primary = true, compact = true)
        Spacer(Modifier.width(4.dp))
        // A cross rather than a "Not now" button: side by side, the two left the words three lines.
        Tap({ Updates.skip(r.version) }, Modifier.size(36.dp), CircleShape) {
            Lc(L.drawable.lucide_ic_x, Modifier.align(Alignment.Center), tint = Ink.muted, size = 18.dp)
        }
    }
}
