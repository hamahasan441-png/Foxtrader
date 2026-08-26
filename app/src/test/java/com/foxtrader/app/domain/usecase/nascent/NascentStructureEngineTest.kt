package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.usecase.nascent.model.StructurePointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NascentStructureEngineTest {
    private val engine = NascentStructureEngine()

    @Test
    fun `a confirmed high is stamped with the bar it became knowable on`() {
        val candles = NascentFixtures.SeriesBuilder(100.0)
            .leg(to = 105.0, bars = 6)
            .leg(to = 101.0, bars = 6)
            .build()

        val highs = engine.swings(candles, left = 2, right = 2)
            .filter { it.type == StructurePointType.HIGH }

        assertTrue("expected at least one confirmed high", highs.isNotEmpty())
        highs.forEach { high ->
            assertEquals(
                "a high with right-width 2 confirms exactly two bars later",
                high.pivotBarIndex + 2,
                high.confirmationBarIndex,
            )
        }
    }

    @Test
    fun `a confirmed low is stamped with the bar it became knowable on`() {
        val candles = NascentFixtures.SeriesBuilder(100.0)
            .leg(to = 96.0, bars = 6)
            .leg(to = 100.0, bars = 6)
            .build()

        val lows = engine.swings(candles, left = 2, right = 2)
            .filter { it.type == StructurePointType.LOW }

        assertTrue("expected at least one confirmed low", lows.isNotEmpty())
        lows.forEach { low ->
            assertEquals(low.pivotBarIndex + 2, low.confirmationBarIndex)
        }
    }

    /**
     * The core non-repaint property: a pivot must never be reported before the
     * bars that prove it exist.
     */
    @Test
    fun `no pivot is reported before its own confirmation bar`() {
        val candles = NascentFixtures.richSeries()
        val swings = engine.swings(candles, left = 2, right = 2)

        swings.forEach { swing ->
            assertTrue(
                "pivot ${swing.pivotBarIndex} confirmed at ${swing.confirmationBarIndex}",
                swing.confirmationBarIndex > swing.pivotBarIndex,
            )
        }
    }

    /**
     * Structure computed on a prefix must equal the full-series structure
     * filtered to that prefix. This is what makes replay and live agree, and it
     * is exactly what an end-relative lookback window would break.
     */
    @Test
    fun `structure on a prefix equals the full series filtered to that prefix`() {
        val full = NascentFixtures.richSeries()
        val fullSwings = engine.swings(full, left = 2, right = 2)
        val fullBreaks = engine.breaks(full, fullSwings)

        for (cut in listOf(60, 90, 120, 160)) {
            if (cut >= full.size) continue
            val prefix = full.subList(0, cut)
            val prefixSwings = engine.swings(prefix, left = 2, right = 2)
            val prefixBreaks = engine.breaks(prefix, prefixSwings)

            assertEquals(
                "swings disagree at cut $cut",
                fullSwings.filter { it.confirmationBarIndex < cut },
                prefixSwings,
            )
            assertEquals(
                "breaks disagree at cut $cut",
                fullBreaks.filter { it.confirmationIndex < cut },
                prefixBreaks,
            )
        }
    }

    @Test
    fun `breaks are never reported before the broken pivot confirmed`() {
        val candles = NascentFixtures.richSeries()
        val swings = engine.swings(candles, left = 2, right = 2)
        val breaks = engine.breaks(candles, swings)

        assertTrue("fixture should produce structural breaks", breaks.isNotEmpty())
        breaks.forEach { event ->
            assertTrue(
                "break at ${event.confirmationIndex} precedes its origin ${event.originIndex}",
                event.confirmationIndex > event.originIndex,
            )
        }
    }
}
