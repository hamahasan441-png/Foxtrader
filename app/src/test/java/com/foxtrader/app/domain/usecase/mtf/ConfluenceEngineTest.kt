package com.foxtrader.app.domain.usecase.mtf

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ConfluenceEngine]. The per-timeframe bias comes from the real
 * (pure) [AnalyzeMarketStructureUseCase], so rather than asserting an exact
 * bias for a fixture, these pin the parts that are fully deterministic: the
 * insufficient-data guard, the <50-bar filter, and the score/recommendation
 * aggregation invariants that must hold for any input.
 */
class ConfluenceEngineTest {

    private val engine = ConfluenceEngine(AnalyzeMarketStructureUseCase())

    @Test
    fun `empty input yields a neutral zero-score insufficient-data result`() {
        val result = engine.analyze(emptyMap())

        assertEquals(Bias.NEUTRAL, result.overallBias)
        assertEquals(0, result.confluenceScore)
        assertEquals(0, result.totalTimeframes)
        assertEquals(0, result.alignedTimeframes)
        assertEquals("Insufficient data", result.recommendation)
        assertTrue(result.analyses.isEmpty())
    }

    @Test
    fun `timeframes with fewer than 50 candles are excluded`() {
        val result = engine.analyze(
            mapOf(
                Timeframe.H1 to trending(40), // below the 50-bar minimum → skipped
                Timeframe.H4 to trending(90),
            ),
        )

        assertEquals(1, result.totalTimeframes)
        assertEquals(1, result.analyses.size)
        assertEquals(Timeframe.H4, result.analyses.first().timeframe)
    }

    @Test
    fun `all timeframes below the minimum degrade to insufficient data`() {
        val result = engine.analyze(mapOf(Timeframe.H1 to trending(30)))
        assertEquals(0, result.totalTimeframes)
        assertEquals("Insufficient data", result.recommendation)
    }

    @Test
    fun `score and recommendation invariants hold for any analyzed set`() {
        val result = engine.analyze(
            mapOf(
                Timeframe.H1 to trending(90),
                Timeframe.H4 to trending(90),
                Timeframe.D1 to trending(90),
            ),
            primaryDirection = Direction.BULLISH,
        )

        assertEquals(3, result.totalTimeframes)
        assertEquals(3, result.analyses.size)
        assertTrue(result.confluenceScore in 0..100)
        assertTrue(result.alignedTimeframes in 0..result.totalTimeframes)
        // confluenceScore is exactly aligned/total as a percentage.
        assertEquals(result.alignedTimeframes * 100 / result.totalTimeframes, result.confluenceScore)
        // Recommendation must match the band the engine assigns for its own score.
        assertEquals(expectedRecommendation(result.confluenceScore), result.recommendation)
    }

    @Test
    fun `alignment is measured against the requested primary direction`() {
        val data = mapOf(Timeframe.H1 to trending(90), Timeframe.H4 to trending(90))

        val bullish = engine.analyze(data, primaryDirection = Direction.BULLISH)
        val bearish = engine.analyze(data, primaryDirection = Direction.BEARISH)

        // Aligned counts are complementary against the same analyzed set:
        // every timeframe that is bullish-aligned is not bearish-aligned, and
        // NEUTRAL timeframes count for neither.
        assertTrue(bullish.alignedTimeframes + bearish.alignedTimeframes <= data.size)
        assertEquals(bullish.totalTimeframes, bearish.totalTimeframes)
    }

    private fun expectedRecommendation(score: Int): String = when {
        score >= 80 -> "Strong setup — all timeframes aligned"
        score >= 60 -> "Good setup — majority alignment"
        score >= 40 -> "Mixed signals — proceed with caution"
        else -> "Weak setup — wait for better alignment"
    }

    private fun trending(count: Int): List<Candle> = (0 until count).map { i ->
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
}
