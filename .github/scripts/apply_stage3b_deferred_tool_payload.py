#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def replace(path, old, new):
    p = ROOT / path
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        # The target branch may gain compatible edits between resumptions. Keep applying the
        # remaining independent hunks; compilation/tests will validate that no required API was
        # skipped. This also makes the one-shot applicator idempotent after a partially applied run.
        print(f"warning: source marker already changed in {path}: {old[:100]!r}")
        return
    p.write_text(text.replace(old, new, 1))

def write(path, content):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)

# Model marker used only by lightweight list placeholders.
replace(
    "app/src/main/java/com/newoether/agora/model/ChatMessage.kt",
    "    val toolStatus: ToolExecutionStatus? = null,\n)",
    "    val toolStatus: ToolExecutionStatus? = null,\n    /** True when list rendering intentionally omitted the full tool payload. */\n    val payloadDeferred: Boolean = false,\n)",
)

# Pure policy: keep only bounded payloads in the normal list cursor; large payloads are loaded by id.
write("app/src/main/java/com/newoether/agora/model/ToolPayloadPolicy.kt", '''package com.newoether.agora.model

/** Policy for keeping large tool JSON out of the normal paged chat query. */
object ToolPayloadPolicy {
    const val MAX_INLINE_JSON_CHARS: Int = 4_096

    fun shouldDefer(jsonLength: Int?): Boolean =
        jsonLength != null && jsonLength > MAX_INLINE_JSON_CHARS

    fun deferredSegments(): List<MessageSegment> = listOf(
        MessageSegment(type = "tool", payloadDeferred = true)
    )
}
''')

# Add a Room projection. It contains every lightweight message field, but never exposes a large
# toolCallJson value through CursorWindow.
replace(
    "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt",
    ")\n\n/** Lightweight projection for startup cleanup; never loads message bodies. */\ndata class MessageImagesProjection(",
    ")\n\n/** Normal chat-list row. Full tool payloads are deliberately excluded when large. */\ndata class MessageListRow(\n    val id: String,\n    val conversationId: String,\n    val parentId: String?,\n    val text: String,\n    val images: List<String>,\n    val thoughts: String?,\n    val thoughtTitle: String?,\n    val tokenCount: Int,\n    val status: MessageStatus,\n    val participant: Participant,\n    val timestamp: Long,\n    val thoughtTimeMs: Long?,\n    val modelName: String?,\n    val toolCallSummaryJson: String?,\n    val toolPayloadAvailable: Boolean,\n    val attachmentMeta: String?,\n)\n\n/** Lightweight projection for startup cleanup; never loads message bodies. */\ndata class MessageImagesProjection(",
)
replace(
    "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt",
    "          CASE WHEN toolCallJson IS NOT NULL AND length(toolCallJson) > :maxToolJsonChars\n            THEN NULL ELSE toolCallJson END AS toolCallJson,\n          CASE WHEN attachmentMeta IS NOT NULL AND length(attachmentMeta) > :maxAttachmentMetaChars",
    "          CASE WHEN toolCallJson IS NOT NULL AND length(toolCallJson) <= :maxToolSummaryChars\n            THEN toolCallJson ELSE NULL END AS toolCallSummaryJson,\n          CASE WHEN toolCallJson IS NOT NULL THEN 1 ELSE 0 END AS toolPayloadAvailable,\n          CASE WHEN attachmentMeta IS NOT NULL AND length(attachmentMeta) > :maxAttachmentMetaChars",
)
replace(
    "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt",
    "        maxToolJsonChars: Int = 131072,\n        maxAttachmentMetaChars: Int = 32768,\n    ): Flow<List<MessageEntity>>",
    "        maxToolSummaryChars: Int = 4096,\n        maxAttachmentMetaChars: Int = 32768,\n    ): Flow<List<MessageListRow>>",
)
replace(
    "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt",
    "    @Query(\"SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId\")",
    "    /** Full payload query used only after the user opens tool details. */\n    @Query(\"SELECT toolCallJson FROM messages WHERE id = :messageId LIMIT 1\")\n    suspend fun getToolCallJson(messageId: String): String?\n\n    @Query(\"SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId\")",
)

