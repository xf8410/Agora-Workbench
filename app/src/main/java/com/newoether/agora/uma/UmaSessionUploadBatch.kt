package com.newoether.agora.uma

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UmaSessionUploadPhase {
    @SerialName("download") DOWNLOAD,
    @SerialName("raw_blobs") RAW_BLOBS,
    @SerialName("derive") DERIVE,
    @SerialName("derived_blobs") DERIVED_BLOBS,
    @SerialName("tree") TREE,
    @SerialName("commit") COMMIT,
    @SerialName("complete") COMPLETE,
}

@Serializable
data class UmaSessionUploadProgress(
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
}

data class UmaSessionUploadBatchLimits(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) {
            "batch_size must be between 1 and $MAX_BATCH_SIZE"
        }
        require(maxDurationMs in MIN_DURATION_MS..MAX_DURATION_MS) {
            "max_duration_ms must be between $MIN_DURATION_MS and $MAX_DURATION_MS"
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val MAX_BATCH_SIZE = 500
        const val DEFAULT_MAX_DURATION_MS = 120_000L
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 170_000L
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
