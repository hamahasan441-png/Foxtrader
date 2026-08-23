package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerSymbol
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.analysis.WyckoffDetector
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.LitXConfidenceScorer
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.litx.MitigationBlockDetector
import com.foxtrader.app.domain.usecase.litx.MssClassifier
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.patterns.CandlePatternDetector
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettings
import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettingsRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScannerUseCaseTest {

    private lateinit var scanner: ScannerUseCase

    @Before
    fun setup() {
        StrategyRuntimeSettingsRegistry.resetAll()
        scanner = ScannerUseCase(
            smcDetector = SmcDetector(),
            candlePatternDetector = CandlePatternDetector(),
            ichimokuCloud = IchimokuCloud(),
            bollingerBands = BollingerBands(),
            wyckoffDetector = WyckoffDetector(),
            analyzeStructure = AnalyzeMarketStructureUseCase(),
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
        scanner.setWatchlist(listOf(ScreenerSymbol("EURUSD", AssetClass.FOREX)))
    }

    @After
    fun tearDown() {
        StrategyRuntimeSettingsRegistry.resetAll()
    }

    @Test
    fun `scanner result includes deterministic rationale and risk level`() {
        val output = scanner(
            dataMap = mapOf("EURUSD" to trendingCandles()),
            strategy = StrategyType.CONFLUENCE,
        )

        assertEquals(1, output.results.size)
        val result = output.results.first()
        assertTrue(result.rationale.contains("EURUSD"))
        assertTrue(result.rationale.contains(StrategyType.CONFLUENCE.label))
        assertTrue(result.rationale.contains("Risk:"))
        assertTrue(result.tags.contains("PACKAGE"))
        assertTrue(result.riskLevel in ScannerRiskLevel.entries)
    }

    @Test
    fun `high volatility or low score classifies as high scanner risk`() {
        val output = scanner(
            dataMap = mapOf("EURUSD" to volatileCandles()),
            strategy = StrategyType.BREAKOUT,
        )

        assertEquals(1, output.results.size)
        assertEquals(ScannerRiskLevel.HIGH, output.results.first().riskLevel)
    }

    @Test
    fun `LIT X omits symbols with no validated signal instead of coercing them bullish`() {
        val output = scanner(
            dataMap = mapOf("EURUSD" to flatCandles()),
            strategy = StrategyType.LITX,
        )

        assertTrue("a directionless LIT X scan must yield no rows", output.results.isEmpty())
        assertTrue(output.validatedLitXSignals.isEmpty())
    }

    @Test
    fun `scanner obeys shared direction controls from strategy gear`() {
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.CONFLUENCE,
            StrategyRuntimeSettings(allowBullish = false, allowBearish = true),
        )

        val output = scanner(
            dataMap = mapOf("EURUSD" to trendingCandles()),
            strategy = StrategyType.CONFLUENCE,
        )

        assertTrue("bullish package ranking must be hidden when bullish is disabled", output.results.isEmpty())
    }

    @Test
    fun `scanner obeys shared minimum confidence on package rankings`() {
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.CONFLUENCE,
            StrategyRuntimeSettings(minimumConfidence = 96),
        )

        val output = scanner(
            dataMap = mapOf("EURUSD" to trendingCandles()),
            strategy = StrategyType.CONFLUENCE,
        )

        assertTrue("package score is capped below 96 and must be filtered", output.results.isEmpty())
    }

    private fun flatCandles(): List<Candle> = (0 until 90).map { i ->
        Candle(
            timestamp = i * 60_000L,
            open = 1.1000,
            high = 1.1004,
            low = 1.0996,
            close = 1.1000,
            volume = 100.0,
        )
    }

    private fun trendingCandles(): List<Candle> = (0 until 90).map { i ->
        val close = 1.1000 + i * 0.0008
        Candle(
            timestamp = i * 60_000L,
            open = close - 0.0002,
            high = close + 0.0006,
            low = close - 0.0006,
            close = close,
            volume = 100.0 + i,
        )
    }

    private fun volatileCandles(): List<Candle> = (0 until 90).map { i ->
        val base = 1.1000 + (i % 5 - 2) * 0.004
        Candle(
            timestamp = i * 60_000L,
            open = base - 0.004,
            high = base + 0.035,
            low = base - 0.035,
            close = base + if (i % 2 == 0) 0.004 else -0.004,
            volume = 200.0,
        )
    }
}
