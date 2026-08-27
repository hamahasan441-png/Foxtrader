package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.mtf.MultiTimeframeSeries
import com.foxtrader.app.domain.usecase.virginwick.model.IfvgConfirmation
import com.foxtrader.app.domain.usecase.virginwick.model.TargetSource
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWick
import com.foxtrader.app.domain.usecase.virginwick.model.WickPoi
import com.foxtrader.app.domain.usecase.virginwick.model.WickSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detector, the activation rule and the risk geometry, each on its own. */
class VirginWickComponentTest {

    private val detector = VirginWickDetector()

    // ------------------------------------------------------------------
    // What makes a wick virgin
    // ------------------------------------------------------------------

    @Test
    fun `an untouched wick is virgin and a revisited one is not`() {
        // Bar 1 spikes down and closes back up, leaving a lower wick to 90.
        // Nothing afterwards trades below 95, so it is never tested.
        val untouched = context(
            listOf(
                VirginWickFixtures.bar(0, 100.0, 101.0, 99.0, 100.0),
                VirginWickFixtures.bar(1, 100.0, 101.0, 90.0, 100.5),
                // Everything after stays clear of the 90..100 region.
                VirginWickFixtures.bar(2, 100.6, 106.0, 100.6, 105.0),
                VirginWickFixtures.bar(3, 105.0, 110.0, 104.0, 109.0),
            ),
        )
        val virgin = detector.virginWicks(untouched, 3, VirginWickFixtures.testConfig())
        assertTrue(
            "the spike to 90 was never revisited",
            virgin.any { it.side == WickSide.LOWER && it.distal == 90.0 },
        )

        // The same series, but bar 3 trades back down into the wick region.
        val revisited = context(
            listOf(
                VirginWickFixtures.bar(0, 100.0, 101.0, 99.0, 100.0),
                VirginWickFixtures.bar(1, 100.0, 101.0, 90.0, 100.5),
                VirginWickFixtures.bar(2, 100.6, 106.0, 100.6, 105.0),
                VirginWickFixtures.bar(3, 105.0, 106.0, 95.0, 104.0),
            ),
        )
        assertTrue(
            "price traded back into the wick, so it is spent",
            detector.virginWicks(revisited, 3, VirginWickFixtures.testConfig())
                .none { it.side == WickSide.LOWER && it.distal == 90.0 },
        )
    }

    @Test
    fun `virginity is judged as of the bar being asked about`() {
        // This is what stops a historical arrow repainting: the wick is tested
        // at bar 4, but at bar 3 it had not been, and that must stay true.
        val series = context(
            listOf(
                VirginWickFixtures.bar(0, 100.0, 101.0, 99.0, 100.0),
                VirginWickFixtures.bar(1, 100.0, 101.0, 90.0, 100.5),
                VirginWickFixtures.bar(2, 100.6, 106.0, 100.6, 105.0),
                VirginWickFixtures.bar(3, 105.0, 110.0, 104.0, 109.0),
                VirginWickFixtures.bar(4, 109.0, 110.0, 92.0, 95.0),
            ),
        )
        fun virginAt(index: Int) = detector.virginWicks(series, index, VirginWickFixtures.testConfig())
            .any { it.side == WickSide.LOWER && it.distal == 90.0 }

        assertTrue("at bar 3 the wick was still untested", virginAt(3))
        assertTrue("by bar 4 price had come back for it", !virginAt(4))
    }

    @Test
    fun `the test mode decides how much of the wick price must take`() {
        // Price returns to 95 — inside the 90..100 wick but short of its extreme.
        val series = context(
            listOf(
                VirginWickFixtures.bar(0, 100.0, 101.0, 99.0, 100.0),
                VirginWickFixtures.bar(1, 100.0, 101.0, 90.0, 100.5),
                VirginWickFixtures.bar(2, 100.6, 106.0, 100.6, 105.0),
                // Back to 96: inside the 90..100 wick, past its 95 midpoint.
                VirginWickFixtures.bar(3, 105.0, 106.0, 96.0, 104.0),
            ),
        )
        fun survives(mode: WickTestMode) =
            detector.virginWicks(series, 3, VirginWickFixtures.testConfig(testMode = mode))
                .any { it.side == WickSide.LOWER && it.distal == 90.0 }

        assertTrue("any touch spends it", !survives(WickTestMode.ANY_TOUCH))
        assertTrue("the midpoint at 95 was not reached", survives(WickTestMode.MIDPOINT))
        assertTrue("the extreme at 90 was not reached", survives(WickTestMode.EXTREME))
    }

