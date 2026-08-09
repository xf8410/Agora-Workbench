package com.newoether.agora.uma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UmaStorageFilesClientTest {
    private fun file(id: Long, session: String = "s", length: Long = 1) = UmaStorageFile(
        fileId = id,
        sessionId = session,
        relativePath = "protocol/request/$id/payload.bin",
        contentType = "application/octet-stream",
        byteLength = length,
        createdAtMs = 1,
    )

    @Test
    fun appendsStrictlyOrderedPageAndReturnsNextCursor() {
        val output = mutableListOf<UmaStorageFile>()
        val next = appendUmaStoragePage(
            expectedSessionId = "s",
            previousCursor = 0,
            page = UmaStorageFilesPage(true, "s", 0, 2, 2, listOf(file(1), file(2))),
            seenFileIds = mutableSetOf(),
            output = output,
        )

        assertTrue(next == 2L)
        assertEquals(listOf(1L, 2L), output.map { it.fileId })
    }

    @Test
    fun emptyPageTerminatesPagination() {
        val next = appendUmaStoragePage(
            "s", 2, UmaStorageFilesPage(true, "s", 2, 2, 0), mutableSetOf(), mutableListOf(),
        )
        assertNull(next)
    }

    @Test
    fun rejectsDuplicateFileIdAcrossPages() {
        val seen = mutableSetOf(2L)
        assertThrows(IllegalArgumentException::class.java) {
            appendUmaStoragePage(
                "s", 1, UmaStorageFilesPage(true, "s", 1, 2, 1, listOf(file(2))), seen, mutableListOf(),
            )
        }
    }

    @Test
    fun rejectsNonAdvancingCursor() {
        assertThrows(IllegalArgumentException::class.java) {
            appendUmaStoragePage(
                "s", 2, UmaStorageFilesPage(true, "s", 2, 2, 1, listOf(file(3))), mutableSetOf(), mutableListOf(),
            )
        }
    }

    @Test
    fun rejectsNegativeByteLength() {
        assertThrows(IllegalArgumentException::class.java) {
            appendUmaStoragePage(
                "s", 0, UmaStorageFilesPage(true, "s", 0, 1, 1, listOf(file(1, length = -1))),
                mutableSetOf(), mutableListOf(),
            )
        }
    }
}
