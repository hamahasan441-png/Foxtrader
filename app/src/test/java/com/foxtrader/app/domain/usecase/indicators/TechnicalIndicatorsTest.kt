package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for TechnicalIndicators.
 *
 * Validates correctness of EMA, SMA, RSI, MACD, ATR, ADX, VWAP,
 * Momentum, Relative Volume, and Volatility calculations.
 *
 * All assertions use a tolerance of 1e-4 to account for floating-point precision.
 */
class TechnicalIndicatorsTest {

    private val tolerance = 1e-4

    // ========================================================================
    // TEST DATA
    // ========================================================================

    private fun buildCandles(prices: List<Double>, volume: Double = 100.0): List<Candle> =
        prices.mapIndexed { i, close ->
            Candle(
                timestamp = 1_000_000L + i * 60_000L,
                open = close - 0.5,
                high = close + 1.0,
                low = close - 1.0,
                close = close,
                volume = volume,
            )
        }

    private val trendingUp = buildCandles(
        (1..50).map { 100.0 + it * 0.5 } // 100.5, 101.0, 101.5, ... 125.0
    )

    private val trendingDown = buildCandles(
        (1..50).map { 125.0 - it * 0.5 } // 124.5, 124.0, 123.5, ... 100.0
    )

    private val flatMarket = buildCandles(
        (1..50).map { 100.0 + (it % 3 - 1) * 0.1 } // oscillates around 100
    )

    // ========================================================================
    // EMA TESTS
    // ========================================================================

    @Test
    fun `EMA returns array of same size as input`() {
        val ema = TechnicalIndicators.calculateEMA(trendingUp, 20)
        assertEquals(trendingUp.size, ema.size)
    }

    @Test
    fun `EMA first value equals first close price`() {
        val ema = TechnicalIndicators.calculateEMA(trendingUp, 20)
        assertEquals(trendingUp[0].close, ema[0], tolerance)
    }

    @Test
    fun `EMA lags behind in uptrend — EMA below close`() {
        val ema = TechnicalIndicators.calculateEMA(trendingUp, 20)
        // After warmup, EMA should lag (be below close in uptrend)
        for (i in 25 until trendingUp.size) {
            assertTrue("EMA[$i] should be below close in uptrend",
                ema[i] < trendingUp[i].close)
        }
    }

    @Test
    fun `EMA leads above in downtrend — EMA above close`() {
        val ema = TechnicalIndicators.calculateEMA(trendingDown, 20)
        for (i in 25 until trendingDown.size) {
            assertTrue("EMA[$i] should be above close in downtrend",
                ema[i] > trendingDown[i].close)
        }
    }

    @Test
    fun `shorter EMA reacts faster than longer EMA`() {
        val ema10 = TechnicalIndicators.calculateEMA(trendingUp, 10)
        val ema50 = TechnicalIndicators.calculateEMA(trendingUp, 50)
        // In uptrend, shorter EMA should be closer to price (higher)
        val last = trendingUp.lastIndex
        assertTrue(ema10[last] > ema50[last])
    }

    @Test
    fun `EMA empty input returns empty array`() {
        val ema = TechnicalIndicators.calculateEMA(emptyList(), 20)
        assertEquals(0, ema.size)
    }

    // ========================================================================
    // SMA TESTS
    // ========================================================================

    @Test
    fun `SMA returns array of same size as input`() {
        val sma = TechnicalIndicators.calculateSMA(trendingUp, 20)
        assertEquals(trendingUp.size, sma.size)
    }

    @Test
    fun `SMA at period-1 equals average of first N prices`() {
        val period = 5
        val sma = TechnicalIndicators.calculateSMA(trendingUp, period)
        val expectedAvg = trendingUp.subList(0, period).map { it.close }.average()
        assertEquals(expectedAvg, sma[period - 1], tolerance)
    }

    // ========================================================================
    // RSI TESTS
    // ========================================================================

    @Test
    fun `RSI in strong uptrend is above 60`() {
        val rsi = TechnicalIndicators.calculateRSI(trendingUp, 14)
        // After warmup period, RSI should be high
        for (i in 20 until trendingUp.size) {
            assertTrue("RSI[$i]=${rsi[i]} should be > 60 in uptrend", rsi[i] > 60.0)
        }
    }

    @Test
    fun `RSI in strong downtrend is below 40`() {
        val rsi = TechnicalIndicators.calculateRSI(trendingDown, 14)
        for (i in 20 until trendingDown.size) {
            assertTrue("RSI[$i]=${rsi[i]} should be < 40 in downtrend", rsi[i] < 40.0)
        }
    }

