package com.foxtrader.app.data.market.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordered failover: march through endpoints on failure, stop at the last one,
 * and snap back to the preferred endpoint after a success.
 */
class FailoverRouterTest {

    @Test
    fun `starts on the preferred endpoint`() {
        val router = FailoverRouter(listOf("primary", "backup", "tertiary"))
        assertEquals("primary", router.current)
        assertEquals(0, router.currentIndex)
        assertFalse(router.isExhausted)
        assertEquals(3, router.size)
    }

    @Test
    fun `advances through endpoints until exhausted`() {
        val router = FailoverRouter(listOf("a", "b"))
        assertTrue(router.advance())
        assertEquals("b", router.current)
        assertTrue(router.isExhausted)
        assertFalse(router.advance()) // already last
        assertEquals("b", router.current)
    }

    @Test
    fun `reset returns to the preferred endpoint`() {
        val router = FailoverRouter(listOf("a", "b", "c"))
        router.advance()
        router.advance()
        assertEquals("c", router.current)
        router.reset()
        assertEquals("a", router.current)
        assertFalse(router.isExhausted)
    }

    @Test
    fun `a single endpoint is always exhausted`() {
        val router = FailoverRouter(listOf("only"))
        assertTrue(router.isExhausted)
        assertFalse(router.advance())
    }

    @Test
    fun `requires at least one endpoint`() {
        try {
            FailoverRouter(emptyList<String>())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
