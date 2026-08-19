package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/** Timeout policy is explicit so long-running requests are not cut off by an elapsed-time limit. */
internal object HttpTimeoutPolicy {
    const val CONNECT_SECONDS = 45L
    // OkHttp treats zero as no read timeout. Calls remain cancellable via Call.cancel().
    const val ORDINARY_READ_MINUTES = 0L
    const val STREAM_READ_MINUTES = 0L
    const val STREAM_WRITE_MINUTES = 5L
}

object HttpClient {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "x-goog-api-key", "api-key")

    private fun isLocalHost(host: String): Boolean {
        if (host.isBlank()) return false
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".home") || h.endsWith(".internal")) return true
        if (!h.contains('.')) return true
        val octets = h.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val first = octets[0].toInt()
            val second = octets[1].toInt()
            return first == 127 || first == 10 || (first == 192 && second == 168) ||
                (first == 172 && second in 16..31) || (first == 169 && second == 254)
        }
        return false
    }

    private fun guardCleartextCredentials(url: String, headers: Map<String, String>) {
        if (!url.startsWith("http://", ignoreCase = true)) return
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (isLocalHost(host)) return
        if (headers.keys.any { it.lowercase() in CREDENTIAL_HEADERS }) {
            throw IOException("Refusing to send API credentials over cleartext HTTP to \"$host\". Use an https:// endpoint.")
        }
    }

    enum class ProxyType { HTTP, SOCKS }

    data class ProxyConfig(
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String = "",
        val password: String = "",
        val bypass: List<String> = emptyList(),
    )

    @Volatile private var proxyConfig: ProxyConfig? = null

    fun setProxy(config: ProxyConfig?) {
        proxyConfig = config?.takeIf { it.host.isNotBlank() && it.port in 1..65535 }
        val current = proxyConfig
        if (current != null && current.username.isNotBlank()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? =
                    if (requestorType == RequestorType.PROXY) {
                        java.net.PasswordAuthentication(current.username, current.password.toCharArray())
                    } else null
            })
        } else {
            java.net.Authenticator.setDefault(null)
        }
    }

    private fun resolveProxy(host: String): java.net.Proxy {
        val config = proxyConfig ?: return java.net.Proxy.NO_PROXY
        if (isProxyBypassed(host, config.bypass)) return java.net.Proxy.NO_PROXY
        val type = if (config.type == ProxyType.SOCKS) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
        return java.net.Proxy(type, java.net.InetSocketAddress.createUnresolved(config.host, config.port))
    }

    private fun isProxyBypassed(host: String, bypass: List<String>): Boolean {
        if (host.isBlank()) return true
        val normalized = host.lowercase().trim('[', ']')
        for (raw in bypass) {
            val entry = raw.trim().lowercase()
            when {
                entry.isEmpty() -> continue
                entry.contains('/') -> if (ipv4InCidr(normalized, entry)) return true
                entry.startsWith("*.") -> if (normalized == entry.drop(2) || normalized.endsWith(entry.drop(1))) return true
                normalized == entry -> return true
            }
        }
        return false
    }

    private fun ipv4ToLong(ip: String): Long? {
        val octets = ip.split('.')
        if (octets.size != 4) return null
        var value = 0L
        for (part in octets) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun ipv4InCidr(host: String, cidr: String): Boolean {
        val parts = cidr.split('/')
        if (parts.size != 2) return false
        val bits = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return false
        val ip = ipv4ToLong(host) ?: return false
        val network = ipv4ToLong(parts[0]) ?: return false
        val mask = if (bits == 0) 0L else (-1L shl (32 - bits)) and 0xffffffffL
        return (ip and mask) == (network and mask)
    }

    private val proxySelector = object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> =
            mutableListOf(resolveProxy(uri?.host ?: ""))
        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, error: IOException?) = Unit
    }

    private val proxyAuthenticator = object : okhttp3.Authenticator {
        override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): Request? {
            val config = proxyConfig
            if (config == null || config.username.isBlank() || config.type != ProxyType.HTTP) return null
            if (response.request.header("Proxy-Authorization") != null) return null
            return response.request.newBuilder()
                .header("Proxy-Authorization", okhttp3.Credentials.basic(config.username, config.password))
                .build()
        }
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(HttpTimeoutPolicy.CONNECT_SECONDS, TimeUnit.SECONDS)
        .proxySelector(proxySelector)
        .proxyAuthenticator(proxyAuthenticator)

    /** Ordinary requests have no elapsed read deadline and remain explicitly cancellable. */
    val client: OkHttpClient = baseBuilder()
        .readTimeout(HttpTimeoutPolicy.ORDINARY_READ_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Streaming generation has no elapsed read deadline and remains explicitly cancellable. */
    private val streamingClient: OkHttpClient = baseBuilder()
        .readTimeout(HttpTimeoutPolicy.STREAM_READ_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(HttpTimeoutPolicy.STREAM_WRITE_MINUTES, TimeUnit.MINUTES)
        .build()

    private val liveHandles: MutableSet<StreamHandle> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    class StreamHandle(
        private val call: okhttp3.Call,
        private val response: okhttp3.Response,
        private val scope: com.newoether.agora.viewmodel.StreamScope?,
    ) {
        val code: Int get() = response.code
        val source: BufferedSource? get() = response.body?.source()
        val errorBody: String? by lazy {
            try { response.body?.string() } catch (_: Exception) { null }
        }

        fun close() {
            liveHandles.remove(this)
            scope?.unregister(this)
            runCatching { call.cancel() }
            response.close()
        }

        fun readLine(): String? = source?.readUtf8Line()
        fun cancel() = call.cancel()
    }

    fun streamPost(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): StreamHandle =
        streamPost(url, jsonBody, headers, boundStreamScope())

    fun streamPost(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        scope: com.newoether.agora.viewmodel.StreamScope?,
    ): StreamHandle {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).post(jsonBody.toRequestBody(JSON))
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val call = streamingClient.newCall(requestBuilder.build())
        val handle = StreamHandle(call, call.execute(), scope)
        scope?.register(handle)
        liveHandles.add(handle)
        return handle
    }

    private val coroutineStreamScope = ThreadLocal<com.newoether.agora.viewmodel.StreamScope?>()

    internal suspend fun <T> withStreamScope(
        scope: com.newoether.agora.viewmodel.StreamScope?,
        block: suspend () -> T,
    ): T = withContext(coroutineStreamScope.asContextElement(scope)) { block() }

    internal fun boundStreamScope(): com.newoether.agora.viewmodel.StreamScope? = coroutineStreamScope.get()

    fun cancelAllStreams() {
        liveHandles.toList().forEach { runCatching { it.cancel() } }
        liveHandles.clear()
    }

    fun post(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String? {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).post(jsonBody.toRequestBody(JSON))
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        return client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string()
            else {
                DebugLog.e("HttpClient", "POST $url failed: ${response.code} ${response.body?.string()}")
                null
            }
        }
    }

    fun fetchModels(url: String, headers: Map<String, String> = emptyMap()): String? {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        return client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        return client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }
}
