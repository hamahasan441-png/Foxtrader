package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeProBacktestEngineTest {

    private val signalEngine = TradeProSignalEngine(
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        flipZoneEngine = FlipZoneEngine(),
        orderFlowProvider = CandleDerivedOrderFlowProvider(),
        imbalanceDetector = ImbalanceDetector(),
        absorptionDetector = AbsorptionDetector(),
        holdZoneEngine = HoldZoneEngine(),
        riskGuard = TradeProRiskGuard(),
        trendRegimeFilter = TrendRegimeFilter(),
        mtfEngine = MtfTradeProEngine(AnalyzeMarketStructureUseCase(), FlipZoneEngine()),
    )

    private val engine = TradeProBacktestEngine(
        signalEngine = signalEngine,
        managementEngine = TradeManagementEngine(),
    )

    private fun flat(n: Int): List<Candle> =
        (0 until n).map { Candle(it * 60_000L, 100.0, 100.0, 100.0, 100.0, 100.0) }

    /** Long oscillating uptrend: rising legs with shallow pullbacks so real structure/swings form. */
    private fun uptrend(cycles: Int = 30): List<Candle> {
        val list = ArrayList<Candle>()
        var price = 100.0
        var t = 0L
        repeat(cycles) {
            repeat(7) {
                val open = price
                val close = price + 1.0
                list += Candle(t, open, close + 0.2, open - 0.2, close, 120.0)
                price = close
                t += 60_000L
            }
            repeat(3) {
                val open = price
                val close = price - 0.5
                list += Candle(t, open, open + 0.2, close - 0.2, close, 80.0)
                price = close
                t += 60_000L
            }
        }
        return list
    }

    @Test
    fun `too little history returns an empty stand-aside result`() {
        val result = engine.run("MESUSD", flat(20))
        assertEquals(0, result.totalTrades)
        assertTrue(result.trades.isEmpty())
        assertTrue(result.equityCurve.isEmpty())
        assertEquals(0.0, result.netPoints, 0.0)
    }

    @Test
    fun `flat market never triggers a setup`() {
        val result = engine.run("MESUSD", flat(200))
        assertEquals(0, result.totalTrades)
        assertEquals(0.0, result.netPoints, 0.0)
    }

    @Test
    fun `backtesting a long trend produces a self-consistent report`() {
        val result = engine.run("MESUSD", uptrend())

        assertEquals("MESUSD", result.symbol)
        // Bucket counts always partition the trades.
        assertEquals(result.totalTrades, result.wins + result.losses + result.breakeven)
        assertEquals(result.totalTrades, result.trades.size)
        assertEquals(result.totalTrades, result.equityCurve.size)

        // Rates are genuine fractions.
        assertTrue(result.winRate in 0.0..1.0)
        assertTrue(result.t1HitRate in 0.0..1.0)
        assertTrue(result.t2HitRate in 0.0..1.0)
        assertTrue(result.runnerHitRate in 0.0..1.0)
        assertTrue("profit factor is non-negative", result.profitFactor >= 0.0)
        assertTrue(result.maxDrawdownPoints >= 0.0)
        assertTrue(result.maxWinStreak >= 0 && result.maxLossStreak >= 0)

        if (result.totalTrades > 0) {
            // The equity curve's final value is the net P&L.
            assertEquals(result.netPoints, result.equityCurve.last(), 1e-6)
            // Expectancy is net over trade count.
            assertEquals(result.netPoints / result.totalTrades, result.expectancy, 1e-6)
        }
    }

    @Test
    fun `every recorded trade obeys accounting invariants`() {
        val result = engine.run("MESUSD", uptrend())
        for (trade in result.trades) {
            assertTrue("net points must be finite", trade.netPoints.isFinite())
            assertTrue("R multiple must be finite", trade.rMultiple.isFinite())
            assertTrue("contracts positive", trade.contracts >= 1)
            assertTrue("exit reason recorded", trade.exitReason.isNotBlank())
            assertTrue("confidence in range", trade.confidence in 0..100)
            // A trade cannot reach T2 without first reaching T1, nor the runner without T2.
            if (trade.reachedT2) assertTrue(trade.reachedT1)
            if (trade.reachedRunner) assertTrue(trade.reachedT2)
            // Exit never precedes entry.
            assertTrue(trade.exitTimestamp >= trade.entryTimestamp)
        }
    }

    @Test
    fun `the backtest is deterministic for identical inputs`() {
        val candles = uptrend()
        val first = engine.run("MESUSD", candles)
        val second = engine.run("MESUSD", candles)
        assertEquals(first.totalTrades, second.totalTrades)
        assertEquals(first.netPoints, second.netPoints, 1e-9)
        assertEquals(first.winRate, second.winRate, 1e-9)
        assertEquals(first.equityCurve, second.equityCurve)
    }

    /**
     * Same oscillating uptrend but at H1 spacing and enough bars that the trailing window
     * resamples into >= 30 H4 bars — so MTF bias validation is actually exercised.
     */
    private fun uptrendH1(cycles: Int = 40): List<Candle> {
        val list = ArrayList<Candle>()
        var price = 100.0
        var t = 0L
        repeat(cycles) {
            repeat(7) {
                val open = price
                val close = price + 1.0
                list += Candle(t, open, close + 0.2, open - 0.2, close, 120.0)
                price = close
                t += 3_600_000L
            }
            repeat(3) {
                val open = price
                val close = price - 0.5
                list += Candle(t, open, open + 0.2, close - 0.2, close, 80.0)
                price = close
                t += 3_600_000L
            }
        }
        return list
    }

    private fun assertSelfConsistent(result: com.foxtrader.app.domain.model.tradepro.TradeProBacktestResult) {
        assertEquals(result.totalTrades, result.wins + result.losses + result.breakeven)
        assertEquals(result.totalTrades, result.trades.size)
        assertEquals(result.totalTrades, result.equityCurve.size)
        assertTrue(result.winRate in 0.0..1.0)
        assertTrue(result.t1HitRate in 0.0..1.0)
        assertTrue(result.t2HitRate in 0.0..1.0)
        assertTrue(result.runnerHitRate in 0.0..1.0)
        assertTrue(result.profitFactor >= 0.0)
        assertTrue(result.maxDrawdownPoints >= 0.0)

        // Analytics: series line up with the trade count and the drawdown curve is coherent.
        assertEquals(result.totalTrades, result.rMultiples.size)
        assertEquals(result.totalTrades, result.drawdownCurve.size)
        assertTrue(result.drawdownCurve.all { it >= 0.0 })
        assertTrue(result.systemQualityNumber.isFinite())
        assertTrue(result.payoffRatio >= 0.0)

        if (result.totalTrades > 0) {
            assertEquals(result.netPoints, result.equityCurve.last(), 1e-6)
            assertEquals(result.netPoints / result.totalTrades, result.expectancy, 1e-6)
            // The deepest point of the underwater curve is exactly the reported max drawdown.
            assertEquals(result.maxDrawdownPoints, result.drawdownCurve.maxOrNull() ?: 0.0, 1e-6)
            // avgR is the mean of the per-trade R list.
            assertEquals(result.rMultiples.sum() / result.totalTrades, result.avgR, 1e-6)
        }
    }

    @Test
    fun `mtf-mode backtest over an H1 trend is self-consistent`() {
        val result = engine.run(
            symbol = "MESUSD",
            candles = uptrendH1(),
            baseTimeframe = Timeframe.H1,
        )
        assertEquals("MESUSD", result.symbol)
        assertSelfConsistent(result)
    }

    @Test
    fun `single-timeframe and mtf modes both run and stay self-consistent`() {
        val candles = uptrendH1()
        val single = engine.run("MESUSD", candles, baseTimeframe = Timeframe.H1, multiTimeframe = false)
        val mtf = engine.run("MESUSD", candles, baseTimeframe = Timeframe.H1, multiTimeframe = true)
        assertSelfConsistent(single)
        assertSelfConsistent(mtf)
    }

    @Test
    fun `mtf-mode backtest is deterministic for identical inputs`() {
        val candles = uptrendH1()
        val first = engine.run("MESUSD", candles, baseTimeframe = Timeframe.H1)
        val second = engine.run("MESUSD", candles, baseTimeframe = Timeframe.H1)
        assertEquals(first.totalTrades, second.totalTrades)
        assertEquals(first.netPoints, second.netPoints, 1e-9)
        assertEquals(first.equityCurve, second.equityCurve)
        // The full analytics payload is deterministic too.
        assertEquals(first.rMultiples, second.rMultiples)
        assertEquals(first.drawdownCurve, second.drawdownCurve)
        assertEquals(first.systemQualityNumber, second.systemQualityNumber, 1e-9)
    }

    @Test
    fun `analytics metrics are exposed and coherent`() {
        val result = engine.run("MESUSD", uptrendH1(), baseTimeframe = Timeframe.H1)
        assertSelfConsistent(result)
        // SQN is only defined with >= 2 trades; it collapses to zero otherwise.
        if (result.totalTrades < 2) {
            assertEquals(0.0, result.systemQualityNumber, 0.0)
        }
        // The underwater curve is monotone in its running maximum and never exceeds max drawdown.
        var runningMax = 0.0
        for (dd in result.drawdownCurve) {
            assertTrue(dd >= 0.0)
            if (dd > runningMax) runningMax = dd
        }
        assertTrue(runningMax <= result.maxDrawdownPoints + 1e-9)
    }

    // -------------------------------------------------------------------------
    // EDGE CASES
    // -------------------------------------------------------------------------

    @Test
    fun `single bar above MIN_BARS returns empty result`() {
        // MIN_BARS + 1 is technically above the guard but unlikely to produce any setups
        // because the signal engine needs market structure that cannot form in that few bars.
        val minRequired = TradeProSignalEngine.MIN_BARS + 1
        val candles = flat(minRequired)
        val result = engine.run("MESUSD", candles)
        // With flat prices no structure forms, so no trades should trigger.
        assertEquals(0, result.totalTrades)
        assertTrue(result.trades.isEmpty())
        assertEquals(0.0, result.netPoints, 0.0)
    }

    @Test
    fun `narrative is never blank regardless of trade count`() {
        val noTradesResult = engine.run("MESUSD", flat(200))
        assertTrue("Narrative must be populated even with 0 trades", noTradesResult.narrative.isNotBlank())

        val withTradesResult = engine.run("MESUSD", uptrend())
        assertTrue("Narrative must be populated with trades", withTradesResult.narrative.isNotBlank())
    }

    @Test
    fun `symbol is preserved in the result`() {
        val result1 = engine.run("EURUSD", uptrend())
        assertEquals("EURUSD", result1.symbol)

        val result2 = engine.run("BTCUSD", uptrend())
        assertEquals("BTCUSD", result2.symbol)
    }

    @Test
    fun `equity curve length equals trade count`() {
        val result = engine.run("MESUSD", uptrend())
        assertEquals(result.totalTrades, result.equityCurve.size)
        assertEquals(result.totalTrades, result.drawdownCurve.size)
        assertEquals(result.totalTrades, result.rMultiples.size)
    }
}
