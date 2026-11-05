package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fields GitHub's releases/latest gives, with the page's mix of files. */
class ReleaseTest {
    private fun asset(name: String) = """{"name":"$name","size":77306948,"digest":"sha256:abc","browser_download_url":"https://github.com/sammyZi/Watchly/releases/download/v2.4.1/$name"}"""
    private val json = """{"tag_name":"v2.4.1","assets":[
        ${asset("Watchly-phone-v2.4.1-armeabi-v7a.apk")},${asset("Watchly-phone-v2.4.1-arm64-v8a.apk")},${asset("Watchly-phone-v2.4.1-universal.apk")},
        ${asset("Watchly-tv-v2.4.1-arm64-v8a.apk")},${asset("Watchly-windows-v2.4.1-x64-setup.exe")}]}"""

    @Test fun onlyNewerCounts() {
        assertTrue(isNewerVersion("v2.4.1", "2.4.0"))
        assertTrue(isNewerVersion("2.10.0", "2.9.9"))
        assertTrue(isNewerVersion("3.0", "2.9.9"))
        assertFalse(isNewerVersion("2.4.0", "2.4.0"))
        assertFalse(isNewerVersion("v2.3.0", "2.4.0"))
        assertFalse(isNewerVersion("2.4", "2.4.0"))
    }

    @Test fun picksThePhonesOwnApk() {
        val r = parseRelease(json, listOf("arm64-v8a", "armeabi-v7a"), "Watchly-phone-")!!
        assertEquals("2.4.1", r.version)
        assertTrue(r.url.endsWith("/Watchly-phone-v2.4.1-arm64-v8a.apk"))
        assertEquals(77306948L, r.size)
        assertEquals("abc", r.sha256)
    }

    @Test fun fallsBackToUniversal() {
        assertTrue(parseRelease(json, listOf("x86_64"), "Watchly-phone-")!!.url.endsWith("-universal.apk"))
    }

    @Test fun nothingForThisApp() {
        assertNull(parseRelease("""{"tag_name":"v2.4.1","assets":[${asset("Watchly-windows-v2.4.1-x64-setup.exe")}]}""", listOf("arm64-v8a"), "Watchly-phone-"))
        assertNull(parseRelease("""{"message":"Not Found"}""", listOf("arm64-v8a"), "Watchly-phone-"))
    }
}
