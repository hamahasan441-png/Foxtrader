package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsEngine
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AiStrategyOptimizer.
 * Validates scoring, ranking, overfitting detection, and graceful degradation.
 */
class AiStrategyOptimizerTest {

    private lateinit var optimizer: AiStrategyOptimizer
    private val backtestEngine = BacktestEngine()

    @Before
    fun setup() {
        optimizer = AiStrategyOptimizer(
            backtestEngine = backtestEngine,
            analyticsEngine = BacktestAnalyticsEngine(),
        )
    }

    // ------------------------------------------------------------------ helpers

    private val simpleStrategy: StrategyFunction = { candles, i ->
        if (i < 20) null
        else {
            val close = candles[i].close
            val prevClose = candles[i - 1].close
            if (close > prevClose) {
                StrategySignal(
                    index = i,
                    timestamp = candles[i].timestamp,
                    direction = Direction.BULLISH,
                    entry = close,
                    stopLoss = close - 0.001,
                    takeProfit = close + 0.003,
                )
            } else null
        }
    }

    private fun trendingSeries(size: Int): List<Candle> = (0 until size).map { i ->
        val trend = 1.10000 + i * 0.00050
        val open = trend
        val close = trend + 0.00040
        Candle(
            timestamp = 1_000L + i * 3_600_000L,
            open = open,
            high = maxOf(open, close) + 0.00020,
            low = minOf(open, close) - 0.00015,
            close = close,
            volume = 1000.0 + (i % 7) * 200.0,
        )
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `optimizer produces ranked candidates sorted by composite score descending`() {
        val candles = trendingSeries(200)
        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = simpleStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Simple Momentum",
        )

        assertTrue("Should have candidates", report.candidates.isNotEmpty())

        // Verify sorting: qualified first, then by score descending
        val qualifiedCandidates = report.candidates.filter { it.qualified }
        for (i in 0 until qualifiedCandidates.size - 1) {
            assertTrue(
                "Qualified candidates should be sorted by score descending",
                qualifiedCandidates[i].compositeScore >= qualifiedCandidates[i + 1].compositeScore,
            )
        }
    }

    @Test
    fun `best candidate has the highest score among qualified`() {
        val candles = trendingSeries(200)
        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = simpleStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Simple Momentum",
        )

        val best = report.bestCandidate
        assertNotNull("Best candidate should not be null", best)

