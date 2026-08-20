package com.foxtrader.app.domain.usecase.sdk

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LogicOp
import com.foxtrader.app.domain.model.StrategyAction
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StrategyCondition
import com.foxtrader.app.domain.model.StrategyConditionKind
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
 * - ScriptContext provides correct read-only access to candle data and indicators (SMA, EMA, RSI, ATR, MACD, Bollinger, Stoch).
 * - ScriptEngine.evaluate respects minBars guard.
 * - ScriptEngine.evaluate returns a signal when the strategy fires.
 * - Custom lambda strategies work correctly.
 * - Non-repainting: strategy at index i sees only candles[0..i].
 * - DSL parsing and compilation into executable Strategy.
 * - Visual StrategyBlueprint compilation into executable Strategy.
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
        val closes = listOf(100.0, 102.0, 104.0, 106.0, 108.0)
        val candles = closes.mapIndexed { i, c -> candle(close = c, ts = i * 60_000L) }
        val ctx = ScriptContext(candles, currentIndex = 4)
        assertEquals(106.0, ctx.sma(3), 1e-6)
    }

    @Test
    fun `ScriptContext ema and rsi compute valid values`() {
        val candles = risingCandles(60)
        val ctx = ScriptContext(candles, currentIndex = 59)
        assertTrue(ctx.ema(20) > 0.0)
        assertTrue(ctx.rsi(14) in 0.0..100.0)
        assertTrue(ctx.atr(14) > 0.0)
    }

    @Test
    fun `ScriptContext macd and bollinger bands return valid outputs`() {
        val candles = risingCandles(60)
        val ctx = ScriptContext(candles, currentIndex = 59)
        val macd = ctx.macd()
        val bb = ctx.bollinger()
        val stoch = ctx.stochastic()

        assertTrue(bb.upper >= bb.lower)
        assertTrue(stoch.k in 0.0..100.0)
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
    fun `indicator offsets cannot expose a future bar`() {
        val candles = risingCandles(30)
        val ctx = ScriptContext(candles, currentIndex = 20)

        assertEquals(ctx.close, ctx.sma(period = 1, offset = -1), 1e-9)
        assertEquals(ctx.close, ctx.ema(period = 1, offset = -1), 1e-9)
        assertEquals(50.0, ctx.rsi(period = 14, offset = -1), 1e-9)
        assertEquals(ctx.high, ctx.highest(period = 1, offset = -1), 1e-9)
        assertEquals(ctx.low, ctx.lowest(period = 1, offset = -1), 1e-9)
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
            val futurePeek = ctx.candle(1)
            if (futurePeek != null) maxIndexSeen = ctx.currentIndex
            null
        }
        val candles = risingCandles(10)
        engine.evaluate(strategy, candles, index = 5)
        assertEquals("No future bar should have been accessible", -1, maxIndexSeen)
    }

    @Test
    fun `throwing script fails closed`() {
        val strategy = Strategy(id = "broken", name = "Broken", minBars = 0) {
            error("bad user script")
        }

        assertNull(engine.evaluate(strategy, risingCandles(10), index = 5))
    }

    // ────────────────────────────────────────────────────────────────────────
    // DSL & Blueprint Compilation Tests
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `compileDsl creates functional buy strategy`() {
        val script = "BUY IF ema(9) cross_over ema(21) SL atr(14)*1.5 TP atr(14)*3.0"
        val strategyResult = engine.compileDsl("dsl_buy", "DSL Buy Strategy", script)
        assertTrue(strategyResult.isSuccess)
        val strategy = strategyResult.getOrThrow()
        assertEquals("dsl_buy", strategy.id)
    }

    @Test
    fun `compileBlueprint compiles StrategyBlueprint to executable Strategy`() {
        val blueprint = StrategyBlueprint(
            name = "EMA + RSI Bullish",
            combinator = LogicOp.AND,
            conditions = listOf(
                StrategyCondition(kind = StrategyConditionKind.INDICATOR, label = "EMA 20 above EMA 50"),
                StrategyCondition(kind = StrategyConditionKind.INDICATOR, label = "RSI leaving 30/70"),
            ),
        )
        val compiled = engine.compileBlueprint(blueprint)
        val candles = risingCandles(60)
        // Evaluates safely
        val signal = engine.evaluate(compiled, candles, 55)
    }

    @Test
    fun `blueprint emits bearish signal for bearish BOS instead of guessing buy`() {
        val blueprint = StrategyBlueprint(
            name = "Directional BOS",
            conditions = listOf(
                StrategyCondition(
                    kind = StrategyConditionKind.MARKET_STRUCTURE,
                    label = "BOS in trade direction",
                ),
            ),
        )
        val history = (0 until 50).map { i ->
            candle(close = 100.0, high = 101.0, low = 99.0, ts = i * 60_000L)
        }
        val bearishBreak = candle(close = 96.0, high = 100.0, low = 95.0, ts = 50 * 60_000L)

        val signal = engine.evaluate(engine.compileBlueprint(blueprint), history + bearishBreak, 50)

        assertNotNull(signal)
        assertEquals(Direction.BEARISH, signal?.direction)
        assertTrue(signal!!.stopLoss > signal.entry)
        assertTrue(signal.takeProfit < signal.entry)
    }

    @Test
    fun `SMT-only blueprint fails closed without correlated peer data`() {
        val blueprint = StrategyBlueprint(
            name = "SMT required",
            conditions = listOf(
                StrategyCondition(
                    kind = StrategyConditionKind.SMT,
                    label = "SMT divergence vs correlated pair",
                ),
            ),
        )

        val signal = engine.evaluate(engine.compileBlueprint(blueprint), risingCandles(60), 59)

        assertNull(signal)
    }

    @Test
    fun `negating unsupported SMT does not turn missing data into a signal`() {
        val blueprint = StrategyBlueprint(
            name = "No SMT",
            conditions = listOf(
                StrategyCondition(
                    kind = StrategyConditionKind.SMT,
                    label = "SMT divergence vs correlated pair",
                    negated = true,
                ),
            ),
        )

        val signal = engine.evaluate(engine.compileBlueprint(blueprint), risingCandles(60), 59)

        assertNull(signal)
    }

    @Test
    fun `ambiguous directional negation fails closed`() {
        val blueprint = StrategyBlueprint(
            name = "Negated directional rule",
            conditions = listOf(
                StrategyCondition(
                    kind = StrategyConditionKind.INDICATOR,
                    label = "EMA 20 above EMA 50",
                    negated = true,
                ),
            ),
        )

        val signal = engine.evaluate(engine.compileBlueprint(blueprint), risingCandles(60), 59)

        assertNull(signal)
    }

    @Test
    fun `risk condition blocks blueprint above supported per-trade cap`() {
        val blueprint = StrategyBlueprint(
            name = "Unsafe risk",
            combinator = LogicOp.AND,
            conditions = listOf(
                StrategyCondition(
                    kind = StrategyConditionKind.MARKET_STRUCTURE,
                    label = "BOS in trade direction",
                ),
                StrategyCondition(
                    kind = StrategyConditionKind.RISK,
                    label = "Risk ≤ configured per-trade cap",
                ),
            ),
            action = StrategyAction(riskPercent = 8.0),
        )
        val history = (0 until 50).map { i ->
            candle(close = 100.0, high = 101.0, low = 99.0, ts = i * 60_000L)
        }
        val bullishBreak = candle(close = 104.0, high = 105.0, low = 100.0, ts = 50 * 60_000L)

        val signal = engine.evaluate(engine.compileBlueprint(blueprint), history + bullishBreak, 50)

        assertNull(signal)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Additional blueprint safety
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `unsafe blueprint risk fails closed even without a risk condition`() {
        val blueprint = StrategyBlueprint(
            name = "Unsafe risk without guard rule",
            conditions = listOf(
                StrategyCondition(
                    kind = StrategyConditionKind.MARKET_STRUCTURE,
                    label = "BOS in trade direction",
                ),
            ),
            action = StrategyAction(riskPercent = 8.0),
        )
        val history = (0 until 50).map { i ->
            candle(close = 100.0, high = 101.0, low = 99.0, ts = i * 60_000L)
        }
        val bullishBreak = candle(close = 104.0, high = 105.0, low = 100.0, ts = 50 * 60_000L)

        val signal = engine.evaluate(engine.compileBlueprint(blueprint), history + bullishBreak, 50)

        assertNull(signal)
    }

    // Built-in strategy smoke test.
    @Test
    fun `BuiltInStrategies evaluate without throwing`() {
        val candles = risingCandles(60)
        val signal1 = engine.evaluate(BuiltInStrategies.emaCross, candles, index = 55)
        val signal2 = engine.evaluate(BuiltInStrategies.rsiExtremes, candles, index = 55)
    }
}
