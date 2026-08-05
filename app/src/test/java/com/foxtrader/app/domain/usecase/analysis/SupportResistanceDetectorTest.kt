package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SupportResistanceDetector.
 * Validates swing-point clustering into S/R zones.
 */
class SupportResistanceDetectorTest {

    private lateinit var detector: SupportResistanceDetector

    @Before
    fun setup() {
        detector = SupportResistanceDetector()
    }

    private fun candle(
        open: Double, high: Double, low: Double, close: Double,
        volume: Double = 100.0, timestamp: Long = 0L,
    ) = Candle(timestamp, open, high, low, close, volume)

    /**
     * Builds a 30-bar sequence with repeated swing highs at ~105 and swing lows at ~95.
     * This should produce at least one support and one resistance zone.
     */
    private fun buildSwingSequence(): List<Candle> {
        val candles = mutableListOf<Candle>()
        // Pattern: rise to 105, fall to 95, repeat. With swingLookback=3 we get repeated highs and lows.
        for (i in 0 until 40) {
            val phase = i % 10
            val price = when {
                phase < 5 -> 95.0 + phase * 2.0 // 95 -> 97 -> 99 -> 101 -> 103
                else -> 105.0 - (phase - 5) * 2.0 // 105 -> 103 -> 101 -> 99 -> 97
            }
            val high = price + 1.0
            val low = price - 1.0
            candles.add(candle(price - 0.5, high, low, price + 0.5, timestamp = i * 60000L))
        }
        return candles
    }

    @Test
    fun `detect returns empty for insufficient data`() {
        // swingLookback=5 needs at least 11 candles
        val candles = (0 until 5).map { i ->
            candle(100.0, 101.0, 99.0, 100.5, timestamp = i * 60000L)
        }
        val zones = detector.detect(candles)
        assertTrue("Insufficient data should yield no zones", zones.isEmpty())
    }

    @Test
    fun `detect identifies zones with repeated swing levels`() {
        val candles = buildSwingSequence()
        val zones = detector.detect(candles, swingLookback = 3)
        assertTrue("Should detect at least one zone", zones.isNotEmpty())
        for (zone in zones) {
            assertTrue("Zone touches must be >= 2", zone.touches >= 2)
            assertTrue("Upper bound >= lower bound", zone.upperBound >= zone.lowerBound)
            assertTrue("Zone price is between bounds", zone.price in zone.lowerBound..zone.upperBound)
        }
    }

    @Test
    fun `zone strength is bounded 0 to 100`() {
        val candles = buildSwingSequence()
        val zones = detector.detect(candles, swingLookback = 3)
        assertTrue("Should have zones to validate", zones.isNotEmpty())
        for (zone in zones) {
            assertTrue("Strength in [0,100]: ${zone.strength}", zone.strength in 0.0..100.0)
        }
    }

    @Test
    fun `maxZones limits output size`() {
        val candles = buildSwingSequence()
        val zones = detector.detect(candles, swingLookback = 3, maxZones = 2)
        assertTrue("Output should not exceed maxZones", zones.size <= 2)
    }
}
