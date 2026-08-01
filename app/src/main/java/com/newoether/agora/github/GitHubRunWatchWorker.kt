package com.newoether.agora.github

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Best-effort Android background polling. WorkManager timing is not exact under Doze. */
class GitHubRunWatchWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val manager = GitHubRunWatchManager(applicationContext)
        val watch = manager.get(id) ?: return Result.success()
        if (!watch.active) return Result.success()
        return try {
            val terminal = manager.poll(id)
            if (!terminal) enqueue(applicationContext, id, 30)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_ID = "watch_id"
        private fun workName(id: String) = "github-run-watch-$id"

        fun enqueue(context: Context, id: String, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<GitHubRunWatchWorker>()
                .setInputData(Data.Builder().putString(KEY_ID, id).build())
                .setInitialDelay(delaySeconds.coerceIn(15, 15 * 60), TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
        }
    }
}
