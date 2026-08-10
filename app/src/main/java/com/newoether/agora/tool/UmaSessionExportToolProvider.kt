package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.uma.UmaBinaryRangeClient
import com.newoether.agora.uma.UmaGitBlobUploader
import com.newoether.agora.uma.UmaGitCommitClient
import com.newoether.agora.uma.UmaGitTreeClient
import com.newoether.agora.uma.UmaSessionGitBlobPipeline
import com.newoether.agora.uma.UmaSessionGitHubPublisher
import com.newoether.agora.uma.UmaSessionResumeDownloader
import com.newoether.agora.uma.UmaSessionZipExporter
import com.newoether.agora.uma.UmaStorageFilesClient
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exposes raw-byte exports and the complete confirmed GitHub session publisher. */
class UmaSessionExportToolProvider(context: Context) : ToolProvider {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val github = GitHubApiClient(appContext)
    var confirm: (suspend (repository: String, summary: String) -> Boolean)? = null
    private val names = setOf(
        "uma_session_download_raw", "uma_session_export_zip", "uma_session_upload_github"
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        val sessionId = string("Exact SO observation session_id to export.")
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "uma_session_download_raw",
                description = "Download every indexed file in one SO session as unchanged bytes with checkpointed resume. Reinvoke after timeout to continue.",
                parameters = ToolParameters(
                    properties = mapOf("session_id" to sessionId),
                    required = listOf("session_id"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "uma_session_export_zip",
                description = "Export every indexed file in one SO session to a ZIP while preserving relative paths, zero-byte files and unchanged bytes.",
                parameters = ToolParameters(
                    properties = mapOf("session_id" to sessionId),
                    required = listOf("session_id"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "uma_session_upload_github",
                description = "After user confirmation, resume-download a complete SO session, preserve and upload every raw file, derive MessagePack JSON plus manifest/exchange/error indexes, then commit all files to one existing workbench/* branch without force.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "session_id" to sessionId,
                        "repo" to string("Target GitHub repository in owner/name form."),
                        "branch" to string("Existing target branch beginning with workbench/."),
                        "target_directory" to string("Repository directory receiving raw and derived session files."),
                        "commit_message" to string("Git commit message, 1-500 characters."),
                    ),
                    required = listOf("session_id", "repo", "branch", "target_directory", "commit_message"),
                ),
            )),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in names) return errorJson("Unknown Uma session export tool")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun arg(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        val sessionId = arg("session_id")
        if (!SESSION_ID.matches(sessionId)) return errorJson("session_id has an invalid format")

        return runCatching {
            val base = File(appContext.cacheDir, "agora-uma")
            require(base.mkdirs() || base.isDirectory) { "cannot create Uma cache directory" }
            when (name) {
                "uma_session_download_raw" -> {
                    val root = File(base, "sessions/$sessionId")
                    val result = UmaSessionResumeDownloader().download(sessionId, root)
                    buildJsonObject {
                        put("ok", true); put("session_id", result.sessionId)
                        put("file_count", result.fileCount); put("total_bytes", result.totalBytes)
                        put("root_directory", result.rootDirectory.canonicalPath); put("resumable", true)
                    }.toString()
                }
                "uma_session_export_zip" -> {
                    val directory = File(base, "exports")
                    require(directory.mkdirs() || directory.isDirectory) { "cannot create export directory" }
                    val temporary = File(directory, "$sessionId.zip.part")
                    val target = File(directory, "$sessionId.zip")
                    if (temporary.exists()) require(temporary.delete()) { "cannot replace partial ZIP" }
                    val result = FileOutputStream(temporary, false).use { output ->
                        UmaSessionZipExporter().export(sessionId, output)
                    }
                    if (target.exists()) require(target.delete()) { "cannot replace completed ZIP" }
                    require(temporary.renameTo(target)) { "cannot finalize ZIP" }
                    buildJsonObject {
                        put("ok", true); put("session_id", result.sessionId)
                        put("file_count", result.fileCount); put("total_bytes", result.totalBytes)
                        put("zip_path", target.canonicalPath); put("zip_bytes", target.length())
                    }.toString()
                }
                "uma_session_upload_github" -> {
                    val repo = github.validateRepo(arg("repo"))
                    val branch = arg("branch")
                    val targetDirectory = arg("target_directory")
                    val commitMessage = arg("commit_message")
                    val approved = confirm?.invoke(
                        repo,
                        "Upload complete Uma session $sessionId with raw files and derived JSON to $repo:$branch/$targetDirectory",
                    ) ?: false
                    if (!approved) return errorJson("GitHub action denied")

                    val filesClient = UmaStorageFilesClient()
                    val rangeClient = UmaBinaryRangeClient()
                    val uploader = UmaGitBlobUploader(github)
                    val pipeline = UmaSessionGitBlobPipeline(
                        UmaSessionResumeDownloader(filesClient, rangeClient), filesClient, uploader,
                    )
                    val publisher = UmaSessionGitHubPublisher(
                        filesClient, pipeline, uploader, UmaGitTreeClient(github), UmaGitCommitClient(github),
                    )
                    val result = publisher.publish(
                        sessionId, repo, branch, targetDirectory, commitMessage,
                        File(base, "sessions/$sessionId"),
                    )
                    buildJsonObject {
                        put("ok", true); put("session_id", result.sessionId)
                        put("repository", result.repository); put("branch", result.branch)
                        put("target_directory", result.targetDirectory)
                        put("raw_file_count", result.rawFileCount)
                        put("derived_file_count", result.derivedFileCount)
                        put("total_tree_entries", result.totalTreeEntries)
                        put("decoded_payload_count", result.decodedPayloadCount)
                        put("decode_error_count", result.decodeErrorCount)
                        put("exchange_count", result.exchangeCount)
                        put("tree_sha", result.treeSha); put("commit_sha", result.commitSha)
                        put("resumed", result.resumed)
                    }.toString()
                }
                else -> error("Unknown Uma session export tool")
            }
        }.getOrElse { errorJson(it.message ?: "Session operation failed") }
    }

    override fun handles(name: String): Boolean = name in names
    private fun errorJson(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()
    private companion object { val SESSION_ID = Regex("[A-Za-z0-9._-]{1,200}") }
}
