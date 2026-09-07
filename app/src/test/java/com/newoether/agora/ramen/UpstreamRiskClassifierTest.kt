package com.newoether.agora.ramen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamRiskClassifierTest {
    @Test
    fun documentationOnlyUpdateStaysLowRisk() {
        val result = UpstreamRiskClassifier.assess(
            listOf(UpstreamChangedPath(".trae/documents/tests_overview.md", "added"))
        )

        assertEquals(UpstreamChangeRisk.LOW, result.risk)
        assertTrue(result.requiresWorkspaceTests)
        assertFalse(result.requiresRamenTests)
        assertFalse(result.requiresAndroidCrossCompile)
        assertFalse(result.requiresSampleReplay)
    }

    @Test
    fun ramenCoreChangeRequiresFullGate() {
        val result = UpstreamRiskClassifier.assess(
            listOf(UpstreamChangedPath("crates/umasim/src/game/ramen/game.rs"))
        )

        assertEquals(UpstreamChangeRisk.HIGH, result.risk)
        assertTrue(result.requiresWorkspaceTests)
        assertTrue(result.requiresRamenTests)
        assertTrue(result.requiresHostAdapterCompile)
        assertTrue(result.requiresAndroidCrossCompile)
        assertTrue(result.requiresSampleReplay)
    }

    @Test
    fun traitsChangeAlsoRequiresSchemaCheck() {
        val result = UpstreamRiskClassifier.assess(
            listOf(UpstreamChangedPath("crates/umasim/src/game/traits.rs"))
        )

        assertEquals(UpstreamChangeRisk.HIGH, result.risk)
        assertTrue(result.requiresSchemaCompatibility)
    }

    @Test
    fun unknownSourceChangeFailsIntoMediumRisk() {
        val result = UpstreamRiskClassifier.assess(
            listOf(UpstreamChangedPath("crates/analyzer/src/main.rs"))
        )

        assertEquals(UpstreamChangeRisk.MEDIUM, result.risk)
        assertTrue(result.requiresRamenTests)
        assertTrue(result.requiresHostAdapterCompile)
        assertFalse(result.requiresAndroidCrossCompile)
    }

    @Test
    fun windowsPathsAreNormalizedBeforeClassification() {
        assertEquals(
            UpstreamChangeRisk.HIGH,
            UpstreamRiskClassifier.classifyPath(".\\crates\\umasim\\src\\gamedata\\config.rs")
        )
    }
}
