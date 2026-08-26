package com.newoether.agora.uma

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class UmaDerivedBlobCheckpoint(
    val sessionId: String,
    val repository: String,
    val blobs: List<UmaSessionUploadedBlob> = emptyList(),
)

/**
 * Generates the rebuildable derived layer and uploads at most one bounded Blob batch.
 *
 * The batch cursor is persisted in the task progress ([UmaSessionUploadProgress.nextCursor]
 * minus the raw file count), so a worker restart resumes the same batch instead of restarting
 * from zero. The blob checkpoint still records every completed upload for reuse across
 * restarts, but it no longer decides where the next batch begins.
 */
class UmaSessionDerivedBlobBatchExecutor(
    private val filesClient: UmaStorageFilesClient,
    private val blobUploader: UmaGitBlobUploader,
    private val taskStore: UmaSessionUploadTaskStore,
    private val generator: UmaSessionDerivedJsonGenerator = UmaSessionDerivedJsonGenerator(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(taskId: String, rootDirectory: File): UmaSessionUploadProgress =
        withContext(Dispatchers.IO) {
            val record = requireNotNull(taskStore.read(taskId)) { "upload task does not exist" }
            require(record.progress.phase in setOf(
                UmaSessionUploadPhase.DERIVE,
                UmaSessionUploadPhase.DERIVED_BLOBS,
            )) { "upload task is not in the derived stage" }

            val task = record.task
            val indexed = filesClient.listAll(task.sessionId)
            val rawCheckpoint = readRawCheckpoint(
                File(rootDirectory, UmaSessionGitBlobPipeline.CHECKPOINT_FILE_NAME)
            )
            require(rawCheckpoint.sessionId == task.sessionId) {
                "raw Git blob checkpoint belongs to a different session"
            }
            require(rawCheckpoint.repository == task.repository) {
                "raw Git blob checkpoint belongs to a different repository"
            }
            require(rawCheckpoint.blobs.size == indexed.size) {
                "raw Git blob upload is incomplete"
            }

            val derived = generator.generate(task.sessionId, rootDirectory, indexed)
            val ordered = derived.files
                .map { (path, file) -> validateUmaArchivePath(path) to file }
                .sortedBy { it.first }
            require(ordered.isNotEmpty()) { "derived layer produced no files" }
            require(ordered.map { it.first }.toSet().size == ordered.size) {
                "derived layer contains duplicate paths"
            }

            val checkpointFile = File(rootDirectory, CHECKPOINT_FILE_NAME)
            val previous = readCheckpoint(checkpointFile)
            require(previous == null || previous.sessionId == task.sessionId) {
                "derived Blob checkpoint belongs to a different session"
            }
            require(previous == null || previous.repository == task.repository) {
                "derived Blob checkpoint belongs to a different repository"
            }
            val filesByPath = ordered.associate { it.first to it.second }
            val completed = previous?.blobs.orEmpty().associateBy { it.relativePath }.toMutableMap()
            require(completed.keys.all { it in filesByPath }) {
                "derived Blob checkpoint contains an unknown path"
            }

            // Durable cursor: derive stage cursors are stored as rawTotalFiles + derivedIndex.
            // A fresh checkpoint (no completed blobs) starts at index 0; otherwise trust only
            // the persisted progress cursor so restarts never re-upload an earlier window.
            val rawTotalFiles = record.progress.rawTotalFiles
            var cursor = if (completed.isEmpty()) {
                (record.progress.nextCursor - rawTotalFiles).coerceIn(0, ordered.size)
            } else {
                completed.size.coerceAtMost(ordered.size)
            }
            val batch = nextUmaUploadBatch(
                ordered.map { it.first },
                completedCount = cursor,
                limits = UmaSessionUploadBatchLimits(task.batchSize),
            )
            batch.forEach { path ->
                val file = requireNotNull(filesByPath[path])
                require(file.isFile) { "derived file is missing: $path" }
                val uploaded = blobUploader.upload(task.repository, file)
                require(uploaded.byteLength == file.length()) {
                    "derived Blob length mismatch: $path"
                }
                completed[path] = UmaSessionUploadedBlob(
                    relativePath = path,
                    blobSha = uploaded.blobSha.lowercase(),
                    byteLength = uploaded.byteLength,
                    sha256 = uploaded.sha256.lowercase(),
                )
                writeCheckpoint(
                    checkpointFile,
                    UmaDerivedBlobCheckpoint(
                        sessionId = task.sessionId,
                        repository = task.repository,
                        blobs = completed.values.sortedBy { it.relativePath },
                    ),
                )
                cursor++
                persistProgress(record, ordered.size, cursor)
            }

            persistProgress(record, ordered.size, cursor)
        }

    private fun persistProgress(
        original: UmaSessionUploadTaskRecord,
        total: Int,
        completedCount: Int,
    ): UmaSessionUploadProgress = taskStore.update(original.task.taskId) { current ->
        require(current.task == original.task) { "upload task arguments changed during batch" }
        current.copy(progress = current.progress.copy(
            phase = if (completedCount >= total) UmaSessionUploadPhase.TREE
                else UmaSessionUploadPhase.DERIVED_BLOBS,
            derivedTotalFiles = total,
            derivedCompletedFiles = completedCount,
            nextCursor = current.progress.rawTotalFiles + completedCount,
            checkpointUpdatedAtMs = clock(),
            lastError = null,
        ))
    }.progress

    private fun readRawCheckpoint(file: File): UmaSessionGitBlobCheckpoint {
        require(file.isFile) { "raw Git blob checkpoint is missing" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun readCheckpoint(file: File): UmaDerivedBlobCheckpoint? {
        if (!file.exists()) return null
        require(file.isFile) { "derived Blob checkpoint path is not a file" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }

    private fun writeCheckpoint(file: File, checkpoint: UmaDerivedBlobCheckpoint) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) require(file.delete()) { "cannot replace derived Blob checkpoint" }
        require(temporary.renameTo(file)) { "cannot commit derived Blob checkpoint" }
    }

    companion object {
        const val CHECKPOINT_FILE_NAME = ".uma-session-derived-git-blobs.json"
    }
}
