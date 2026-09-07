package com.newoether.agora.courier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPathMapperTest {
    private val primaryRoot = "/storage/emulated/0"
    private val androidDataGrant = listOf("primary:Android/data")

    @Test
    fun `maps an Android_data path to a document id inside the granted tree`() {
        val id = SafPathMapper.documentIdForPath(
            "/storage/emulated/0/Android/data/com.galasports.totalfootball.bilibili/files/UnityCache/Shared",
            primaryRoot,
            androidDataGrant,
        )
        assertEquals(
            "primary:Android/data/com.galasports.totalfootball.bilibili/files/UnityCache/Shared",
            id,
        )
    }

    @Test
    fun `a root grant covers any primary path`() {
        val id = SafPathMapper.documentIdForPath(
            "/storage/emulated/0/Download/x.apk",
            primaryRoot,
            listOf("primary:"),
        )
        assertEquals("primary:Download/x.apk", id)
    }

    @Test
    fun `paths outside every grant return null`() {
        // 只授权了 Android/data，Download 不被覆盖
        val id = SafPathMapper.documentIdForPath(
            "/storage/emulated/0/Download/x.apk",
            primaryRoot,
            androidDataGrant,
        )
        assertEquals(null, id)
    }

    @Test
    fun `paths outside primary storage return null`() {
        assertEquals(
            null,
            SafPathMapper.documentIdForPath("/sdcard/Android/data/x", primaryRoot, androidDataGrant),
        )
    }

    @Test
    fun `the deepest matching grant wins`() {
        val id = SafPathMapper.documentIdForPath(
            "/storage/emulated/0/Android/data/com.example/files",
            primaryRoot,
            listOf("primary:", "primary:Android/data", "primary:Android/data/com.example"),
        )
        assertEquals("primary:Android/data/com.example/files", id)
    }

    @Test
    fun `prefix boundaries do not match partial segments`() {
        // 授权 primary:Android/data 不应覆盖 primary:Android/dataX
        assertEquals(
            null,
            SafPathMapper.documentIdForPath("/storage/emulated/0/Android/dataX/y", primaryRoot, androidDataGrant),
        )
    }

    @Test
    fun `rejects traversal and malformed paths`() {
        listOf(
            "relative/path",
            "/storage/emulated/0/../0/secret",
            "/storage/emulated/0/Android/data//double",
            "/storage/emulated/0/./x",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SafPathMapper.requireNormalized(path)
            }
        }
    }

    @Test
    fun `relativeUnder strips the delivery root`() {
        assertEquals(
            "com.example/cache.bin",
            SafPathMapper.relativeUnder(
                "/storage/emulated/0/Android/data/com.example/cache.bin",
                "/storage/emulated/0/Android/data",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SafPathMapper.relativeUnder("/storage/emulated/0/Download/x", "/storage/emulated/0/Android/data")
        }
    }

    @Test
    fun `isCovered mirrors documentIdForPath`() {
        assertTrue(
            SafPathMapper.isCovered("/storage/emulated/0/Android/data/a/b", primaryRoot, androidDataGrant)
        )
        assertFalse(
            SafPathMapper.isCovered("/storage/emulated/0/Download/a", primaryRoot, androidDataGrant)
        )
    }
}
