package com.newoether.agora.automation

import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagerTest {
    @Test
    fun scheduledIncompleteTaskIsDisabledWithoutCallingModel() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        var stored = task(prompt = "")
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } coAnswers { stored = firstArg() }
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution", stored.nextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Skipped)
        assertFalse(stored.enabled)
        assertEquals(0L, stored.nextRunAt)
        coVerify(exactly = 0) { engine.runOnce(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun incompleteDraftIsNeverPersisted() = runTest {
        val repository = mockk<TaskRepository>()
        every { repository.getAllTasks() } returns MutableStateFlow(emptyList())
        coEvery { repository.upsertTask(any()) } returns Unit
        val manager = TaskManager(
            repository,
            mockk(),
            mockk(),
            backgroundScope,
        )

        manager.saveTask(task(name = "", prompt = "Prompt"))
        manager.saveTask(task(name = "Task", prompt = ""))

        coVerify(exactly = 0) { repository.upsertTask(any()) }
    }

    private fun task(
        name: String = "Task",
        prompt: String = "Prompt",
    ) = TaskEntity(
        id = "task",
        name = name,
        prompt = prompt,
        cronExpr = "* * * * *",
        nextRunAt = 123L,
        enabled = true,
    )
}
