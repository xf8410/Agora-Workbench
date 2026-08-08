package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolDefinitionDeduplicationTest {
    private fun tool(name: String, description: String) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = emptyMap()),
        )
    )

    @Test
    fun providerConfigKeepsOnlyFirstDefinitionForEachFunctionName() {
        val config = ProviderConfig(
            apiKey = "",
            modelId = "model",
            tools = listOf(
                tool("github_create_pull_request", "first"),
                tool("github_merge_pull_request", "merge"),
                tool("github_create_pull_request", "duplicate"),
                tool("github_merge_pull_request", "duplicate merge"),
            ),
        )

        assertEquals(
            listOf("github_create_pull_request", "github_merge_pull_request"),
            config.tools?.map { it.function.name },
        )
        assertEquals("first", config.tools?.first()?.function?.description)
    }

    @Test
    fun providerConfigPreservesNullToolList() {
        val config = ProviderConfig(apiKey = "", modelId = "model", tools = null)
        assertNull(config.tools)
    }
}
