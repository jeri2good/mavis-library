package com.mavislibrary.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

@Serializable data class AccountUser(val id: String, val email: String)
@Serializable data class AccountSession(val token: String, val user: AccountUser, val recoveryCode: String? = null)
@Serializable data class AccountMe(val user: AccountUser, val token: String? = null)
@Serializable data class Usage(val used: Int = 0, val cap: Int = 0)
@Serializable data class SubscriptionStatus(
    val state: String, val plan: String? = null, val podcastEnabled: Boolean = false,
    val since: Long? = null, val expiresAt: Long? = null, val usage: Map<String, Usage> = emptyMap(),
) {
    fun canRequest(now: Long = System.currentTimeMillis()) = state in setOf("none", "revoked") ||
        state == "denied" && since != null && now >= since + 7 * 86400_000L
    fun active(now: Long = System.currentTimeMillis()) = state == "active" && (expiresAt == null || expiresAt > now)
}
@Serializable data class Subscriber(val userId: String, val email: String, val state: String, val plan: String? = null, val message: String? = null)
@Serializable data class SubscriberPage(val items: List<Subscriber>, val cursor: String? = null)

fun loungeError(status: Int, body: String): ApiException {
    val code = runCatching { ApiJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
    val message = when (status) {
        401 -> "Sign in to use Podcast Lounge"
        403 -> "Request access"
        429 -> if (code == "studio_busy") "Studio busy, try later" else if (code == "active_limit") "Two podcasts are already in progress" else "Daily limit reached"
        502, 503 -> "Studio unavailable, try later"
        else -> MavisApi.errorFrom(status, body).message
    }
    return ApiException(code ?: "http_$status", message, status)
}

class AccountsApi(
    baseUrl: String,
    private val execute: (suspend (Request) -> Response)? = null,
    private val token: suspend () -> String?,
    private val owner: suspend () -> String? = { null },
) {
    private val base = baseUrl.toHttpUrl()
    private val client by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build() }
    private suspend fun request(path: String, body: JsonObject? = null, admin: Boolean = false, authenticated: Boolean = true, params: List<Pair<String, String>> = emptyList()): String = withContext(Dispatchers.IO) {
        val url = base.newBuilder().encodedPath(path).apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store").header("x-mavis-source", "android-native")
        if (admin) builder.header("x-mavis-access", owner()?.takeIf(String::isNotBlank) ?: throw ApiException("owner_required", "Save an owner code first."))
        else if (authenticated) builder.header("Authorization", "Bearer ${token()?.takeIf(String::isNotBlank) ?: throw ApiException("sign_in_required", "Sign in to continue.", 401)}")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val request = builder.build()
        val response = try { execute?.invoke(request) ?: client.newCall(request).execute() }
        catch (_: IOException) { throw ApiException("unreachable", "Could not reach Mavis Library. Check your connection.", offline = true) }
        response.use {
            val source = it.body?.source() ?: throw ApiException("bad_response", "Empty library response.")
            source.request(1_000_001)
            if (source.buffer.size > 1_000_000) throw ApiException("bad_response", "Library response too large.")
            val text = source.readUtf8()
            if (!it.isSuccessful) throw MavisApi.errorFrom(it.code, text)
            text
        }
    }
    suspend fun authenticate(action: String, email: String, password: String, recoveryCode: String = ""): AccountSession {
        require(action in setOf("signin", "signup", "recover"))
        return ApiJson.decodeFromString<AccountSession>(request("/api/account/$action", buildJsonObject {
            put("email", email.trim()); put("password", password)
            if (action == "recover") put("recoveryCode", recoveryCode.trim())
        }, authenticated = false))
    }
    suspend fun me() = ApiJson.decodeFromString<AccountMe>(request("/api/account/me"))
    suspend fun status() = ApiJson.decodeFromString<SubscriptionStatus>(request("/api/subscription/status"))
    suspend fun requestAccess(message: String = "") {
        request("/api/subscription/request", buildJsonObject { if (message.isNotBlank()) put("message", message.take(200)) })
    }
    suspend fun adminList(cursor: String? = null) = ApiJson.decodeFromString<SubscriberPage>(request("/api/subscription/admin/list", admin = true,
        params = listOf("state" to "all", "limit" to "200") + (cursor?.let { listOf("cursor" to it) } ?: emptyList())))
    suspend fun decide(action: String, userId: String) {
        require(action in setOf("approve", "deny", "revoke"))
        request("/api/subscription/admin/$action", buildJsonObject { put("userId", userId) }, admin = true)
    }
}
