from pathlib import Path

assistant = Path("app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt")
text = assistant.read_text()
old_status = """                    message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0) stringResource(R.string.cost_tokens, message.tokenCount) else null
                    isStreaming && isTranscribing -> transcribingStatus"""
if old_status in text:
    text = text.replace(old_status, "                    isStreaming && isTranscribing -> transcribingStatus", 1)
elif "message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0)" in text:
    raise SystemExit("Unexpected token header shape")

old_visibility = "visible = shouldShowAssistantActions(isStreaming, message.text),"
new_visibility = """visible = shouldShowAssistantActions(
                            isStreaming = isStreaming,
                            text = message.text,
                            hasRenderableContent = message.images.isNotEmpty() ||
                                !message.segments.isNullOrEmpty() ||
                                message.status == MessageStatus.ERROR ||
                                message.status == MessageStatus.STOPPED,
                        ),"""
if old_visibility in text:
    text = text.replace(old_visibility, new_visibility, 1)
elif "hasRenderableContent = message.images.isNotEmpty()" not in text:
    raise SystemExit("Assistant action visibility call not found")

if "stringResource(R.string.reply_token_usage, message.tokenCount)" not in text:
    footer = "                Spacer(modifier = Modifier.height(16.dp))"
    pos = text.rfind(footer)
    if pos < 0:
        raise SystemExit("Assistant footer not found")
    usage = """                AnimatedVisibility(
                    visible = message.participant == Participant.MODEL &&
                        shouldShowAssistantTokenUsage(message.status, message.tokenCount),
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
                ) {
                    Text(
                        text = stringResource(R.string.reply_token_usage, message.tokenCount),
                        style = ChatType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                }

"""
    text = text[:pos] + usage + text[pos:]
assistant.write_text(text)

Path("app/src/main/java/com/newoether/agora/ui/chat/message/AssistantActionVisibility.kt").write_text("""package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageStatus

internal fun shouldShowAssistantActions(
    isStreaming: Boolean,
    text: String,
    hasRenderableContent: Boolean,
): Boolean = text.isNotBlank() || (!isStreaming && hasRenderableContent)

internal fun shouldShowAssistantTokenUsage(status: MessageStatus, tokenCount: Int): Boolean =
    status in setOf(MessageStatus.SUCCESS, MessageStatus.ERROR, MessageStatus.STOPPED) && tokenCount > 0
""")

Path("app/src/test/java/com/newoether/agora/ui/chat/message/AssistantActionVisibilityTest.kt").write_text("""package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionVisibilityTest {
    @Test fun completedTextReply_showsActions() = assertTrue(shouldShowAssistantActions(false, "answer", false))
    @Test fun staleStreamingFlag_withText_showsActions() = assertTrue(shouldShowAssistantActions(true, "answer", false))
    @Test fun terminalToolOrErrorReply_showsActions() = assertTrue(shouldShowAssistantActions(false, "", true))
    @Test fun emptyStreamingPlaceholder_hidesActions() = assertFalse(shouldShowAssistantActions(true, "", false))

    @Test fun terminalReportedUsage_showsTokens() {
        assertTrue(shouldShowAssistantTokenUsage(MessageStatus.SUCCESS, 42))
        assertTrue(shouldShowAssistantTokenUsage(MessageStatus.ERROR, 42))
        assertTrue(shouldShowAssistantTokenUsage(MessageStatus.STOPPED, 42))
    }

    @Test fun unknownOrNonTerminalUsage_hidesTokens() {
        assertFalse(shouldShowAssistantTokenUsage(MessageStatus.SUCCESS, 0))
        assertFalse(shouldShowAssistantTokenUsage(MessageStatus.THINKING, 42))
    }
}
""")

for filename, anchor, addition in [
    ("app/src/main/res/values/strings.xml", "    <string name=\"cost_tokens\">Cost %1$d tokens</string>\n", "    <string name=\"reply_token_usage\">Total usage: %1$d tokens</string>\n"),
    ("app/src/main/res/values-zh/strings.xml", "    <string name=\"cost_tokens\">消耗 %1$d Tokens</string>\n", "    <string name=\"reply_token_usage\">Token 总消耗：%1$d</string>\n"),
]:
    path = Path(filename)
    value = path.read_text()
    if 'name="reply_token_usage"' not in value:
        if anchor not in value:
            raise SystemExit(f"String insertion point missing in {filename}")
        path.write_text(value.replace(anchor, anchor + addition, 1))

Path(".github/workflows/apply-stage2-token-layout-once.yml").unlink(missing_ok=True)
Path("scripts/apply_stage2_token_layout.py").unlink(missing_ok=True)
