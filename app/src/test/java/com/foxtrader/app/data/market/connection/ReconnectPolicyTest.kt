package com.foxtrader.app.data.market.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Exponential backoff must grow geometrically, cap at the maximum, de-correlate
 * clients with bounded jitter, and hand back a sentinel once retries are
 * exhausted so the caller can fail over.
 */
class ReconnectPolicyTest {

    @Test
    fun `grows exponentially without jitter`() {
        val policy = ReconnectPolicy(
            initialDelayMs = 1_000,
            maxDelayMs = 60_000,
            multiplier = 2.0,
            jitterFactor = 0.0,
        )
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(4_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
    }

    @Test
    fun `caps at the maximum delay`() {
        val policy = ReconnectPolicy(
            initialDelayMs = 1_000,
            maxDelayMs = 5_000,
            multiplier = 10.0,
            jitterFactor = 0.0,
        )
        policy.nextDelayMs() // 1000
        policy.nextDelayMs() // capped
        assertEquals(5_000L, policy.nextDelayMs())
    }

    @Test
    fun `jitter stays within the configured band`() {
        val policy = ReconnectPolicy(
            initialDelayMs = 10_000,
            maxDelayMs = 60_000,
            multiplier = 1.0, // keep base constant to isolate jitter
            jitterFactor = 0.2,
            random = Random(seed = 42),
        )
        repeat(100) {
            val delay = policy.nextDelayMs()
            assertTrue("delay $delay within [8000,12000]", delay in 8_000L..12_000L)
        }
    }

    @Test
    fun `never exceeds the maximum even with positive jitter`() {
        val policy = ReconnectPolicy(
            initialDelayMs = 5_000,
            maxDelayMs = 5_000,
            multiplier = 1.0,
            jitterFactor = 0.5,
            random = Random(seed = 7),
        )
        repeat(50) {
            assertTrue(policy.nextDelayMs() <= 5_000L)
        }
    }

    @Test
    fun `returns the give-up sentinel once maxAttempts is exhausted`() {
        val policy = ReconnectPolicy(
            initialDelayMs = 100,
            maxDelayMs = 100,
            maxAttempts = 3,
            jitterFactor = 0.0,
        )
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.nextDelayMs()
        assertTrue(policy.isExhausted)
        assertEquals(ReconnectPolicy.GIVE_UP, policy.nextDelayMs())
        assertEquals(3, policy.attemptCount)
    }

    @Test
    fun `reset restarts the backoff after a successful connection`() {
        val policy = ReconnectPolicy(initialDelayMs = 1_000, jitterFactor = 0.0)
        policy.nextDelayMs()
        policy.nextDelayMs()
        assertEquals(2, policy.attemptCount)
        policy.reset()
        assertEquals(0, policy.attemptCount)
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `rejects an invalid configuration`() {
        try {
            ReconnectPolicy(initialDelayMs = 0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
