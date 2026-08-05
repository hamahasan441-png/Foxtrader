package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Displacement
import com.foxtrader.app.domain.model.PriceZoneKind
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the new LIT X SMC primitives (pure JVM). */
class LitXPrimitivesTest {

    private fun candle(i: Int, o: Double, h: Double, l: Double, c: Double, v: Double = 1000.0) =
        Candle(1_700_000_000_000L + i * 60_000L, o, h, l, c, v)

    // ---- DisplacementDetector ----

    @Test
    fun `detects a bullish displacement impulse`() {
        val detector = DisplacementDetector()
        val base = (0 until 24).map { candle(it, 1.1000, 1.1005, 1.0995, 1.1000) } // tiny bodies
        val impulse = candle(24, 1.1000, 1.1031, 1.0999, 1.1030) // body 0.0030 >> avg range
        val disp = detector.detectLatest(base + impulse)

        assertNotNull(disp)
        assertEquals(Direction.BULLISH, disp!!.direction)
        assertTrue("impulse should exceed the ATR multiple", disp.atrMultiple >= 1.2)
    }

    @Test
    fun `no displacement in a flat series`() {
        val detector = DisplacementDetector()
        val flat = (0 until 40).map { candle(it, 1.1000, 1.1005, 1.0995, 1.1000) }
        assertNull(detector.detectLatest(flat))
    }

    // ---- PremiumDiscountCalculator ----

    private fun rangeSeries(lastClose: Double): List<Candle> {
        val list = mutableListOf<Candle>()
        list += candle(0, 1.00, 1.001, 1.00, 1.00)     // range low = 1.00
        list += candle(1, 1.199, 1.20, 1.199, 1.199)   // range high = 1.20
        for (i in 2..48) list += candle(i, 1.10, 1.10, 1.10, 1.10)
        list += candle(49, lastClose, lastClose, lastClose, lastClose)
        return list
    }

    @Test
    fun `classifies premium discount and equilibrium`() {
        val calc = PremiumDiscountCalculator()
        assertEquals(PriceZoneKind.PREMIUM, calc.calculate(rangeSeries(1.18))!!.currentZone)
        assertEquals(PriceZoneKind.DISCOUNT, calc.calculate(rangeSeries(1.02))!!.currentZone)
        assertEquals(PriceZoneKind.EQUILIBRIUM, calc.calculate(rangeSeries(1.10))!!.currentZone)
    }

    // ---- MssClassifier ----

    @Test
    fun `upgrades CHOCH to MSS when displacement is aligned`() {
        val classifier = MssClassifier()
        val choch = StructureBreak(StructureBreakType.CHOCH, Direction.BULLISH, 1.10, 0L, 40, true)
        val alignedDisp = Displacement(Direction.BULLISH, 41, 41, 1.10, 1.13, 0.9, 1.5, true)

        val strong = classifier.classify(listOf(choch), alignedDisp)
        assertTrue(strong.present)
        assertEquals(StructureBreakType.MSS, strong.type)
        assertTrue(strong.isStrong)

        val weak = classifier.classify(listOf(choch), displacement = null)
        assertEquals(StructureBreakType.CHOCH, weak.type)
        assertFalse(weak.isStrong)

        val none = classifier.classify(emptyList(), null)
        assertFalse(none.present)
    }
}
