package com.mavislibrary.app.data.api

import com.mavislibrary.app.data.secure.Secret
import com.mavislibrary.app.data.secure.SecureStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AccountRepository(private val api: AccountsApi, private val secrets: SecureStore, private val adult: suspend () -> Boolean) {
    private val mutation = Mutex()
    val signedIn = secrets.has(Secret.ACCOUNT_TOKEN)
    private suspend fun requireAdult() { check(adult()) { "Leave Kids mode to manage accounts." } }
    suspend fun authenticate(action: String, email: String, password: String, code: String = ""): String? = mutation.withLock {
        requireAdult()
        val session = api.authenticate(action, email, password, code)
        requireAdult()
        check(session.token.isNotBlank() && session.user.id.isNotBlank() && secrets.writeAll(mapOf(Secret.ACCOUNT_TOKEN to session.token, Secret.ACCOUNT_USER_ID to session.user.id))) { "Android refused to store the session securely. Please try again." }
        session.recoveryCode
    }
    /** Forgot password: checks the form locally, then saves the session like a sign-in. Returns the replacement recovery code to show once. */
    suspend fun resetPassword(email: String, recoveryCode: String, password: String, confirm: String): String? {
        if (email.isBlank() || recoveryCode.isBlank() || password.isEmpty()) throw ApiException("bad_request", "Enter your email, recovery code and a new password.", 400)
        if (password != confirm) throw ApiException("confirm_mismatch", "Passwords don't match.")
        return try { authenticate("recover", email, password, recoveryCode) } catch (e: ApiException) {
            // The server's own message wins; this only replaces the generic fallback when it sent none.
            if (e.status == 401 && e.message == "The request failed (401).") throw ApiException("mismatch", "That email and recovery code don't match.", 401)
            throw e
        }
    }
    suspend fun me(): AccountUser = mutation.withLock {
        requireAdult()
        val session = api.me()
        requireAdult()
        val values = buildMap { put(Secret.ACCOUNT_USER_ID, session.user.id); session.token?.let { put(Secret.ACCOUNT_TOKEN, it) } }
        check(secrets.writeAll(values)) { "Could not refresh the secure session." }
        session.user
    }
    suspend fun signOut() = mutation.withLock { requireAdult(); secrets.clearAll(Secret.ACCOUNT_TOKEN, Secret.ACCOUNT_USER_ID) }
    suspend fun status(): SubscriptionStatus { requireAdult(); return api.status() }
    suspend fun requestAccess(message: String) { requireAdult(); api.requestAccess(message) }
    suspend fun subscribers(): List<Subscriber> {
        requireAdult()
        val result = mutableListOf<Subscriber>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            requireAdult()
            val page = api.adminList(cursor)
            result += page.items.filter { it.state in setOf("pending", "active") }
            cursor = page.cursor
            check(cursor == null || cursors.add(cursor)) { "The approval list could not be completed. Refresh to retry." }
        } while (cursor != null)
        return result
    }
    suspend fun decide(action: String, userId: String) { requireAdult(); api.decide(action, userId) }
}
