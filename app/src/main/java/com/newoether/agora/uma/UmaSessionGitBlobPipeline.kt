package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UmaSessionUploadedBlob(
    @SerialName("relative_path") val relativePath: String,
    @SerialName("blob_sha") val blobSha: String,
    @SerialName("byte_length") val byteLength: Long,
    val sha256: String,
)

@Serializable
internal data class UmaSessionGitBlobCheckpoint(
    @SerialName("session_id") val sessionId: String,
    val repository: String,
    val blobs: List<UmaSessionUploadedBlob> = emptyList(),
)

data class UmaSessionGitBlobPipelineResult(
    val sessionId: String,
    val repository: String,
    val fileCount: Int,
    val totalBytes: Long,
    val rootDirectory: File,
    val blobs: List<UmaSessionUploadedBlob>,
)

/**
 * Downloads one complete SO session and uploads each unchanged local file through the Git Blob API.
 * The local downloader and the blob checkpoint are both resumable. No Git tree, commit or ref is
 * mutated by this stage. A live capture may append files between the download and the index
 * refresh; the commit snapshot is whatever finished uploading, and growth alone is not an error.
 */
class UmaSessionGitBlobPipeline(
    private val downloader: UmaSessionResumeDownloader,
    private val filesClient: UmaStorageFilesClient,
    private val blobUploader: UmaGitBlobUploader,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun upload(
        sessionId: String,
        repository: String,
        rootDirectory: File,
    ): UmaSessionGitBlobPipelineResult = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank()) { "session_id must not be blank" }
        require(repository.isNotBlank()) { "repository must not be blank" }

        val download = downloader.download(sessionId, rootDirectory)
        val files = filesClient.listAll(sessionId)
        // Growth between the two calls means the capture appended files; they simply stay out of
        // this snapshot (the next run picks them up). Shrinkage or a changed total is an error.
        require(files.size >= download.fileCount) { "downloaded file count changed before upload" }
        require(files.size >= 1) { "SO file index is empty" }
        val indexedByPath = files.associateBy { validateUmaArchivePath(it.relativePath) }

        val checkpointFile = File(rootDirectory, CHECKPOINT_FILE_NAME)
        val previous = readCheckpoint(checkpointFile)
        require(previous == null || previous.sessionId == sessionId) {
            "Git blob checkpoint belongs to a different session"
        }
        require(previous == null || previous.repository == repository) {
            "Git blob checkpoint belongs to a different repository"
        }
        val completed = previous?.blobs.orEmpty().associateBy { it.relativePath }.toMutableMap()
        require(completed.keys.all { it in indexedByPath }) {
            "Git blob checkpoint contains a path absent from the SO index"
        }

        download.completedRelativePaths().forEach { relativePath ->
            val indexed = requireNotNull(indexedByPath[relativePath]) {
                "completed path missing from SO index: $relativePath"
            }
            val localFile = resolveUmaSessionFile(rootDirectory, relativePath)
            require(localFile.isFile) { "downloaded file is missing: $relativePath" }
            require(localFile.length() == indexed.byteLength) {
                "downloaded file length mismatch: $relativePath"
            }

            val existing = completed[relativePath]
            if (existing != null) {
                require(existing.byteLength == indexed.byteLength) {
                    "Git blob checkpoint length mismatch: $relativePath"
                }
                require(UMA_GIT_SHA_PATTERN.matches(existing.blobSha)) {
                    "Git blob checkpoint has an invalid blob SHA: $relativePath"
                }
            } else {
                val uploaded = blobUploader.upload(repository, localFile)
                require(uploaded.byteLength == indexed.byteLength) {
                    "uploaded blob length mismatch: $relativePath"
                }
                indexed.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                    require(uploaded.sha256.equals(expected, ignoreCase = true)) {
                        "uploaded blob source SHA-256 mismatch: $relativePath"
                    }
                }
                completed[relativePath] = UmaSessionUploadedBlob(
                    relativePath = relativePath,
                    blobSha = uploaded.blobSha.lowercase(),
                    byteLength = uploaded.byteLength,
                    sha256 = uploaded.sha256.lowercase(),
                )
                writeCheckpoint(
                    checkpointFile,
                    UmaSessionGitBlobCheckpoint(
                        sessionId = sessionId,
                        repository = repository,
                        blobs = completed.values.sortedBy { it.relativePath },
                    ),
                )
            }
        }

        val ordered = download.completedRelativePaths().map { relativePath ->
            requireNotNull(completed[relativePath]) {
                "missing uploaded blob for $relativePath"
            }
        }
        require(ordered.isNotEmpty()) { "no raw blobs were published for session $sessionId" }
        UmaSessionGitBlobPipelineResult(
            sessionId = sessionId,
            repository = repository,
            fileCount = ordered.size,
            totalBytes = ordered.sumOf { it.byteLength },
            rootDirectory = rootDirectory,
            blobs = ordered,
        )
    }

    private fun readCheckpoint(file: File): UmaSessionGitBlobCheckpoint? {
        if (!file.exists()) return null
        require(file.isFile) { "Git blob checkpoint path is not a file" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun writeCheckpoint(file: File, checkpoint: UmaSessionGitBlobCheckpoint) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) require(file.delete()) { "cannot replace Git blob checkpoint" }
        require(temporary.renameTo(file)) { "cannot commit Git blob checkpoint" }
    }

    private fun resolveUmaSessionFile(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "relative_path escapes session root"
        }
        return target
    }

    companion object {
        const val CHECKPOINT_FILE_NAME = ".uma-session-git-blobs.json"

        /** Files that finished downloading in this workspace; the snapshot actually committed. */
        internal fun UmaSessionDownloadResult.completedRelativePaths(): List<String> =
            File(rootDirectory, UmaSessionResumeDownloader.CHECKPOINT_FILE_NAME)
                .takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)
                ?.let { text ->
                    runCatching {
                        Json { ignoreUnknownKeys = true }
                            .decodeFromString(UmaSessionDownloadCheckpoint.serializer(), text)
                            .completedRelativePaths
                    }.getOrNull()
                }
                .orEmpty()
    }
}
