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
import com.newoether.agora.AgoraApplication
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.data.local.ChatEntity
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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

/** Agora-native Uma monitor and bounded headless-analysis coordinator. */
class UmaWorkbenchService : Service() {
    companion object {
        private const val CHANNEL = "agora_uma_workbench"
        private const val NOTIFICATION = 18765
        private const val PREFS = "uma_workbench"
        private const val KEY_AUTO = "auto_analyze"
        private const val KEY_CONVERSATION = "conversation_id"
        private const val KEY_LAST_ANALYZED = "last_analyzed_signature"
        private const val MIN_ANALYSIS_INTERVAL_MS = 20_000L
        const val ACTION_START = "com.newoether.agora.uma.START"
        const val ACTION_STOP = "com.newoether.agora.uma.STOP"
        const val ACTION_REFRESH = "com.newoether.agora.uma.REFRESH"
        const val ACTION_ANALYZE = "com.newoether.agora.uma.ANALYZE"
        const val ACTION_TOGGLE_AUTO = "com.newoether.agora.uma.TOGGLE_AUTO"

        fun start(context: Context) {
            val i = Intent(context, UmaWorkbenchService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, UmaWorkbenchService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyzing = AtomicBoolean(false)
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var monitorJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var statusText: TextView? = null
    private var lastSignature = ""
    private var latestDisplay = "Uma Workbench\n正在连接 SO…"
    private var lastAnalysisAt = 0L

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
            ACTION_REFRESH -> scope.launch { pollOnce(true) }
            ACTION_ANALYZE -> scope.launch { analyzeNow(manual = true) }
            ACTION_TOGGLE_AUTO -> {
                prefs.edit().putBoolean(KEY_AUTO, !prefs.getBoolean(KEY_AUTO, false)).apply()
                updateStatus(latestDisplay, notificationLabel())
            }
            else -> { if (overlay == null && Settings.canDrawOverlays(this)) showOverlay(); startMonitor() }
        }
        return START_STICKY
    }

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch { while (isActive) { pollOnce(false); delay(2_000) } }
    }

