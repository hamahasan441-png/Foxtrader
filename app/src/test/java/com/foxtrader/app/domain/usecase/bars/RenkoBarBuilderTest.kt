package com.foxtrader.app.domain.usecase.bars

import com.foxtrader.app.domain.model.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [RenkoBarBuilder]. Real instance, synthetic ticks, hand-computed
 * brick sequence. `mid = (bid + ask) / 2`; a tick's volume = bidVolume + askVolume.
 */
class RenkoBarBuilderTest {

    private lateinit var builder: RenkoBarBuilder

    @Before
    fun setup() {
        builder = RenkoBarBuilder()
    }

    /** Tick whose mid is exactly [mid] and whose bid+ask volume is [volume]. */
    private fun tick(ts: Long, mid: Double, volume: Double = 2.0) =
        Tick(timestampMs = ts, bid = mid, ask = mid, bidVolume = volume / 2.0, askVolume = volume / 2.0)

    @Test
    fun `empty input yields no bricks`() {
        assertTrue(builder.build(emptyList(), brickSize = 1.0).isEmpty())
    }

    @Test
    fun `non-positive brick size yields no bricks`() {
        val ticks = listOf(tick(1, 100.0), tick(2, 105.0))
        assertTrue(builder.build(ticks, brickSize = 0.0).isEmpty())
    }

    @Test
    fun `no brick until price moves a full brick from the anchor`() {
        // Anchor = first mid (100). Second tick moves only 0.5 -> no brick.
        val ticks = listOf(tick(1, 100.0), tick(2, 100.5))
        assertTrue(builder.build(ticks, brickSize = 1.0).isEmpty())
    }

    @Test
    fun `builds the expected up and down brick sequence`() {
        // Anchor 100. Mids: 100, 101.2, 102.3, 101.0, 99.0 ; brickSize 1.0.
        val ticks = listOf(
            tick(1, 100.0, volume = 2.0),
            tick(2, 101.2, volume = 2.0),
            tick(3, 102.3, volume = 2.0),
            tick(4, 101.0, volume = 2.0),
            tick(5, 99.0, volume = 2.0),
        )
        val bricks = builder.build(ticks, brickSize = 1.0)

        // 100->101 (up), 101->102 (up), 102->101 (down), 101->100 (down), 100->99 (down)
        assertEquals(5, bricks.size)

        // brick 0: up, carries volume accumulated over ticks 1 and 2 = 4.0
        assertEquals(100.0, bricks[0].open, 1e-9)
        assertEquals(101.0, bricks[0].close, 1e-9)
        assertEquals(101.0, bricks[0].high, 1e-9)
        assertEquals(100.0, bricks[0].low, 1e-9)
        assertEquals(4.0, bricks[0].volume, 1e-9)

        // brick 1: up
        assertEquals(101.0, bricks[1].open, 1e-9)
        assertEquals(102.0, bricks[1].close, 1e-9)

        // brick 2: down -> high = open, low = close
        assertEquals(102.0, bricks[2].open, 1e-9)
        assertEquals(101.0, bricks[2].close, 1e-9)
        assertEquals(102.0, bricks[2].high, 1e-9)
        assertEquals(101.0, bricks[2].low, 1e-9)

        // last brick closes at 99
        assertEquals(99.0, bricks.last().close, 1e-9)

        // Every brick is exactly one brick tall.
        for (b in bricks) {
            assertEquals(1.0, abs(b.close - b.open), 1e-9)
        }
    }

    @Test
    fun `a large jump prints multiple bricks at once`() {
        // Anchor 100, then a jump to 103.5 -> three up bricks (100-101, 101-102, 102-103).
        val ticks = listOf(tick(1, 100.0), tick(2, 103.5))
        val bricks = builder.build(ticks, brickSize = 1.0)
        assertEquals(3, bricks.size)
        assertEquals(101.0, bricks[0].close, 1e-9)
        assertEquals(102.0, bricks[1].close, 1e-9)
        assertEquals(103.0, bricks[2].close, 1e-9)
    }
}
