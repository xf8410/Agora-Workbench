package com.newoether.agora.uma

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UmaSessionZipExporterTest {
    private fun file(length: Long, sha256: String? = null) = UmaStorageFile(
        fileId = 7,
        sessionId = "session",
        relativePath = "protocol/request/1/payload.bin",
        contentType = "application/octet-stream",
        byteLength = length,
        sha256 = sha256,
        createdAtMs = 1,
    )

    @Test
    fun copiesContiguousChunksAndReturnsDigest() = kotlinx.coroutines.test.runTest {
        val source = byteArrayOf(0, 1, 2, 3, 4)
        val output = mutableListOf<Byte>()
        val digest = copyUmaIndexedFile(file(source.size.toLong()), 2, { output += it.toList() }) { id, offset, length ->
            val start = offset.toInt()
            UmaBinaryRange(id, offset, source.size.toLong(), source.copyOfRange(start, start + length))
        }

        assertArrayEquals(source, output.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source), digest)
    }

    @Test
    fun acceptsZeroByteFileWithoutCallingReader() = kotlinx.coroutines.test.runTest {
        var reads = 0
        val digest = copyUmaIndexedFile(file(0), 2, {}) { _, _, _ ->
            reads++
            error("reader must not be called")
        }

        assertEquals(0, reads)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(), digest)
    }

    @Test
    fun rejectsPrematureEmptyChunk() = kotlinx.coroutines.test.runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.test.runTest {
                copyUmaIndexedFile(file(3), 2, {}) { id, offset, _ ->
                    UmaBinaryRange(id, offset, 3, ByteArray(0))
                }
            }
        }
    }

    @Test
    fun rejectsTraversalAndAbsoluteArchivePaths() {
        listOf("../payload.bin", "protocol/../payload.bin", "/payload.bin", "protocol//payload.bin").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { validateUmaArchivePath(path) }
        }
        assertEquals("protocol/request/1/payload.bin", validateUmaArchivePath("protocol/request/1/payload.bin"))
    }
}
