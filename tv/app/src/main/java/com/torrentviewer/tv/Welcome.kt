package com.torrentviewer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.GENRES
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Meta
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type

/**
 * The phone's first-launch tour, for the TV: one welcome over a wall of what is trending, then the genres the home
 * screen leads with. No sign-in step: the TV signs in from the phone, under Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Welcome(onDone: () -> Unit) {
    val posters by produceState(emptyList<Title>()) {
        value = (Meta.catalog("movie").take(12) + Meta.catalog("tv").take(12)).filter { it.poster != null }
    }
    var genres by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(SettingsStore.value.value.genres.toSet()) }
    val first = remember { FocusRequester() }
    BackHandler(genres) { genres = false }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        // The wall: two rows of posters across the top, fading into the page.
        Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            posters.chunked(8).take(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { Artwork(it.poster, it.title, Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = if (genres) 0.8f else 0.35f), 0.45f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black)))

        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Safe.x + 24.dp, vertical = Safe.y + 12.dp).widthIn(max = 820.dp)) {
            Text("WATCHLY", Modifier.padding(bottom = 14.dp), style = Type.title.copy(color = Ink.accent, fontWeight = FontWeight.Black, letterSpacing = 2.sp))
            if (!genres) {
                Text("Your media player", style = Type.hero)
                Text(
                    "Browse trending films, series and anime, and play them from the addons and servers you choose. Watchly comes with none.",
                    Modifier.padding(top = 10.dp, bottom = 26.dp).widthIn(max = 640.dp), style = Type.body.copy(color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp),
                )
                TvButton("Get started", { genres = true }, icon = L.drawable.lucide_ic_arrow_right, primary = true, requester = first)
                first.claim(genres)
            } else {
                Text("What do you like?", style = Type.hero)
                Text(
                    "Pick a few — your home screen leads with them. Change them any time in Settings.",
                    Modifier.padding(top = 10.dp, bottom = 20.dp), style = Type.body.copy(color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GENRES.forEachIndexed { i, g ->
                        val on = g in picked
                        Chip(g, on, onClick = { picked = if (on) picked - g else picked + g }, icon = if (on) L.drawable.lucide_ic_check else null, requester = first.takeIf { i == 0 })
                    }
                }
                Row(Modifier.padding(top = 24.dp)) {
                    TvButton(if (picked.isEmpty()) "Continue" else "Continue with ${picked.size}", {
                        val s = SettingsStore.value.value
                        SettingsStore.save(s.copy(genres = GENRES.filter { it in picked }))
                        onDone()
                    }, icon = L.drawable.lucide_ic_arrow_right, primary = true)
                    Spacer(Modifier.width(12.dp))
                    TvButton("Skip", onDone)
                }
                first.claim(genres)
            }
        }
    }
}
