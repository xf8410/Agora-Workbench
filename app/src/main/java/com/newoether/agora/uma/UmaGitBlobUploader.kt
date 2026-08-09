package com.newoether.agora.uma

import android.util.Base64
import com.newoether.agora.github.GitHubApiClient
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class UmaGitBlobResult(
    val blobSha: String,
    val byteLength: Long,
    val sha256: String,
)

internal fun buildUmaGitBlobBody(bytes: ByteArray): JsonObject = buildJsonObject {
    put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
    put("encoding", "base64")
}

internal fun parseUmaGitBlobSha(body: String, json: Json = Json): String {
    val sha = json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content.orEmpty()
    require(SHA1_PATTERN.matches(sha)) { "GitHub blob response did not contain a valid SHA" }
    return sha
}

/** Uploads one complete local file as an unchanged Git blob and returns both Git and local hashes. */
class UmaGitBlobUploader(
    private val client: GitHubApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun upload(repo: String, file: File): UmaGitBlobResult = withContext(Dispatchers.IO) {
        val safeRepo = client.validateRepo(repo)
        require(file.isFile) { "blob source is not a file" }
        require(file.length() <= MAX_GIT_BLOB_BYTES) {
            "Git blob exceeds GitHub's 100 MiB limit"
        }

        val bytes = file.readBytes()
        require(bytes.size.toLong() == file.length()) { "file length changed while reading" }
        val localSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val response = client.request(
            method = "POST",
            path = "/repos/$safeRepo/git/blobs",
            body = buildUmaGitBlobBody(bytes),
        )
        if (response.code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub blob upload failed"
            error("$message (HTTP ${response.code})")
        }

        UmaGitBlobResult(
            blobSha = parseUmaGitBlobSha(response.body, json),
            byteLength = bytes.size.toLong(),
            sha256 = localSha256,
        )
    }

    companion object {
        const val MAX_GIT_BLOB_BYTES = 100L * 1024L * 1024L
    }
}

private val SHA1_PATTERN = Regex("[0-9a-fA-F]{40}")
