package com.newoether.agora.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgoraForegroundServiceLeaseTest {
    @Test
    fun distinctOwners_startOnceAndOnlyLastReleaseStops() {
        val leases = ForegroundOwnerLeases()
        var starts = 0
        var stops = 0

        assertTrue(leases.acquire("message-a") { starts++; true })
        assertTrue(leases.acquire("message-b") { starts++; true })
        assertEquals(1, starts)
        assertEquals(2, leases.size())

        assertTrue(leases.release("message-a") { stops++ })
        assertEquals(0, stops)
        assertEquals(1, leases.size())

        assertTrue(leases.release("message-b") { stops++ })
        assertEquals(1, stops)
        assertEquals(0, leases.size())
    }

    @Test
    fun duplicateAcquireAndRelease_areIdempotent() {
        val leases = ForegroundOwnerLeases()
        var starts = 0
        var stops = 0

        assertTrue(leases.acquire("message") { starts++; true })
        assertFalse(leases.acquire("message") { starts++; true })
        assertEquals(1, starts)

        assertTrue(leases.release("message") { stops++ })
        assertFalse(leases.release("message") { stops++ })
        assertEquals(1, stops)
    }

    @Test
    fun failedFirstStart_rollsBackOwnerSoAcquireCanRetry() {
        val leases = ForegroundOwnerLeases()

        assertFalse(leases.acquire("message") { false })
        assertEquals(0, leases.size())
        assertTrue(leases.acquire("message") { true })
        assertEquals(1, leases.size())
    }

    @Test
    fun releaseBeforePromotion_defersStopUntilServiceIsForeground() {
        val leases = ForegroundOwnerLeases()
        val stopNowFlags = mutableListOf<Boolean>()
        var selfStops = 0

        // A generation that fails within milliseconds releases before onCreate runs.
        assertTrue(leases.acquire("message") { true })
        assertTrue(leases.release("message") { stopNowFlags += it })
        assertEquals(listOf(false), stopNowFlags)

        // onCreate's startForeground() then completes and the service stops itself.
        leases.onPromoted { selfStops++ }
        assertEquals(1, selfStops)
    }

    @Test
    fun reacquireBeforePromotion_keepsServiceRunning() {
        val leases = ForegroundOwnerLeases()
        val stopNowFlags = mutableListOf<Boolean>()
        var selfStops = 0

        assertTrue(leases.acquire("message-a") { true })
        assertTrue(leases.release("message-a") { stopNowFlags += it })
        assertTrue(leases.acquire("message-b") { true })

        leases.onPromoted { selfStops++ }
        assertEquals(0, selfStops)

        assertTrue(leases.release("message-b") { stopNowFlags += it })
        assertEquals(listOf(false, true), stopNowFlags)
    }

    @Test
    fun promotedService_stopsImmediatelyOnLastRelease() {
        val leases = ForegroundOwnerLeases()
        val stopNowFlags = mutableListOf<Boolean>()
        var selfStops = 0

        assertTrue(leases.acquire("message") { true })
        leases.onPromoted { selfStops++ }
        assertTrue(leases.release("message") { stopNowFlags += it })

        assertEquals(0, selfStops)
        assertEquals(listOf(true), stopNowFlags)
    }

    @Test
    fun completionId_isStableAndHandlesIntMinHash() {
        val ordinary = "conversation-with-a-wide-hash"

        assertEquals(ordinary.hashCode() and Int.MAX_VALUE, stableCompletionNotificationId(ordinary))
        assertEquals(
            stableCompletionNotificationId(ordinary),
            stableCompletionNotificationId(ordinary),
        )
        // This Java/Kotlin string is a known hashCode() == Int.MIN_VALUE edge case.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertEquals(0, stableCompletionNotificationId("polygenelubricants"))
    }
}
