package com.newoether.agora.audit

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class ZipInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: BinaryAuditStore
    private lateinit var inspector: ZipInspector
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.filesDir } returns tmp.newFolder("files")
        store = BinaryAuditStore(context)
        inspector = ZipInspector(store)
    }

    private fun importZip(bytes: ByteArray, name: String): BinaryAuditEntry =
        store.import(name = name) { bytes.inputStream() }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `lists entries with sizes and method`() {
        val zip = zipBytes("a.txt" to "hello", "dir/b.txt" to "world!")
        val entry = importZip(zip, "test.apk")
        val listed = inspector.listEntries(store.fileOf(entry))

        assertEquals(listOf("a.txt", "dir/b.txt"), listed.map { it.name })
        assertEquals(5L, listed.first { it.name == "a.txt" }.sizeBytes)
        assertEquals(6L, listed.first { it.name == "dir/b.txt" }.sizeBytes)
        assertTrue(listed.all { it.method == "DEFLATE" || it.method == "STORE" })
    }

    @Test
    fun `extract by exact entry name and register derived entry`() {
        val zip = zipBytes(
            "assets/bin/Data/Managed/Metadata/global-metadata.dat" to "METADATA-BYTES",
            "classes.dex" to "DEX-BYTES",
        )
        val entry = importZip(zip, "game.apk")
        val extracted = inspector.extractEntries(
            store.fileOf(entry), "assets/bin/Data/Managed/Metadata/global-metadata.dat",
        )

        assertEquals(1, extracted.size)
        assertEquals("game.apk::global-metadata.dat", extracted[0].name)
        assertEquals("METADATA-BYTES".toByteArray().size.toLong(), extracted[0].byteLength)
        // The derived entry is independently resolvable
        val resolved = store.resolve(extracted[0].sourceId)
        assertEquals(extracted[0].sha256, resolved.sha256)
    }

    @Test
    fun `extract by case-insensitive substring when no exact match`() {
        val zip = zipBytes("root/Global-Metadata.DAT" to "XYZ", "other.txt" to "1")
        val entry = importZip(zip, "pkg.apk")
        val extracted = inspector.extractEntries(store.fileOf(entry), "global-metadata")

        assertEquals(1, extracted.size)
        assertEquals("pkg.apk::Global-Metadata.DAT", extracted[0].name)
    }

    @Test
    fun `max_entries bounds extraction`() {
        val zip = zipBytes(
            "m1.dat" to "1", "m2.dat" to "2", "m3.dat" to "3", "m4.dat" to "4",
        )
        val entry = importZip(zip, "multi.apk")
        val extracted = inspector.extractEntries(store.fileOf(entry), ".dat", maxEntries = 2)
        assertEquals(2, extracted.size)
    }

    @Test
    fun `no matching entry throws with helpful message`() {
        val zip = zipBytes("a.txt" to "1")
        val entry = importZip(zip, "small.apk")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            inspector.extractEntries(store.fileOf(entry), "no-such-entry")
        }
        assertTrue(ex.message!!.contains("audit_zip_list"))
    }

    @Test
    fun `not a zip throws`() {
        val entry = store.import(name = "garbage.bin") { "not a zip at all".toByteArray().inputStream() }
        assertThrows(Exception::class.java) {
            inspector.listEntries(store.fileOf(entry))
        }
    }
}
