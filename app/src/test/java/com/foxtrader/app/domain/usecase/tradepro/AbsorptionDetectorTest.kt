package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.OrderFlowBar
import com.foxtrader.app.domain.model.tradepro.OrderFlowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsorptionDetectorTest {

    private val detector = AbsorptionDetector()

    private fun bar(
        i: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        buy: Double,
        sell: Double,
    ) = OrderFlowBar(i, i * 60_000L, open, high, low, close, buy, sell, OrderFlowSource.CANDLE_DERIVED)

    /** Five calm baseline bars (vol 100, range 2) then one heavy stalled bar. */
    private fun baseline(): MutableList<OrderFlowBar> {
        val list = ArrayList<OrderFlowBar>()
        for (i in 0 until 5) {
            list += bar(i, open = 100.0, high = 101.0, low = 99.0, close = 100.2, buy = 55.0, sell = 45.0)
        }
        return list
    }

    @Test
    fun `heavy volume with tiny progress flags absorption`() {
        val bars = baseline()
        // 3x volume, big range but net progress only 0.2 (10% of avg range), heavy buy dominance.
        bars += bar(5, open = 100.0, high = 103.0, low = 99.0, close = 100.2, buy = 250.0, sell = 50.0)
        val events = detector.detect(bars, lookback = 5)
        assertEquals(1, events.size)
        assertEquals(5, events[0].index)
        assertEquals(Direction.BULLISH, events[0].absorbedSide)
        assertTrue(events[0].strength > 0.0)
    }

    @Test
    fun `calm bars produce no absorption`() {
        val bars = baseline()
        bars += bar(5, open = 100.0, high = 101.0, low = 99.0, close = 100.8, buy = 55.0, sell = 45.0)
        assertTrue(detector.detect(bars, lookback = 5).isEmpty())
    }

    @Test
    fun `not enough bars yields no events`() {
        val bars = baseline() // only 5, lookback 5 -> nothing to scan
        assertTrue(detector.detect(bars, lookback = 5).isEmpty())
    }
}
