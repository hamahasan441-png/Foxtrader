package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.MarketStructure
import com.foxtrader.app.domain.model.SwingPoint
import com.foxtrader.app.domain.model.SwingType
import com.foxtrader.app.domain.model.tradepro.FlipZoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipZoneEngineTest {

    private val engine = FlipZoneEngine()

    private fun low(price: Double, index: Int) = SwingPoint(SwingType.LOW, price, index * 60_000L, index)
    private fun high(price: Double, index: Int) = SwingPoint(SwingType.HIGH, price, index * 60_000L, index)

    @Test
    fun `bullish structure anchors on the last higher-low`() {
        val structure = MarketStructure(
            bias = Bias.BULLISH,
            swingHighs = listOf(high(120.0, 2), high(125.0, 4)),
            swingLows = listOf(low(100.0, 1), low(105.0, 3), low(110.0, 5)),
            breaks = emptyList(),
        )
        val fz = engine.compute(structure)!!
        assertEquals(110.0, fz.price, 1e-6)
        assertEquals(FlipZoneKind.LAST_HIGHER_LOW, fz.kind)
        assertEquals(Bias.BULLISH, fz.bias)
        assertEquals(5, fz.anchorIndex)
        assertTrue(fz.allowsLong(115.0))
    }

    @Test
    fun `bearish structure anchors on the last lower-high`() {
        val structure = MarketStructure(
            bias = Bias.BEARISH,
            swingHighs = listOf(high(120.0, 1), high(115.0, 3), high(110.0, 5)),
            swingLows = listOf(low(100.0, 2), low(95.0, 4)),
            breaks = emptyList(),
        )
        val fz = engine.compute(structure)!!
        assertEquals(110.0, fz.price, 1e-6)
        assertEquals(FlipZoneKind.LAST_LOWER_HIGH, fz.kind)
        assertTrue(fz.allowsShort(105.0))
    }

    @Test
    fun `neutral structure has no flip zone`() {
        val structure = MarketStructure(Bias.NEUTRAL, emptyList(), emptyList(), emptyList())
        assertNull(engine.compute(structure))
    }

    @Test
    fun `bullish with no higher-low falls back to the most recent low`() {
        val structure = MarketStructure(
            bias = Bias.BULLISH,
            swingHighs = emptyList(),
            swingLows = listOf(low(110.0, 1), low(105.0, 3), low(100.0, 5)),
            breaks = emptyList(),
        )
        val fz = engine.compute(structure)!!
        assertEquals(100.0, fz.price, 1e-6)
    }
}
