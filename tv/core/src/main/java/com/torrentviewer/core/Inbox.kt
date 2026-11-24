package com.torrentviewer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One of Watchly's own notifications, kept for the notification center. [link] is a watchly:// link the app opens, as the system notification's tap would. */
data class Note(val id: String, val title: String, val text: String, val at: Long, val link: String?)

/**
 * What Watchly told the viewer — new episodes, finished downloads, an update — kept per profile, newest
 * first, so the notification center still has it after the system notification is swiped away.
 */
object Inbox {
    private const val KEY = "inbox"
    private const val READ = "inbox-read"
    /** Older ones go. Calibration knob. */
    private const val MAX = 60

    private val _items = MutableStateFlow<List<Note>>(emptyList())
    val items: StateFlow<List<Note>> = _items
    private val _readAt = MutableStateFlow(0L)
    /** Everything up to this was seen in the notification center. */
    val readAt: StateFlow<Long> = _readAt

    /** On a profile switch and each return to the app: the episode check writes while the app is away. */
    fun load() {
        _items.value = parse(Kv.get(KEY))
        _readAt.value = Kv.get(READ)?.toLongOrNull() ?: 0L
    }

    /** For the profile in use. A note already kept (by [Note.id]) isn't added again. */
    fun add(note: Note) {
        val now = parse(Kv.get(KEY))
        if (now.any { it.id == note.id }) return
        val next = (listOf(note) + now).take(MAX)
        Kv.set(KEY, json(next))
        _items.value = next
    }

    /** For another profile, from the background check: that profile's list, without switching to it. */
    fun addFor(profile: String, note: Note) {
        val now = parse(Kv.getFor(profile, KEY))
        if (now.any { it.id == note.id }) return
        Kv.setFor(profile, KEY, json((listOf(note) + now).take(MAX)))
    }

    fun markRead() {
        val at = System.currentTimeMillis()
        Kv.set(READ, at.toString())
        _readAt.value = at
    }

    fun clear() {
        Kv.set(KEY, null)
        _items.value = emptyList()
    }

    private fun parse(raw: String?): List<Note> = runCatching {
        JSONArray(raw ?: "[]").objects().map { Note(it.getString("id"), it.optString("title"), it.optString("text"), it.optLong("at"), it.str("link")) }
    }.getOrDefault(emptyList())

    private fun json(list: List<Note>) = JSONArray(list.map { JSONObject().put("id", it.id).put("title", it.title).put("text", it.text).put("at", it.at).put("link", it.link) }).toString()
}
