package com.foxtrader.app.data.remote.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Mt4WebSocketRequest]. The central guarantee: a live MetaApi token
 * embedded in the WebSocket URL must never survive into a redacted diagnostic
 * string, and a blank token must prevent connection entirely.
 *
 * Note: okhttp's `HttpUrl` only supports http/https, so a `wss:` request URL is
 * rewritten to `https:` for parsing by [okhttp3.Request.Builder.url]. We assert
 * on the security-relevant parts (token/account query params) rather than the
 * scheme.
 */
class Mt4WebSocketRequestTest {

    private val realToken = "live-secret-token-9f2a"

    @Test
    fun `create builds a request carrying the auth token in the query`() {
        val req = Mt4WebSocketRequest.create(realToken, "account-123")

        assertNotNull(req)
        val url = req!!.request.url
        assertEquals(realToken, url.queryParameter("auth-token"))
        assertEquals("account-123", url.queryParameter("accountId"))
    }

    @Test
    fun `redacted string never contains the raw token`() {
        val req = Mt4WebSocketRequest.create(realToken, "account-123")!!

        val redacted = req.redacted()

        assertFalse("Redacted URL must not contain the raw token", redacted.contains(realToken))
        assertTrue("Token value must be masked", redacted.contains(Mt4WebSocketRequest.REDACTED))
        // The account id is not a secret and stays for diagnostics.
        assertTrue(redacted.contains("accountId=account-123"))
    }

    @Test
    fun `redacted string masks the auth-token query parameter value`() {
        val req = Mt4WebSocketRequest.create(realToken, "account-123")!!
        val redacted = req.redacted()

        // The redacted diagnostic string must carry the placeholder for
        // auth-token and never the live token, while keeping the account id.
        assertFalse("must not leak the live token", redacted.contains(realToken))
        assertTrue("must contain the redaction marker", redacted.contains(Mt4WebSocketRequest.REDACTED))
        assertTrue("must keep the account id", redacted.contains("accountId=account-123"))
    }

    @Test
    fun `blank token prevents creating a request`() {
        assertNull(Mt4WebSocketRequest.create("", "account-123"))
        assertNull(Mt4WebSocketRequest.create("   ", "account-123"))
    }

    @Test
    fun `blank account id prevents creating a request`() {
        assertNull(Mt4WebSocketRequest.create(realToken, ""))
        assertNull(Mt4WebSocketRequest.create(realToken, "  "))
    }
}
