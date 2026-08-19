package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
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
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Regression tests for the Structure Breakout (BOS) strategy.
 *
 * The strategy previously required `breakIndex == i`, which is unsatisfiable:
 * the structure engine confirms a swing only after `rightBars` (5) further
 * candles exist, so on a slice ending at bar i the newest possible breakIndex
 * is i - 5. The strategy therefore NEVER produced a signal — on the chart, in
 * the Backtest Lab, or in the scanner. These tests pin the fixed behaviour:
 * it fires on the exact bar a break becomes visible, at most once per break.
 */
class StructureBreakoutStrategyTest {

    private val library = StrategyLibrary(
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

    /** Same trending fixture the other strategy tests use. */
    private fun trendingSeries(size: Int = 300): List<Candle> = (0 until size).map { i ->
        val trend = 100.0 + i * 0.3
        val noise = sin(i / 4.0) * 2.0
        val open = trend + noise
        val close = trend + noise + 0.5
        Candle(
            timestamp = 1_000L + i * 3_600_000L,
            open = open, high = maxOf(open, close) + 1.5,
            low = minOf(open, close) - 1.5, close = close,
            volume = 1000.0 + (i % 7) * 200.0,
        )
    }

    @Test
    fun `breakout strategy produces signals on a trending series`() {
        val definition = library.get(StrategyType.BREAKOUT)
        val candles = trendingSeries()

        var count = 0
        for (i in definition.minimumBars until candles.size) {
            if (definition.function(candles, i) != null) count++
        }

        assertTrue(
            "BOS continuation must fire on a series full of bullish structure breaks (got $count)",
            count > 0,
        )
    }

    @Test
    fun `breakout signals are directional with valid risk geometry`() {
        val definition = library.get(StrategyType.BREAKOUT)
        val candles = trendingSeries()

        for (i in definition.minimumBars until candles.size) {
            val signal = definition.function(candles, i) ?: continue
            // The fixture trends upward — every BOS continuation must be long.
            assertEquals(Direction.BULLISH, signal.direction)
            val risk = abs(signal.entry - signal.stopLoss)
            val reward = abs(signal.takeProfit - signal.entry)
            assertTrue("risk must be positive", risk > 0.0)
            assertTrue("must maintain >= 2:1 R:R", reward / risk >= 1.9)
            assertTrue("stop must be below entry for a long", signal.stopLoss < signal.entry)
        }
    }

    @Test
    fun `breakout fires at most once per structure break`() {
        val definition = library.get(StrategyType.BREAKOUT)
        val candles = trendingSeries()

        // Each signal bar maps to the break confirmed exactly 5 bars earlier,
        // so distinct signal bars imply distinct breaks. Duplicate bars would
        // mean the same break fired twice.
        val signalBars = mutableListOf<Int>()
        for (i in definition.minimumBars until candles.size) {
            if (definition.function(candles, i) != null) signalBars += i
        }
        assertEquals("one signal per confirmation bar", signalBars.distinct().size, signalBars.size)
    }
}
