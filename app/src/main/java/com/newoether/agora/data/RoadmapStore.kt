package com.newoether.agora.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Lifecycle of a planned-feature entry. */
@Serializable
enum class RoadmapStatus { TODO, DOING, DONE }

@Serializable
enum class RoadmapPriority { HIGH, NORMAL, LOW }

@Serializable
data class RoadmapItem(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: RoadmapStatus = RoadmapStatus.TODO,
    val priority: RoadmapPriority = RoadmapPriority.NORMAL,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Persistent "planned features" (roadmap) list, stored as one JSON document in app-private
 * storage. Entries are few by nature (a personal feature backlog), so whole-file read/write
 * under a single lock is simpler and safer than a Room migration; volume growth is a
 * non-issue by design. A corrupt or missing file degrades to an empty list instead of
 * crashing (consistent with the memory store's resilience posture).
 */
class RoadmapStore(private val filesDir: File) {

    @Serializable
    private data class RoadmapDocument(val items: List<RoadmapItem> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val file: File get() = File(filesDir.apply { mkdirs() }, "roadmap_items.json")

    fun list(): List<RoadmapItem> = synchronized(lock) { read().items }

    fun add(title: String, detail: String, priority: RoadmapPriority): RoadmapItem = synchronized(lock) {
        val doc = read()
        val now = System.currentTimeMillis()
        val item = RoadmapItem(
            id = "rm_${now.toString(36)}_${(0..0xFFFF).random().toString(36)}",
            title = title.trim(),
            detail = detail.trim(),
            priority = priority,
            createdAt = now,
            updatedAt = now
        )
        write(doc.copy(items = listOf(item) + doc.items))
        item
    }

    fun update(
        id: String,
        title: String? = null,
        detail: String? = null,
        status: RoadmapStatus? = null,
        priority: RoadmapPriority? = null
    ): RoadmapItem? = synchronized(lock) {
        val doc = read()
        val index = doc.items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = doc.items[index]
        val updated = old.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: old.title,
            detail = detail?.trim() ?: old.detail,
            status = status ?: old.status,
            priority = priority ?: old.priority,
            updatedAt = System.currentTimeMillis()
        )
        write(doc.copy(items = doc.items.toMutableList().also { it[index] = updated }))
        updated
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val doc = read()
        val next = doc.items.filterNot { it.id == id }
        if (next.size == doc.items.size) return false
        write(doc.copy(items = next))
        true
    }

    private fun read(): RoadmapDocument =
        if (!file.exists()) RoadmapDocument()
        else runCatching { json.decodeFromString<RoadmapDocument>(file.readText()) }
            .getOrElse { RoadmapDocument() }

    private fun write(doc: RoadmapDocument) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(doc))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(doc))
            tmp.delete()
        }
    }
}
