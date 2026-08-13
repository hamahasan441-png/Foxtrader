package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [BacktestEngine] metric stability.
 *
 * A backtest report is a decision-making artifact: a "NaN" Sharpe or an
 * "Infinity%" return is worse than no number at all, because it silently
 * invalidates every ranking built on top of it (the strategy comparison list,
 * the optimizer's objective, the analytics report).
 */
class BacktestEngineDegenerateConfigTest {

    private fun candles(n: Int): List<Candle> = (0 until n).map { i ->
        val c = 100.0 + kotlin.math.sin(i / 7.0)
        Candle(1_700_000_000_000L + i * 60_000L, c, c + 0.5, c - 0.5, c + 0.1, 1_000.0)
    }

    /** Always-in strategy so trades are actually generated. */
    private val alwaysLong: StrategyFunction = { cs, i ->
        val c = cs[i]
        StrategySignal(
            index = i,
            timestamp = c.timestamp,
            direction = Direction.BULLISH,
            entry = c.close,
            stopLoss = c.close * 0.99,
            takeProfit = c.close * 1.01,
            volume = 0.1,
        )
    }

    private val never: StrategyFunction = { _, _ -> null }

    private fun assertMetricsFinite(label: String, engine: BacktestEngine, candles: List<Candle>, strategy: StrategyFunction) {
        val result = engine(candles, strategy)
        val m = result.metrics
        assertTrue("$label sharpeRatio", m.sharpeRatio.isFinite())
        assertTrue("$label sortinoRatio", m.sortinoRatio.isFinite())
        assertTrue("$label calmarRatio", m.calmarRatio.isFinite())
        assertTrue("$label profitFactor", m.profitFactor.isFinite())
        assertTrue("$label expectancy", m.expectancy.isFinite())
        assertTrue("$label winRate", m.winRate.isFinite())
        assertTrue("$label returnPercent", m.returnPercent.isFinite())
        assertTrue("$label finalBalance", m.finalBalance.isFinite())
        assertTrue("$label durationDays", result.durationDays.isFinite())
    }

    @Test
    fun `metrics stay finite with a zero initial balance`() {
        // initialBalance == 0 divided both the per-trade return series (NaN
        // Sharpe/Sortino) and the headline return percentage (Infinity).
        val engine = BacktestEngine()
        engine.updateConfig(BacktestConfig(initialBalance = 0.0))
        assertMetricsFinite("zero-balance", engine, candles(300), alwaysLong)
    }

    @Test
    fun `metrics stay finite when the running balance is wiped out mid-run`() {
        // A large per-lot commission against a tiny balance drives the running
        // balance through zero, which is the divisor in computeReturns().
        val engine = BacktestEngine()
        engine.updateConfig(BacktestConfig(initialBalance = 1.0, commissionPerLot = 10_000.0))
        assertMetricsFinite("wiped-out", engine, candles(300), alwaysLong)
    }

    @Test
    fun `metrics stay finite for empty and tiny candle series`() {
        val engine = BacktestEngine()
        for (n in listOf(0, 1, 2)) {
            assertMetricsFinite("n=$n/always", engine, candles(n), alwaysLong)
            assertMetricsFinite("n=$n/never", engine, candles(n), never)
        }
    }

    @Test
    fun `metrics stay finite when every trade has a zero risk distance`() {
        // stopLoss == entry makes the R-multiple divisor zero.
        val engine = BacktestEngine()
        val zeroRisk: StrategyFunction = { cs, i ->
            val c = cs[i]
            StrategySignal(
                index = i,
                timestamp = c.timestamp,
                direction = Direction.BULLISH,
                entry = c.close,
                stopLoss = c.close,
                takeProfit = c.close,
                volume = 0.1,
            )
        }
        assertMetricsFinite("zero-risk", engine, candles(300), zeroRisk)
    }

    @Test
    fun `analytics report survives an empty backtest`() {
        val engine = BacktestEngine()
        val analytics = BacktestAnalyticsEngine()
        analytics.analyze(engine(emptyList(), never))
        analytics.analyze(engine(candles(300), alwaysLong))
    }
}
