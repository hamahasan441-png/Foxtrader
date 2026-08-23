package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorEdgeHardeningTest {

    @Test
    fun `bullish SuperTrend seed is placed on lower band`() {
        val candles = candles(30)
        val result = SuperTrend().calculate(candles, atrPeriod = 10, multiplier = 3.0)
        assertEquals(1, result.direction.first())
        assertEquals(result.finalLowerBands.first(), result.values.first(), 1e-12)
        assertTrue(result.values.first() <= candles.first().high)
    }

    @Test
    fun `MFI one-sided positive flow reaches exactly 100`() {
        val candles = candles(40)
        val mfi = VolumeIndicators().moneyFlowIndex(candles, 14)
        assertEquals(100.0, mfi.last(), 0.0)
    }

    @Test
    fun `MFI no-volume window is neutral`() {
        val candles = candles(40).map { it.copy(volume = 0.0) }
        val mfi = VolumeIndicators().moneyFlowIndex(candles, 14)
        assertEquals(50.0, mfi.last(), 0.0)
    }

    @Test
    fun `Donchian invalid period is sanitized instead of producing infinity`() {
        val result = ChannelIndicators().donchian(candles(10), period = 0)
        assertTrue(result.upper.all { it.isFinite() })
        assertTrue(result.lower.all { it.isFinite() })
        assertTrue(result.middle.all { it.isFinite() })
    }

    @Test
    fun `non-finite Bollinger multiplier cannot poison the series`() {
        val result = BollingerBands().calculate(candles(30), period = 10, multiplier = Double.NaN)
        assertTrue(result.upper.all { it.isFinite() })
        assertTrue(result.middle.all { it.isFinite() })
        assertTrue(result.lower.all { it.isFinite() })
    }

    private fun candles(count: Int): List<Candle> = (0 until count).map { i ->
        val price = 100.0 + i
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = price - 0.25,
            high = price + 1.0,
            low = price - 1.0,
            close = price + 0.25,
            volume = 1_000.0 + i,
        )
    }
}
