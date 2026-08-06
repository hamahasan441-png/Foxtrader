package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AnchoredVwap] — the anchored VWAP engine with std-dev bands.
 * Pure math, no framework dependencies.
 */
class AnchoredVwapTest {

    private fun candle(o: Double, h: Double, l: Double, c: Double, v: Double, i: Int = 0) =
        Candle(1_700_000_000_000L + i * 60_000L, o, h, l, c, v)

    @Test
    fun `empty input yields empty arrays`() {
        val result = AnchoredVwap.calculate(emptyList(), anchorIndex = 0)
        assertEquals(0, result.vwap.size)
        assertEquals(0, result.upperBand.size)
        assertEquals(0, result.lowerBand.size)
    }

    @Test
    fun `bars before the anchor are NaN`() {
        val candles = (0 until 5).map { candle(10.0, 11.0, 9.0, 10.0, 100.0, it) }
        val result = AnchoredVwap.calculate(candles, anchorIndex = 2)
        assertEquals(2, result.anchorIndex)
        assertTrue(result.vwap[0].isNaN())
        assertTrue(result.vwap[1].isNaN())
        assertTrue(!result.vwap[2].isNaN())
        assertTrue(!result.vwap[4].isNaN())
    }

    @Test
    fun `first anchored value equals that bar's typical price with zero-width bands`() {
        val candles = listOf(candle(10.0, 12.0, 6.0, 9.0, 50.0, 0))
        val result = AnchoredVwap.calculate(candles, anchorIndex = 0)
        val typical = (12.0 + 6.0 + 9.0) / 3.0
        assertEquals(typical, result.vwap[0], 1e-9)
        // With a single bar the variance is zero, so bands collapse onto the VWAP.
        assertEquals(result.vwap[0], result.upperBand[0], 1e-9)
        assertEquals(result.vwap[0], result.lowerBand[0], 1e-9)
    }

    @Test
    fun `vwap is volume-weighted across bars`() {
        val candles = listOf(
            candle(9.0, 10.0, 8.0, 9.0, 2.0, 0),   // tp = 9.0, v = 2
            candle(11.0, 12.0, 10.0, 11.0, 4.0, 1), // tp = 11.0, v = 4
        )
        val result = AnchoredVwap.calculate(candles, anchorIndex = 0)
        // (9*2 + 11*4) / (2+4) = 62/6
        assertEquals(9.0, result.vwap[0], 1e-9)
        assertEquals(62.0 / 6.0, result.vwap[1], 1e-9)
    }

    @Test
    fun `bands are symmetric around the vwap`() {
        val candles = listOf(
            candle(9.0, 10.0, 8.0, 9.0, 2.0, 0),
            candle(11.0, 12.0, 10.0, 11.0, 4.0, 1),
            candle(10.0, 13.0, 9.0, 12.0, 3.0, 2),
        )
        val result = AnchoredVwap.calculate(candles, anchorIndex = 0)
        for (i in 1 until candles.size) {
            val up = result.upperBand[i] - result.vwap[i]
            val down = result.vwap[i] - result.lowerBand[i]
            assertEquals("band asymmetry at bar $i", up, down, 1e-9)
            assertTrue("upper must exceed vwap once variance is positive", up > 0.0)
        }
    }

    @Test
    fun `band multiplier scales the band distance linearly`() {
        val candles = listOf(
            candle(9.0, 10.0, 8.0, 9.0, 2.0, 0),
            candle(11.0, 12.0, 10.0, 11.0, 4.0, 1),
        )
        val oneSigma = AnchoredVwap.calculate(candles, anchorIndex = 0, bandMultiplier = 1.0)
        val twoSigma = AnchoredVwap.calculate(candles, anchorIndex = 0, bandMultiplier = 2.0)
        val d1 = oneSigma.upperBand[1] - oneSigma.vwap[1]
        val d2 = twoSigma.upperBand[1] - twoSigma.vwap[1]
        assertEquals(2.0 * d1, d2, 1e-9)
    }

    @Test
    fun `zero volume bars are weighted equally`() {
        val candles = listOf(
            candle(9.0, 10.0, 8.0, 9.0, 0.0, 0),   // tp 9, v treated as 1
            candle(11.0, 12.0, 10.0, 11.0, 0.0, 1), // tp 11, v treated as 1
        )
        val result = AnchoredVwap.calculate(candles, anchorIndex = 0)
        // Equal weights → simple mean of typical prices.
        assertEquals(10.0, result.vwap[1], 1e-9)
    }

    @Test
    fun `auto anchor picks the leg origin low in an uptrend`() {
        // Strictly rising: lowest low is bar 0, price ends near the top.
        val candles = (0 until 30).map { i ->
            val base = 100.0 + i
            candle(base, base + 0.5, base - 0.5, base + 0.3, 1000.0, i)
        }
        assertEquals(0, AnchoredVwap.autoAnchorIndex(candles))
    }

    @Test
    fun `auto anchor picks the leg origin high in a downtrend`() {
        // Strictly falling: highest high is bar 0, price ends near the bottom.
        val candles = (0 until 30).map { i ->
            val base = 200.0 - i
            candle(base, base + 0.5, base - 0.5, base - 0.3, 1000.0, i)
        }
        assertEquals(0, AnchoredVwap.autoAnchorIndex(candles))
    }
}
