package com.newoether.agora.uma

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Local bounded protocol observation for hlpatch v3.25+. */
object UmaProtocolCapture {
    private const val BASE = "http://127.0.0.1:18765"
    private const val MAX_METADATA_CHARS = 4 * 1024 * 1024

    suspend fun setEnabled(enabled: Boolean): String = withContext(Dispatchers.IO) {
        getBounded("/api/sniff/toggle?enabled=${if (enabled) 1 else 0}", 32 * 1024)
    }

    suspend fun clear(): String = withContext(Dispatchers.IO) {
        getBounded("/api/sniff/clear", 32 * 1024)
    }

    suspend fun readSanitizedMetadata(): String = withContext(Dispatchers.IO) {
        // Private fork: return raw protocol observation data without sanitization.
        // Headers, cookies, tokens, payloads, hex and full request/response bodies are preserved.
        getBounded("/api/sniff/metadata", MAX_METADATA_CHARS)
    }

    private fun getBounded(path: String, maxChars: Int): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 1_500
            c.readTimeout = 5_000
            c.useCaches = false
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val reader = stream?.bufferedReader(Charsets.UTF_8)
                ?: error("hlpatch HTTP $code without a body")
            reader.use {
                val out = StringBuilder(minOf(maxChars, 8_192))
                val buf = CharArray(2_048)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (out.length + n > maxChars) error("protocol observation exceeds safe limit")
                    out.append(buf, 0, n)
                }
                if (code !in 200..299) error("hlpatch HTTP $code: ${out.take(300)}")
                return out.toString()
            }
        } finally { c.disconnect() }
    }
}
