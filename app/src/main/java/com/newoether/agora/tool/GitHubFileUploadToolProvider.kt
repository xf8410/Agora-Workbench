package com.newoether.agora.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * App 内二进制文件直传 GitHub 仓库（真实 Git 文件提交）：
 * AI 依据用户指令调用，把本机文件（聊天附件的本地路径、/workspace 沙盒产物等）
 * 上传到用户仓库的 workbench/* 分支——例如把足球游戏启动海报提交到
 * football-game-kotlin 的 app/src/main/assets/。
 *
 * 安全边界（与 GitHubApiClient 守卫一致）：
 * - 必须已 GitHub 登录（复用 GitHubAuthManager 的加密凭据，无明文入库）
 * - 分支强制 workbench/* 前缀；缺失分支按仓库默认分支自动创建
 * - 体积 ≤900KB；图片自动压缩（最长边 ≤2048px，JPEG 质量自适应）
 * - 仅提交目标路径一个文件，不触碰仓库其他内容
 */
class GitHubFileUploadToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = UPLOAD_FILE,
                description = (
                    "Upload a local binary file (image poster, asset, bundle) from this device into the user's GitHub " +
                        "repository as a real Git commit. Only workbench/* target branches are allowed; a missing branch is " +
                        "created from the repository default branch. Use the absolute local file path of a chat attachment or " +
                        "workspace artifact. Images are auto-compressed (max side 2048px, JPEG) to fit the 900 KB limit."
                    ),
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Repository in owner/name form, e.g. xf8410/football-game-kotlin."),
                        "branch" to ToolProperty("string", "Target branch; must start with workbench/."),
                        "path" to ToolProperty("string", "Repository-relative destination path, e.g. app/src/main/assets/splash.jpg."),
                        "local_path" to ToolProperty("string", "Absolute local file path of the file to upload."),
                        "message" to ToolProperty("string", "Optional commit message."),
                    ),
                    required = listOf("repo", "branch", "path", "local_path"),
                ),
            ),
        )
    )

    override fun handles(name: String): Boolean = name == UPLOAD_FILE

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != UPLOAD_FILE) return errorJson("Unknown upload tool")
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }

        fun text(key: String): String = (args[key] as? JsonPrimitive)?.content.orEmpty()

        val repo = text("repo").trim()
        val branch = text("branch").trim()
        val path = text("path").trim().removePrefix("/")
        val localPath = text("local_path").trim()
        val message = text("message")

        if (repo.isBlank() || branch.isBlank() || path.isBlank() || localPath.isBlank()) {
            return errorJson("repo, branch, path and local_path are required")
        }

        val source = File(localPath)
        if (!source.isFile) return errorJson("Local file not found: $localPath")
        val raw = runCatching { source.readBytes() }.getOrElse { return errorJson("Cannot read local file: ${it.message}") }
        if (raw.isEmpty()) return errorJson("Local file is empty")

        val fileName = source.name.lowercase()
        val payload: ByteArray = if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
            fileName.endsWith(".png") || fileName.endsWith(".webp")
        ) {
            compressForUpload(raw)
                ?: return errorJson("Image could not be decoded; attach a valid JPG/PNG/WEBP or upload a non-image file")
        } else {
            if (raw.size > MAX_UPLOAD_BYTES) {
                return errorJson("File too large (${raw.size / 1000} KB, max ${MAX_UPLOAD_BYTES / 1000} KB)")
            }
            raw
        }

        return try {
            ensureBranch(repo, branch)
            val commitSha = client.writeBinaryFile(
                repo = repo,
                path = path,
                branch = branch,
                message = message.ifBlank { "Upload $path (Agora file upload)" },
                bytes = payload,
            )
            buildJsonObject {
                put("ok", true)
                put("repo", repo)
                put("branch", branch)
                put("path", path)
                put("commit", commitSha)
                put("uploaded_kb", payload.size / 1000)
                put("note", "Committed to $branch. Trigger the repo build workflow next if the repo uses one.")
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "Upload failed")
        }
    }

    /** workbench/* 分支不存在时按仓库默认分支创建 */
    private suspend fun ensureBranch(repo: String, branch: String) {
        require(branch.startsWith("workbench/")) { "Branch must start with workbench/" }
        val defaultBranch = runCatching {
            client.repository(repo)["default_branch"]?.jsonPrimitive?.content ?: "main"
        }.getOrDefault("main")
        runCatching { client.createBranch(repo, branch, defaultBranch) }
    }

    /** 客户端压缩：最长边 ≤2048，JPEG 质量自适应降到 ≤900KB */
    private fun compressForUpload(bytes: ByteArray): ByteArray? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val maxSide = 2048
        val longest = maxOf(src.width, src.height)
        val scaled = if (longest > maxSide) {
            val ratio = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        var quality = 88
        var out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        while (out.size() > MAX_UPLOAD_BYTES && quality > 40) {
            quality -= 12
            out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        }
        return out.toByteArray()
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(400)) }.toString()

    private companion object {
        const val UPLOAD_FILE = "github_upload_file"
        const val MAX_UPLOAD_BYTES = 900_000
    }
}
