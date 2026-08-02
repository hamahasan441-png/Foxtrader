package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
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
}
