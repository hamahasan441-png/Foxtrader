package com.foxtrader.app.domain.usecase.sdk

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.sdk.script.BuiltInStrategies
import com.foxtrader.app.domain.sdk.script.ScriptContext
import com.foxtrader.app.domain.sdk.script.ScriptEngine
import com.foxtrader.app.domain.sdk.script.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the scripting engine and ScriptContext.
 *
 * Validates:
 * - ScriptContext provides correct read-only access to candle data.
 * - ScriptEngine.evaluate respects minBars guard.
 * - ScriptEngine.evaluate returns a signal when the strategy fires.
 * - Custom lambda strategies work correctly.
 * - Non-repainting: strategy at index i sees only candles[0..i].
 */
class ScriptEngineTest {

    private lateinit var engine: ScriptEngine

    @Before
    fun setup() {
        engine = ScriptEngine()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun candle(close: Double, high: Double? = null, low: Double? = null, ts: Long = 0L) =
        Candle(
            timestamp = ts,
            open = close - 0.1,
            high = high ?: (close + 0.2),
            low = low ?: (close - 0.2),
            close = close,
            volume = 1000.0,
        )

    private fun risingCandles(n: Int): List<Candle> =
        (0 until n).map { candle(close = 100.0 + it * 0.5, ts = it * 60_000L) }

    // ────────────────────────────────────────────────────────────────────────
    // ScriptContext tests
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `ScriptContext exposes current bar values`() {
        val candles = risingCandles(5)
        val ctx = ScriptContext(candles, currentIndex = 4)
        assertEquals(candles[4].close, ctx.close, 1e-9)
        assertEquals(candles[4].open, ctx.open, 1e-9)
        assertEquals(candles[4].high, ctx.high, 1e-9)
        assertEquals(candles[4].low, ctx.low, 1e-9)
    }

    @Test
    fun `ScriptContext size equals currentIndex + 1 (non-repainting)`() {
        val candles = risingCandles(10)
        val ctx = ScriptContext(candles, currentIndex = 6)
        assertEquals(7, ctx.size)
    }

    @Test
    fun `ScriptContext candle offset 0 returns current bar`() {
        val candles = risingCandles(5)
        val ctx = ScriptContext(candles, currentIndex = 3)
        assertEquals(candles[3], ctx.candle(0))
    }

    @Test
    fun `ScriptContext candle offset -1 returns previous bar`() {
        val candles = risingCandles(5)
        val ctx = ScriptContext(candles, currentIndex = 3)
        assertEquals(candles[2], ctx.candle(-1))
    }

    @Test
    fun `ScriptContext candle returns null for future offset (non-repainting)`() {
        val candles = risingCandles(5)
        val ctx = ScriptContext(candles, currentIndex = 2)
        assertNull("Future bar must not be visible", ctx.candle(1))
    }

    @Test
    fun `ScriptContext sma returns correct simple moving average`() {
        // candles: 100, 102, 104, 106, 108 → SMA(3) at index 4 = (104+106+108)/3 = 106.0
        val closes = listOf(100.0, 102.0, 104.0, 106.0, 108.0)
        val candles = closes.mapIndexed { i, c -> candle(close = c, ts = i * 60_000L) }
        val ctx = ScriptContext(candles, currentIndex = 4)
        assertEquals(106.0, ctx.sma(3), 1e-6)
    }

    @Test
    fun `ScriptContext highest and lowest are correct`() {
        val candles = listOf(
            candle(100.0, high = 105.0, low = 95.0),
            candle(102.0, high = 107.0, low = 97.0),
            candle(101.0, high = 106.0, low = 96.0),
        )
        val ctx = ScriptContext(candles, currentIndex = 2)
        assertEquals(107.0, ctx.highest(3), 1e-6)
        assertEquals(95.0, ctx.lowest(3), 1e-6)
    }

    @Test
    fun `ScriptContext crossOver detects fast crossing above slow`() {
        val ctx = ScriptContext(emptyList(), 0)
        assertTrue(ctx.crossOver(fast = 1.1, slow = 1.0, prevFast = 0.9, prevSlow = 1.0))
    }

    @Test
    fun `ScriptContext crossUnder detects fast crossing below slow`() {
        val ctx = ScriptContext(emptyList(), 0)
        assertTrue(ctx.crossUnder(fast = 0.9, slow = 1.0, prevFast = 1.1, prevSlow = 1.0))
    }

    // ────────────────────────────────────────────────────────────────────────
    // ScriptEngine tests
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `evaluate returns null when index is less than minBars`() {
        val strategy = Strategy(id = "test", name = "Test", minBars = 10) { null }
        val candles = risingCandles(20)
        assertNull(engine.evaluate(strategy, candles, index = 5))
    }

    @Test
    fun `evaluate returns null when index is out of bounds`() {
        val strategy = Strategy(id = "test", name = "Test", minBars = 2) { null }
        assertNull(engine.evaluate(strategy, risingCandles(5), index = 10))
    }

    @Test
    fun `evaluate passes correct context to strategy lambda`() {
        var capturedSize = 0
        val strategy = Strategy(id = "ctx_test", name = "CtxTest", minBars = 3) { ctx ->
            capturedSize = ctx.size
            null
        }
        val candles = risingCandles(10)
        engine.evaluate(strategy, candles, index = 7)
        assertEquals("ScriptContext size must equal index + 1", 8, capturedSize)
    }

    @Test
    fun `evaluate returns signal when strategy fires`() {
        val strategy = Strategy(id = "always_buy", name = "Always Buy", minBars = 1) { ctx ->
            StrategySignal(
                index = ctx.currentIndex,
                timestamp = ctx.current.timestamp,
                direction = Direction.BULLISH,
                entry = ctx.close,
                stopLoss = ctx.close - 1.0,
                takeProfit = ctx.close + 2.0,
                setupType = "test",
            )
        }
        val candles = risingCandles(5)
        val signal = engine.evaluate(strategy, candles, index = 3)
        assertNotNull(signal)
        assertEquals(Direction.BULLISH, signal!!.direction)
        assertEquals(3, signal.index)
    }

    @Test
    fun `evaluate non-repainting - strategy cannot access future bars`() {
        var maxIndexSeen = -1
        val strategy = Strategy(id = "sniffer", name = "Sniffer", minBars = 0) { ctx ->
            // Try to peek one bar ahead
            val futurePeek = ctx.candle(1)
            if (futurePeek != null) maxIndexSeen = ctx.currentIndex
            null
        }
        val candles = risingCandles(10)
        engine.evaluate(strategy, candles, index = 5)
        assertEquals("No future bar should have been accessible", -1, maxIndexSeen)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Built-in strategy smoke tests
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `BuiltInStrategies smaCross evaluates without throwing`() {
        val candles = risingCandles(55)
        // Just ensure it doesn't throw on evaluation
        val signal = engine.evaluate(BuiltInStrategies.smaCross, candles, index = 53)
        // Signal may be null (no crossover in monotonically rising data) — that's fine
    }

    @Test
    fun `BuiltInStrategies smaCross minBars is 51`() {
        assertEquals(51, BuiltInStrategies.smaCross.minBars)
    }
}
