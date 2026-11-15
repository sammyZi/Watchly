package com.torrentviewer.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.composables.icons.lucide.R as L

/** What a watch party needs of the player it rides on: each app's player fills this in. */
interface PartyPlayer {
    /** Seconds, read off the player now. */
    fun time(): Double
    /** Where the player is or is going (mid-jump, the target): what the leader tells the room. */
    val spot: Double
    val paused: Boolean
    val loaded: Boolean
    /** Loading, or playing with no new frames for a while. */
    val starved: Boolean
    /** A jump not landed yet. */
    val seeking: Boolean
    /** Held for 2×: the leader's film then runs at that. */
    val boosting: Boolean
    /** The speed the film runs at now (held for 2×, that): the leader's is the room's. */
    val rate: Float
    val fileName: String?
    fun setPaused(on: Boolean)
    /** The player's own speed, for steering, leaving the viewer's choice alone. */
    fun steerRate(r: Float)
    /** A guest's shown speed — the speed button, "Ends at" — set to the room's. */
    fun showRate(r: Float)
    /** A jump the party decided on, not to be asked about again. */
    fun jump(seconds: Double)
    fun say(text: String, icon: Int? = null)
    fun savePosition()
}

/** A guest's pause ([seekTo] null) or jump, waiting on "just me" or "everyone". */
class PartyChoice(val seekTo: Double?)

/** A reaction on its way up the picture, from [x] (0–1 of the width). */
class Floater(val id: Long, val emoji: String, val from: String, val x: Float)

/**
 * The watch party's side of one player, the same on the phone and the TV: what the leader tells the
 * room, how a guest follows it, waiting for whoever is loading, and the asks between them. Each app
 * draws its own cards from this state. One per file, as the player is.
 *
 * The leader says where it is on every play, pause, jump, change of speed or of who is waited for,
 * every [Party.BEAT_MS], and on each hello. A guest follows its play and pause on each change; once a
 * second it closes a small gap by speed and a big one by a jump — never while loading, and never
 * again until the last jump has had [Party.JUMP_SETTLE_MS] to load (jumping on, the gap grew while it
 * loaded, and the film reopened every two seconds under a spinner). Anyone stuck loading holds the
 * film, for as long as the leader's Settings allow. A guest can pause or jump "just me" and watch on
 * its own until Catch up.
 */
class PartyDriver(private val watch: Watch) {
    /** Guest: what the leader last said about this file. */
    var leaderState by mutableStateOf<PartyState?>(null)
        private set
    /** Guest: the leader's play or pause as last followed, so only a change of it is followed again. */
    private var leaderPlaying: Boolean? = null
    /** Leader: a guest's ask, until allowed, refused or ten seconds old. */
    var ask by mutableStateOf<PartyAsk?>(null)
    /** Guest: a pause or jump asked for — for this one alone, or everyone? */
    var choice by mutableStateOf<PartyChoice?>(null)
    /** Guest: watching on its own until Catch up. */
    var detached by mutableStateOf(false)
        private set
    /** This player stuck loading long enough to hold the party. */
    var stuck by mutableStateOf(false)
        private set
    /** Leader: guests stuck loading, the film held for them. */
    val waitingFor = mutableStateListOf<String>()
    /** Leader: guests gone on without, until they say they are through. */
    private val skipped = mutableSetOf<String>()
    /** Leader: paused for [waitingFor], not by anyone's hand — so it plays on by itself. */
    var held by mutableStateOf(false)
        private set
    val floating = mutableStateListOf<Floater>()
    var chatPeek by mutableStateOf<ChatLine?>(null)

    private var p: PartyPlayer? = null

    val room get() = Party.room
    val guest get() = room?.leader == false
    val leader get() = room?.leader == true
    /** This one's name as the room shows it, numbered if someone else has it too. */
    val me get() = room?.myName() ?: Party.me

    /** Who the film is held for, as this phone sees it: the leader's list, or the room's word for it. */
    val heldFor: List<String>
        get() = when {
            leader -> if (held) waitingFor.toList() else emptyList()
            guest -> leaderState?.takeIf { !it.playing }?.wait.orEmpty()
            else -> emptyList()
        }

