package com.torrentviewer.mobile

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Party
import com.torrentviewer.core.Room
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.clock
import com.torrentviewer.core.CertificateBadge
import com.torrentviewer.core.Facts
import com.torrentviewer.core.PartyState
import com.torrentviewer.core.TitleMark
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.torrentviewer.core.heroScrims
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.delay

/**
 * The invite as the film's own Detail page — its backdrop and logo heading the screen, the facts, the
 * plot — with Join the party where Resume would be and Not now where Start over is; then who invited
 * you, who is there, and where the film has got to.
 */
@Composable
private fun JoinAsDetail(room: Room, s: PartyState, title: Title?, people: List<com.torrentviewer.core.Member>, backdrop: String?, wide: Boolean) {
    val tmdb by androidx.compose.runtime.produceState<com.torrentviewer.core.TmdbTitle?>(null, s.media.imdb) {
        value = s.media.imdb?.let { com.torrentviewer.core.Tmdb.title(it, s.media.kind ?: "movie") }
    }
    val name = title?.title ?: s.media.title
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        if (wide && backdrop != null) Artwork(backdrop, name, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight(0.9f).heroScrims(), fill = false)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            if (!wide) Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                backdrop?.let { Artwork(it, name, Modifier.fillMaxSize().bottomScrim(0.6f), fill = false) }
                val mark = Modifier.align(Alignment.BottomStart).padding(horizontal = EDGE).widthIn(max = 260.dp)
                if (title != null) TitleMark(title, mark, maxHeight = 72.dp, style = Type.display)
                else Text(name, mark, style = Type.display.copy(color = Ink.text, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(horizontal = EDGE).then(if (wide) Modifier.statusBarsPadding().padding(top = 56.dp).widthIn(max = 460.dp) else Modifier.padding(top = 10.dp))) {
                if (wide) {
                    if (title != null) TitleMark(title, Modifier.widthIn(max = 320.dp).padding(bottom = 12.dp), maxHeight = 80.dp, style = Type.display)
                    else Text(name, Modifier.padding(bottom = 12.dp), style = Type.display.copy(color = Ink.text, fontWeight = FontWeight.Bold))
                }
                title?.let { Facts(it.copy(genres = it.genres.take(2))) }
                tmdb?.certificate?.let { CertificateBadge(it, Modifier.padding(top = 12.dp)) }

                // Who invited you, where the film has got to, and who else is there.
                val host = s.by.ifBlank { people.firstOrNull { it.leader }?.name ?: "The host" }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    NameDot(host, 34.dp)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text("$host invited you to a watch party", style = Type.label.copy(color = Ink.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val pulse = rememberInfiniteTransition(label = "live")
                            val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")
                            Box(Modifier.size(7.dp).alpha(if (s.playing) a else 1f).clip(CircleShape).background(if (s.playing) Color(0xFF30D158) else Ink.faint))
                            var now by remember { mutableStateOf(room.now()) }
                            LaunchedEffect(s) { while (true) { now = room.now(); delay(1000) } }
                            val others = people.filter { !it.leader && !it.me }
                            val count = maxOf(room.people, people.size)
                            Text(
                                "  ${if (s.playing) "Playing now" else "Paused"} at ${clock(s.posAt(now))}" + if (others.isEmpty()) "" else "  ·  $count watching",
                                style = Type.meta.copy(color = Ink.text.copy(alpha = 0.7f)),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                // Where Resume and Start over are on the Detail page.
                Button("Join the party", { room.accepted = true }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_users, primary = true)
                // The leader's torrent may be slow here, or too big: any release of the same film keeps in step.
                if (title != null) Button("Pick my own source", {
                    room.ownSource = true
                    room.accepted = true
                    ownSourceFor = title
                }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = L.drawable.lucide_ic_list_video)
                Button("Not now", { Party.leave() }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = L.drawable.lucide_ic_x)

                title?.plot?.let { plot ->
                    Text(plot, Modifier.padding(top = 14.dp), style = Type.body.copy(color = Ink.text.copy(alpha = 0.85f)), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (title?.directors.orEmpty().isNotEmpty()) Text(
                    "${if (title?.isSeries == true) "Creators" else "Director"}: ${title?.directors.orEmpty().take(2).joinToString(", ")}",
                    Modifier.padding(top = 10.dp),
                    style = Type.meta.copy(color = Ink.faint),
                )

                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top) {
                    Lc(L.drawable.lucide_ic_lock, tint = Ink.faint, size = 14.dp)
                    Text(
                        "  The film streams on this phone. Only play, pause and the spot are shared — encrypted, no account.",
                        style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp),
                    )
                }
            }
        }
        BackButton { Party.leave() }
        // What this page is, over the art: not the film's own page but an invite to it.
        Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(L.drawable.lucide_ic_users, tint = Ink.accent, size = 14.dp)
            Text("  WATCH PARTY", style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp))
        }
    }
}

