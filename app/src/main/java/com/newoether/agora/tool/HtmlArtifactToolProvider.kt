package com.newoether.agora.tool

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Lets the assistant produce standalone HTML files (charts, cards, reports, small pages) that
 * the user can open immediately. Files persist under filesDir/html_artifacts/ and are opened
 * through FileProvider + ACTION_VIEW, so the system browser renders them without the app
 * needing a WebView.
 */
class HtmlArtifactToolProvider(private val appContext: Context) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "write_html_artifact",
                description = "Save a complete HTML document (self-contained, inline CSS/JS) as a file and open it for the user. Use for charts, visual cards, formatted reports, and small interactive pages. The HTML must be a full document; reference no external local files.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "name" to ToolProperty("string", "Short file name without extension, e.g. 'weekly-report' or '进度卡片'."),
                        "html" to ToolProperty("string", "The full HTML document content.")
                    ),
                    required = listOf("name", "html")
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "list_html_artifacts",
                description = "List previously generated HTML artifact files with their sizes and timestamps.",
                parameters = ToolParameters(properties = emptyMap())
            )
        )
    )

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

        return when (name) {
            "write_html_artifact" -> {
                val html = text("html")
                if (html.isEmpty()) return errorJson("html is required")
                if (html.length > MAX_HTML_CHARS) return errorJson("html too large (>${MAX_HTML_CHARS / 1024}KB); split the content")
                val safeName = sanitizeName(text("name").ifEmpty { "artifact" })
                val dir = File(appContext.filesDir, DIR).apply { mkdirs() }
                val file = File(dir, "${System.currentTimeMillis()}_$safeName.html")
                file.writeText(html)
                val opened = openExternally(file)
                buildJsonObject {
                    put("ok", true)
                    put("saved_path", file.absolutePath)
                    put("file_name", file.name)
                    put("byte_length", file.length())
                    put("opened_in_browser", opened)
                }.toString()
            }
            "list_html_artifacts" -> {
                val dir = File(appContext.filesDir, DIR)
                val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                buildJsonObject {
                    put("ok", true)
                    put(
                        "files",
                        kotlinx.serialization.json.buildJsonArray {
                            files.take(50).forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("file_name", f.name)
                                        put("byte_length", f.length())
                                        put("modified_at", f.lastModified())
                                    }
                                )
                            }
                        }
                    )
                }.toString()
            }
            else -> errorJson("Unknown html artifact tool")
        }
    }

    /** Opens the file via the system viewer; failure is reported but never aborts the save. */
    private fun openExternally(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/html")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(intent)
        true
    }.getOrElse {
        DebugLog.w(TAG, "Failed to open html artifact", it)
        false
    }

    private fun sanitizeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60).ifEmpty { "artifact" }

    private fun errorJson(message: String) =
        buildJsonObject { put("ok", false); put("error", message) }.toString()

    override fun handles(name: String) =
        name in setOf("write_html_artifact", "list_html_artifacts")

    private companion object {
        const val DIR = "html_artifacts"
        const val MAX_HTML_CHARS = 5 * 1024 * 1024 / 4 // 1.25M chars ≈ 5MB UTF-8 ceiling
        const val TAG = "HtmlArtifactTool"
    }
}
