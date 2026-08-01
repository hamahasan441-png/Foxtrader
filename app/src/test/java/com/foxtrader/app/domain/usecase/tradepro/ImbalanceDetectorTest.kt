package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.OrderFlowBar
import com.foxtrader.app.domain.model.tradepro.OrderFlowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImbalanceDetectorTest {

    private val detector = ImbalanceDetector()

    private fun bar(i: Int, buy: Double, sell: Double) = OrderFlowBar(
        index = i, timestamp = i * 60_000L,
        open = 100.0, high = 101.0, low = 99.0, close = 100.0,
        buyVolume = buy, sellVolume = sell, source = OrderFlowSource.CANDLE_DERIVED,
    )

    @Test
    fun `buy-dominant bar is a bullish imbalance`() {
        val out = detector.detect(listOf(bar(0, buy = 90.0, sell = 10.0)), ratio = 3.0)
        assertEquals(1, out.size)
        assertEquals(Direction.BULLISH, out[0].direction)
        assertEquals(9.0, out[0].ratio, 1e-6)
    }

    @Test
    fun `sell-dominant bar is a bearish imbalance`() {
        val out = detector.detect(listOf(bar(0, buy = 10.0, sell = 90.0)), ratio = 3.0)
        assertEquals(Direction.BEARISH, out[0].direction)
    }

    @Test
    fun `balanced bar produces no imbalance`() {
        val out = detector.detect(listOf(bar(0, buy = 50.0, sell = 50.0)), ratio = 3.0)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `one-sided bar with zero opposing volume qualifies as infinite ratio`() {
        val out = detector.detect(listOf(bar(0, buy = 70.0, sell = 0.0)), ratio = 3.0)
        assertEquals(1, out.size)
        assertEquals(Direction.BULLISH, out[0].direction)
        assertTrue(out[0].ratio.isInfinite())
    }

    @Test
    fun `bars below min volume are ignored`() {
        val out = detector.detect(listOf(bar(0, buy = 9.0, sell = 1.0)), ratio = 3.0, minVolume = 50.0)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(detector.detect(emptyList(), ratio = 3.0).isEmpty())
    }
}