    @Test
    fun `a wick that is a rounding error on its own bar is not a level`() {
        val series = context(
            listOf(
                VirginWickFixtures.bar(0, 100.0, 101.0, 99.0, 100.0),
                // A ten-point body with a hundredth-of-a-point tail.
                VirginWickFixtures.bar(1, 100.0, 110.01, 99.99, 110.0),
                VirginWickFixtures.bar(2, 110.0, 116.0, 109.5, 115.0),
                VirginWickFixtures.bar(3, 115.0, 120.0, 114.0, 119.0),
            ),
        )
        assertTrue(
            "a tail this small is noise, not somewhere anyone was rejected",
            detector.virginWicks(series, 3, VirginWickFixtures.testConfig())
                .none { it.contextIndex == 1 },
        )
    }

    // ------------------------------------------------------------------
    // Activation
    // ------------------------------------------------------------------

    @Test
    fun `a lower wick becomes demand once the context closes above it`() {
        val series = context(
            listOf(
                VirginWickFixtures.bar(0, 100.0, 101.0, 99.0, 100.0),
                VirginWickFixtures.bar(1, 100.0, 101.0, 90.0, 100.5),
                VirginWickFixtures.bar(2, 100.6, 106.0, 100.6, 105.0),
                VirginWickFixtures.bar(3, 105.0, 110.0, 104.0, 109.0),
            ),
        )
        val config = VirginWickFixtures.testConfig()
        val wicks = detector.virginWicks(series, 3, config)
        val pois = detector.activate(wicks, series, 3, config)

        val demand = pois.firstOrNull { it.wick.distal == 90.0 }
        assertNotNull("the market closed away from the wick, so it is a zone", demand)
        assertEquals(Direction.BULLISH, demand?.direction)
    }

    @Test
    fun `an upper wick becomes supply and mirrors the demand case`() {
        val series = context(
            listOf(
                VirginWickFixtures.bar(0, 100.0, 101.0, 99.0, 100.0),
                VirginWickFixtures.bar(1, 100.0, 110.0, 99.0, 99.5),
                // Everything after stays clear of the 99.5..110 region.
                VirginWickFixtures.bar(2, 99.4, 99.4, 94.0, 95.0),
                VirginWickFixtures.bar(3, 95.0, 96.0, 90.0, 91.0),
            ),
        )
        val config = VirginWickFixtures.testConfig()
        val wicks = detector.virginWicks(series, 3, config)
        val supply = detector.activate(wicks, series, 3, config)
            .firstOrNull { it.wick.distal == 110.0 }

        assertNotNull(supply)
        assertEquals(Direction.BEARISH, supply?.direction)
    }

    @Test
    fun `requiring more closes never activates more zones`() {
        val execution = VirginWickFixtures.m1Walk(4_000, seed = 2)
        val series = MultiTimeframeSeries.from(execution, Timeframe.M1, Timeframe.H1)
        val last = series.candles.lastIndex

        val one = VirginWickFixtures.testConfig().copy(closesBeyondToActivate = 1)
        val three = VirginWickFixtures.testConfig().copy(closesBeyondToActivate = 3)
        val wicks = detector.virginWicks(series, last, one)

        val loose = detector.activate(wicks, series, last, one).size
        val strict = detector.activate(wicks, series, last, three).size
        assertTrue("the fixture must activate zones", loose > 0)
        assertTrue("demanding more closes admitted more zones", strict <= loose)
    }

