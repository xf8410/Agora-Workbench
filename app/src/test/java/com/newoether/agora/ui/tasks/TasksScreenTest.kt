package com.newoether.agora.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class TasksScreenTest {
    @Test
    fun countdown_clampsExpiredRunsToZero() {
        assertEquals("0:00", formatTaskCountdown(-1L))
        assertEquals("0:00", formatTaskCountdown(0L))
    }

    @Test
    fun countdown_roundsUpPartialSeconds() {
        assertEquals("0:01", formatTaskCountdown(1L))
        assertEquals("0:43", formatTaskCountdown(42_001L))
    }

    @Test
    fun countdown_includesHoursWithoutWrappingAtOneDay() {
        assertEquals("1:02:03", formatTaskCountdown(3_723_000L))
        assertEquals("25:00:00", formatTaskCountdown(90_000_000L))
    }
}
