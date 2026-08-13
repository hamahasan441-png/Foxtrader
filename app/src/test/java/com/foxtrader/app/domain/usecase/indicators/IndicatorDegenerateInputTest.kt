package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for degenerate indicator inputs.
 *
 * Indicator periods and bucket counts are not internal constants: they arrive
 * from the plugin SDK (`params["period"]?.toInt()`), from indicator settings,
 * and from strategy scripts. Before these guards a zero or negative period
 * either crashed the chart outright (ArrayIndexOutOfBounds /
 * NegativeArraySize / an empty `coerceIn` range) or silently produced NaN
 * values that propagated into signals, stop distances and position sizing.
 *
 * The contract asserted here: every indicator is **total** — for any period and
 * any candle series it returns a correctly-sized, all-finite result instead of
 * throwing or emitting NaN/Infinity.
 */
class IndicatorDegenerateInputTest {

    private fun candles(n: Int, close: (Int) -> Double = { 100.0 + it }): List<Candle> =
        (0 until n).map { i ->
            val c = close(i)
            Candle(
                timestamp = 1_700_000_000_000L + i * 60_000L,
                open = c - 0.5,
                high = c + 1.0,
                low = c - 1.0,
                close = c,
                volume = 100.0 + i,
            )
        }

    private val series = candles(200)

    /** Periods a user or script can realistically supply, including invalid ones. */
    private val degeneratePeriods = listOf(-10, -1, 0, 1)

    private fun assertAllFinite(label: String, values: DoubleArray) {
        val offending = values.withIndex().firstOrNull { !it.value.isFinite() }
        assertTrue(
            "$label produced a non-finite value at index ${offending?.index}: ${offending?.value}",
            offending == null,
        )
    }

    // ========================================================================
    // MOVING AVERAGES
    // ========================================================================

    @Test
    fun `EMA is total for non-positive periods`() {
        for (p in degeneratePeriods) {
            val result = TechnicalIndicators.calculateEMA(series, p)
            assertEquals("EMA size for period=$p", series.size, result.size)
            assertAllFinite("EMA(period=$p)", result)
        }
    }

    @Test
    fun `SMA is total for non-positive periods`() {
        // period <= 0 previously indexed candles[i - period] past the last
        // element and divided by zero.
        for (p in degeneratePeriods) {
            val result = TechnicalIndicators.calculateSMA(series, p)
            assertEquals("SMA size for period=$p", series.size, result.size)
            assertAllFinite("SMA(period=$p)", result)
        }
    }

    // ========================================================================
    // OSCILLATORS
    // ========================================================================

    @Test
    fun `RSI is total for non-positive periods and stays within 0-100`() {
        for (p in degeneratePeriods) {
            val result = TechnicalIndicators.calculateRSI(series, p)
            assertEquals("RSI size for period=$p", series.size, result.size)
            assertAllFinite("RSI(period=$p)", result)
            assertTrue(
                "RSI(period=$p) left the 0..100 range",
                result.all { it in 0.0..100.0 },
            )
        }
    }

    @Test
    fun `ADX is total for non-positive periods`() {
        // period <= 0 slipped past the `len < period * 2` guard and then seeded
        // the smoothing loop from tr[-1].
        for (p in degeneratePeriods) {
            val result = TechnicalIndicators.calculateADX(series, p)
            assertAllFinite("ADX(period=$p)", result.adx)
            assertAllFinite("plusDI(period=$p)", result.plusDI)
            assertAllFinite("minusDI(period=$p)", result.minusDI)
        }
    }

    @Test
    fun `MACD is total for non-positive signal periods`() {
        for (p in degeneratePeriods) {
            val result = TechnicalIndicators.calculateMACD(series, p, p * 2, p)
            assertAllFinite("MACD line(period=$p)", result.macd)
            assertAllFinite("MACD signal(period=$p)", result.signal)
            assertAllFinite("MACD histogram(period=$p)", result.histogram)
        }
    }

    @Test
    fun `Stochastic is total for non-positive periods`() {
        val stochastic = StochasticOscillator()
        for (p in degeneratePeriods) {
            val result = stochastic.calculate(series, p, p)
            assertAllFinite("%K(period=$p)", result.percentK)
            assertAllFinite("%D(period=$p)", result.percentD)
        }
    }

    // ========================================================================
    // VOLATILITY / TREND
    // ========================================================================

    @Test
    fun `ATR is total for non-positive periods`() {
        // ATR feeds stop-loss distance and ATR-based position sizing, so a NaN
        // here would silently size a real order.
        for (p in degeneratePeriods) {
            val result = TechnicalIndicators.calculateATR(series, p)
            assertEquals("ATR size for period=$p", series.size, result.size)
            assertAllFinite("ATR(period=$p)", result)
        }
    }

