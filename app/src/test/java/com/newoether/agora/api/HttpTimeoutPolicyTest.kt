package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTimeoutPolicyTest {
    @Test
    fun `ordinary requests retain a bounded read timeout`() {
        assertTrue(HttpTimeoutPolicy.ORDINARY_READ_MINUTES > 0L)
    }

    @Test
    fun `streaming generation has no hard read timeout`() {
        // OkHttp defines a zero timeout as no timeout.
        assertEquals(0L, HttpTimeoutPolicy.STREAM_READ_MINUTES)
        assertTrue(HttpTimeoutPolicy.STREAM_WRITE_MINUTES >= 5L)
    }
}
