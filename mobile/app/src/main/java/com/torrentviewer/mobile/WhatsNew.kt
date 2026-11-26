package com.torrentviewer.mobile

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type

/** One new thing in a version: its icon, what it is in a few words, and what it does. */
private class Feature(@DrawableRes val icon: Int, val title: String, val text: String)

/**
 * What each version brings, as the screen after an update shows it. A version with nothing here shows nothing:
 * add its notes when it ships.
 */
private val NOTES = mapOf(
    "2.5.0" to listOf(
        Feature(L.drawable.lucide_ic_users, "Watch party", "Watch together with friends on phone, TV or Windows. Share a code, and everyone stays in step — no account needed."),
        Feature(L.drawable.lucide_ic_link, "Nuvio and AniList", "Sign in to Nuvio for your library and Continue Watching, and to AniList to track the anime you watch."),
        Feature(L.drawable.lucide_ic_bookmark, "My List everywhere", "My List now includes your Stremio and Nuvio lists. Films you finish move to the new Watched shelf, and every shelf splits into Movies and Series."),
        Feature(L.drawable.lucide_ic_chart_no_axes_column, "Your stats", "Hours watched this year, your favourite titles and genres, and your streaks — including what you watched on your accounts."),
        Feature(L.drawable.lucide_ic_bell, "Notifications", "Hear when a series you follow has a new episode, with AniList's news in the same place."),
        Feature(L.drawable.lucide_ic_palette, "Make it yours", "Pick an accent colour, go pure black on AMOLED screens, choose the app's language, and give profiles new people and animal icons."),
        Feature(L.drawable.lucide_ic_zap, "Quicker starts", "A film you stop part-way opens again at once, and the next episode gets ready while this one plays."),
        Feature(L.drawable.lucide_ic_captions, "Player tools", "Change stream without leaving the film, a sleep timer, and Sync to this line for subtitles that run early or late."),
    ),
)

/** The version last shown here (or recorded on a fresh install, which has the tour instead). */
private const val SEEN = "phone:whats-new"

/**
 * The version whose notes are due now: this one, after an update, when it has notes and they were not shown yet.
 * [existing]: someone already used the app (as the tour decides) — a fresh install only records the version.
 */
fun whatsNewDue(existing: Boolean): String? {
    val version = BuildConfig.VERSION_NAME
    val seen = Kv.global(SEEN)
    if (seen == version) return null
    if (!existing || version !in NOTES) {
        Kv.setGlobal(SEEN, version)
        return null
    }
    return version
}

/** Once a version's notes are seen: never again for it. */
fun whatsNewSeen() = Kv.setGlobal(SEEN, BuildConfig.VERSION_NAME)

/** Settings' "What's new": this version's notes again, as [tourAgain] is for the tour. */
var whatsNewAgain by androidx.compose.runtime.mutableStateOf(false)

/** This version has notes to show. */
val hasWhatsNew get() = BuildConfig.VERSION_NAME in NOTES

/** After an update: what is new in [version], one card each, and Got it. Back says Got it too. */
@Composable
fun WhatsNew(version: String, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val context = androidx.compose.ui.platform.LocalContext.current
    val features = NOTES[version].orEmpty()
    // The pattern Apple's own apps and WhatsNewKit use: a centred title with the app's name in the accent, then
    // rows of a large accent icon beside a bold title and a short line, no cards, and one button pinned below.
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            // Room at the foot for the pinned button, so the last row scrolls clear of it.
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 52.dp, bottom = 190.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "title") {
                Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("What's New in", style = Type.display.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold), textAlign = TextAlign.Center)
                    Text("Watchly $version", style = Type.display.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, color = Ink.accent), textAlign = TextAlign.Center)
                }
            }
            itemsIndexed(features, key = { _, f -> f.title }) { i, f -> FeatureRow(f, i) }
        }
        // The button stays put at the foot, the list fading out under it.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Ink.bg.copy(alpha = 0f), 0.28f to Ink.bg, 1f to Ink.bg))
                .navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button("Continue", onDone, Modifier.fillMaxWidth(), primary = true)
            Tap({ openUrl(context, "$REPO_URL/releases/tag/v$version") }, Modifier.padding(top = 6.dp), RoundedCornerShape(8.dp)) {
                Text("Full release notes", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = Type.label.copy(color = Ink.accent, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/** One feature: a large accent icon beside its name and a short line; fades and rises in, a beat after the one above. */
@Composable
private fun FeatureRow(f: Feature, index: Int) {
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 70L)
        shown.animateTo(1f, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    Row(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * 24.dp.toPx()
        },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.TopCenter) {
            Lc(f.icon, tint = Ink.accent, size = 30.dp)
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(f.title, style = Type.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
            Text(f.text, Modifier.padding(top = 3.dp), style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = Ink.muted))
        }
    }
}
