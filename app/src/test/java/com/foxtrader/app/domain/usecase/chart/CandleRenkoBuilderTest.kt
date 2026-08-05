package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [CandleRenkoBuilder]. Real instance, hand-computed brick sequence
 * driven by candle closes.
 */
class CandleRenkoBuilderTest {

    private lateinit var builder: CandleRenkoBuilder

    @Before
    fun setup() {
        builder = CandleRenkoBuilder()
    }

    private fun candle(ts: Long, close: Double, volume: Double = 2.0) =
        Candle(timestamp = ts, open = close, high = close, low = close, close = close, volume = volume)

    @Test
    fun `empty input yields no bricks`() {
        assertTrue(builder.build(emptyList(), brickSize = 1.0).isEmpty())
    }

    @Test
    fun `non-positive brick size yields no bricks`() {
        val candles = listOf(candle(1L, 100.0), candle(2L, 105.0))
        assertTrue(builder.build(candles, brickSize = 0.0).isEmpty())
    }

    @Test
    fun `no brick until close moves a full brick from the anchor`() {
        val candles = listOf(candle(1L, 100.0), candle(2L, 100.5))
        assertTrue(builder.build(candles, brickSize = 1.0).isEmpty())
    }

    @Test
    fun `builds the expected brick sequence from candle closes`() {
        // Anchor 100. Closes: 100, 101.2, 102.3, 101.0, 99.0 ; brickSize 1.0.
        val candles = listOf(
            candle(1L, 100.0, volume = 2.0),
            candle(2L, 101.2, volume = 2.0),
            candle(3L, 102.3, volume = 2.0),
            candle(4L, 101.0, volume = 2.0),
            candle(5L, 99.0, volume = 2.0),
        )
        val bricks = builder.build(candles, brickSize = 1.0)

        // 100->101, 101->102 (up), 102->101, 101->100, 100->99 (down)
        assertEquals(5, bricks.size)

        // Brick 0: up, volume accumulated over candles 1 and 2 = 4.0
        assertEquals(100.0, bricks[0].open, 1e-9)
        assertEquals(101.0, bricks[0].close, 1e-9)
        assertEquals(101.0, bricks[0].high, 1e-9)
        assertEquals(100.0, bricks[0].low, 1e-9)
        assertEquals(4.0, bricks[0].volume, 1e-9)

        // Brick 2: down -> high = open, low = close
        assertEquals(102.0, bricks[2].open, 1e-9)
        assertEquals(101.0, bricks[2].close, 1e-9)
        assertEquals(102.0, bricks[2].high, 1e-9)
        assertEquals(101.0, bricks[2].low, 1e-9)

        assertEquals(99.0, bricks.last().close, 1e-9)

        for (b in bricks) {
            assertEquals(1.0, abs(b.close - b.open), 1e-9)
        }
    }
}