    @Test
    fun `a zone is never knowable before the context bar that created it closed`() {
        val execution = VirginWickFixtures.m1Walk(4_000, seed = 2)
        val series = MultiTimeframeSeries.from(execution, Timeframe.M1, Timeframe.H1)
        val config = VirginWickFixtures.testConfig()
        val wicks = detector.virginWicks(series, series.candles.lastIndex, config)
        val pois = detector.activate(wicks, series, series.candles.lastIndex, config)

        assertTrue("fixture must produce zones", pois.isNotEmpty())
        pois.forEach {
            assertTrue(
                "the zone activated before its own wick existed",
                it.activatedAtIndex >= it.wick.knownFromIndex,
            )
            assertTrue("a zone activated past the series", it.activatedAtIndex <= execution.lastIndex)
        }
    }

    @Test
    fun `the live zone book is bounded and aged`() {
        val execution = VirginWickFixtures.m1Walk(4_000, seed = 2)
        val series = MultiTimeframeSeries.from(execution, Timeframe.M1, Timeframe.H1)
        val config = VirginWickFixtures.testConfig().copy(maxPoiAgeBars = 500, maxActivePoisPerSide = 2)
        val wicks = detector.virginWicks(series, series.candles.lastIndex, config)
        val pois = detector.activate(wicks, series, series.candles.lastIndex, config)

        val at = 3_500
        val active = detector.activeAt(pois, at, config)
        assertTrue(
            "the book is capped per side",
            active.count { it.wick.side == WickSide.LOWER } <= 2 &&
                active.count { it.wick.side == WickSide.UPPER } <= 2,
        )
        active.forEach {
            assertTrue("a zone from the future entered the book", it.activatedAtIndex <= at)
            assertTrue("a stale zone stayed live", at - it.activatedAtIndex <= 500)
        }
    }

    // ------------------------------------------------------------------
    // Risk
    // ------------------------------------------------------------------

    @Test
    fun `the stop takes the safer of the inversion and the entry bar`() {
        val poi = demandPoi(proximal = 100.0, distal = 90.0)
        val entryCandle = VirginWickFixtures.bar(10, 96.0, 99.0, 95.0, 98.0)
        // The inversion's low sits above the entry bar's low, so the entry bar
        // is the safer of the two and must be the one used.
        val ifvg = IfvgConfirmation(Direction.BULLISH, high = 98.0, low = 97.0, originIndex = 8, inversionIndex = 10)

        val geometry = VirginWickRiskEngine.build(
            direction = Direction.BULLISH,
            entry = 98.0,
            entryCandle = entryCandle,
            ifvg = ifvg,
            poi = poi,
            opposingWicks = emptyList(),
            config = VirginWickFixtures.testConfig().copy(stopBufferFraction = 0.0),
        )
        requireNotNull(geometry)
        assertTrue("the stop must clear the deepest candidate", geometry.stop <= 90.0)
    }

    @Test
    fun `an unreasonably distant draw is abandoned for the fixed multiple`() {
        val poi = demandPoi(proximal = 100.0, distal = 99.0)
        val entryCandle = VirginWickFixtures.bar(10, 99.5, 100.5, 99.2, 100.0)
        val far = VirginWick(
            side = WickSide.UPPER,
            timeframe = Timeframe.H1,
            proximal = 400.0,
            distal = 420.0,
            contextIndex = 0,
            knownFromIndex = 0,
            timestamp = 0L,
        )

        val geometry = VirginWickRiskEngine.build(
            direction = Direction.BULLISH,
            entry = 100.0,
            entryCandle = entryCandle,
            ifvg = null,
            poi = poi,
            opposingWicks = listOf(far),
            config = VirginWickFixtures.testConfig(),
        )
        requireNotNull(geometry)
        assertEquals(
            "a draw hundreds of R away is not a scalp target",
            TargetSource.FIXED_MULTIPLE,
            geometry.source,
        )
    }