    @Test
    fun `SuperTrend is total for non-positive ATR periods`() {
        val superTrend = SuperTrend()
        for (p in degeneratePeriods) {
            val result = superTrend.calculate(series, p, 3.0)
            assertAllFinite("SuperTrend(period=$p)", result.values)
        }
    }

    @Test
    fun `Bollinger bands are total for non-positive periods`() {
        val bollinger = BollingerBands()
        for (p in degeneratePeriods) {
            val result = bollinger.calculate(series, p, 2.0)
            assertAllFinite("Bollinger middle(period=$p)", result.middle)
            assertAllFinite("Bollinger upper(period=$p)", result.upper)
            assertAllFinite("Bollinger lower(period=$p)", result.lower)
        }
    }

    @Test
    fun `Ichimoku is total for non-positive periods`() {
        val ichimoku = IchimokuCloud()
        for (p in degeneratePeriods) {
            val result = ichimoku.calculate(series, p, p, p, p)
            assertAllFinite("Tenkan(period=$p)", result.tenkan)
            assertAllFinite("Kijun(period=$p)", result.kijun)
            assertAllFinite("SenkouA(period=$p)", result.senkouA)
            assertAllFinite("SenkouB(period=$p)", result.senkouB)
        }
    }

    @Test
    fun `relative volume and momentum are total for non-positive periods`() {
        for (p in degeneratePeriods) {
            assertAllFinite("RelVol(period=$p)", TechnicalIndicators.calculateRelativeVolume(series, p))
            assertAllFinite("Momentum(period=$p)", TechnicalIndicators.calculateMomentum(series, p))
        }
    }

    // ========================================================================
    // VOLATILITY WITH MALFORMED PRICES
    // ========================================================================

    @Test
    fun `volatility returns zero instead of NaN when closes are zero`() {
        // A zero close (a malformed provider bar, or a halted/delisted symbol
        // padded with zeros) made the percentage return 0/0 = NaN, and one NaN
        // poisoned the mean and variance for the whole series.
        val zeroed = (0 until 50).map {
            Candle(1_700_000_000_000L + it * 60_000L, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val volatility = TechnicalIndicators.calculateVolatility(zeroed)
        assertTrue("Volatility must be finite, was $volatility", volatility.isFinite())
        assertEquals(0.0, volatility, 0.0)
    }

    @Test
    fun `volatility ignores zero-priced bars mixed into a real series`() {
        val mixed = candles(50).toMutableList().also {
            it[10] = it[10].copy(open = 0.0, high = 0.0, low = 0.0, close = 0.0)
        }
        val volatility = TechnicalIndicators.calculateVolatility(mixed)
        assertTrue("Volatility must be finite, was $volatility", volatility.isFinite())
    }

    // ========================================================================
    // PROFILE BUCKET COUNTS
    // ========================================================================

    @Test
    fun `volume profile survives a non-positive bucket count`() {
        // Zero/negative buckets previously threw NegativeArraySizeException, or
        // made coerceIn(0, buckets - 1) an empty-range IllegalArgumentException.
        val detector = SmcDetector()
        for (b in listOf(-10, -1, 0, 1)) {
            val profile = detector.computeVolumeProfile(series, b)
            assertTrue("Volume profile should yield at least one level for buckets=$b", profile.levels.isNotEmpty())
        }
    }

    @Test
    fun `market profile survives a non-positive row count`() {
        val profile = MarketProfile()
        for (rows in listOf(-10, -1, 0, 1)) {
            val result = profile.compute(series, rows)
            assertTrue("Market profile should yield at least one level for rows=$rows", result.levels.isNotEmpty())
        }
    }

    // ========================================================================
    // EMPTY / TINY SERIES
    // ========================================================================

    @Test
    fun `indicators tolerate empty and single-bar series`() {
        for (tiny in listOf(emptyList(), candles(1), candles(2))) {
            assertEquals(tiny.size, TechnicalIndicators.calculateEMA(tiny, 20).size)
            assertEquals(tiny.size, TechnicalIndicators.calculateSMA(tiny, 20).size)
            assertEquals(tiny.size, TechnicalIndicators.calculateRSI(tiny, 14).size)
            assertEquals(tiny.size, TechnicalIndicators.calculateATR(tiny, 14).size)
            assertEquals(tiny.size, TechnicalIndicators.calculateVWAP(tiny).size)
            assertTrue(TechnicalIndicators.calculateVolatility(tiny).isFinite())
            TechnicalIndicators.calculateADX(tiny, 14)
        }
    }
}
