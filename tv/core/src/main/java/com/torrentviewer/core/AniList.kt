package com.torrentviewer.core

import android.util.JsonReader
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.StringReader

/** One AniList entry the mapping ties to an IMDb title: [season] is its TVDB season, -1 when the list doesn't say. */
internal data class AniEntry(val imdb: String, val id: Int, val season: Int, val type: String) {
    val isFilm get() = type == "MOVIE"
    val isSeries get() = type == "TV" || type == "TV_SHORT" || type == "ONA"
}

/** One part of a season, in airing order: AniList splits a season into its cours where TVDB keeps one. [episodes] is null while it airs. */
internal data class Cour(val id: Int, val episodes: Int?)

/** Which part of a season [episode] falls in, and its number within that part. The last part takes whatever is past the others. */
internal fun courOf(cours: List<Cour>, episode: Int): Pair<Int, Int>? {
    var offset = 0
    cours.forEachIndexed { i, c ->
        val n = c.episodes
        if (n == null || i == cours.lastIndex || episode <= offset + n) return c.id to (episode - offset).coerceAtLeast(1)
        offset += n
    }
    return null
}

/** The season's episodes a part's progress covers: its own, counted on from the parts before it. */
internal fun episodesOf(cours: List<Cour>, id: Int, progress: Int): List<Int> {
    var offset = 0
    for (c in cours) {
        if (c.id == id) return (1..progress).map { offset + it }
        offset += c.episodes ?: return emptyList()
    }
    return emptyList()
}

/** An airing notification's words: AniList sends them in pieces ("Episode ", " of ", " aired.") to put the number and title between. */
internal fun airingText(contexts: List<String>, episode: Int, title: String): String =
    if (contexts.size >= 3) "${contexts[0]}$episode${contexts[1]}$title${contexts[2]}" else "Episode $episode of $title aired."

/** The token AniList hands back in a sign-in link's fragment ("…#access_token=…&expires_in=…"), and when it runs out. */
internal fun tokenFromRedirect(link: String, now: Long = System.currentTimeMillis()): TrackerTokens? {
    val fields = link.substringAfter('#', "").split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
    val access = fields["access_token"]?.takeIf { it.isNotBlank() } ?: return null
    return TrackerTokens(access, null, now + (fields["expires_in"]?.toLongOrNull() ?: 365L * 24 * 3600) * 1000)
}

/**
 * AniList (anilist.co), for anime: what is finished here marks the episode or film there, and what is
 * watched there comes back as [library]. Signed in on AniList's own page, which hands the token back to
 * the app by a `watchly://anilist` link; it lasts a year and AniList has no way to renew it.
 *
 * AniList has its own ids and a separate entry for each season, often each half of one, where the app
 * goes by IMDb and season numbers. Fribb's anime-lists ties them together: the entry, its IMDb title and
 * its TVDB season — the seasons the app already counts anime by (Simkl's use_tvdb_anime_seasons).
 */
object AniList {
    const val NAME = "AniList"
    const val SITE = "anilist.co"
    private const val API = "https://graphql.anilist.co"
    private const val MAPPING = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json"
    /** The mapping changes as new seasons air. Calibration knob. */
    private const val MAPPING_DAYS = 7L
    /** Far past any real list; a list that never ends must not spend the minute's requests. */
    private const val MAX_CHUNKS = 20

    /** From the app's build; empty leaves AniList out. */
    var clientId = ""
    val available get() = clientId.isNotEmpty()
    val authorizeUrl get() = "https://anilist.co/api/v2/oauth/authorize?client_id=${enc(clientId)}&response_type=token"

    /** Where the mapping is kept; set by the app at launch. */
    var dir: File? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _user = MutableStateFlow<String?>(null)
    val user: StateFlow<String?> = _user
    private val _library = MutableStateFlow(StremioLibrary())
    val library: StateFlow<StremioLibrary> = _library