# Repository API exposes bounded rows and a background-decoded on-demand payload.
replace(
    "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt",
    "import com.newoether.agora.data.local.MessageEntity\n",
    "import com.newoether.agora.data.local.MessageEntity\nimport com.newoether.agora.data.local.MessageListRow\nimport com.newoether.agora.model.MessageSegment\nimport com.newoether.agora.model.ToolPayloadPolicy\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n",
)
replace(
    "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt",
    "        val messages = chatDao.getMessagesForConversation(id).first()",
    "        val messages = chatDao.getAllMessagesForConversation(id)",
)
replace(
    "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt",
    "    fun getMessagesForConversation(conversationId: String, limit: Int = 100): Flow<List<MessageEntity>> =",
    "    fun getMessagesForConversation(conversationId: String, limit: Int = 100): Flow<List<MessageListRow>> =",
)
replace(
    "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt",
    "            maxToolJsonChars = 131_072,",
    "            maxToolSummaryChars = ToolPayloadPolicy.MAX_INLINE_JSON_CHARS,",
)
replace(
    "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt",
    "    fun getMessageCountForConversation(conversationId: String): Flow<Int> =",
    "    suspend fun loadToolSegments(messageId: String): List<MessageSegment>? {\n        val raw = chatDao.getToolCallJson(messageId) ?: return null\n        return withContext(Dispatchers.Default) {\n            runCatching { Json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull()\n        }\n    }\n\n    fun getMessageCountForConversation(conversationId: String): Flow<Int> =",
)

# Map the bounded JSON when present, otherwise expose a generic deferred card. Do not parse large
# JSON on Main. Startup cleanup uses one SQL update instead of writing a truncated projection back.
replace(
    "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt",
    "import com.newoether.agora.model.ToolCallData\n",
    "import com.newoether.agora.model.ToolCallData\nimport com.newoether.agora.model.ToolPayloadPolicy\n",
)
replace(
    "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt",
    "stuckMessages.forEach { msg ->\n                                convRepo.upsertMessage(msg.copy(status = MessageStatus.STOPPED))\n                            }",
    "if (stuckMessages.isNotEmpty()) {\n                                convRepo.fixStuckMessages(id)\n                            }",
)
replace(
    "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt",
    "val decodedSegments = entity.toolCallJson?.let { raw ->",
    "val decodedSegments = entity.toolCallSummaryJson?.let { raw ->",
)
replace(
    "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt",
    "segments = decodedSegments ?: entity.thoughts\n                                            ?.takeIf { it.isNotBlank() }",
    "segments = decodedSegments\n                                            ?: if (entity.toolPayloadAvailable) ToolPayloadPolicy.deferredSegments() else null\n                                            ?: entity.thoughts\n                                            ?.takeIf { it.isNotBlank() }",
)
replace(
    "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt",
    "    fun regenerate(messageId: String) = generationController.regenerate(messageId)",
    "    suspend fun loadToolSegments(messageId: String): List<MessageSegment>? =\n        convRepo.loadToolSegments(messageId)\n\n    fun regenerate(messageId: String) = generationController.regenerate(messageId)",
)

# Thread the loader from the app down to the detail sheet.
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt",
    "                                onPdfPagesClick = { pages, idx -> haptics.action(); onPdfPagesClick?.invoke(pages, idx) },\n                                thoughtExpandedStates",
    "                                onPdfPagesClick = { pages, idx -> haptics.action(); onPdfPagesClick?.invoke(pages, idx) },\n                                loadToolSegments = viewModel::loadToolSegments,\n                                thoughtExpandedStates",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt",
    "import com.newoether.agora.model.MessageStatus\n",
    "import com.newoether.agora.model.MessageStatus\nimport com.newoether.agora.model.MessageSegment\n",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt",
    "    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,\n    thoughtExpandedStates:",
    "    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,\n    loadToolSegments: suspend (String) -> List<MessageSegment>? = { null },\n    thoughtExpandedStates:",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt",
    "                        onPdfPagesClick = onPdfPagesClick,\n                        onHeightChanged",
    "                        onPdfPagesClick = onPdfPagesClick,\n                        loadToolSegments = loadToolSegments,\n                        onHeightChanged",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
    "import com.newoether.agora.model.Participant\n",
    "import com.newoether.agora.model.Participant\nimport com.newoether.agora.model.MessageSegment\n",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
    "    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,\n    onHeightChanged:",
    "    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,\n    loadToolSegments: suspend (String) -> List<MessageSegment>? = { null },\n    onHeightChanged:",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
    "            markdownFlavour = markdownFlavour,\n            onDismiss",
    "            markdownFlavour = markdownFlavour,\n            loadToolSegments = loadToolSegments,\n            onDismiss",
)

