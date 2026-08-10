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

/** Runs one bounded stage and reschedules the durable task until its single commit is complete. */
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

        if (record.progress.phase in setOf(
                UmaSessionUploadPhase.COMPLETE,
                UmaSessionUploadPhase.CANCELLED,
                UmaSessionUploadPhase.PAUSED,
            )
        ) return Result.success()

        return try {
            val filesClient = UmaStorageFilesClient()
            val github = GitHubApiClient(applicationContext)
            val uploader = UmaGitBlobUploader(github)
            val root = File(base, "sessions/${record.task.sessionId}")
            val progress = when (record.progress.phase) {
                UmaSessionUploadPhase.QUEUED,
                UmaSessionUploadPhase.DOWNLOAD,
                UmaSessionUploadPhase.RAW_BLOBS -> UmaSessionRawBlobBatchExecutor(
                    downloader = UmaSessionResumeDownloader(filesClient, UmaBinaryRangeClient()),
                    filesClient = filesClient,
                    blobUploader = uploader,
                    taskStore = store,
                ).execute(taskId, root)

                UmaSessionUploadPhase.DERIVE,
                UmaSessionUploadPhase.DERIVED_BLOBS -> UmaSessionDerivedBlobBatchExecutor(
                    filesClient = filesClient,
                    blobUploader = uploader,
                    taskStore = store,
                ).execute(taskId, root)

                UmaSessionUploadPhase.TREE,
                UmaSessionUploadPhase.COMMIT -> UmaSessionUploadFinalizer(
                    filesClient = filesClient,
                    treeClient = UmaGitTreeClient(github),
                    commitClient = UmaGitCommitClient(github),
                    taskStore = store,
                ).finalize(taskId, root)

                UmaSessionUploadPhase.FAILED -> error("failed upload task must be explicitly resumed")
                UmaSessionUploadPhase.COMPLETE,
                UmaSessionUploadPhase.PAUSED,
                UmaSessionUploadPhase.CANCELLED -> return Result.success()
            }
            setProgress(progressData(progress))
            if (progress.complete) Result.success() else Result.retry()
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
            .putInt("derived_completed_files", progress.derivedCompletedFiles)
            .putInt("derived_total_files", progress.derivedTotalFiles)
            .putInt("next_cursor", progress.nextCursor)
            .putString("tree_sha", progress.treeSha)
            .putString("commit_sha", progress.commitSha)
            .build()

        private fun errorData(message: String) = Data.Builder().putString("error", message).build()
    }
}
