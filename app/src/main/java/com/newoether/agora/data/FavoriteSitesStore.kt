package com.newoether.agora.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Where a favorite-site entry comes from / belongs to. */
@Serializable
enum class SiteCategory { MINE, EXTERNAL, OTHER }

@Serializable
data class FavoriteSiteItem(
    val id: String,
    val title: String,
    val url: String,
    val category: SiteCategory = SiteCategory.OTHER,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Persistent "favorite sites" list, stored as one JSON document in app-private storage.
 * Mirrors [RoadmapStore]'s posture: whole-file read/write under a single lock, corrupt or
 * missing file never crashes. Difference from RoadmapStore: on the very first run (file
 * absent) the store seeds a built-in default list (the project's repo navigation table),
 * which the user can then freely extend, edit, or delete through the UI or the sites_*
 * tools — the defaults are initial data, not a fixed set. A corrupt file degrades to an
 * empty list and never re-seeds, so duplicates cannot appear after a crash.
 */
class FavoriteSitesStore(private val filesDir: File) {

    @Serializable
    private data class SitesDocument(val items: List<FavoriteSiteItem> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val file: File get() = File(filesDir.apply { mkdirs() }, "favorite_sites.json")

    fun list(): List<FavoriteSiteItem> = synchronized(lock) {
        if (!file.exists()) {
            val seeded = SitesDocument(DEFAULT_SITES)
            write(seeded)
            seeded.items
        } else {
            read().items
        }
    }

    fun add(title: String, url: String, category: SiteCategory, note: String): FavoriteSiteItem =
        synchronized(lock) {
            val doc = read()
            val now = System.currentTimeMillis()
            val item = FavoriteSiteItem(
                id = "site_${now.toString(36)}_${(0..0xFFFF).random().toString(36)}",
                title = title.trim(),
                url = url.trim(),
                category = category,
                note = note.trim(),
                createdAt = now,
                updatedAt = now
            )
            write(doc.copy(items = doc.items + item))
            item
        }

    fun update(
        id: String,
        title: String? = null,
        url: String? = null,
        category: SiteCategory? = null,
        note: String? = null
    ): FavoriteSiteItem? = synchronized(lock) {
        val doc = read()
        val index = doc.items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = doc.items[index]
        val updated = old.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: old.title,
            url = url?.trim()?.takeIf { it.isNotEmpty() } ?: old.url,
            category = category ?: old.category,
            note = note?.trim() ?: old.note,
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

    private fun read(): SitesDocument =
        if (!file.exists()) SitesDocument()
        else runCatching { json.decodeFromString<SitesDocument>(file.readText()) }
            .getOrElse { SitesDocument() }

    private fun write(doc: SitesDocument) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(doc))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(doc))
            tmp.delete()
        }
    }

    companion object {
        /**
         * Built-in initial list (project repo navigation table, user-provided 2026-09-11).
         * Seeded once on first run only; fully user-editable afterwards.
         */
        val DEFAULT_SITES: List<FavoriteSiteItem> = listOf(
            seed("01", SiteCategory.MINE, "xf8410 账户主页", "https://github.com/xf8410", "100 仓母体，全量清单见 repo-audit-index"),
            seed("02", SiteCategory.MINE, "bestsoccer-gala", "https://github.com/xf8410/bestsoccer-gala", "足球复刻·参考数据主仓（Python）"),
            seed("03", SiteCategory.MINE, "bestsoccor-rebuild", "https://github.com/xf8410/bestsoccor-rebuild", "足球复刻·壳线主仓（交接文件 notes/）"),
            seed("04", SiteCategory.MINE, "hlpatch", "https://github.com/xf8410/hlpatch", "本地 hlpatch（Rust）"),
            seed("05", SiteCategory.MINE, "uma-patcher-gala", "https://github.com/xf8410/uma-patcher-gala", "赛马娘插件打包器（Kotlin）"),
            seed("06", SiteCategory.MINE, "hachimi-template-gala", "https://github.com/xf8410/hachimi-template-gala", "FairGuard 运行时取证插件线（Rust）"),
            seed("07", SiteCategory.MINE, "uma-ramen-nn-lab", "https://github.com/xf8410/uma-ramen-nn-lab", "NN 主实验室，009b 冠军 66734.8"),
            seed("08", SiteCategory.MINE, "uma-ramen-teacher-lab", "https://github.com/xf8410/uma-ramen-teacher-lab", "教师成绩单仓（EXP-013）"),
            seed("09", SiteCategory.MINE, "uma-ramen-nn-lab-exp010", "https://github.com/xf8410/uma-ramen-nn-lab-exp010", "EXP-010 中间产物仓（已归档）"),
            seed("10", SiteCategory.MINE, "uma-juece", "https://github.com/xf8410/uma-juece", "决策浮窗母体（Java）"),
            seed("11", SiteCategory.MINE, "uma-juece-ramen", "https://github.com/xf8410/uma-juece-ramen", "拉面杯决策浮窗（Rust）"),
            seed("12", SiteCategory.MINE, "umaai-rs (fork)", "https://github.com/xf8410/umaai-rs", "上游 xulai1001/umaai-rs 的 fork"),
            seed("13", SiteCategory.MINE, "umamusume-scenario-mechanics", "https://github.com/xf8410/umamusume-scenario-mechanics", "剧本机制仓"),
            seed("14", SiteCategory.EXTERNAL, "xulai1001 主页", "https://github.com/xulai1001", "上游协作者主页（C# 插件线）"),
            seed("15", SiteCategory.EXTERNAL, "xulai1001/umaai-rs", "https://github.com/xulai1001/umaai-rs", "上游 Rust 主线"),
            seed("16", SiteCategory.EXTERNAL, "EventLoggerPlugin", "https://github.com/xulai1001/EventLoggerPlugin", "上游 C# 插件线"),
            seed("17", SiteCategory.EXTERNAL, "UmamusumeResponseAnalyzer", "https://github.com/xulai1001/UmamusumeResponseAnalyzer", "上游 C# 插件线"),
            seed("18", SiteCategory.EXTERNAL, "RamenScenarioAnalyzer", "https://github.com/xulai1001/RamenScenarioAnalyzer", "上游 C# 插件线"),
            seed("19", SiteCategory.EXTERNAL, "SendGameStatusPlugin", "https://github.com/xulai1001/SendGameStatusPlugin", "上游 C# 插件线"),
            seed("20", SiteCategory.EXTERNAL, "muxueliunian 主页", "https://github.com/muxueliunian", "协作者主页"),
            seed("21", SiteCategory.EXTERNAL, "umaai-rs-muxue", "https://github.com/muxueliunian/umaai-rs-muxue", "暮雪流年的 umaai-rs 分支"),
            seed("22", SiteCategory.EXTERNAL, "kairusds 主页", "https://github.com/kairusds", "协作者主页（Hachimi-Edge 源头）"),
            seed("23", SiteCategory.EXTERNAL, "yingyingyingqwq 主页", "https://github.com/yingyingyingqwq", "协作者主页"),
            seed("24", SiteCategory.EXTERNAL, "EtherealAO 主页", "https://github.com/EtherealAO", "协作者主页"),
            seed("25", SiteCategory.EXTERNAL, "hzyhhzy/UmaAi", "https://github.com/hzyhhzy/UmaAi", "外部参考仓")
        )

        private fun seed(
            n: String,
            category: SiteCategory,
            title: String,
            url: String,
            note: String
        ) = FavoriteSiteItem(
            id = "site_seed_$n",
            title = title,
            url = url,
            category = category,
            note = note,
            createdAt = 0L,
            updatedAt = 0L
        )
    }
}