    private var profile = ""
    /** A profile is in use: [load] has run. */
    private var loaded = false
    private var guest = false
    private var tokens: TrackerTokens? = null
    private var userId = 0
    private var flushing = false
    private var listening = false

    private const val QUEUE = "anilist-queue"
    private const val LIBRARY = "anilist-library"
    private fun slot(id: String) = "anilist:$id"

    /* --------------------------------------------------------------- account */

    /** On every profile switch, like the other trackers. After Kv has the profile. */
    fun load(id: String, isGuest: Boolean) {
        profile = id
        loaded = true
        guest = isGuest
        val saved = if (isGuest) null else Secrets.get(slot(id))?.let { runCatching { JSONObject(it) }.getOrNull() }
        tokens = saved?.let { TrackerTokens(it.getString("access"), null, it.getLong("expires")) }?.takeIf { it.expires > System.currentTimeMillis() }
        userId = saved?.optInt("user") ?: 0
        _user.value = saved?.str("name")
        _connected.value = tokens != null
        _library.value = if (tokens != null) parseWatched(Kv.get(LIBRARY)) else StremioLibrary()
        if (!listening) {
            listening = true
            AppScope.launch { Net.online.collect { if (it) flush() } }
        } else flush()
        pull()
        pending?.let { pending = null; adopt(it) }
    }

    /** A sign-in that came back before any profile was loaded: the app was started by the link itself. */
    private var pending: TrackerTokens? = null

    private fun save(next: TrackerTokens?, id: Int = 0, name: String? = null) {
        tokens = next
        userId = id
        // A page's row carries the list's progress: signed in or out, it is asked again.
        infos.clear()
        _user.value = name
        _connected.value = next != null
        _unread.value = 0
        if (!guest) Secrets.put(slot(profile), next?.let { JSONObject().put("access", it.access).put("expires", it.expires).put("user", id).put("name", name).toString() })
        if (next == null) {
            _library.value = StremioLibrary()
            listOf(QUEUE, LIBRARY).forEach { Kv.set(it, null) }
        }
    }

    fun forget(id: String) = Secrets.put(slot(id), null)

    /**
     * A `watchly://anilist#access_token=…` link, back from AniList's approval page: signs in with it.
     * False for any other link, which the app handles as before.
     */
    fun signIn(link: String?): Boolean {
        if (link == null || !link.startsWith("watchly://anilist")) return false
        val t = tokenFromRedirect(link) ?: return true.also { Log.w("anilist", "sign-in: no token in the link back (${link.substringBefore('#')}…)") }
        // Not the profile's id: the first profile's is "" (it keeps the bare keys), so its sign-ins were all held
        // back for a profile that had long been loaded, and AniList's page stayed at "Waiting for your approval".
        if (!loaded) pending = t else adopt(t)
        return true
    }

    /** Who the token belongs to, then signed in as them. */
    private fun adopt(t: TrackerTokens) {
        val owner = profile
        AppScope.launch {
            runCatching {
                val me = gql("query{Viewer{id name}}", JSONObject(), t.access).getJSONObject("Viewer")
                if (owner != profile) return@runCatching
                save(t, me.getInt("id"), me.str("name"))
                Log.i("anilist", "signed in as ${me.str("name")}")
                pull()
                flush()
            }.onFailure { Log.w("anilist", "sign-in: ${it.message}") }
        }
    }

    /** Signs out here. The token itself only goes from AniList's own settings, under Apps. */
    fun disconnect() = save(null)

    /**
     * This sign-in, to hand to a TV on the home network: it has no browser for AniList's page. The phone
     * sends it only once its viewer says yes. Null when not signed in.
     */
    fun shareable(): String? = tokens?.let { JSONObject().put("anilist", it.access).put("expires", it.expires).toString() }

    /** A sign-in sent over from a phone ([shareable]): what to tell the phone, as the TV's page shows it. */
    fun signInFrom(json: String): String {
        val o = runCatching { JSONObject(json.trim()) }.getOrNull()
        val access = o?.str("anilist") ?: return "That isn't an AniList sign-in — send it from Watchly on the phone."
        val expires = o.optLong("expires").takeIf { it > System.currentTimeMillis() } ?: return "That AniList sign-in has run out — sign in again on the phone."
        adopt(TrackerTokens(access, null, expires))
        return "Signing the TV in to AniList — you can close this page."
    }

