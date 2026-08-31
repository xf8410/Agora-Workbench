package com.newoether.agora.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MemoryManager(context: Context) {
    private val memoryDir: File =
        File(context.filesDir, "memory_db").also { it.mkdirs() }

    private val activeMemoryFile: File =
        File(context.filesDir, "active_memory.md")

    private val metaFile: File =
        File(memoryDir, "memory_meta.json")

    /** Absent file = enabled (default). Only an explicit "false" disables the auto handoff. */
    private val sessionHandoffFlagFile: File =
        File(context.filesDir, "session_handoff_enabled")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    data class MemoryFileInfo(
        val name: String,
        val description: String = ""
    )

    @Synchronized
    fun getActiveMemory(): String =
        if (activeMemoryFile.exists()) activeMemoryFile.readText() else ""

    @Synchronized
    fun updateActiveMemory(
        content: String,
        mode: String = "replace",
        oldString: String? = null,
        newString: String? = null
    ): String =
        when (mode) {
            "append" -> {
                activeMemoryFile.appendText("\n$content")
                "Appended to active memory."
            }
            "prepend" -> {
                val existing = getActiveMemory()
                activeMemoryFile.writeText("$content\n$existing")
                "Prepended to active memory."
            }
            "patch" -> {
                if (oldString == null) throw IllegalArgumentException("old_string is required for patch mode")
                val existing = getActiveMemory()
                val count = existing.countOccurrences(oldString)
                if (count == 0)
                    throw IllegalArgumentException("old_string not found in active memory")
                if (count > 1)
                    throw IllegalArgumentException("old_string matches $count times in active memory — must be unique")
                activeMemoryFile.writeText(existing.replace(oldString, newString ?: ""))
                "Active memory patched."
            }
            else -> {
                activeMemoryFile.writeText(content)
                "Active memory updated."
            }
        }

    @Synchronized
    private fun loadMeta(): MutableMap<String, String> =
        if (metaFile.exists()) {
            try { json.decodeFromString<MutableMap<String, String>>(metaFile.readText()) }
            catch (_: Exception) { mutableMapOf() }
        } else mutableMapOf()

    @Synchronized
    private fun saveMeta(meta: Map<String, String>) {
        metaFile.writeText(json.encodeToString(meta))
    }

    @Synchronized
    fun getDescription(name: String): String {
        val resolved = resolveFile(name)
        if (!resolved.exists()) return ""
        return loadMeta()[resolved.name] ?: ""
    }

    @Synchronized
    fun setDescription(name: String, description: String) {
        val resolved = resolveFile(name)
        if (!resolved.exists()) throw IllegalArgumentException("File not found: $name")
        val meta = loadMeta()
        if (description.isBlank()) meta.remove(resolved.name) else meta[resolved.name] = description
        saveMeta(meta)
    }

    @Synchronized
    fun listFiles(): List<MemoryFileInfo> {
        val meta = loadMeta()
        return memoryDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { MemoryFileInfo(it.name, meta[it.name] ?: "") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    @Synchronized
    fun getMetaJson(): String =
        if (metaFile.exists()) metaFile.readText() else "{}"

    @Synchronized
    fun saveMetaJson(jsonStr: String) {
        // Atomic write via temp-file + rename so a concurrent getMetaJson can never observe
        // a half-written JSON (POSIX write is not atomic; parallel generations on different
        // conversations both touch this global meta file).
        val tmp = java.io.File(metaFile.parentFile, metaFile.name + ".tmp")
        tmp.writeText(jsonStr)
        if (!tmp.renameTo(metaFile)) {
            // Rename can fail on some filesystems if the target exists; fall back to a plain
            // write under the same @Synchronized lock (still safe against concurrent readers,
            // just not torn-write-safe on crash). Best-effort cleanup.
            metaFile.writeText(jsonStr)
            tmp.delete()
        }
    }

    @Synchronized
    fun readFile(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        return file.readText()
    }

    @Synchronized
    fun createFile(name: String, content: String, description: String = ""): String {
        val file = resolveFile(name)
        if (file.exists()) throw IllegalArgumentException("File already exists: ${file.name}")
        file.writeText(content)
        if (description.isNotBlank()) {
            val meta = loadMeta()
            meta[file.name] = description
            saveMeta(meta)
        }
        return "Created ${file.name}"
    }

    @Synchronized
    fun editFile(name: String, content: String? = null, newName: String? = null, description: String? = null, oldString: String? = null, newString: String? = null): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        val meta = loadMeta()
        var renamedFile: File? = null
        if (oldString != null) {
            val fileText = file.readText()
            val count = fileText.countOccurrences(oldString)
            if (count == 0)
                throw IllegalArgumentException("old_string not found in ${file.name}")
            if (count > 1)
                throw IllegalArgumentException("old_string matches $count times in ${file.name} — must be unique")
            file.writeText(fileText.replace(oldString, newString ?: ""))
        } else if (content != null) {
            file.writeText(content)
        }
        if (newName != null && newName != name) {
            renamedFile = resolveFile(newName)
            if (renamedFile.exists()) throw IllegalArgumentException("Target file already exists: ${renamedFile.name}")
            file.renameTo(renamedFile)
            val desc = meta.remove(file.name)
            if (desc != null) meta[renamedFile.name] = desc
        }
        if (description != null) {
            if (description.isBlank()) meta.remove((renamedFile ?: file).name)
            else meta[(renamedFile ?: file).name] = description
        }
        saveMeta(meta)
        val targetName = newName?.let { resolveFile(it).name } ?: file.name
        if (oldString != null && newName != null) return "Replaced in and renamed to $targetName"
        if (oldString != null) return "Replaced in $targetName"
        if (content != null && newName != null) return "Updated and renamed to $targetName"
        if (content != null) return "Updated $targetName"
        if (newName != null) return "Renamed to $targetName"
        if (description != null) return "Updated description of $targetName"
        return "No changes made."
    }

    private fun String.countOccurrences(substring: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = indexOf(substring, idx)
            if (idx < 0) break
            count++
            idx += substring.length
        }
        return count
    }

    @Synchronized
    fun deleteFile(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        file.delete()
        val meta = loadMeta()
        meta.remove(file.name)
        saveMeta(meta)
        return "Deleted ${file.name}"
    }

    // ── Auto session handoff switch ───────────────────────────
    // The app (not the model) records a rolling snapshot of recent exchanges at the top of the
    // active memory. Default ON; the settings page exposes the toggle.

    @Synchronized
    fun isSessionHandoffEnabled(): Boolean =
        if (sessionHandoffFlagFile.exists()) sessionHandoffFlagFile.readText().trim() != "false" else true

    @Synchronized
    fun setSessionHandoffEnabled(enabled: Boolean) {
        if (enabled) sessionHandoffFlagFile.delete() else sessionHandoffFlagFile.writeText("false")
    }

    /**
     * Auto session handoff: the app (not the model) records a rolling snapshot of recent
     * exchanges at the TOP of the active memory, so "what was just said" survives even
     * when a reply was interrupted by an exception. Bounded: newest entries first, oldest
     * entries dropped once the entry/char limits are exceeded.
     */
    @Synchronized
    fun appendSessionHandoff(
        conversationId: String,
        title: String?,
        userText: String?,
        replyExcerpt: String?,
        statusTag: String
    ) {
        if (!isSessionHandoffEnabled()) return
        fun sanitize(value: String?, max: Int): String =
            value?.replace(Regex("\\s+"), " ")?.trim()?.take(max).orEmpty()
        val user = sanitize(userText, 160)
        val reply = sanitize(replyExcerpt, 240)
        if (user.isEmpty() && reply.isEmpty()) return
        val t = sanitize(title, 40)
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))
        val entry = buildString {
            append("- ").append(time)
            if (t.isNotEmpty()) append(" 「").append(t).append("」")
            append(" ").append(statusTag)
            if (user.isNotEmpty()) append("｜用户：").append(user)
            if (reply.isNotEmpty()) append("｜回复：").append(reply)
        }
        val startMarker = "<!-- agora:auto-session:start -->"
        val endMarker = "<!-- agora:auto-session:end -->"
        val header = "$startMarker\n## 最近会话（自动记录，最新在上）\n"
        val existing = getActiveMemory()
        val startIdx = existing.indexOf(startMarker)
        val rawEndIdx = existing.indexOf(endMarker)
        val newContent: String
        if (startIdx >= 0 && rawEndIdx > startIdx) {
            val endIdx = rawEndIdx + endMarker.length
            val head = existing.substring(0, startIdx)
            val tail = existing.substring(endIdx)
            val lines = existing.substring(startIdx, endIdx).lines()
                .filter { it.startsWith("- ") }
                .toMutableList()
            lines.add(0, entry)
            while (lines.size > 1 && (lines.size > 8 || lines.joinToString("\n").length > 4000)) {
                lines.removeAt(lines.lastIndex)
            }
            newContent = head + header + lines.joinToString("\n") + "\n" + endMarker + tail
        } else {
            val rest = if (existing.isBlank()) "" else "\n" + existing
            newContent = header + entry + "\n" + endMarker + rest
        }
        activeMemoryFile.writeText(newContent)
    }

    private fun resolveFile(name: String): File {
        val fileSafeName = name.replace(Regex("""[/\\]"""), "_")
        val file = File(memoryDir, if (fileSafeName.endsWith(".md")) fileSafeName else "$fileSafeName.md")
        val canonicalPath = file.canonicalPath
        val canonicalDir = memoryDir.canonicalPath
        if (!canonicalPath.startsWith(canonicalDir)) {
            throw IllegalArgumentException("Invalid file name: $name")
        }
        return file
    }
}
