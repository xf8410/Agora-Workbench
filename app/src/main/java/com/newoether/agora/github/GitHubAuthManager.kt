package com.newoether.agora.github

import android.content.Context
import com.newoether.agora.util.SecretCrypto
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GitHubSession(
    val login: String,
    val token: String,
    val scopes: String = "",
)

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,
)

/** GitHub credentials never enter Room, chat messages, shell commands, URLs or logs. */
class GitHubAuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("github_auth", Context.MODE_PRIVATE)

    fun loadSession(): GitHubSession? {
        val token = SecretCrypto.decrypt(prefs.getString(KEY_TOKEN, "") ?: "")
        val login = prefs.getString(KEY_LOGIN, "") ?: ""
        if (token.isBlank() || login.isBlank()) return null
        return GitHubSession(login, token, prefs.getString(KEY_SCOPES, "") ?: "")
    }

    fun savedClientId(): String = prefs.getString(KEY_CLIENT_ID, "") ?: ""

    fun saveClientId(value: String) {
        prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()
    }

    fun logout() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).remove(KEY_SCOPES).apply()
    }

    suspend fun loginWithToken(token: String): Result<GitHubSession> = withContext(Dispatchers.IO) {
        val clean = token.trim()
        if (clean.isBlank()) return@withContext Result.failure(IllegalArgumentException("Token is empty"))
        runCatching {
            val response = request("https://api.github.com/user", "GET", clean)
            if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}")
            val login = Json.parseToJsonElement(response.body).jsonObject["login"]?.jsonPrimitive?.content
                ?: error("GitHub response did not contain a login")
            val session = GitHubSession(login, clean, response.scopes)
            save(session)
            session
        }
    }

    suspend fun requestDeviceCode(clientId: String): Result<GitHubDeviceCode> = withContext(Dispatchers.IO) {
        val clean = clientId.trim()
        if (clean.isBlank()) return@withContext Result.failure(IllegalArgumentException("OAuth Client ID is empty"))
        runCatching {
            saveClientId(clean)
            val body = form(mapOf(
                "client_id" to clean,
                "scope" to "repo workflow read:user codespace",
            ))
            val response = request("https://github.com/login/device/code", "POST", body = body)
            if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}")
            val obj = Json.parseToJsonElement(response.body).jsonObject
            GitHubDeviceCode(
                deviceCode = obj.getValue("device_code").jsonPrimitive.content,
                userCode = obj.getValue("user_code").jsonPrimitive.content,
                verificationUri = obj.getValue("verification_uri").jsonPrimitive.content,
                expiresIn = obj.getValue("expires_in").jsonPrimitive.content.toInt(),
                interval = obj["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
            )
        }
    }

    suspend fun completeDeviceFlow(clientId: String, code: GitHubDeviceCode): Result<GitHubSession> =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval.coerceAtLeast(5)
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                val response = request(
                    "https://github.com/login/oauth/access_token",
                    "POST",
                    body = form(mapOf(
                        "client_id" to clientId.trim(),
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    )),
                )
                val obj = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                val token = obj?.get("access_token")?.jsonPrimitive?.content
                if (!token.isNullOrBlank()) return@withContext loginWithToken(token)
                when (obj?.get("error")?.jsonPrimitive?.content) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    "access_denied" -> return@withContext Result.failure(Exception("Authorization denied"))
                    "expired_token" -> return@withContext Result.failure(Exception("Device code expired"))
                    null -> return@withContext Result.failure(Exception("Invalid GitHub response"))
                    else -> return@withContext Result.failure(Exception(obj["error_description"]?.jsonPrimitive?.content ?: "GitHub authorization failed"))
                }
            }
            Result.failure(Exception("Device code expired"))
        }

    private fun save(session: GitHubSession) {
        prefs.edit()
            .putString(KEY_TOKEN, SecretCrypto.encrypt(session.token))
            .putString(KEY_LOGIN, session.login)
            .putString(KEY_SCOPES, session.scopes)
            .apply()
    }

    private data class Response(val code: Int, val body: String, val scopes: String)

    private fun request(url: String, method: String, token: String = "", body: String? = null): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Agora-Workbench")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Response(code, text, connection.getHeaderField("X-OAuth-Scopes").orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
        const val KEY_SCOPES = "scopes"
        const val KEY_CLIENT_ID = "oauth_client_id"
    }
}
