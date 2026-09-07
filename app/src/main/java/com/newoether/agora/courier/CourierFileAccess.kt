package com.newoether.agora.courier

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One directory entry produced by the strategy chain. */
data class PhoneDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/** Which strategies are usable on this device right now (shown in Settings). */
data class StrategyProbe(
    val directAvailable: Boolean,
    val safAuthorizedTreeUris: List<String>,
    val suAvailable: Boolean,
)

/**
 * 读取策略链：①java.io File 直读（公共目录 + App 自有目录）
 * ②SAF document tree（用户持久授权，覆盖 Android/data——无 root 机主的主路径）
 * ③su -c（Runtime.exec，运行时探测，无 root 自动跳过）。
 *
 * 每次成功解析都返回实际使用的策略名，结果 JSON 如实记录。
 * [treeUris] 由调用方在 suspend 上下文里读取 DataStore 后传入（本类不做 I/O 取配置）。
 */
class CourierFileAccess(
    context: Context,
    private val treeUris: Set<String>,
) {
    private val appContext = context.applicationContext

    /** Storage root this strategy chain can map into tree grants (primary external storage). */
    private val primaryRoot: String by lazy {
        Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
    }

    // ── Strategy probing ─────────────────────────────────────

    suspend fun probe(): StrategyProbe = StrategyProbe(
        directAvailable = probeDirect(),
        safAuthorizedTreeUris = treeUris.toList(),
        suAvailable = suAvailable(),
    )

    private fun probeDirect(): Boolean {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return try {
            downloads.exists() && (downloads.list() != null || downloads.isDirectory)
        } catch (_: SecurityException) {
            false
        }
    }

    // ── Listing ──────────────────────────────────────────────

    /**
     * Lists one directory through the strategy chain.
     * @return strategy name ("direct" | "saf" | "su") and its entries.
     * @throws CourierAccessException when every strategy fails.
     */
    suspend fun listDir(path: String): Pair<String, List<PhoneDirEntry>> {
        SafPathMapper.requireNormalized(path)
        // ① direct
        try {
            val dir = File(path)
            val children = dir.listFiles()
            if (children != null) {
                return "direct" to children.map {
                    PhoneDirEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L)
                }
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "direct list failed for $path: ${e.message}")
        }
        // ② SAF
        try {
            val entries = safList(path)
            return "saf" to entries
        } catch (e: Exception) {
            DebugLog.d(TAG, "saf list failed for $path: ${e.message}")
        }
        // ③ su
        if (suAvailable()) {
            val entries = suList(path)
            return "su" to entries
        }
        throw CourierAccessException(path, "direct/SAF/su 均无法读取该目录；若是 Android/data 子目录，请先到 设置→GitHub→文件投递 授权")
    }

    /**
     * Opens one file through the strategy chain.
     * @return strategy name and an open stream (caller closes).
     */
    suspend fun openFile(path: String): Pair<String, InputStream> {
        SafPathMapper.requireNormalized(path)
        // ① direct
        try {
            val file = File(path)
            if (file.isFile && file.canRead()) {
                return "direct" to file.inputStream().buffered()
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "direct open failed for $path: ${e.message}")
        }
        // ② SAF
        try {
            val stream = safOpen(path)
            return "saf" to stream
        } catch (e: Exception) {
            DebugLog.d(TAG, "saf open failed for $path: ${e.message}")
        }
        // ③ su
        if (suAvailable()) {
            return "su" to suOpen(path)
        }
        throw CourierAccessException(path, "direct/SAF/su 均无法读取该文件；若是 Android/data 子目录，请先到 设置→GitHub→文件投递 授权")
    }

    /** Stat one file through the strategy chain without opening it. */
    suspend fun statFile(path: String): Pair<String, Long> {
        SafPathMapper.requireNormalized(path)
        val direct = File(path)
        if (direct.isFile && direct.canRead()) return "direct" to direct.length()
        val parent = path.substringBeforeLast('/', "")
        require(parent.startsWith('/')) { "无法定位父目录：$path" }
        val (strategy, entries) = listDir(parent)
        val entry = entries.firstOrNull { it.name == path.substringAfterLast('/') }
            ?: throw CourierAccessException(path, "父目录中找不到该文件")
        require(!entry.isDirectory) { "该路径是目录，请用 upload_phone_dir" }
        return strategy to entry.sizeBytes
    }

    // ── SAF strategy ─────────────────────────────────────────

    private fun safDocumentId(path: String): String? {
        val trees = treeUris.mapNotNull { raw ->
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(raw)) }.getOrNull()
        }
        return SafPathMapper.documentIdForPath(path, primaryRoot, trees)
    }

    private fun safList(path: String): List<PhoneDirEntry> {
        val documentId = safDocumentId(path)
            ?: throw CourierAccessException(path, "路径不在任何已授权的 SAF 目录内（请在 设置→GitHub→文件投递 授权）")
        val treeUri = pickTreeUri(documentId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val entries = mutableListOf<PhoneDirEntry>()
        appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val size = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) 0L else cursor.getLong(3)
                entries += PhoneDirEntry(name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size)
            }
        } ?: throw CourierAccessException(path, "SAF 查询返回 null（授权可能已失效，请重新授权）")
        return entries
    }

    private fun safOpen(path: String): InputStream {
        val documentId = safDocumentId(path)
            ?: throw CourierAccessException(path, "路径不在任何已授权的 SAF 目录内（请在 设置→GitHub→文件投递 授权）")
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(pickTreeUri(documentId), documentId)
        return appContext.contentResolver.openInputStream(documentUri)
            ?: throw CourierAccessException(path, "SAF 打开输入流失败")
    }

    /** Picks the deepest authorized tree grant that covers [documentId] (same rule as [SafPathMapper]). */
    private fun pickTreeUri(documentId: String): Uri {
        val volume = documentId.substringBefore(':', "")
        val relative = documentId.substringAfter(':', "").trim('/')
        var bestUri: Uri? = null
        var bestDepth = -1
        for (raw in treeUris) {
            val uri = Uri.parse(raw)
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: continue
            if (treeDocId.substringBefore(':') != volume) continue
            val treeRelative = treeDocId.substringAfter(':', "").trim('/')
            val covered = treeRelative.isEmpty() ||
                relative == treeRelative ||
                relative.startsWith("$treeRelative/")
            if (!covered) continue
            val depth = treeRelative.split('/').size
            if (depth > bestDepth) {
                bestDepth = depth
                bestUri = uri
            }
        }
        return bestUri ?: throw CourierAccessException(documentId, "没有覆盖该路径的 SAF 授权")
    }

    // ── su strategy ──────────────────────────────────────────

    suspend fun suAvailable(): Boolean {
        // Process-level cache: probing spawns a root shell, so never do it repeatedly.
        synchronized(SU_LOCK) { SU_AVAILABILITY?.let { return it } }
        val result = withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                val done = process.waitFor(Constants.COURIER_SU_PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                process.inputStream.use { it.readBytes() } // drain to avoid buffer deadlock
                val ok = done && process.exitValue() == 0
                if (!done) process.destroy()
                ok
            } catch (_: Exception) {
                false
            }
        }
        synchronized(SU_LOCK) { SU_AVAILABILITY = result }
        return result
    }

    private suspend fun suList(path: String): List<PhoneDirEntry> = withContext(Dispatchers.IO) {
        // toybox ls -l: perms links owner group size date date time name…
        // The name may contain spaces, so everything after column 8 is the name.
        val output = execSu("ls -A -l '${suQuote(path)}'", Constants.COURIER_SU_LIST_TIMEOUT_MS)
        val entries = mutableListOf<PhoneDirEntry>()
        output.lineSequence().forEach { line ->
            if (line.isBlank() || line.startsWith("total")) return@forEach
            val columns = line.split(' ', limit = 9)
            if (columns.size < 9) return@forEach
            val isDir = columns[0].startsWith("d")
            val size = columns[4].toLongOrNull() ?: 0L
            var name = columns[8]
            if (name.endsWith("/") && isDir) name = name.trimEnd('/')
            entries += PhoneDirEntry(name, isDir, size)
        }
        entries
    }

    private fun suOpen(path: String): InputStream =
        ProcessBuilder("su", "-c", "cat '${suQuote(path)}'")
            .redirectErrorStream(false)
            .start()
            .inputStream
            .buffered()

    private fun execSu(command: String, timeoutMs: Int): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        val output = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        if (!finished) {
            process.destroy()
            throw CourierAccessException(command, "su 命令超时")
        }
        if (process.exitValue() != 0) {
            throw CourierAccessException(command, "su 命令失败（exit ${process.exitValue()}）：${output.take(200)}")
        }
        return output
    }

    /** Shell-quotes inside a single-quoted argument. */
    private fun suQuote(value: String): String = value.replace("'", "'\\''")

    companion object {
        private const val TAG = "CourierFileAccess"
        private const val SU_LOCK = "courier-su-probe"
        @Volatile
        private var SU_AVAILABILITY: Boolean? = null

        /** Convenience intent that opens the system "All files access" switch for this app. */
        fun allFilesAccessIntent(context: Context): Intent = Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }
}

/** Raised when every read strategy failed for one path; surfaced verbatim into errors[]. */
class CourierAccessException(val path: String, message: String) : RuntimeException(message)
