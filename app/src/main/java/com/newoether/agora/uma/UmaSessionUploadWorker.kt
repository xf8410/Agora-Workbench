package com.newoether.agora.uma

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newoether.agora.github.GitHubApiClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs one bounded raw-Blob batch. WorkManager retries the same durable task until the raw stage is
 * complete, so the upload no longer depends on one chat tool invocation remaining connected.
 */
class UmaSessionUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty()
        if (taskId.isBlank()) return Result.failure(errorData("missing task_id"))

        val base = File(applicationContext.cacheDir, "agora-uma")
        val store = UmaSessionUploadTaskStore(base)
        val record = runCatching { store.read(taskId) }.getOrElse {
            return Result.failure(errorData(it.message ?: "cannot read upload task"))
        } ?: return Result.failure(errorData("upload task does not exist"))

        if (record.progress.phase == UmaSessionUploadPhase.CANCELLED) return Result.success()
        if (record.progress.phase == UmaSessionUploadPhase.PAUSED) return Result.success()
        if (record.progress.phase !in setOf(
                UmaSessionUploadPhase.QUEUED,
                UmaSessionUploadPhase.DOWNLOAD,
                UmaSessionUploadPhase.RAW_BLOBS,
            )
        ) return Result.success()

        return try {
            val filesClient = UmaStorageFilesClient()
            val executor = UmaSessionRawBlobBatchExecutor(
                downloader = UmaSessionResumeDownloader(filesClient, UmaBinaryRangeClient()),
                filesClient = filesClient,
                blobUploader = UmaGitBlobUploader(GitHubApiClient(applicationContext)),
                taskStore = store,
            )
            val progress = executor.execute(
                taskId = taskId,
                rootDirectory = File(base, "sessions/${record.task.sessionId}"),
            )
            setProgress(progressData(progress))
            if (progress.phase == UmaSessionUploadPhase.RAW_BLOBS) Result.retry() else Result.success()
        } catch (failure: Throwable) {
            val message = failure.message ?: failure::class.java.name
            runCatching {
                store.update(taskId) { current ->
                    if (current.progress.phase == UmaSessionUploadPhase.CANCELLED) current else
                        current.copy(progress = current.progress.copy(
                            lastError = message,
                            checkpointUpdatedAtMs = System.currentTimeMillis(),
                        ))
                }
            }
            Result.retry()
        }
    }

    companion object {
        const val KEY_TASK_ID = "uma_upload_task_id"
        private const val UNIQUE_PREFIX = "uma-session-upload-"

        fun enqueue(context: Context, taskId: String, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UmaSessionUploadWorker>()
                .setInputData(Data.Builder().putString(KEY_TASK_ID, taskId).build())
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(UNIQUE_PREFIX + taskId)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + taskId,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + taskId)
        }

        private fun progressData(progress: UmaSessionUploadProgress) = Data.Builder()
            .putString("phase", progress.phase.name)
            .putInt("raw_completed_files", progress.rawCompletedFiles)
            .putInt("raw_total_files", progress.rawTotalFiles)
            .putLong("raw_completed_bytes", progress.rawCompletedBytes)
            .putLong("raw_total_bytes", progress.rawTotalBytes)
            .putInt("next_cursor", progress.nextCursor)
            .build()

        private fun errorData(message: String) = Data.Builder().putString("error", message).build()
    }
}
