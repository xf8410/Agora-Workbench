package com.newoether.agora.uma

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UmaStorageFile(
    @SerialName("file_id") val fileId: Long,
    @SerialName("session_id") val sessionId: String,
    @SerialName("relative_path") val relativePath: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("byte_length") val byteLength: Long,
    val sha256: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long,
)

@Serializable
internal data class UmaStorageFilesPage(
    val ok: Boolean,
    @SerialName("session_id") val sessionId: String,
    val cursor: Long,
    @SerialName("next_cursor") val nextCursor: Long,
    val count: Int,
    val files: List<UmaStorageFile> = emptyList(),
)

internal fun appendUmaStoragePage(
    expectedSessionId: String,
    previousCursor: Long,
    page: UmaStorageFilesPage,
    seenFileIds: MutableSet<Long>,
    output: MutableList<UmaStorageFile>,
): Long? {
    require(page.ok) { "storage/files returned ok=false" }
    require(page.sessionId == expectedSessionId) { "storage/files returned a different session_id" }
    require(page.cursor == previousCursor) { "storage/files cursor mismatch" }
    require(page.count == page.files.size) { "storage/files count mismatch" }
    if (page.files.isEmpty()) return null
    require(page.nextCursor > previousCursor) { "storage/files cursor did not advance" }
    var lastId = previousCursor
    page.files.forEach { file ->
        require(file.sessionId == expectedSessionId) { "file belongs to a different session" }
        require(file.fileId > lastId) { "file_id order is not strictly increasing" }
        require(file.byteLength >= 0) { "file has a negative byte_length" }
        require(seenFileIds.add(file.fileId)) { "duplicate file_id ${file.fileId}" }
        lastId = file.fileId
        output += file
    }
    require(page.nextCursor == lastId) { "next_cursor does not match the last file_id" }
    return page.nextCursor
}

/** Enumerates every indexed file in a session by following the SO cursor until the empty page. */
class UmaStorageFilesClient(
    private val baseUrl: String = "http://127.0.0.1:18765",
    private val pageSize: Int = 1000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listAll(sessionId: String): List<UmaStorageFile> {
        require(sessionId.isNotBlank()) { "session_id must not be blank" }
        require(pageSize in 1..1000) { "pageSize must be 1-1000" }
        val files = mutableListOf<UmaStorageFile>()
        val seen = mutableSetOf<Long>()
        var cursor = 0L
        while (true) {
            val page = readPage(sessionId, cursor)
            cursor = appendUmaStoragePage(sessionId, cursor, page, seen, files) ?: break
        }
        return files
    }

    private suspend fun readPage(sessionId: String, cursor: Long): UmaStorageFilesPage = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")
        val connection = URL(
            "${baseUrl.trimEnd('/')}/storage/files?session_id=$encoded&cursor=$cursor&limit=$pageSize"
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 60_000
            connection.useCaches = false
            val code = connection.responseCode
            val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }.orEmpty()
            val body = bytes.toString(Charsets.UTF_8)
            require(code in 200..299) { "hlpatch HTTP $code: $body" }
            json.decodeFromString<UmaStorageFilesPage>(body)
        } finally {
            connection.disconnect()
        }
    }
}
