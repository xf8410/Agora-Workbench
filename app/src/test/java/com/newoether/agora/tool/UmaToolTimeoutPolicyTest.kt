package com.newoether.agora.tool

import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class UmaToolTimeoutPolicyTest {
    @Test
    fun `small status endpoint keeps bounded timeout`() {
        assertEquals(
            Constants.UMA_SO_SMALL_READ_TIMEOUT_MS,
            umaSoReadTimeoutMs("/summary", 128 * 1024),
        )
    }

    @Test
    fun `complete class enumeration receives long timeout`() {
        assertEquals(
            Constants.UMA_SO_LARGE_READ_TIMEOUT_MS,
            umaSoReadTimeoutMs("/il2cpp/classes", 8 * 1024 * 1024),
        )
    }

    @Test
    fun `large arbitrary SO response receives long timeout`() {
        assertEquals(
            Constants.UMA_SO_LARGE_READ_TIMEOUT_MS,
            umaSoReadTimeoutMs("/debug/custom", 16 * 1024 * 1024),
        )
    }
}
