package com.foxtrader.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {
    @Test
    fun `redacts bearer and key value secrets`() {
        val raw = "Authorization: Bearer abc.def.ghi password=hunter2 api_key=supersecret"
        val redacted = SensitiveDataRedactor.redact(raw)
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("supersecret"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `redacts json secrets`() {
        val raw = "{\"access_token\":\"abc123\",\"symbol\":\"EURUSD\"}"
        val redacted = SensitiveDataRedactor.redact(raw)
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("EURUSD"))
    }
}