/**
 * After a party is left — by Back out of the film, Leave, or its leader ending it — a card at the foot
 * of whatever screen is up, with the way back in. A guest goes straight back to the party; a leader
 * opens the same room again, so the link everyone has still works.
 */
@Composable
fun RejoinBar(last: com.torrentviewer.core.LastParty, top: Boolean) {
    val white = Color.White
    // Over the film, up top, clear of the seek bar; elsewhere above the tab bar.
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = if (top) 56.dp else 0.dp, bottom = if (top) 0.dp else 76.dp),
        contentAlignment = if (top) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().shadow(12.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(Color(0xF21A1A1E)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(L.drawable.lucide_ic_users, tint = Ink.accent, size = 20.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    when {
                        last.leader -> "You ended your watch party"
                        last.ended -> "The watch party has ended"
                        else -> "You left the watch party"
                    },
                    style = Type.label.copy(color = white, fontWeight = FontWeight.SemiBold),
                )
                Text(
                    if (last.leader) "Reopen it — everyone's link still works" else if (last.ended) "Rejoin to be there when it starts again" else "Rejoin to watch together again",
                    style = Type.meta.copy(color = white.copy(alpha = 0.65f), fontSize = 12.sp),
                )
            }
            Tap({ Party.rejoin() }, shape = RoundedCornerShape(10.dp)) {
                Text(if (last.leader) "Reopen" else "Rejoin", Modifier.background(Ink.accent).padding(horizontal = 16.dp, vertical = 10.dp), style = Type.label.copy(color = white, fontWeight = FontWeight.Bold))
            }
            Tap({ Party.last = null }, Modifier.padding(start = 4.dp), CircleShape) {
                Lc(L.drawable.lucide_ic_x, Modifier.padding(8.dp), tint = white.copy(alpha = 0.7f), size = 18.dp)
            }
        }
    }
}

/**
 * An invite, opened: its own screen rather than a sheet over whatever was up. First "Connecting…",
 * then what the party is watching — the film's own art, who leads it, who is there, where the film
 * has got to — and one big Join. Nothing plays until Join; Not now (or Back) leaves the room. Any web
 * page could hand the app a party link, so what it would play is always shown first.
 */
/** A guest's "Pick my own source": the film whose page App opens next, then clears. */
var ownSourceFor by mutableStateOf<Title?>(null)

