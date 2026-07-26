package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.LoopEntity
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
import org.junit.Before
import org.junit.Test

class LoopManagerTest {
    private val taskRepository = mockk<TaskRepository>()
    private val conversationRepository = mockk<ConversationRepository>()
    private val engine = mockk<TaskExecutionEngine>()
    private val stored = MutableStateFlow<LoopEntity?>(null)
    private val cancelled = mutableListOf<String>()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        every { taskRepository.getLoop(any()) } returns stored
        coEvery { taskRepository.upsertLoop(any()) } coAnswers {
            stored.value = firstArg()
        }
        coEvery { taskRepository.deleteLoop(any()) } coAnswers {
            stored.value = null
        }
        coEvery { conversationRepository.getConversation("conversation") } returns
            ChatEntity(id = "conversation", title = "Conversation", modelId = "OpenAI:model")
    }

    @Test
    fun startLoop_persistsAndSchedulesThenRejectsAnActiveConflict() = runTest {
        val manager = manager()

        val started = manager.startLoop(
            conversationId = "conversation",
            intervalMs = LoopPolicy.MIN_INTERVAL_MS,
            prompt = "  inspect  ",
        )

        assertTrue(started is LoopManager.StartResult.Started)
        assertEquals("inspect", stored.value?.prompt)
        assertEquals(now + LoopPolicy.MIN_INTERVAL_MS, stored.value?.nextFireAt)
        assertEquals(LoopPolicy.DEFAULT_MAX_CYCLES, stored.value?.maxCycles)
        val conflict = manager.startLoop("conversation", LoopPolicy.MIN_INTERVAL_MS)
        assertTrue(conflict is LoopManager.StartResult.Conflict)
    }

    @Test
    fun stopLoop_incrementsRevisionAndCancelsDurableWork() = runTest {
        stored.value = loop(revision = 7L)
        val manager = manager()

        assertEquals(LoopManager.StopResult.Stopped, manager.stopLoop("conversation"))
        assertFalse(stored.value!!.active)
        assertEquals(8L, stored.value!!.revision)
        assertEquals(listOf("conversation"), cancelled)
        assertEquals(LoopManager.StopResult.AlreadyStopped, manager.stopLoop("conversation"))
        assertEquals(listOf("conversation", "conversation"), cancelled)
    }

    @Test
    fun stopLoop_cancelsWorkerEvenWhenFinalCycleAlreadyMarkedInactive() = runTest {
        stored.value = loop(maxCycles = 1).copy(active = false, cycleCount = 1, nextFireAt = 0L)
        val manager = manager()

        assertEquals(LoopManager.StopResult.AlreadyStopped, manager.stopLoop("conversation"))

        assertEquals(listOf("conversation"), cancelled)
    }

    @Test
    fun successfulCycleAdvancesAndSchedulesFromCompletionTime() = runTest {
        stored.value = loop(maxCycles = 2, revision = 3L)
        coEvery {
            engine.runOnceWithConversationLockHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } returns TaskExecutionEngine.Result.Success("model-message", "done")
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertTrue(result is LoopManager.ExecutionResult.Finished)
        assertEquals(1, stored.value!!.cycleCount)
        assertTrue(stored.value!!.active)
        assertEquals(now + LoopPolicy.MIN_INTERVAL_MS, stored.value!!.nextFireAt)
        assertEquals(3L, stored.value!!.revision)
    }

    @Test
    fun modelFailureStillConsumesFinalCycleWithoutImmediateRetry() = runTest {
        stored.value = loop(maxCycles = 1)
        coEvery {
            engine.runOnceWithConversationLockHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } returns TaskExecutionEngine.Result.Failure("provider failed")
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertTrue(result is LoopManager.ExecutionResult.Finished)
        assertEquals(1, stored.value!!.cycleCount)
        assertFalse(stored.value!!.active)
        assertEquals(0L, stored.value!!.nextFireAt)
    }

    @Test
    fun stopDuringGenerationCannotBeOverwrittenByStaleCompletion() = runTest {
        stored.value = loop(maxCycles = 5, revision = 10L)
        coEvery {
            engine.runOnceWithConversationLockHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } coAnswers {
            stored.value = stored.value!!.copy(active = false, revision = 11L)
            TaskExecutionEngine.Result.Success("model-message", "done")
        }
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertTrue(result is LoopManager.ExecutionResult.Superseded)
        // The durable pre-generation claim remains consumed even though stop superseded
        // completion. Replaying that cycle could duplicate provider/tool side effects.
        assertEquals(1, stored.value!!.cycleCount)
        assertFalse(stored.value!!.active)
        assertEquals(11L, stored.value!!.revision)
    }

    @Test
    fun retryOfClaimedOccurrenceNeverReplaysModelSideEffects() = runTest {
        val scheduledAt = now
        stored.value = loop(nextFireAt = scheduledAt, maxCycles = 3)
        coEvery {
            engine.runOnceWithConversationLockHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        } returns TaskExecutionEngine.Result.Success("model-message", "done")
        val manager = manager()

        val first = manager.executeByConversationId("conversation", scheduledAt)
        val retry = manager.executeByConversationId("conversation", scheduledAt)

        assertTrue(first is LoopManager.ExecutionResult.Finished)
        assertTrue(retry is LoopManager.ExecutionResult.Superseded)
        assertEquals(1, stored.value!!.cycleCount)
        coVerify(exactly = 1) {
            engine.runOnceWithConversationLockHeld("conversation", "Continue.", "OpenAI:model", null, true, any())
        }
    }

    @Test
    fun exhaustedInfrastructureFailureCanDeferAndRearmLoop() = runTest {
        stored.value = loop(nextFireAt = now - 1L, maxCycles = 3)
        val manager = manager()

        assertTrue(manager.deferAfterInfrastructureFailure("conversation"))

        assertEquals(now + LoopPolicy.MIN_INTERVAL_MS, stored.value!!.nextFireAt)
        assertTrue(stored.value!!.active)
    }

    @Test
    fun notDueWorkerNeverCallsTheModelAndRepairsItsSchedule() = runTest {
        stored.value = loop(nextFireAt = now + 5_000L)
        val manager = manager()

        val result = manager.executeByConversationId("conversation")

        assertEquals(LoopManager.ExecutionResult.NotDue(now + 5_000L), result)
        coVerify(exactly = 0) {
            engine.runOnceWithConversationLockHeld(any(), any(), any(), any(), any(), any())
        }
    }

    private fun kotlinx.coroutines.test.TestScope.manager() = LoopManager(
        taskRepository = taskRepository,
        conversationRepository = conversationRepository,
        engine = engine,
        cancelWork = { cancelled += it },
        clock = { now },
    )

    private fun loop(
        nextFireAt: Long = now,
        maxCycles: Int = LoopPolicy.DEFAULT_MAX_CYCLES,
        revision: Long = 0L,
    ) = LoopEntity(
        conversationId = "conversation",
        intervalMs = LoopPolicy.MIN_INTERVAL_MS,
        nextFireAt = nextFireAt,
        maxCycles = maxCycles,
        active = true,
        revision = revision,
    )
}
