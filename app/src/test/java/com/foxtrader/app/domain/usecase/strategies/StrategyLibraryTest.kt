package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class StrategyLibraryTest {

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

    /** A trending series with enough bars for all strategies. */
    private fun trendingSeries(size: Int = 200): List<Candle> = (0 until size).map { i ->
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
    fun `all strategy types are registered`() {
        val all = library.all()
        assertTrue("Every StrategyType must have a definition", all.size == StrategyType.entries.size)
        for (type in StrategyType.entries) {
            assertNotNull("Missing strategy for $type", all[type])
        }
    }


    @Test
    fun `every strategy is non-repainting — signal at index i only reads 0 to i`() {
        val candles = trendingSeries()
        for ((type, definition) in library.all()) {
            for (i in definition.minimumBars until candles.size) {
                val signal = definition.function(candles, i)
                if (signal != null) {
                    assertTrue(
                        "$type signal at $i must have index == i",
                        signal.index == i,
                    )
                    assertTrue(
                        "$type entry price must be positive",
                        signal.entry > 0.0,
                    )
                    assertTrue(
                        "$type stop must differ from entry",
                        signal.stopLoss != signal.entry,
                    )
                    val risk = abs(signal.entry - signal.stopLoss)
                    val reward = abs(signal.takeProfit - signal.entry)
                    assertTrue(
                        "$type R:R must be >= 2.0, got ${reward / risk}",
                        risk > 0.0 && reward / risk >= 1.9,
                    )
                }
            }
        }
    }

    @Test
    fun `strategies handle short data gracefully (no crash)`() {
        val shortCandles = trendingSeries(size = 20)
        for ((type, definition) in library.all()) {
            for (i in shortCandles.indices) {
                val signal = definition.function(shortCandles, i)
                // Must not throw; if minimumBars > size, null is expected.
                if (i < definition.minimumBars) {
                    assertTrue("$type should return null for i=$i < min", signal == null)
                }
            }
        }
    }

    @Test
    fun `strategy definitions have valid metadata`() {
        for ((_, def) in library.all()) {
            assertTrue("Name non-blank", def.name.isNotBlank())
            assertTrue("Description non-blank", def.description.isNotBlank())
            assertTrue("Min bars > 0", def.minimumBars > 0)
        }
    }
}
