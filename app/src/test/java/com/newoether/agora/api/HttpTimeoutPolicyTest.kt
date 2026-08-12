package com.newoether.agora.api

import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTimeoutPolicyTest {
    @Test
    fun `streaming read timeout exceeds ordinary request timeout`() {
        assertTrue(HttpTimeoutPolicy.STREAM_READ_MINUTES > HttpTimeoutPolicy.ORDINARY_READ_MINUTES)
    }

    @Test
    fun `streaming timeout supports long reasoning requests`() {
        assertTrue(HttpTimeoutPolicy.STREAM_READ_MINUTES >= 30L)
        assertTrue(HttpTimeoutPolicy.STREAM_WRITE_MINUTES >= 5L)
    }
}
