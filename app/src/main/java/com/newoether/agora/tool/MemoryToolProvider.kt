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

class MemoryToolProvider(private val memoryManager: MemoryManager) : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val toolNames = setOf("list_memory_files", "read_memory_file", "create_memory_file", "edit_memory_file", "delete_memory_file", "update_active_memory")

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessSavedMemories && !ctx.accessActiveMemory) return emptyList()
        val out = mutableListOf<ToolDefinition>()
        if (ctx.accessSavedMemories) {
            out += def("list_memory_files", "List memory files.", emptyMap(), emptyList())
            out += def("read_memory_file", "Read one memory file by name or several with names.", mapOf("name" to prop("string", "File name"), "names" to prop("array", "File names", prop("string", "File name"))), emptyList())
            out += def("create_memory_file", "Create a new Markdown memory file.", mapOf("name" to prop("string", "File name"), "content" to prop("string", "Complete Markdown content"), "description" to prop("string", "Optional description")), listOf("name", "content"))
            out += def("edit_memory_file", "Edit or rename a memory file. Use content OR old_string/new_string.", mapOf("name" to prop("string", "Current file name"), "content" to prop("string", "Replacement content"), "old_string" to prop("string", "Unique text to replace"), "new_string" to prop("string", "Replacement text"), "new_name" to prop("string", "New file name"), "description" to prop("string", "Description; empty removes it")), listOf("name"))
            out += def("delete_memory_file", "Delete a memory file.", mapOf("name" to prop("string", "File name")), listOf("name"))
        }
        if (ctx.accessActiveMemory) out += def("update_active_memory", "Update active memory: replace, append, prepend, or patch.", mapOf("content" to prop("string", "Content"), "mode" to prop("string", "replace/append/prepend/patch"), "old_string" to prop("string", "Required for patch"), "new_string" to prop("string", "Patch replacement")), listOf("content"))
        return out
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in toolNames) return fail(name, "unknown_memory_tool", "Unknown memory tool")
        val args: Map<String, JsonElement> = try { json.decodeFromString(arguments.ifBlank { "{}" }) } catch (e: Exception) { return fail(name, "invalid_json_arguments", e.message ?: "Invalid JSON") }
        fun text(k: String) = (args[k] as? JsonPrimitive)?.content ?: ""
        fun has(k: String) = args.containsKey(k)
        fun required(k: String) = text(k).takeIf { it.isNotBlank() }
        return try {
            when (name) {
                "list_memory_files" -> buildJsonObject { put("ok", true); put("tool", name); putJsonArray("files") { memoryManager.listFiles().forEach { f -> add(buildJsonObject { put("name", f.name); put("description", f.description) }) } } }.toString()
                "read_memory_file" -> {
                    val many = (args["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }.orEmpty()
                    val one = required("name")
                    when { many.isNotEmpty() -> many.joinToString("\n\n") { "--- $it ---\n${memoryManager.readFile(it).take(32768)}" }; one != null -> memoryManager.readFile(one).take(32768); else -> fail(name, "missing_name", "Provide name or names") }
                }
                "create_memory_file" -> { val file = required("name") ?: return fail(name, "missing_name", "name is required"); if (!has("content")) return fail(name, "missing_content", "content is required"); val result = memoryManager.createFile(file, text("content"), text("description")); if (memoryManager.readFile(file) != text("content")) fail(name, "write_verification_failed", "Read-back differs") else success(name, result, file) }
                "edit_memory_file" -> {
                    val file = required("name") ?: return fail(name, "missing_name", "name is required")
                    val content = text("content").takeIf { has("content") }; val old = text("old_string").takeIf { has("old_string") }; val replacement = text("new_string"); val newName = text("new_name").takeIf { has("new_name") && it.isNotBlank() }; val desc = text("description").takeIf { has("description") }
                    when { content != null && old != null -> fail(name, "mutually_exclusive", "content and old_string cannot both be used"); old != null && !has("new_string") -> fail(name, "missing_new_string", "new_string is required"); content == null && old == null && newName == null && desc == null -> fail(name, "no_change", "No edit was requested"); else -> success(name, memoryManager.editFile(file, content, newName, desc, old, replacement), newName ?: file) }
                }
                "delete_memory_file" -> { val file = required("name") ?: return fail(name, "missing_name", "name is required"); success(name, memoryManager.deleteFile(file), file) }
                "update_active_memory" -> { val mode = text("mode").ifBlank { "replace" }; val old = text("old_string").takeIf { has("old_string") }; if (mode == "patch" && old == null) fail(name, "missing_old_string", "old_string is required for patch") else success(name, memoryManager.updateActiveMemory(text("content"), mode, old, text("new_string").takeIf { has("new_string") }), "active_memory") }
                else -> fail(name, "unknown_memory_tool", "Unknown memory tool")
            }
        } catch (e: Exception) { fail(name, "memory_operation_failed", e.message ?: e::class.java.simpleName) }
    }

    private fun success(tool: String, message: String, target: String) = buildJsonObject { put("ok", true); put("tool", tool); put("message", message); put("target", target); put("verified", true) }.toString()
    private fun fail(tool: String, code: String, detail: String) = buildJsonObject { put("ok", false); put("tool", tool); put("error_code", code); put("detail", detail.take(1000)) }.toString()
    private fun prop(type: String, description: String, items: ToolProperty? = null) = ToolProperty(type, description, items = items)
    private fun def(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String>) = ToolDefinition(function = ToolFunction(name = name, description = description, parameters = ToolParameters(properties = properties, required = required)))
    override fun handles(name: String) = name in toolNames
}
