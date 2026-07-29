package com.newoether.agora.github

import android.content.Context
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class GitHubApiResponse(val code: Int, val body: String)

/** Small, controlled GitHub REST client. The token is read internally and never returned. */
class GitHubApiClient(context: Context) {
    private val auth = GitHubAuthManager(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    fun isSignedIn(): Boolean = auth.loadSession() != null

    suspend fun request(method: String, path: String, body: JsonElement? = null): GitHubApiResponse = withContext(Dispatchers.IO) {
        val session = auth.loadSession() ?: error("GitHub is not signed in")
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Agora-Workbench")
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            GitHubApiResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally { connection.disconnect() }
    }

    suspend fun createBranch(repo: String, branch: String, base: String): String {
        val ref = request("GET", "/repos/$repo/git/ref/heads/${encodeSegment(base)}")
        requireSuccess(ref)
        val sha = json.parseToJsonElement(ref.body).jsonObject.getValue("object").jsonObject.getValue("sha").jsonPrimitive.content
        val created = request("POST", "/repos/$repo/git/refs", buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", sha) })
        if (created.code == 422 && created.body.contains("Reference already exists")) return branch
        requireSuccess(created)
        return branch
    }

    /** GitHub Contents API returns JsonObject for a file and JsonArray for a directory. */
    suspend fun readContent(repo: String, path: String, ref: String): JsonElement {
        val response = request("GET", "/repos/$repo/contents/${encodePath(path)}?ref=${encodeSegment(ref)}")
        requireSuccess(response)
        return json.parseToJsonElement(response.body)
    }

    /** Compatibility helper for callers that explicitly require a single file. */
    suspend fun readFile(repo: String, path: String, ref: String): JsonObject =
        readContent(repo, path, ref) as? JsonObject ?: error("Expected a GitHub file response, but path is a directory")

    suspend fun writeFile(repo: String, path: String, branch: String, message: String, content: String): String {
        val existing = request("GET", "/repos/$repo/contents/${encodePath(path)}?ref=${encodeSegment(branch)}")
        val sha = if (existing.code in 200..299) (json.parseToJsonElement(existing.body) as? JsonObject)?.get("sha")?.jsonPrimitive?.content else null
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = buildJsonObject { put("message", message); put("content", encoded); put("branch", branch); if (sha != null) put("sha", sha) }
        val response = request("PUT", "/repos/$repo/contents/${encodePath(path)}", body)
        requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonObject.getValue("commit").jsonObject.getValue("sha").jsonPrimitive.content
    }

    private fun requireSuccess(response: GitHubApiResponse) {
        if (response.code !in 200..299) {
            val message = runCatching { json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() ?: "GitHub API error"
            error("$message (HTTP ${response.code})")
        }
    }

    private fun encodeSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun encodePath(value: String): String = value.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
}
