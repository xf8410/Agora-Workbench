package com.newoether.agora.courier

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourierVolumeWriterTest {
    private fun tempDir(): File = createTempDir(prefix = "courier-test")

    @Test
    fun `zip volume contains every planned file under its relative path`() {
        val dir = tempDir()
        try {
            val payloadA = "alpha-contents".toByteArray()
            val payloadB = "beta-0123456789".toByteArray()
            val files = listOf(
                PlannedFile("/root/Android/data/a/cache.bin", "Android/data/a/cache.bin", payloadA.size.toLong()),
                PlannedFile("/root/Android/data/b/config.json", "Android/data/b/config.json", payloadB.size.toLong()),
            )
            val target = File(dir, "part_001.zip")
            CourierVolumeWriter.writeZipVolume(target, files) { path ->
                when (path) {
                    files[0].absolutePath -> ByteArrayInputStream(payloadA)
                    files[1].absolutePath -> ByteArrayInputStream(payloadB)
                    else -> error("unexpected path $path")
                }
            }

            assertTrue(target.isFile)
            val entries = ZipInputStream(target.inputStream().buffered()).use { zip ->
                buildList {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        add(entry.name to zip.readBytes().decodeToString())
                    }
                }
            }
            assertEquals(
                listOf("Android/data/a/cache.bin" to "alpha-contents", "Android/data/b/config.json" to "beta-0123456789"),
                entries,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `raw part writes exactly the requested byte range`() {
        val dir = tempDir()
        try {
            val source = (0 until 1000).map { (it % 251).toByte() }.toByteArray()
            val target = File(dir, "part_002.zip")
            CourierVolumeWriter.writeRawPart(target, { ByteArrayInputStream(source) }, offset = 300, length = 400)
            val written = target.readBytes()
            assertEquals(400, written.size)
            assertEquals(source.copyOfRange(300, 700).toList(), written.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `raw part fails when the source ends early`() {
        val dir = tempDir()
        try {
            val target = File(dir, "part_001.zip")
            assertThrows(IllegalStateException::class.java) {
                CourierVolumeWriter.writeRawPart(target, { ByteArrayInputStream(ByteArray(10)) }, offset = 0, length = 100)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `zip volume rejects an empty file list`() {
        val dir = tempDir()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                CourierVolumeWriter.writeZipVolume(File(dir, "part_001.zip"), emptyList()) { error("never") }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
