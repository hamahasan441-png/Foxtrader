package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Test

class TechnicalIndicatorsRsiParityTest {

    @Test
    fun `RSI is exactly 100 when Wilder average loss is zero`() {
        val candles = candles(count = 32) { i -> 100.0 + i }
        val rsi = TechnicalIndicators.calculateRSI(candles, 14)

        for (i in 14 until rsi.size) {
            assertEquals("strictly rising RSI at bar $i", 100.0, rsi[i], 0.0)
        }
    }

    @Test
    fun `RSI is exactly 0 when Wilder average gain is zero`() {
        val candles = candles(count = 32) { i -> 132.0 - i }
        val rsi = TechnicalIndicators.calculateRSI(candles, 14)

        for (i in 14 until rsi.size) {
            assertEquals("strictly falling RSI at bar $i", 0.0, rsi[i], 0.0)
        }
    }

    @Test
    fun `flat market stays neutral instead of producing non finite RSI`() {
        val candles = candles(count = 32) { 100.0 }
        val rsi = TechnicalIndicators.calculateRSI(candles, 14)

        for (value in rsi) {
            assertEquals(50.0, value, 0.0)
        }
    }

    @Test
    fun `RSI prefix is stable and cannot change from future candles`() {
        val candles = candles(count = 80) { i ->
            100.0 + i * 0.12 + kotlin.math.sin(i * 0.7) * 2.0
        }
        val full = TechnicalIndicators.calculateRSI(candles, 14)

        for (end in 14 until candles.size) {
            val prefix = TechnicalIndicators.calculateRSI(candles.subList(0, end + 1), 14)
            assertEquals("prefix RSI differs at bar $end", full[end], prefix[end], 1e-12)
        }
    }

    private fun candles(count: Int, closeAt: (Int) -> Double): List<Candle> =
        (0 until count).map { i ->
            val close = closeAt(i)
            Candle(
                timestamp = i * 60_000L,
                open = close,
                high = close + 0.5,
                low = close - 0.5,
                close = close,
                volume = 1_000.0,
            )
        }
}