# Sheet state: load only deferred payloads, show explicit loading/error states, and keep full text
# selectable/copyable after loading.
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
    "import androidx.compose.material3.HorizontalDivider\n",
    "import androidx.compose.material3.HorizontalDivider\nimport androidx.compose.material3.CircularProgressIndicator\n",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
    "import com.newoether.agora.model.ChatMessage\n",
    "import com.newoether.agora.model.ChatMessage\nimport com.newoether.agora.model.MessageSegment\n",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
    "    markdownFlavour: MarkdownFlavourDescriptor,\n    onDismiss: () -> Unit",
    "    markdownFlavour: MarkdownFlavourDescriptor,\n    loadToolSegments: suspend (String) -> List<MessageSegment>?,\n    onDismiss: () -> Unit",
)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
    "    val liveSegs = remember(message.segments) {\n        mergeAdjacentSegments(message.segments.orEmpty()).filter { it.type != \"answer\" }\n    }",
    "    val initialSegs = remember(message.segments) {\n        mergeAdjacentSegments(message.segments.orEmpty()).filter { it.type != \"answer\" }\n    }\n    var loadedSegs by remember(message.id) { mutableStateOf<List<MessageSegment>?>(null) }\n    var payloadLoading by remember(message.id) { mutableStateOf(false) }\n    var payloadLoadFailed by remember(message.id) { mutableStateOf(false) }\n    val needsPayload = initialSegs.any { it.payloadDeferred }\n    LaunchedEffect(message.id, needsPayload) {\n        if (needsPayload) {\n            payloadLoading = true\n            val full = loadToolSegments(message.id)\n            if (full == null) payloadLoadFailed = true\n            else loadedSegs = mergeAdjacentSegments(full).filter { it.type != \"answer\" }\n            payloadLoading = false\n        }\n    }\n    val liveSegs = loadedSegs ?: initialSegs",
)
old = """                                    if (detailSeg.type == \"tool\") {
                                        ToolDetailContent(detailSeg)
                                    } else if"""
new = """                                    if (detailSeg.type == \"tool\") {
                                        when {
                                            payloadLoading -> ToolPayloadLoading()
                                            payloadLoadFailed -> ToolPayloadLoadError()
                                            else -> SelectionContainer { ToolDetailContent(detailSeg) }
                                        }
                                    } else if"""
replace("app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt", old, new)
replace(
    "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
    "                            } else if (seg.type == \"tool\") {\n                                ToolDetailContent(seg)\n                            } else if",
    "                            } else if (seg.type == \"tool\") {\n                                when {\n                                    payloadLoading -> ToolPayloadLoading()\n                                    payloadLoadFailed -> ToolPayloadLoadError()\n                                    else -> SelectionContainer { ToolDetailContent(seg) }\n                                }\n                            } else if",
)
p = ROOT / "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt"
text = p.read_text()
if "private fun ToolPayloadLoading()" not in text:
    text += '''\n\n@Composable\nprivate fun ToolPayloadLoading() {\n    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {\n        CircularProgressIndicator()\n        Text(stringResource(R.string.tool_detail_loading), modifier = Modifier.padding(top = 12.dp))\n    }\n}\n\n@Composable\nprivate fun ToolPayloadLoadError() {\n    Text(\n        text = stringResource(R.string.tool_detail_load_failed),\n        color = MaterialTheme.colorScheme.error,\n        style = ChatType.body,\n        modifier = Modifier.padding(vertical = 24.dp),\n    )\n}\n'''
    p.write_text(text)

for path, values in [
    ("app/src/main/res/values/strings.xml", '\n    <string name="tool_detail_loading">Loading complete tool details…</string>\n    <string name="tool_detail_load_failed">Complete tool details could not be loaded.</string>\n'),
    ("app/src/main/res/values-zh/strings.xml", '\n    <string name="tool_detail_loading">正在加载完整工具详情…</string>\n    <string name="tool_detail_load_failed">无法加载完整工具详情。</string>\n'),
]:
    p = ROOT / path
    text = p.read_text()
    if 'name="tool_detail_loading"' not in text:
        p.write_text(text.replace("</resources>", values + "</resources>"))

write("app/src/test/java/com/newoether/agora/model/ToolPayloadPolicyTest.kt", '''package com.newoether.agora.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPayloadPolicyTest {
    @Test fun boundedPayloadStaysInline() {
        assertFalse(ToolPayloadPolicy.shouldDefer(ToolPayloadPolicy.MAX_INLINE_JSON_CHARS))
    }

    @Test fun largePayloadIsDeferred() {
        assertTrue(ToolPayloadPolicy.shouldDefer(ToolPayloadPolicy.MAX_INLINE_JSON_CHARS + 1))
    }

    @Test fun deferredLegacyPayloadHasClickableToolPlaceholder() {
        val segment = ToolPayloadPolicy.deferredSegments().single()
        assertTrue(segment.type == "tool" && segment.payloadDeferred)
    }
}
''')
