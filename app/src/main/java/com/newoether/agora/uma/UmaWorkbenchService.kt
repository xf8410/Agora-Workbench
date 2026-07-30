package com.newoether.agora.uma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Agora-native Uma monitor. The game remains the only foreground Activity; this foreground
 * service reads the injected SO directly and renders a small overlay without uma-juece/browser. */
class UmaWorkbenchService : Service() {
    companion object {
        private const val CHANNEL = "agora_uma_workbench"
        private const val NOTIFICATION = 18765
        const val ACTION_START = "com.newoether.agora.uma.START"
        const val ACTION_STOP = "com.newoether.agora.uma.STOP"
        const val ACTION_REFRESH = "com.newoether.agora.uma.REFRESH"

        fun start(context: Context) {
            val intent = Intent(context, UmaWorkbenchService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, UmaWorkbenchService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var statusText: TextView? = null
    private var lastSignature = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION, notification("等待连接 127.0.0.1:18765"))
        if (Settings.canDrawOverlays(this)) showOverlay()
        startMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH -> scope.launch { pollOnce(force = true) }
            else -> {
                if (overlay == null && Settings.canDrawOverlays(this)) showOverlay()
                startMonitor()
            }
        }
        return START_STICKY
    }

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                pollOnce(force = false)
                delay(2_000)
            }
        }
    }

    private suspend fun pollOnce(force: Boolean) {
        val raw = runCatching { httpGet("/summary", 128 * 1024) }.getOrElse {
            updateStatus("SO 未连接\n${it.message ?: "18765 无响应"}", "SO 未连接")
            return
        }
        val summary = runCatching { JSONObject(raw) }.getOrNull()
        val signature = summary?.let {
            listOf(
                it.optInt("turn", -1).toString(), it.optInt("month", -1).toString(),
                it.optInt("half", -1).toString(), it.optString("scenario", ""),
                it.optJSONObject("stats")?.toString().orEmpty(),
                it.optJSONArray("trainings")?.toString().orEmpty(),
            ).joinToString("|")
        } ?: raw.hashCode().toString()
        if (!force && signature == lastSignature) return
        lastSignature = signature
        val turn = summary?.optInt("turn", -1) ?: -1
        val scenario = summary?.optString("scenario", "")?.ifBlank { "育成" } ?: "育成"
        val stats = summary?.optJSONObject("stats")
        val line = if (stats != null) {
            "速${stats.optInt("speed")} 耐${stats.optInt("stamina")} 力${stats.optInt("power")} " +
                "根${stats.optInt("guts")} 智${stats.optInt("wiz")}"
        } else "已读取 summary"
        val display = buildString {
            append("SO ●  ").append(scenario)
            if (turn >= 0) append("  T").append(turn)
            append('\n').append(line)
            append("\n点此打开 Agora · 长按拖动")
        }
        updateStatus(display, "$scenario ${if (turn >= 0) "T$turn" else "已连接"}")
    }

    private suspend fun httpGet(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val connection = URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1_500
            connection.readTimeout = 3_000
            connection.useCaches = false
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder(minOf(maxChars, 8_192))
                val buf = CharArray(2_048)
                while (true) {
                    val count = reader.read(buf)
                    if (count < 0) break
                    if (result.length + count > maxChars) error("响应超过 ${maxChars / 1024} KiB")
                    result.append(buf, 0, count)
                }
                result.toString()
            }
        } finally { connection.disconnect() }
    }

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD15171C.toInt())
            textSize = 13f
            setPadding(24, 18, 24, 18)
            text = "Uma Workbench\n正在连接 SO…"
        }
        statusText = text
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            elevation = 12f
            setOnClickListener { openAgora() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 180 }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var moved = false
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) moved = true
                    params.x = originX - dx.toInt(); params.y = originY + dy.toInt()
                    runCatching { windowManager?.updateViewLayout(container, params) }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) openAgora(); true }
                else -> false
            }
        }
        runCatching { windowManager?.addView(container, params); overlay = container }
    }

    private fun openAgora() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun updateStatus(text: String, notificationText: String) {
        scope.launch(Dispatchers.Main) { statusText?.text = text }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(notificationText))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Agora · 赛马娘工作台")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, "刷新", PendingIntent.getService(this, 1, Intent(this, UmaWorkbenchService::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, "停止", PendingIntent.getService(this, 2, Intent(this, UmaWorkbenchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Uma Workbench", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
