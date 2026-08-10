package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.uma.UmaSessionResumeDownloader
import com.newoether.agora.uma.UmaSessionZipExporter
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exposes the existing raw-byte session exporters to the model tool registry. */
class UmaSessionExportToolProvider : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val names = setOf("uma_session_download_raw", "uma_session_export_zip")

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        val sessionId = ToolProperty("string", "Exact SO observation session_id to export.")
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
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in names) return errorJson("Unknown Uma session export tool")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        val sessionId = (args["session_id"] as? JsonPrimitive)?.content.orEmpty().trim()
        if (!SESSION_ID.matches(sessionId)) return errorJson("session_id has an invalid format")

        return runCatching {
            val base = requireNotNull(System.getProperty("java.io.tmpdir")) {
                "java.io.tmpdir is unavailable"
            }
            when (name) {
                "uma_session_download_raw" -> {
                    val root = File(base, "agora-uma-sessions/$sessionId")
                    val result = UmaSessionResumeDownloader().download(sessionId, root)
                    buildJsonObject {
                        put("ok", true)
                        put("session_id", result.sessionId)
                        put("file_count", result.fileCount)
                        put("total_bytes", result.totalBytes)
                        put("root_directory", result.rootDirectory.canonicalPath)
                        put("resumable", true)
                    }.toString()
                }
                "uma_session_export_zip" -> {
                    val directory = File(base, "agora-uma-exports")
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
                        put("ok", true)
                        put("session_id", result.sessionId)
                        put("file_count", result.fileCount)
                        put("total_bytes", result.totalBytes)
                        put("zip_path", target.canonicalPath)
                        put("zip_bytes", target.length())
                    }.toString()
                }
                else -> error("Unknown Uma session export tool")
            }
        }.getOrElse { errorJson(it.message ?: "Session export failed") }
    }

    override fun handles(name: String): Boolean = name in names

    private fun errorJson(message: String) = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9._-]{1,200}")
    }
}
