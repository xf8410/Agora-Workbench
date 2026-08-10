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
import com.newoether.agora.uma.UmaSessionUploadBatchLimits
import com.newoether.agora.uma.UmaSessionUploadPhase
import com.newoether.agora.uma.UmaSessionUploadProgress
import com.newoether.agora.uma.UmaSessionUploadTask
import com.newoether.agora.uma.UmaSessionUploadTaskRecord
import com.newoether.agora.uma.UmaSessionUploadTaskStore
import com.newoether.agora.uma.UmaSessionUploadWorker
import com.newoether.agora.uma.UmaSessionZipExporter
import com.newoether.agora.uma.UmaStorageFilesClient
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exposes raw-byte exports, the legacy synchronous publisher and durable upload tasks. */
class UmaSessionExportToolProvider(context: Context) : ToolProvider {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val github = GitHubApiClient(appContext)
    var confirm: (suspend (repository: String, summary: String) -> Boolean)? = null
    private val names = setOf(
        "uma_session_download_raw",
        "uma_session_export_zip",
        "uma_session_upload_github",
        "uma_session_upload_start",
        "uma_session_upload_status",
        "uma_session_upload_resume",
        "uma_session_upload_cancel",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        val sessionId = string("Exact SO observation session_id to export.")
        val taskId = string("Persistent Uma upload task_id returned by uma_session_upload_start.")
        val uploadProperties = mapOf(
            "session_id" to sessionId,
            "repo" to string("Target GitHub repository in owner/name form."),
            "branch" to string("Existing target branch beginning with workbench/."),
            "target_directory" to string("Repository directory receiving raw and derived session files."),
            "commit_message" to string("Git commit message, 1-500 characters."),
        )
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
                description = "Legacy synchronous complete-session publisher. Prefer uma_session_upload_start for large sessions.",
                parameters = ToolParameters(
                    properties = uploadProperties,
                    required = uploadProperties.keys.toList(),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "uma_session_upload_start",
                description = "After user confirmation, create and enqueue a durable background upload task, then immediately return its task_id. The task resumes from existing raw-file and Git-Blob checkpoints and is not tied to one tool-call duration.",
                parameters = ToolParameters(
                    properties = uploadProperties + ("batch_size" to integer(
                        "Raw files per durable batch, 1-${UmaSessionUploadBatchLimits.MAX_BATCH_SIZE}; defaults to ${UmaSessionUploadBatchLimits.DEFAULT_BATCH_SIZE}."
                    )),
                    required = uploadProperties.keys.toList(),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "uma_session_upload_status",
                description = "Read persisted progress for one durable Uma session upload task.",
                parameters = ToolParameters(
                    properties = mapOf("task_id" to taskId),
                    required = listOf("task_id"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "uma_session_upload_resume",
                description = "Resume one paused or failed durable Uma session upload from its verified checkpoints.",
                parameters = ToolParameters(
                    properties = mapOf("task_id" to taskId),
                    required = listOf("task_id"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "uma_session_upload_cancel",
                description = "Cancel local scheduling for one durable Uma upload and persist CANCELLED state. Already-created unreferenced Git Blobs are not rewritten.",
                parameters = ToolParameters(
                    properties = mapOf("task_id" to taskId),
                    required = listOf("task_id"),
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
        val base = File(appContext.cacheDir, "agora-uma")

        return runCatching {
            require(base.mkdirs() || base.isDirectory) { "cannot create Uma cache directory" }
            when (name) {
                "uma_session_download_raw" -> {
                    val sessionId = requireSessionId(arg("session_id"))
                    val root = File(base, "sessions/$sessionId")
                    val result = UmaSessionResumeDownloader().download(sessionId, root)
                    buildJsonObject {
                        put("ok", true); put("session_id", result.sessionId)
                        put("file_count", result.fileCount); put("total_bytes", result.totalBytes)
                        put("root_directory", result.rootDirectory.canonicalPath); put("resumable", true)
                    }.toString()
                }
                "uma_session_export_zip" -> {
                    val sessionId = requireSessionId(arg("session_id"))
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
                "uma_session_upload_github" -> executeLegacyUpload(args, base)
                "uma_session_upload_start" -> startUpload(args, base)
                "uma_session_upload_status" -> {
                    val record = requireNotNull(taskStore(base).read(requireTaskId(arg("task_id")))) {
                        "upload task does not exist"
                    }
                    progressJson(record.progress)
                }
                "uma_session_upload_resume" -> {
                    val taskId = requireTaskId(arg("task_id"))
                    val store = taskStore(base)
                    val updated = store.update(taskId) { current ->
                        require(!current.progress.complete) { "completed upload task cannot be resumed" }
                        require(current.progress.phase != UmaSessionUploadPhase.CANCELLED) {
                            "cancelled upload task cannot be resumed"
                        }
                        current.copy(progress = current.progress.copy(
                            phase = if (current.progress.rawCompletedFiles == 0) {
                                UmaSessionUploadPhase.QUEUED
                            } else {
                                UmaSessionUploadPhase.RAW_BLOBS
                            },
                            lastError = null,
                            checkpointUpdatedAtMs = System.currentTimeMillis(),
                        ))
                    }
                    UmaSessionUploadWorker.enqueue(appContext, taskId, replace = true)
                    progressJson(updated.progress)
                }
                "uma_session_upload_cancel" -> {
                    val taskId = requireTaskId(arg("task_id"))
                    val store = taskStore(base)
                    val updated = store.update(taskId) { current ->
                        require(!current.progress.complete) { "completed upload task cannot be cancelled" }
                        current.copy(progress = current.progress.copy(
                            phase = UmaSessionUploadPhase.CANCELLED,
                            checkpointUpdatedAtMs = System.currentTimeMillis(),
                        ))
                    }
                    UmaSessionUploadWorker.cancel(appContext, taskId)
                    progressJson(updated.progress)
                }
                else -> error("Unknown Uma session export tool")
            }
        }.getOrElse { errorJson(it.message ?: "Session operation failed") }
    }

    private suspend fun startUpload(args: Map<String, JsonElement>, base: File): String {
        fun arg(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        val sessionId = requireSessionId(arg("session_id"))
        val repo = github.validateRepo(arg("repo"))
        val branch = arg("branch")
        val targetDirectory = arg("target_directory")
        val commitMessage = arg("commit_message")
        val batchSize = arg("batch_size").takeIf(String::isNotBlank)?.toIntOrNull()
            ?: UmaSessionUploadBatchLimits.DEFAULT_BATCH_SIZE
        UmaSessionUploadBatchLimits(batchSize)
        val approved = confirm?.invoke(
            repo,
            "Start durable Uma session upload $sessionId to $repo:$branch/$targetDirectory",
        ) ?: false
        require(approved) { "GitHub action denied" }

        val now = System.currentTimeMillis()
        val taskId = "uma-$sessionId-${UUID.randomUUID()}"
        val task = UmaSessionUploadTask(
            taskId = taskId,
            sessionId = sessionId,
            repository = repo,
            branch = branch,
            targetDirectory = targetDirectory,
            commitMessage = commitMessage,
            batchSize = batchSize,
            createdAtMs = now,
        )
        val progress = UmaSessionUploadProgress(
            taskId = taskId,
            sessionId = sessionId,
            repository = repo,
            branch = branch,
            targetDirectory = targetDirectory,
            phase = UmaSessionUploadPhase.QUEUED,
            rawTotalFiles = 0,
            rawCompletedFiles = 0,
            rawTotalBytes = 0,
            rawCompletedBytes = 0,
            nextCursor = 0,
            checkpointUpdatedAtMs = now,
        )
        taskStore(base).create(UmaSessionUploadTaskRecord(task, progress))
        UmaSessionUploadWorker.enqueue(appContext, taskId)
        return progressJson(progress)
    }

    private suspend fun executeLegacyUpload(args: Map<String, JsonElement>, base: File): String {
        fun arg(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        val sessionId = requireSessionId(arg("session_id"))
        val repo = github.validateRepo(arg("repo"))
        val branch = arg("branch")
        val targetDirectory = arg("target_directory")
        val commitMessage = arg("commit_message")
        val approved = confirm?.invoke(
            repo,
            "Upload complete Uma session $sessionId with raw files and derived JSON to $repo:$branch/$targetDirectory",
        ) ?: false
        require(approved) { "GitHub action denied" }

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
        return buildJsonObject {
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

    private fun progressJson(progress: UmaSessionUploadProgress) = buildJsonObject {
        put("ok", true)
        put("task_id", progress.taskId)
        put("session_id", progress.sessionId)
        put("repository", progress.repository)
        put("branch", progress.branch)
        put("target_directory", progress.targetDirectory)
        put("phase", progress.phase.name)
        put("raw_total_files", progress.rawTotalFiles)
        put("raw_completed_files", progress.rawCompletedFiles)
        put("raw_total_bytes", progress.rawTotalBytes)
        put("raw_completed_bytes", progress.rawCompletedBytes)
        put("derived_total_files", progress.derivedTotalFiles)
        put("derived_completed_files", progress.derivedCompletedFiles)
        put("reused_blob_count", progress.reusedBlobCount)
        put("next_cursor", progress.nextCursor)
        put("checkpoint_updated_at_ms", progress.checkpointUpdatedAtMs)
        progress.lastError?.let { put("last_error", it) }
        progress.treeSha?.let { put("tree_sha", it) }
        progress.commitSha?.let { put("commit_sha", it) }
        put("complete", progress.complete)
        put("terminal", progress.terminal)
    }.toString()

    private fun taskStore(base: File) = UmaSessionUploadTaskStore(base)

    private fun requireSessionId(value: String): String {
        require(SESSION_ID.matches(value)) { "session_id has an invalid format" }
        return value
    }

    private fun requireTaskId(value: String): String {
        require(TASK_ID.matches(value)) { "task_id has an invalid format" }
        return value
    }

    override fun handles(name: String): Boolean = name in names
    private fun errorJson(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9._-]{1,200}")
        val TASK_ID = Regex("[A-Za-z0-9._-]{1,240}")
    }
}
