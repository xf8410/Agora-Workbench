package com.newoether.agora.uma

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UmaSessionUploadWorkspaceTest {
    private val base = File("/tmp/agora-uma")

    @Test
    fun `workspace is stable for the same durable task`() {
        val task = task(taskId = "task-1", repository = "owner/repo-a")
        assertEquals(
            umaSessionUploadWorkspace(base, task),
            umaSessionUploadWorkspace(base, task),
        )
    }

    @Test
    fun `workspaces are isolated across repositories`() {
        val first = task(taskId = "task-1", repository = "owner/repo-a")
        val second = task(taskId = "task-1", repository = "owner/repo-b")
        assertNotEquals(
            umaSessionUploadWorkspace(base, first),
            umaSessionUploadWorkspace(base, second),
        )
    }

    @Test
    fun `cancelled task checkpoints cannot leak into a replacement task`() {
        val cancelled = task(taskId = "task-cancelled", repository = "owner/repo-a")
        val replacement = task(taskId = "task-replacement", repository = "owner/repo-a")
        assertNotEquals(
            umaSessionUploadWorkspace(base, cancelled),
            umaSessionUploadWorkspace(base, replacement),
        )
    }

    @Test
    fun `workspace path contains no repository or destination text`() {
        val workspace = umaSessionUploadWorkspace(
            base,
            task(taskId = "task-1", repository = "owner/private-repo"),
        )
        assertTrue(workspace.path.startsWith(File(base, "sessions/session-1/upload-workspaces").path))
        assertTrue(!workspace.path.contains("private-repo"))
    }

    private fun task(taskId: String, repository: String) = UmaSessionUploadTask(
        taskId = taskId,
        sessionId = "session-1",
        repository = repository,
        branch = "workbench/session-upload",
        targetDirectory = "archives/session-1",
        commitMessage = "archive session",
        batchSize = 1,
        createdAtMs = 1L,
    )
}
