package com.newoether.agora.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceOutputPolicyTest {
    @Test
    fun sanitize_removesUnsupportedFutureMonitoringClaims_butKeepsCurrentRunFacts() {
        val output = WorkspaceOutputPolicy.sanitize(
            "CI：运行中\n地址：https://github.com/example/actions/runs/123\n我会持续监控CI，完成后通知你。"
        )
        assertTrue(output.contains("CI：运行中"))
        assertTrue(output.contains("https://github.com/example/actions/runs/123"))
        assertFalse(output.contains("持续监控"))
        assertFalse(output.contains("通知你"))
    }
}
