package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTimeoutPolicyTest {
    @Test
    fun `ordinary requests have a finite read inactivity timeout`() {
        // A zero OkHttp read timeout means wait forever, so a server that accepts the connection and then
        // stalls would hang a tool call (web_search, web_fetch, generate_image) indefinitely.
        assertTrue(HttpTimeoutPolicy.ORDINARY_READ_MINUTES in 1L..10L)
    }

    @Test
    fun `streaming generation has no hard read timeout`() {
        assertEquals(0L, HttpTimeoutPolicy.STREAM_READ_MINUTES)
        assertTrue(HttpTimeoutPolicy.STREAM_WRITE_MINUTES >= 5L)
    }
}
