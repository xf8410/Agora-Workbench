package com.newoether.agora.uma

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UmaMessagePackJsonDecoderTest {
    private val decoder = UmaMessagePackJsonDecoder()

    @Test
    fun decodesNestedStringMapArrayBinaryAndExtension() {
        val bytes = byteArrayOf(
            0x83.toByte(),
            0xa1.toByte(), 'a'.code.toByte(), 0x01,
            0xa1.toByte(), 'b'.code.toByte(), 0x92.toByte(), 0xc3.toByte(), 0xff.toByte(),
            0xa1.toByte(), 'c'.code.toByte(), 0xc7.toByte(), 0x02, 0x05, 0x11, 0x22,
        )
        val root = decoder.decode(bytes).value as JsonObject
        assertEquals(1, (root.getValue("a") as JsonPrimitive).content.toInt())
        val array = root.getValue("b") as JsonArray
        assertEquals("true", (array[0] as JsonPrimitive).content)
        val extension = root.getValue("c") as JsonObject
        assertTrue(extension.containsKey(UmaMessagePackJsonDecoder.MSGPACK_EXTENSION))
    }

    @Test
    fun preservesNonStringAndDuplicateMapKeysAsEntryPairs() {
        val nonString = decoder.decode(byteArrayOf(0x81.toByte(), 0x01, 0xa1.toByte(), 'x'.code.toByte())).value as JsonObject
        assertTrue(nonString.containsKey(UmaMessagePackJsonDecoder.MSGPACK_MAP))

        val duplicate = decoder.decode(byteArrayOf(
            0x82.toByte(), 0xa1.toByte(), 'a'.code.toByte(), 0x01,
            0xa1.toByte(), 'a'.code.toByte(), 0x02,
        )).value as JsonObject
        assertTrue(duplicate.containsKey(UmaMessagePackJsonDecoder.MSGPACK_MAP))
    }

    @Test
    fun rejectsTrailingBytesWithExactOffset() {
        val failure = assertFailsWith<UmaMessagePackDecodeException> {
            decoder.decode(byteArrayOf(0x01, 0x02))
        }
        assertEquals(1, failure.byteOffset)
    }

    @Test
    fun commitAndRefBodiesNeverForceUpdate() {
        val shaA = "a".repeat(40)
        val shaB = "b".repeat(40)
        val commit = buildUmaGitCommitBody("archive session", shaA, shaB)
        assertEquals(shaA, (commit["tree"] as JsonPrimitive).content)
        val ref = buildUmaGitRefUpdateBody(shaA)
        assertFalse((ref["force"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun onlyWorkbenchBranchesAreAccepted() {
        assertEquals("workbench/session", requireUmaWorkbenchBranch("workbench/session"))
        assertFailsWith<IllegalArgumentException> { requireUmaWorkbenchBranch("main") }
    }
}
