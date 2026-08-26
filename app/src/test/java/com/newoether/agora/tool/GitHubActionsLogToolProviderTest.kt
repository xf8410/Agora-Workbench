package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActionsLogToolProviderTest {
    @Test
    fun shortLogIsReturnedUnchanged() {
        val log = "compile\nerror: unresolved reference\nfailed"
        assertTrue(summarizeActionsLog(log, 1_000) == log)
    }

    @Test
    fun longLogKeepsCompilerErrorAndTerminalTail() {
        val setup = (1..500).joinToString("\n") { "setup line $it" }
        val failure = "e: Main.kt:42: Unresolved reference ImportantSymbol"
        val tail = (1..100).joinToString("\n") { "terminal $it" }
        val summary = summarizeActionsLog("$setup\n$failure\n$tail", 2_000)

        assertTrue(summary.contains("ImportantSymbol"))
        assertTrue(summary.contains("terminal 100"))
        assertFalse(summary.contains("setup line 1\nsetup line 2\nsetup line 3"))
        assertTrue(summary.length <= 2_000)
    }

    @Test
    fun oversizedDiagnosticExcerptNeverCutsSectionMarkers() {
        // Many error lines: the naive takeLast(maxChars) implementation used to slice off the
        // leading marker and the first excerpts once the joined diagnostic approached the budget.
        val errors = (1..400).joinToString("\n") { "error: compile failure number $it ${"x".repeat(80)}" }
        val raw = "start\n$errors\n" + (1..100).joinToString("\n") { "terminal $it" }
        val summary = summarizeActionsLog(raw, 5_000)

        assertTrue(summary.startsWith("[diagnostic excerpts]\n"))
        assertTrue(summary.contains("error: compile failure number 1 "))
        assertTrue(summary.contains("[terminal log tail]"))
        assertTrue(summary.contains("terminal 100"))
        assertTrue(summary.length <= 5_000)
    }

    @Test
    fun tinyBudgetDegradesToPlainTailSlice() {
        val raw = (1..2000).joinToString("") { "a" }
        val summary = summarizeActionsLog(raw, 300)

        assertEquals(300, summary.length)
        assertTrue(summary.endsWith("aaa"))
    }

    @Test
    fun noErrorMarkerStillKeepsMarkerAndTail() {
        val raw = (1..500).joinToString("\n") { "plain setup line $it" }
        val summary = summarizeActionsLog(raw, 1_500)

        assertTrue(summary.startsWith("[diagnostic excerpts]\n"))
        assertTrue(summary.contains("No standard error marker found."))
        assertTrue(summary.contains("[terminal log tail]"))
        assertTrue(summary.length <= 1_500)
    }
}
