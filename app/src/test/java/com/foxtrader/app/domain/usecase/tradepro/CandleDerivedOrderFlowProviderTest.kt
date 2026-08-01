package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.tradepro.OrderFlowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleDerivedOrderFlowProviderTest {

    private val provider = CandleDerivedOrderFlowProvider()

    private fun candle(open: Double, high: Double, low: Double, close: Double, vol: Double) =
        Candle(timestamp = 1_000L, open = open, high = high, low = low, close = close, volume = vol)

    @Test
    fun `close at high routes volume to buyers`() {
        val bars = provider.toOrderFlow(listOf(candle(10.0, 12.0, 10.0, 12.0, 100.0)))
        assertEquals(1, bars.size)
        assertEquals(100.0, bars[0].buyVolume, 1e-6)
        assertEquals(0.0, bars[0].sellVolume, 1e-6)
        assertEquals(100.0, bars[0].delta, 1e-6)
        assertEquals(OrderFlowSource.CANDLE_DERIVED, bars[0].source)
    }

    @Test
    fun `close at low routes volume to sellers`() {
        val bars = provider.toOrderFlow(listOf(candle(12.0, 12.0, 10.0, 10.0, 100.0)))
        assertEquals(0.0, bars[0].buyVolume, 1e-6)
        assertEquals(100.0, bars[0].sellVolume, 1e-6)
        assertEquals(-100.0, bars[0].delta, 1e-6)
    }

    @Test
    fun `zero-range bar splits evenly and never divides by zero`() {
        val bars = provider.toOrderFlow(listOf(candle(10.0, 10.0, 10.0, 10.0, 80.0)))
        assertEquals(40.0, bars[0].buyVolume, 1e-6)
        assertEquals(40.0, bars[0].sellVolume, 1e-6)
        assertEquals(0.0, bars[0].dominance, 1e-6)
    }

    @Test
    fun `non-finite or negative volume is treated as zero`() {
        val bars = provider.toOrderFlow(
            listOf(
                candle(10.0, 11.0, 9.0, 10.5, Double.NaN),
                candle(10.0, 11.0, 9.0, 10.5, -5.0),
            ),
        )
        assertEquals(0.0, bars[0].totalVolume, 1e-6)
        assertEquals(0.0, bars[1].totalVolume, 1e-6)
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(provider.toOrderFlow(emptyList()).isEmpty())
    }

    @Test
    fun `bar index and timestamp are preserved`() {
        val bars = provider.toOrderFlow((0 until 3).map { candle(10.0, 11.0, 9.0, 10.5, 50.0) })
        assertEquals(0, bars[0].index)
        assertEquals(2, bars[2].index)
    }
}
