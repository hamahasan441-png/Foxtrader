package com.foxtrader.app.data.remote.dukascopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DukascopyHttpPolicyTest {
    @Test
    fun `waf challenge is never treated as an empty market`() {
        assertEquals(
            DukascopyHttpOutcome.RETRY,
            classifyDukascopyResponse(code = 202, bodyEmpty = true, challenged = true),
        )
        assertEquals(
            DukascopyHttpOutcome.RETRY,
            classifyDukascopyResponse(code = 200, bodyEmpty = true, challenged = true),
        )
    }

    @Test
    fun `blank success and auth failures are retried and a real 404 is absent`() {
        assertEquals(DukascopyHttpOutcome.RETRY, classifyDukascopyResponse(200, bodyEmpty = true, challenged = false))
        assertEquals(DukascopyHttpOutcome.RETRY, classifyDukascopyResponse(403, bodyEmpty = true, challenged = false))
        assertEquals(DukascopyHttpOutcome.RETRY, classifyDukascopyResponse(503, bodyEmpty = true, challenged = false))
        assertEquals(DukascopyHttpOutcome.ABSENT, classifyDukascopyResponse(404, bodyEmpty = true, challenged = false))
        assertEquals(DukascopyHttpOutcome.DATA, classifyDukascopyResponse(200, bodyEmpty = false, challenged = false))
    }

    @Test
    fun `waf cookie is taken from a combined cookie header`() {
        assertEquals(
            "abc=def",
            extractAwsWafToken("other=1; aws-waf-token=abc=def; path=/"),
        )
        assertNull(extractAwsWafToken(null))
        assertNull(extractAwsWafToken("session=nope"))
    }
}
