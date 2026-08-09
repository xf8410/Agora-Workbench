package com.newoether.agora.github

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubActionsRunsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesRunningAndCompletedWorkflowRuns() {
        val response = json.decodeFromString<GitHubWorkflowRunsResponse>(
            """
            {
              "total_count": 2,
              "workflow_runs": [
                {
                  "id": 31312153286,
                  "name": "Build Agora Workbench APK",
                  "status": "in_progress",
                  "conclusion": null,
                  "head_branch": "main",
                  "head_sha": "c49e941b795b869124be6c4367cdf6035c7238ba",
                  "event": "push",
                  "created_at": "2026-08-09T11:59:29Z",
                  "updated_at": "2026-08-09T11:59:33Z",
                  "html_url": "https://github.com/xf8410/Agora-Workbench/actions/runs/31312153286"
                },
                {
                  "id": 31311331228,
                  "name": "Build Agora Workbench APK",
                  "status": "completed",
                  "conclusion": "success",
                  "head_branch": "workbench/example",
                  "head_sha": "084bf19108dfb9a24dc664a90d590adbbd04613d",
                  "event": "pull_request",
                  "created_at": "2026-08-09T11:39:26Z",
                  "updated_at": "2026-08-09T11:49:06Z",
                  "html_url": "https://github.com/xf8410/Agora-Workbench/actions/runs/31311331228"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, response.workflowRuns.size)
        assertEquals("c49e941", response.workflowRuns[0].shortSha)
        assertNull(response.workflowRuns[0].conclusion)
        assertEquals("success", response.workflowRuns[1].conclusion)
        assertEquals("pull_request", response.workflowRuns[1].event)
    }
}