    /** Leader: where this player is, to the room. */
    fun tell() {
        val r = room?.takeIf { it.leader } ?: return
        val p = p ?: return
        val media = watch.partyMedia() ?: return
        // The player's own clock, not the last report of it; mid-jump, where it is going.
        val pos = if (!p.seeking && p.loaded) p.time().takeIf { it > 0 } ?: p.spot else p.spot
        val wait = waitingFor.toList() + listOfNotNull(r.myName().takeIf { stuck })
        val s = SettingsStore.value.value
        r.tell(PartyState(pos, !p.paused && p.loaded && !stuck, media, p.rate, r.now(), wait, s.partyAsks != "off", r.myName()))
    }

    /** Leader: stop holding the film for whoever is loading; they catch up when they can. */
    fun goOnWithout() {
        skipped += waitingFor
        waitingFor.clear()
        held = false
        p?.setPaused(false)
    }

    /** Play/pause pressed. True if the party took it and the player is to do nothing more. */
    fun onTogglePause(): Boolean {
        val p = p ?: return false
        val r = room ?: return false
        if (r.leader) {
            // Held for someone loading: Play is "go on without them".
            if (!held) return false
            goOnWithout()
            return true
        }
        when {
            // On its own: its play and pause are its own, until Catch up.
            detached -> return false
            // Paused by leaving the app while the leader played: back in step at once, nothing to ask.
            p.paused && leaderPlaying == true -> {
                p.setPaused(false)
                r.hello()
            }
            !p.paused -> choice = PartyChoice(null)
            leaderState?.asks == false -> p.say("Only the leader controls this party", USERS)
            else -> {
                r.ask("play")
                p.say("Asked to play", USERS)
            }
        }
        return true
    }

    /** A jump the viewer asked for. True if the party took it (a guest is asked whose jump it is). */
    fun onSeek(seconds: Double): Boolean {
        if (!guest || detached) return false
        choice = PartyChoice(seconds.coerceAtLeast(0.0))
        return true
    }

    /** The system's Play (a headset, the lock screen): true if the party took it. */
    fun onRemotePlay(): Boolean {
        if (!held) return false
        goOnWithout()
        return true
    }

    /** A guest's speed is the leader's. */
    val canPickSpeed get() = !guest

    fun justMe() {
        val c = choice ?: return
        val p = p ?: return
        choice = null
        detached = true
        val to = c.seekTo
        if (to != null) p.jump(to) else {
            p.setPaused(true)
            p.savePosition()
        }
        p.say("Watching on your own — Catch up any time", USER)
    }

    fun everyone() {
        val c = choice ?: return
        choice = null
        val to = c.seekTo
        if (to != null) room?.ask("seek", to) else room?.ask("pause")
        p?.say(if (to != null) "Asked to jump to ${clock(to)} for everyone" else "Asked to pause for everyone", USERS)
    }

    /** Guest: back with the party — its play or pause, and its spot (the steering jumps there). */
    fun catchUp() {
        detached = false
        val playing = leaderState?.playing ?: true
        leaderPlaying = playing
        p?.setPaused(!playing)
        room?.hello()
        p?.say("Catching up with the party", USERS)
    }

    /** Leader: a guest's ask, done. */
    fun allow(a: PartyAsk) {
        val p = p ?: return
        when (a.what) {
            "pause" -> p.setPaused(true)
            // Held for someone loading, play is going on without them — or the banner stays over a playing film.
            "play" -> if (held) goOnWithout() else p.setPaused(false)
            "seek" -> p.jump(a.pos)
        }
    }

