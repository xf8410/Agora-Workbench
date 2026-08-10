package com.newoether.agora.uma

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UmaSessionUploadPhase {
    @SerialName("queued") QUEUED,
    @SerialName("download") DOWNLOAD,
    @SerialName("raw_blobs") RAW_BLOBS,
    @SerialName("derive") DERIVE,
    @SerialName("derived_blobs") DERIVED_BLOBS,
    @SerialName("tree") TREE,
    @SerialName("commit") COMMIT,
    @SerialName("complete") COMPLETE,
    @SerialName("paused") PAUSED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
}

@Serializable
data class UmaSessionUploadTask(
    @SerialName("task_id") val taskId: String,
    @SerialName("session_id") val sessionId: String,
    val repository: String,
    val branch: String,
    @SerialName("target_directory") val targetDirectory: String,
    @SerialName("commit_message") val commitMessage: String,
    @SerialName("batch_size") val batchSize: Int = UmaSessionUploadBatchLimits.DEFAULT_BATCH_SIZE,
    @SerialName("created_at_ms") val createdAtMs: Long,
) {
    init {
        require(TASK_ID.matches(taskId)) { "task_id has an invalid format" }
        require(sessionId.isNotBlank())
        require(repository.isNotBlank())
        requireUmaWorkbenchBranch(branch)
        require(targetDirectory.isNotBlank())
        require(commitMessage.isNotBlank() && commitMessage.length <= 500)
        UmaSessionUploadBatchLimits(batchSize)
    }

    private companion object {
        val TASK_ID = Regex("[A-Za-z0-9._-]{1,240}")
    }
}

@Serializable
data class UmaSessionUploadProgress(
    @SerialName("task_id") val taskId: String,
    @SerialName("session_id") val sessionId: String,
    val repository: String,
    val branch: String,
    @SerialName("target_directory") val targetDirectory: String,
    val phase: UmaSessionUploadPhase,
    @SerialName("raw_total_files") val rawTotalFiles: Int,
    @SerialName("raw_completed_files") val rawCompletedFiles: Int,
    @SerialName("raw_total_bytes") val rawTotalBytes: Long,
    @SerialName("raw_completed_bytes") val rawCompletedBytes: Long,
    @SerialName("derived_total_files") val derivedTotalFiles: Int = 0,
    @SerialName("derived_completed_files") val derivedCompletedFiles: Int = 0,
    @SerialName("reused_blob_count") val reusedBlobCount: Int = 0,
    @SerialName("next_cursor") val nextCursor: Int,
    @SerialName("checkpoint_updated_at_ms") val checkpointUpdatedAtMs: Long,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("tree_sha") val treeSha: String? = null,
    @SerialName("commit_sha") val commitSha: String? = null,
) {
    init {
        require(rawTotalFiles >= 0)
        require(rawCompletedFiles in 0..rawTotalFiles)
        require(rawTotalBytes >= 0)
        require(rawCompletedBytes in 0..rawTotalBytes)
        require(derivedTotalFiles >= 0)
        require(derivedCompletedFiles in 0..derivedTotalFiles)
        require(reusedBlobCount >= 0)
        require(nextCursor >= 0)
    }

    val complete: Boolean get() = phase == UmaSessionUploadPhase.COMPLETE
    val terminal: Boolean get() = phase in setOf(
        UmaSessionUploadPhase.COMPLETE,
        UmaSessionUploadPhase.CANCELLED,
        UmaSessionUploadPhase.FAILED,
    )
}

data class UmaSessionUploadBatchLimits(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) {
            "batch_size must be between 1 and $MAX_BATCH_SIZE"
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val MAX_BATCH_SIZE = 500
    }
}

internal fun <T> nextUmaUploadBatch(
    ordered: List<T>,
    completedCount: Int,
    limits: UmaSessionUploadBatchLimits,
): List<T> {
    require(completedCount in 0..ordered.size) { "upload cursor is outside the file list" }
    return ordered.subList(completedCount, minOf(ordered.size, completedCount + limits.batchSize))
}
