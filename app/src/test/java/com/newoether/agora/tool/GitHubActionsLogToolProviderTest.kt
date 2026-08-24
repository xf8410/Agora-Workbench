package com.newoether.agora.tool

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
}
