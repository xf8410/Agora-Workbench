package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Uploads at most one bounded batch of unchanged raw session files and persists every Blob SHA.
 *
 * A live capture keeps appending files to the session, so the SO index may grow between worker
 * runs. Instead of failing on an exact count match, this executor uploads exactly the files that
 * finished downloading; anything new stays pending for the next appended run, which re-syncs the
 * incremental downloader first. Restarts therefore converge instead of looping forever.
 */
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
            // Incremental + resumable: completed files are verified locally, only new/partial
            // files hit the SO again. A growing live session simply adds more work over runs.
            downloader.download(task.sessionId, rootDirectory)
            val indexed = filesClient.listAll(task.sessionId)

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

            val completed = checkpoint?.blobs.orEmpty().associateBy { it.relativePath }.toMutableMap()
            require(completed.keys.all { it in orderedPaths.toSet() }) {
                "Git blob checkpoint contains a path absent from the SO index"
            }
            completed.forEach { (path, blob) ->
                val source = requireNotNull(indexed.firstOrNull { validateUmaArchivePath(it.relativePath) == path })
                require(blob.byteLength == source.byteLength) {
                    "Git blob checkpoint length mismatch: $path"
                }
                require(UMA_GIT_SHA_PATTERN.matches(blob.blobSha)) {
                    "Git blob checkpoint has an invalid blob SHA: $path"
                }
            }

            // Defer anything not fully present on disk yet; a later appended run picks it up
            // after the incremental downloader has fetched it.
            fun localReady(path: String, expectedLength: Long): Boolean {
                val file = resolveSessionFileOrNull(rootDirectory, path) ?: return false
                return file.isFile && file.length() == expectedLength
            }
            val pending = orderedPaths
                .filterNot(completed::containsKey)
                .filter { path ->
                    val source = requireNotNull(indexed.firstOrNull { validateUmaArchivePath(it.relativePath) == path })
                    localReady(path, source.byteLength)
                }
            val batch = nextUmaUploadBatch(
                pending,
                completedCount = 0,
                limits = UmaSessionUploadBatchLimits(task.batchSize),
            )
            val reusedAtStart = completed.size
            batch.forEach { path ->
                val source = requireNotNull(indexed.firstOrNull { validateUmaArchivePath(it.relativePath) == path })
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
        val totalBytes = indexed.sumOf { it.byteLength }
        val phase = if (completed.size >= indexed.size) {
            UmaSessionUploadPhase.DERIVE
        } else {
            UmaSessionUploadPhase.RAW_BLOBS
        }
        return taskStore.update(original.task.taskId) { current ->
            require(current.task == original.task) { "upload task arguments changed during batch" }
            current.copy(progress = current.progress.copy(
                phase = phase,
                rawTotalFiles = indexed.size,
                rawCompletedFiles = completed.size.coerceAtMost(indexed.size),
                rawTotalBytes = totalBytes,
                rawCompletedBytes = completedBytes.coerceAtMost(totalBytes),
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

    private fun resolveSessionFile(root: File, relativePath: String): File =
        resolveSessionFileOrNull(root, relativePath)
            ?: error("relative_path escapes session root")

    private fun resolveSessionFileOrNull(root: File, relativePath: String): File? {
        val safe = runCatching { validateUmaArchivePath(relativePath) }.getOrNull() ?: return null
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, safe).canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) return null
        return target
    }
}
