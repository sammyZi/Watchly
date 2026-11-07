package com.torrentviewer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Legal
import com.torrentviewer.core.Type

/** A legal page's sections. Each can take focus: the D-pad only scrolls as far as what it can land on. */
@Composable
fun LegalText(sections: List<Pair<String, String>>) {
    Text(Legal.UPDATED, Modifier.padding(bottom = 14.dp), style = Type.meta.copy(color = Ink.faint))
    sections.forEach { (heading, text) ->
        Focusable({}, Modifier.widthIn(max = 860.dp).padding(bottom = 8.dp), RoundedCornerShape(8.dp), 1f) {
            Column(Modifier.padding(12.dp)) {
                Text(heading, style = Type.label.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                Text(text, Modifier.padding(top = 4.dp), style = Type.body.copy(color = Ink.muted, fontSize = 15.sp, lineHeight = 22.sp))
            }
        }
    }
}

/** What the viewer confirms before using the app; [changed]: they accepted an older version. Back leaves the app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Agreement(changed: Boolean, onAgree: () -> Unit) {
    var reading by remember { mutableStateOf<Pair<String, List<Pair<String, String>>>?>(null) }
    val target = remember { FocusRequester() }
    val open = reading
    if (open != null) {
        BackHandler { reading = null }
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
            Column(Modifier.fillMaxSize().background(Ink.bg).verticalScroll(rememberScrollState()).padding(horizontal = Safe.x, vertical = Safe.y)) {
                Row(Modifier.padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("Back", { reading = null }, icon = L.drawable.lucide_ic_arrow_left, requester = target)
                    Spacer(Modifier.width(18.dp))
                    Text(open.first, style = Type.display)
                }
                LegalText(open.second)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Ink.bg).padding(horizontal = Safe.x, vertical = Safe.y), verticalArrangement = Arrangement.Center) {
            Text(if (changed) "Our terms have changed" else "Before you start", style = Type.display)
            Text(Legal.SUMMARY, Modifier.padding(top = 14.dp).widthIn(max = 860.dp), style = Type.body.copy(color = Ink.muted, fontSize = 17.sp, lineHeight = 25.sp))
            Text(Legal.ACCEPT, Modifier.padding(top = 12.dp).widthIn(max = 860.dp), style = Type.body.copy(color = Ink.muted, fontSize = 17.sp, lineHeight = 25.sp))
            Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton("I accept", onAgree, icon = L.drawable.lucide_ic_check, primary = true, requester = target)
                TvButton("Privacy policy", { reading = "Privacy policy" to Legal.PRIVACY }, icon = L.drawable.lucide_ic_shield)
                TvButton("Terms and conditions", { reading = "Terms and conditions" to Legal.TERMS }, icon = L.drawable.lucide_ic_file_text)
                TvButton("Legal notice", { reading = "Legal notice" to Legal.NOTICE }, icon = L.drawable.lucide_ic_scale)
            }
        }
    }
    // Keyed on the page: each has its own button to land on.
    target.claim(open)
}
