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

    /** 读协议观测。maxChars 上限防大缓冲 OOM（SO 侧每条含完整 body_hex，满缓冲可达数 MB）——
     *  超限不截断尾巴（半条 JSON 比没有更危险），整帧拒绝并提示分批清理。 */
    suspend fun readMetadata(maxChars: Int = 2 * 1024 * 1024): String = withContext(Dispatchers.IO) {
        get("/api/sniff/metadata", maxChars)
    }

    private fun get(path: String, maxChars: Int = Int.MAX_VALUE): String {
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
                    if (out.length + n > maxChars) {
                        error("hlpatch 响应超过上限 ${maxChars / 1024} KiB（连接观测缓冲很大）：" +
                            "先 uma_sniff_clear 清旧观测再读，或让 SO 侧调小缓冲上限")
                    }
                    out.append(buf, 0, n)
                }
                if (code !in 200..299) error("hlpatch HTTP $code: ${out.take(300)}")
                return out.toString()
            }
        } finally { c.disconnect() }
    }
}