    @Test
    fun `a draw within reach is preferred over the fixed multiple`() {
        val poi = demandPoi(proximal = 100.0, distal = 99.0)
        val entryCandle = VirginWickFixtures.bar(10, 99.5, 100.5, 99.2, 100.0)
        val near = VirginWick(
            side = WickSide.UPPER,
            timeframe = Timeframe.H1,
            proximal = 102.5,
            distal = 103.0,
            contextIndex = 0,
            knownFromIndex = 0,
            timestamp = 0L,
        )

        val geometry = VirginWickRiskEngine.build(
            direction = Direction.BULLISH,
            entry = 100.0,
            entryCandle = entryCandle,
            ifvg = null,
            poi = poi,
            opposingWicks = listOf(near),
            config = VirginWickFixtures.testConfig(),
        )
        requireNotNull(geometry)
        assertEquals(TargetSource.DRAW_ON_LIQUIDITY, geometry.source)
        assertEquals(102.5, geometry.target, 1e-9)
    }

    @Test
    fun `a setup that cannot reach the minimum reward is rejected`() {
        val poi = demandPoi(proximal = 100.0, distal = 90.0)
        val entryCandle = VirginWickFixtures.bar(10, 99.0, 100.0, 89.0, 99.5)
        val near = VirginWick(
            side = WickSide.UPPER,
            timeframe = Timeframe.H1,
            proximal = 100.2,
            distal = 100.4,
            contextIndex = 0,
            knownFromIndex = 0,
            timestamp = 0L,
        )
        assertNull(
            "a ten-point stop for a fractional target is not a trade",
            VirginWickRiskEngine.build(
                direction = Direction.BULLISH,
                entry = 99.5,
                entryCandle = entryCandle,
                ifvg = null,
                poi = poi,
                opposingWicks = listOf(near),
                config = VirginWickFixtures.testConfig().copy(
                    defaultRewardMultiple = 0.2,
                    minRewardMultiple = 1.5,
                ),
            ),
        )
    }

    @Test
    fun `bearish geometry is the mirror of bullish`() {
        val poi = WickPoi(
            wick = VirginWick(
                side = WickSide.UPPER,
                timeframe = Timeframe.H1,
                proximal = 100.0,
                distal = 110.0,
                contextIndex = 1,
                knownFromIndex = 0,
                timestamp = 0L,
            ),
            activatedAtIndex = 0,
            activatingCloses = 1,
        )
        val geometry = VirginWickRiskEngine.build(
            direction = Direction.BEARISH,
            entry = 102.0,
            entryCandle = VirginWickFixtures.bar(10, 102.0, 105.0, 101.0, 102.0),
            ifvg = null,
            poi = poi,
            opposingWicks = emptyList(),
            config = VirginWickFixtures.testConfig(),
        )
        requireNotNull(geometry)
        assertTrue("a short's stop sits above its entry", geometry.stop > geometry.entry)
        assertTrue("a short's target sits below its entry", geometry.target < geometry.entry)
    }

    // ------------------------------------------------------------------

    private fun context(bars: List<Candle>): MultiTimeframeSeries =
        // One execution bar per context bar keeps these unit cases readable:
        // the dating machinery has its own tests, and is not what is under test.
        MultiTimeframeSeries.from(
            expand(bars),
            Timeframe.M1,
            Timeframe.M5,
        )

    /** Blow each context bar up into five execution bars that reproduce it. */
    private fun expand(bars: List<Candle>): List<Candle> {
        val out = ArrayList<Candle>(bars.size * 5)
        bars.forEachIndexed { index, bar ->
            val base = index * 5
            out += VirginWickFixtures.bar(base, bar.open, bar.open, bar.open, bar.open)
            out += VirginWickFixtures.bar(base + 1, bar.open, bar.high, bar.open, bar.high)
            out += VirginWickFixtures.bar(base + 2, bar.high, bar.high, bar.low, bar.low)
            out += VirginWickFixtures.bar(base + 3, bar.low, bar.low, bar.low, bar.low)
            out += VirginWickFixtures.bar(base + 4, bar.low, bar.close, bar.low, bar.close)
        }
        return out
    }

    private fun demandPoi(proximal: Double, distal: Double) = WickPoi(
        wick = VirginWick(
            side = WickSide.LOWER,
            timeframe = Timeframe.H1,
            proximal = proximal,
            distal = distal,
            contextIndex = 1,
            knownFromIndex = 0,
            timestamp = 0L,
        ),
        activatedAtIndex = 0,
        activatingCloses = 1,
    )
}
