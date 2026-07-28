package com.foxtrader.app.data.market.tick

import com.foxtrader.app.data.market.model.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tick compression: N ticks in one interval collapse to one OHLCV summary, and a
 * closed interval is never revisited (no repaint at the aggregation stage).
 */
class TickAggregatorTest {

    private val interval = 60_000L

    private fun tick(price: Double, ts: Long, qty: Double = 1.0) =
        Tick("XAUUSD", price, qty, ts)

    @Test
    fun `ticks in one interval fold without emitting`() {
        val agg = TickAggregator(interval)
        assertNull(agg.add(tick(100.0, 0L)))
        assertNull(agg.add(tick(105.0, 10_000L)))
        assertNull(agg.add(tick(95.0, 20_000L)))
    }

    @Test
    fun `advancing to a new interval emits the closed aggregate`() {
        val agg = TickAggregator(interval)
        agg.add(tick(100.0, 0L, qty = 1.0))
        agg.add(tick(110.0, 10_000L, qty = 2.0))
        agg.add(tick(90.0, 20_000L, qty = 3.0))
        val closed = agg.add(tick(120.0, interval))

        assertEquals(0L, closed!!.intervalStart)
        assertEquals(100.0, closed.open, 0.0)
        assertEquals(110.0, closed.high, 0.0)
        assertEquals(90.0, closed.low, 0.0)
        assertEquals(90.0, closed.close, 0.0)
        assertEquals(6.0, closed.volume, 0.0)
        assertEquals(3, closed.tickCount)
    }

    @Test
    fun `aggregate converts losslessly to a candle`() {
        val agg = TickAggregator(interval)
        agg.add(tick(1.0, 0L))
        val closed = agg.add(tick(2.0, interval))!!
        val candle = closed.toCandle()
        assertEquals(closed.intervalStart, candle.timestamp)
        assertEquals(closed.close, candle.close, 0.0)
    }

    @Test
    fun `late ticks for a closed interval are dropped`() {
        val agg = TickAggregator(interval)
        agg.add(tick(1.0, 0L))
        agg.add(tick(2.0, interval)) // closes [0, interval)
        assertNull(agg.add(tick(999.0, 30_000L))) // late for closed interval
        assertEquals(1L, agg.droppedLateTicks)
    }

    @Test
    fun `flush finalises the in-progress interval`() {
        val agg = TickAggregator(interval)
        agg.add(tick(5.0, 0L))
        agg.add(tick(6.0, 10_000L))
        val flushed = agg.flush()
        assertEquals(0L, flushed!!.intervalStart)
        assertEquals(6.0, flushed.close, 0.0)
        assertEquals(2, flushed.tickCount)
        // A second flush yields nothing.
        assertNull(agg.flush())
    }

    @Test
    fun `interval start is floor-aligned`() {
        val agg = TickAggregator(interval)
        agg.add(tick(1.0, 45_000L))
        val closed = agg.add(tick(2.0, interval + 1_000L))
        assertEquals(0L, closed!!.intervalStart)
    }

    @Test
    fun `interval must be positive`() {
        try {
            TickAggregator(0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
