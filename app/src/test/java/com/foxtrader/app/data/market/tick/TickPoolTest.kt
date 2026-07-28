package com.foxtrader.app.data.market.tick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Object pooling: reuse instances to keep the hot path allocation-free, degrade
 * gracefully (never block) when exhausted, and never leak a previous value.
 */
class TickPoolTest {

    @Test
    fun `pre-allocates the initial capacity`() {
        val pool = TickPool(initialCapacity = 8)
        assertEquals(8, pool.available)
        assertEquals(0, pool.outstanding)
    }

    @Test
    fun `acquire draws from the pool and tracks outstanding`() {
        val pool = TickPool(initialCapacity = 2)
        val a = pool.acquire()
        val b = pool.acquire()
        assertEquals(0, pool.available)
        assertEquals(2, pool.outstanding)
        assertNotSame(a, b)
    }

    @Test
    fun `release resets fields and returns the instance to the pool`() {
        val pool = TickPool(initialCapacity = 1)
        val tick = pool.acquire()
        tick.set("BTCUSDT", 100.0, 5.0, 1_000L)
        pool.release(tick)
        assertEquals(1, pool.available)
        assertEquals(0, pool.outstanding)
        // The same instance is handed back out, now clean.
        val reused = pool.acquire()
        assertEquals("", reused.symbol)
        assertEquals(0.0, reused.price, 0.0)
        assertEquals(0L, reused.timestamp)
    }

    @Test
    fun `exhaustion allocates a fresh instance instead of blocking`() {
        val pool = TickPool(initialCapacity = 0)
        val tick = pool.acquire() // nothing pooled — must still succeed
        assertEquals(1, pool.outstanding)
        pool.release(tick)
        assertEquals(1, pool.available)
    }

    @Test
    fun `steady-state acquire-release does not grow the pool unbounded`() {
        val pool = TickPool(initialCapacity = 4)
        repeat(10_000) {
            val t = pool.acquire()
            t.set("S", it.toDouble(), 1.0, it.toLong())
            pool.release(t)
        }
        assertTrue(pool.available <= 4 + 1) // never balloons beyond the high-water mark
        assertEquals(0, pool.outstanding)
    }
}
