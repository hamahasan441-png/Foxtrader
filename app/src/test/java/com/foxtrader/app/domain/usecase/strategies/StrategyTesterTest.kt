package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.LitXConfidenceScorer
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.litx.MitigationBlockDetector
import com.foxtrader.app.domain.usecase.litx.MssClassifier
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

class StrategyTesterTest {

    private lateinit var tester: StrategyTester

    @Before
    fun setup() {
        val library = StrategyLibrary(
            smcDetector = SmcDetector(),
            analyzeStructure = AnalyzeMarketStructureUseCase(),
            ichimokuCloud = IchimokuCloud(),
            litXEngine = LitXEngine(
                smcDetector = SmcDetector(),
                analyzeStructure = AnalyzeMarketStructureUseCase(),
                sessionDetector = SessionDetector(),
                displacementDetector = DisplacementDetector(),
                mitigationDetector = MitigationBlockDetector(),
                premiumDiscount = PremiumDiscountCalculator(),
                mssClassifier = MssClassifier(),
                scorer = LitXConfidenceScorer(),
            ),
        )
        tester = StrategyTester(
            library = library,
            backtestEngine = BacktestEngine(),
            instrumentTypeResolver = InstrumentTypeResolver(),
        )
    }

    // -------------------------------------------------------------------------
    // SINGLE STRATEGY TEST
    // -------------------------------------------------------------------------

    @Test
    fun `test single strategy returns a StrategyTestResult with metrics`() {
        val candles = trendingSeries(200)
        val result = tester.test(
            type = StrategyType.TREND_FOLLOWING,
            candles = candles,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )

        assertTrue("Should have a definition name", result.definition.name.isNotBlank())
        assertTrue("Total trades should be >= 0", result.backtest.metrics.totalTrades >= 0)
        assertTrue("Final balance should be positive", result.backtest.metrics.finalBalance > 0)
        assertEquals(StrategyType.TREND_FOLLOWING, result.definition.type)
    }

    // -------------------------------------------------------------------------
    // testAll SKIPS STRATEGIES REQUIRING MORE BARS
    // -------------------------------------------------------------------------

    @Test
    fun `testAll skips strategies requiring more bars than available`() {
        // Provide only 10 candles -- most strategies need 50+ bars.
        val shortCandles = trendingSeries(10)
        val report = tester.testAll(
            candles = shortCandles,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )

        // Count how many strategies have minimumBars <= 10
        val library = StrategyLibrary(
            smcDetector = SmcDetector(),
            analyzeStructure = AnalyzeMarketStructureUseCase(),
            ichimokuCloud = IchimokuCloud(),
            litXEngine = LitXEngine(
                smcDetector = SmcDetector(),
                analyzeStructure = AnalyzeMarketStructureUseCase(),
                sessionDetector = SessionDetector(),
                displacementDetector = DisplacementDetector(),
                mitigationDetector = MitigationBlockDetector(),
                premiumDiscount = PremiumDiscountCalculator(),
                mssClassifier = MssClassifier(),
                scorer = LitXConfidenceScorer(),
            ),
        )
        val eligible = library.all().values.count { it.minimumBars <= 10 }

        assertEquals(
            "Only strategies with minimumBars <= candles.size should be tested",
            eligible,
            report.results.size,
        )
        // Total strategies is 9, most require more than 10 bars
        assertTrue(
            "Some strategies should be skipped",
            report.results.size < StrategyType.entries.size,
        )
    }

    // -------------------------------------------------------------------------
    // testAll RANKS BY SCORE DESCENDING
    // -------------------------------------------------------------------------

    @Test
    fun `testAll ranks results by score descending`() {
        val candles = trendingSeries(200)
        val report = tester.testAll(
            candles = candles,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )

        assertTrue("Should have at least 2 results for ranking test", report.results.size >= 2)

        for (i in 0 until report.results.size - 1) {
            assertTrue(
                "Results must be sorted descending by score: " +
                    "${report.results[i].score} should be >= ${report.results[i + 1].score}",
                report.results[i].score >= report.results[i + 1].score,
            )
        }
    }

    // -------------------------------------------------------------------------
    // CONTRACT SIZE RESOLVED FROM SYMBOL
    // -------------------------------------------------------------------------

    @Test
    fun `contract size is resolved from symbol for the backtest config`() {
        val candles = trendingSeries(200)

        // Test with forex symbol (contract size = 100000)
        val forexResult = tester.test(
            type = StrategyType.TREND_FOLLOWING,
            candles = candles,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )
        assertEquals(100_000, forexResult.backtest.config.contractSize)

        // Test with crypto symbol (contract size = 1)
        val cryptoResult = tester.test(
            type = StrategyType.TREND_FOLLOWING,
            candles = candles,
            symbol = "BTCUSD",
            timeframe = Timeframe.H1,
        )
        assertEquals(1, cryptoResult.backtest.config.contractSize)

        // Test with gold symbol (contract size = 100)
        val goldResult = tester.test(
            type = StrategyType.TREND_FOLLOWING,
            candles = candles,
            symbol = "XAUUSD",
            timeframe = Timeframe.H1,
        )
        assertEquals(100, goldResult.backtest.config.contractSize)
    }

    // -------------------------------------------------------------------------
    // SCORE IS ZERO WITH FEWER THAN 3 TRADES
    // -------------------------------------------------------------------------

    @Test
    fun `score is zero when fewer than 3 trades`() {
        // Use only 5 bars -- guaranteed to produce fewer than 3 trades for any strategy,
        // ensuring the zero-score path is always exercised (no conditional assertion needed).
        val shortCandles = trendingSeries(5)
        val result = tester.test(
            type = StrategyType.TREND_FOLLOWING,
            candles = shortCandles,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )

        assertTrue(
            "With only 5 bars, fewer than 3 trades must be produced",
            result.backtest.metrics.totalTrades < 3,
        )
        assertEquals(
            "Score must be 0.0 when totalTrades < 3",
            0.0,
            result.score,
            1e-10,
        )
    }

    @Test
    fun `score formula produces non-zero result with sufficient trades`() {
        // Run with enough data to produce trades
        val candles = trendingSeries(300)
        val report = tester.testAll(
            candles = candles,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )

        // At least one strategy should have 3+ trades and thus a non-zero score
        val withTrades = report.results.filter { it.backtest.metrics.totalTrades >= 3 }
        if (withTrades.isNotEmpty()) {
            assertTrue(
                "Strategies with 3+ trades should have non-zero score",
                withTrades.any { it.score > 0.0 },
            )
        }
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private fun trendingSeries(size: Int): List<Candle> = (0 until size).map { i ->
        val trend = 100.0 + i * 0.3
        val noise = sin(i / 4.0) * 2.0
        val open = trend + noise
        val close = trend + noise + 0.5
        Candle(
            timestamp = 1_000L + i * 3_600_000L,
            open = open,
            high = maxOf(open, close) + 1.5,
            low = minOf(open, close) - 1.5,
            close = close,
            volume = 1000.0 + (i % 7) * 200.0,
        )
    }
}
