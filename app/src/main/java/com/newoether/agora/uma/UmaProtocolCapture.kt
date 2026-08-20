package com.newoether.agora.uma

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 协议观察读取（hlpatch v3.25+）。 */
object UmaProtocolCapture {
    private const val BASE = "http://127.0.0.1:18765"

    suspend fun setEnabled(enabled: Boolean): String = withContext(Dispatchers.IO) {
        get("/api/sniff/toggle?enabled=${if (enabled) 1 else 0}")
    }

    suspend fun clear(): String = withContext(Dispatchers.IO) {
        get("/api/sniff/clear")
    }

    suspend fun readMetadata(): String = withContext(Dispatchers.IO) {
        get("/api/sniff/metadata")
    }

    private fun get(path: String): String {
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
                val out = StringBuilder()
                val buf = CharArray(8_192)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    out.append(buf, 0, n)
                }
                if (code !in 200..299) error("hlpatch HTTP $code: ${out.take(300)}")
                return out.toString()
            }
        } finally { c.disconnect() }
    }
}
