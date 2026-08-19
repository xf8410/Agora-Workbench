package com.newoether.agora.uma

import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.github.GitHubApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One complete session file already uploaded through the Git Blob API. */
data class UmaGitTreeBlob(
    val relativePath: String,
    val blobSha: String,
)

data class UmaGitTreeResult(
    val treeSha: String,
    val entryCount: Int,
)

internal fun joinUmaGitTreePath(directory: String, relativePath: String): String {
    val safeRelativePath = validateUmaArchivePath(relativePath)
    if (directory.isBlank()) return safeRelativePath
    require('\\' !in directory) { "target directory must use forward slashes" }
    require(!directory.startsWith('/')) { "target directory must not be absolute" }
    val parts = directory.trimEnd('/').split('/')
    require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
        "target directory contains an unsafe segment"
    }
    return parts.joinToString("/") + "/" + safeRelativePath
}

internal fun buildUmaGitTreeBody(
    baseTreeSha: String,
    directory: String,
    blobs: List<UmaGitTreeBlob>,
): JsonObject {
    require(GIT_OBJECT_SHA_PATTERN.matches(baseTreeSha)) { "invalid base tree SHA" }
    require(blobs.isNotEmpty()) { "tree requires at least one blob" }
    val paths = mutableSetOf<String>()
    return buildJsonObject {
        put("base_tree", baseTreeSha.lowercase())
        putJsonArray("tree") {
            blobs.forEach { blob ->
                require(GIT_OBJECT_SHA_PATTERN.matches(blob.blobSha)) { "invalid blob SHA" }
                val path = joinUmaGitTreePath(directory, blob.relativePath)
                require(paths.add(path)) { "duplicate Git tree path $path" }
                add(buildJsonObject {
                    put("path", path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", blob.blobSha.lowercase())
                })
            }
        }
    }
}

internal fun parseUmaGitTreeSha(body: String, json: Json = Json): String {
    val sha = json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content.orEmpty()
    require(GIT_OBJECT_SHA_PATTERN.matches(sha)) { "GitHub tree response did not contain a valid SHA" }
    return sha.lowercase()
}

/** Creates Git trees that overlay uploaded session blobs on an existing repository tree. */
class UmaGitTreeClient(
    private val client: GitHubApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun create(
        repo: String,
        baseTreeSha: String,
        directory: String,
        blobs: List<UmaGitTreeBlob>,
    ): UmaGitTreeResult = withContext(Dispatchers.IO) {
        val safeRepo = client.validateRepo(repo)
        val body = buildUmaGitTreeBody(baseTreeSha, directory, blobs)
        val response = requestWithRetry(safeRepo, body)
        if (response.code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub tree creation failed"
            error("$message (HTTP ${response.code})")
        }
        UmaGitTreeResult(
            treeSha = parseUmaGitTreeSha(response.body, json),
            entryCount = blobs.size,
        )
    }

    /**
     * Overlays a large path set through cumulative, bounded trees. Only the final tree is committed,
     * avoiding oversized GitHub requests while retaining one atomic repository commit.
     */
    suspend fun createBatched(
        repo: String,
        baseTreeSha: String,
        directory: String,
        blobs: List<UmaGitTreeBlob>,
        batchSize: Int = DEFAULT_TREE_BATCH_SIZE,
    ): UmaGitTreeResult {
        require(blobs.isNotEmpty()) { "tree requires at least one blob" }
        require(batchSize in 1..MAX_TREE_BATCH_SIZE) { "invalid Git tree batch size" }
        require(blobs.map { joinUmaGitTreePath(directory, it.relativePath) }.toSet().size == blobs.size) {
            "duplicate Git tree path"
        }
        var currentTreeSha = baseTreeSha
        var completed = 0
        blobs.chunked(batchSize).forEach { batch ->
            val result = create(repo, currentTreeSha, directory, batch)
            currentTreeSha = result.treeSha
            completed += result.entryCount
        }
        return UmaGitTreeResult(currentTreeSha, completed)
    }

    private suspend fun requestWithRetry(repo: String, body: JsonObject): GitHubApiResponse {
        var response: GitHubApiResponse? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            response = client.request(
                method = "POST",
                path = "/repos/$repo/git/trees",
                body = body,
            )
            if (response!!.code !in RETRYABLE_CODES || attempt == MAX_ATTEMPTS - 1) {
                return response!!
            }
            delay(INITIAL_RETRY_DELAY_MS * (1L shl attempt))
        }
        return requireNotNull(response)
    }

    companion object {
        const val DEFAULT_TREE_BATCH_SIZE = 200
        const val MAX_TREE_BATCH_SIZE = 500
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)
    }
}

private val GIT_OBJECT_SHA_PATTERN = Regex("[0-9a-fA-F]{40}")
