package com.newoether.agora.uma

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UmaSessionJsonCatalogTest {
    private val catalog = UmaSessionJsonCatalog()

    @Test
    fun `classifies request payload from source path`() {
        val role = catalog.decoded(
            "protocol/request/exchange-1/payload.bin",
            "derived/messagepack/protocol/request/exchange-1/payload.bin.json",
            buildJsonObject { put("command_type", 1); put("turn", 4) },
        )
        assertEquals("request", role.direction)
        assertEquals("protocol_request_payload", role.role)
        assertEquals(listOf("command_type", "turn"), role.top_level_keys)
        assertEquals("high", role.confidence)
    }

    @Test
    fun `classifies response payload from source path`() {
        val role = catalog.decoded(
            "protocol/response/exchange-1/payload.bin",
            "derived/messagepack/protocol/response/exchange-1/payload.bin.json",
            buildJsonObject { put("result", 1) },
        )
        assertEquals("response", role.direction)
        assertEquals("protocol_response_payload", role.role)
    }

    @Test
    fun `text report contains purpose source and fields`() {
        val role = catalog.decoded(
            "protocol/response/exchange-1/payload.bin",
            "derived/messagepack/protocol/response/exchange-1/payload.bin.json",
            buildJsonObject { put("result", 1) },
        )
        val text = catalog.renderText("session-1", catalog.builtInFiles() + role)
        assertTrue(text.contains("Uma Session JSON 文件用途报告"))
        assertTrue(text.contains("原始来源: protocol/response/exchange-1/payload.bin"))
        assertTrue(text.contains("顶层字段: result"))
        assertTrue(text.contains("manifest.json"))
    }
}
