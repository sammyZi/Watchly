package com.torrentviewer.mobile

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
import com.torrentviewer.core.Type
import com.torrentviewer.core.Updates
import com.torrentviewer.core.bytes

/**
 * Floating at the top of the screen while a new version is waiting. Update opens the APK in the
 * browser, which downloads it and hands it to Android's installer: this app has no leave to install.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
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
        Button("Update", { openUrl(context, r.url) }, primary = true, compact = true)
        Spacer(Modifier.width(4.dp))
        // A cross rather than a "Not now" button: side by side, the two left the words three lines.
        Tap({ Updates.skip(r.version) }, Modifier.size(36.dp), CircleShape) {
            Lc(L.drawable.lucide_ic_x, Modifier.align(Alignment.Center), tint = Ink.muted, size = 18.dp)
        }
    }
}
