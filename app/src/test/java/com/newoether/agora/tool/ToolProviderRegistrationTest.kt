package com.newoether.agora.tool

import android.app.Application
import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.uma.UmaApplicationContext
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.GenerationManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression lock for the tool-registration losses this repo has hit repeatedly:
 *
 *  - 2930dfe1: UmaToolProvider existed as a file but was never instantiated
 *    (the apply_uma_workbench_integration.py patch workflow did not stick) —
 *    every uma_* tool was missing from the model's tool list.
 *  - e7cab24a: the 032a4b7 merge silently dropped githubWatchToolProvider
 *    (and umaToolProvider) from builtInToolProviders.
 *  - 8cf6117b: GitHubPullRequestToolProvider and GitHubCloneToolProvider were added
 *    as files but never registered — 3 GitHub tools were unusable from birth.
 *
 * The registration is a hand-maintained list inside GenerationManager with no compile-time
 * safety, so merges/rewrites drop entries silently. These tests turn any future drop into a
 * loud CI failure.
 *
 * Invariants:
 *  1. Every concrete ToolProvider in com.newoether.agora.tool is reachable:
 *     all of them are registered in builtInToolProviders EXCEPT
 *       - AutomationToolProvider — foreground-only, attached per-instance via
 *         additionalToolProviders (headless automation must not self-replicate), and
 *       - UmaSessionExportToolProvider — reachable through UmaToolProvider's delegation.
 *  2. Every tool a provider advertises in definitions() is routable through its handles().
 *  3. No tool name is advertised twice across the built-in chain (silent shadowing).
 *
 * If these tests fail after a merge: restore the registration, do NOT weaken the test.
 */
class ToolProviderRegistrationTest {

    @Before
    fun setUp() {
        // UmaToolProvider.definitions() lazily builds UmaSessionExportToolProvider, which
        // requires the process-lifetime application context.
        UmaApplicationContext.install(mockk(relaxed = true))
    }

    private fun newManager(): GenerationManager = GenerationManager(
        app = mockk(relaxed = true),
        conversations = mockk(relaxed = true),
        memoryManager = mockk(relaxed = true),
        providers = emptyMap(),
        context = mockk(relaxed = true),
        sandboxFactory = null,
    )

    private fun builtinProviders(manager: GenerationManager): List<ToolProvider> {
        val field = GenerationManager::class.java.getDeclaredField("builtInToolProviders")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(manager) as List<ToolProvider>
    }

    private fun allFlagsOn() = GenerationContext(
        accessSavedMemories = true,
        accessActiveMemory = true,
        accessPastConversations = true,
        webSearchEnabled = true,
        imageGenEnabled = true,
        automationToolsEnabled = true,
        githubWorkspaceMode = true,
        shellEnabled = true,
    )

    @Test
    fun `every built-in tool provider is registered`() {
        val registered = builtinProviders(newManager()).map { it::class.java.simpleName }.toSet()
        val expected = setOf(
            "MemoryToolProvider",
            "WebSearchToolProvider",
            "RagToolProvider",
            "ImageGenToolProvider",
            "GitHubToolProvider",
            "GitHubWatchToolProvider",
            "GitHubActionsLogToolProvider",
            "GitHubWorkspaceToolProvider",
            "GitHubPullRequestToolProvider",
            "GitHubRepositoryMutationToolProvider",
            "GitHubBranchMutationToolProvider",
            "GitHubCloneToolProvider",
            "UmaToolProvider",
            "ShellToolProvider",
        )
        assertEquals(
            "GenerationManager.builtInToolProviders lost or gained a provider — " +
                "a merge likely dropped a registration again. Restore the registration.",
            expected,
            registered,
        )
    }

    @Test
    fun `every advertised tool is routable through its provider`() {
        val ctx = allFlagsOn()
        for (provider in builtinProviders(newManager())) {
            val who = provider::class.java.simpleName
            for (definition in provider.definitions(ctx)) {
                val name = definition.function.name
                assertTrue(
                    "$who advertises tool '$name' in definitions() but handles() rejects it — " +
                        "the model would see a tool it can never execute",
                    provider.handles(name),
                )
            }
        }
    }

    @Test
    fun `no tool name is advertised twice across the built-in chain`() {
        val ctx = allFlagsOn()
        val providers = builtinProviders(newManager())
        val all = providers.flatMap { provider ->
            provider.definitions(ctx).map { definition ->
                provider::class.java.simpleName to definition.function.name
            }
        }
        val duplicates = all.groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue(
            "Duplicate tool names silently shadow each other by provider order: $duplicates",
            duplicates.isEmpty(),
        )
    }
}
