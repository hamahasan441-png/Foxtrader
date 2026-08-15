package com.foxtrader.app.data.remote.websocket

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 */
class Mt4WebSocketRequestTest {

    private val realToken = "live-secret-token-9f2a"

    @Test
    fun `create builds a request carrying the auth token in the query`() {
        val req = Mt4WebSocketRequest.create(realToken, "account-123")

        assertNotNull(req)
        val url = req!!.request.url
        assertEquals("wss", url.scheme)
        assertEquals(realToken, url.queryParameter("auth-token"))
        assertEquals("account-123", url.queryParameter("accountId"))
    }

    @Test
    fun `redacted string never contains the raw token`() {
        val req = Mt4WebSocketRequest.create(realToken, "account-123")!!

        val redacted = req.redacted()

        assertFalse("Redacted URL must not contain the raw token", redacted.contains(realToken))
        assertTrue("Token value must be masked", redacted.contains("[REDACTED]"))
        // The account id is not a secret and stays for diagnostics.
        assertTrue(redacted.contains("account-123"))
    }

    @Test
    fun `redacted string masks the auth-token query parameter value`() {
        val req = Mt4WebSocketRequest.create(realToken, "account-123")!!
        val redactedUrl = req.redacted().toHttpUrlOrNull()

        // The redacted URL is a valid URL and its auth-token value is the
        // placeholder, never the live token.
        assertNotNull(redactedUrl)
        assertEquals(Mt4WebSocketRequest.REDACTED, redactedUrl!!.queryParameter("auth-token"))
        assertFalse(redactedUrl.toString().contains(realToken))
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
