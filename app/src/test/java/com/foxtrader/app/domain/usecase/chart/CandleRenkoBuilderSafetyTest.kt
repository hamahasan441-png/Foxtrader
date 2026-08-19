package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Safety-net tests for [CandleRenkoBuilder] hardening: pathological brick
 * sizes must degrade to a bounded (or empty) output instead of an OOM/ANR.
 */
class CandleRenkoBuilderSafetyTest {

    private lateinit var builder: CandleRenkoBuilder

    @Before
    fun setup() {
        builder = CandleRenkoBuilder()
    }

    private fun candle(ts: Long, close: Double) =
        Candle(timestamp = ts, open = close, high = close, low = close, close = close, volume = 1.0)

    @Test
    fun `NaN brick size yields no bricks`() {
        val candles = listOf(candle(1L, 100.0), candle(2L, 105.0))
        assertTrue(builder.build(candles, brickSize = Double.NaN).isEmpty())
    }

    @Test
    fun `infinite brick size yields no bricks`() {
        val candles = listOf(candle(1L, 100.0), candle(2L, 105.0))
        assertTrue(builder.build(candles, brickSize = Double.POSITIVE_INFINITY).isEmpty())
    }

    @Test
    fun `a microscopic brick against a huge move is capped instead of exploding`() {
        // 1 -> 1_000_000 with brick 0.001 would demand ~1e9 bricks (OOM).
        val candles = listOf(candle(1L, 1.0), candle(2L, 1_000_000.0))

        val bricks = builder.build(candles, brickSize = 0.001)

        assertTrue("output must be bounded", bricks.size <= 50_000)
        assertTrue("cap must still produce bricks", bricks.isNotEmpty())
    }

    @Test
    fun `normal brick sequence is unaffected by the cap`() {
        val candles = listOf(candle(1L, 100.0), candle(2L, 103.2))
        val bricks = builder.build(candles, brickSize = 1.0)

        assertEquals(3, bricks.size)
        assertEquals(101.0, bricks[0].close, 1e-9)
        assertEquals(102.0, bricks[1].close, 1e-9)
        assertEquals(103.0, bricks[2].close, 1e-9)
    }
}
