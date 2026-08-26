package com.newoether.agora.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MemoryManager(context: Context) {
    private val memoryDir = File(context.filesDir, "memory_db").also { it.mkdirs() }
    private val activeMemoryFile = File(context.filesDir, "active_memory.md")
    private val metaFile = File(memoryDir, "memory_meta.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    data class MemoryFileInfo(val name: String, val description: String = "")

    @Synchronized fun getActiveMemory(): String = if (activeMemoryFile.exists()) activeMemoryFile.readText() else ""

    @Synchronized fun updateActiveMemory(content: String, mode: String = "replace", oldString: String? = null, newString: String? = null): String {
        val existing = getActiveMemory()
        val next = when (mode) {
            "append" -> if (existing.isEmpty()) content else "$existing\n$content"
            "prepend" -> if (existing.isEmpty()) content else "$content\n$existing"
            "patch" -> {
                require(oldString != null) { "old_string is required for patch mode" }
                require(existing.countOccurrences(oldString) == 1) { "old_string must match exactly once in active memory" }
                existing.replace(oldString, newString ?: "")
            }
            "replace" -> content
            else -> throw IllegalArgumentException("Unknown active memory mode: $mode")
        }
        atomicWrite(activeMemoryFile, next)
        check(getActiveMemory() == next) { "Active memory read-back verification failed" }
        return "Active memory updated ($mode)."
    }

    @Synchronized private fun loadMeta(): MutableMap<String, String> = if (metaFile.exists()) runCatching { json.decodeFromString<MutableMap<String, String>>(metaFile.readText()) }.getOrElse { mutableMapOf() } else mutableMapOf()
    @Synchronized private fun saveMeta(meta: Map<String, String>) = atomicWrite(metaFile, json.encodeToString(meta))
    @Synchronized fun getDescription(name: String): String { val f=resolveFile(name); return if(f.exists()) loadMeta()[f.name].orEmpty() else "" }
    @Synchronized fun setDescription(name: String, description: String) { val f=resolveFile(name); require(f.exists()) { "File not found: $name" }; val m=loadMeta(); if(description.isBlank())m.remove(f.name)else m[f.name]=description;saveMeta(m) }
    @Synchronized fun listFiles(): List<MemoryFileInfo> { val m=loadMeta(); return memoryDir.listFiles()?.filter{it.extension=="md"}?.map{MemoryFileInfo(it.name,m[it.name].orEmpty())}?.sortedBy{it.name}?:emptyList() }
    @Synchronized fun getMetaJson(): String = if(metaFile.exists())metaFile.readText() else "{}"
    @Synchronized fun saveMetaJson(jsonStr:String) { json.parseToJsonElement(jsonStr); atomicWrite(metaFile,jsonStr) }

    @Synchronized fun readFile(name:String):String { val f=resolveFile(name); require(f.exists()){ "File not found: $name" }; return f.readText() }
    @Synchronized fun createFile(name:String,content:String,description:String=""):String { val f=resolveFile(name); require(!f.exists()){ "File already exists: ${f.name}" }; atomicWrite(f,content); check(readFile(name)==content){"Memory file read-back verification failed"}; if(description.isNotBlank()){val m=loadMeta();m[f.name]=description;saveMeta(m)};return "Created ${f.name}" }
    @Synchronized fun editFile(name:String,content:String?=null,newName:String?=null,description:String?=null,oldString:String?=null,newString:String?=null):String { val f=resolveFile(name);require(f.exists()){"File not found: $name"};require(!(content!=null&&oldString!=null)){"content and old_string are mutually exclusive"};var target=f; if(oldString!=null){val old=f.readText();require(old.countOccurrences(oldString)==1){"old_string must match exactly once in ${f.name}"};atomicWrite(f,old.replace(oldString,newString?:""))}else if(content!=null)atomicWrite(f,content);if(newName!=null&&newName!=name){target=resolveFile(newName);require(!target.exists()){"Target file already exists: ${target.name}"};require(f.renameTo(target)){"Could not rename ${f.name}"};val m=loadMeta();m.remove(f.name)?.let{m[target.name]=it};saveMeta(m)};if(description!=null){val m=loadMeta();if(description.isBlank())m.remove(target.name)else m[target.name]=description;saveMeta(m)};return "Updated ${target.name}" }
    @Synchronized fun deleteFile(name:String):String { val f=resolveFile(name);require(f.exists()){"File not found: $name"};require(f.delete()){"Could not delete ${f.name}"};val m=loadMeta();m.remove(f.name);saveMeta(m);return "Deleted ${f.name}" }

    private fun atomicWrite(file:File,text:String){ val tmp=File(file.parentFile,file.name+".tmp-${Thread.currentThread().id}");tmp.writeText(text);tmp.outputStream().fd.sync();require(tmp.renameTo(file)){tmp.delete();throw IllegalStateException("Could not atomically write ${file.name}")} }
    private fun String.countOccurrences(sub:String):Int { var n=0;var p=0;while(true){p=indexOf(sub,p);if(p<0)return n;n++;p+=sub.length} }
    private fun resolveFile(name:String):File { require(name.isNotBlank()){"Invalid file name"};val safe=name.replace(Regex("[/\\\\]"),"_");val f=File(memoryDir,if(safe.endsWith(".md"))safe else "$safe.md");require(f.canonicalPath.startsWith(memoryDir.canonicalPath)){"Invalid file name: $name"};return f }
}
