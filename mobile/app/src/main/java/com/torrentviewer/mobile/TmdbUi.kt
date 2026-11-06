package com.torrentviewer.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.CastMember
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Title
import com.torrentviewer.core.Tmdb
import com.torrentviewer.core.TmdbCard
import com.torrentviewer.core.TmdbPerson
import com.torrentviewer.core.Type
import kotlinx.coroutines.launch

/** Instead of a title's page, on a kids profile, for what is rated above it. */
@Composable
fun TooOldForKids(title: Title, certificate: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        RoundIcon(L.drawable.lucide_ic_arrow_left, onBack, Modifier.statusBarsPadding().padding(start = 12.dp, top = 8.dp))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Lc(L.drawable.lucide_ic_lock, tint = Ink.muted, size = 34.dp)
            Text("Not for this profile", Modifier.padding(top = 14.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
            Text(
                "${title.title.ifEmpty { "This title" }} is rated $certificate. Switch to a grown-up's profile to watch it.",
                Modifier.padding(top = 6.dp),
                style = Type.meta.copy(color = Ink.muted, textAlign = TextAlign.Center),
            )
        }
    }
}

/** Opening a card asks TMDB for its IMDb id first, the one thing the rest of the app goes by. */
@Composable
private fun rememberCardOpener(onOpen: (Title) -> Unit): (TmdbCard) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(onOpen) {
        { card -> scope.launch { Tmdb.imdbOf(card)?.let { onOpen(Title(it, card.kind, card.title, year = card.year, poster = card.poster)) } } }
    }
}

/** The cast from TMDB: real photos, who each one plays, and a tap through to what else they're in. */
@Composable
fun PeopleRow(people: List<CastMember>, onPerson: ((CastMember) -> Unit)?) {
    Column(Modifier.padding(top = 18.dp)) {
        Text("Cast", Modifier.padding(bottom = 10.dp), style = Type.title.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(people, key = { it.id }) { person ->
                Tap({ onPerson?.invoke(person) }, Modifier.width(84.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(Ink.surface2), contentAlignment = Alignment.Center) {
                            Text(person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), style = Type.label.copy(fontWeight = FontWeight.Bold, color = Ink.muted))
                            person.photo?.let { Artwork(it, person.name, Modifier.fillMaxSize(), fill = false) }
                        }
                        Text(person.name, Modifier.padding(top = 6.dp), style = Type.meta.copy(fontSize = 12.sp, color = Ink.text, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        person.role?.let { Text(it, style = Type.meta.copy(fontSize = 11.sp, color = Ink.faint, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** "More like this", from TMDB's recommendations. */
@Composable
fun SimilarRail(cards: List<TmdbCard>, onOpen: (Title) -> Unit, modifier: Modifier = Modifier) {
    val open = rememberCardOpener(onOpen)
    Column(modifier.fillMaxWidth()) {
        Text("More like this", Modifier.padding(bottom = 10.dp), style = Type.title.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards, key = { "${it.kind}:${it.id}" }) { card ->
                TitleCard(card.poster, card.title, { open(card) }, Modifier.width(112.dp), info = card.year)
            }
        }
    }
}

/** One person: their photo, a little about them, and what they're in, best known first. */
@Composable
fun PersonScreen(id: Int, name: String, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val person by produceState<TmdbPerson?>(null, id) { value = Tmdb.person(id) }
    val open = rememberCardOpener(onOpen)
    var more by rememberSaveable(id) { mutableStateOf(false) }
    val p = person
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(110.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column(Modifier.statusBarsPadding().padding(top = 56.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Ink.surface2)) {
                            p?.photo?.let { Artwork(it, name, Modifier.fillMaxSize(), fill = false) }
                        }
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(name, style = Type.display, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val line = listOfNotNull(p?.knownFor, p?.credits?.size?.takeIf { it > 0 }?.let { "$it titles" }).joinToString("  ·  ")
                            if (line.isNotEmpty()) Text(line, Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint))
                        }
                    }
                    p?.about?.let { about ->
                        Tap({ more = !more }, Modifier.fillMaxWidth().padding(top = 14.dp)) {
                            Text(about, style = Type.body.copy(color = Ink.muted, fontSize = 14.sp), maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("Known for", Modifier.padding(top = 18.dp, bottom = 2.dp), style = Type.title.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
            }
            items(p?.credits.orEmpty(), key = { "${it.kind}:${it.id}" }) { card ->
                TitleCard(card.poster, card.title, { open(card) }, Modifier.fillMaxWidth(), info = card.year)
            }
        }
        RoundIcon(L.drawable.lucide_ic_arrow_left, onBack, Modifier.statusBarsPadding().padding(start = 12.dp, top = 8.dp))
    }
}
