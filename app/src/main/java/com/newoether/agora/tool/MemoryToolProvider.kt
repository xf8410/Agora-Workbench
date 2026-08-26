package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Memory tools. Success results keep the historical plain-text contract
 * ("Created …" / "Updated …" / raw file content / files JSON) so model prompts and
 * existing tests stay stable; failures are structured JSON with a stable error_code
 * so ToolExecutionErrors can surface an actionable reason instead of a generic error.
 * Durable write verification lives in [MemoryManager] (atomic write + read-back check).
 */
class MemoryToolProvider(private val memoryManager: MemoryManager) : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val toolNames = setOf(
        "list_memory_files", "read_memory_file", "create_memory_file",
        "edit_memory_file", "delete_memory_file", "update_active_memory",
    )

    private fun boundedRead(name: String): String {
        val value = memoryManager.readFile(name)
        val maxChars = 32 * 1024
        return if (value.length <= maxChars) value
        else value.take(maxChars) + "\n…[memory file truncated; split it into smaller Markdown files]"
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessSavedMemories && !ctx.accessActiveMemory) return emptyList()
        val tools = mutableListOf<ToolDefinition>()
        if (ctx.accessSavedMemories) {
            tools += def("list_memory_files", "List all files in the memory database with their names and descriptions.", emptyMap(), emptyList())
            tools += def("read_memory_file", "Read the content of one file (name) or several files (names).", mapOf(
                "name" to prop("string", "The file name to read."),
                "names" to prop("array", "Multiple file names to read in one call.", prop("string", "A file name.")),
            ), emptyList())
            tools += def("create_memory_file", "Create a new Markdown memory file; fails if it already exists.", mapOf(
                "name" to prop("string", "The file name to create (e.g., 'notes.md')."),
                "content" to prop("string", "The Markdown content for the file."),
                "description" to prop("string", "A short description of what this file contains (optional)."),
            ), listOf("name", "content"))
            tools += def("edit_memory_file", "Edit, rename, or update a file. Use 'content' for a full rewrite OR 'old_string'+'new_string' for one exact replacement.", mapOf(
                "name" to prop("string", "The current file name to edit."),
                "content" to prop("string", "New full content. Mutually exclusive with old_string."),
                "old_string" to prop("string", "Exact string that must match exactly once."),
                "new_string" to prop("string", "Replacement; empty string deletes the match. Required with old_string."),
                "new_name" to prop("string", "Optional new file name."),
                "description" to prop("string", "Optional description; empty removes it."),
            ), listOf("name"))
            tools += def("delete_memory_file", "Delete a file from the memory database.", mapOf(
                "name" to prop("string", "The file name to delete.")), listOf("name"))
        }
        if (ctx.accessActiveMemory) {
            tools += def("update_active_memory", "Update active memory. Modes: replace, append, prepend, patch (patch needs old_string).", mapOf(
                "content" to prop("string", "The content to write."),
                "mode" to prop("string", "One of: replace, append, prepend, patch. Default replace."),
                "old_string" to prop("string", "Exact string for patch mode; must match exactly once."),
                "new_string" to prop("string", "Replacement for patch; empty deletes the match."),
            ), listOf("content"))
        }
        return tools
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in toolNames) return fail(name, "unknown_memory_tool", "Unknown tool: $name")
        val args: Map<String, JsonElement> = try {
            json.decodeFromString(arguments.ifBlank { "{}" })
        } catch (e: Exception) {
            return fail(name, "invalid_json_arguments", e.message ?: "Arguments are not valid JSON")
        }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content ?: ""
        fun present(key: String) = args.containsKey(key)
        fun required(key: String) = text(key).takeIf { it.isNotBlank() }
        return try {
            when (name) {
                "list_memory_files" -> buildJsonObject {
                    put("ok", true); put("type", "list_memory_files")
                    putJsonArray("files") {
                        memoryManager.listFiles().forEach { f ->
                            add(buildJsonObject { put("name", f.name); put("description", f.description) })
                        }
                    }
                }.toString()
                "read_memory_file" -> {
                    val many = (args["names"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                        .orEmpty()
                    val single = required("name")
                    when {
                        many.isNotEmpty() -> many.joinToString("\n\n") { n -> "--- $n ---\n${boundedRead(n)}" }
                        single != null -> boundedRead(single)
                        else -> fail(name, "missing_name", "No file name provided. Use 'name' for one file or 'names' for several.")
                    }
                }
                "create_memory_file" -> {
                    val file = required("name") ?: return fail(name, "missing_name", "name is required")
                    if (!present("content")) return fail(name, "missing_content", "content is required (an empty string is allowed)")
                    memoryManager.createFile(file, text("content"), text("description"))
                }
                "edit_memory_file" -> {
                    val file = required("name") ?: return fail(name, "missing_name", "name is required")
                    val content = text("content").takeIf { present("content") }
                    val oldString = text("old_string").takeIf { present("old_string") }
                    val newString = text("new_string")
                    val newName = text("new_name").takeIf { present("new_name") && it.isNotBlank() }
                    val description = text("description").takeIf { present("description") }
                    when {
                        content != null && oldString != null -> fail(name, "mutually_exclusive", "'content' and 'old_string' are mutually exclusive; use one or the other.")
                        oldString != null && !present("new_string") -> fail(name, "missing_new_string", "'old_string' requires 'new_string' (pass an empty string to delete).")
                        content == null && oldString == null && newName == null && description == null ->
                            fail(name, "no_change", "Provide at least one of: content, old_string+new_string, new_name, description.")
                        else -> memoryManager.editFile(file, content, newName, description, oldString, newString)
                    }
                }
                "delete_memory_file" -> {
                    val file = required("name") ?: return fail(name, "missing_name", "name is required")
                    memoryManager.deleteFile(file)
                }
                "update_active_memory" -> {
                    val mode = text("mode").ifBlank { "replace" }
                    val oldString = text("old_string").takeIf { present("old_string") }
                    val newString = text("new_string").takeIf { present("new_string") }
                    if (mode == "patch" && oldString == null) fail(name, "missing_old_string", "'old_string' is required for patch mode.")
                    else memoryManager.updateActiveMemory(text("content"), mode, oldString, newString)
                }
                else -> fail(name, "unknown_memory_tool", "Unknown tool: $name")
            }
        } catch (e: Exception) {
            fail(name, "memory_operation_failed", e.message ?: (e::class.java.simpleName))
        }
    }

    private fun fail(tool: String, code: String, detail: String) = buildJsonObject {
        put("ok", false); put("tool", tool); put("error_code", code); put("detail", detail.take(1000))
    }.toString()

    private fun prop(type: String, description: String, items: ToolProperty? = null) = ToolProperty(type, description, items = items)
    private fun def(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String>) =
        ToolDefinition(function = ToolFunction(name = name, description = description, parameters = ToolParameters(properties = properties, required = required)))
    override fun handles(name: String): Boolean = name in toolNames
}
