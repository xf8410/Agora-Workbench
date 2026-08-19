package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTimeoutPolicyTest {
    @Test
    fun `ordinary requests have no hard read timeout`() {
        // OkHttp defines a zero timeout as no timeout.
        assertEquals(0L, HttpTimeoutPolicy.ORDINARY_READ_MINUTES)
    }

    @Test
    fun `streaming generation has no hard read timeout`() {
        assertEquals(0L, HttpTimeoutPolicy.STREAM_READ_MINUTES)
        assertTrue(HttpTimeoutPolicy.STREAM_WRITE_MINUTES >= 5L)
    }
}
