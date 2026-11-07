package com.torrentviewer.core

import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTest {
    /** A slip in the file would crash the first screen of all three apps. */
    @Test fun everyPageReads() {
        assertTrue(Legal.UPDATED.isNotBlank() && Legal.SUMMARY.isNotBlank() && Legal.ACCEPT.isNotBlank())
        listOf(Legal.NOTICE, Legal.PRIVACY, Legal.TERMS).forEach { assertTrue(it.isNotEmpty()) }
    }
}
