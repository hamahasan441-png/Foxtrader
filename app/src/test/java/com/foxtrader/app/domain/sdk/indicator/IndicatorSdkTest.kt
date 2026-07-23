package com.foxtrader.app.domain.sdk.indicator

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.builtin.BollingerIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.EmaIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.RsiIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorSdkTest {

    private fun candles(n: Int) = (0 until n).map { i ->
        Candle(i * 60_000L, 100.0 + i * 0.1, 101.0 + i * 0.1, 99.0 + i * 0.1, 100.5 + i * 0.1, 100.0)
    }

    @Test
    fun `EMA computes correct-length output`() {
        val result = EmaIndicator(20).compute(candles(100))
        assertNotNull(result.main)
        assertEquals(100, result.main!!.size)
    }

    @Test
    fun `EMA returns empty for insufficient data`() {
        val result = EmaIndicator(20).compute(candles(5))
        assertTrue(result.series.isEmpty())
    }

    @Test
    fun `Bollinger produces 3 series`() {
        val result = BollingerIndicator().compute(candles(50))
        assertEquals(3, result.series.size)
        assertTrue(result.series.containsKey("upper"))
        assertTrue(result.series.containsKey("middle"))
        assertTrue(result.series.containsKey("lower"))
    }

    @Test
    fun `RSI produces signals on overbought-oversold`() {
        // Build candles with a strong trend then reversal to trigger RSI extremes
        val trending = (0 until 80).map { i ->
            Candle(i * 60_000L, 100.0 + i * 0.5, 101.0 + i * 0.5, 99.5 + i * 0.5, 100.5 + i * 0.5, 100.0)
        }
        val result = RsiIndicator(14).compute(trending)
        assertNotNull(result.main)
        // Should have some overbought signals since it's a strong uptrend
        assertTrue(result.signals.any { it.type == SignalType.SELL })
    }

    @Test
    fun `IndicatorRegistry manages registrations`() {
        val reg = IndicatorRegistry()
        reg.register(EmaIndicator(20))
        reg.register(RsiIndicator(14))
        assertEquals(2, reg.size)
        assertEquals(1, reg.getOverlays().size)
        assertEquals(1, reg.getSubPanels().size)
        reg.unregister("ema_20")
        assertEquals(1, reg.size)
    }
}
