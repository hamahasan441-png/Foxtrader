package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SmtDetector — correlated-pair divergence at swing highs/lows.
 */
class SmtDetectorTest {

    private lateinit var detector: SmtDetector

    @Before
    fun setup() {
        detector = SmtDetector()
    }

    /**
     * Build a series where recent swing highs make a higher high.
     * Each "peak" at [peakIndices] gets a high of [baseHigh + peakStep * ordinal].
     */
    private fun buildSeriesWithPeaks(
        length: Int,
        basePrice: Double,
        peakIndices: List<Int>,
        peakStep: Double,
    ): List<Candle> = (0 until length).map { i ->
        val peakOrdinal = peakIndices.indexOf(i)
        val high = if (peakOrdinal >= 0) {
            basePrice + 5.0 + peakStep * peakOrdinal
        } else {
            basePrice + 1.0
        }
        Candle(
            timestamp = i * 60_000L,
            open = basePrice,
            high = high,
            low = basePrice - 1.0,
            close = basePrice + 0.5,
            volume = 100.0,
        )
    }

    /**
     * Build a series where recent swing lows make a lower low.
     */
    private fun buildSeriesWithTroughs(
        length: Int,
        basePrice: Double,
        troughIndices: List<Int>,
        troughStep: Double,
    ): List<Candle> = (0 until length).map { i ->
        val troughOrdinal = troughIndices.indexOf(i)
        val low = if (troughOrdinal >= 0) {
            basePrice - 5.0 - troughStep * troughOrdinal
        } else {
            basePrice - 1.0
        }
        Candle(
            timestamp = i * 60_000L,
            open = basePrice,
            high = basePrice + 1.0,
            low = low,
            close = basePrice - 0.5,
            volume = 100.0,
        )
    }

    // ---------------------------------------------------------------- TESTS

    @Test
    fun `detects bearish SMT when primary makes higher high but correlated does not`() {
        // Primary: two swing highs, second is higher (higher high).
        val primary = buildSeriesWithPeaks(
            length = 40,
            basePrice = 100.0,
            peakIndices = listOf(10, 30), // first peak at 10, second at 30
            peakStep = 2.0,              // second peak is 2.0 higher
        )
        // Correlated: two swing highs, but second is LOWER (fails to confirm).
        val correlated = buildSeriesWithPeaks(
            length = 40,
            basePrice = 50.0,
            peakIndices = listOf(10, 30),
            peakStep = -1.0,             // second peak is LOWER -> divergence
        )

        val results = detector.detect(primary, correlated, leftBars = 3, rightBars = 3)

        assertTrue("Should detect bearish SMT", results.any { it.direction == Direction.BEARISH })
    }

    @Test
    fun `detects bullish SMT when primary makes lower low but correlated does not`() {
        // Primary: two swing lows, second is lower (lower low).
        val primary = buildSeriesWithTroughs(
            length = 40,
            basePrice = 100.0,
            troughIndices = listOf(10, 30),
            troughStep = 2.0,            // second trough is deeper
        )
        // Correlated: two swing lows, but second is HIGHER (fails to confirm).
        val correlated = buildSeriesWithTroughs(
            length = 40,
            basePrice = 50.0,
            troughIndices = listOf(10, 30),
            troughStep = -1.0,           // second trough is higher -> divergence
        )

        val results = detector.detect(primary, correlated, leftBars = 3, rightBars = 3)

        assertTrue("Should detect bullish SMT", results.any { it.direction == Direction.BULLISH })
    }

    @Test
    fun `no divergence when both instruments confirm`() {
        // Both make higher highs — no divergence.
        val primary = buildSeriesWithPeaks(40, 100.0, listOf(10, 30), 2.0)
        val correlated = buildSeriesWithPeaks(40, 50.0, listOf(10, 30), 2.0)

        val results = detector.detect(primary, correlated, leftBars = 3, rightBars = 3)

        assertTrue("Should not detect divergence when both confirm", results.isEmpty())
    }

    @Test
    fun `returns empty for insufficient data`() {
        val short = (0 until 5).map {
            Candle(it * 60_000L, 100.0, 101.0, 99.0, 100.5, 100.0)
        }
        assertTrue(detector.detect(short, short).isEmpty())
    }

    @Test
    fun `confidence is within bounds`() {
        val primary = buildSeriesWithPeaks(40, 100.0, listOf(10, 30), 2.0)
        val correlated = buildSeriesWithPeaks(40, 50.0, listOf(10, 30), -1.0)

        val results = detector.detect(primary, correlated, leftBars = 3, rightBars = 3)
        for (r in results) {
            assertTrue("Confidence in [30, 95]", r.confidence in 30.0..95.0)
        }
    }

    @Test
    fun `divergence price and index are valid`() {
        val primary = buildSeriesWithPeaks(40, 100.0, listOf(10, 30), 2.0)
        val correlated = buildSeriesWithPeaks(40, 50.0, listOf(10, 30), -1.0)

        val results = detector.detect(primary, correlated, leftBars = 3, rightBars = 3)
        for (r in results) {
            assertTrue("barIndex should be positive", r.barIndex > 0)
            assertTrue("price should be positive", r.price > 0)
        }
    }
}
