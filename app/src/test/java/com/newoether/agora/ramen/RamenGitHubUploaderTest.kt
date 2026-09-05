package com.newoether.agora.ramen

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RamenGitHubUploaderTest {
    private val fixedTime: ZonedDateTime =
        ZonedDateTime.of(2026, 9, 1, 12, 34, 56, 789_000_000, ZoneId.of("Asia/Shanghai"))

    private fun record(seq: Long, run: String = "r20260829_012233") = buildJsonObject {
        put("seq", seq)
        put("type", "turn")
        put("run", run)
        put("ts", 1787966553000)
        put("turn", 12)
        put("key", "12:demo")
    }

    @Test
    fun objectPathMatchesPeerFallbackChannelFormat() {
        assertEquals(
            "data/20260901/123456-789-abcdef12.jsonl",
            ramenUploadObjectPath(fixedTime, "abcdef12"),
        )
    }

    @Test
    fun uuid8IsEightHexCharacters() {
        repeat(20) {
            val value = ramenUuid8()
            assertEquals(8, value.length)
            assertTrue(value.all { it.isDigit() || it in 'a'..'f' })
        }
    }

    @Test
    fun jsonlKeepsRecordsRawOnePerLine() {
        val records = listOf(
            record(1),
            buildJsonObject {
                put("seq", 2)
                put("type", "outcome")
                put("run", "r20260829_012233")
            },
        )
        val lines = buildRamenJsonl(records).split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines.last().isEmpty())
        assertEquals("""{"seq":1,"type":"turn","run":"r20260829_012233","ts":1787966553000,"turn":12,"key":"12:demo"}""", lines[0])
        assertEquals("""{"seq":2,"type":"outcome","run":"r20260829_012233"}""", lines[1])
    }

    @Test
    fun recordSeqExtractsTheCursorField() {
        assertEquals(42L, ramenRecordSeq(record(42)))
        assertNull(ramenRecordSeq(buildJsonObject { put("type", "turn") }))
        assertNull(ramenRecordSeq(JsonPrimitive("not an object")))
    }

    @Test
    fun nextAfterAdvancesToTheMaxSequence() {
        val page = listOf(record(7), record(3), record(11))
        assertEquals(11L, ramenNextAfter(page, pageLimit = 2, currentAfter = 0))
    }

    @Test
    fun nextAfterReturnsNullForShortPageWithoutSeq() {
        val page = listOf(buildJsonObject { put("type", "turn") })
        assertNull(ramenNextAfter(page, pageLimit = 200, currentAfter = 5))
    }

    @Test
    fun nextAfterRejectsFullPageWithoutSeq() {
        val page = (1..200L).map { buildJsonObject { put("type", "turn") } }
        assertThrows(IllegalStateException::class.java) {
            ramenNextAfter(page, pageLimit = 200, currentAfter = 0)
        }
    }

    @Test
    fun nextAfterRejectsNonAdvancingCursor() {
        val page = listOf(record(5))
        assertThrows(IllegalArgumentException::class.java) {
            ramenNextAfter(page, pageLimit = 200, currentAfter = 5)
        }
    }

    @Test
    fun commitMessageCarriesTheRecordCount() {
        assertEquals("Agora 收集数据工作台上传 123 条决策记录", ramenCommitMessage(123))
    }

    @Test
    fun uploadBranchIsLockedToTheFixedRepositoryDefault() {
        assertEquals("main", requireRamenUploadBranch("main"))
        assertThrows(IllegalArgumentException::class.java) {
            requireRamenUploadBranch("workbench/ramen-data")
        }
    }

    @Test
    fun statusParsesPersistedFieldsWhenPresent() {
        val status = Json.decodeFromString<RamenStatus>(
            """{"queue_len":1,"recent_len":2,"uploaded_total":3,"dropped_total":4,"token_configured":true,""" +
                """"persisted_len":120,"persisted_runs":7}""",
        )
        assertEquals(120L, status.persistedLen)
        assertEquals(7L, status.persistedRuns)
    }

    @Test
    fun statusStaysCompatibleWithPeersWithoutPersistedFields() {
        val status = Json.decodeFromString<RamenStatus>(
            """{"queue_len":1,"recent_len":2,"uploaded_total":3,"dropped_total":4,"token_configured":false}""",
        )
        assertNull(status.persistedLen)
        assertNull(status.persistedRuns)
    }
}
