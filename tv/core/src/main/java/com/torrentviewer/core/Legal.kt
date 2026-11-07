package com.torrentviewer.core

import org.json.JSONObject

/** The words in legal.json, which Windows reads too. Each section is a heading and its text. */
object Legal {
    private val root by lazy { JSONObject(Legal::class.java.getResourceAsStream("/legal.json")!!.bufferedReader().use { it.readText() }) }

    private fun sections(key: String) = root.getJSONArray(key).objects().map { it.getString("heading") to it.getString("text") }

    /** What was accepted is kept by this: a new date asks everyone again. */
    val UPDATED: String get() = root.getString("updated")
    val SUMMARY: String get() = root.getString("summary")
    val ACCEPT: String get() = root.getString("accept")
    val NOTICE by lazy { sections("notice") }
    val PRIVACY by lazy { sections("privacy") }
    val TERMS by lazy { sections("terms") }
}