    private suspend fun pollOnce(force: Boolean) {
        val raw = runCatching { httpGet("/summary", 128 * 1024) }.getOrElse {
            updateStatus("SO 未连接\n${it.message ?: "18765 无响应"}", "SO 未连接")
            return
        }
        val changes = runCatching { UmaRuntimeState.update(raw) }.getOrElse {
            updateStatus("SO 响应解析失败\n${it.message}", "响应解析失败")
            return
        }
        val summary = JSONObject(raw)
        val signature = listOf(
            summary.optInt("turn", -1), summary.optInt("month", -1), summary.optInt("half", -1),
            summary.optString("scenario", ""), summary.optJSONObject("stats")?.toString().orEmpty(),
            summary.optJSONArray("trainings")?.toString().orEmpty(),
        ).joinToString("|")
        if (!force && signature == lastSignature) return
        lastSignature = signature
        latestDisplay = formatSummary(summary)
        updateStatus(latestDisplay, notificationLabel())

        val changed = changes.optJSONArray("changed")
        val meaningful = changes.optBoolean("meaningful", false)
        val trigger = changed?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.any {
                it in setOf("turn", "month", "half", "scenario", "trainings", "initial_snapshot")
            }
        } == true
        if (prefs.getBoolean(KEY_AUTO, false) && meaningful && trigger &&
            signature != prefs.getString(KEY_LAST_ANALYZED, "") &&
            System.currentTimeMillis() - lastAnalysisAt >= MIN_ANALYSIS_INTERVAL_MS) {
            analyzeNow(manual = false)
        }
    }

    private fun formatSummary(summary: JSONObject): String {
        val turn = summary.optInt("turn", -1)
        val scenario = summary.optString("scenario", "").ifBlank { "育成" }
        val stats = summary.optJSONObject("stats")
        val line = if (stats != null) "速${stats.optInt("speed")} 耐${stats.optInt("stamina")} " +
            "力${stats.optInt("power")} 根${stats.optInt("guts")} 智${stats.optInt("wiz")}" else "已读取 summary"
        return "SO ●  $scenario${if (turn >= 0) "  T$turn" else ""}\n$line\n" +
            "自动:${if (prefs.getBoolean(KEY_AUTO, false)) "开" else "关"} · 点开 Agora · 长按拖动"
    }

    private suspend fun analyzeNow(manual: Boolean) {
        if (!analyzing.compareAndSet(false, true)) return
        try {
            lastAnalysisAt = System.currentTimeMillis()
            updateStatus("$latestDisplay\nAI 分析中…", "AI 分析中")
            val app = application as AgoraApplication
            val container = app.container
            val conversationId = ensureConversation(container)
            val changes = UmaRuntimeState.changesJson().take(4_096)
            val prompt = """
                赛马娘 SO 状态发生变化。请使用 Agora 内置 uma_get_snapshot，必要时使用其他 uma_* 白名单工具分析当前状态。
                当前结构变化：$changes
                遵守证据等级：[MDB] / [截图确认] / [代码确认] / [候选] / [unknown]。禁止猜测，禁止全量类扫描、/scan、/il2cpp/classes、raw sniff 或递归 dump。
                输出简短：当前事实、可信结论、未知项、下一步需要等待的游戏操作或定点类查询。
            """.trimIndent()
            when (val result = container.taskExecutionEngine.runOnce(
                conversationId = conversationId,
                userText = prompt,
                foregroundServiceManagedExternally = true,
            )) {
                is TaskExecutionEngine.Result.Success -> {
                    prefs.edit().putString(KEY_LAST_ANALYZED, lastSignature).apply()
                    val compact = result.text.replace('\n', ' ').take(160)
                    updateStatus("$latestDisplay\nAI：$compact", "AI 分析完成")
                }
                is TaskExecutionEngine.Result.Failure -> updateStatus(
                    "$latestDisplay\nAI 失败：${result.reason.take(120)}", "AI 分析失败")
            }
        } catch (e: Exception) {
            updateStatus("$latestDisplay\nAI 失败：${e.message?.take(120)}", "AI 分析失败")
        } finally { analyzing.set(false) }
    }

    private suspend fun ensureConversation(container: com.newoether.agora.di.AppContainer): String {
        val saved = prefs.getString(KEY_CONVERSATION, null)
        if (!saved.isNullOrBlank() && container.conversationRepository.getConversation(saved) != null) return saved
        val id = UUID.randomUUID().toString()
        container.conversationRepository.upsertConversation(ChatEntity(
            id = id,
            title = "赛马娘自动分析",
            modelId = container.settingsRepository.selectedModel.value,
            origin = "user",
            graduated = true,
        ))
        prefs.edit().putString(KEY_CONVERSATION, id).apply()
        return id
    }

    private suspend fun httpGet(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val c = URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 1_500; c.readTimeout = 3_000; c.useCaches = false
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val out = StringBuilder(minOf(maxChars, 8_192)); val buf = CharArray(2_048)
                while (true) { val n = r.read(buf); if (n < 0) break
                    if (out.length + n > maxChars) error("响应超过 ${maxChars / 1024} KiB")
                    out.append(buf, 0, n) }
                out.toString()
            }
        } finally { c.disconnect() }
    }

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val text = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0xDD15171C.toInt())
            textSize = 13f; setPadding(24, 18, 24, 18); text = latestDisplay }
        statusText = text
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(text); elevation = 12f }
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 180 }
        var dx0=0f; var dy0=0f; var ox=0; var oy=0; var moved=false
        box.setOnTouchListener { _, e -> when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx0=e.rawX; dy0=e.rawY; ox=p.x; oy=p.y; moved=false; true }
            MotionEvent.ACTION_MOVE -> { val dx=e.rawX-dx0; val dy=e.rawY-dy0; if(kotlin.math.abs(dx)+kotlin.math.abs(dy)>12)moved=true
                p.x=ox-dx.toInt(); p.y=oy+dy.toInt(); runCatching{windowManager?.updateViewLayout(box,p)}; true }
            MotionEvent.ACTION_UP -> { if(!moved) openAgora(); true }; else -> false } }
        runCatching { windowManager?.addView(box, p); overlay=box }
    }

    private fun openAgora() = startActivity(Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) })

    private fun notificationLabel() = "SO 已连接 · 自动${if (prefs.getBoolean(KEY_AUTO, false)) "开" else "关"}"
    private fun updateStatus(text: String, note: String) { scope.launch(Dispatchers.Main) { statusText?.text=text }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(note)) }

    private fun servicePending(action: String, request: Int) = PendingIntent.getService(this, request,
        Intent(this, UmaWorkbenchService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Agora · 赛马娘工作台").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0,"分析",servicePending(ACTION_ANALYZE,1)).addAction(0,"自动开关",servicePending(ACTION_TOGGLE_AUTO,2))
        .addAction(0,"停止",servicePending(ACTION_STOP,3)).build()
    private fun createChannel() { if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL,"Uma Workbench",NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { monitorJob?.cancel(); overlay?.let{runCatching{windowManager?.removeView(it)}}; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
