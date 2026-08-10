package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Uploads at most one bounded batch of unchanged raw session files and persists every Blob SHA. */
class UmaSessionRawBlobBatchExecutor(
    private val downloader: UmaSessionResumeDownloader,
    private val filesClient: UmaStorageFilesClient,
    private val blobUploader: UmaGitBlobUploader,
    private val taskStore: UmaSessionUploadTaskStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(taskId: String, rootDirectory: File): UmaSessionUploadProgress =
        withContext(Dispatchers.IO) {
            val record = requireNotNull(taskStore.read(taskId)) { "upload task does not exist" }
            require(record.progress.phase !in setOf(
                UmaSessionUploadPhase.COMPLETE,
                UmaSessionUploadPhase.CANCELLED,
            )) { "upload task is already terminal" }

            val task = record.task
            val download = downloader.download(task.sessionId, rootDirectory)
            val indexed = filesClient.listAll(task.sessionId)
            require(indexed.size == download.fileCount) { "downloaded file count changed before upload" }
            require(indexed.sumOf { it.byteLength } == download.totalBytes) {
                "downloaded byte count changed before upload"
            }

            val checkpointFile = File(rootDirectory, UmaSessionGitBlobPipeline.CHECKPOINT_FILE_NAME)
            val checkpoint = readBlobCheckpoint(checkpointFile)
            require(checkpoint == null || checkpoint.sessionId == task.sessionId) {
                "Git blob checkpoint belongs to a different session"
            }
            require(checkpoint == null || checkpoint.repository == task.repository) {
                "Git blob checkpoint belongs to a different repository"
            }

            val orderedPaths = indexed.map { validateUmaArchivePath(it.relativePath) }
            require(orderedPaths.toSet().size == orderedPaths.size) { "SO index contains duplicate paths" }
            val indexedByPath = indexed.zip(orderedPaths).associate { (file, path) -> path to file }
            val completed = checkpoint?.blobs.orEmpty().associateBy { it.relativePath }.toMutableMap()
            require(completed.keys.all { it in indexedByPath }) {
                "Git blob checkpoint contains a path absent from the SO index"
            }
            completed.forEach { (path, blob) ->
                val source = requireNotNull(indexedByPath[path])
                require(blob.byteLength == source.byteLength) {
                    "Git blob checkpoint length mismatch: $path"
                }
                require(UMA_GIT_SHA_PATTERN.matches(blob.blobSha)) {
                    "Git blob checkpoint has an invalid blob SHA: $path"
                }
            }

            val pending = orderedPaths.filterNot(completed::containsKey)
            val batch = nextUmaUploadBatch(
                pending,
                completedCount = 0,
                limits = UmaSessionUploadBatchLimits(task.batchSize),
            )
            val reusedAtStart = completed.size
            batch.forEach { path ->
                val source = requireNotNull(indexedByPath[path])
                val localFile = resolveSessionFile(rootDirectory, path)
                require(localFile.isFile && localFile.length() == source.byteLength) {
                    "downloaded file is missing or changed: $path"
                }
                val uploaded = blobUploader.upload(task.repository, localFile)
                require(uploaded.byteLength == source.byteLength) {
                    "uploaded blob length mismatch: $path"
                }
                source.sha256?.takeIf(String::isNotBlank)?.let { expected ->
                    require(uploaded.sha256.equals(expected, ignoreCase = true)) {
                        "uploaded blob source SHA-256 mismatch: $path"
                    }
                }
                completed[path] = UmaSessionUploadedBlob(
                    relativePath = path,
                    blobSha = uploaded.blobSha.lowercase(),
                    byteLength = uploaded.byteLength,
                    sha256 = uploaded.sha256.lowercase(),
                )
                writeBlobCheckpoint(
                    checkpointFile,
                    UmaSessionGitBlobCheckpoint(
                        sessionId = task.sessionId,
                        repository = task.repository,
                        blobs = completed.values.sortedBy { it.relativePath },
                    ),
                )
                persistProgress(record, indexed, completed, reusedAtStart)
            }

            persistProgress(record, indexed, completed, reusedAtStart)
        }

    private fun persistProgress(
        original: UmaSessionUploadTaskRecord,
        indexed: List<UmaStorageFile>,
        completed: Map<String, UmaSessionUploadedBlob>,
        reusedAtStart: Int,
    ): UmaSessionUploadProgress {
        val completedBytes = completed.values.sumOf { it.byteLength }
        val phase = if (completed.size == indexed.size) {
            UmaSessionUploadPhase.DERIVE
        } else {
            UmaSessionUploadPhase.RAW_BLOBS
        }
        return taskStore.update(original.task.taskId) { current ->
            require(current.task == original.task) { "upload task arguments changed during batch" }
            current.copy(progress = current.progress.copy(
                phase = phase,
                rawTotalFiles = indexed.size,
                rawCompletedFiles = completed.size,
                rawTotalBytes = indexed.sumOf { it.byteLength },
                rawCompletedBytes = completedBytes,
                reusedBlobCount = reusedAtStart,
                nextCursor = completed.size,
                checkpointUpdatedAtMs = clock(),
                lastError = null,
            ))
        }.progress
    }

    private fun readBlobCheckpoint(file: File): UmaSessionGitBlobCheckpoint? {
        if (!file.exists()) return null
        require(file.isFile) { "Git blob checkpoint path is not a file" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun writeBlobCheckpoint(file: File, checkpoint: UmaSessionGitBlobCheckpoint) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) require(file.delete()) { "cannot replace Git blob checkpoint" }
        require(temporary.renameTo(file)) { "cannot commit Git blob checkpoint" }
    }

    private fun resolveSessionFile(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, validateUmaArchivePath(relativePath)).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "relative_path escapes session root"
        }
        return target
    }
}
