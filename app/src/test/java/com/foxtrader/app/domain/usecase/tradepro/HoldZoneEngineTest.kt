package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.Imbalance
import com.foxtrader.app.domain.model.tradepro.OrderFlowBar
import com.foxtrader.app.domain.model.tradepro.OrderFlowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldZoneEngineTest {

    private val engine = HoldZoneEngine()

    private fun bar(i: Int, high: Double, low: Double, open: Double, close: Double) = OrderFlowBar(
        index = i, timestamp = i * 60_000L,
        open = open, high = high, low = low, close = close,
        buyVolume = 60.0, sellVolume = 40.0, source = OrderFlowSource.CANDLE_DERIVED,
    )

    private fun imb(i: Int, direction: Direction) =
        Imbalance(index = i, timestamp = i * 60_000L, direction = direction, ratio = 6.0, price = 100.0, volume = 100.0)

    @Test
    fun `stacked same-direction imbalances form a buy-hold zone`() {
        val bars = listOf(
            bar(0, high = 100.0, low = 98.0, open = 99.0, close = 99.5),
            bar(1, high = 105.0, low = 100.0, open = 100.5, close = 104.0),
            bar(2, high = 106.0, low = 101.0, open = 101.5, close = 105.0),
            bar(3, high = 107.0, low = 104.0, open = 104.5, close = 106.0),
            bar(4, high = 104.0, low = 101.0, open = 101.0, close = 103.0), // retest into zone, bullish reaction
        )
        val imbalances = listOf(imb(1, Direction.BULLISH), imb(2, Direction.BULLISH))
        val zones = engine.build(bars, imbalances, minStack = 2, maxGap = 1)
        assertEquals(1, zones.size)
        val z = zones[0]
        assertEquals(HoldZoneType.BUY_HOLD, z.type)
        assertEquals(106.0, z.high, 1e-6)
        assertEquals(100.0, z.low, 1e-6)
        assertEquals(2, z.stackedCount)
        assertTrue(z.defended)
    }

    @Test
    fun `a single imbalance does not form a zone`() {
        val bars = listOf(bar(0, 100.0, 98.0, 99.0, 99.5), bar(1, 105.0, 100.0, 100.5, 104.0))
        val zones = engine.build(bars, listOf(imb(1, Direction.BULLISH)), minStack = 2, maxGap = 1)
        assertTrue(zones.isEmpty())
    }

    @Test
    fun `opposite-direction imbalances are not grouped together`() {
        val bars = (0..3).map { bar(it, 105.0, 100.0, 101.0, 104.0) }
        val imbalances = listOf(imb(1, Direction.BULLISH), imb(2, Direction.BEARISH))
        val zones = engine.build(bars, imbalances, minStack = 2, maxGap = 1)
        assertTrue(zones.isEmpty())
    }

    @Test
    fun `empty inputs are safe`() {
        assertTrue(engine.build(emptyList(), emptyList()).isEmpty())
    }
}
