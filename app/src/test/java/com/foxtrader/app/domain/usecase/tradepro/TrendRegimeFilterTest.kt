package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendRegimeFilterTest {

    private val filter = TrendRegimeFilter()
    private val config = TradeProConfig()

    private fun candlesFromCloses(closes: List<Double>): List<Candle> =
        closes.mapIndexed { i, c -> Candle(i * 60_000L, c, c + 0.5, c - 0.5, c, 100.0) }

    @Test
    fun `efficiency ratio is 1 for a straight-line move and near 0 for chop`() {
        val trend = (0..40).map { it.toDouble() }            // +1 every bar => perfectly efficient
        assertEquals(1.0, filter.efficiencyRatio(trend, 30), 1e-6)

        val chop = (0..40).map { if (it % 2 == 0) 100.0 else 101.0 } // oscillates, no net progress
        assertTrue(filter.efficiencyRatio(chop, 30) < 0.1)
    }

    @Test
    fun `ema tracks a rising series upward`() {
        val e = filter.ema((0..50).map { it.toDouble() }, 10)
        assertEquals(51, e.size)
        assertTrue(e.last() > e[e.size - 11]) // slope positive
    }

    @Test
    fun `clean uptrend allows longs and blocks shorts`() {
        val up = candlesFromCloses((0..80).map { 5000.0 + it * 1.0 })
        assertTrue(filter.allows(up, Direction.BULLISH, config))
        assertFalse(filter.allows(up, Direction.BEARISH, config))
    }

    @Test
    fun `clean downtrend allows shorts and blocks longs`() {
        val down = candlesFromCloses((0..80).map { 5000.0 - it * 1.0 })
        assertTrue(filter.allows(down, Direction.BEARISH, config))
        assertFalse(filter.allows(down, Direction.BULLISH, config))
    }

    @Test
    fun `choppy market blocks both directions`() {
        val chop = candlesFromCloses((0..80).map { 5000.0 + if (it % 2 == 0) 0.0 else 1.0 })
        assertFalse(filter.allows(chop, Direction.BULLISH, config))
        assertFalse(filter.allows(chop, Direction.BEARISH, config))
    }

    @Test
    fun `disabled filter always allows`() {
        val chop = candlesFromCloses((0..80).map { 5000.0 + if (it % 2 == 0) 0.0 else 1.0 })
        val off = config.copy(useTrendFilter = false)
        assertTrue(filter.allows(chop, Direction.BULLISH, off))
        assertTrue(filter.allows(chop, Direction.BEARISH, off))
    }
}
