package com.torrentviewer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Party
import com.torrentviewer.core.Profile
import com.torrentviewer.core.Type

/** A party code's length, as the leader's screen shows it (K7Q2-M9XD). */
private const val SLOTS = 8

/**
 * The rail's Join a party: the code the leader shared, as the phone's sheet asks for it — you beside two
 * free seats, the code filling its eight slots. Typed in the TV's own box below them, which knows the
 * TV keyboards' ways; once it takes, the invite's own sheet asks before anything plays.
 */
@Composable
fun JoinPartyScreen(me: Profile, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { box.requestFocus() } }
    fun join() {
        if (code.length < SLOTS) return run { error = "The code is $SLOTS letters and numbers." }
        if (Party.join(code) == null) return run { error = "That isn't a watch party code." }
        onDismiss()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy((-16).dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(76.dp).clip(CircleShape)) { Avatar(me, 76.dp) }
                repeat(2) { Seat() }
            }
            Text("Join a watch party", Modifier.padding(top = 22.dp), style = Type.title.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp))
            Text("Enter the $SLOTS-character code your friend shared.", Modifier.padding(top = 8.dp), style = Type.label.copy(color = Ink.muted))
            Row(Modifier.padding(top = 30.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                repeat(SLOTS) { i ->
                    if (i == SLOTS / 2) Text("–", Modifier.padding(horizontal = 6.dp, vertical = 12.dp), style = Type.title.copy(color = Ink.faint))
                    Slot(code.getOrNull(i), next = i == code.length, wrong = error != null, Modifier.weight(1f).padding(horizontal = 5.dp))
                }
            }
            Text(error ?: " ", Modifier.padding(top = 14.dp), style = Type.label.copy(color = Ink.danger))
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(
                    code, { typed -> code = typed.uppercase().filter { it.isLetterOrDigit() }.take(SLOTS); error = null },
                    "Type the code", Modifier.weight(1f), requester = box, onSubmit = ::join, leading = L.drawable.lucide_ic_users,
                )
                TvButton("Join", ::join, icon = L.drawable.lucide_ic_log_in, primary = true)
            }
            Text(
                "Only play, pause and where you are are shared, encrypted. The TV shows what's on before anything plays.\nTo host, start a party from the player.",
                Modifier.padding(top = 24.dp), style = Type.meta.copy(color = Ink.faint), textAlign = TextAlign.Center,
            )
        }
    }
}

/** One letter of the code over its line: the next to fill is lit, all of them red while the code is wrong. */
@Composable
private fun Slot(char: Char?, next: Boolean, wrong: Boolean, modifier: Modifier) {
    val line = when {
        wrong -> Ink.danger
        next -> Ink.text
        char != null -> Ink.muted
        else -> Ink.stroke
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(char?.toString() ?: "", style = Type.title.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp), maxLines = 1)
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(if (next) 3.dp else 2.dp).clip(RoundedCornerShape(2.dp)).background(line))
    }
}

/** A seat still free in the party: a dashed ring with a plus. */
@Composable
private fun Seat() {
    val ring = Ink.muted
    Box(
        Modifier.size(76.dp).drawBehind {
            drawCircle(ring, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))))
        },
        contentAlignment = Alignment.Center,
    ) { Lc(L.drawable.lucide_ic_plus, tint = ring, size = 26.dp) }
}