        val qualifiedCandidates = report.candidates.filter { it.qualified }
        if (qualifiedCandidates.isNotEmpty()) {
            val highestScore = qualifiedCandidates.maxOf { it.compositeScore }
            assertEquals(
                "Best candidate should have the highest qualified score",
                highestScore,
                best!!.compositeScore,
                0.001,
            )
        }
    }

    @Test
    fun `overfitting is flagged when out-of-sample degrades significantly`() {
        // Use a strategy designed to overfit: it performs well only on the first section of data
        // by generating signals only in low-index candles (training portion)
        val candles = trendingSeries(200)
        val splitPoint = (200 * 0.7).toInt() // 140

        // Strategy that generates signals only up to the split point
        val overfitStrategy: StrategyFunction = { candleList, i ->
            if (i < 20 || i >= splitPoint - 5) null
            else {
                val close = candleList[i].close
                val prevClose = candleList[i - 1].close
                if (close > prevClose) {
                    StrategySignal(
                        index = i,
                        timestamp = candleList[i].timestamp,
                        direction = Direction.BULLISH,
                        entry = close,
                        stopLoss = close - 0.001,
                        takeProfit = close + 0.003,
                    )
                } else null
            }
        }

        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = overfitStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Overfit Strategy",
        )

        // Since the strategy generates no trades on out-of-sample,
        // out-of-sample profit factor = 0.0 < 0.5 * in-sample profit factor
        if (report.bestCandidate != null && report.bestOutOfSample != null) {
            val inPf = report.bestCandidate!!.backtestResult.metrics.profitFactor
            val outPf = report.bestOutOfSample!!.metrics.profitFactor
            if (inPf > 0.0 && outPf < 0.5 * inPf) {
                assertTrue(
                    "Should flag overfitting when OOS degrades",
                    report.overfitWarning,
                )
            }
        }
    }

    @Test
    fun `insufficient data returns empty report gracefully`() {
        val shortCandles = trendingSeries(30) // Less than 50 minimum
        val report = optimizer.optimize(
            candles = shortCandles,
            strategyFunction = simpleStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Test",
        )

        assertTrue("Candidates should be empty", report.candidates.isEmpty())
        assertFalse("Should not flag overfitting", report.overfitWarning)
        assertTrue("Narrative should mention insufficient data", report.narrative.isNotBlank())
        assertEquals("EURUSD", report.symbol)
        assertEquals("Test", report.strategyName)
    }

    @Test
    fun `minimum trade count filter marks candidates as not qualified`() {
        val candles = trendingSeries(200)

        // Strategy that produces very few signals (only one every 50 bars)
        val sparseStrategy: StrategyFunction = { candleList, i ->
            if (i % 50 == 25 && i > 20) {
                val close = candleList[i].close
                StrategySignal(
                    index = i,
                    timestamp = candleList[i].timestamp,
                    direction = Direction.BULLISH,
                    entry = close,
                    stopLoss = close - 0.001,
                    takeProfit = close + 0.003,
                )
            } else null
        }

        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = sparseStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Sparse Strategy",
            minTrades = 5,
        )

        // Some candidates should be not qualified due to low trade count in 70% of 200 bars
        if (report.candidates.isNotEmpty()) {
            val unqualified = report.candidates.filter { !it.qualified }
            // With very sparse signals on 140 in-sample bars, most will have < 5 trades
            assertTrue(
                "Some candidates should not be qualified with sparse signals",
                unqualified.isNotEmpty(),
            )
            // Check that unqualified candidates actually have fewer than 5 trades
            unqualified.forEach { candidate ->
                assertTrue(
                    "Unqualified candidate should have fewer than 5 trades",
                    candidate.backtestResult.metrics.totalTrades < 5,
                )
            }
        }
    }

    @Test
    fun `narrative is never blank`() {
        val candles = trendingSeries(200)
        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = simpleStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Test Strategy",
        )

        assertTrue("Narrative should not be blank", report.narrative.isNotBlank())
    }

    @Test
    fun `narrative is not blank for empty report`() {
        val shortCandles = trendingSeries(10)
        val report = optimizer.optimize(
            candles = shortCandles,
            strategyFunction = simpleStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Test",
        )

        assertTrue("Narrative should not be blank even for empty report", report.narrative.isNotBlank())
    }

    @Test
    fun `report contains correct symbol and timeframe`() {
        val candles = trendingSeries(200)
        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = simpleStrategy,
            symbol = "GBPUSD",
            timeframe = Timeframe.M15,
            strategyName = "Momentum",
        )

        assertEquals("GBPUSD", report.symbol)
        assertEquals(Timeframe.M15, report.timeframe)
        assertEquals("Momentum", report.strategyName)
    }

    @Test
    fun `train and test bars are correct based on split`() {
        val candles = trendingSeries(200)
        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = simpleStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Test",
        )

        assertEquals("Train bars should be 70% of 200", 140, report.trainBars)
        assertEquals("Test bars should be 30% of 200", 60, report.testBars)
    }

    @Test
    fun `grid expansion produces expected number of candidates`() {
        val grid = OptimizationGrid(
            riskPercentValues = listOf(1.0, 2.0),
            spreadValues = listOf(0.00001, 0.00002),
            slippageValues = listOf(0.0, 0.00001),
        )
        val candles = trendingSeries(200)
        val report = optimizer.optimize(
            candles = candles,
            strategyFunction = simpleStrategy,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            strategyName = "Test",
            grid = grid,
        )

        // 2 risk x 2 spread x 2 slippage = 8 combinations
        assertEquals("Should have 8 candidates", 8, report.candidates.size)
    }
}
