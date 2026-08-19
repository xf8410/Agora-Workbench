package com.newoether.agora.uma

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UmaMessagePackDecodeException(val byteOffset: Int, message: String) :
    IllegalArgumentException("MessagePack decode failed at byte $byteOffset: $message")

data class UmaMessagePackJsonResult(val value: JsonElement, val consumedBytes: Int)

/** Strict, allocation-bounded MessagePack decoder used only for derived JSON beside untouched BIN. */
class UmaMessagePackJsonDecoder(
    private val maxDepth: Int = 128,
    private val maxCollectionEntries: Int = 1_000_000,
) {
    fun decode(bytes: ByteArray): UmaMessagePackJsonResult {
        val cursor = Cursor(bytes)
        val value = readValue(cursor, 0)
        if (cursor.position != bytes.size) fail(cursor.position, "trailing ${bytes.size - cursor.position} bytes")
        return UmaMessagePackJsonResult(value, cursor.position)
    }

    private fun readValue(c: Cursor, depth: Int): JsonElement {
        if (depth > maxDepth) fail(c.position, "maximum nesting depth exceeded")
        val markerOffset = c.position
        return when (val marker = c.u8()) {
            in 0x00..0x7f -> JsonPrimitive(marker)
            in 0x80..0x8f -> readMap(c, marker and 0x0f, depth + 1)
            in 0x90..0x9f -> readArray(c, marker and 0x0f, depth + 1)
            in 0xa0..0xbf -> JsonPrimitive(c.utf8(marker and 0x1f))
            0xc0 -> JsonNull
            0xc2 -> JsonPrimitive(false)
            0xc3 -> JsonPrimitive(true)
            0xc4 -> binary(c.bytes(c.u8()))
            0xc5 -> binary(c.bytes(c.u16()))
            0xc6 -> binary(c.bytes(c.length32()))
            0xc7 -> readVariableExtension(c, c.u8())
            0xc8 -> readVariableExtension(c, c.u16())
            0xc9 -> readVariableExtension(c, c.length32())
            0xca -> JsonPrimitive(Float.fromBits(c.i32()).toDouble())
            0xcb -> JsonPrimitive(Double.fromBits(c.i64()))
            0xcc -> JsonPrimitive(c.u8())
            0xcd -> JsonPrimitive(c.u16())
            0xce -> JsonPrimitive(c.u32())
            0xcf -> JsonPrimitive(c.u64String())
            0xd0 -> JsonPrimitive(c.i8())
            0xd1 -> JsonPrimitive(c.i16())
            0xd2 -> JsonPrimitive(c.i32())
            0xd3 -> JsonPrimitive(c.i64())
            0xd4 -> extension(c.i8(), c.bytes(1))
            0xd5 -> extension(c.i8(), c.bytes(2))
            0xd6 -> extension(c.i8(), c.bytes(4))
            0xd7 -> extension(c.i8(), c.bytes(8))
            0xd8 -> extension(c.i8(), c.bytes(16))
            0xd9 -> JsonPrimitive(c.utf8(c.u8()))
            0xda -> JsonPrimitive(c.utf8(c.u16()))
            0xdb -> JsonPrimitive(c.utf8(c.length32()))
            0xdc -> readArray(c, c.u16(), depth + 1)
            0xdd -> readArray(c, c.length32(), depth + 1)
            0xde -> readMap(c, c.u16(), depth + 1)
            0xdf -> readMap(c, c.length32(), depth + 1)
            in 0xe0..0xff -> JsonPrimitive(marker - 256)
            else -> fail(markerOffset, "unsupported marker 0x${marker.toString(16)}")
        }
    }

    private fun readVariableExtension(c: Cursor, length: Int): JsonElement {
        val type = c.i8()
        return extension(type, c.bytes(length))
    }

    private fun readArray(c: Cursor, count: Int, depth: Int): JsonArray {
        requireCount(c, count)
        return buildJsonArray { repeat(count) { add(readValue(c, depth)) } }
    }

    private fun readMap(c: Cursor, count: Int, depth: Int): JsonElement {
        requireCount(c, count)
        val entries = ArrayList<Pair<JsonElement, JsonElement>>(count)
        var allStringKeys = true
        val seen = HashSet<String>()
        repeat(count) {
            val key = readValue(c, depth)
            val value = readValue(c, depth)
            val text = (key as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text == null || !seen.add(text)) allStringKeys = false
            entries += key to value
        }
        return if (allStringKeys) JsonObject(entries.associate { (it.first as JsonPrimitive).content to it.second })
        else buildJsonObject {
            put(MSGPACK_MAP, buildJsonArray {
                entries.forEach { (key, value) -> add(buildJsonArray { add(key); add(value) }) }
            })
        }
    }

    /** Pure Kotlin RFC 4648 encoder: deterministic in local JVM tests and available below API 26. */
    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val hasSecond = index < bytes.size
            val second = if (hasSecond) bytes[index++].toInt() and 0xff else 0
            val hasThird = index < bytes.size
            val third = if (hasThird) bytes[index++].toInt() and 0xff else 0
            out.append(BASE64_ALPHABET[first ushr 2])
            out.append(BASE64_ALPHABET[((first and 0x03) shl 4) or (second ushr 4)])
            out.append(if (hasSecond) BASE64_ALPHABET[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            out.append(if (hasThird) BASE64_ALPHABET[third and 0x3f] else '=')
        }
        return out.toString()
    }

    private fun binary(bytes: ByteArray) = buildJsonObject {
        put(MSGPACK_BINARY_BASE64, encodeBase64(bytes))
        put("byte_length", bytes.size)
    }

    private fun extension(type: Int, bytes: ByteArray) = buildJsonObject {
        put(MSGPACK_EXTENSION, buildJsonObject {
            put("type", type)
            put("data_base64", encodeBase64(bytes))
            put("byte_length", bytes.size)
        })
    }

    private fun requireCount(c: Cursor, count: Int) {
        if (count < 0 || count > maxCollectionEntries) fail(c.position, "collection count $count is out of bounds")
    }

    private fun fail(offset: Int, message: String): Nothing = throw UmaMessagePackDecodeException(offset, message)

    private class Cursor(private val data: ByteArray) {
        var position = 0
        private fun need(count: Int) {
            if (count < 0 || position > data.size - count) throw UmaMessagePackDecodeException(position, "unexpected EOF")
        }
        fun u8(): Int { need(1); return data[position++].toInt() and 0xff }
        fun i8(): Int = u8().let { if (it >= 128) it - 256 else it }
        fun u16(): Int = (u8() shl 8) or u8()
        fun i16(): Int = u16().let { if (it >= 0x8000) it - 0x10000 else it }
        fun i32(): Int { need(4); val start = position; position += 4; return ByteBuffer.wrap(data, start, 4).order(ByteOrder.BIG_ENDIAN).int }
        fun u32(): Long = i32().toLong() and 0xffffffffL
        fun i64(): Long { need(8); val start = position; position += 8; return ByteBuffer.wrap(data, start, 8).order(ByteOrder.BIG_ENDIAN).long }
        fun u64String(): String {
            val signed = i64()
            return if (signed >= 0) signed.toString() else java.lang.Long.toUnsignedString(signed)
        }
        fun length32(): Int {
            val value = u32()
            if (value > Int.MAX_VALUE) throw UmaMessagePackDecodeException(position - 4, "length exceeds Int range")
            return value.toInt()
        }
        fun bytes(count: Int): ByteArray { need(count); return data.copyOfRange(position, position + count).also { position += count } }
        fun utf8(count: Int): String {
            val bytes = bytes(count)
            val decoder = Charsets.UTF_8.newDecoder()
            return try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { throw UmaMessagePackDecodeException(position - count, "invalid UTF-8") }
        }
    }

    companion object {
        const val MSGPACK_MAP = "\$msgpack_map"
        const val MSGPACK_BINARY_BASE64 = "\$msgpack_binary_base64"
        const val MSGPACK_EXTENSION = "\$msgpack_extension"
        private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}
