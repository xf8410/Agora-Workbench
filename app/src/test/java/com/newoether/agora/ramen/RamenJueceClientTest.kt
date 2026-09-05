package com.newoether.agora.ramen

import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RamenJueceClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun dataPathUsesContractParameterNames() {
        assertEquals("/data?limit=50&after=0", ramenDataPath(50, 0))
        assertEquals("/data?limit=200&after=17", ramenDataPath(200, 17))
        assertEquals("/data?limit=1&after=0", ramenDataPath(0, -5))
        assertEquals("/data?limit=500&after=0", ramenDataPath(10_000, 0))
    }

    @Test
    fun healthResponseParsesContractFields() {
        val health = json.decodeFromString<RamenHealth>(
            """{"ok":true,"app":"juece-ramen","version":"0.4.2"}""",
        )
        assertTrue(health.ok)
        assertEquals("juece-ramen", health.app)
        assertEquals("0.4.2", health.version)
    }

    @Test
    fun statusResponseParsesContractFieldsVerbatim() {
        val status = json.decodeFromString<RamenStatus>(
            """{"queue_len":3,"recent_len":12,"uploaded_total":100,"dropped_total":2,"token_configured":true}""",
        )
        assertEquals(3L, status.queueLen)
        assertEquals(12L, status.recentLen)
        assertEquals(100L, status.uploadedTotal)
        assertEquals(2L, status.droppedTotal)
        assertTrue(status.tokenConfigured)
    }

    @Test
    fun dataPageKeepsRecordsUntyped() {
        val page = json.decodeFromString<RamenDataPage>(
            """{"count":1,"records":[{"type":"turn","run":"r1","ts":1787966553000,"turn":12,""" +
                """"key":"12:demo","summary":{"hp":1},"decision":{"act":0},"outcome":null,"seq":9,"extra":"ignored"}]}""",
        )
        assertEquals(1, page.count)
        assertEquals(1, page.records.size)
        assertTrue(page.records[0].toString().contains(""""run":"r1""""))
    }

    @Test
    fun clearResultParsesContractFields() {
        val result = json.decodeFromString<RamenClearResult>("""{"ok":true,"deleted":15}""")
        assertTrue(result.ok)
        assertEquals(15L, result.deleted)
    }

    @Test
    fun baseUrlValidationEnforcesHttpPrefixAndSanity() {
        assertNull(validateRamenBaseUrl("http://127.0.0.1:18767"))
        assertNull(validateRamenBaseUrl("  http://192.168.1.5:18767/  "))
        assertEquals("地址不能为空", validateRamenBaseUrl("   "))
        assertEquals("地址必须以 http:// 开头", validateRamenBaseUrl("https://127.0.0.1:18767"))
        assertEquals("地址必须以 http:// 开头", validateRamenBaseUrl("127.0.0.1:18767"))
        assertTrue(
            validateRamenBaseUrl("http://127.0.0.1:18767/${"a".repeat(Constants.RAMEN_BASE_URL_MAX_LENGTH)}")
                ?.startsWith("地址过长") == true,
        )
        assertEquals("地址不能包含空白或控制字符", validateRamenBaseUrl("http://127.0.0.1:18767/x y"))
    }
}
