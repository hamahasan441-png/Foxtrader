package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.rsireversal.model.PivotSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RSI Orderflow candle engine (§37) and non-repaint pivot engine (§5.1, §6).
 */
class RsiReversalComponentTest {

    // ------------------------------------------------------------------
    // §37 — RSI candles
    // ------------------------------------------------------------------

    @Test
    fun `rsi candles stay in range and are well formed after warmup`() {
        val candles = RsiReversalFixtures.randomWalk(500)
        val rsi = RsiCandleEngine.calculate(candles, rsiLength = 14)

        assertEquals(candles.size, rsi.size)
        rsi.forEach { candle ->
            assertTrue("RSI must stay within 0..100", candle.high <= 100.0 && candle.low >= 0.0)
            assertTrue("high must contain the body", candle.high >= maxOf(candle.open, candle.close))
            assertTrue("low must contain the body", candle.low <= minOf(candle.open, candle.close))
            assertTrue("no NaN may escape", candle.open.isFinite() && candle.close.isFinite())
        }
    }

    @Test
    fun `rsi candle timestamps track the source bars exactly`() {
        val candles = RsiReversalFixtures.randomWalk(120)
        val rsi = RsiCandleEngine.calculate(candles, rsiLength = 14)
        candles.indices.forEach { assertEquals(candles[it].timestamp, rsi[it].timestamp) }
    }

    @Test
    fun `rsi candles are a pure function of the prefix`() {
        // Recomputing over a truncated series must reproduce the same candles,
        // which is what lets replay and batch calculation agree.
        val candles = RsiReversalFixtures.randomWalk(400)
        val full = RsiCandleEngine.calculate(candles, rsiLength = 14)
        val prefix = RsiCandleEngine.calculate(candles.take(250), rsiLength = 14)

        prefix.indices.forEach { i ->
            assertEquals(full[i].open, prefix[i].open, 1e-12)
            assertEquals(full[i].high, prefix[i].high, 1e-12)
            assertEquals(full[i].low, prefix[i].low, 1e-12)
            assertEquals(full[i].close, prefix[i].close, 1e-12)
        }
    }

    @Test
    fun `the close-only rsi overload matches the series overload`() {
        val candles = RsiReversalFixtures.randomWalk(300)
        val viaCandles = TechnicalIndicators.calculateRSI(candles, 14)
        val viaSeries = TechnicalIndicators.calculateRsiSeries(
            DoubleArray(candles.size) { candles[it].close },
            14,
        )
        assertEquals(viaCandles.size, viaSeries.size)
        viaCandles.indices.forEach { assertEquals(viaCandles[it], viaSeries[it], 1e-12) }
    }

    @Test
    fun `rsi candle engine survives degenerate input`() {
        assertTrue(RsiCandleEngine.calculate(emptyList(), 14).isEmpty())
        assertEquals(1, RsiCandleEngine.calculate(RsiReversalFixtures.randomWalk(1), 14).size)
        // Fewer bars than the RSI period: defined, neutral, and not NaN.
        val short = RsiCandleEngine.calculate(RsiReversalFixtures.randomWalk(5), 14)
        assertTrue(short.all { it.open.isFinite() && it.high.isFinite() && it.low.isFinite() })
    }

    @Test
    fun `a flat series produces a neutral rsi rather than NaN`() {
        val flat = (0 until 60).map { index ->
            Candle(
                timestamp = index * RsiReversalFixtures.BAR_MILLIS,
                open = 1.1, high = 1.1, low = 1.1, close = 1.1, volume = 1.0,
            )
        }
        val rsi = RsiCandleEngine.calculate(flat, 14)
        assertTrue(rsi.all { it.close == 50.0 })
    }

    // ------------------------------------------------------------------
    // §5.1 / §6 — pivots
    // ------------------------------------------------------------------

    @Test
    fun `a pivot is never emitted before its right side exists`() {
        val path = RsiReversalFixtures.zigzag(listOf(1.10, 1.09, 1.11, 1.08), barsBetween = 5)
        val candles = RsiReversalFixtures.priceCandles(path)
        val pivots = detect(candles, left = 2, right = 2)

        pivots.forEach { pivot ->
            assertEquals("confirmation lags the pivot by the right side", pivot.index + 2, pivot.confirmedIndex)
            assertTrue("a pivot may not confirm past the series", pivot.confirmedIndex < candles.size)
        }
    }

    @Test
    fun `confirmed pivots never move when bars are appended`() {
        val candles = RsiReversalFixtures.randomWalk(600)
        val early = detect(candles.take(400), left = 2, right = 2)
        val late = detect(candles, left = 2, right = 2)

        // Every pivot knowable at bar 400 must survive unchanged at bar 600.
        val stillValid = early.filter { it.confirmedIndex < 400 - 2 }
        assertTrue("fixture must actually produce pivots", stillValid.isNotEmpty())
        stillValid.forEach { pivot ->
            val match = late.firstOrNull { it.index == pivot.index && it.isHigh == pivot.isHigh }
            assertEquals("pivot $pivot disappeared or moved", pivot.value, match?.value)
        }
    }

    @Test
    fun `an equal-level plateau resolves to a single pivot`() {
        // Three bars share the same low; only one pivot may be emitted for it.
        val lows = doubleArrayOf(
            1.1000, 1.0990, 1.0980, 1.0970,
            1.0960, 1.0960, 1.0960,
            1.0970, 1.0980, 1.0990, 1.1000,
        )
        val candles = RsiReversalFixtures.priceCandles(lows)
        val plateau = detect(candles, left = 2, right = 2).filter { !it.isHigh }
        assertEquals("exactly one pivot for the plateau", 1, plateau.size)
        assertEquals("resolved to the last bar of the plateau", 6, plateau.single().index)
    }

    @Test
    fun `pivot detection is empty when the series is shorter than the window`() {
        val candles = RsiReversalFixtures.randomWalk(4)
        assertTrue(detect(candles, left = 2, right = 2).isEmpty())
    }

    @Test
    fun `price and rsi structure are detected independently`() {
        // The whole strategy depends on these two disagreeing, so a shared
        // engine must not accidentally couple them.
        val pricePath = RsiReversalFixtures.zigzag(listOf(1.10, 1.09, 1.11, 1.08))
        val rsiPath = RsiReversalFixtures.zigzag(listOf(40.0, 30.0, 60.0, 45.0))
        val candles = RsiReversalFixtures.priceCandles(pricePath)
        val rsiCandles = RsiReversalFixtures.rsiCandlesFrom(rsiPath, candles)

        val priceLows = detect(candles, 2, 2).filter { !it.isHigh }.map { it.value }
        val rsiLows = RsiReversalPivotEngine.detect(
            series = PivotSeries.RSI,
            size = rsiCandles.size,
            left = 2,
            right = 2,
            highAt = { rsiCandles[it].high },
            lowAt = { rsiCandles[it].low },
            timestampAt = { rsiCandles[it].timestamp },
        ).filter { !it.isHigh }.map { it.value }

        assertNotEquals("the two series must not produce the same extremes", priceLows, rsiLows)
        assertTrue(priceLows.isNotEmpty() && rsiLows.isNotEmpty())
    }

    private fun detect(candles: List<Candle>, left: Int, right: Int) =
        RsiReversalPivotEngine.detect(
            series = PivotSeries.PRICE,
            size = candles.size,
            left = left,
            right = right,
            highAt = { candles[it].high },
            lowAt = { candles[it].low },
            timestampAt = { candles[it].timestamp },
        )
}
