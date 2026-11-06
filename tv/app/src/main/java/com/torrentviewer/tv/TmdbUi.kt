@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
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
    val back = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Lc(L.drawable.lucide_ic_lock, tint = Ink.muted, size = 40.dp)
            Text("Not for this profile", Modifier.padding(top = 16.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
            Text(
                "${title.title.ifEmpty { "This title" }} is rated $certificate. Switch to a grown-up's profile to watch it.",
                Modifier.padding(top = 6.dp, bottom = 24.dp),
                style = Type.meta.copy(color = Ink.muted, textAlign = TextAlign.Center),
            )
            TvButton("Go back", onBack, requester = back)
        }
    }
    back.claim()
}

/** Opening a card asks TMDB for its IMDb id first, the one thing the rest of the app goes by. */
@Composable
private fun rememberCardOpener(onOpen: (Title) -> Unit): (TmdbCard) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(onOpen) {
        { card -> scope.launch { Tmdb.imdbOf(card)?.let { onOpen(Title(it, card.kind, card.title, year = card.year, poster = card.poster)) } } }
    }
}

/** A TMDB card as the app's own poster. */
private fun TmdbCard.asTitle() = Title("", kind, title, year = year, poster = poster)

/** The cast from TMDB: real photos, who each one plays, and OK through to what else they're in. */
@Composable
fun PeopleRail(people: List<CastMember>, onPerson: ((CastMember) -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Cast", Modifier.padding(start = ORDER_LEFT, bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll(ORDER_ROW_LEAD)) {
            LazyRow(
                Modifier.padding(start = ORDER_LEFT - ORDER_ROW_LEAD).focusRestorer(),
                contentPadding = PaddingValues(start = ORDER_ROW_LEAD, end = Safe.x),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(people, key = { _, it -> it.id }) { _, person ->
                    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Focusable({ onPerson?.invoke(person) }, Modifier.size(96.dp), CircleShape, 1.08f) {
                            Box(Modifier.fillMaxSize().clip(CircleShape).background(Ink.surface2), contentAlignment = Alignment.Center) {
                                Text(person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), style = Type.label.copy(fontWeight = FontWeight.Bold, color = Ink.muted))
                                person.photo?.let { Artwork(it, person.name, Modifier.fillMaxSize(), fill = false) }
                            }
                        }
                        Text(person.name, Modifier.padding(top = 8.dp), style = Type.meta.copy(fontSize = 13.sp, color = Ink.text, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        person.role?.let { Text(it, style = Type.meta.copy(fontSize = 12.sp, color = Ink.faint, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis) }
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
        Text("More like this", Modifier.padding(start = ORDER_LEFT, bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeScroll(ORDER_ROW_LEAD)) {
            LazyRow(
                Modifier.padding(start = ORDER_LEFT - ORDER_ROW_LEAD).focusRestorer(),
                contentPadding = PaddingValues(start = ORDER_ROW_LEAD, end = Safe.x),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(cards, key = { _, it -> "${it.kind}:${it.id}" }) { _, card ->
                    PosterCard(card.asTitle(), { open(card) }, sub = card.year)
                }
            }
        }
    }
}

/** One person: their photo, a little about them, and what they're in, best known first. */
@Composable
fun PersonScreen(id: Int, name: String, onOpen: (Title) -> Unit) {
    val person by produceState(Tmdb.knownPerson(id), id) { value = Tmdb.person(id) }
    val open = rememberCardOpener(onOpen)
    /** The card that opened a title, to come back to. */
    var last by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    val aim = remember { last }
    val target = remember { FocusRequester() }
    val p = person
    val credits = p?.credits.orEmpty()

    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberMinimalScroll(60.dp)) {
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ORDER_LEFT, end = Safe.x, top = Safe.y + 12.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Ink.surface2)) {
                        p?.photo?.let { Artwork(it, name, Modifier.fillMaxSize(), fill = false) }
                    }
                    Column(Modifier.padding(start = 24.dp).widthIn(max = 620.dp)) {
                        Text(name, style = Type.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val line = listOfNotNull(p?.knownFor, credits.size.takeIf { it > 0 }?.let { "$it titles" }).joinToString("  ·  ")
                        if (line.isNotEmpty()) Text(line, Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint))
                        p?.about?.let { Text(it, Modifier.padding(top = 10.dp), style = Type.body.copy(color = Ink.muted, fontSize = 14.sp), maxLines = 5, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            items(credits, key = { "${it.kind}:${it.id}" }) { card ->
                PosterCard(
                    card.asTitle(),
                    { last = card.id; open(card) },
                    sub = card.year,
                    requester = if (card.id == (aim ?: credits.first().id)) target else null,
                )
            }
        }
    }
    target.claim(credits.isEmpty())
}
