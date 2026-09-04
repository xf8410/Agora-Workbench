    @Test
    fun tokenUsage_roundTripsThroughSerializableRuntimeRecord() {
        val usage = TokenUsage(
            inputTokensTotal = 900, inputTokensCached = 300, outputTokens = 77, totalTokens = 977,
            cacheDetailsStatus = CacheDetailsStatus.PROVIDED,
            origin = UsageOrigin.PROVIDER_REPORTED, rawUsageJson = """{"total_tokens":977}""",
        )
        val record = UsageRequestRecord(
            requestId = "req-1",
            conversationId = "conv-1",
            providerId = SessionUsageRuntime.stableProviderId("OpenAI", null),
            providerName = "OpenAI",
            modelId = "gpt-x",
            inputTokensTotal = usage.inputTokensTotal,
            inputTokensCached = usage.inputTokensCached,
            outputTokens = usage.outputTokens,
            reasoningTokens = usage.thoughtsTokens,
            totalTokens = usage.totalTokens,
            cacheDetailsStatus = usage.cacheDetailsStatus,
            origin = usage.origin,
            rawUsageJson = usage.rawUsageJson,
        )
        val encoded = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(UsageRequestRecord.serializer()), listOf(record)
        )
        val decoded = Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(UsageRequestRecord.serializer()), encoded
        )
        assertEquals(listOf(record), decoded)
        assertEquals(600, decoded.first().inputTokensUncached)
    }
