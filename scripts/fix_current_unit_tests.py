from pathlib import Path

# Make the RAG business-path entity type explicit. UI projections must never leak into branch logic.
p = Path('app/src/main/java/com/newoether/agora/tool/RagToolProvider.kt')
t = p.read_text(encoding='utf-8')
t = t.replace(
'''                val allMessages = conversations.getMessagesForConversationSnapshot(conversationId)
                    .filter { it.participant == Participant.USER || it.participant == Participant.MODEL }''',
'''                val allMessages: List<MessageEntity> = conversations
                    .getMessagesForConversationSnapshot(conversationId)
                    .filter { it.participant == Participant.USER || it.participant == Participant.MODEL }'''
)
t = t.replace(
'''            val allMessages = conversations.getMessagesForConversationSnapshot(id)
                .filter { it.participant == Participant.USER || it.participant == Participant.MODEL }''',
'''            val allMessages: List<MessageEntity> = conversations
                .getMessagesForConversationSnapshot(id)
                .filter { it.participant == Participant.USER || it.participant == Participant.MODEL }'''
)
p.write_text(t, encoding='utf-8')

# The version lives in the Android app module, not the root convention build file.
p = Path('app/src/test/java/com/newoether/agora/WorkbenchReleaseMetadataTest.kt')
t = p.read_text(encoding='utf-8').replace(
'val gradle = File("build.gradle.kts").readText()',
'val gradle = File("app/build.gradle.kts").readText()'
)
p.write_text(t, encoding='utf-8')

# Lock the output filter behavior with a focused JVM test.
p = Path('app/src/test/java/com/newoether/agora/workspace/WorkspaceOutputPolicyTest.kt')
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text('''package com.newoether.agora.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceOutputPolicyTest {
    @Test
    fun sanitize_removesUnsupportedFutureMonitoringClaims_butKeepsCurrentRunFacts() {
        val output = WorkspaceOutputPolicy.sanitize(
            "CI：运行中\\n地址：https://github.com/example/actions/runs/123\\n我会持续监控CI，完成后通知你。"
        )
        assertTrue(output.contains("CI：运行中"))
        assertTrue(output.contains("https://github.com/example/actions/runs/123"))
        assertFalse(output.contains("持续监控"))
        assertFalse(output.contains("通知你"))
    }
}
''', encoding='utf-8')
