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

data class GitHubApiResponse(val code: Int, val body: String, val linkHeader: String? = null)

/** Controlled GitHub REST client. Public GETs may be anonymous; mutations always require auth. */
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
        require(path.startsWith('/')) { "GitHub API path must start with /" }
        val session = auth.loadSession()
        if (requireAuth && session == null) error("GitHub is not signed in")
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Agora-Workbench")
            session?.let { connection.setRequestProperty("Authorization", "Bearer ${it.token}") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val linkHeader = connection.getHeaderField("Link")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { reader ->
                val out = StringBuilder(minOf(MAX_API_RESPONSE_CHARS, 8192))
                val buffer = CharArray(8192)
                while (out.length < MAX_API_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_API_RESPONSE_CHARS - out.length))
                    if (count < 0) break
                    out.append(buffer, 0, count)
                }
                out.toString()
            }.orEmpty()
            GitHubApiResponse(code, text, linkHeader)
        } finally { connection.disconnect() }
    }

    suspend fun publicRequest(method: String, path: String): GitHubApiResponse =
        request(method, path, requireAuth = false)

    suspend fun repository(repo: String): JsonObject {
        val safeRepo = validateRepo(repo)
        val response = publicRequest("GET", "/repos/$safeRepo")
        requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonObject
    }

    suspend fun createBranch(repo: String, branch: String, base: String): String {
        val safeRepo = validateRepo(repo)
        require(branch.startsWith("workbench/") && branch.length in 11..200 && !branch.contains("..")) { "Writes require a valid workbench/* branch" }
        val ref = request("GET", "/repos/$safeRepo/git/ref/heads/${encodeSegment(base)}")
        requireSuccess(ref)
        val sha = json.parseToJsonElement(ref.body).jsonObject.getValue("object").jsonObject.getValue("sha").jsonPrimitive.content
        val created = request("POST", "/repos/$safeRepo/git/refs", buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", sha) })
        if (created.code == 422 && created.body.contains("Reference already exists")) return branch
        requireSuccess(created)
        return branch
    }

    /** Contents API returns an object for a file and an array for a directory. */
    suspend fun readContent(repo: String, path: String, ref: String): JsonElement {
        val safeRepo = validateRepo(repo)
        val effectiveRef = ref.ifBlank { repository(safeRepo)["default_branch"]?.jsonPrimitive?.content ?: "main" }
        val response = publicRequest("GET", "/repos/$safeRepo/contents/${encodePath(path)}?ref=${encodeSegment(effectiveRef)}")
        requireSuccess(response)
        return json.parseToJsonElement(response.body)
    }

    suspend fun readFile(repo: String, path: String, ref: String): JsonObject =
        readContent(repo, path, ref) as? JsonObject ?: error("Expected a GitHub file response, but path is a directory")

    suspend fun writeFile(repo: String, path: String, branch: String, message: String, content: String): String =
        writeBinaryFile(repo, path, branch, message, content.toByteArray(Charsets.UTF_8))

    /**
     * Binary upload (images, assets, bundles) through the same Contents API.
     * Shares the workbench/* guard with writeFile and is size-capped below
     * GitHub's 1 MB contents limit so client-side downscaled JPEGs always pass.
     */
    suspend fun writeBinaryFile(repo: String, path: String, branch: String, message: String, bytes: ByteArray): String {
        val safeRepo = validateRepo(repo)
        require(branch.startsWith("workbench/") && branch.length in 11..200 && !branch.contains("..")) { "Writes require a valid workbench/* branch" }
        require(path.isNotBlank() && !path.split('/').contains("..")) { "Invalid file path" }
        require(bytes.isNotEmpty()) { "Nothing to upload" }
        require(bytes.size <= MAX_BINARY_BYTES) { "File is too large to upload (${bytes.size / 1000} KB raw, max ${MAX_BINARY_BYTES / 1000} KB)" }
        val encodedPath = encodePath(path)
        val existing = request("GET", "/repos/$safeRepo/contents/$encodedPath?ref=${encodeSegment(branch)}")
        val sha = if (existing.code in 200..299) (json.parseToJsonElement(existing.body) as? JsonObject)?.get("sha")?.jsonPrimitive?.content else null
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val response = request("PUT", "/repos/$safeRepo/contents/$encodedPath", buildJsonObject {
            put("message", message.ifBlank { "Upload $path" }); put("content", encoded); put("branch", branch); if (sha != null) put("sha", sha)
        })
        requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonObject.getValue("commit").jsonObject.getValue("sha").jsonPrimitive.content
    }

    /** Triggers a repository workflow_dispatch by workflow file name (e.g. build.yml). */
    suspend fun dispatchWorkflowFile(repo: String, workflowFile: String, ref: String) {
        val safeRepo = validateRepo(repo)
        require(workflowFile.isNotBlank() && !workflowFile.contains("..")) { "Invalid workflow file" }
        require(ref.isNotBlank() && !ref.contains("..")) { "Invalid dispatch ref" }
        val response = request("POST", "/repos/$safeRepo/actions/workflows/${encodeSegment(workflowFile)}/dispatches", buildJsonObject { put("ref", ref) })
        requireSuccess(response)
    }

    fun validateRepo(value: String): String {
        val repo = value.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
        require(REPO_PATTERN.matches(repo)) { "Repository must be in owner/name form" }
        return repo
    }

    private fun requireSuccess(response: GitHubApiResponse) {
        if (response.code !in 200..299) {
            val message = runCatching { json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() ?: "GitHub API error"
            val hint = if (response.code == 404 && !isSignedIn()) " (public repository not found; sign in for private repositories)" else ""
            error("$message (HTTP ${response.code})$hint")
        }
    }

    fun encodeSegment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun encodePath(value: String) = value.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    private companion object {
        val REPO_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
        const val MAX_API_RESPONSE_CHARS = 2_000_000
        const val MAX_WRITE_BYTES = 750_000
        const val MAX_BINARY_BYTES = 900_000
    }
}
