package com.newoether.agora

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.newoether.agora.di.AppContainer
import com.newoether.agora.github.ConversationArchiveManager
import com.newoether.agora.github.ConversationDatabaseArchiveWorker
import com.newoether.agora.util.CrashReporter
import java.util.concurrent.TimeUnit

class AgoraApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container.automationScheduler.start()

        // Hard data-safety rule: scan all Room messages independently of the UI's bounded window.
        ConversationArchiveManager.enqueueDatabaseScan(this)
        val periodic = PeriodicWorkRequestBuilder<ConversationDatabaseArchiveWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "conversation-database-archive-periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }
}
