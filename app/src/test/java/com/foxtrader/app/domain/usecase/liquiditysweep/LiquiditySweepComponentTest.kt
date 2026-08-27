package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquidityLevel
import com.foxtrader.app.domain.usecase.mtf.MultiTimeframeSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-timeframe substrate and the four detectors, each on its own.
 */
class LiquiditySweepComponentTest {

    private val levelEngine = LiquidityLevelEngine()
    private val sweepDetector = LiquiditySweepDetector()
    private val entryEngine = LiquiditySweepEntryEngine()
    private val biasEngine = LiquiditySweepBiasEngine(AnalyzeMarketStructureUseCase())

    // ------------------------------------------------------------------
    // MultiTimeframeSeries — the no-lookahead substrate
    // ------------------------------------------------------------------

    @Test
    fun `a higher timeframe bar is only knowable once the execution series reached its close`() {
        val execution = LiquiditySweepFixtures.m5Walk(600)
        val series = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)

        assertTrue("the fixture must produce higher-timeframe bars", series.candles.isNotEmpty())
        val duration = 15 * 60 * 1000L
        val executionDuration = 5 * 60 * 1000L

        series.candles.indices.forEach { i ->
            val knownFrom = series.knownFrom(i)
            val bucketEnd = series.candles[i].timestamp + duration
            assertTrue(
                "bar $i was published before its bucket closed",
                execution[knownFrom].timestamp + executionDuration >= bucketEnd,
            )
            if (knownFrom > 0) {
                assertTrue(
                    "bar $i could have been published one bar earlier",
                    execution[knownFrom - 1].timestamp + executionDuration < bucketEnd,
                )
            }
        }
    }

    @Test
    fun `the unfinished trailing bucket is never published`() {
        val execution = LiquiditySweepFixtures.m5Walk(601)
        val series = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)
        val lastClose = series.candles.last().timestamp + 15 * 60 * 1000L
        val seriesEnd = execution.last().timestamp + 5 * 60 * 1000L
        assertTrue("a bucket that had not closed was published", lastClose <= seriesEnd)
    }

    @Test
    fun `the closed prefix grows monotonically and never shrinks`() {
        val execution = LiquiditySweepFixtures.m5Walk(400)
        val series = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)

        var previous = 0
        execution.indices.forEach { i ->
            val count = series.countClosedAt(i)
            assertTrue("the closed count went backwards at $i", count >= previous)
            previous = count
        }
        assertEquals(series.candles.size, series.countClosedAt(execution.lastIndex))
    }

    @Test
    fun `resampling to the same or a lower timeframe carries no independent information`() {
        val execution = LiquiditySweepFixtures.m5Walk(200)
        assertTrue(MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M5).isEmpty)
        assertTrue(MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M1).isEmpty)
        assertTrue(MultiTimeframeSeries.from(emptyList(), Timeframe.M5, Timeframe.M15).isEmpty)
    }

    // ------------------------------------------------------------------
    // Bias
    // ------------------------------------------------------------------

    @Test
    fun `requiring both timeframes to agree is never looser than the higher alone`() {
        val execution = LiquiditySweepFixtures.m5Walk(2_000, seed = 4)
        val higher = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.H1)
        val mid = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)

        val htfOnly = LiquiditySweepFixtures.testConfig(biasMode = BiasMode.HTF_STRUCTURE)
        val both = LiquiditySweepFixtures.testConfig(biasMode = BiasMode.HTF_AND_MTF_AGREE)

        var loose = 0
        var strict = 0
        for (bar in 200 until execution.size step 5) {
            val a = biasEngine.biasAt(bar, higher, mid, htfOnly)
            val b = biasEngine.biasAt(bar, higher, mid, both)
            if (a != null) loose++
            if (b != null) {
                strict++
                assertNotNull("agreement produced a bias the higher timeframe alone did not", a)
                assertEquals("agreement disagreed with the higher timeframe", a?.bias, b.bias)
            }
        }
        assertTrue("the fixture must produce a bias to compare", loose > 0)
        assertTrue("agreement admitted more than the higher timeframe alone", strict <= loose)
    }

    @Test
    fun `a disabled bias filter is neutral rather than absent`() {
        val execution = LiquiditySweepFixtures.m5Walk(600)
        val higher = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.H1)
        val mid = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)

        val bias = biasEngine.biasAt(
            500, higher, mid,
            LiquiditySweepFixtures.testConfig(biasMode = BiasMode.NONE),
        )
        assertNotNull(bias)
        assertEquals(Bias.NEUTRAL, bias?.bias)
        assertNull("a neutral bias must not claim a direction", bias?.direction)
    }

    // ------------------------------------------------------------------
    // Levels
    // ------------------------------------------------------------------

    @Test
    fun `levels are never knowable before the higher-timeframe bar that formed them closed`() {
        val execution = LiquiditySweepFixtures.m5Walk(1_500, seed = 2)
        val higher = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.H1)
        val mid = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)
        val levels = levelEngine.levels(higher, mid, LiquiditySweepFixtures.testConfig())

        assertTrue("fixture must mark levels", levels.isNotEmpty())
        levels.forEach {
            assertTrue("a level was published before the series began", it.knownFromIndex >= 0)
            assertTrue("a level was published past the series", it.knownFromIndex <= execution.lastIndex)
        }
    }

    @Test
    fun `levels describing the same shelf are collapsed`() {
        val execution = LiquiditySweepFixtures.m5Walk(1_500, seed = 2)
        val higher = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.H1)
        val mid = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)
        val config = LiquiditySweepFixtures.testConfig()
        val levels = levelEngine.levels(higher, mid, config)

        levels.indices.forEach { i ->
            ((i + 1) until levels.size).forEach { j ->
                if (levels[i].aboveMarket == levels[j].aboveMarket) {
                    val tolerance = kotlin.math.abs(levels[i].price) * config.levelClusterFraction
                    assertTrue(
                        "two levels on the same side sit within the cluster tolerance",
                        kotlin.math.abs(levels[i].price - levels[j].price) > tolerance,
                    )
                }
            }
        }
    }

    @Test
    fun `the active book is bounded, aged and only ever backward looking`() {
        val execution = LiquiditySweepFixtures.m5Walk(1_500, seed = 2)
        val higher = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.H1)
        val mid = MultiTimeframeSeries.from(execution, Timeframe.M5, Timeframe.M15)
        val config = LiquiditySweepFixtures.testConfig().copy(maxLevelAgeBars = 200)
        val levels = levelEngine.levels(higher, mid, config)

        val at = 1_200
        val active = levelEngine.activeAt(levels, at, config)
        assertTrue("the book must not be empty at this point", active.isNotEmpty())
        assertTrue(
            "the book is capped per side",
            active.count { it.aboveMarket } <= config.maxActiveLevelsPerSide &&
                active.count { !it.aboveMarket } <= config.maxActiveLevelsPerSide,
        )
        active.forEach {
            assertTrue("a level from the future entered the book", it.knownFromIndex <= at)
            assertTrue("a stale level stayed in the book", at - it.knownFromIndex <= config.maxLevelAgeBars)
        }
    }

    // ------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------

    @Test
    fun `a penetration that closes back is a sweep`() {
        val level = sellSideLevel(1.1000)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.1010),
        )
        val sweep = sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig())

        assertNotNull("price took the level and closed back above it", sweep)
        requireNotNull(sweep)
        assertEquals(Direction.BULLISH, sweep.direction)
        assertEquals(1.0990, sweep.extreme, 1e-9)
        assertEquals(1, sweep.reclaimIndex)
    }

    @Test
    fun `a penetration that holds below is a break, not a sweep`() {
        val level = sellSideLevel(1.1000)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.0992),
        )
        assertNull(
            "a close beyond the level is an accepted break",
            sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig()),
        )
    }

    @Test
    fun `a break that is only reclaimed later is not rescued`() {
        val level = sellSideLevel(1.1000)
        // Bar 1 breaks and closes below; bar 2 stays below; bar 3 closes back.
        // That is a break with a pullback, not liquidity being collected.
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.0992),
            LiquiditySweepFixtures.bar(2, 1.0992, 1.0996, 1.0985, 1.0990),
            LiquiditySweepFixtures.bar(3, 1.0990, 1.1010, 1.0988, 1.1005),
        )
        assertNull(
            sweepDetector.sweepAt(candles, 3, listOf(level), LiquiditySweepFixtures.testConfig()),
        )
    }

    @Test
    fun `a level is not swept before it exists`() {
        val level = sellSideLevel(1.1000).copy(knownFromIndex = 5)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.1010),
        )
        assertNull(
            "the level had not formed when price traded through that price",
            sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig()),
        )
    }

    @Test
    fun `a touch that does not clear the penetration threshold is not a sweep`() {
        val level = sellSideLevel(1.1000)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.1000, 1.1010),
        )
        assertNull(
            sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig()),
        )
    }

    @Test
    fun `buy-side sweeps mirror sell-side sweeps`() {
        val level = sellSideLevel(1.1000).copy(aboveMarket = true)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.0980, 1.0985, 1.0975, 1.0982),
            LiquiditySweepFixtures.bar(1, 1.0982, 1.1010, 1.0980, 1.0990),
        )
        val sweep = sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig())

        requireNotNull(sweep)
        assertEquals("buy-side liquidity taken sets up a short", Direction.BEARISH, sweep.direction)
        assertEquals(1.1010, sweep.extreme, 1e-9)
    }

    // ------------------------------------------------------------------
    // Entry and geometry
    // ------------------------------------------------------------------

    @Test
    fun `the stop sits beyond the swept extreme and the reward multiple is respected`() {
        val level = sellSideLevel(1.1000)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.1010),
        )
        val sweep = requireNotNull(
            sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig()),
        )

        val geometry = entryEngine.geometry(
            direction = Direction.BULLISH,
            entry = 1.1010,
            sweep = sweep,
            opposingLevels = emptyList(),
            config = LiquiditySweepFixtures.testConfig().copy(targetMode = TargetMode.FIXED_R, riskReward = 3.0),
        )

        requireNotNull(geometry)
        assertTrue("the stop must sit below the swept low", geometry.stop < sweep.extreme)
        val risk = geometry.entry - geometry.stop
        assertEquals(geometry.entry + 3.0 * risk, geometry.target, 1e-9)
    }

    @Test
    fun `a setup that cannot reach the minimum reward is rejected`() {
        val level = sellSideLevel(1.1000)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.1010),
        )
        val sweep = requireNotNull(
            sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig()),
        )

        // The only opposing liquidity sits barely above the entry.
        val nearTarget = LiquidityLevel(
            source = LevelSource.MTF_SWING,
            timeframe = Timeframe.M15,
            aboveMarket = true,
            price = 1.1012,
            knownFromIndex = 0,
            touches = 1,
        )
        assertNull(
            entryEngine.geometry(
                direction = Direction.BULLISH,
                entry = 1.1010,
                sweep = sweep,
                opposingLevels = listOf(nearTarget),
                config = LiquiditySweepFixtures.testConfig().copy(minRiskReward = 2.0),
            ),
        )
    }

    @Test
    fun `geometry refuses a stop on the wrong side of the entry`() {
        val level = sellSideLevel(1.1000)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.1010),
        )
        val sweep = requireNotNull(
            sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig()),
        )
        assertNull(
            "an entry below the swept low leaves no risk to measure",
            entryEngine.geometry(
                direction = Direction.BULLISH,
                entry = 1.0985,
                sweep = sweep,
                opposingLevels = emptyList(),
                config = LiquiditySweepFixtures.testConfig(),
            ),
        )
    }

    @Test
    fun `reclaim mode enters on the reclaim bar itself`() {
        val level = sellSideLevel(1.1000)
        val candles = listOf(
            LiquiditySweepFixtures.bar(0, 1.1020, 1.1025, 1.1015, 1.1018),
            LiquiditySweepFixtures.bar(1, 1.1018, 1.1020, 1.0990, 1.1010),
        )
        val sweep = requireNotNull(
            sweepDetector.sweepAt(candles, 1, listOf(level), LiquiditySweepFixtures.testConfig()),
        )
        val entry = entryEngine.findEntry(
            candles, sweep,
            LiquiditySweepFixtures.testConfig(entryMode = EntryMode.RECLAIM),
        )
        requireNotNull(entry)
        assertEquals(sweep.reclaimIndex, entry.index)
        assertEquals(candles[1].close, entry.price, 1e-9)
    }

    // ------------------------------------------------------------------

    private fun sellSideLevel(price: Double) = LiquidityLevel(
        source = LevelSource.MTF_SWING,
        timeframe = Timeframe.M15,
        aboveMarket = false,
        price = price,
        knownFromIndex = 0,
        touches = 1,
    )
}
