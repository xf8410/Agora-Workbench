package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientTimeoutPolicyTest {

    @Test
    fun generationStreams_haveNoFixedReadOrCallTimeout() {
        assertEquals(0, HttpClient.streamingClient.readTimeoutMillis)
        assertEquals(0, HttpClient.streamingClient.callTimeoutMillis)
        assertTrue(HttpClient.streamingClient.connectTimeoutMillis > 0)
        assertTrue(HttpClient.streamingClient.writeTimeoutMillis > 0)
    }

    @Test
    fun ordinaryRequests_remainBounded() {
        assertTrue(HttpClient.client.connectTimeoutMillis > 0)
        assertTrue(HttpClient.client.writeTimeoutMillis > 0)
    }
}
