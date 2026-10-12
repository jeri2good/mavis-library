package com.mavislibrary.app.data.api

import com.mavislibrary.app.data.podcast.fakeResponse
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AccountsApiTest {
    @Test fun statusParsesEveryStateUsageAndCooldown() = runTest {
        for (state in listOf("none", "pending", "active", "denied", "revoked")) {
            val api = AccountsApi("https://site.example.org", execute = {
                fakeResponse(it, """{"state":"$state","plan":"family","signedIn":true,"podcastEnabled":true,"since":1000,"expiresAt":null,"usage":{"podcast":{"used":2,"cap":3}}}""")
            }, token = { "test-session" })
            val status = api.status()
            assertEquals(state, status.state)
            assertEquals(Usage(2, 3), status.usage["podcast"])
            assertTrue(status.podcastEnabled)
            assertEquals(state in listOf("none", "revoked"), status.canRequest(2000))
            if (state == "denied") assertTrue(status.canRequest(1000 + 7 * 86400_000L))
        }
    }

    @Test fun bearerAndOwnerHeadersRemainIndependentAndBodiesMatchContract() = runTest {
        val seen = mutableListOf<Request>()
        val api = AccountsApi("https://site.example.org", execute = { request ->
            seen += request
            when {
                request.url.encodedPath.endsWith("/list") -> fakeResponse(request, """{"items":[],"cursor":null}""")
                request.url.encodedPath.endsWith("/signin") -> fakeResponse(request, """{"token":"returned-test-session","user":{"id":"user1","email":"member@example.org"}}""")
                else -> fakeResponse(request, """{"state":"pending","usage":{}}""")
            }
        }, token = { "test-session" }, owner = { "test-owner" })
        api.status(); api.requestAccess("Please add me"); api.adminList(); api.decide("approve", "user1")
        val signin = api.authenticate("signin", "member@example.org", "test-password")
        assertEquals("member@example.org", signin.user.email)
        assertEquals("Bearer test-session", seen[0].header("Authorization"))
        assertNull(seen[0].header("x-mavis-access"))
        assertEquals("test-owner", seen[2].header("x-mavis-access"))
        assertNull(seen[2].header("Authorization"))
        assertNull(seen[4].header("Authorization"))
        assertEquals("no-store", seen[0].header("Cache-Control"))
        val body = Buffer().apply { seen[3].body!!.writeTo(this) }.readUtf8()
        assertEquals("""{"userId":"user1"}""", body)
    }

    @Test fun friendlyGatewayErrorsNeverEchoCredentials() {
        val expected = mapOf(401 to "Sign in to use Podcast Lounge", 403 to "Request access", 429 to "Daily limit reached", 503 to "Studio unavailable, try later", 502 to "Studio unavailable, try later")
        expected.forEach { (code, message) ->
            assertEquals(message, loungeError(code, """{"message":"private diagnostics"}""").message)
        }
        assertEquals("Studio busy, try later", loungeError(429, """{"code":"studio_busy"}""").message)
    }

    @Test fun signupAndRecoveryBodiesAndOwnerPaginationMatchTheLiveContract() = runTest {
        val seen = mutableListOf<Request>()
        val api = AccountsApi("https://site.example.org", execute = {
            seen += it
            if (it.url.encodedPath.endsWith("/list")) fakeResponse(it, """{"items":[],"cursor":null}""")
            else fakeResponse(it, """{"token":"fixture-session","user":{"id":"user1","email":"member@example.org"},"recoveryCode":"fixture-code"}""")
        }, token = { "fixture-session" }, owner = { "fixture-owner" })
        assertEquals("fixture-code", api.authenticate("signup", "member@example.org", "fixture-password").recoveryCode)
        assertEquals("fixture-code", api.authenticate("recover", "member@example.org", "new-fixture-password", "old-fixture-code").recoveryCode)
        val recoveryBody = Buffer().apply { seen[1].body!!.writeTo(this) }.readUtf8()
        assertTrue(recoveryBody.contains("\"recoveryCode\":\"old-fixture-code\""))
        assertTrue(recoveryBody.contains("\"password\":\"new-fixture-password\""))
        api.adminList("s/user1")
        assertEquals("all", seen.last().url.queryParameter("state"))
        assertEquals("s/user1", seen.last().url.queryParameter("cursor"))
        for (action in listOf("approve", "deny", "revoke")) {
            api.decide(action, "user1")
            assertEquals("/api/subscription/admin/$action", seen.last().url.encodedPath)
            assertEquals("fixture-owner", seen.last().header("x-mavis-access"))
            assertNull(seen.last().header("Authorization"))
        }
    }

    @Test fun missingSessionOrOwnerFailsBeforeHttp() = runTest {
        var calls = 0
        val api = AccountsApi("https://site.example.org", execute = { calls++; fakeResponse(it, "{}") }, token = { null })
        assertTrue(runCatching { api.status() }.isFailure)
        assertTrue(runCatching { api.adminList() }.isFailure)
        assertEquals(0, calls)
    }
}
