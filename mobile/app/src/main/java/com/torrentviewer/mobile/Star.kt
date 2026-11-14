package com.torrentviewer.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type
import com.torrentviewer.core.WatchLog

/** Where the app's code is, and where a star goes. */
const val REPO_URL = "https://github.com/sammyZi/Watchly"

/**
 * Once the app has earned it, one ask: a star on GitHub. That is the whole of the "review" — there
 * is no store page to rate. Asked when the profile has watched on at least [DAYS] days and [HOURS]
 * hours in all; "Not now" asks once more [AGAIN_DAYS] days later, and then never. A star from the
 * card or from Settings ends it. Device-wide: the phone asks once, not once per profile.
 */
object StarPrompt {
    private const val KEY = "star-prompt"
    private const val DAYS = 3
    private const val HOURS = 3
    private const val AGAIN_DAYS = 21L
    private const val MAX_ASKS = 2

    fun due(now: Long = System.currentTimeMillis()): Boolean {
        val state = Kv.global(KEY)
        if (state == "done") return false
        // "later:<when>:<times asked>"
        val parts = state?.split(':')
        if (parts != null && parts.size == 3) {
            val at = parts[1].toLongOrNull() ?: 0L
            val asked = parts[2].toIntOrNull() ?: 0
            if (asked >= MAX_ASKS || now - at < AGAIN_DAYS * 86_400_000L) return false
        }
        val log = WatchLog.read()
        val days = log.days.count { it.value.values.sum() > 0 }
        val seconds = log.days.values.sumOf { it.values.sum() }
        return days >= DAYS && seconds >= HOURS * 3600.0
    }

    fun starred() = Kv.setGlobal(KEY, "done")

    fun later(now: Long = System.currentTimeMillis()) {
        val asked = Kv.global(KEY)?.split(':')?.getOrNull(2)?.toIntOrNull() ?: 0
        Kv.setGlobal(KEY, "later:$now:${asked + 1}")
    }
}

/** The ask, as a card at the top of the screen like the update card: a star, or not now. */
@Composable
fun StarCard(modifier: Modifier = Modifier) {
    var showing by remember { mutableStateOf(StarPrompt.due()) }
    if (!showing) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 8.dp).shadow(10.dp, shape).clip(shape).background(Ink.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFF5B301).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Lc(L.drawable.lucide_ic_star, tint = Color(0xFFF5B301), size = 20.dp)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Enjoying Watchly?", style = Type.label.copy(fontWeight = FontWeight.Bold))
                Text("It's free and open source. A star on GitHub helps others find it.", Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.muted))
            }
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Tap({ StarPrompt.later(); showing = false }, shape = RoundedCornerShape(8.dp)) {
                Text("Not now", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = Type.label.copy(color = Ink.muted, fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.width(6.dp))
            Button("Star on GitHub", {
                StarPrompt.starred()
                showing = false
                openUrl(context, REPO_URL)
            }, primary = true, compact = true)
        }
    }
}