    /* --------------------------------------------------------------- the API */

    private suspend fun gql(query: String, vars: JSONObject, token: String? = tokens?.access): JSONObject {
        val body = JSONObject().put("query", query).put("variables", vars).toString()
        val headers = buildMap {
            put("Accept", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val answer = try {
            JSONObject(Net.postJson(API, body, 30_000, quiet = true, headers = headers))
        } catch (e: HttpException) {
            // A token taken away on AniList's side: signed out here too.
            if (e.code == 401 && token != null && token == tokens?.access) save(null)
            throw e
        }
        answer.optJSONArray("errors")?.objects()?.firstOrNull()?.let { throw IOException(it.optString("message", "AniList refused it")) }
        return answer.getJSONObject("data")
    }

    /* --------------------------------------------------------------- mapping */

    private var byImdb: Map<String, List<AniEntry>> = emptyMap()
    private var byId: Map<Int, AniEntry> = emptyMap()
    /** Each part's episode count and start, by id, once asked: to put a season's parts in order. */
    private val parts = mutableMapOf<Int, Pair<Int?, Long>>()

    /** One load at a time: sync and a page opening at once fetched the 6MB list twice. */
    private val mappingLock = kotlinx.coroutines.sync.Mutex()

    /** Kept a week as lines ("tt… id season type"); the 6MB list only comes again after that. */
    private suspend fun mapping(): Boolean = mappingLock.withLock { loadMapping() }.also { if (!it) Log.w("anilist", "mapping: couldn't load the anime list") }

    private suspend fun loadMapping(): Boolean {
        if (byImdb.isNotEmpty()) return true
        val cache = dir?.let { File(it, "anilist-map.txt") }
        val fresh = cache?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < MAPPING_DAYS * 24 * 3600 * 1000 }
        val entries = fresh?.let { f -> runCatching { f.readLines().mapNotNull(::entryOf) }.getOrNull() }
            ?: runCatching { readMapping(Net.get(MAPPING, 120_000, quiet = true)) }.getOrNull()?.also { got ->
                cache?.let { f -> runCatching { f.writeText(got.joinToString("\n") { "${it.imdb} ${it.id} ${it.season} ${it.type}" }) } }
            }
            ?: cache?.takeIf { it.exists() }?.let { f -> runCatching { f.readLines().mapNotNull(::entryOf) }.getOrNull() }
            ?: return false
        byImdb = entries.groupBy { it.imdb }
        byId = entries.associateBy { it.id }
        Log.i("anilist", "mapping: ${entries.size} entries${if (fresh != null) ", kept" else ""}")
        return true
    }

    private fun entryOf(line: String): AniEntry? = line.split(' ').takeIf { it.size == 4 }?.let { AniEntry(it[0], it[1].toIntOrNull() ?: return null, it[2].toIntOrNull() ?: -1, it[3]) }

    /** Streamed, not held whole as JSON: only the entries with an AniList id and an IMDb title. */
    private fun readMapping(json: String): List<AniEntry> {
        val out = mutableListOf<AniEntry>()
        JsonReader(StringReader(json)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                var id = 0
                var type = ""
                var season = -1
                val imdbs = mutableListOf<String>()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "anilist_id" -> id = runCatching { r.nextInt() }.getOrElse { r.skipValue(); 0 }
                        "type" -> type = runCatching { r.nextString() }.getOrElse { r.skipValue(); "" }
                        "imdb_id" -> runCatching {
                            if (r.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                                r.beginArray(); while (r.hasNext()) imdbs += r.nextString(); r.endArray()
                            } else imdbs += r.nextString()
                        }.onFailure { r.skipValue() }
                        "season" -> runCatching {
                            r.beginObject()
                            while (r.hasNext()) if (r.nextName() == "tvdb") season = r.nextInt() else r.skipValue()
                            r.endObject()
                        }.onFailure { r.skipValue() }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id > 0 && type.isNotEmpty()) imdbs.filter { it.startsWith("tt") }.forEach { out += AniEntry(it, id, season, type) }
            }
            r.endArray()
        }
        return out
    }

    /** A season's parts in airing order, asking AniList for the counts and starts it doesn't have yet. */
    private suspend fun coursOf(imdb: String, season: Int): List<Cour> {
        val ids = byImdb[imdb].orEmpty().filter { it.isSeries && it.season == season }.map { it.id }
        if (ids.size <= 1) return ids.map { Cour(it, parts[it]?.first) }
        learnParts(ids)
        return ids.sortedBy { parts[it]?.second ?: Long.MAX_VALUE }.map { Cour(it, parts[it]?.first) }
    }

    private suspend fun learnParts(ids: Collection<Int>) {
        ids.filter { it !in parts }.chunked(50).forEach { chunk ->
            val page = gql("query(\$ids:[Int]){Page(perPage:50){media(id_in:\$ids){id episodes startDate{year month day}}}}", JSONObject().put("ids", JSONArray(chunk)))
            page.getJSONObject("Page").optJSONArray("media")?.objects().orEmpty().forEach { m ->
                val d = m.optJSONObject("startDate")
                val start = d?.let { it.optInt("year", 9999) * 10_000L + it.optInt("month", 12) * 100 + it.optInt("day", 31) } ?: Long.MAX_VALUE
                parts[m.getInt("id")] = (if (m.isNull("episodes")) null else m.optInt("episodes")) to start
            }
        }
    }

    /* --------------------------------------------------------------- a title's page */

    /**
     * What AniList says of an anime, for its page: [score] of 100, the main [studio], [status] ("RELEASING"…),
     * the [nextEpisode] and when it airs (ms), and with an account, how far the viewer is on their list.
     */
    data class Info(
        val url: String, val score: Int?, val studio: String?, val status: String?, val episodes: Int?,
        val nextEpisode: Int?, val nextAt: Long?, val progress: Int?, val listStatus: String?,
    )

    private val infos = mutableMapOf<String, Info?>()

    /** The film, or the season's latest part: the one airing, if any is. Null for what the mapping doesn't know. */
    suspend fun info(imdb: String, season: Int?): Info? {
        val key = "$imdb:${season ?: -1}"
        if (key in infos) return infos[key]
        if (!mapping()) return null
        val all = byImdb[imdb].orEmpty()
        val id = if (season == null) (all.firstOrNull { it.isFilm } ?: all.singleOrNull())?.id
        else coursOf(imdb, season).lastOrNull()?.id ?: all.filter { it.isSeries }.singleOrNull()?.id
        val got = id?.let {
            val m = gql(
                "query(\$id:Int){Media(id:\$id){siteUrl averageScore status episodes studios(isMain:true){nodes{name}} nextAiringEpisode{episode airingAt} mediaListEntry{progress status}}}",
                JSONObject().put("id", it),
            ).getJSONObject("Media")
            val next = m.optJSONObject("nextAiringEpisode")
            val entry = m.optJSONObject("mediaListEntry")
            Info(
                m.str("siteUrl") ?: "https://anilist.co/anime/$it",
                if (m.isNull("averageScore")) null else m.optInt("averageScore"),
                m.optJSONObject("studios")?.optJSONArray("nodes")?.objects()?.firstOrNull()?.str("name"),
                m.str("status"),
                if (m.isNull("episodes")) null else m.optInt("episodes"),
                next?.optInt("episode"),
                next?.optLong("airingAt")?.times(1000),
                entry?.optInt("progress"),
                entry?.str("status"),
            )
        }
        infos[key] = got
        return got
    }

    /* --------------------------------------------------------------- watched here → there */

    private fun queued(): List<String> = runCatching { JSONArray(Kv.get(QUEUE) ?: "[]").let { a -> (0 until a.length()).map(a::getString) } }.getOrDefault(emptyList())
    private fun setQueue(items: List<String>) = Kv.set(QUEUE, if (items.isEmpty()) null else JSONArray(items).toString())

    /** A film or an episode finished; anime only, as the mapping knows it. */
    fun watched(at: Item, season: Int?, episode: Int?) {
        val imdb = at.imdb ?: return
        if (tokens == null) return
        setQueue(queued() + JSONObject().put("imdb", imdb).put("season", season ?: -1).put("episode", episode ?: -1).toString())
        flush()
    }

    private fun flush() {
        if (flushing || tokens == null) return
        flushing = true
        AppScope.launch {
            try {
                val owner = profile
                if (!mapping()) return@launch
                while (owner == profile && tokens != null) {
                    val raw = queued().firstOrNull() ?: break
                    val o = runCatching { JSONObject(raw) }.getOrNull()
                    if (o != null) try {
                        send(o.getString("imdb"), o.optInt("season", -1), o.optInt("episode", -1))
                        infos.clear()
                    } catch (e: HttpException) {
                        if (e.code == 429 || e.code >= 500) break
                        Log.w("anilist", "dropped a watch it refused: ${e.code}")
                    } catch (_: IOException) {
                        break
                    }
                    setQueue(queued().drop(1))
                }
            } finally {
                flushing = false
            }
        }
    }

    /** Progress only ever goes up, and a title finished there before is left as it is: this may be a rewatch. */
    private suspend fun send(imdb: String, season: Int, episode: Int) {
        val (id, progress) = if (episode < 0) {
            val film = byImdb[imdb].orEmpty().let { all -> all.firstOrNull { it.isFilm } ?: all.singleOrNull() } ?: return
            film.id to 1
        } else courOf(coursOf(imdb, season), episode) ?: return
        val media = gql("query(\$id:Int){Media(id:\$id){episodes mediaListEntry{progress status}}}", JSONObject().put("id", id)).getJSONObject("Media")
        val entry = media.optJSONObject("mediaListEntry")
        if (entry?.str("status") == "COMPLETED") return
        val total = if (media.isNull("episodes")) null else media.optInt("episodes")
        val next = maxOf(progress, entry?.optInt("progress") ?: 0)
        val status = if ((total != null && next >= total) || episode < 0) "COMPLETED" else "CURRENT"
        gql(
            "mutation(\$id:Int,\$p:Int,\$s:MediaListStatus){SaveMediaListEntry(mediaId:\$id,progress:\$p,status:\$s){id}}",
            JSONObject().put("id", id).put("p", if (episode < 0) (total ?: 1) else next).put("s", status),
        )
    }

    /* --------------------------------------------------------------- notifications */

    /**
     * One of AniList's own notifications: an episode aired, a follow, a like or reply, a title changed.
     * [imdb] (and [kind], [season]) when the mapping ties it to a title here, so it opens that page; else [url] on AniList.
     */
    data class Notice(val id: Int, val text: String, val at: Long, val url: String, val imdb: String?, val kind: String?, val season: Int?, val poster: String?)

    private val _unread = MutableStateFlow(0)
    /** AniList's own count of unread notifications, asked with each [pull]. */
    val unread: StateFlow<Int> = _unread

    private const val MEDIA = "media{id siteUrl title{english romaji} coverImage{medium}}"
    private const val PERSON = "user{name siteUrl}"

    /** The latest 30, newest first. [read] marks them read there too, as AniList's own page does on opening. */
    suspend fun notices(read: Boolean): List<Notice> {
        if (tokens == null) return emptyList()
        mapping()
        val social = listOf("Following", "ActivityMessage", "ActivityMention", "ActivityReply", "ActivityReplySubscribed", "ActivityLike", "ActivityReplyLike")
            .joinToString(" ") { "... on ${it}Notification{id type context createdAt $PERSON${if (it == "Following") "" else " activityId"}}" }
        val threads = listOf("ThreadCommentMention", "ThreadCommentReply", "ThreadCommentSubscribed", "ThreadCommentLike", "ThreadLike")
            .joinToString(" ") { "... on ${it}Notification{id type context createdAt $PERSON thread{siteUrl}}" }
        val media = listOf("RelatedMediaAddition", "MediaDataChange", "MediaMerge").joinToString(" ") { "... on ${it}Notification{id type context createdAt $MEDIA}" }
        val query = "query(\$r:Boolean){Page(perPage:30){notifications(resetNotificationCount:\$r){" +
            "... on AiringNotification{id type episode contexts createdAt $MEDIA} $social $threads $media " +
            "... on MediaDeletionNotification{id type context createdAt deletedMediaTitle}}}}"
        val rows = gql(query, JSONObject().put("r", read)).getJSONObject("Page").optJSONArray("notifications")?.objects().orEmpty()
        if (read) _unread.value = 0
        return rows.mapNotNull(::noticeOf)
    }

    private fun noticeOf(n: JSONObject): Notice? {
        val id = n.optInt("id").takeIf { it > 0 } ?: return null
        val m = n.optJSONObject("media")
        val title = m?.optJSONObject("title")?.let { it.str("english") ?: it.str("romaji") }
        val who = n.optJSONObject("user")
        val context = n.str("context").orEmpty()
        val text = when {
            n.has("contexts") -> airingText(n.optJSONArray("contexts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(), n.optInt("episode"), title ?: "a show")
            who != null -> (who.str("name") ?: "Someone") + context
            title != null -> title + context
            n.has("deletedMediaTitle") -> n.str("deletedMediaTitle").orEmpty() + context
            else -> return null
        }.trim()
        val entry = m?.optInt("id")?.let { byId[it] }
        val url = n.optJSONObject("thread")?.str("siteUrl")
            ?: n.optInt("activityId").takeIf { it > 0 }?.let { "https://anilist.co/activity/$it" }
            ?: who?.str("siteUrl") ?: m?.str("siteUrl") ?: "https://anilist.co/notifications"
        return Notice(
            id, text, n.optLong("createdAt") * 1000, url, entry?.imdb,
            entry?.let { if (it.isFilm) "movie" else "tv" }, entry?.season?.takeIf { it >= 0 && entry.isSeries },
            m?.optJSONObject("coverImage")?.str("medium"),
        )
    }

    private suspend fun countUnread() {
        _unread.value = runCatching { gql("query{Viewer{unreadNotificationCount}}", JSONObject()).getJSONObject("Viewer").optInt("unreadNotificationCount") }.getOrDefault(_unread.value)
    }

    /* --------------------------------------------------------------- watched there → here */

    /**
     * The anime list, back as films and episodes by IMDb title and season. Asked on each return to the app;
     * one or two requests, as AniList allows 30 a minute just now.
     */
    fun pull() {
        if (tokens == null || userId == 0) return
        val owner = profile
        AppScope.launch { countUnread() }
        AppScope.launch {
            runCatching {
                if (!mapping()) return@runCatching
                val rows = mutableListOf<JSONObject>()
                for (chunk in 1..MAX_CHUNKS) {
                    val c = gql(
                        "query(\$u:Int,\$c:Int){MediaListCollection(userId:\$u,type:ANIME,chunk:\$c,perChunk:500){hasNextChunk lists{entries{mediaId progress status updatedAt media{episodes title{english romaji} startDate{year month day}}}}}}",
                        JSONObject().put("u", userId).put("c", chunk),
                    ).getJSONObject("MediaListCollection")
                    c.optJSONArray("lists")?.objects().orEmpty().forEach { l -> rows += l.optJSONArray("entries")?.objects().orEmpty() }
                    if (!c.optBoolean("hasNextChunk")) break
                }
                rows.forEach { e ->
                    val m = e.optJSONObject("media") ?: return@forEach
                    val d = m.optJSONObject("startDate")
                    val start = d?.let { it.optInt("year", 9999) * 10_000L + it.optInt("month", 12) * 100 + it.optInt("day", 31) } ?: Long.MAX_VALUE
                    parts[e.getInt("mediaId")] = (if (m.isNull("episodes")) null else m.optInt("episodes")) to start
                }
                // The parts of a season not on the list still count toward where the others start.
                val split = rows.mapNotNull { byId[it.getInt("mediaId")] }.filter { it.isSeries && it.season >= 0 }
                    .flatMap { a -> byImdb[a.imdb].orEmpty().filter { it.isSeries && it.season == a.season }.map { it.id } }
                learnParts(split.toSet())

                val films = mutableSetOf<String>()
                val episodes = mutableMapOf<String, MutableSet<Ep>>()
                // What is being watched there, at the episode after the last one: Continue Watching and the page's Play.
                val upNext = mutableListOf<Paused>()
                // For the Library: each title once, by its plainest name and when it last moved there.
                val titles = mutableListOf<Seen>()
                var unmatched = 0
                rows.forEach { e ->
                    val a = byId[e.getInt("mediaId")] ?: run { unmatched++; return@forEach }
                    val status = e.str("status")
                    val done = status == "COMPLETED"
                    val name = e.optJSONObject("media")?.optJSONObject("title")?.let { it.str("english") ?: it.str("romaji") }
                    val at = isoNow(e.optLong("updatedAt") * 1000)
                    if (a.isFilm) {
                        if (done) {
                            films += a.imdb
                            name?.let { titles += Seen(a.imdb, it, null, "movie", at) }
                        }
                        return@forEach
                    }
                    if (!a.isSeries || a.season < 0) { unmatched++; return@forEach }
                    val progress = e.optInt("progress")
                    val count = if (done) parts[a.id]?.first ?: progress else progress
                    val cours = byImdb[a.imdb].orEmpty().filter { it.isSeries && it.season == a.season }.map { it.id }
                        .sortedBy { parts[it]?.second ?: Long.MAX_VALUE }.map { Cour(it, parts[it]?.first) }
                    val seen = episodesOf(cours, a.id, count)
                    seen.forEach { n -> episodes.getOrPut(a.imdb) { mutableSetOf() } += Ep(a.season, n) }
                    if (seen.isNotEmpty()) name?.let { titles += Seen(a.imdb, it, null, "tv", at) }
                    val total = parts[a.id]?.first
                    if ((status == "CURRENT" || status == "REPEATING") && progress > 0 && (total == null || progress < total)) {
                        val next = (seen.lastOrNull() ?: 0) + 1
                        val title = e.optJSONObject("media")?.optJSONObject("title")?.let { it.str("english") ?: it.str("romaji") } ?: return@forEach
                        upNext += Paused(a.imdb, title, null, Ep(a.season, next), 0.0, null, e.optLong("updatedAt") * 1000)
                    }
                }
                if (owner != profile || tokens == null) return@runCatching
                // One show, one place to carry on: its latest season being watched.
                // A show's seasons are entries of their own there ("… Season 3"): the shortest name is the show's.
                val seenTitles = titles.groupBy { it.imdb }.map { (imdb, all) ->
                    all.maxBy { it.at }.copy(title = all.minBy { it.title.length }.title, last = if (all.first().kind == "tv") furthest(episodes[imdb]) else null)
                }.sortedByDescending { it.at }
                val got = StremioLibrary(films = films, episodes = episodes, paused = upNext.sortedByDescending { it.at }.distinctBy { it.imdb }, seen = seenTitles)
                _library.value = got
                Kv.set(LIBRARY, watchedJson(got))
                infos.clear()
                Log.i("anilist", "pull: ${rows.size} on the list — ${films.size} films, ${episodes.values.sumOf { it.size }} episodes of ${episodes.size} series, ${got.paused.size} being watched; $unmatched not matched to a title")
            }.onFailure { Log.w("anilist", "pull: ${it.message}") }
        }
    }
}
