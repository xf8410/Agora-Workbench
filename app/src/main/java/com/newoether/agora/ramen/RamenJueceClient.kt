package com.newoether.agora.ramen

import com.newoether.agora.util.Constants
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * juece-ramen decision datasource contract — field names are fixed by the peer API
 * on 127.0.0.1:18767 and must not be renamed here.
 */
@Serializable
data class RamenHealth(
    val ok: Boolean,
    val app: String = "",
    val version: String = "",
)

@Serializable
data class RamenStatus(
    @SerialName("queue_len") val queueLen: Long = 0,
    @SerialName("recent_len") val recentLen: Long = 0,
    @SerialName("uploaded_total") val uploadedTotal: Long = 0,
    @SerialName("dropped_total") val droppedTotal: Long = 0,
    @SerialName("token_configured") val tokenConfigured: Boolean = false,
    /** Persisted decision-record total; absent on peers before the persistence rework. */
    @SerialName("persisted_len") val persistedLen: Long? = null,
    /** Number of persisted run files; absent on peers before the persistence rework. */
    @SerialName("persisted_runs") val persistedRuns: Long? = null,
)

/** Decision-log records are passed through untyped: the peer schema may grow while it is developed in parallel. */
@Serializable
data class RamenDataPage(
    val count: Int = 0,
    val records: List<JsonElement> = emptyList(),
)

@Serializable
data class RamenClearResult(
    val ok: Boolean,
    val deleted: Long = 0,
)

/** Builds the bounded /data query path; kept top-level so the bounds are unit-testable. */
internal fun ramenDataPath(limit: Int, after: Long): String {
    val boundedLimit = limit.coerceIn(1, Constants.RAMEN_DATA_LIMIT_MAX)
    val boundedAfter = after.coerceAtLeast(0L)
    return "${Constants.RAMEN_PATH_DATA}?limit=$boundedLimit&after=$boundedAfter"
}

/** Validates a user-configured juece-ramen base URL; returns a Chinese error message or null when valid. */
fun validateRamenBaseUrl(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return "地址不能为空"
    if (value.length > Constants.RAMEN_BASE_URL_MAX_LENGTH) {
        return "地址过长（最多 ${Constants.RAMEN_BASE_URL_MAX_LENGTH} 字符）"
    }
    if (!value.startsWith("http://")) return "地址必须以 http:// 开头"
    if (value.any { it.isWhitespace() || it == '\u0000' }) return "地址不能包含空白或控制字符"
    return null
}

/**
 * Loopback-only JSON client for the juece-ramen decision datasource
 * (GET /health /status /data /summary, DELETE /data). Style follows UmaStorageFilesClient:
 * HttpURLConnection, bounded reads, no invented headers, failures carry a readable message.
 */
class RamenJueceClient(
    private val baseUrl: String = RamenDataSourceStore.baseUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun healthRaw(): String = request("GET", Constants.RAMEN_PATH_HEALTH)
    suspend fun health(): RamenHealth = json.decodeFromString<RamenHealth>(healthRaw()).also { require(it.ok) { "juece-ramen health returned ok=false" } }

    suspend fun statusRaw(): String = request("GET", Constants.RAMEN_PATH_STATUS)
    suspend fun status(): RamenStatus = json.decodeFromString(statusRaw())

    suspend fun dataRaw(limit: Int, after: Long): String = request("GET", ramenDataPath(limit, after))
    suspend fun data(limit: Int, after: Long): RamenDataPage = json.decodeFromString(dataRaw(limit, after))

    suspend fun summaryRaw(): String = request("GET", Constants.RAMEN_PATH_SUMMARY)

    suspend fun clearDataRaw(): String = request("DELETE", Constants.RAMEN_PATH_DATA)
    suspend fun clearData(): RamenClearResult = json.decodeFromString<RamenClearResult>(clearDataRaw()).also { require(it.ok) { "juece-ramen clear returned ok=false" } }

    private suspend fun request(method: String, path: String): String = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = Constants.RAMEN_CONNECT_TIMEOUT_MS
            connection.readTimeout = Constants.RAMEN_READ_TIMEOUT_MS
            connection.useCaches = false
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reader = stream?.bufferedReader(Charsets.UTF_8)
                ?: throw IllegalStateException("juece-ramen returned HTTP $code without a body")
            reader.use {
                val out = StringBuilder(minOf(Constants.RAMEN_MAX_RESPONSE_CHARS, 8_192))
                val chunk = CharArray(16 * 1024)
                while (true) {
                    val count = it.read(chunk)
                    if (count < 0) break
                    if (out.length + count > Constants.RAMEN_MAX_RESPONSE_CHARS) {
                        throw IllegalStateException(
                            "juece-ramen response exceeded the ${Constants.RAMEN_MAX_RESPONSE_CHARS / 1024} KiB limit; use a smaller limit",
                        )
                    }
                    out.append(chunk, 0, count)
                }
                if (code !in 200..299) throw IllegalStateException("juece-ramen HTTP $code: ${out.toString().take(300)}")
                out.toString()
            }
        } catch (timeout: SocketTimeoutException) {
            throw IllegalStateException(
                "juece-ramen ${method.lowercase()} $path timed out after ${Constants.RAMEN_READ_TIMEOUT_MS / 1000}s",
                timeout,
            )
        } finally {
            connection.disconnect()
        }
    }
}
