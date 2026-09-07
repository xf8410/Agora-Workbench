package com.newoether.agora.courier

import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.github.GitHubApiResponse
import com.newoether.agora.util.Constants
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Courier 专用 GitHub 上传通道：Git Data API（blob → tree → commit → ref），
 * 单卷可到 [MAX_VOLUME_BYTES]（Contents API 1MB 上限装不下分卷）。
 *
 * 与 GitHubBinaryUploader 的差异（这是新类而不是改动它的原因）：
 * - 分支不强制 workbench/*：Courier 的目标是机主在设置里指定的私有中转仓（如 bestsoccer-gala）
 * - 体积不设 900KB 上限：走 blob API 且 base64 流式写入，避免一次性构造巨型 JSON 字符串
 * - token 只经 GitHubApiClient 的已登录会话传递，不落日志、不进 URL
 */
class CourierUploader(private val client: GitHubApiClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** GitHub Git Data "Create a blob" official cap is 100 MB; keep a safety margin. */
    private val maxVolumeBytes = MAX_VOLUME_BYTES

    /** Validates a branch/ref name without the workbench/* restriction (see class doc). */
    private fun requireValidBranch(branch: String) {
        require(branch.matches(BRANCH_PATTERN)) { "Invalid branch name: $branch" }
    }

    private fun requireValidRepoPath(path: String): String {
        val trimmed = path.trim('/').trimEnd('/')
        require(trimmed.isNotBlank()) { "Target path must not be blank" }
        require(!trimmed.split('/').any { it == "." || it == ".." }) { "Invalid target path: $path" }
        require(!trimmed.contains("//")) { "Invalid target path: $path" }
        return trimmed
    }

    /** Returns the branch head SHA, creating the branch from the default branch when missing. */
    suspend fun ensureBranch(repo: String, branch: String): String {
        val safeRepo = client.validateRepo(repo)
        requireValidBranch(branch)
        val ref = client.request("GET", "/repos/$safeRepo/git/ref/heads/${client.encodeSegment(branch)}")
        if (ref.code in 200..299) {
            return json.parseToJsonElement(ref.body).jsonObject.getValue("object").jsonObject.getValue("sha").jsonPrimitive.content
        }
        require(ref.code == 404) { "Cannot read branch $branch (HTTP ${ref.code}): ${ref.body.take(200)}" }
        val repository = client.repository(safeRepo)
        val defaultBranch = repository.getValue("default_branch").jsonPrimitive.content
        val baseRef = client.request("GET", "/repos/$safeRepo/git/ref/heads/${client.encodeSegment(defaultBranch)}")
        require(baseRef.code in 200..299) { "Cannot read default branch $defaultBranch (HTTP ${baseRef.code})" }
        val baseSha = json.parseToJsonElement(baseRef.body).jsonObject.getValue("object").jsonObject.getValue("sha").jsonPrimitive.content
        val created = client.request("POST", "/repos/$safeRepo/git/refs", buildJsonObject {
            put("ref", "refs/heads/$branch"); put("sha", baseSha)
        })
        require(created.code in 200..299 || created.code == 422) {
            "Cannot create branch $branch (HTTP ${created.code}): ${created.body.take(200)}"
        }
        return baseSha
    }

    /**
     * Uploads one volume file as a Git blob on [branch], retrying per courier policy
     * (initial attempt + [Constants.COURIER_UPLOAD_RETRIES] retries, then give up).
     * @return the file's GitHub path.
     */
    suspend fun uploadVolume(
        repo: String,
        branch: String,
        path: String,
        file: File,
        message: String,
    ): String = withContext(Dispatchers.IO) {
        val safeRepo = client.validateRepo(repo)
        requireValidBranch(branch)
        val safePath = requireValidRepoPath(path)
        require(file.isFile && file.length() > 0) { "Volume file missing or empty: ${file.name}" }
        require(file.length() <= maxVolumeBytes) {
            "Volume too large (${file.length() / 1_000_000} MB, max ${MAX_VOLUME_BYTES / 1_000_000} MB)"
        }
        val bytes = file.readBytes()
        var lastError: Exception? = null
        repeat(1 + Constants.COURIER_UPLOAD_RETRIES) { attempt ->
            try {
                commitBlob(safeRepo, branch, safePath, bytes, message)
                return@withContext safePath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < Constants.COURIER_UPLOAD_RETRIES) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Volume upload failed")
    }

    /**
     * Uploads one in-memory payload as a Git blob on [branch] with the same retry policy
     * as [uploadVolume] (used for whole-file direct delivery).
     * @return the file's GitHub path.
     */
    suspend fun uploadBytes(
        repo: String,
        branch: String,
        path: String,
        bytes: ByteArray,
        message: String,
    ): String = withContext(Dispatchers.IO) {
        val safeRepo = client.validateRepo(repo)
        requireValidBranch(branch)
        val safePath = requireValidRepoPath(path)
        require(bytes.isNotEmpty()) { "Nothing to upload" }
        require(bytes.size <= maxVolumeBytes) {
            "Payload too large (${bytes.size / 1_000_000} MB, max ${MAX_VOLUME_BYTES / 1_000_000} MB)"
        }
        var lastError: Exception? = null
        repeat(1 + Constants.COURIER_UPLOAD_RETRIES) { attempt ->
            try {
                commitBlob(safeRepo, branch, safePath, bytes, message)
                return@withContext safePath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < Constants.COURIER_UPLOAD_RETRIES) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Payload upload failed")
    }

    /** Uploads small JSON payloads (the manifest) through the same blob pipeline. */
    suspend fun uploadManifest(
        repo: String,
        branch: String,
        path: String,
        manifest: JsonObject,
        message: String,
    ): String = withContext(Dispatchers.IO) {
        val safeRepo = client.validateRepo(repo)
        requireValidBranch(branch)
        val safePath = requireValidRepoPath(path)
        commitBlob(safeRepo, branch, safePath, manifest.toString().toByteArray(Charsets.UTF_8), message)
        safePath
    }

    /** blob → tree → commit → update ref. Commits are fast-forwarded, never forced. */
    private suspend fun commitBlob(repo: String, branch: String, path: String, bytes: ByteArray, message: String) {
        val blobSha = createBlob(repo, bytes)

        val head = client.request("GET", "/repos/$repo/git/ref/heads/${client.encodeSegment(branch)}")
        require(head.code in 200..299) { "Branch $branch is gone (HTTP ${head.code})" }
        val parentSha = json.parseToJsonElement(head.body).jsonObject.getValue("object").jsonObject.getValue("sha").jsonPrimitive.content

        val parentCommit = client.request("GET", "/repos/$repo/git/commits/$parentSha")
        require(parentCommit.code in 200..299) { "Cannot read parent commit (HTTP ${parentCommit.code})" }
        val baseTreeSha = json.parseToJsonElement(parentCommit.body).jsonObject.getValue("tree").jsonObject.getValue("sha").jsonPrimitive.content

        val tree = client.request("POST", "/repos/$repo/git/trees", buildJsonObject {
            put("base_tree", baseTreeSha)
            put("tree", JsonArray(listOf(buildJsonObject {
                put("path", path); put("mode", "100644"); put("type", "blob"); put("sha", blobSha)
            })))
        })
        require(tree.code in 200..299) { "Cannot create tree (HTTP ${tree.code}): ${tree.body.take(200)}" }
        val treeSha = json.parseToJsonElement(tree.body).jsonObject.getValue("sha").jsonPrimitive.content

        val commit = client.request("POST", "/repos/$repo/git/commits", buildJsonObject {
            put("message", message.ifBlank { "Courier: upload $path" })
            put("tree", treeSha)
            put("parents", JsonArray(listOf(JsonPrimitive(parentSha))))
        })
        require(commit.code in 200..299) { "Cannot create commit (HTTP ${commit.code}): ${commit.body.take(200)}" }
        val commitSha = json.parseToJsonElement(commit.body).jsonObject.getValue("sha").jsonPrimitive.content

        val updated = client.request("PATCH", "/repos/$repo/git/refs/heads/${client.encodeSegment(branch)}", buildJsonObject {
            put("sha", commitSha); put("force", false)
        })
        require(updated.code in 200..299) { "Cannot move ref $branch (HTTP ${updated.code}): ${updated.body.take(200)}" }
    }

    /**
     * Streams `{"content":"<base64>","encoding":"base64"}` into the connection without
     * materializing the full JSON string in memory. Base64.Encoder.encode(byte[], OutputStream)
     * appends the full encoding (padding included) without closing the underlying stream, so the
     * JSON suffix can still be written afterwards.
     */
    private suspend fun createBlob(repo: String, bytes: ByteArray): String {
        val response: GitHubApiResponse = client.requestStreamBody(
            "POST", "/repos/$repo/git/blobs", "application/json",
        ) { output ->
            output.write("{\"content\":\"".toByteArray(Charsets.UTF_8))
            try {
                Base64.getEncoder().encode(bytes, output)
            } catch (e: IOException) {
                throw IOException("Base64 streaming into the blob request body failed", e)
            }
            output.write("\",\"encoding\":\"base64\"}".toByteArray(Charsets.UTF_8))
        }
        require(response.code in 200..299) { "Blob upload failed (HTTP ${response.code}): ${response.body.take(300)}" }
        return json.parseToJsonElement(response.body).jsonObject.getValue("sha").jsonPrimitive.content
    }

    companion object {
        // Last-resort guard before the blob API hard limit; source noted in Constants.
        private val MAX_VOLUME_BYTES = Constants.COURIER_MAX_SINGLE_BLOB_MB * 1_000_000L
        private const val RETRY_DELAY_MS = 2_000L
        private val BRANCH_PATTERN = Regex("[A-Za-z0-9._/-]{1,200}")
    }
}