@Composable
fun JoinPartyScreen(room: Room) {
    BackHandler { Party.leave() }
    val offer by room.states.collectAsState(room.states.replayCache.lastOrNull())
    val s = offer
    // The film's art and title from its imdb id, once the leader has said what it is.
    var title by remember { mutableStateOf<Title?>(null) }
    LaunchedEffect(s?.media?.imdb) {
        val imdb = s?.media?.imdb ?: return@LaunchedEffect
        title = Meta.title(imdb, s.media.kind ?: "movie")
    }
    // Long enough with nothing said: say why, rather than spin for ever.
    var waitedLong by remember { mutableStateOf(false) }
    LaunchedEffect(s == null) {
        waitedLong = false
        if (s == null) {
            delay(12_000)
            waitedLong = true
        }
    }
    val people = room.members.values.sortedWith(compareByDescending<com.torrentviewer.core.Member> { it.leader }.thenBy { it.name.lowercase() })
    // The Detail page's backdrop: the wide art, sharp, heading the screen and fading into it — from the
    // imdb id at once, before the title's own details are in.
    val backdrop = title?.wide ?: s?.media?.imdb?.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/background/medium/$it/img" } ?: s?.media?.poster
    val poster = title?.poster ?: s?.media?.poster
    val white = Color.White
    val wide = isWide()
    // The leader has ended it, or no one is in the room any more: say so, rather than wait for ever.
    if (room.over) {
        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Lc(L.drawable.lucide_ic_users, tint = white.copy(alpha = 0.6f), size = 44.dp)
                Text("This watch party is closed", Modifier.padding(top = 20.dp), style = Type.title.copy(color = white, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                Text(
                    "Its host has left, or ended it. Ask them for a new link.",
                    Modifier.padding(top = 6.dp).widthIn(max = 320.dp),
                    style = Type.meta.copy(color = white.copy(alpha = 0.7f)),
                    textAlign = TextAlign.Center,
                )
                Button("Close", { Party.leave() }, Modifier.padding(top = 32.dp).widthIn(min = 200.dp), icon = L.drawable.lucide_ic_x, primary = true)
            }
        }
        return
    }
    // Once the leader has said what is on: the film's own Detail page, with Join where Play would be.
    if (s != null) {
        JoinAsDetail(room, s, title, people, backdrop, wide)
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        backdrop?.let {
            if (wide) Artwork(it, s?.media?.title.orEmpty(), Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight(0.9f).heroScrims(), fill = false)
            else Artwork(it, s?.media?.title.orEmpty(), Modifier.fillMaxWidth().aspectRatio(1f).bottomScrim(0.6f), fill = false)
        }
        // Enough dark under the words that they read on any picture, the art still showing through.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.4f to Color.Black.copy(alpha = 0.45f), 0.62f to Color.Black)))

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Tap({ Party.leave() }, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(8.dp), tint = white, size = 24.dp) }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(CircleShape).background(Ink.accent.copy(alpha = 0.22f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Lc(L.drawable.lucide_ic_users, tint = Ink.accent, size = 14.dp)
                    Text("  WATCH PARTY", style = Type.meta.copy(color = white, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp))
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(40.dp))
            }

            Spacer(Modifier.height(28.dp))
            if (s == null) {
                Spacer(Modifier.height(80.dp))
                Spinner(size = 56.dp)
                Text(
                    when {
                        !waitedLong -> "Connecting to the party…"
                        room.connected -> "Connected — waiting for the host to play something"
                        else -> "Can't reach the party yet"
                    },
                    Modifier.padding(top = 20.dp),
                    style = Type.title.copy(color = white, fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                )
                Text(
                    when {
                        !waitedLong -> "Finding the room and asking the host what's on."
                        room.connected -> "Keep this open — the film shows here as soon as they start it."
                        else -> "Check this phone's connection. Watchly keeps trying."
                    },
                    Modifier.padding(top = 6.dp).widthIn(max = 320.dp),
                    style = Type.meta.copy(color = white.copy(alpha = 0.7f)),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(40.dp))
                Button("Not now", { Party.leave() })
                return@Column
            }

            Box(Modifier.width(170.dp).aspectRatio(2f / 3f).shadow(24.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Ink.surface)) {
                Artwork(poster, s.media.title, Modifier.fillMaxSize())
            }
            Text(
                title?.title ?: s.media.title,
                Modifier.padding(top = 22.dp),
                style = Type.title.copy(color = white, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
                textAlign = TextAlign.Center,
            )
            title?.let { t ->
                val facts = listOfNotNull(t.year?.toString(), t.runtime, t.genres.take(2).joinToString(" · ").ifBlank { null })
                if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), Modifier.padding(top = 6.dp), style = Type.meta.copy(color = white.copy(alpha = 0.7f)))
            }

            // Where the film has got to, live: a pulsing dot while it plays.
            Row(Modifier.padding(top = 16.dp).clip(CircleShape).background(white.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                val pulse = rememberInfiniteTransition(label = "live")
                val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")
                Box(Modifier.size(8.dp).alpha(if (s.playing) a else 1f).clip(CircleShape).background(if (s.playing) Color(0xFF30D158) else white.copy(alpha = 0.5f)))
                var now by remember { mutableStateOf(room.now()) }
                LaunchedEffect(s) { while (true) { now = room.now(); delay(1000) } }
                Text("  ${if (s.playing) "Playing now" else "Paused"} at ${clock(s.posAt(now))}", style = Type.label.copy(color = white, fontWeight = FontWeight.SemiBold))
            }

            // Who leads, and who is there.
            Column(Modifier.padding(top = 26.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(white.copy(alpha = 0.07f)).padding(16.dp)) {
                val host = s.by.ifBlank { people.firstOrNull { it.leader }?.name ?: "The host" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NameDot(host, 40.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(host, style = Type.label.copy(color = white, fontWeight = FontWeight.Bold))
                        Text("invited you to watch together", style = Type.meta.copy(color = white.copy(alpha = 0.65f)))
                    }
                    Lc(L.drawable.lucide_ic_crown, tint = Color(0xFFF5C542), size = 18.dp)
                }
                val others = people.filter { !it.leader && !it.me }
                val count = maxOf(room.people, people.size)
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Faces overlapping, as a chat app shows who is in a call.
                    Box(Modifier.height(28.dp).width((28 + 18 * (others.take(4).size - 1).coerceAtLeast(0)).dp)) {
                        others.take(4).forEachIndexed { i, m -> Box(Modifier.offset(x = (18 * i).dp)) { NameDot(m.name, 28.dp) } }
                    }
                    Text(
                        when {
                            others.isEmpty() -> "  You'd be the first to join"
                            else -> "  ${others.take(2).joinToString { it.name }}${if (others.size > 2) " and ${others.size - 2} more" else ""} · $count watching"
                        },
                        style = Type.meta.copy(color = white.copy(alpha = 0.8f)),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Button("Join the party", { room.accepted = true }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_play, primary = true)
            Spacer(Modifier.height(10.dp))
            Tap({ Party.leave() }, shape = RoundedCornerShape(10.dp)) {
                Text("Not now", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = Type.label.copy(color = white.copy(alpha = 0.75f)))
            }

            Row(Modifier.padding(top = 18.dp).widthIn(max = 340.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
                Lc(L.drawable.lucide_ic_lock, tint = white.copy(alpha = 0.5f), size = 14.dp)
                Text(
                    "  The film streams on this phone. Only play, pause and the spot are shared — encrypted, no account.",
                    style = Type.meta.copy(color = white.copy(alpha = 0.5f), fontSize = 12.sp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
