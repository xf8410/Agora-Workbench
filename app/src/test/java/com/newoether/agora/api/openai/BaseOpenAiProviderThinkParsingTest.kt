package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiDelta
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.StreamingThinkTagParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BaseOpenAiProviderThinkParsingTest {
    private class TestProvider : BaseOpenAiProvider() {
        override val name = "test"
        override val defaultBaseUrl = "https://example.invalid/v1"

        suspend fun parse(
            delta: OpenAiDelta,
            config: ProviderConfig,
            parser: StreamingThinkTagParser,
            emit: suspend (StreamEvent) -> Unit
        ) = parseDeltaContent(delta, config, parser, emit)
    }

    private fun config(thinkingEnabled: Boolean = true) = ProviderConfig(
        apiKey = "",
        modelId = "test",
        thinkingEnabled = thinkingEnabled
    )

    @Test
    fun `content think tags split across deltas are preserved as thought`() = runTest {
        val provider = TestProvider()
        val parser = StreamingThinkTagParser()
        val events = mutableListOf<StreamEvent>()
        provider.parse(OpenAiDelta(content = "<thi"), config(), parser, events::add)
        provider.parse(OpenAiDelta(content = "nk>many tool calls and a very long analysis</think>answer"), config(), parser, events::add)
        parser.flush(
            onText = { events.add(StreamEvent.TextChunk(it)) },
            onThought = { events.add(StreamEvent.ThoughtChunk(it)) }
        )

        assertEquals(
            "many tool calls and a very long analysis",
            events.filterIsInstance<StreamEvent.ThoughtChunk>().joinToString("") { it.thought }
        )
        assertEquals(
            "answer",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text }
        )
    }

    @Test
    fun `structured reasoning and ordinary content both remain available`() = runTest {
        val provider = TestProvider()
        val parser = StreamingThinkTagParser()
        val events = mutableListOf<StreamEvent>()
        provider.parse(
            OpenAiDelta(reasoningContent = "structured thought", content = "answer"),
            config(),
            parser,
            events::add
        )

        assertEquals("structured thought", events.filterIsInstance<StreamEvent.ThoughtChunk>().single().thought)
        assertEquals("answer", events.filterIsInstance<StreamEvent.TextChunk>().single().text)
    }
}
