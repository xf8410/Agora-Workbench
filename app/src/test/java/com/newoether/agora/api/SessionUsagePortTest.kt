package com.newoether.agora.api

import com.newoether.agora.model.CacheDetailsStatus
import com.newoether.agora.model.ConversationUsageSummary
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.UsageOrigin
import com.newoether.agora.model.UsageRequestRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the session-usage-dashboard work: record/summary math + lenient usage parsing. */
class SessionUsagePortTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun summary_sumsOnlyProviderReportedRecords() {
        val reported = UsageRequestRecord(
            requestId = "r1", conversationId = "c", providerId = "p", providerName = "OpenAI",
            modelId = "gpt-x", inputTokensTotal = 1000, inputTokensCached = 700, outputTokens = 200,
            cacheDetailsStatus = CacheDetailsStatus.PROVIDED, origin = UsageOrigin.PROVIDER_REPORTED,
        )
        val estimated = UsageRequestRecord(
            requestId = "r2", conversationId = "c", providerId = "p", providerName = "OpenAI",
            modelId = "gpt-x", inputTokensTotal = 50, outputTokens = 10,
            origin = UsageOrigin.LOCALLY_ESTIMATED,
        )
        val summary = ConversationUsageSummary("c", listOf(reported, estimated))

        assertEquals(1000, summary.reportedInputTokens)
        assertEquals(700, summary.reportedCachedTokens)
        assertEquals(300, summary.reportedUncachedTokens)
        assertEquals(200, summary.reportedOutputTokens)
        assertEquals(50, summary.estimatedInputTokens)
        assertTrue(summary.cacheDetailsComplete)
    }

    @Test
    fun summary_cacheDetailsIncompleteWhenAnyRecordOmitsBreakdown() {
        val withDetails = UsageRequestRecord(
            requestId = "r1", conversationId = "c", providerId = "p", providerName = "OpenAI",
            modelId = "gpt-x", inputTokensTotal = 1000, inputTokensCached = 700,
            cacheDetailsStatus = CacheDetailsStatus.PROVIDED, origin = UsageOrigin.PROVIDER_REPORTED,
        )
        val withoutDetails = UsageRequestRecord(
            requestId = "r2", conversationId = "c", providerId = "p", providerName = "OpenAI",
            modelId = "gpt-x", inputTokensTotal = 100, origin = UsageOrigin.PROVIDER_REPORTED,
        )
        val summary = ConversationUsageSummary("c", listOf(withDetails, withoutDetails))
        assertFalse(summary.cacheDetailsComplete)
    }

    @Test
    fun usageParser_readsOpenAiCanonicalFields() {
        val usage = com.newoether.agora.api.openai.OpenAiUsageParser.parse(
            json.parseToJsonElement(
                """{"prompt_tokens":1000,"completion_tokens":200,"total_tokens":1200,
                    "prompt_tokens_details":{"cached_tokens":700},
                    "completion_tokens_details":{"reasoning_tokens":120}}"""
            )
        )!!
        assertEquals(1000, usage.inputTokensTotal)
        assertEquals(700, usage.inputTokensCached)
        assertEquals(200, usage.outputTokens)
        assertEquals(120, usage.thoughtsTokens)
        assertEquals(1200, usage.totalTokens)
        assertEquals(CacheDetailsStatus.PROVIDED, usage.cacheDetailsStatus)
        assertEquals(700.0 / 1000.0, usage.cacheHitRatio!!, 1e-9)
    }

    @Test
    fun usageParser_readsOpenRouterStyleAlternates() {
        val usage = com.newoether.agora.api.openai.OpenAiUsageParser.parse(
            json.parseToJsonElement(
                """{"usage_details":{"cached_tokens":64,"reasoning_tokens":8},
                    "input_tokens":500,"output_tokens":50}"""
            )
        )!!
        assertEquals(500, usage.inputTokensTotal)
        assertEquals(64, usage.inputTokensCached)
        assertEquals(8, usage.thoughtsTokens)
        assertEquals(550, usage.totalTokens) // derived input+output
    }

    @Test
    fun usageParser_returnsNullForNonUsageObject() {
        assertNull(com.newoether.agora.api.openai.OpenAiUsageParser.parse(
            json.parseToJsonElement("""{"id":"chatcmpl-1"}""")
        ))
    }

    @Test
    fun tokenUsage_roundTripsThroughSerializableRuntimeRecord() {
        val usage = TokenUsage(
            inputTokensTotal = 900, inputTokensCached = 300, outputTokens = 77, totalTokens = 977,
            cacheDetailsStatus = CacheDetailsStatus.PROVIDED,
            origin = UsageOrigin.PROVIDER_REPORTED, rawUsageJson = """{"total_tokens":977}""",
        )
        val record = UsageRequestRecord(
            requestId = "req-1", conversationId = "conv-1",
            providerId = SessionUsageRuntime.stableProviderId("OpenAI", null),
            providerName = "OpenAI", modelId = "gpt-x",
            usage.inputTokensTotal, usage.inputTokensCached, usage.outputTokens,
            usage.thoughtsTokens, usage.totalTokens, usage.cacheDetailsStatus,
            usage.origin, usage.rawUsageJson,
        )
        val encoded = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(UsageRequestRecord.serializer()), listOf(record)
        )
        val decoded = Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(UsageRequestRecord.serializer()), encoded
        )
        assertEquals(listOf(record), decoded)
        assertEquals(300, decoded.first().inputTokensUncached)
    }
}
