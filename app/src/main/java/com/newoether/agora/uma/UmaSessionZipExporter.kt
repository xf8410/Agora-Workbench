package com.newoether.agora.uma

import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of exporting every indexed file in one SO observation session. */
data class UmaSessionExportResult(
    val sessionId: String,
    val fileCount: Int,
    val totalBytes: Long,
)

internal fun validateUmaArchivePath(relativePath: String): String {
    require(relativePath.isNotBlank()) { "relative_path must not be blank" }
    require('\\' !in relativePath) { "relative_path must use forward slashes" }
    require(!relativePath.startsWith('/')) { "relative_path must not be absolute" }
    val parts = relativePath.split('/')
    require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
        "relative_path contains an unsafe segment"
    }
    return relativePath
}

internal suspend fun copyUmaIndexedFile(
    file: UmaStorageFile,
    chunkSize: Int,
    write: (ByteArray) -> Unit,
    readChunk: suspend (fileId: Long, offset: Long, length: Int) -> UmaBinaryRange,
): ByteArray {
    require(file.fileId > 0) { "file_id must be positive" }
    require(file.byteLength >= 0) { "byte_length must be non-negative" }
    require(chunkSize in 1..UmaBinaryRangeClient.MAX_CHUNK_BYTES) { "invalid chunkSize" }
    val digest = MessageDigest.getInstance("SHA-256")
    var offset = 0L
    while (offset < file.byteLength) {
        val requested = minOf(chunkSize.toLong(), file.byteLength - offset).toInt()
        val range = readChunk(file.fileId, offset, requested)
        require(range.fileId == file.fileId) { "read_range returned a different file_id" }
        require(range.offset == offset) { "read_range returned a non-contiguous offset" }
        require(range.totalLength == file.byteLength) { "indexed length changed during export" }
        require(range.bytes.isNotEmpty()) { "read_range returned an empty chunk before EOF" }
        require(range.bytes.size <= requested) { "read_range returned more bytes than requested" }
        require(offset + range.bytes.size <= file.byteLength) { "read_range exceeded indexed length" }
        write(range.bytes)
        digest.update(range.bytes)
        offset += range.bytes.size
    }
    require(offset == file.byteLength) { "exported byte count does not match index" }
    return digest.digest()
}

/**
 * Writes every file listed by /storage/files into a ZIP, preserving each indexed relative path and
 * fetching unchanged bytes through /storage/read_range. The output is complete only when this
 * function returns successfully.
 */
class UmaSessionZipExporter(
    private val filesClient: UmaStorageFilesClient = UmaStorageFilesClient(),
    private val rangeClient: UmaBinaryRangeClient = UmaBinaryRangeClient(),
    private val chunkSize: Int = UmaBinaryRangeClient.MAX_CHUNK_BYTES,
) {
    suspend fun export(sessionId: String, output: OutputStream): UmaSessionExportResult =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) { "session_id must not be blank" }
            require(chunkSize in 1..UmaBinaryRangeClient.MAX_CHUNK_BYTES) { "invalid chunkSize" }
            val files = filesClient.listAll(sessionId)
            val archivePaths = mutableSetOf<String>()
            var totalBytes = 0L
            ZipOutputStream(output).use { zip ->
                files.forEach { file ->
                    require(file.sessionId == sessionId) { "file belongs to a different session" }
                    val path = validateUmaArchivePath(file.relativePath)
                    require(archivePaths.add(path)) { "duplicate relative_path $path" }
                    zip.putNextEntry(ZipEntry(path).apply { time = file.createdAtMs })
                    val actualDigest = try {
                        copyUmaIndexedFile(
                            file = file,
                            chunkSize = chunkSize,
                            write = zip::write,
                            readChunk = rangeClient::read,
                        )
                    } finally {
                        zip.closeEntry()
                    }
                    file.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                        val actual = actualDigest.joinToString("") { "%02x".format(it) }
                        require(actual.equals(expected, ignoreCase = true)) {
                            "SHA-256 mismatch for ${file.relativePath}"
                        }
                    }
                    totalBytes = Math.addExact(totalBytes, file.byteLength)
                }
            }
            UmaSessionExportResult(sessionId, files.size, totalBytes)
        }
}
