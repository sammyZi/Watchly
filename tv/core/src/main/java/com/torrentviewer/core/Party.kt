package com.torrentviewer.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Enough for a guest to open the same file: a magnet, never a debrid link (it holds the host's key). */
data class PartyMedia(val uri: String, val file: String, val title: String, val imdb: String?, val kind: String?, val poster: String?)

/**
 * The leader's player: [pos] seconds at [stamp] (the relay's clock, ms), going at [rate] or not, and
 * who the party is holding for while they load ([wait]).
 */
data class PartyState(
    val pos: Double,
    val playing: Boolean,
    val media: PartyMedia,
    val rate: Float = 1f,
    val stamp: Long = 0,
    val wait: List<String> = emptyList(),
    /** Whether the leader takes requests: off, a guest's buttons say so instead of asking. */
    val asks: Boolean = true,
    /** The leader's name, for "Sam paused" and the invite's preview. */
    val by: String = "",
) {
    /** Where the leader is now, as best this phone can tell from [now] on the shared clock. */
    fun posAt(now: Long): Double = if (playing && stamp > 0) pos + (now - stamp).coerceAtLeast(0) / 1000.0 * rate else pos
}

/** A guest asking the leader to pause, play or jump to [pos]. */
data class PartyAsk(val from: String, val what: String, val pos: Double)

/** Someone in the room as they last said: [status] is "opening", "watching", "paused", "loading" or "behind". */
data class Member(val id: String, val name: String, val leader: Boolean, val status: String, val seen: Long, val me: Boolean = false)

/** A line in the party's chat; [from] null is the room's own ("Sam joined"). */
data class ChatLine(val from: String?, val text: String, val at: Long, val mine: Boolean = false)

/** The quick reactions, sent and floated over the picture. */
val PARTY_REACTIONS = listOf("😂", "😮", "😱", "❤️", "👏", "🔥")

/**
 * A watch party. Everyone streams the torrent themselves; only the leader's play, pause and position
 * go round, through the relay in party/ (a Cloudflare Worker), which passes each message to the rest
 * of the room. Messages are AES-GCM sealed with a key that exists only in the invite link, so the
 * relay sees noise. No account: the room is a random id, and having the link is being invited.
 *
 * As Jellyfin's SyncPlay and Syncplay do it:
 *  - One clock: every phone pings the relay for its time and keeps the reply with the shortest round
 *    trip, so a guest knows how old the leader's position is however slow its own connection.
 *  - The leader says where it is on every play, pause and jump, every [BEAT_MS], and on each hello.
 *  - A guest follows: the same play/pause; off by a little, it plays up to [NUDGE] faster or slower
 *    until it is back (unnoticeable); off by more than [SKIP_SECONDS], it jumps.
 *  - Everyone waits for the slowest: a phone stuck loading for [STUCK_MS] says so, and the leader
 *    holds the film until it is through — or until the leader goes on without it (Settings say how long at most).
 *  - A guest's own pause or jump is theirs alone (Catch up rejoins), or asked of the leader.
 *  - A guest may play a release of their own ([Room.ownSource]): followed by the imdb id, not the file.
 */
object Party {
    /** The Worker's address (https://…workers.dev); blank turns the feature off. */
    var server = ""
    /** The profile in use's name: what the others see, unless Settings gives a party name. */
    var profileName = "Someone"
    val me get() = SettingsStore.value.value.partyName.trim().ifBlank { profileName }
    var room by mutableStateOf<Room?>(null)
        private set
    /** Into the engine log ("party: …"), set by each app: what the party did, for when a join goes wrong. */
    var log: (String) -> Unit = {}

    const val BEAT_MS = 10_000L
    /** A guest this far from the leader jumps to it; nearer, it changes speed instead. */
    const val SKIP_SECONDS = 2.0
    /** After a jump, this long for it to load before another is tried. */
    const val JUMP_SETTLE_MS = 10_000L
    /** Closer than this counts as together. */
    const val DEADZONE_SECONDS = 0.15
    /** The most a guest's speed is changed by to catch up or fall back: 8%, not something you hear. */
    const val NUDGE = 0.08
    /** Loading this long holds the party (for as long as the leader's Settings allow). */
    const val STUCK_MS = 2_000L
    /** A link opened on a room with no one in it, for this long, is a closed party. */
    const val EMPTY_MS = 6_000L

    /** How fast a guest [drift] seconds behind (negative: ahead) plays at to close the gap, for a leader at [rate]. */
    fun catchUpRate(drift: Double, rate: Float): Float =
        if (kotlin.math.abs(drift) < DEADZONE_SECONDS) rate else (rate * (1 + (drift / 4).coerceIn(-NUDGE, NUDGE))).toFloat()

    /** This build has a relay: Settings offers the feature. */
    val available get() = server.isNotBlank()
    /** …and the viewer hasn't turned it off. */
    val enabled get() = available && SettingsStore.value.value.partyOn

    fun host(film: PartyMedia? = null): Room = Room(randomHex(16), random(16), leader = true).also { it.film = film; enter(it) }

    /** From the invite (https://…/j/<id>#<key> or watchly://party/<id>?k=<key>); null if it isn't one. */
    fun join(link: String): Room? {
        val (id, key) = parseInvite(link) ?: return null
        if (room?.id == id) return room
        // A leader opening its own invite — from the chat it was sent to — is back in its own party, as its
        // leader: joining it as a guest left the room with no one playing anything.
        if (last?.let { it.id == id && it.leader } == true) {
            rejoin()
            return room
        }
        return Room(id, hex(key), leader = false).also(::enter)
    }

    /**
     * Out of the party — by Back out of the film, Leave, or the leader ending it ([ended]). Kept in
     * [last] so it can be rejoined: a leader opens the same room again (everyone's link still works),
     * a guest goes straight back in. An invite turned down at its preview isn't kept.
     */
    fun leave(ended: Boolean = false) {
        room?.let {
            log(if (it.leader) "ended the party" else if (ended) "the leader ended the party" else "left the party")
            if (it.accepted) last = LastParty(it.id, it.keyHex, it.leader, ended, it.lastTold?.media, it.ownSource)
        }
        room?.close()
        room = null
    }

    /** The party last left, to rejoin; null once rejoined, dismissed, or another one begins. */
    var last by mutableStateOf<LastParty?>(null)

    fun rejoin() {
        val l = last ?: return
        last = null
        enter(Room(l.id, hex(l.key), l.leader).also { it.accepted = true; it.ownSource = l.own; it.film = l.media })
        // A leader's film, open again — the room with nothing on was why Reopen seemed to do nothing.
        if (l.leader) l.media?.let(reopen)
    }

    /** Opens a leader's film for [rejoin]: each app's Watch, set at start. */
    var reopen: (PartyMedia) -> Unit = {}

    private fun enter(r: Room) {
        room?.close()
        room = r
        last = null
        log("${if (r.leader) "started" else "joining"} room ${r.id.take(6)}…")
        r.connect()
    }

    /** Long enough for two missed heartbeats: someone not heard from in this long has gone. */
    const val GONE_MS = 75_000L
    const val CHAT_MAX = 300

    internal fun parseInvite(link: String): Pair<String, String>? {
        val runs = Regex("[0-9a-f]{32}").findAll(link.lowercase()).map { it.value }.toList()
        return if (runs.size >= 2) runs[0] to runs[1] else null
    }

    internal val client by lazy { OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build() }
}

/**
 * A party left, to rejoin: [leader] if this one led it, [ended] if its leader ended it, and [media] what
 * a leader was playing — Reopen opens the room and that film again, or it would be a room with nothing on.
 */
data class LastParty(val id: String, val key: String, val leader: Boolean, val ended: Boolean, val media: PartyMedia? = null, val own: Boolean = false)

class Room internal constructor(val id: String, private val key: ByteArray, val leader: Boolean) {
    /**
     * The invite. A leader's carries the film's imdb id and name after the `?`, so a chat app's preview of it
     * shows the film's art (the page sets it as og:image); the key stays after the `#`, which no server sees.
     */
    val link: String get() {
        val m = film ?: lastTold?.media
        val imdb = m?.imdb?.takeIf { Regex("tt\\d{1,10}").matches(it) }
        val query = if (m == null || imdb == null) "" else "?i=$imdb&t=" + java.net.URLEncoder.encode(m.title.take(80), "UTF-8").replace("+", "%20")
        return "${Party.server.trimEnd('/')}/j/$id$query#${hex(key)}"
    }
    /** Leader: the film the invite is for, from the start — [lastTold] is only set once the player has said something. */
    var film: PartyMedia? = null
    internal val keyHex get() = hex(key)
    /** Everyone in the room, this phone included. */
    var people by mutableIntStateOf(1)
        private set
    var connected by mutableStateOf(false)
        private set
    /** A guest who has seen what the party plays and said yes; until then nothing opens. A leader's own party is accepted. */
    var accepted by mutableStateOf(leader)
    /**
     * A guest playing a copy of their own (another release of the same film, picked from its sources)
     * rather than the leader's torrent: nothing of the leader's opens, and the film is followed by its
     * imdb id instead of the file name. ponytail: one id for a whole series — the guest picks the episode.
     */
    var ownSource by mutableStateOf(false)
    /** Guests: the leader's player, the last one replayed to whoever starts listening. */
    val states = MutableSharedFlow<PartyState>(replay = 1, extraBufferCapacity = 8)
    /** Leader: a guest wanting something. */
    val asks = MutableSharedFlow<PartyAsk>(extraBufferCapacity = 8)
    /** Leader: a guest wants to know where things are. */
    val hellos = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Leader: a guest stuck loading (true), or through it (false). */
    val stuck = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 16)
    /** Guests: the leader ended it. */
    val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Guests: this party is over — its leader ended it, or the link was opened on an empty room (the
     * leader is long gone, so no one answers). The join screen says so rather than wait for ever.
     * A leader coming back with a film turns it off again.
     */
    var over by mutableStateOf(false)
        private set
    /** Who is here, by a random id per phone (two people may share a name); this phone included. */
    val members = androidx.compose.runtime.mutableStateMapOf<String, Member>()
    val chat = androidx.compose.runtime.mutableStateListOf<ChatLine>()
    /** Chat lines from others since the party panel was last open. */
    var unread by mutableIntStateOf(0)
    /** Someone's reaction, this phone's own included: (name, emoji). */
    val reactions = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)

    /** This phone in [members]. */
    private val self = randomHex(6)
    /** What this phone's player is doing, told to the room on each change. */
    private var status = "opening"

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private val since = System.currentTimeMillis()

    /**
     * The relay's clock less this phone's monotonic one, from the ping with the shortest round trip of
     * the last few. Monotonic, as Nuvio's party clock is: a phone whose time is set by hand, or jumps
     * at a network time update, keeps the same idea of "now" in the room.
     */
    @Volatile private var offset = System.currentTimeMillis() - mono()
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Now on the room's shared clock, ms. */
    fun now() = mono() + offset

    internal fun connect() {
        if (closed) return
        val url = Party.server.trimEnd('/').replaceFirst("http", "ws") + "/room/$id"
        socket = Party.client.newWebSocket(Request.Builder().url(url).header("User-Agent", USER_AGENT).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Party.log("connected to the relay")
                AppScope.launch {
                    connected = true
                    // Quick pings until the clock is settled, then one every half minute: a phone that
                    // moves from Wi-Fi to mobile data gets a new round trip.
                    var n = 0
                    while (socket === webSocket && !closed) {
                        webSocket.send(JSONObject().put("t", "time").put("c", mono()).toString())
                        // Here, with the clock's slower pings: the heartbeat that keeps this phone on everyone's list.
                        if (n == 0 || n >= 5) here()
                        val cutoff = System.currentTimeMillis() - Party.GONE_MS
                        members.values.filter { !it.me && it.seen < cutoff }.forEach { gone(it.id) }
                        delay(if (++n < 5) 1000 else 30_000)
                    }
                }
                if (!leader) {
                    hello()
                    // Nobody else in the room, and no word from a leader, after this long: it is closed.
                    // (A leader's own reconnect is a few seconds; it says where it is every 10.)
                    AppScope.launch {
                        delay(Party.EMPTY_MS)
                        if (!closed && socket === webSocket && people <= 1 && states.replayCache.isEmpty()) over = true
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Party.log("relay connection failed: ${t.message ?: t.javaClass.simpleName}${response?.let { " (${it.code})" }.orEmpty()}")
                lost(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
        })
    }

    /** Dropped — a tunnel, a Wi-Fi switch: in again a few seconds on, for as long as the party lasts. */
    private fun lost(ws: WebSocket) {
        if (ws !== socket) return
        AppScope.launch {
            connected = false
            delay(3000)
            if (!closed && ws === socket) connect()
        }
    }

    internal fun close() {
        send(JSONObject().put("t", "bye").put("id", self))
        if (leader) send(JSONObject().put("t", "end"))
        closed = true
        socket?.close(1000, null)
        socket = null
    }

    /** Leader: what it last told the room — kept, so Reopen knows the film. */
    var lastTold: PartyState? = null
        private set

    fun tell(state: PartyState) {
        lastTold = state
        send(encodeState(state))
    }

    fun ask(what: String, pos: Double = 0.0) = send(JSONObject().put("t", "ask").put("from", myName()).put("what", what).put("pos", pos))

    fun hello() = send(JSONObject().put("t", "hello").put("from", myName()))

    fun stuck(on: Boolean) = send(JSONObject().put("t", "stuck").put("from", myName()).put("on", on))

    /** This phone's own name as the room shows it — numbered, if another here has the same. */
    fun myName(): String = members[self]?.name ?: Party.me

    /** Each member's name as they gave it; [members] hold the names as shown. */
    private val bases = mutableMapOf<String, String>()

    /**
     * Two people called Sam are "Sam 1" and "Sam 2" — the same numbers on every phone, TV and PC, so
     * "waiting for Sam 2" and a chat line from "Sam 1" mean the same person to everyone: the leader
     * first, then by each one's id. The name is what the party knows people by.
     */
    private fun renumber() {
        val groups = members.values.groupBy { (bases[it.id] ?: it.name).trim().lowercase() }
        for (group in groups.values) {
            val ordered = group.sortedWith(compareByDescending<Member> { it.leader }.thenBy { it.id })
            ordered.forEachIndexed { i, m ->
                val base = bases[m.id] ?: m.name
                val shown = if (ordered.size == 1) base else "$base ${i + 1}"
                if (m.name != shown) members[m.id] = m.copy(name = shown)
            }
        }
    }

    /** What the player here is doing now; the room hears of a change at once. */
    fun status(now: String) {
        if (now == status) return
        status = now
        here()
    }

    fun say(text: String) {
        val t = text.trim().take(Party.CHAT_MAX)
        if (t.isEmpty()) return
        val me = myName()
        send(JSONObject().put("t", "chat").put("from", me).put("text", t))
        AppScope.launch { line(ChatLine(me, t, System.currentTimeMillis(), mine = true)) }
    }

    fun react(emoji: String) {
        if (emoji !in PARTY_REACTIONS) return
        val me = myName()
        send(JSONObject().put("t", "react").put("from", me).put("emoji", emoji))
        reactions.tryEmit(me to emoji)
    }

    /** The name as given, never the number: each one numbers the room for itself, the same way. */
    private fun here() {
        send(JSONObject().put("t", "here").put("id", self).put("from", Party.me).put("leader", leader).put("status", status))
        AppScope.launch {
            bases[self] = Party.me
            members[self] = Member(self, Party.me, leader, status, System.currentTimeMillis(), me = true)
            renumber()
        }
    }

    private fun line(l: ChatLine) {
        chat.add(l)
        if (chat.size > 200) chat.removeAt(0)
        if (!l.mine && l.from != null) unread++
    }

    private fun gone(id: String) {
        val m = members.remove(id) ?: return
        bases.remove(id)
        line(ChatLine(null, "${m.name} left", System.currentTimeMillis()))
        renumber()
    }

    private fun send(json: JSONObject) {
        socket?.send(seal(key, json.toString()))
    }

    private fun receive(text: String) {
        // The relay's own messages, the count and the clock, are the plain ones.
        if (text.startsWith("{")) {
            val j = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (j.optString("t")) {
                "count" -> j.optInt("n", -1).takeIf { it > 0 }?.let { n -> AppScope.launch { people = n } }
                "time" -> clock(j.optLong("c"), j.optLong("s"), mono())
            }
            return
        }
        val j = unseal(key, text)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val from = j.optString("from", "Someone")
        when (j.optString("t")) {
            "state" -> if (!leader) decodeState(j)?.let { s ->
                val last = states.replayCache.lastOrNull()
                if (last?.media?.file != s.media.file) Party.log("the leader (${s.by}) plays ${s.media.file} — ${s.media.uri.substringBefore("&dn=").take(60)}, ${s.media.uri.split('&').count { it.startsWith("tr=") }} trackers")
                states.tryEmit(s)
                AppScope.launch { over = false }
            }
            "ask" -> if (leader) asks.tryEmit(PartyAsk(from, j.optString("what"), j.optDouble("pos", 0.0)))
            "hello" -> if (leader) hellos.tryEmit(from)
            "stuck" -> if (leader) stuck.tryEmit(from to j.optBoolean("on"))
            "end" -> if (!leader) {
                ended.tryEmit(Unit)
                AppScope.launch { over = true }
            }
            "here" -> {
                val id = j.optString("id").takeIf { it.isNotEmpty() && it != self } ?: return
                val status = j.optString("status", "watching")
                AppScope.launch {
                    val new = id !in members
                    bases[id] = from
                    members[id] = Member(id, from, j.optBoolean("leader"), status, System.currentTimeMillis())
                    renumber()
                    if (new) {
                        // Not for those already here when this phone came in: they didn't just join.
                        if (System.currentTimeMillis() - since > 5000) line(ChatLine(null, "${members[id]?.name ?: from} joined", System.currentTimeMillis()))
                        // So the newcomer has everyone without waiting for the next heartbeat.
                        here()
                    }
                }
            }
            "bye" -> AppScope.launch { gone(j.optString("id")) }
            "chat" -> {
                val t = j.optString("text").trim().take(Party.CHAT_MAX)
                if (t.isNotEmpty()) AppScope.launch { line(ChatLine(from, t, System.currentTimeMillis())) }
            }
            "react" -> j.optString("emoji").takeIf { it in PARTY_REACTIONS }?.let { reactions.tryEmit(from to it) }
        }
    }

    /** NTP's sum, on one ping: sent at [sent], the relay's time [server], back at [back]. */
    @Synchronized
    internal fun clock(sent: Long, server: Long, back: Long) {
        if (sent <= 0 || server <= 0 || back < sent) return
        samples.addLast((back - sent) to server - (sent + back) / 2)
        if (samples.size > 8) samples.removeFirst()
        offset = samples.minBy { it.first }.second
    }
}

internal fun encodeState(s: PartyState): JSONObject = JSONObject()
    .put("t", "state").put("pos", s.pos).put("playing", s.playing)
    .put("rate", s.rate.toDouble()).put("stamp", s.stamp).put("wait", org.json.JSONArray(s.wait)).put("asks", s.asks).put("by", s.by)
    .put("uri", s.media.uri).put("file", s.media.file).put("title", s.media.title)
    .put("imdb", s.media.imdb).put("kind", s.media.kind).put("poster", s.media.poster)

internal fun decodeState(j: JSONObject): PartyState? {
    fun opt(k: String) = j.optString(k).takeIf { it.isNotEmpty() && !j.isNull(k) }
    val uri = opt("uri") ?: return null
    val file = opt("file") ?: return null
    val wait = j.optJSONArray("wait")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
    return PartyState(
        j.optDouble("pos", 0.0), j.optBoolean("playing"), PartyMedia(uri, file, opt("title") ?: file, opt("imdb"), opt("kind"), opt("poster")),
        j.optDouble("rate", 1.0).toFloat().takeIf { it > 0 } ?: 1f, j.optLong("stamp", 0), wait, j.optBoolean("asks", true), j.optString("by", ""),
    )
}

/**
 * The playing file as a guest can open it: a magnet as it is, an addon's link or a .torrent file as a
 * magnet from the info hash. Null for a local video or a link with no torrent behind it.
 * The magnet keeps its trackers: often the release's own, where most of its peers are. Without them a
 * guest found three peers through the DHT where the leader had dozens, and the film never got going.
 * Only past [MAGNET_MAX] are the last ones left off, to fit the relay's message.
 */
fun shareableUri(uri: String, name: String, engineHash: String?): String? = when {
    uri.startsWith("magnet:", true) -> uri
    isLocalVideo(uri) -> null
    isDirectLink(uri) -> linkHash(uri)?.let { magnetFor(it, name) }
    else -> engineHash?.let { magnetFor(it, name) }
}?.let(::fitMagnet)

const val MAGNET_MAX = 4000

internal fun fitMagnet(magnet: String): String {
    if (magnet.length <= MAGNET_MAX) return magnet
    val parts = magnet.split('&')
    val (trackers, rest) = parts.partition { it.startsWith("tr=") }
    val kept = rest.toMutableList()
    for (t in trackers) if ((kept + t).joinToString("&").length <= MAGNET_MAX) kept += t
    return kept.joinToString("&")
}

/** A guest opening what the leader plays: the file by name, as the leader's numbering may be TorBox's. */
fun Watch.openParty(s: PartyState) = openParty(s.media)

/** The film a party plays, opened here: a guest following its leader, or a leader reopening its party. */
fun Watch.openParty(m: PartyMedia) {
    val item = History.put(Item(m.uri, m.title, System.currentTimeMillis(), imdb = m.imdb, kind = m.kind, poster = m.poster, titleName = m.title))
    resume(item, want = m.file)
}

fun Watch.partyMedia(): PartyMedia? {
    val at = item ?: return null
    val f = playingFile ?: return null
    val uri = shareableUri(at.uri, meta?.name ?: f.name, engine.infoHash) ?: return null
    return PartyMedia(uri, f.name, at.label, at.imdb, at.kind, at.poster)
}

/** Milliseconds on a clock that only ever goes forward, whatever the phone's time is set to. */
internal fun mono() = System.nanoTime() / 1_000_000

private val rng = SecureRandom()
private fun random(n: Int) = ByteArray(n).also(rng::nextBytes)
private fun randomHex(n: Int) = hex(random(n))
internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
internal fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun seal(key: ByteArray, text: String): String {
    val iv = random(12)
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
    return hex(iv + c.doFinal(text.toByteArray()))
}

/** Null for anything not sealed with [key]: a stranger guessing the room id gets nowhere. */
internal fun unseal(key: ByteArray, sealed: String): String? = runCatching {
    val b = hex(sealed)
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, 0, 12)) }
    String(c.doFinal(b, 12, b.size - 12))
}.getOrNull()
