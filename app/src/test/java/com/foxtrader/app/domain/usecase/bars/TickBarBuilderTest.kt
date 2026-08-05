package com.foxtrader.app.domain.usecase.bars

import com.foxtrader.app.domain.model.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TickBarBuilder]. Real instance, synthetic ticks; bar boundaries
 * are a fixed tick count so OHLC/volume are exact.
 */
class TickBarBuilderTest {

    private lateinit var builder: TickBarBuilder

    @Before
    fun setup() {
        builder = TickBarBuilder()
    }

    private fun tick(ts: Long, mid: Double, volume: Double = 2.0) =
        Tick(timestampMs = ts, bid = mid, ask = mid, bidVolume = volume / 2.0, askVolume = volume / 2.0)

    @Test
    fun `empty input yields no bars`() {
        assertTrue(builder.build(emptyList(), ticksPerBar = 5).isEmpty())
    }

    @Test
    fun `non-positive count yields no bars`() {
        val ticks = listOf(tick(1, 100.0), tick(2, 101.0))
        assertTrue(builder.build(ticks, ticksPerBar = 0).isEmpty())
    }

    @Test
    fun `groups every N ticks into one bar`() {
        // 10 ticks, mids 100..109, 2.0 volume each; 5 ticks per bar -> 2 bars.
        val ticks = (0 until 10).map { i -> tick(i.toLong(), 100.0 + i, volume = 2.0) }
        val bars = builder.build(ticks, ticksPerBar = 5)

        assertEquals(2, bars.size)

        assertEquals(100.0, bars[0].open, 1e-9)
        assertEquals(104.0, bars[0].high, 1e-9)
        assertEquals(100.0, bars[0].low, 1e-9)
        assertEquals(104.0, bars[0].close, 1e-9)
        assertEquals(10.0, bars[0].volume, 1e-9) // 5 ticks * 2.0

        assertEquals(105.0, bars[1].open, 1e-9)
        assertEquals(109.0, bars[1].close, 1e-9)
        assertEquals(10.0, bars[1].volume, 1e-9)
    }

    @Test
    fun `trailing partial group forms the final bar`() {
        // 7 ticks, 5 per bar -> bar0 (5 ticks) + bar1 (2 ticks).
        val ticks = (0 until 7).map { i -> tick(i.toLong(), 100.0 + i, volume = 2.0) }
        val bars = builder.build(ticks, ticksPerBar = 5)

        assertEquals(2, bars.size)
        assertEquals(10.0, bars[0].volume, 1e-9) // 5 * 2.0
        assertEquals(4.0, bars[1].volume, 1e-9)  // 2 * 2.0
        assertEquals(105.0, bars[1].open, 1e-9)
        assertEquals(106.0, bars[1].close, 1e-9)
    }
}
