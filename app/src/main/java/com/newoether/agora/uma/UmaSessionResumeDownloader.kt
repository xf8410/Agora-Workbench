package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UmaSessionDownloadCheckpoint(
    val sessionId: String,
    val currentRelativePath: String? = null,
    val currentOffset: Long = 0,
    val completedRelativePaths: List<String> = emptyList(),
)

data class UmaSessionDownloadResult(
    val sessionId: String,
    val fileCount: Int,
    val totalBytes: Long,
    val rootDirectory: File,
)

/**
 * Downloads every indexed session file to a directory while preserving relative paths and raw
 * bytes. A partially downloaded file uses a sibling `.part` file; its verified length is the next
 * read_range offset after restart. The checkpoint is atomically replaced after every chunk.
 *
 * A live capture keeps appending files to the session, so [UmaStorageFilesClient.listAll] may
 * return a larger list on each run. That is expected and safe here: every completed file is
 * verified locally without re-download, only new or partial files are fetched incrementally.
 */
class UmaSessionResumeDownloader(
    private val filesClient: UmaStorageFilesClient = UmaStorageFilesClient(),
    private val rangeClient: UmaBinaryRangeClient = UmaBinaryRangeClient(),
    private val chunkSize: Int = UmaBinaryRangeClient.MAX_CHUNK_BYTES,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun download(sessionId: String, rootDirectory: File): UmaSessionDownloadResult =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) { "session_id must not be blank" }
            require(chunkSize in 1..UmaBinaryRangeClient.MAX_CHUNK_BYTES) { "invalid chunkSize" }
            require(rootDirectory.mkdirs() || rootDirectory.isDirectory) {
                "cannot create download root"
            }

            val checkpointFile = File(rootDirectory, CHECKPOINT_FILE_NAME)
            val previous = readCheckpoint(checkpointFile)
            require(previous == null || previous.sessionId == sessionId) {
                "checkpoint belongs to a different session"
            }

            val files = filesClient.listAll(sessionId)
            val completed = previous?.completedRelativePaths.orEmpty().toMutableSet()
            var totalBytes = 0L
            files.forEach { file ->
                require(file.sessionId == sessionId) { "file belongs to a different session" }
                val relativePath = validateUmaArchivePath(file.relativePath)
                val target = resolveUnderRoot(rootDirectory, relativePath)
                val part = File(target.parentFile, target.name + PART_SUFFIX)
                require(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
                    "cannot create parent directory for $relativePath"
                }

                if (target.isFile && target.length() == file.byteLength) {
                    // Already fully downloaded in an earlier run; verify hash only when the SO
                    // index provides one.
                    verifyExpectedSha256(target, file.sha256, relativePath)
                } else if (file.byteLength > 0L) {
                    downloadOne(file, part, checkpointFile, completed)
                    require(part.length() == file.byteLength) {
                        "downloaded byte count does not match index for $relativePath"
                    }
                    verifyExpectedSha256(part, file.sha256, relativePath)
                    require(part.renameTo(target)) { "cannot finalize $relativePath" }
                } else {
                    // Zero-byte indexed files still become real entries.
                    require(part.delete() || !part.exists()) { "cannot reset partial $relativePath" }
                    require(target.createNewFile() || target.isFile) { "cannot finalize $relativePath" }
                }

                completed += relativePath
                writeCheckpoint(
                    checkpointFile,
                    UmaSessionDownloadCheckpoint(sessionId, completedRelativePaths = completed.sorted()),
                )
                totalBytes = Math.addExact(totalBytes, file.byteLength)
            }

            UmaSessionDownloadResult(sessionId, files.size, totalBytes, rootDirectory)
        }

    private suspend fun downloadOne(
        file: UmaStorageFile,
        part: File,
        checkpointFile: File,
        completed: Set<String>,
    ) {
        require(!part.exists() || part.isFile) { "partial path is not a file" }
        var offset = if (part.exists()) part.length() else 0L
        if (offset > file.byteLength) {
            // The index shrank or changed identity for this path; start over cleanly.
            require(part.delete()) { "cannot reset oversized partial $relativePathForLog(file)" }
            offset = 0L
        }

        FileOutputStream(part, true).use { output ->
            while (offset < file.byteLength) {
                val requested = minOf(chunkSize.toLong(), file.byteLength - offset).toInt()
                val range = rangeClient.read(file.fileId, offset, requested)
                require(range.fileId == file.fileId) { "read_range returned a different file_id" }
                require(range.offset == offset) { "read_range returned a non-contiguous offset" }
                require(range.totalLength == file.byteLength) { "indexed length changed during download" }
                require(range.bytes.isNotEmpty()) { "read_range returned an empty chunk before EOF" }
                require(range.bytes.size <= requested) { "read_range returned too many bytes" }
                output.write(range.bytes)
                output.fd.sync()
                offset += range.bytes.size
                writeCheckpoint(
                    checkpointFile,
                    UmaSessionDownloadCheckpoint(
                        sessionId = file.sessionId,
                        currentRelativePath = file.relativePath,
                        currentOffset = offset,
                        completedRelativePaths = completed.sorted(),
                    ),
                )
            }
        }
    }

    private fun relativePathForLog(file: UmaStorageFile): String = file.relativePath

    private fun readCheckpoint(file: File): UmaSessionDownloadCheckpoint? {
        if (!file.exists()) return null
        require(file.isFile) { "checkpoint path is not a file" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun writeCheckpoint(file: File, checkpoint: UmaSessionDownloadCheckpoint) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) require(file.delete()) { "cannot replace checkpoint" }
        require(temporary.renameTo(file)) { "cannot commit checkpoint" }
    }

    private fun resolveUnderRoot(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "relative_path escapes download root"
        }
        return target
    }

    private fun verifyExpectedSha256(file: File, expected: String?, relativePath: String) {
        val normalized = expected?.takeIf { it.isNotBlank() } ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(normalized, ignoreCase = true)) {
            "SHA-256 mismatch for $relativePath"
        }
    }

    companion object {
        const val CHECKPOINT_FILE_NAME = ".uma-session-download.json"
        private const val PART_SUFFIX = ".part"
    }
}
