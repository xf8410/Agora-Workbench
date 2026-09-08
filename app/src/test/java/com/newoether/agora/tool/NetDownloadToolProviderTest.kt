package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for [NetDownloadToolProvider] helpers. No Android dependencies. */
class NetDownloadToolProviderTest {

    @Test
    fun `url key is a stable sha256 hex`() {
        val key = NetDownloadToolProvider.keyFor("https://example.com/a.apk")
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, NetDownloadToolProvider.keyFor("https://example.com/a.apk"))
        assertTrue(key != NetDownloadToolProvider.keyFor("https://example.com/b.apk"))
    }

    @Test
    fun `filename sanitization keeps safe names and strips path components`() {
        assertEquals("game.apk", NetDownloadToolProvider.sanitizeFileName("game.apk"))
        assertEquals("Offset_2446893076.zip", NetDownloadToolProvider.sanitizeFileName("Offset_2446893076.zip"))
        // Path separators / traversal attempts collapse into underscores.
        assertEquals("_etc_passwd", NetDownloadToolProvider.sanitizeFileName("/etc/passwd"))
        assertEquals(".._evil", NetDownloadToolProvider.sanitizeFileName("..\\evil"))
        assertEquals("a_b", NetDownloadToolProvider.sanitizeFileName("a b"))
        assertEquals("", NetDownloadToolProvider.sanitizeFileName("///"))
    }

    @Test
    fun `default name falls back to download bin for blank filenames`() {
        assertEquals("download.bin", NetDownloadToolProvider.defaultNameFromUrl("https://example.com/"))
        assertEquals("download.bin", NetDownloadToolProvider.defaultNameFromUrl("not a url at all"))
        assertEquals("f.apk", NetDownloadToolProvider.defaultNameFromUrl("https://example.com/f.apk?token=x#frag"))
    }

    @Test
    fun `range header uses byte offset`() {
        assertEquals("bytes=0-", NetDownloadToolProvider.rangeHeaderFor(0L))
        assertEquals("bytes=123456-", NetDownloadToolProvider.rangeHeaderFor(123_456L))
    }

    @Test
    fun `content length parsing accepts plain bytes and rejects garbage`() {
        assertEquals(1234L, NetDownloadToolProvider.parseContentLength("1234"))
        assertEquals(-1L, NetDownloadToolProvider.parseContentLength(null))
        assertEquals(-1L, NetDownloadToolProvider.parseContentLength("not-a-number"))
        assertEquals(-1L, NetDownloadToolProvider.parseContentLength("-5"))
    }

    @Test
    fun `only http and https urls accepted`() {
        assertTrue(NetDownloadToolProvider.isHttpUrl("https://lf6-apk-channel.bytesmanager.com/obj/ad-app-package/x.apk"))
        assertTrue(NetDownloadToolProvider.isHttpUrl("http://example.com/a.zip"))
        assertFalse(NetDownloadToolProvider.isHttpUrl("ftp://example.com/a.zip"))
        assertFalse(NetDownloadToolProvider.isHttpUrl("file:///sdcard/a.zip"))
        assertFalse(NetDownloadToolProvider.isHttpUrl("content://media/a"))
        assertFalse(NetDownloadToolProvider.isHttpUrl(""))
    }
}