    /** Everything that runs while the player is up. [panelOpen]: the party panel is showing (no chat peeks then). */
    @Composable
    fun Effects(player: PartyPlayer, panelOpen: Boolean) {
        p = player
        val room = Party.room
        val loaded = player.loaded
        val paused = player.paused

        // Leader: the room hears every play, pause and change of who is waited for, a beat every few
        // seconds, and an answer to each hello.
        LaunchedEffect(room, paused, loaded, player.rate, stuck, waitingFor.size) {
            if (room?.leader != true) return@LaunchedEffect
            while (true) {
                tell()
                delay(Party.BEAT_MS)
            }
        }
        LaunchedEffect(room) {
            if (room?.leader != true) return@LaunchedEffect
            launch { room.hellos.collect { tell() } }
            launch {
                room.stuck.collect { (who, on) ->
                    if (!on) {
                        waitingFor.remove(who)
                        skipped.remove(who)
                    } else if (SettingsStore.value.value.partyWaitSec > 0 && who !in skipped && who !in waitingFor) waitingFor.add(who)
                }
            }
            // As Settings say: asked (Allow / No), done at once, or not heard.
            room.asks.collect { a ->
                when (SettingsStore.value.value.partyAsks) {
                    "allow" -> {
                        allow(a)
                        p?.say("${a.from}: ${if (a.what == "seek") "jumped to ${clock(a.pos)}" else a.what}", USERS)
                    }
                    "off" -> Unit
                    else -> ask = a
                }
            }
        }
        // Leader: anyone loading holds the film — Jellyfin's "waiting" — and it plays on when they are through.
        LaunchedEffect(waitingFor.size, loaded) {
            if (room?.leader != true || !loaded) return@LaunchedEffect
            if (waitingFor.isNotEmpty() && !player.paused) {
                held = true
                player.setPaused(true)
            } else if (waitingFor.isEmpty() && held) {
                held = false
                player.setPaused(false)
            }
        }
        // Never held for ever: a phone that lost its connection mid-wait says nothing more.
        LaunchedEffect(held) {
            if (!held) return@LaunchedEffect
            delay(SettingsStore.value.value.partyWaitSec.coerceAtLeast(1) * 1000L)
            p?.say("Went on without ${waitingFor.joinToString()}", USERS)
            goOnWithout()
        }
        LaunchedEffect(ask) {
            if (ask == null) return@LaunchedEffect
            delay(10_000)
            ask = null
        }
        LaunchedEffect(choice) {
            if (choice == null) return@LaunchedEffect
            delay(8_000)
            choice = null
        }

        // Guest: the leader's play and pause, followed on each change of them; its speed shown here
        // too; who did what said. Paused here while the leader plays (Home, a call): left alone until
        // Play brings it back in step.
        LaunchedEffect(room, loaded) {
            if (room == null || room.leader || !loaded) return@LaunchedEffect
            // What was replayed is from before the film opened here: ask for where things are now.
            room.hello()
            room.states.collect { s ->
                // The leader's file — or, on a copy of one's own, the same film.
                val same = s.media.file == p?.fileName || (room.ownSource && s.media.imdb != null && s.media.imdb == watch.item?.imdb)
                if (!same) return@collect
                val before = leaderState
                leaderState = s
                val who = s.by.ifBlank { "The leader" }
                val sped = s.rate != p?.rate
                if (sped) p?.showRate(s.rate)
                // Who did what, as Nuvio's party says it — not for a hold for someone loading, nobody's hand.
                if (before != null && s.wait.isEmpty() && before.wait.isEmpty()) when {
                    sped -> p?.say("$who set the speed to ${rateText(s.rate)}", GAUGE)
                    s.playing != before.playing -> p?.say(if (s.playing) "$who pressed play" else "$who paused", if (s.playing) PLAY else PAUSE)
                    abs(s.pos - before.posAt(s.stamp)) > 3 -> p?.say("$who jumped to ${clock(s.pos)}", JUMP)
                }
                if (s.playing != leaderPlaying) {
                    leaderPlaying = s.playing
                    // On its own, this one keeps its own play and pause until Catch up.
                    if (!detached) p?.setPaused(!s.playing)
                }
            }
        }
        // Guest: once a second, how far from the leader on the shared clock.
        LaunchedEffect(room, loaded) {
            if (room == null || room.leader || !loaded) return@LaunchedEffect
            var applied = 0f
            var jumped = 0L
            fun settled() = System.currentTimeMillis() - jumped > Party.JUMP_SETTLE_MS
            while (true) {
                delay(1000)
                val s = leaderState ?: continue
                val pl = p ?: continue
                if (pl.boosting || pl.seeking) continue
                fun steer(r: Float) {
                    if (r != applied) pl.steerRate(r)
                    applied = r
                }
                fun jump(to: Double) {
                    jumped = System.currentTimeMillis()
                    steer(s.rate)
                    pl.jump(to)
                }
                val here = pl.time()
                when {
                    detached -> steer(s.rate)
                    !s.playing && pl.paused -> {
                        steer(s.rate)
                        if (abs(s.pos - here) > 0.5 && settled()) jump(s.pos)
                    }
                    // Out of step by choice, or loading: nothing to steer. Loading long, the party waits for it.
                    pl.paused || stuck || pl.starved || !s.playing -> steer(s.rate)
                    else -> {
                        val drift = s.posAt(room.now()) - here
                        if (abs(drift) > Party.SKIP_SECONDS) {
                            if (settled()) jump(s.posAt(room.now())) else steer(Party.catchUpRate(drift, s.rate))
                        } else steer(if (SettingsStore.value.value.partySmooth) Party.catchUpRate(drift, s.rate) else s.rate)
                    }
                }
            }
        }
        // Out of the party: the viewer's own speed again, not the leader's.
        LaunchedEffect(room) {
            if (room != null || leaderState == null) return@LaunchedEffect
            leaderState = null
            detached = false
            val own = SettingsStore.value.value.playbackRate
            player.showRate(own)
            player.steerRate(own)
        }
        // Everyone's list shows what this player is doing.
        LaunchedEffect(room, loaded, stuck, paused, detached) {
            room?.status(
                when {
                    !loaded -> "opening"
                    detached -> "behind"
                    stuck -> "loading"
                    paused -> "paused"
                    else -> "watching"
                },
            )
        }
        // Loading for more than a moment, once the film has started here: the party waits for it.
        // One still opening the torrent is not waited for — it joins where the others have got to.
        val starved = loaded && player.starved
        LaunchedEffect(starved) {
            if (!starved) {
                stuck = false
                return@LaunchedEffect
            }
            delay(Party.STUCK_MS)
            stuck = true
        }
        // On its own, a guest's loading is its own business.
        LaunchedEffect(stuck, detached) { if (room?.leader == false) room.stuck(stuck && !detached) }
        DisposableEffect(Unit) { onDispose { if (stuck) Party.room?.takeIf { !it.leader }?.stuck(false) } }

        // Reactions float up over the picture; a chat line shows a moment with the panel shut.
        LaunchedEffect(room) {
            room?.reactions?.collect { (who, emoji) ->
                if (SettingsStore.value.value.partyShowReactions) floating.add(Floater(System.nanoTime(), emoji, who, 0.55f + Math.random().toFloat() * 0.35f))
            }
        }
        val open = panelOpen
        LaunchedEffect(open) { if (open) room?.unread = 0 }
        LaunchedEffect(room) {
            val r = room ?: return@LaunchedEffect
            var seen = r.chat.size
            snapshotFlow { r.chat.size }.collect { n ->
                val last = r.chat.lastOrNull()
                if (n > seen && last != null && !last.mine && !openNow && SettingsStore.value.value.partyShowChat) chatPeek = last
                seen = n
            }
        }
        openNow = panelOpen
        LaunchedEffect(chatPeek) {
            if (chatPeek == null) return@LaunchedEffect
            delay(5000)
            chatPeek = null
        }
    }

    /** Read by the chat collector, which outlives the composition that set it. */
    @Volatile private var openNow = false

    private companion object {
        val USERS = L.drawable.lucide_ic_users
        val USER = L.drawable.lucide_ic_user
        val GAUGE = L.drawable.lucide_ic_gauge
        val PLAY = L.drawable.lucide_ic_play
        val PAUSE = L.drawable.lucide_ic_pause
        val JUMP = L.drawable.lucide_ic_chevrons_right
        fun rateText(r: Float) = "${r.toString().removeSuffix(".0")}×"
    }
}
