package com.newoether.agora.uma

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Local bounded protocol observation for hlpatch v3.25+. */
object UmaProtocolCapture {
    private const val BASE = "http://127.0.0.1:18765"
    private const val MAX_METADATA_CHARS = 512 * 1024

    suspend fun setEnabled(enabled: Boolean): String = withContext(Dispatchers.IO) {
        getBounded("/api/sniff/toggle?enabled=${if (enabled) 1 else 0}", 32 * 1024)
    }

    suspend fun clear(): String = withContext(Dispatchers.IO) {
        getBounded("/api/sniff/clear", 32 * 1024)
    }

    suspend fun readSanitizedMetadata(): String = withContext(Dispatchers.IO) {
        val raw = getBounded("/api/sniff/metadata", MAX_METADATA_CHARS)
        val source = JSONObject(raw)
        // v3.25 already emits metadata only. Rebuild it anyway so future SO additions cannot
        // accidentally pass headers, cookies, tokens or payload text into the model.
        val entries = source.optJSONArray("entries")
        if (entries != null) {
            val out = JSONObject()
                .put("ok", true)
                .put("enabled", source.optBoolean("enabled", false))
                .put("after_id", source.optLong("after_id", 0))
                .put("last_id", source.optLong("last_id", 0))
                .put("privacy", "payloads, headers, cookies, tokens, text and hex are omitted")
            val clean = JSONArray()
            val start = maxOf(0, entries.length() - 100)
            for (i in start until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                clean.put(JSONObject()
                    .put("id", item.optLong("id", i.toLong()))
                    .put("request_id", item.optLong("request_id", 0))
                    .put("timestamp_ms", item.optLong("timestamp_ms", 0))
                    .put("direction", item.optString("direction", "unknown").take(16))
                    .put("path", sanitizePath(item.optString("path", "")))
                    .put("size", item.optLong("size", -1)))
            }
            return@withContext out.put("count", clean.length()).put("entries", clean).toString()
        }

        // Backward-compatible sanitizer for pre-v3.25 SO builds.
        val out = JSONObject()
            .put("ok", true)
            .put("enabled", source.optBoolean("enabled", false))
            .put("privacy", "payloads, headers, cookies, tokens, text and hex are omitted")
        out.put("requests", sanitizeLegacy(source.optJSONArray("requests"), "request"))
        out.put("responses", sanitizeLegacy(source.optJSONArray("responses"), "response"))
        out.toString()
    }

    private fun sanitizeLegacy(items: JSONArray?, direction: String): JSONArray {
        val out = JSONArray()
        if (items == null) return out
        val start = maxOf(0, items.length() - 20)
        for (i in start until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            out.put(JSONObject()
                .put("direction", direction)
                .put("id", item.optLong("id", i.toLong()))
                .put("path", sanitizePath(item.optString("url", "")))
                .put("size", item.optLong("size", -1L)))
        }
        return out
    }

    private fun sanitizePath(raw: String): String = runCatching {
        val uri = URI(raw)
        (uri.path ?: raw.substringBefore('?')).take(500)
    }.getOrElse { raw.substringBefore('?').takeLast(500) }

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
