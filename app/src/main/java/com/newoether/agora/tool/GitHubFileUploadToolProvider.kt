package com.newoether.agora.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.github.GitHubBinaryUploader
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * github_upload_file：把本机文件（聊天附件/沙盒产物）提交为用户 GitHub 仓库的真实 Git 文件。
 * 图片自动压缩（长边≤2048、JPEG 质量自适应），体积≤900KB；分支强制 workbench/*。
 */
class GitHubFileUploadToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val uploader = GitHubBinaryUploader(client)
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = UPLOAD_FILE,
                description = "Upload a local file from this device into the user's GitHub repository as a real Git commit on a workbench/* branch. Use the absolute local path of a chat attachment or workspace artifact. Images are auto-compressed to fit 900 KB. A missing workbench branch is created from the default branch automatically.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Repository in owner/name form."),
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
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }

        fun text(key: String): String = (args[key] as? JsonPrimitive)?.content.orEmpty()
        val repo = text("repo").trim(); val branch = text("branch").trim()
        val path = text("path").trim().removePrefix("/"); val localPath = text("local_path").trim()
        val message = text("message")

        if (repo.isBlank() || branch.isBlank() || path.isBlank() || localPath.isBlank()) {
            return errorJson("repo, branch, path and local_path are required")
        }

        val source = File(localPath)
        if (!source.isFile) return errorJson("Local file not found: $localPath")
        val raw = runCatching { source.readBytes() }.getOrElse { return errorJson("Cannot read local file: ${it.message}") }
        if (raw.isEmpty()) return errorJson("Local file is empty")

        val name = source.name.lowercase()
        val payload: ByteArray = if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
            compress(raw) ?: return errorJson("Image could not be decoded")
        } else {
            if (raw.size > 900_000) return errorJson("File too large (${raw.size / 1000} KB, max 900 KB)")
            raw
        }

        return try {
            ensureBranch(repo, branch)
            val commitSha = uploader.upload(repo, path, branch, message.ifBlank { "Upload $path (Agora)" }, payload)
            buildJsonObject {
                put("ok", true); put("repo", repo); put("branch", branch); put("path", path)
                put("commit", commitSha); put("uploaded_kb", payload.size / 1000)
                put("note", "Committed to $branch. Trigger the repo build workflow next if the repo uses one.")
            }.toString()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { errorJson(e.message ?: "Upload failed") }
    }

    private suspend fun ensureBranch(repo: String, branch: String) {
        require(branch.startsWith("workbench/")) { "Branch must start with workbench/" }
        val defaultBranch = runCatching {
            client.repository(repo)["default_branch"]?.jsonPrimitive?.content ?: "main"
        }.getOrDefault("main")
        runCatching { client.createBranch(repo, branch, defaultBranch) }
    }

    private fun compress(bytes: ByteArray): ByteArray? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val longest = maxOf(src.width, src.height)
        val scaled = if (longest > 2048) {
            val ratio = 2048f / longest
            Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
        } else src
        var quality = 88
        var out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        while (out.size() > 900_000 && quality > 40) {
            quality -= 12
            out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        }
        return out.toByteArray()
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(400)) }.toString()

    private companion object { const val UPLOAD_FILE = "github_upload_file" }
}
