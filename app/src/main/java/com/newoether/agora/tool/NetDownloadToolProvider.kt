package com.newoether.agora.tool

import android.content.Context
import android.os.StatFs
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.audit.BinaryAuditEntry
import com.newoether.agora.audit.BinaryAuditStore
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * net_download_file：把一个 HTTP(S) 文件完整流式下载到本机 binary-audit 存储。
 *
 * 设计铁律（背景：2.3GB 附件曾把 512MB 堆打满连崩）：
 *  - 响应体永远走 256KiB 分段写盘，任何字节都不进堆；
 *  - 下载前校验 Content-Length + 磁盘剩余空间 + max_mb 上限，流中按 64MiB 周期复查空间；
 *  - 手动跟随重定向（≤5 跳）；Range 断点续传：网络中断/超时/短读后再次调用同一 URL
 *    自动从 .part 续传；完成后经 [BinaryAuditStore.adopt] 同卷 rename 原子落库；
 *  - 落库返回 source_id/sha256/byte_length，直接接 audit_read_bytes / audit_info，
 *    无需再 audit_import。
 */
class NetDownloadToolProvider(context: Context) : ToolProvider {

    private val appContext = context.applicationContext
    private val store by lazy { BinaryAuditStore(appContext) }
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadMutex = Mutex()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun text(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = TOOL,
                description = "完整下载一个 HTTP(S) 文件（APK/压缩包等，支持数 GB）到本机 binary-audit 存储：" +
                    "流式分段写盘、绝不整文件进内存，自动跟随重定向，下载前校验 Content-Length 与磁盘剩余空间；" +
                    "中断后再次调用同一 URL 自动断点续传；完成后返回 source_id/sha256/byte_length，" +
                    "可直接用 audit_read_bytes / audit_info 分析（无需再 audit_import）。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to text("待下载文件的 HTTP(S) 直链。"),
                        "name" to text("落库显示名（可选），缺省取 URL 文件名。"),
                        "max_mb" to integer("体积上限 MB（可选），缺省 $DEFAULT_MAX_MB；超限中止并清理断点。"),
                    ),
                    required = listOf("url"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name == TOOL

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != TOOL) return errorJson("Unknown net download tool")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()

        val urlText = text("url")
        if (urlText.isBlank()) return errorJson("url is required")
        val maxMb = text("max_mb").toIntOrNull() ?: DEFAULT_MAX_MB
        if (maxMb <= 0) return errorJson("max_mb must be positive")

