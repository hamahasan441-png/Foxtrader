package com.foxtrader.app.domain.sdk.script

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptEngineTest {

    private val engine = ScriptEngine()

    private fun candles(n: Int) = (0 until n).map { i ->
        Candle(i * 60_000L, 100.0 + i * 0.1, 101.0 + i * 0.1, 99.0 + i * 0.1, 100.5 + i * 0.1, 100.0)
    }

    private val alwaysBuy = Strategy(
        id = "test_buy", name = "Always Buy", minBars = 5,
    ) { ctx ->
        StrategySignal(ctx.currentIndex, ctx.current.timestamp, Direction.BULLISH,
            ctx.close, ctx.close - 1.0, ctx.close + 2.0)
    }

    @Test
    fun `evaluate returns signal when strategy triggers`() {
        val signal = engine.evaluate(alwaysBuy, candles(20), 10)
        assertNotNull(signal)
        assertEquals(Direction.BULLISH, signal!!.direction)
        assertEquals(10, signal.index)
    }

    @Test
    fun `evaluate returns null when index below minBars`() {
        assertNull(engine.evaluate(alwaysBuy, candles(20), 3))
    }

    @Test
    fun `ScriptContext only sees data up to currentIndex`() {
        val ctx = ScriptContext(candles(100), 50)
        assertEquals(51, ctx.size) // [0..50]
        assertNull(ctx.candle(1))  // can't look ahead
        assertNotNull(ctx.candle(0))
        assertNotNull(ctx.candle(-5))
    }

    @Test
    fun `ScriptContext sma computes correctly`() {
        val data = candles(30)
        val ctx = ScriptContext(data, 29)
        val sma5 = ctx.sma(5)
        val expected = data.takeLast(5).sumOf { it.close } / 5.0
        assertEquals(expected, sma5, 0.0001)
    }

    @Test
    fun `ScriptContext highest and lowest`() {
        val data = candles(30)
        val ctx = ScriptContext(data, 29)
        val hi = ctx.highest(10)
        val lo = ctx.lowest(10)
        assertTrue(hi > lo)
        assertEquals(data.subList(20, 30).maxOf { it.high }, hi, 0.0001)
    }

    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
