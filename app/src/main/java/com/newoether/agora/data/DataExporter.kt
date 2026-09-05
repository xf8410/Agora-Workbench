package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.encodeToString
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataExporter(
    private val context: Context,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager
) {
    private companion object {
        const val EXPORT_PAGE_SIZE = 100

        /**
         * Backup format version. 3 adds the draftText/draftAttachments columns on conversations.
         * Version 2 readers ignore unknown JSON keys, so a v3 backup still imports into older
         * builds; v3 readers accept both because every extra field has a default.
         */
        const val BACKUP_FORMAT_VERSION = 3
    }

    enum class ExportCategory(val manifestKey: String) {
        CONVERSATIONS("conversations"),
        MEMORIES("memories"),
        SYSTEM_PROMPTS("system_prompts"),
        SETTINGS("settings"),
        API_KEYS("api_keys");

        companion object {
            fun fromManifestKey(key: String): ExportCategory? =
                entries.find { it.manifestKey == key }
        }
    }

    @Serializable
    private data class ExportManifest(
        @SerialName("agora_export_version") val version: Int,
        @SerialName("app_version") val appVersion: String,
        @SerialName("exported_at") val exportedAt: String,
        val categories: List<String>,
        @SerialName("has_api_keys") val hasApiKeys: Boolean = false
    )

    @Serializable
    private data class ExportConversations(
        val conversations: List<ExportChatEntity>,
        val messages: List<ExportMessageEntity>,
        val tasks: List<ExportTaskEntity> = emptyList(),
        val loops: List<ExportLoopEntity> = emptyList()
    )

    @Serializable
    private data class ExportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val taskId: String? = null,
        val origin: String = "user",
        val graduated: Boolean = false,
        /** Unsent composer text (v3). Older backups decode as empty. */
        @SerialName("draft_text") val draftText: String = "",
        /** JSON list of SelectedAttachment (v3). */
        @SerialName("draft_attachments") val draftAttachments: String? = null
    )

    @Serializable
    private data class ExportTaskEntity(
        val id: String,
        val name: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val modelId: String? = null,
        val cronExpr: String,
        /** Informational snapshot; importers recompute this device-local derived value. */
        val nextRunAt: Long,
        val enabled: Boolean = true,
        val createdAt: Long,
        val lastRunAt: Long? = null
    )

    @Serializable
    private data class ExportLoopEntity(
        val conversationId: String,
        val intervalMs: Long,
        val prompt: String? = null,
        val nextFireAt: Long,
        val cycleCount: Int = 0,
        /** New v2 archives always emit the bounded default for legacy null values. */
        val maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
        val active: Boolean = true,
        val revision: Long = 0L
    )

    @Serializable
    private data class ExportMessageEntity(
        val id: String,
        val conversationId: String,
        val parentId: String? = null,
        val text: String,
        val images: List<String> = emptyList(),
        val thoughts: String? = null,
        val thoughtTitle: String? = null,
        val tokenCount: Int = 0,
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null
    )

    @Serializable
    private data class ExportSettings(
        val selectedModel: String,
        val availableModels: Map<String, List<String>>,
        val enabledModels: Set<String>,
        val modelAliases: Map<String, String>,
        val maxContextWindow: Int,
        val visualizeContextRollout: Boolean,
        val codeExecutionEnabled: Boolean,
        val googleSearchEnabled: Boolean,
        val thinkingEnabled: Boolean,
        val thinkingLevel: String,
        val thinkingBudgetEnabled: Boolean,
        val thinkingBudgetTokens: Int,
        val autoCacheEnabled: Boolean,
        val providerBaseUrls: Map<String, String>,
        val titleGenerationEnabled: Boolean,
        val titleGenerationModel: String?,
        val titleGenerationPrompt: String? = null,
        val accessPastConversations: Boolean,
        val accessSavedMemories: Boolean,
        val accessActiveMemory: Boolean,
        val ragSearchEnabled: Boolean,
        val modelSearchMethod: String,
        val manualSearchMethod: String,
        val embeddingModels: List<EmbeddingModelConfig>,
        val activeEmbeddingModelId: String,
        val appLanguage: String,
        val webSearchEnabled: Boolean,
        val webSearchProvider: String,
        val webSearchBaseUrl: String,
        val ragThreshold: Float,
        val shellEnabled: Boolean = false,
        val shellDevices: List<ShellDeviceConfig> = emptyList(),
        val customProviders: List<CustomProviderConfig> = emptyList(),
        val localChatModels: List<LocalChatModelConfig>,
        @SerialName("active_system_prompt_id") val activeSystemPromptId: String?
    )

    @Serializable
    private data class ExportApiKeys(
        val apiKeys: List<ApiKeyEntry>,
        val activeApiKeyIds: Map<String, String>,
        val webSearchApiKeys: Map<String, String>,
        val shellApiKeys: Map<String, String> = emptyMap()
    )

    data class ExportResult(
        val imagesExported: Int = 0
    )

    private fun openImageStream(imgUri: String): java.io.InputStream? {
        val uri = Uri.parse(imgUri)
        // Handle content:// and file:// URIs
        if (uri.scheme == "content" || uri.scheme == "file") {
            return try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        }
        // Handle bare file paths (from processImages)
        val file = java.io.File(imgUri)
        if (file.exists()) return try { file.inputStream() } catch (_: Exception) { null }
        return null
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun export(
        uri: Uri,
        categories: Set<ExportCategory>,
        includeApiKeys: Boolean,
        onProgress: (Float) -> Unit = {}
    ): ExportResult {
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = appInfo.versionName ?: "unknown"
        val exportedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date())

        val manifest = ExportManifest(
            version = BACKUP_FORMAT_VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt,
            categories = categories.map { it.manifestKey },
            hasApiKeys = includeApiKeys && categories.contains(ExportCategory.API_KEYS)
        )

        var imagesExportedTotal = 0
        val totalSteps = categories.size + 1 // +1 for manifest
        var completed = 0
        fun step() { completed++; onProgress(completed.toFloat() / totalSteps) }

        context.contentResolver.openOutputStream(uri)?.use { raw ->
            val zip = ZipOutputStream(BufferedOutputStream(raw))

            // Manifest
            zip.putNextEntry(ZipEntry("manifest.json"))
            Json.encodeToStream(manifest, zip)
            zip.closeEntry()
            step()

            // Conversations
            if (ExportCategory.CONVERSATIONS in categories) {
                val imageMap = mutableMapOf<String, List<String>>() // messageId -> list of image URIs to keep

                // The conversations block is by far the heaviest step (media scan + full message
                // dump), so subdivide its progress slice: 50% media scan, 50% message JSON dump.
                val stepWidth = 1f / totalSteps
                val convBase = 1f / totalSteps // progress already emitted by the manifest step
                val totalMediaRows = chatDao.getMediaMessageCount()
                val totalMessages = chatDao.getMessagesCount()

                // Media scan page by page with a lightweight projection (no message bodies).
                // Media bytes are STREAMED into the zip — never read fully into the heap, so a
                // large image/video can no longer OOM or stall the whole export.
                var mediaOffset = 0
                while (true) {
                    val mediaPage = chatDao.getMediaProjectionPage(EXPORT_PAGE_SIZE, mediaOffset)
                    if (mediaPage.isEmpty()) break
                    for (msg in mediaPage) {
                    val surviving = mutableListOf<String>()
                    for ((idx, imgUri) in msg.images.withIndex()) {
                        val inStream = openImageStream(imgUri)
                        if (inStream == null) continue
                        val copied: Long = inStream.use { stream ->
                            try {
                                zip.putNextEntry(ZipEntry("images/${msg.id}/$idx"))
                                stream.copyTo(zip)
                            } catch (_: Exception) {
                                runCatching { zip.closeEntry() }
                                0L
                            }
                        }
                        if (copied > 0) surviving.add(imgUri)
                    }
                    imagesExportedTotal += surviving.size
                    if (surviving.isNotEmpty()) {
                        imageMap[msg.id] = surviving
                    }

                    // Stream video files referenced by attachmentMeta
                    val meta = try {
                        msg.attachmentMeta?.let { Json.decodeFromString<AttachmentMeta>(it) }
                    } catch (_: Exception) { null }
                    if (meta != null) {
                        for (item in meta.items) {
                            if (item.type != "video" || item.originalUri.isNullOrBlank()) continue
                            val videoUri = item.originalUri
                            // Handle file:// URIs (local copies)
                            if (videoUri.startsWith("file://")) {
                                val filePath = videoUri.removePrefix("file://")
                                val file = java.io.File(filePath)
                                if (file.exists()) {
                                    try {
                                        file.inputStream().use { stream ->
                                            zip.putNextEntry(ZipEntry("videos/${msg.id}/${item.imageIndex ?: 0}"))
                                            stream.copyTo(zip)
                                            zip.closeEntry()
                                        }
                                    } catch (_: Exception) {
                                        runCatching { zip.closeEntry() }
                                    }
                                }
                            }
                        }
                    }
                    }
                    mediaOffset += mediaPage.size
                    if (mediaPage.size < EXPORT_PAGE_SIZE) break
                    if (totalMediaRows > 0) {
                        val frac = minOf(1f, mediaOffset.toFloat() / totalMediaRows)
                        onProgress(convBase + stepWidth * 0.5f * frac)
                    }
                }

                val conversations = chatDao.getAllConversationsList().map { c ->
                    ExportChatEntity(
                        id = c.id,
                        title = c.title,
                        lastUpdated = c.lastUpdated,
                        selectedBranchesJson = c.selectedBranchesJson,
                        systemPromptId = c.systemPromptId,
                        modelId = c.modelId,
                        taskId = c.taskId,
                        origin = c.origin,
                        graduated = c.graduated,
                        draftText = c.draftText,
                        draftAttachments = c.draftAttachments
                    )
                }
                val tasks = chatDao.getAllTasksList().map { task ->
                    ExportTaskEntity(
                        id = task.id,
                        name = task.name,
                        prompt = task.prompt,
                        systemPrompt = task.systemPrompt,
                        modelId = task.modelId,
                        cronExpr = task.cronExpr,
                        nextRunAt = task.nextRunAt,
                        enabled = task.enabled,
                        createdAt = task.createdAt,
                        lastRunAt = task.lastRunAt
                    )
                }
                val loops = chatDao.getAllLoopsList().map { loop ->
                    val disarmed = disarmImportedLoop(loop)
                    ExportLoopEntity(
                        conversationId = disarmed.conversationId,
                        intervalMs = disarmed.intervalMs,
                        prompt = disarmed.prompt,
                        nextFireAt = disarmed.nextFireAt,
                        cycleCount = disarmed.cycleCount,
                        maxCycles = disarmed.maxCycles,
                        active = disarmed.active,
                        revision = disarmed.revision
                    )
                }
                zip.putNextEntry(ZipEntry("conversations.json"))
                fun writeJson(value: String) = zip.write(value.toByteArray(Charsets.UTF_8))
                writeJson("{\"conversations\":")
                writeJson(Json.encodeToString(conversations))
                writeJson(",\"messages\":[")
                var messageOffset = 0
                var firstMessage = true
                while (true) {
                    val page = chatDao.getMessagesPageByRowid(EXPORT_PAGE_SIZE, messageOffset)
                    if (page.isEmpty()) break
                    for (m in page) {
                        if (!firstMessage) writeJson(",")
                        firstMessage = false
                        val exportedImages = imageMap[m.id] ?: emptyList()
                        val exported = ExportMessageEntity(m.id, m.conversationId, m.parentId, m.text,
                            exportedImages, m.thoughts, m.thoughtTitle, m.tokenCount, m.status.name,
                            m.participant.name, m.timestamp, m.thoughtTimeMs, m.modelName,
                            m.toolCallJson, m.attachmentMeta)
                        writeJson(Json.encodeToString(exported))
                    }
                    messageOffset += page.size
                    if (page.size < EXPORT_PAGE_SIZE) break
                    if (totalMessages > 0) {
                        val frac = minOf(1f, messageOffset.toFloat() / totalMessages)
                        onProgress(convBase + stepWidth * (0.5f + 0.5f * frac))
                    }
                }
                writeJson("],\"tasks\":")
                writeJson(Json.encodeToString(tasks))
                writeJson(",\"loops\":")
                writeJson(Json.encodeToString(loops))
                writeJson("}")
                zip.closeEntry()
                step()
            }

            // Memories
            if (ExportCategory.MEMORIES in categories) {
                val activeMemory = memoryManager.getActiveMemory()
                if (activeMemory.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry("memories/active_memory.md"))
                    zip.write(activeMemory.toByteArray())
                    zip.closeEntry()
                }
                for (file in memoryManager.listFiles()) {
                    val content = memoryManager.readFile(file.name)
                    zip.putNextEntry(ZipEntry("memories/memory_db/${file.name}"))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                val metaJson = memoryManager.getMetaJson()
                if (metaJson != "{}") {
                    zip.putNextEntry(ZipEntry("memories/memory_db/memory_meta.json"))
                    zip.write(metaJson.toByteArray())
                    zip.closeEntry()
                }
                step()
            }

            // System Prompts
            if (ExportCategory.SYSTEM_PROMPTS in categories) {
                val prompts = settingsManager.systemPrompts.first()
                zip.putNextEntry(ZipEntry("system_prompts.json"))
                Json.encodeToStream(prompts, zip)
                zip.closeEntry()
                step()
            }

            // Settings
            if (ExportCategory.SETTINGS in categories) {
                val settings = ExportSettings(
                    selectedModel = settingsManager.selectedModel.first(),
                    availableModels = settingsManager.availableModels.first(),
                    enabledModels = settingsManager.enabledModels.first(),
                    modelAliases = settingsManager.modelAliases.first(),
                    maxContextWindow = settingsManager.maxContextWindow.first(),
                    visualizeContextRollout = settingsManager.visualizeContextRollout.first(),
                    codeExecutionEnabled = settingsManager.codeExecutionEnabled.first(),
                    googleSearchEnabled = settingsManager.googleSearchEnabled.first(),
                    thinkingEnabled = settingsManager.thinkingEnabled.first(),
                    thinkingLevel = settingsManager.thinkingLevel.first(),
                    thinkingBudgetEnabled = settingsManager.thinkingBudgetEnabled.first(),
                    thinkingBudgetTokens = settingsManager.thinkingBudgetTokens.first(),
                    autoCacheEnabled = settingsManager.autoCacheEnabled.first(),
                    providerBaseUrls = settingsManager.providerBaseUrls.first(),
                    titleGenerationEnabled = settingsManager.titleGenerationEnabled.first(),
                    titleGenerationModel = settingsManager.titleGenerationModel.first(),
                    titleGenerationPrompt = settingsManager.titleGenerationPrompt.first(),
                    accessPastConversations = settingsManager.accessPastConversations.first(),
                    accessSavedMemories = settingsManager.accessSavedMemories.first(),
                    accessActiveMemory = settingsManager.accessActiveMemory.first(),
                    ragSearchEnabled = settingsManager.ragSearchEnabled.first(),
                    modelSearchMethod = settingsManager.modelSearchMethod.first(),
                    manualSearchMethod = settingsManager.manualSearchMethod.first(),
                    embeddingModels = settingsManager.embeddingModels.first().map { it.copy(localFilePath = "") },
                    activeEmbeddingModelId = "", // cleared — embedding models are local GGUF, don't transfer
                    appLanguage = settingsManager.appLanguage.first(),
                    webSearchEnabled = settingsManager.webSearchEnabled.first(),
                    webSearchProvider = settingsManager.webSearchProvider.first(),
                    webSearchBaseUrl = settingsManager.webSearchBaseUrl.first(),
                    ragThreshold = settingsManager.ragThreshold.first(),
                    shellEnabled = settingsManager.shellEnabled.first(),
                    shellDevices = settingsManager.shellDevices.first().map { d ->
                        if (includeApiKeys) d else d.copy(apiKey = "")
                    },
                    customProviders = settingsManager.customProviders.first(),
                    localChatModels = settingsManager.localChatModels.first().map { it.copy(localFilePath = "") },
                    activeSystemPromptId = settingsManager.activeSystemPromptId.first()
                )
                zip.putNextEntry(ZipEntry("settings.json"))
                Json.encodeToStream(settings, zip)
                zip.closeEntry()
                step()

                // Extra settings (separate file to keep data class size manageable)
                val extra = ExportExtraSettings.toJsonObject(settingsManager, includeApiKeys)
                zip.putNextEntry(ZipEntry("extra_settings.json"))
                Json.encodeToStream(extra, zip)
                zip.closeEntry()
            }

            // API Keys (opt-in)
            if (includeApiKeys && ExportCategory.API_KEYS in categories) {
                val keys = ExportApiKeys(
                    apiKeys = settingsManager.apiKeys.first(),
                    activeApiKeyIds = settingsManager.activeApiKeyIds.first(),
                    webSearchApiKeys = settingsManager.webSearchApiKeys.first(),
                    shellApiKeys = settingsManager.shellDevices.first()
                        .filter { it.apiKey.isNotBlank() }
                        .associate { it.name to it.apiKey }
                )
                zip.putNextEntry(ZipEntry("api_keys.json"))
                Json.encodeToStream(keys, zip)
                zip.closeEntry()
                step()
            }

            // ── Custom font file ──
            val fontPath = settingsManager.customFontPath.first()
            if (fontPath.isNotBlank()) {
                val fontFile = File(fontPath)
                if (fontFile.exists()) {
                    zip.putNextEntry(ZipEntry("custom_font/${fontFile.name}"))
                    fontFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            zip.finish()
            zip.flush()
        }

        onProgress(1f)
        return ExportResult(imagesExported = imagesExportedTotal)
    }
}
