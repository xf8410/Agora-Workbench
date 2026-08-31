package com.newoether.agora.github

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 独立的二进制文件上传通道（图片/资产打包文件等）。
 *
 * 与 GitHubApiClient 的 writeBinaryFile 等价，但作为独立小文件维护，
 * 避免修改核心客户端带来的回归风险。安全边界与主客户端一致：
 * - 复用 GitHubAuthManager 的加密凭据，未登录直接失败
 * - 分支强制 workbench/* 前缀（workbench 二进制守卫）
 * - 单文件 ≤900KB（GitHub Contents API 实际上限 1MB 的安全水位）
 */
class GitHubBinaryUploader(private val client: GitHubApiClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upload(
        repo: String,
        path: String,
        branch: String,
        message: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val safeRepo = client.validateRepo(repo)
        require(branch.startsWith("workbench/") && branch.length in 11..200 && !branch.contains("..")) { "Writes require a valid workbench/* branch" }
        require(path.isNotBlank() && !path.split('/').contains("..")) { "Invalid file path" }
        require(bytes.isNotEmpty()) { "Nothing to upload" }
        require(bytes.size <= MAX_BINARY_BYTES) { "File too large (${bytes.size / 1000} KB, max ${MAX_BINARY_BYTES / 1000} KB)" }

        val encodedPath = encodePath(path)
        val existing = client.request("GET", "/repos/$safeRepo/contents/$encodedPath?ref=${client.encodeSegment(branch)}")
        val sha = if (existing.code in 200..299) {
            runCatching {
                json.parseToJsonElement(existing.body).jsonObject["sha"]?.jsonPrimitive?.content
            }.getOrNull()
        } else null

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = buildJsonObject {
            put("message", message.ifBlank { "Upload $path" })
            put("content", encoded)
            put("branch", branch)
            if (sha != null) put("sha", sha)
        }
        val response = client.request("PUT", "/repos/$safeRepo/contents/$encodedPath", body)
        require(response.code in 200..299) { "Upload failed (HTTP ${response.code}): ${response.body.take(300)}" }
        json.parseToJsonElement(response.body).jsonObject.getValue("commit").jsonObject.getValue("sha").jsonPrimitive.content
    }

    private fun encodePath(value: String): String =
        value.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { client.encodeSegment(it) }

    private companion object {
        const val MAX_BINARY_BYTES = 900_000
    }
}
