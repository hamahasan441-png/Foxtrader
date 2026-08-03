package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeProOptimizerTest {

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

    private val optimizer = TradeProOptimizer(
        TradeProBacktestEngine(signalEngine, TradeManagementEngine()),
    )

    /** Oscillating uptrend at H1 spacing so HTF resampling and swings both work. */
    private fun uptrendH1(cycles: Int = 20): List<Candle> {
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

    @Test
    fun `too little history returns an empty report`() {
        val report = optimizer.optimize("MESUSD", uptrendH1(cycles = 2)) // 20 bars <= MIN_BARS
        assertTrue(report.candidates.isEmpty())
        assertEquals(0, report.evaluated)
        assertNull(report.best)
    }

    @Test
    fun `default grid evaluates every combination and partitions the history`() {
        val candles = uptrendH1()
        val report = optimizer.optimize("MESUSD", candles, baseTimeframe = Timeframe.H1)

        assertEquals("MESUSD", report.symbol)
        assertEquals(27, report.evaluated) // 3 x 3 x 3
        assertEquals(27, report.candidates.size)
        assertEquals(candles.size, report.inSampleBars + report.outOfSampleBars)
        assertNotNull("held-out slice large enough to validate", report.bestOutOfSample)
    }

    @Test
    fun `candidates are ranked qualified-first then by score, and best is the top`() {
        val report = optimizer.optimize("MESUSD", uptrendH1(), baseTimeframe = Timeframe.H1)
        val candidates = report.candidates

        assertEquals(report.best, candidates.firstOrNull())
        for (i in 0 until candidates.size - 1) {
            val a = candidates[i]
            val b = candidates[i + 1]
            // No unqualified candidate may precede a qualified one.
            assertTrue("qualified must come first", !(a.qualified.not() && b.qualified))
            // Within the same qualification tier, score is non-increasing.
            if (a.qualified == b.qualified) {
                assertTrue("scores must be non-increasing (${a.score} < ${b.score})", a.score >= b.score)
            }
        }
    }

    @Test
    fun `every candidate config stays within the grid and keeps coherent targets`() {
        val report = optimizer.optimize("MESUSD", uptrendH1(), baseTimeframe = Timeframe.H1)
        report.candidates.forEach { candidate ->
            val c = candidate.config
            assertTrue(c.stopPoints in listOf(2.0, 3.0, 4.0))
            assertTrue(c.target2Points in listOf(6.0, 8.0, 12.0))
            assertTrue(c.minEfficiencyRatio in listOf(0.20, 0.30, 0.40))
            assertEquals(c.target2Points / 2.0, c.target1Points, 1e-9)
            assertEquals(c.target2Points * 2.0, c.runnerPoints, 1e-9)
        }
    }

    @Test
    fun `optimization is deterministic for identical inputs`() {
        val candles = uptrendH1()
        val first = optimizer.optimize("MESUSD", candles, baseTimeframe = Timeframe.H1)
        val second = optimizer.optimize("MESUSD", candles, baseTimeframe = Timeframe.H1)
        assertEquals(first.candidates.map { it.label }, second.candidates.map { it.label })
        assertEquals(first.best?.config, second.best?.config)
        assertEquals(first.bestOutOfSample?.netPoints, second.bestOutOfSample?.netPoints)
    }
}
