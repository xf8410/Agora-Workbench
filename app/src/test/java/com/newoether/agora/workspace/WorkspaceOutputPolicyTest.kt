package com.newoether.agora.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceOutputPolicyTest {
    @Test
    fun replacesRawGithubTreeArrayWithHumanReadableSummary() {
        val payload = """
            [{"path":"crates/umasim/src/main.rs","type":"blob","mode":"100644","sha":"1234567890123456789012345678901234567890","size":1903},
             {"path":"ramen.rs","type":"blob","mode":"100644","sha":"abcdefabcdefabcdefabcdefabcdefabcdefabcd","size":5391}]
        """.trimIndent()

        val result = WorkspaceOutputPolicy.sanitize(payload)

        assertEquals("GitHub 文件树已更新；原始文件列表已保留在工具结果中。", result)
        assertTrue("path" !in result)
        assertTrue("sha" !in result)
    }

    @Test
    fun preservesNormalWorkspaceReport() {
        val report = "实验分支已完成验证\nCI 状态：通过\n提交 SHA：abc123"
        assertEquals(report, WorkspaceOutputPolicy.sanitize(report))
    }

    @Test
    fun removesUnsupportedFutureMonitoringClaimsWithoutDroppingReport() {
        val report = "检查完成。\n我会继续监控 CI，完成后通知你。\n提交已保存。"
        assertEquals("检查完成。\n提交已保存。", WorkspaceOutputPolicy.sanitize(report))
    }
}
