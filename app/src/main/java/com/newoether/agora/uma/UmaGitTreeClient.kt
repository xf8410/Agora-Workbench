package com.newoether.agora.uma

import com.newoether.agora.github.GitHubApiClient
import kotlinx.coroutines.Dispatchers
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
    validateUmaGitDirectory(directory)
    return directory.trimEnd('/') + "/" + safeRelativePath
}

private fun validateUmaGitDirectory(directory: String): String {
    require(directory.isNotBlank()) { "target directory must not be blank" }
    require('\\' !in directory) { "target directory must use forward slashes" }
    require(!directory.startsWith('/')) { "target directory must not be absolute" }
    val normalized = directory.trimEnd('/')
    val parts = normalized.split('/')
    require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
        "target directory contains an unsafe segment"
    }
    return normalized
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

private data class UmaTreeNode(
    val blobs: MutableMap<String, String> = linkedMapOf(),
    val children: MutableMap<String, UmaTreeNode> = linkedMapOf(),
)

/** Creates Git trees for one directory at a time, avoiding one oversized flat Tree request. */
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
        UmaGitTreeResult(
            treeSha = postTree(safeRepo, body),
            entryCount = blobs.size,
        )
    }

    /**
     * Builds every archive directory as its own Git Tree, then overlays a single subtree entry on
     * the repository base tree. File paths and Blob bytes remain unchanged.
     */
    suspend fun createHierarchical(
        repo: String,
        baseTreeSha: String,
        directory: String,
        blobs: List<UmaGitTreeBlob>,
    ): UmaGitTreeResult = withContext(Dispatchers.IO) {
        require(GIT_OBJECT_SHA_PATTERN.matches(baseTreeSha)) { "invalid base tree SHA" }
        require(blobs.isNotEmpty()) { "tree requires at least one blob" }
        val safeRepo = client.validateRepo(repo)
        val targetDirectory = validateUmaGitDirectory(directory)
        val root = buildHierarchy(blobs)
        val sessionTreeSha = createNodeTree(safeRepo, root)
        val overlay = buildJsonObject {
            put("base_tree", baseTreeSha.lowercase())
            putJsonArray("tree") {
                add(buildJsonObject {
                    put("path", targetDirectory)
                    put("mode", "040000")
                    put("type", "tree")
                    put("sha", sessionTreeSha)
                })
            }
        }
        UmaGitTreeResult(
            treeSha = postTree(safeRepo, overlay),
            entryCount = blobs.size,
        )
    }

    private fun buildHierarchy(blobs: List<UmaGitTreeBlob>): UmaTreeNode {
        val root = UmaTreeNode()
        val fullPaths = mutableSetOf<String>()
        blobs.sortedBy { it.relativePath }.forEach { blob ->
            require(GIT_OBJECT_SHA_PATTERN.matches(blob.blobSha)) { "invalid blob SHA" }
            val path = validateUmaArchivePath(blob.relativePath)
            require(fullPaths.add(path)) { "duplicate Git tree path $path" }
            val parts = path.split('/')
            var node = root
            parts.dropLast(1).forEach { segment ->
                require(segment !in node.blobs) { "path is both file and directory: $segment" }
                node = node.children.getOrPut(segment) { UmaTreeNode() }
            }
            val fileName = parts.last()
            require(fileName !in node.children) { "path is both file and directory: $path" }
            require(node.blobs.put(fileName, blob.blobSha.lowercase()) == null) {
                "duplicate Git tree path $path"
            }
        }
        return root
    }

    private suspend fun createNodeTree(repo: String, node: UmaTreeNode): String {
        val childTrees = linkedMapOf<String, String>()
        node.children.toSortedMap().forEach { (name, child) ->
            childTrees[name] = createNodeTree(repo, child)
        }
        val body = buildJsonObject {
            putJsonArray("tree") {
                node.blobs.toSortedMap().forEach { (name, sha) ->
                    add(buildJsonObject {
                        put("path", name)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", sha)
                    })
                }
                childTrees.forEach { (name, sha) ->
                    add(buildJsonObject {
                        put("path", name)
                        put("mode", "040000")
                        put("type", "tree")
                        put("sha", sha)
                    })
                }
            }
        }
        return postTree(repo, body)
    }

    private suspend fun postTree(repo: String, body: JsonObject): String {
        val response = client.request(
            method = "POST",
            path = "/repos/$repo/git/trees",
            body = body,
        )
        if (response.code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub tree creation failed"
            error("$message (HTTP ${response.code})")
        }
        return parseUmaGitTreeSha(response.body, json)
    }
}

private val GIT_OBJECT_SHA_PATTERN = Regex("[0-9a-fA-F]{40}")
