package com.newoether.agora

import android.app.Application
import com.newoether.agora.di.AppContainer
import com.newoether.agora.util.CrashReporter

class AgoraApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        com.newoether.agora.api.SessionUsageRuntime.install(this)
        com.newoether.agora.uma.UmaApplicationContext.install(this)
        container.automationScheduler.start()
    }
}
