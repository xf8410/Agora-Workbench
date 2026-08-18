package com.newoether.agora.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionErrorsTest {
    @Test fun `unknown tool uses T001 Chinese message`() {
        val text = ToolExecutionErrors.unknownTool("missing_tool")
        assertTrue(text.contains("T001"))
        assertTrue(text.contains("找不到工具"))
        assertFalse(text.contains("Unknown tool"))
    }

    @Test fun `timeout uses T004 without elapsed milliseconds`() {
        val text = ToolExecutionErrors.timeout("web_search")
        assertTrue(text.contains("T004"))
        assertTrue(text.contains("超时"))
        assertFalse(text.contains("ms"))
    }

    @Test fun `denied provider result becomes T005`() {
        val text = ToolExecutionErrors.normalizeResult(
            "github_delete_workflow_run",
            "{\"ok\":false,\"error\":\"GitHub action denied\"}",
        )
        assertTrue(text.contains("T005"))
        assertTrue(text.contains("未获批准"))
        assertFalse(text.contains("GitHub action denied"))
    }

    @Test fun `not signed in becomes T006`() {
        val text = ToolExecutionErrors.normalizeResult(
            "github_list_repositories",
            "{\"ok\":false,\"error\":\"GitHub is not signed in\"}",
        )
        assertTrue(text.contains("T006"))
        assertTrue(text.contains("登录信息"))
    }

    @Test fun `permission error becomes T008`() {
        val text = ToolExecutionErrors.normalizeResult(
            "github_write_file",
            "{\"ok\":false,\"error\":\"Resource not accessible: HTTP 403\"}",
        )
        assertTrue(text.contains("T008"))
        assertTrue(text.contains("权限"))
    }

    @Test fun `successful result remains unchanged`() {
        val original = "{\"ok\":true,\"value\":\"done\"}"
        assertTrue(ToolExecutionErrors.normalizeResult("example", original) == original)
    }
}