        return try {
            downloadMutex.withLock {
                withContext(Dispatchers.IO) { downloadAndStore(urlText, text("name"), maxMb) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val part = File(downloadDir(), keyFor(urlText) + PART_SUFFIX)
            buildJsonObject {
                put("ok", false)
                put("error", (e.message ?: "download failed").take(500))
                put("downloaded_bytes", if (part.isFile) part.length() else 0L)
                put("hint", "断点已保留：再次调用 net_download_file（同一 URL）自动续传")
            }.toString()
        }
    }

    // ── 下载核心（全阻塞、无挂起点：工具超时只会延迟返回，不会撕毁断点）──

    private fun downloadAndStore(urlText: String, nameArg: String, maxMb: Int): String {
        require(isHttpUrl(urlText)) { "仅支持 http/https 直链：$urlText" }
        val key = keyFor(urlText)
        val dir = downloadDir()
        val partFile = File(dir, key + PART_SUFFIX)
        val metaFile = File(dir, key + META_SUFFIX)
        val doneFile = File(dir, key + DONE_SUFFIX)
        val maxBytes = maxMb.toLong() * 1_000_000L

        // 0) 上一次调用其实已完成（例如结果被工具超时吞掉）→ 直接命中缓存，不重下
        if (doneFile.isFile) {
            val entry = store.resolve(doneFile.readText().trim())
            return successJson(entry, urlText, resumedFromCompleted = true)
        }

        // 1) 断点状态：meta 与 URL 不匹配或缺失 → 断点视为无效
        var meta = if (metaFile.isFile) readMeta(metaFile) else null
        if (meta != null && meta.url != urlText) {
            partFile.delete(); metaFile.delete(); meta = null
        }
        if (meta == null && partFile.exists()) partFile.delete()
        val displayName = nameArg.ifBlank { meta?.name?.ifBlank { null } ?: defaultNameFromUrl(urlText) }
        var resumeFrom = if (partFile.isFile) partFile.length() else 0L

        // 2) 连接循环（手动重定向 + 416 一次重启）
        var target = URL(urlText)
        var redirects = 0
        var restarts = 0
        while (true) {
            val conn = target.openConnection() as HttpURLConnection
            val code: Int
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", Constants.WEB_FETCH_USER_AGENT)
                conn.setRequestProperty("Accept", "*/*")
                if (resumeFrom > 0) conn.setRequestProperty("Range", rangeHeaderFor(resumeFrom))
                code = conn.responseCode

                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("HTTP $code redirect without Location header")
                    conn.disconnect()
                    redirects++
                    require(redirects <= MAX_REDIRECTS) { "Too many redirects (>$MAX_REDIRECTS)" }
                    target = URL(target, location)
                    continue
                }
                if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                    conn.disconnect()
                    val metaTotal = meta?.totalBytes ?: -1L
                    if (metaTotal > 0 && resumeFrom == metaTotal) {
                        return finalizeDownload(partFile, metaFile, doneFile, displayName, urlText)
                    }
                    require(restarts < 1) { "Range 416 and resume state unusable" }
                    restarts++
                    resumeFrom = 0L
                    partFile.delete(); metaFile.delete(); meta = null
                    target = URL(urlText)
                    continue
                }
                if (code !in 200..299) {
                    val body = runCatching {
                        conn.errorStream?.buffered()?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull()
                    throw IOException("HTTP $code for $target" + (body?.let { ": ${it.take(200)}" } ?: ""))
                }

                val append = code == HTTP_PARTIAL && resumeFrom > 0
                val declared = parseContentLength(conn.getHeaderField("Content-Length"))
                val totalBytes = if (append) {
                    meta?.totalBytes?.takeIf { it > 0 } ?: if (declared >= 0) resumeFrom + declared else -1L
                } else {
                    declared
                }
                if (totalBytes > maxBytes) {
                    partFile.delete(); metaFile.delete()
                    throw IOException("文件 ${(totalBytes / 1_000_000)}MB 超过 max_mb=$maxMb 上限，已清理断点")
                }
                if (totalBytes > 0) ensureSpace(totalBytes)
                meta = DownloadMeta(url = urlText, name = displayName, totalBytes = totalBytes)
                writeMeta(metaFile, meta)

                conn.inputStream.use { input ->
                    FileOutputStream(partFile, append).use { out ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var sinceSpaceCheck = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            sinceSpaceCheck += read
                            if (sinceSpaceCheck >= SPACE_CHECK_INTERVAL) {
                                sinceSpaceCheck = 0L
                                ensureSpace(partFile.length())
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            // 3) 完整性：声明了长度则校验（短读 → 保留断点，提示续传）
            val got = partFile.length()
            val expected = meta?.totalBytes ?: -1L
            if (expected > 0 && got < expected) {
                return buildJsonObject {
                    put("ok", false)
                    put("error", "连接中断：已下载 $got / $expected 字节")
                    put("downloaded_bytes", got)
                    put("total_bytes", expected)
                    put("hint", "再次调用 net_download_file（同一 URL）自动续传")
                }.toString()
            }
            if (expected > 0 && got > expected) {
                partFile.delete(); metaFile.delete()
                throw IOException("server sent more bytes than declared Content-Length ($got > $expected)")
            }
            return finalizeDownload(partFile, metaFile, doneFile, displayName, urlText)
        }
    }

    /** 同卷 rename 落库（BinaryAuditStore.adopt），写完成标记，供超时后的重试直接命中。 */
    private fun finalizeDownload(
        partFile: File,
        metaFile: File,
        doneFile: File,
        displayName: String,
        urlText: String,
    ): String {
        val entry = store.adopt(partFile, displayName)
        metaFile.delete()
        doneFile.writeText(entry.sourceId)
        return successJson(entry, urlText, resumedFromCompleted = false)
    }

    private fun ensureSpace(bytesNeeded: Long) {
        val available = StatFs(appContext.filesDir.path).availableBytes
        require(available > bytesNeeded + SPACE_MARGIN_BYTES) {
            "磁盘剩余空间不足：需要约 ${bytesNeeded / 1_000_000}MB（含安全余量），当前可用 ${available / 1_000_000}MB"
        }
    }

    private fun downloadDir(): File = File(appContext.cacheDir, "netdl").apply { mkdirs() }

    private fun readMeta(file: File): DownloadMeta? = runCatching {
        val obj = json.decodeFromString<Map<String, JsonElement>>(file.readText())
        DownloadMeta(
            url = (obj["url"] as? JsonPrimitive)?.content.orEmpty(),
            name = (obj["name"] as? JsonPrimitive)?.content.orEmpty(),
            totalBytes = (obj["total_bytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1L,
        )
    }.getOrNull()

    private fun writeMeta(file: File, meta: DownloadMeta) {
        file.writeText(buildJsonObject {
            put("url", meta.url)
            put("name", meta.name)
            put("total_bytes", meta.totalBytes)
        }.toString())
    }

    private fun successJson(entry: BinaryAuditEntry, url: String, resumedFromCompleted: Boolean) =
        buildJsonObject {
            put("ok", true)
            put("source_id", entry.sourceId)
            put("name", entry.name)
            put("byte_length", entry.byteLength)
            put("sha256", entry.sha256)
            put("url", url)
            put("hit_completed_cache", resumedFromCompleted)
            put("next", "用 audit_read_bytes / audit_info 直接分析该 source_id")
        }.toString()

    private fun errorJson(message: String) = buildJsonObject {
        put("ok", false)
        put("error", message.take(500))
    }.toString()

    private data class DownloadMeta(val url: String, val name: String, val totalBytes: Long)

    companion object {
        private const val TOOL = "net_download_file"
        private const val PART_SUFFIX = ".part"
        private const val META_SUFFIX = ".meta"
        private const val DONE_SUFFIX = ".done"
        private const val BUFFER_BYTES = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val DEFAULT_MAX_MB = 8192
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val SPACE_MARGIN_BYTES = 512L * 1024 * 1024
        private const val SPACE_CHECK_INTERVAL = 64L * 1024 * 1024

        fun keyFor(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun sanitizeFileName(raw: String): String =
            raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
                .trim()
                .take(120)
                .ifBlank { "download.bin" }

        fun defaultNameFromUrl(url: String): String {
            val path = url.substringBefore('?').substringBefore('#')
            val last = path.substringAfterLast('/')
            val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
            return sanitizeFileName(decoded.ifBlank { "download.bin" })
        }

        fun rangeHeaderFor(offset: Long): String = "bytes=$offset-"

        fun parseContentLength(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L

        fun isHttpUrl(raw: String): Boolean = runCatching {
            val protocol = URL(raw).protocol.lowercase()
            protocol == "http" || protocol == "https"
        }.getOrDefault(false)
    }
}
