package com.newoether.agora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.app.ActivityManager
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.util.DebugLog

/**
 * Thread-safe owner set for the shared generation foreground service. The transition callbacks
 * run under the same lock as the set mutation, preventing a last-release stop from racing past a
 * new first-acquire start. Duplicate acquires/releases are deliberately idempotent.
 */
internal class ForegroundOwnerLeases {
    private val owners = linkedSetOf<String>()

    fun acquire(owner: String, onFirstAcquire: () -> Boolean): Boolean = synchronized(owners) {
        if (!owners.add(owner)) return@synchronized false
        if (owners.size == 1 && !onFirstAcquire()) {
            owners.remove(owner)
            return@synchronized false
        }
        true
    }

    fun release(owner: String, onLastRelease: () -> Unit): Boolean = synchronized(owners) {
        if (!owners.remove(owner)) return@synchronized false
        if (owners.isEmpty()) onLastRelease()
        true
    }

    fun size(): Int = synchronized(owners) { owners.size }
}

/** Uses all non-sign bits, including the Int.MIN_VALUE edge that Math.abs cannot normalize. */
internal fun stableCompletionNotificationId(conversationId: String): Int =
    conversationId.hashCode() and Int.MAX_VALUE

class AgoraForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "agora_generation_status"
        const val NOTIFICATION_ID = 1
        private const val COMPLETION_CHANNEL_ID = "agora_completed"
        private const val TAG = "AgoraForegroundService"
        private var instance: AgoraForegroundService? = null
        private val ownerLeases = ForegroundOwnerLeases()

        /** Acquires this generation's lease; returns false for a duplicate owner/start failure. */
        fun acquire(context: Context, owner: String): Boolean {
            if (owner.isBlank()) return false
            return ownerLeases.acquire(owner) { startService(context) }
        }

        private fun startService(context: Context): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AgoraForegroundService::class.java)
            // Record process importance (foreground vs background) at start — both as a diagnostic
            // trail for the unreproducible "did not start in time" crash (#60) and as the gate.
            val info = ActivityManager.RunningAppProcessInfo()
            val importance = try {
                ActivityManager.getMyMemoryState(info)
                info.importance
            } catch (e: Exception) {
                CrashReporter.note("FGS.start getMyMemoryState threw ${e.javaClass.simpleName}")
                // If we can't read state, assume foreground so we don't silently disable FGS.
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
            CrashReporter.note("FGS.start api=${Build.VERSION.SDK_INT} importance=$importance trim=${info.lastTrimLevel}")
            // Fail-closed (the 5s-timeout crash is the alternative): starting a foreground service
            // while the process is already backgrounded is exactly what triggers
            // ForegroundServiceDidNotStartInTimeException — the system defers Service instantiation
            // and onCreate's startForeground can't run within 5s (#60, 140 crashes). The generation
            // that requested the lease keeps running on its existing coroutine without a persistent
            // notification; a possible later OS kill under memory pressure is a far better failure
            // mode than an immediate crash. Foreground owners (importance <= FOREGROUND_SERVICE)
            // always proceed.
            if (importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                CrashReporter.note("FGS.start skipped importance=$importance not-foreground")
                DebugLog.w(TAG, "Skipping FGS start: process not foreground (importance=$importance)")
                return false
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                CrashReporter.note("FGS.startForegroundService ok")
                true
            } catch (e: RuntimeException) {
                CrashReporter.note("FGS.startForegroundService threw ${e.javaClass.simpleName}")
                DebugLog.w(TAG, "Failed to start foreground service", e)
                false
            }
        }

        fun updateText(text: String) {
            instance?.updateNotificationText(text)
        }

        /** Releases only [owner]'s lease. The service stops after the final distinct owner. */
        fun release(context: Context, owner: String) {
            val released = ownerLeases.release(owner) {
                CrashReporter.note("FGS.stop foregroundStarted=${instance?.foregroundStarted}")
                val appContext = context.applicationContext
                appContext.stopService(Intent(appContext, AgoraForegroundService::class.java))
            }
            CrashReporter.note("FGS.release released=$released owners=${ownerLeases.size()}")
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Generation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification while Agora is generating"
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        fun showCompletionNotification(context: Context, responseText: String, conversationId: String) {
            createCompletionChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.agora_responded))
                .setContentText(if (responseText.length > 200) responseText.take(200) + "…" else responseText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(createPendingIntent(context, stableCompletionNotificationId(conversationId), conversationId))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        if (responseText.length > 200) responseText.take(200) + "…" else responseText
                    )
                )
                .build()

            try {
                manager.notify(stableCompletionNotificationId(conversationId), notification)
            } catch (e: RuntimeException) {
                DebugLog.w(TAG, "Failed to show completion notification", e)
            }
        }

        private fun createCompletionChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "Response Ready",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shown when a response finishes generating"
                setShowBadge(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        private fun createPendingIntent(
            context: Context,
            requestCode: Int,
            conversationId: String? = null,
        ): PendingIntent {
            return PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    conversationId?.let {
                        data = Uri.Builder()
                            .scheme("agora")
                            .authority("conversation")
                            .appendPath(it)
                            .build()
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, it)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    @Volatile private var currentText: String = "Generating response…"
    private var foregroundStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.note("FGS.onCreate")
        createChannel(this)
        val notification = buildGenerationNotification(currentText)
        // Must NOT catch exceptions here: if startForeground() fails, the real
        // exception (SecurityException, ForegroundServiceStartNotAllowed, etc.)
        // must propagate so Crashlytics/logs capture it. Catching + stopSelf()
        // leaves the system's 5-second timeout to fire, which only surfaces the
        // useless ForegroundServiceDidNotStartInTimeException instead.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType()
        )
        foregroundStarted = true
        CrashReporter.note("FGS.startForeground ok")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() already called in onCreate(); no re-promote needed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    private fun updateNotificationText(text: String) {
        currentText = text
        if (!foregroundStarted) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildGenerationNotification(text))
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to update notification", e)
        }
    }

    private fun buildGenerationNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(createPendingIntent(this, 0))
            .build()
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }

    override fun onTimeout(type: Int, reason: Int) {
        CrashReporter.note("FGS.onTimeout type=$type reason=$reason")
        DebugLog.w(TAG, "Foreground service timed out: type=$type reason=$reason")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
