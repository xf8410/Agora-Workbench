package com.newoether.agora.uma

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UmaBinaryRange(
    val fileId: Long,
    val offset: Long,
    val totalLength: Long,
    val bytes: ByteArray,
)

internal data class UmaRangeHeaders(
    val contentLength: Long,
    val fileId: Long?,
    val fileLength: Long?,
    val rangeStart: Long?,
    val rangeEndExclusive: Long?,
)

internal fun validateUmaRangeResponse(
    statusCode: Int,
    requestedFileId: Long,
    requestedOffset: Long,
    requestedLength: Int,
    bodyLength: Int,
    headers: UmaRangeHeaders,
): UmaBinaryRange {
    require(statusCode == 200 || statusCode == 206) { "Unexpected read_range HTTP $statusCode" }
    require(headers.contentLength == bodyLength.toLong()) { "Content-Length does not match received bytes" }
    require(bodyLength <= requestedLength) { "SO returned more bytes than requested" }
    headers.fileId?.let { require(it == requestedFileId) { "SO returned a different file_id" } }
    val totalLength = requireNotNull(headers.fileLength) { "Missing X-HLPATCH-File-Length" }
    require(totalLength >= 0) { "Negative file length" }
    val start = headers.rangeStart ?: if (totalLength == 0L) 0L else requestedOffset
    require(start == requestedOffset) { "SO returned a non-contiguous range start" }
    headers.rangeEndExclusive?.let { end ->
        require(end >= start) { "Invalid range end" }
        require(end - start == bodyLength.toLong()) { "Range length does not match received bytes" }
    }
    require(start + bodyLength <= totalLength) { "Range exceeds indexed file length" }
    return UmaBinaryRange(requestedFileId, start, totalLength, ByteArray(bodyLength))
}

/** Reads one bounded /storage/read_range chunk as unchanged bytes. */
class UmaBinaryRangeClient(
    private val baseUrl: String = "http://127.0.0.1:18765",
) {
    suspend fun read(fileId: Long, offset: Long, length: Int): UmaBinaryRange = withContext(Dispatchers.IO) {
        require(fileId > 0) { "file_id must be positive" }
        require(offset >= 0) { "offset must be non-negative" }
        require(length in 1..MAX_CHUNK_BYTES) { "length must be 1-$MAX_CHUNK_BYTES" }
        val path = "/storage/read_range?file_id=$fileId&offset=$offset&length=$length"
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 60_000
            connection.useCaches = false
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
                error("hlpatch HTTP $code: $error")
            }
            val out = ByteArrayOutputStream(length)
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= length) { "SO returned more bytes than requested" }
                    out.write(buffer, 0, count)
                }
            }
            val bytes = out.toByteArray()
            val headers = UmaRangeHeaders(
                contentLength = connection.getHeaderFieldLong("Content-Length", -1),
                fileId = connection.getHeaderField("X-HLPATCH-File-Id")?.toLongOrNull(),
                fileLength = connection.getHeaderField("X-HLPATCH-File-Length")?.toLongOrNull(),
                rangeStart = connection.getHeaderField("X-HLPATCH-Range-Start")?.toLongOrNull(),
                rangeEndExclusive = connection.getHeaderField("X-HLPATCH-Range-End-Exclusive")?.toLongOrNull(),
            )
            validateUmaRangeResponse(code, fileId, offset, length, bytes.size, headers).copy(bytes = bytes)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
    }
}
