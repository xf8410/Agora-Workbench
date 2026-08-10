package com.newoether.agora.uma

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UmaSessionUploadBatchTest {
    @Test
    fun `bounded batch advances from completed cursor`() {
        val files = (0 until 250).toList()
        assertEquals(
            (100 until 200).toList(),
            nextUmaUploadBatch(files, 100, UmaSessionUploadBatchLimits(100)),
        )
        assertEquals(
            (200 until 250).toList(),
            nextUmaUploadBatch(files, 200, UmaSessionUploadBatchLimits(100)),
        )
        assertTrue(nextUmaUploadBatch(files, 250, UmaSessionUploadBatchLimits(100)).isEmpty())
    }

    @Test
    fun `batch limits reject zero and oversized values`() {
        assertThrows(IllegalArgumentException::class.java) { UmaSessionUploadBatchLimits(0) }
        assertThrows(IllegalArgumentException::class.java) {
            UmaSessionUploadBatchLimits(UmaSessionUploadBatchLimits.MAX_BATCH_SIZE + 1)
        }
    }

    @Test
    fun `task store persists progress and forbids argument replacement`() {
        val root = Files.createTempDirectory("uma-upload-task-test").toFile()
        try {
            val store = UmaSessionUploadTaskStore(root)
            val task = UmaSessionUploadTask(
                taskId = "uma-test-task",
                sessionId = "session-1",
                repository = "owner/repo",
                branch = "workbench/session-upload",
                targetDirectory = "evidence/raw_sessions/session-1",
                commitMessage = "archive complete session",
                batchSize = 100,
                createdAtMs = 1L,
            )
            val initial = UmaSessionUploadProgress(
                taskId = task.taskId,
                sessionId = task.sessionId,
                repository = task.repository,
                branch = task.branch,
                targetDirectory = task.targetDirectory,
                phase = UmaSessionUploadPhase.QUEUED,
                rawTotalFiles = 0,
                rawCompletedFiles = 0,
                rawTotalBytes = 0,
                rawCompletedBytes = 0,
                nextCursor = 0,
                checkpointUpdatedAtMs = 1L,
            )
            store.create(UmaSessionUploadTaskRecord(task, initial))
            val updated = store.update(task.taskId) { current ->
                current.copy(progress = current.progress.copy(
                    phase = UmaSessionUploadPhase.RAW_BLOBS,
                    rawTotalFiles = 2798,
                    rawCompletedFiles = 100,
                    rawTotalBytes = 34_798_618L,
                    rawCompletedBytes = 1_000_000L,
                    nextCursor = 100,
                    checkpointUpdatedAtMs = 2L,
                ))
            }
            assertEquals(100, store.read(task.taskId)?.progress?.rawCompletedFiles)
            assertEquals(100, updated.progress.nextCursor)
            assertFalse(updated.progress.complete)
            assertEquals(1, store.list().size)

            assertThrows(IllegalArgumentException::class.java) {
                store.update(task.taskId) { current ->
                    current.copy(task = current.task.copy(repository = "other/repo"))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `complete progress is terminal`() {
        val progress = UmaSessionUploadProgress(
            taskId = "uma-complete",
            sessionId = "session-1",
            repository = "owner/repo",
            branch = "workbench/session-upload",
            targetDirectory = "archive/session-1",
            phase = UmaSessionUploadPhase.COMPLETE,
            rawTotalFiles = 1,
            rawCompletedFiles = 1,
            rawTotalBytes = 10,
            rawCompletedBytes = 10,
            derivedTotalFiles = 4,
            derivedCompletedFiles = 4,
            nextCursor = 5,
            checkpointUpdatedAtMs = 3L,
            treeSha = "a".repeat(40),
            commitSha = "b".repeat(40),
        )
        assertTrue(progress.complete)
        assertTrue(progress.terminal)
    }
}
