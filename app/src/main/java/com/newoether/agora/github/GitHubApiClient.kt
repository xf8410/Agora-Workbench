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

/**
 * Small, controlled GitHub REST client. The token is read internally and never returned.
 * Normal calls require login; [readContent] alone permits anonymous public-repository reads.
 */
class GitHubApiClient(context: Context) {
    private val auth = GitHubAuthManager(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    fun isSignedIn(): Boolean = auth.loadSession() != null

    suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        requireAuth: Boolean = true,
    ): GitHubApiResponse = withContext(Dispatchers.IO) {
        val session = auth.loadSession()
        if (requireAuth && session == null) error("GitHub is not signed in")
        require(path.startsWith('/')) { "Invalid GitHub API path" }

        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Agora-Workbench")
            // Attach the saved token when present, including public reads, for a higher rate limit.
            session?.let { connection.setRequestProperty("Authorization", "Bearer ${it.token}") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            GitHubApiResponse(code, stream?.bufferedReader()?.use { it.readTextLimited(MAX_RESPONSE_CHARS) }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    suspend fun createBranch(repo: String, branch: String, base: String): String {
        val safeRepo = normalizeRepo(repo)
        val ref = request("GET", "/repos/$safeRepo/git/ref/heads/${encodeSegment(base)}")
        requireSuccess(ref)
        val sha = json.parseToJsonElement(ref.body).jsonObject
            .getValue("object").jsonObject.getValue("sha").jsonPrimitive.content
        val created = request("POST", "/repos/$safeRepo/git/refs", buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", sha)
        })
        if (created.code == 422 && created.body.contains("Reference already exists")) return branch
        requireSuccess(created)
        return branch
    }

    /**
     * The one anonymous-capable operation. GitHub returns 404 for a private repository when the
     * current account cannot access it, so anonymous callers only obtain public content.
     */
    suspend fun readContent(repo: String, path: String, ref: String): JsonElement {
        val safeRepo = normalizeRepo(repo)
        val effectiveRef = if (ref.isNotBlank()) ref else resolveDefaultBranch(safeRepo)
        val response = request(
            method = "GET",
            path = "/repos/$safeRepo/contents/${encodePath(path)}?ref=${encodeSegment(effectiveRef)}",
            requireAuth = false,
        )
        requireSuccess(response, publicRead = true)
        return json.parseToJsonElement(response.body)
    }

    /** Compatibility helper for callers that explicitly require a single file. */
    suspend fun readFile(repo: String, path: String, ref: String): JsonObject =
        readContent(repo, path, ref) as? JsonObject
            ?: error("Expected a GitHub file response, but path is a directory")

    suspend fun writeFile(repo: String, path: String, branch: String, message: String, content: String): String {
        val safeRepo = normalizeRepo(repo)
        require(path.isNotBlank()) { "File path is empty" }
        val existing = request("GET", "/repos/$safeRepo/contents/${encodePath(path)}?ref=${encodeSegment(branch)}")
        val sha = if (existing.code in 200..299) {
            (json.parseToJsonElement(existing.body) as? JsonObject)?.get("sha")?.jsonPrimitive?.content
        } else null
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = buildJsonObject {
            put("message", message)
            put("content", encoded)
            put("branch", branch)
            if (sha != null) put("sha", sha)
        }
        val response = request("PUT", "/repos/$safeRepo/contents/${encodePath(path)}", body)
        requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonObject
            .getValue("commit").jsonObject.getValue("sha").jsonPrimitive.content
    }

    private suspend fun resolveDefaultBranch(repo: String): String {
        val response = request("GET", "/repos/$repo", requireAuth = false)
        requireSuccess(response, publicRead = true)
        return json.parseToJsonElement(response.body).jsonObject["default_branch"]
            ?.jsonPrimitive?.content ?: "main"
    }

    private fun normalizeRepo(value: String): String {
        val repo = value.trim()
            .removePrefix("https://github.com/")
            .removeSuffix("/")
            .removeSuffix(".git")
        require(REPO_PATTERN.matches(repo)) { "Repository must be in owner/name form" }
        return repo
    }

    private fun requireSuccess(response: GitHubApiResponse, publicRead: Boolean = false) {
        if (response.code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub API error"
            val hint = if (publicRead && response.code == 404 && !isSignedIn()) {
                "; repository is missing or private — sign in to read an accessible private repository"
            } else ""
            error("$message (HTTP ${response.code})$hint")
        }
    }

    private fun java.io.BufferedReader.readTextLimited(limit: Int): String {
        val result = StringBuilder(minOf(limit, 8192))
        val buffer = CharArray(8192)
        while (result.length < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - result.length))
            if (count < 0) break
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(value: String): String =
        value.trim('/').split('/').filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }

    private companion object {
        val REPO_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
