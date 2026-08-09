package com.newoether.agora.uma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UmaBinaryRangeClientTest {
    @Test
    fun validatesAContiguousPartialRange() {
        val range = validateUmaRangeResponse(
            statusCode = 206,
            requestedFileId = 42,
            requestedOffset = 1024,
            requestedLength = 4096,
            bodyLength = 4096,
            headers = UmaRangeHeaders(
                contentLength = 4096,
                fileId = 42,
                fileLength = 10000,
                rangeStart = 1024,
                rangeEndExclusive = 5120,
            ),
        )

        assertEquals(42, range.fileId)
        assertEquals(1024, range.offset)
        assertEquals(10000, range.totalLength)
        assertEquals(4096, range.bytes.size)
    }

    @Test
    fun acceptsAZeroByteFileWithoutInventingContent() {
        val range = validateUmaRangeResponse(
            statusCode = 200,
            requestedFileId = 7,
            requestedOffset = 0,
            requestedLength = 1024,
            bodyLength = 0,
            headers = UmaRangeHeaders(
                contentLength = 0,
                fileId = 7,
                fileLength = 0,
                rangeStart = 0,
                rangeEndExclusive = null,
            ),
        )

        assertEquals(0, range.totalLength)
        assertEquals(0, range.bytes.size)
    }

    @Test
    fun rejectsLengthMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            validateUmaRangeResponse(
                statusCode = 206,
                requestedFileId = 42,
                requestedOffset = 0,
                requestedLength = 100,
                bodyLength = 99,
                headers = UmaRangeHeaders(100, 42, 1000, 0, 100),
            )
        }
    }
}
