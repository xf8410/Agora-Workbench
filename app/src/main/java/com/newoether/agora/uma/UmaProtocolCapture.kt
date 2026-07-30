package com.newoether.agora.uma

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicitly user-controlled protocol observation.
 *
 * Raw sniff material is read only inside this process and is never returned to the model. The
 * public result contains route/path, direction, size and local observation id only. Headers,
 * cookies, tokens, text and hex payloads are deliberately discarded.
 */
object UmaProtocolCapture {
    private const val BASE = "http://127.0.0.1:18765"
    private const val MAX_RAW_CHARS = 256 * 1024

    suspend fun setEnabled(enabled: Boolean): String = withContext(Dispatchers.IO) {
        val c = URL("$BASE/api/sniff/toggle").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 1_500
            c.readTimeout = 3_000
            c.setFixedLengthStreamingMode(1)
            c.outputStream.use { it.write(if (enabled) byteArrayOf('1'.code.toByte()) else byteArrayOf('0'.code.toByte())) }
            val code = c.responseCode
            if (code !in 200..299) error("hlpatch HTTP $code")
            JSONObject().put("ok", true).put("enabled", enabled).toString()
        } finally { c.disconnect() }
    }

    suspend fun readSanitizedMetadata(): String = withContext(Dispatchers.IO) {
        val raw = getBounded("/api/sniff", MAX_RAW_CHARS)
        val source = JSONObject(raw)
        val out = JSONObject()
            .put("ok", true)
            .put("enabled", source.optBoolean("enabled", false))
            .put("privacy", "payloads, headers, cookies, tokens, text and hex are omitted")
        out.put("requests", sanitize(source.optJSONArray("requests"), "request"))
        out.put("responses", sanitize(source.optJSONArray("responses"), "response"))
        out.toString()
    }

    private fun sanitize(items: JSONArray?, direction: String): JSONArray {
        val out = JSONArray()
        if (items == null) return out
        val start = maxOf(0, items.length() - 20)
        for (i in start until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val rawUrl = item.optString("url", "")
            val path = runCatching {
                val uri = URI(rawUrl)
                uri.path?.take(240).orEmpty()
            }.getOrElse {
                rawUrl.substringBefore('?').takeLast(240)
            }
            out.put(JSONObject()
                .put("direction", direction)
                .put("id", item.optLong("id", i.toLong()))
                .put("path", path)
                .put("size", item.optLong("size", -1L)))
        }
        return out
    }

    private fun getBounded(path: String, maxChars: Int): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 1_500
            c.readTimeout = 3_000
            c.useCaches = false
            if (c.responseCode != 200) error("hlpatch HTTP ${c.responseCode}")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder(minOf(maxChars, 8_192))
                val buf = CharArray(2_048)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (out.length + n > maxChars) error("protocol observation exceeds safe limit")
                    out.append(buf, 0, n)
                }
                return out.toString()
            }
        } finally { c.disconnect() }
    }
}
