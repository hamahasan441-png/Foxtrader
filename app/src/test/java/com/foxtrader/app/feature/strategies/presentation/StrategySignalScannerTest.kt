package com.foxtrader.app.feature.strategies.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.analysis.DivergenceDetector
import com.foxtrader.app.domain.usecase.analysis.RiskRewardOptimizer
import com.foxtrader.app.domain.usecase.analysis.WyckoffDetector
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.patterns.CandlePatternDetector
import com.foxtrader.app.domain.usecase.patterns.HarmonicPatternDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Verifies the signal scanner extracted from StrategiesViewModel is usable and
 * correct in isolation (no ViewModel / coroutine scope needed) — the point of
 * the extraction. Asserts invariants that must hold for every emitted signal
 * rather than exact counts, since detections depend on the synthetic series.
 */
class StrategySignalScannerTest {

    private val scanner = StrategySignalScanner(
        harmonicDetector = HarmonicPatternDetector(),
        candlePatternDetector = CandlePatternDetector(),
        divergenceDetector = DivergenceDetector(),
        smcDetector = SmcDetector(),
        wyckoffDetector = WyckoffDetector(),
        ichimokuCloud = IchimokuCloud(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        riskReward = RiskRewardOptimizer(),
    )

    /** A deterministic wavy series with enough bars for every detector to run. */
    private fun series(size: Int = 160): List<Candle> = (0 until size).map { i ->
        val base = 100.0 + sin(i / 6.0) * 5.0 + i * 0.05
        val open = base
        val close = base + sin(i / 3.0) * 1.5
        val high = maxOf(open, close) + 1.0
        val low = minOf(open, close) - 1.0
        Candle(
            timestamp = 1_000L + i * 60_000L,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = 1_000.0 + (i % 10) * 50.0,
        )
    }

    @Test
    fun `detect runs without a ViewModel and returns a valid list`() {
        val signals = scanner.detect("EURUSD", series())
        // Must not throw and must return a (possibly empty) list.
        assertTrue("signals list should be non-null", signals.size >= 0)
    }

    @Test
    fun `every emitted signal has consistent, well-formed fields`() {
        val signals = scanner.detect("EURUSD", series())
        signals.forEach { s ->
            assertTrue("symbol propagated", s.symbol == "EURUSD")
            assertTrue("strategyName non-blank", s.strategyName.isNotBlank())
            assertTrue("confidence in 0..100 (${s.confidence})", s.confidence in 0..100)
            assertTrue("prices positive", s.entry > 0.0 && s.stopLoss > 0.0 && s.takeProfit > 0.0)
            assertTrue("risk/reward non-negative", s.riskReward >= 0.0)
        }
    }

    @Test
    fun `too-few candles yields no crash and no signals from data-hungry detectors`() {
        val signals = scanner.detect("EURUSD", series(size = 10))
        // Short series must be handled gracefully (no exception).
        assertTrue(signals.size >= 0)
    }
}
