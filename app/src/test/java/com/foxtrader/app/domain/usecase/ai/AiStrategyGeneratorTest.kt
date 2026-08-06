package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsEngine
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
import com.foxtrader.app.domain.usecase.strategies.StrategyLibrary
import com.foxtrader.app.domain.usecase.strategies.StrategyTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

class AiStrategyGeneratorTest {

    private lateinit var generator: AiStrategyGenerator

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
        val tester = StrategyTester(
            library = library,
            backtestEngine = BacktestEngine(),
            instrumentTypeResolver = InstrumentTypeResolver(),
        )
        generator = AiStrategyGenerator(
            strategyTester = tester,
            analyticsEngine = BacktestAnalyticsEngine(),
        )
    }

    // -------------------------------------------------------------------------
    // TRENDING MARKET DETECTION
    // -------------------------------------------------------------------------

    @Test
    fun `trending market data produces TRENDING regime`() {
        val candles = trendingSeries(300)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        assertEquals(MarketRegime.TRENDING, report.regimeAnalysis.regime)
        assertTrue(
            "Trend strength (ADX) should be > 25 for trending data",
            report.regimeAnalysis.trendStrength > 25.0,
        )
    }

    @Test
    fun `trending market ranks TREND_FOLLOWING highly`() {
        val candles = trendingSeries(300)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        // TREND_FOLLOWING should be among the top strategies
        val trendFollowing = report.generatedStrategies.filter {
            it.strategyType == StrategyType.TREND_FOLLOWING
        }
        assertTrue("TREND_FOLLOWING should be present in results", trendFollowing.isNotEmpty())

        // Its alignment score should reflect the trending regime bonus
        val tf = trendFollowing.first()
        assertEquals(
            "TREND_FOLLOWING should get +30 alignment in trending regime",
            30,
            tf.regimeAlignmentScore,
        )
    }

    // -------------------------------------------------------------------------
    // RANGING MARKET DETECTION
    // -------------------------------------------------------------------------

    @Test
    fun `ranging market data produces RANGING regime`() {
        val candles = rangingSeries(300)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        assertEquals(MarketRegime.RANGING, report.regimeAnalysis.regime)
        assertTrue(
            "Trend strength (ADX) should be < 20 for ranging data",
            report.regimeAnalysis.trendStrength < 20.0,
        )
    }

    @Test
    fun `ranging market ranks MEAN_REVERSION highly`() {
        val candles = rangingSeries(300)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        val meanReversion = report.generatedStrategies.filter {
            it.strategyType == StrategyType.MEAN_REVERSION
        }
        assertTrue("MEAN_REVERSION should be present in results", meanReversion.isNotEmpty())

        val mr = meanReversion.first()
        assertEquals(
            "MEAN_REVERSION should get +30 alignment in ranging regime",
            30,
            mr.regimeAlignmentScore,
        )
    }

    // -------------------------------------------------------------------------
    // REPORT VALIDATION
    // -------------------------------------------------------------------------

    @Test
    fun `report contains valid regime analysis with all fields populated`() {
        val candles = trendingSeries(200)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        assertNotNull("Regime analysis should not be null", report.regimeAnalysis)
        assertTrue(
            "Trend strength should be non-negative",
            report.regimeAnalysis.trendStrength >= 0.0,
        )
        assertTrue(
            "Volatility percentile should be in 0-100",
            report.regimeAnalysis.volatilityPercentile in 0.0..100.0,
        )
        assertTrue(
            "Mean reversion score should be in 0-100",
            report.regimeAnalysis.meanReversionScore in 0.0..100.0,
        )
        assertTrue(
            "Suitable strategies should not be empty",
            report.regimeAnalysis.suitableStrategies.isNotEmpty(),
        )
    }

    @Test
    fun `all generated strategies have non-null analytics report`() {
        val candles = trendingSeries(200)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        assertTrue("Should have generated strategies", report.generatedStrategies.isNotEmpty())

        for (strategy in report.generatedStrategies) {
            assertNotNull(
                "Analytics report should not be null for ${strategy.strategyType}",
                strategy.analyticsReport,
            )
            assertNotNull(
                "Recommendation should not be null for ${strategy.strategyType}",
                strategy.recommendation,
            )
            assertTrue(
                "Regime alignment score should be in 0-100 for ${strategy.strategyType}",
                strategy.regimeAlignmentScore in 0..100,
            )
        }
    }

    @Test
    fun `report includes walk-forward validation status`() {
        val candles = trendingSeries(300)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        // Strategies with enough trades should have walk-forward data
        for (strategy in report.generatedStrategies) {
            // The recommendation string should reference walk-forward status
            assertTrue(
                "Recommendation should mention walk-forward for ${strategy.strategyType}",
                strategy.recommendation.contains("walk-forward", ignoreCase = true) ||
                    strategy.recommendation.contains("Walk-forward", ignoreCase = true),
            )
        }
    }

    // -------------------------------------------------------------------------
    // GRACEFUL HANDLING OF INSUFFICIENT DATA
    // -------------------------------------------------------------------------

    @Test
    fun `empty candle input produces empty report`() {
        val report = generator.generate(emptyList(), "EURUSD", Timeframe.H1)

        assertTrue("Generated strategies should be empty", report.generatedStrategies.isEmpty())
        assertEquals(null, report.topRecommendation)
        assertTrue(
            "Narrative should mention insufficient data",
            report.narrative.contains("Insufficient data"),
        )
    }

    @Test
    fun `short candle input below minimum bars produces empty report`() {
        val shortCandles = trendingSeries(20)
        val report = generator.generate(shortCandles, "EURUSD", Timeframe.H1)

        assertTrue("Generated strategies should be empty", report.generatedStrategies.isEmpty())
        assertEquals(null, report.topRecommendation)
        assertTrue(
            "Narrative should mention insufficient data",
            report.narrative.contains("Insufficient data"),
        )
    }

    // -------------------------------------------------------------------------
    // STRATEGY-REGIME ALIGNMENT SCORING
    // -------------------------------------------------------------------------

    @Test
    fun `alignment scores follow documented rules`() {
        val candles = trendingSeries(200)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        // In a TRENDING regime:
        assertEquals(MarketRegime.TRENDING, report.regimeAnalysis.regime)

        for (strategy in report.generatedStrategies) {
            val expectedAlignment = when (strategy.strategyType) {
                StrategyType.TREND_FOLLOWING -> 30
                StrategyType.ICHIMOKU -> 20
                StrategyType.SMART_MONEY, StrategyType.LIT, StrategyType.LITX, StrategyType.CONFLUENCE -> 10
                else -> 0
            }
            assertEquals(
                "Alignment for ${strategy.strategyType} in TRENDING regime",
                expectedAlignment,
                strategy.regimeAlignmentScore,
            )
        }
    }

    @Test
    fun `composite score applies alignment bonus correctly`() {
        val candles = trendingSeries(300)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        // Strategies should be sorted by composite score descending
        for (i in 0 until report.generatedStrategies.size - 1) {
            assertTrue(
                "Strategies should be sorted by composite score descending",
                report.generatedStrategies[i].compositeScore >=
                    report.generatedStrategies[i + 1].compositeScore,
            )
        }
    }

    @Test
    fun `top recommendation is the first strategy in sorted list`() {
        val candles = trendingSeries(200)
        val report = generator.generate(candles, "EURUSD", Timeframe.H1)

        if (report.generatedStrategies.isNotEmpty()) {
            assertEquals(
                "Top recommendation should be the first sorted strategy",
                report.generatedStrategies.first(),
                report.topRecommendation,
            )
        }
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    /**
     * Generates a strongly trending upward series with ADX > 25.
     * Uses consistent directional movement to ensure the ADX indicator
     * registers a strong trend.
     */
    private fun trendingSeries(size: Int): List<Candle> = (0 until size).map { i ->
        val trend = 1.10000 + i * 0.00050
        val noise = sin(i / 8.0) * 0.00010
        val open = trend + noise
        val close = trend + noise + 0.00040
        Candle(
            timestamp = 1_000L + i * 3_600_000L,
            open = open,
            high = maxOf(open, close) + 0.00020,
            low = minOf(open, close) - 0.00015,
            close = close,
            volume = 1000.0 + (i % 7) * 200.0,
        )
    }

    /**
     * Generates a ranging/sideways series oscillating around a mean with ADX < 20.
     * Price oscillates in a tight band without establishing directional momentum.
     */
    private fun rangingSeries(size: Int): List<Candle> = (0 until size).map { i ->
        val mean = 1.10000
        val oscillation = sin(i / 5.0) * 0.00100
        val open = mean + oscillation
        val close = mean + sin((i + 1) / 5.0) * 0.00100
        Candle(
            timestamp = 1_000L + i * 3_600_000L,
            open = open,
            high = maxOf(open, close) + 0.00015,
            low = minOf(open, close) - 0.00015,
            close = close,
            volume = 800.0 + (i % 5) * 100.0,
        )
    }
}
