package com.newoether.agora.audit

import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming search over stored audit binaries. Never loads more than WINDOW_BYTES of the
 * file into memory; carry-over bytes bridge window boundaries so a match that straddles a
 * read window is never missed (audit_extract_strings is window-local; this is whole-file).
 */
object BytePatternSearcher {

    private const val WINDOW = 64L * 1024
    private const val TAIL = 4095 // max pattern gap bridged across windows

    data class Hit(val offset: Long, val hexPreview: String)

    /**
     * Finds up to [limit] occurrences of [needle] at/after [startOffset].
     * [needle] must be non-empty and at most [TAIL] + 1 bytes.
     */
    fun findAll(file: File, needle: ByteArray, startOffset: Long = 0L, limit: Int = 50): List<Hit> {
        require(needle.isNotEmpty()) { "search pattern is empty" }
        require(needle.size <= TAIL + 1) { "search pattern too long (>${TAIL + 1} bytes)" }
        require(file.isFile) { "stored bytes missing — re-import" }
        val hits = ArrayList<Hit>()
        RandomAccessFile(file, "r").use { raf ->
            var pos = startOffset.coerceIn(0L, raf.length())
            var overlap = needle.size - 1
            var eof = false
            var windowStart = pos
            var bufferStart = pos
            while (!eof && hits.size < limit) {
                raf.seek(windowStart)
                val chunk = ByteArray(WINDOW.toInt())
                var read = 0
                while (read < chunk.size) {
                    val n = raf.read(chunk, read, chunk.size - read)
                    if (n < 0) { eof = true; break }
                    read += n
                }
                if (read == 0) break
                // search region = carry-over overlap + fresh chunk, starting after bufferStart
                val searchBuf = ByteArray(read)
                System.arraycopy(chunk, 0, searchBuf, 0, read)
                var idx = indexOfFrom(searchBuf, needle, (bufferStart - windowStart).toInt())
                while (idx >= 0 && hits.size < limit) {
                    val absolute = windowStart + idx
                    if (absolute >= startOffset) {
                        val previewLen = minOf(needle.size + 16, searchBuf.size - idx)
                        hits += Hit(absolute, searchBuf.copyOfRange(idx, idx + previewLen).toHex())
                        if (hits.size >= limit) break
                    }
                    idx = indexOfFrom(searchBuf, needle, idx + 1)
                }
                if (eof || read < WINDOW.toInt()) break
                // next window: rewind by needle-1 bytes to bridge straddling matches
                windowStart += read - overlap
                bufferStart = windowStart + overlap // skip the overlap region's already-hit zone
            }
        }
        return hits
    }

    private fun indexOfFrom(data: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from > data.size - needle.size) return -1
        outer@
        for (i in maxOf(0, from)..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
