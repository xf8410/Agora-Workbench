package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.courier.CourierAccessException
import com.newoether.agora.courier.CourierError
import com.newoether.agora.courier.CourierFileAccess
import com.newoether.agora.courier.CourierLimits
import com.newoether.agora.courier.CourierManifest
import com.newoether.agora.courier.CourierUploader
import com.newoether.agora.courier.CourierVolumePlanner
import com.newoether.agora.courier.CourierVolumeWriter
import com.newoether.agora.courier.PlannedFile
import com.newoether.agora.courier.SafPathMapper
import com.newoether.agora.courier.UploadedVolume
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 文件投递（File Courier）工具族：让 Agora 内置智能体把手机上的文件/目录经
 * zip 分卷上传到机主指定的私有 GitHub 仓，供云端取回。读取走策略链
 * （File 直读 → SAF 授权目录 → su 探测），上传走 Git Blob API。
 *
 * upload_phone_dir / upload_phone_file 在一次工具调用内完成全部上传与重试，
 * 模型只需发起一次调用并汇总结果，严禁靠多轮对话循环传文件（控额度）。
 */
class PhoneFileToolProvider(context: Context) : ToolProvider {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { GitHubApiClient(appContext) }
    private val uploader by lazy { CourierUploader(client) }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun text(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = LIST_DIR,
                description = "列出手机上任意目录的内容（含子项类型/大小），走读取策略链（File 直读 → SAF 授权目录 → su 探测）。可读取公共存储与已授权的 Android/data 沙盒目录。",
                parameters = ToolParameters(
                    properties = mapOf("path" to text("手机上的绝对路径，如 /storage/emulated/0/Download 或 /storage/emulated/0/Android/data/<包名>/files。")),
                    required = listOf("path"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = UPLOAD_FILE,
                description = "把手机上单个文件投递到机主私有 GitHub 仓：默认按 ${Constants.COURIER_FILE_SPLIT_DEFAULT_MB}MB 切片（part_001.zip… + manifest.json，云端按序拼接还原）；split_mb=0 时整文件直传（≤${Constants.COURIER_MAX_SINGLE_BLOB_MB}MB）。一次调用完成全部上传与重试，禁止靠多轮对话循环传文件。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to text("手机上待投递文件的绝对路径。"),
                        "repo" to text("目标仓 owner/name；缺省用设置页「文件投递」里配置的仓。"),
                        "target_path" to text("仓内目标路径（可含目录，以 / 结尾则追加原文件名）；缺省 courier/<时间戳>/<原文件名>。"),
                        "split_mb" to integer("切片大小 MB；缺省 ${Constants.COURIER_FILE_SPLIT_DEFAULT_MB}，0 = 不切片直传。"),
                    ),
                    required = listOf("path"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = UPLOAD_DIR,
                description = "把手机上整个目录递归打包分卷投递到机主私有 GitHub 仓：递归收集 → 按文件边界 zip 分卷（part_001.zip…，不劈开单个文件）→ 全部上传 + manifest.json。一次调用完成全部上传与重试，返回 uploaded/errors 汇总。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to text("手机上待投递目录的绝对路径。"),
                        "repo" to text("目标仓 owner/name；缺省用设置页「文件投递」里配置的仓。"),
                        "target_prefix" to text("仓内目标目录前缀；缺省 courier/<时间戳>/。"),
                        "max_volume_mb" to integer("单卷上限 MB，缺省 ${Constants.COURIER_DEFAULT_VOLUME_MB}。"),
                        "max_total_mb" to integer("总字节上限 MB，缺省 ${Constants.COURIER_DEFAULT_TOTAL_MB}。"),
                        "max_files" to integer("文件数上限，缺省 ${Constants.COURIER_DEFAULT_MAX_FILES}。"),
                    ),
                    required = listOf("path"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name in NAMES

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in NAMES) return errorJson("Unknown phone file tool")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()

        return try {
            val treeUris = settings.courierSafTreeUris.first()
            val access = CourierFileAccess(appContext, treeUris)
            when (name) {
                LIST_DIR -> executeList(access, text("path"))
                UPLOAD_FILE -> executeUploadFile(
                    access,
                    path = text("path"),
                    repo = text("repo").ifBlank { settings.courierTargetRepo.first() },
                    targetPath = text("target_path"),
                    splitMb = text("split_mb").toIntOrNull() ?: Constants.COURIER_FILE_SPLIT_DEFAULT_MB,
                )
                UPLOAD_DIR -> executeUploadDir(
                    access,
                    path = text("path"),
                    repo = text("repo").ifBlank { settings.courierTargetRepo.first() },
                    targetPrefix = text("target_prefix"),
                    maxVolumeMb = text("max_volume_mb").toIntOrNull() ?: Constants.COURIER_DEFAULT_VOLUME_MB,
                    maxTotalMb = text("max_total_mb").toIntOrNull() ?: Constants.COURIER_DEFAULT_TOTAL_MB,
                    maxFiles = text("max_files").toIntOrNull() ?: Constants.COURIER_DEFAULT_MAX_FILES,
                )
                else -> errorJson("Unknown phone file tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "文件投递失败")
        }
    }

    // ── list_phone_dir ───────────────────────────────────────

    private suspend fun executeList(access: CourierFileAccess, rawPath: String): String {
        val path = normalizeInputPath(rawPath)
        val (strategy, entries) = access.listDir(path)
        val truncated = entries.size > Constants.COURIER_LIST_MAX_ENTRIES
        val shown = entries.take(Constants.COURIER_LIST_MAX_ENTRIES)
        return buildJsonObject {
            put("ok", true)
            put("path", path)
            put("strategy", strategy)
            put("entry_count", entries.size)
            put("truncated", truncated)
            putJsonArray("entries") {
                shown.forEach { entry ->
                    add(buildJsonObject {
                        put("name", entry.name)
                        put("type", if (entry.isDirectory) "dir" else "file")
                        put("size_bytes", entry.sizeBytes)
                    })
                }
            }
        }.toString()
    }

    // ── upload_phone_file ────────────────────────────────────

    private suspend fun executeUploadFile(
        access: CourierFileAccess,
        rawPath: String,
        repo: String,
        targetPath: String,
        splitMb: Int,
    ): String {
        val source = normalizeInputPath(rawPath)
        val safeRepo = client.validateRepo(repo)
        val branch = Constants.COURIER_DEFAULT_BRANCH
        require(splitMb >= 0) { "split_mb must be >= 0" }
        val splitBytes = splitMb.toLong() * 1_000_000L
        val blobCapBytes = Constants.COURIER_MAX_SINGLE_BLOB_MB * 1_000_000L

        val (statStrategy, fileSize) = access.statFile(source)
        require(fileSize > 0) { "文件为空或大小未知：$source" }

        val stamp = newStamp()
        val fileName = source.substringAfterLast('/')
        val destination = targetPath.trim().trimStart('/').ifBlank { "courier/$stamp/$fileName" }
        val githubPath = if (destination.endsWith('/')) destination + fileName else destination
        requireValidRepoPath(githubPath)

        uploader.ensureBranch(safeRepo, branch)

        // ① 不切片（split_mb=0 且 ≤ blob 上限，或文件本身小于切片阈值）→ 整文件直传
        if (splitBytes == 0L || fileSize <= splitBytes) {
            if (fileSize > blobCapBytes) {
                return errorJson(
                    "文件 ${(fileSize / 1_000_000)}MB 超过单 Blob 上限 ${Constants.COURIER_MAX_SINGLE_BLOB_MB}MB；" +
                        "请把 split_mb 设为 ${Constants.COURIER_FILE_SPLIT_DEFAULT_MB} 等正值以启用切片投递"
                )
            }
            val (strategy, stream) = access.openFile(source)
            val bytes = withContext(Dispatchers.IO) { stream.use { it.readBytes() } }
            val sha = sha256Hex(bytes)
            val committed = uploader.uploadBytes(
                safeRepo, branch, githubPath, bytes,
                "Courier: file $stamp $fileName",
            )
            return buildJsonObject {
                put("ok", true)
                put("source_path", source)
                put("source_strategy", strategy)
                put("repo", safeRepo)
                put("branch", branch)
                put("mode", "whole_file")
                put("uploaded", JsonArray(listOf(buildJsonObject {
                    put("source_path", source)
                    put("zip_name", committed.substringAfterLast('/'))
                    put("github_path", committed)
                    put("size_bytes", bytes.size.toLong())
                    put("sha256", sha)
                    put("strategy", strategy)
                })))
                put("total_bytes", bytes.size.toLong())
                put("volumes", 1)
                putJsonArray("errors") {}
            }.toString()
        }

        // ② 切片：part_001.zip…（raw byte slices，云端按序拼接）+ manifest.json
        val partBytes = splitBytes.coerceAtMost(blobCapBytes)
        val totalParts = ((fileSize + partBytes - 1) / partBytes).toInt()
        val partDir = githubPath.substringBeforeLast('/')
        val taskDir = File(appContext.cacheDir, "courier/file-$stamp").also { it.mkdirs() }
        try {
            val uploaded = mutableListOf<UploadedVolume>()
            val fileSha = MessageDigest.getInstance("SHA-256")
            withContext(Dispatchers.IO) {
                access.openFile(source).second.use { input ->
                    var partIndex = 1
                    var bytesInPart = 0L
                    var partFile = newPartFile(taskDir, partIndex)
                    var outputStream = partFile.outputStream().buffered()
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        fileSha.update(buffer, 0, read)
                        var consumed = 0
                        while (consumed < read) {
                            if (bytesInPart == partBytes && partIndex < totalParts) {
                                outputStream.close()
                                partIndex++
                                bytesInPart = 0L
                                partFile = newPartFile(taskDir, partIndex)
                                outputStream = partFile.outputStream().buffered()
                            }
                            val chunk = minOf(read - consumed, (partBytes - bytesInPart).toInt())
                            outputStream.write(buffer, consumed, chunk)
                            bytesInPart += chunk
                            consumed += chunk
                        }
                    }
                    outputStream.close()
                }
                for (index in 1..totalParts) {
                    val partFile = newPartFile(taskDir, index)
                    val partSha = sha256File(partFile)
                    val committed = uploader.uploadVolume(
                        safeRepo, branch, "$partDir/${CourierManifest.partName(index)}", partFile,
                        "Courier: file part $index/$totalParts $stamp",
                    )
                    uploaded += UploadedVolume(
                        zipName = CourierManifest.partName(index),
                        githubPath = committed,
                        sizeBytes = partFile.length(),
                        sha256 = partSha,
                        files = emptyList(),
                    )
                    partFile.delete()
                }
            }
            val manifest = CourierManifest.buildJson(
                mode = CourierManifest.MODE_FILE_RAW_SPLIT,
                volumeFormat = CourierManifest.VOLUME_FORMAT_RAW_PART,
                sourcePath = source,
                targetPrefix = "$partDir/",
                volumes = uploaded,
                truncated = false,
                finishedAtEpochMs = System.currentTimeMillis(),
                originalSha256 = sha256Hex(fileSha.digest()),
                originalFileName = fileName,
                partBytes = partBytes,
            )
            val manifestGithubPath = uploader.uploadManifest(
                safeRepo, branch, "$partDir/${CourierManifest.MANIFEST_NAME}", manifest,
                "Courier: manifest $stamp",
            )
            return buildJsonObject {
                put("ok", true)
                put("source_path", source)
                put("source_strategy", statStrategy)
                put("repo", safeRepo)
                put("branch", branch)
                put("mode", CourierManifest.MODE_FILE_RAW_SPLIT)
                put("manifest_github_path", manifestGithubPath)
                put("uploaded", uploadedJson(uploaded, source))
                put("total_bytes", uploaded.sumOf { it.sizeBytes })
                put("volumes", uploaded.size)
                putJsonArray("errors") {}
            }.toString()
        } finally {
            taskDir.deleteRecursively()
        }
    }

    // ── upload_phone_dir ─────────────────────────────────────

    private suspend fun executeUploadDir(
        access: CourierFileAccess,
        rawPath: String,
        repo: String,
        rawTargetPrefix: String,
        maxVolumeMb: Int,
        maxTotalMb: Int,
        maxFiles: Int,
    ): String {
        val root = normalizeInputPath(rawPath).trimEnd('/')
        val safeRepo = client.validateRepo(repo)
        val branch = Constants.COURIER_DEFAULT_BRANCH
        require(maxVolumeMb > 0 && maxTotalMb > 0) { "max_volume_mb/max_total_mb must be positive" }
        val limits = CourierLimits(
            maxVolumeBytes = maxVolumeMb.toLong() * 1_000_000L,
            maxTotalBytes = maxTotalMb.toLong() * 1_000_000L,
            maxFiles = maxFiles,
        )
        val stamp = newStamp()
        val prefix = rawTargetPrefix.trim().trimStart('/').ifBlank { "courier/$stamp/" }.let {
            if (it.endsWith('/')) it else "$it/"
        }
        requireValidRepoPath(prefix)

        // 1) 递归收集（尊重 max_files / max_total_mb，超限标记 truncated）
        val collected = collectDir(access, root, limits)
        // 2) 目录模式按文件边界分卷、不劈开单文件；超过 Blob 上限的单文件只能记 error
        val deliverable = mutableListOf<PlannedFile>()
        val errors = mutableListOf<CourierError>()
        collected.files.forEach { file ->
            if (file.sizeBytes > Constants.COURIER_MAX_SINGLE_BLOB_MB * 1_000_000L) {
                errors += CourierError(
                    file.absolutePath,
                    "单文件 ${(file.sizeBytes / 1_000_000)}MB 超过目录模式单卷上限（不劈开单文件）；请用 upload_phone_file 切片投递",
                )
            } else {
                deliverable += file
            }
        }

        val taskDir = File(appContext.cacheDir, "courier/dir-$stamp").also { it.mkdirs() }
        try {
            // 3) 计划分卷并逐卷打包上传（卷失败重试 2 次后放弃并记 errors）
            val plans = CourierVolumePlanner.plan(deliverable, limits)
            val uploaded = mutableListOf<UploadedVolume>()
            val strategies = mutableListOf<String>()
            for (plan in plans) {
                val partFile = File(taskDir, CourierManifest.partName(plan.partIndex))
                try {
                    withContext(Dispatchers.IO) {
                        // openWithStrategy is suspend; writeZipVolume's open callback is sync.
                        // Blocking the IO thread for the duration of a stream open is intended here.
                        CourierVolumeWriter.writeZipVolume(partFile, plan.files) { path ->
                            kotlinx.coroutines.runBlocking { openWithStrategy(access, path, strategies) }
                        }
                    }
                    val partSha = withContext(Dispatchers.IO) { sha256File(partFile) }
                    val committed = uploader.uploadVolume(
                        safeRepo, branch,
                        prefix + CourierManifest.partName(plan.partIndex),
                        partFile,
                        "Courier: dir $stamp part ${plan.partIndex}",
                    )
                    uploaded += UploadedVolume(
                        zipName = CourierManifest.partName(plan.partIndex),
                        githubPath = committed,
                        sizeBytes = partFile.length(),
                        sha256 = partSha,
                        files = plan.files.map { it.relativePath },
                        singleOversize = plan.singleOversize,
                    )
                    partFile.delete()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += CourierError(
                        plan.files.firstOrNull()?.absolutePath ?: "$root(卷 ${plan.partIndex})",
                        "卷 ${plan.partIndex} 打包/上传失败：${e.message}",
                    )
                }
            }

            // 4) manifest（部分失败也写，云端按 uploaded/errors 处理）
            var manifestPath: String? = null
            if (uploaded.isNotEmpty()) {
                val manifest = CourierManifest.buildJson(
                    mode = CourierManifest.MODE_DIR_ZIP,
                    volumeFormat = CourierManifest.VOLUME_FORMAT_ZIP_ARCHIVE,
                    sourcePath = root,
                    targetPrefix = prefix,
                    volumes = uploaded,
                    truncated = collected.truncated,
                    finishedAtEpochMs = System.currentTimeMillis(),
                    catalogSha256 = catalogSha256(deliverable),
                )
                manifestPath = runCatching {
                    uploader.uploadManifest(safeRepo, branch, prefix + CourierManifest.MANIFEST_NAME, manifest, "Courier: manifest $stamp")
                }.getOrElse {
                    errors += CourierError(prefix + CourierManifest.MANIFEST_NAME, "manifest 上传失败：${it.message}")
                    null
                }
            }

            return buildJsonObject {
                put("ok", errors.isEmpty())
                put("source_path", root)
                put("repo", safeRepo)
                put("branch", branch)
                put("target_prefix", prefix)
                manifestPath?.let { put("manifest_github_path", it) }
                put("uploaded", uploadedJson(uploaded, null))
                put("total_bytes", collected.totalBytes)
                put("volumes", uploaded.size)
                put("file_count", collected.files.size)
                put("truncated", collected.truncated)
                putJsonArray("errors") {
                    errors.forEach { error ->
                        add(buildJsonObject {
                            put("path", error.path)
                            put("error", error.error.take(MAX_ERROR_LENGTH))
                        })
                    }
                }
                put("finished_at_epoch_ms", System.currentTimeMillis())
            }.toString()
        } finally {
            taskDir.deleteRecursively()
        }
    }

    // ── helpers ──────────────────────────────────────────────

    private suspend fun openWithStrategy(
        access: CourierFileAccess,
        path: String,
        strategies: MutableList<String>,
    ): InputStream {
        val (strategy, stream) = access.openFile(path)
        strategies += "$path=$strategy"
        return stream
    }

    /** BFS over listDir with hard caps; records truncation instead of failing silently. */
    private suspend fun collectDir(access: CourierFileAccess, root: String, limits: CourierLimits): CollectedTree {
        val files = mutableListOf<PlannedFile>()
        val queue = ArrayDeque<String>()
        queue += root
        var totalBytes = 0L
        var truncated = false
        while (queue.isNotEmpty()) {
            if (files.size >= limits.maxFiles || totalBytes >= limits.maxTotalBytes) {
                truncated = true
                break
            }
            val dir = queue.removeFirst()
            val (_, entries) = access.listDir(dir)
            for (entry in entries) {
                val child = "$dir/${entry.name}"
                if (entry.isDirectory) {
                    queue += child
                    continue
                }
                if (files.size >= limits.maxFiles || totalBytes + entry.sizeBytes > limits.maxTotalBytes) {
                    truncated = true
                    break
                }
                files += PlannedFile(
                    absolutePath = child,
                    relativePath = SafPathMapper.relativeUnder(child, root),
                    sizeBytes = entry.sizeBytes,
                )
                totalBytes += entry.sizeBytes
            }
        }
        return CollectedTree(files, truncated, totalBytes)
    }

    private data class CollectedTree(
        val files: List<PlannedFile>,
        val truncated: Boolean,
        val totalBytes: Long,
    )

    /** uploaded:[{source_path, zip_name, github_path, size_bytes, sha256}] — 云端按此拼回数据。 */
    private fun uploadedJson(uploaded: List<UploadedVolume>, singleSource: String?): JsonArray =
        JsonArray(uploaded.map { volume ->
            buildJsonObject {
                singleSource?.let { put("source_path", it) }
                put("zip_name", volume.zipName)
                put("github_path", volume.githubPath)
                put("size_bytes", volume.sizeBytes)
                put("sha256", volume.sha256)
                if (volume.files.isNotEmpty()) {
                    put("source_paths", JsonArray(volume.files.map { JsonPrimitive(it) }))
                }
            }
        })

    /** Catalog hash: SHA-256 over "relativePath\nsizeBytes\n" lines (file-set consistency check). */
    private fun catalogSha256(files: List<PlannedFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.relativePath }.forEach { file ->
            digest.update(file.relativePath.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
            digest.update(file.sizeBytes.toString().toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return sha256Hex(digest.digest())
    }

    private fun newPartFile(taskDir: File, index: Int) = File(taskDir, CourierManifest.partName(index))

    private fun newStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun normalizeInputPath(raw: String): String {
        val path = raw.trim()
        require(path.isNotBlank()) { "path 不能为空" }
        SafPathMapper.requireNormalized(path)
        return path.trimEnd('/')
    }

    private fun requireValidRepoPath(path: String) {
        require(path.isNotBlank()) { "目标路径不能为空" }
        require(!path.split('/').any { it == "." || it == ".." }) { "非法目标路径：$path" }
        require(!path.contains("//")) { "非法目标路径：$path" }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256File(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun errorJson(message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", message.take(500))
            put("hint", "若是 Android/data 目录：请先在 设置 → GitHub → 文件投递 里授权该目录（SAF），并确认 GitHub 已登录")
        }.toString()

    private companion object {
        const val LIST_DIR = "list_phone_dir"
        const val UPLOAD_FILE = "upload_phone_file"
        const val UPLOAD_DIR = "upload_phone_dir"
        val NAMES = setOf(LIST_DIR, UPLOAD_FILE, UPLOAD_DIR)
        const val BUFFER_BYTES = 256 * 1024
        const val MAX_ERROR_LENGTH = 400
    }
}