    @Test
    fun `RSI range is 0 to 100`() {
        val rsi = TechnicalIndicators.calculateRSI(trendingUp, 14)
        for (value in rsi) {
            assertTrue("RSI should be in [0,100] but was $value", value in 0.0..100.0)
        }
    }

    @Test
    fun `RSI default value is 50 for insufficient data`() {
        val shortData = buildCandles(listOf(100.0, 101.0, 102.0))
        val rsi = TechnicalIndicators.calculateRSI(shortData, 14)
        assertEquals(50.0, rsi[0], tolerance)
    }

    // ========================================================================
    // MACD TESTS
    // ========================================================================

    @Test
    fun `MACD line positive in uptrend`() {
        val result = TechnicalIndicators.calculateMACD(trendingUp)
        // After warmup, MACD should be positive in uptrend (fast > slow)
        assertTrue(result.macd.last() > 0)
    }

    @Test
    fun `MACD line negative in downtrend`() {
        val result = TechnicalIndicators.calculateMACD(trendingDown)
        assertTrue(result.macd.last() < 0)
    }

    @Test
    fun `MACD histogram is difference of MACD and signal`() {
        val result = TechnicalIndicators.calculateMACD(trendingUp)
        val last = trendingUp.lastIndex
        assertEquals(
            result.macd[last] - result.signal[last],
            result.histogram[last],
            tolerance,
        )
    }

    // ========================================================================
    // ATR TESTS
    // ========================================================================

    @Test
    fun `ATR is always positive`() {
        val atr = TechnicalIndicators.calculateATR(trendingUp, 14)
        for (i in 14 until atr.size) {
            assertTrue("ATR[$i] should be > 0", atr[i] > 0.0)
        }
    }

    @Test
    fun `ATR reflects candle range`() {
        // Our test candles have range = high - low = 2.0 (close+1 - (close-1))
        val atr = TechnicalIndicators.calculateATR(flatMarket, 14)
        // ATR should be approximately 2.0 (the consistent range of our test candles)
        assertEquals(2.0, atr.last(), 0.5)
    }

    // ========================================================================
    // ADX TESTS
    // ========================================================================

    @Test
    fun `ADX high in trending market`() {
        val result = TechnicalIndicators.calculateADX(trendingUp, 14)
        // ADX > 25 indicates a trend
        val last = trendingUp.lastIndex
        assertTrue("ADX should be > 20 in trend, was ${result.adx[last]}", result.adx[last] > 20.0)
    }

    @Test
    fun `ADX plusDI greater than minusDI in uptrend`() {
        val result = TechnicalIndicators.calculateADX(trendingUp, 14)
        val last = trendingUp.lastIndex
        assertTrue("+DI should be > -DI in uptrend", result.plusDI[last] > result.minusDI[last])
    }

    // ========================================================================
    // VWAP TESTS
    // ========================================================================

    @Test
    fun `VWAP returns array of correct size`() {
        val vwap = TechnicalIndicators.calculateVWAP(trendingUp)
        assertEquals(trendingUp.size, vwap.size)
    }

    @Test
    fun `VWAP first value equals first typical price`() {
        val vwap = TechnicalIndicators.calculateVWAP(trendingUp)
        val c = trendingUp[0]
        val tp = (c.high + c.low + c.close) / 3.0
        assertEquals(tp, vwap[0], tolerance)
    }

    // ========================================================================
    // MOMENTUM TESTS
    // ========================================================================

    @Test
    fun `Momentum positive in uptrend`() {
        val mom = TechnicalIndicators.calculateMomentum(trendingUp, 10)
        assertTrue(mom.last() > 0)
    }

    @Test
    fun `Momentum negative in downtrend`() {
        val mom = TechnicalIndicators.calculateMomentum(trendingDown, 10)
        assertTrue(mom.last() < 0)
    }

    // ========================================================================
    // VOLATILITY TESTS
    // ========================================================================

    @Test
    fun `Volatility is positive for any moving market`() {
        val vol = TechnicalIndicators.calculateVolatility(trendingUp)
        assertTrue(vol > 0.0)
    }

    @Test
    fun `Volatility returns 0 for insufficient data`() {
        val single = buildCandles(listOf(100.0))
        assertEquals(0.0, TechnicalIndicators.calculateVolatility(single), tolerance)
    }
}
