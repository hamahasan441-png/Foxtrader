package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquiditySweepSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Whole-engine guarantees: replay parity, no look-ahead, the strictness
 * ladders, duplicate protection, reliability and performance.
 */
class LiquiditySweepEngineTest {

    private val engine = LiquiditySweepEngine(AnalyzeMarketStructureUseCase())

    private fun analyze(
        candles: List<Candle>,
        config: LiquiditySweepConfig = LiquiditySweepConfig(),
    ) = engine.analyze(LiquiditySweepFixtures.SYMBOL, Timeframe.M5, candles, config)

    /** Identity that survives being recomputed, for cross-run comparison. */
    private fun fingerprint(signal: LiquiditySweepSignal) = listOf(
        signal.direction.name,
        signal.timestamp,
        signal.sweep.sweepIndex,
        signal.entryIndex,
        signal.entryType.name,
    ).joinToString("|")

    // ------------------------------------------------------------------
    // Replay parity and non-repaint
    // ------------------------------------------------------------------

    @Test
    fun `analysis over a prefix equals the full run restricted to that prefix`() {
        val candles = LiquiditySweepFixtures.m5Walk(3_000, seed = 1)
        val full = analyze(candles)
        assertTrue("fixture must exercise the model", full.signals.isNotEmpty())

        listOf(1_200, 1_800, 2_400).forEach { cutoff ->
            val replay = analyze(candles.take(cutoff))
            val expected = full.signals.filter { it.entryIndex < cutoff }.map(::fingerprint)
            val actual = replay.signals.map(::fingerprint)
            assertEquals("replay diverged from history at bar $cutoff", expected, actual)
        }
    }

    @Test
    fun `a confirmed signal never disappears as bars are appended`() {
        val candles = LiquiditySweepFixtures.m5Walk(3_000, seed = 3)
        val early = analyze(candles.take(2_000)).signals.map(::fingerprint)
        val late = analyze(candles).signals.map(::fingerprint)

        assertTrue("fixture must produce signals", early.isNotEmpty())
        assertTrue("appending bars erased a confirmed signal", late.containsAll(early))
    }

    @Test
    fun `an entry never precedes the sweep that justified it`() {
        val analysis = analyze(LiquiditySweepFixtures.m5Walk(3_000, seed = 1))
        assertTrue(analysis.signals.isNotEmpty())
        analysis.signals.forEach {
            assertTrue("entry preceded the reclaim", it.entryIndex >= it.sweep.reclaimIndex)
            assertTrue("reclaim preceded the penetration", it.sweep.reclaimIndex >= it.sweep.sweepIndex)
            assertTrue(
                "the level was not knowable when it was swept",
                it.sweep.level.knownFromIndex <= it.sweep.sweepIndex,
            )
            assertTrue(
                "the bias was not knowable when the sweep confirmed",
                it.bias.knownFromIndex <= it.sweep.reclaimIndex,
            )
        }
    }

    @Test
    fun `analysis is deterministic across repeated runs`() {
        val candles = LiquiditySweepFixtures.m5Walk(2_000, seed = 9)
        assertEquals(
            analyze(candles).signals.map(::fingerprint),
            analyze(candles).signals.map(::fingerprint),
        )
    }

    // ------------------------------------------------------------------
    // The strictness ladders
    // ------------------------------------------------------------------

    @Test
    fun `entry modes are ordered by strictness`() {
        val candles = LiquiditySweepFixtures.m5Walk(3_000, seed = 1)
        fun count(mode: EntryMode) =
            analyze(candles, LiquiditySweepConfig(entryMode = mode)).signals.size

        val reclaim = count(EntryMode.RECLAIM)
        val retest = count(EntryMode.RETEST)
        val choch = count(EntryMode.CHOCH_RETEST)

        assertTrue("the fixture must produce signals to compare", reclaim > 0)
        assertTrue("Retest admitted more than Reclaim", retest <= reclaim)
        assertTrue("Choch+Retest admitted more than Retest", choch <= retest)
    }

    @Test
    fun `bias modes are ordered by strictness`() {
        val candles = LiquiditySweepFixtures.m5Walk(3_000, seed = 1)
        fun count(mode: BiasMode) =
            analyze(candles, LiquiditySweepConfig(biasMode = mode)).signals.size

        val none = count(BiasMode.NONE)
        val htf = count(BiasMode.HTF_STRUCTURE)
        val both = count(BiasMode.HTF_AND_MTF_AGREE)

        assertTrue("the fixture must produce signals to compare", none > 0)
        assertTrue("the HTF filter admitted more than no filter", htf <= none)
        assertTrue("requiring agreement admitted more than the HTF filter", both <= htf)
    }

    @Test
    fun `every signal trades with the higher-timeframe bias`() {
        val analysis = analyze(
            LiquiditySweepFixtures.m5Walk(3_000, seed = 1),
            LiquiditySweepConfig(biasMode = BiasMode.HTF_STRUCTURE),
        )
        assertTrue(analysis.signals.isNotEmpty())
        analysis.signals.forEach {
            assertEquals("a signal traded against its own bias", it.bias.direction, it.direction)
        }
    }

    // ------------------------------------------------------------------
    // Liquidity is consumed once collected
    // ------------------------------------------------------------------

    @Test
    fun `one shelf is only swept once`() {
        val analysis = analyze(LiquiditySweepFixtures.m5Walk(3_000, seed = 1))
        val shelves = analysis.sweeps.map { it.level.aboveMarket to it.level.price }
        assertTrue("fixture must produce sweeps", shelves.isNotEmpty())
        assertEquals("the same shelf was swept twice", shelves.size, shelves.distinct().size)
    }

    @Test
    fun `every signal has a distinct identity`() {
        val analysis = analyze(LiquiditySweepFixtures.m5Walk(3_000, seed = 1))
        val keys = analysis.signals.map { it.key }
        assertEquals("duplicate signal keys were published", keys.size, keys.distinct().size)
    }

    // ------------------------------------------------------------------
    // Geometry and publication
    // ------------------------------------------------------------------

    @Test
    fun `signal geometry is complete and correctly sided`() {
        val analysis = analyze(LiquiditySweepFixtures.m5Walk(3_000, seed = 1))
        assertTrue(analysis.signals.isNotEmpty())

        analysis.signals.forEach { signal ->
            assertTrue(signal.entry.isFinite() && signal.entry > 0.0)
            assertTrue(signal.stop.isFinite() && signal.target.isFinite())
            assertTrue("risk must be positive", signal.risk > 0.0)
            assertTrue("reward must clear the minimum", signal.riskReward >= LiquiditySweepConfig().minRiskReward)
            if (signal.direction == Direction.BULLISH) {
                assertTrue(signal.stop < signal.entry && signal.target > signal.entry)
            } else {
                assertTrue(signal.stop > signal.entry && signal.target < signal.entry)
            }
        }
    }

    @Test
    fun `hiding history shows only the live window`() {
        val candles = LiquiditySweepFixtures.m5Walk(3_000, seed = 1)
        val all = analyze(candles, LiquiditySweepConfig(historicalSignals = true))
        val live = analyze(
            candles,
            LiquiditySweepConfig(historicalSignals = false, liveWindowBars = 200),
        )

        assertTrue("fixture must produce history to hide", all.signals.size > live.signals.size)
        live.signals.forEach {
            assertTrue("a signal outside the live window survived", it.entryIndex >= candles.lastIndex - 199)
        }
    }

    @Test
    fun `the warmup region never publishes a signal`() {
        val config = LiquiditySweepConfig()
        val analysis = analyze(LiquiditySweepFixtures.m5Walk(3_000, seed = 1), config)
        assertTrue(analysis.signals.all { it.entryIndex >= config.warmupBars })
    }

    // ------------------------------------------------------------------
    // Backtest entry point
    // ------------------------------------------------------------------

    @Test
    fun `the backtest function never reads past the evaluated bar`() {
        val candles = LiquiditySweepFixtures.m5Walk(3_000, seed = 1)
        val entryBars = analyze(candles).signals.map { it.entryIndex }
        assertTrue("fixture must supply bars to probe", entryBars.isNotEmpty())

        entryBars.take(12).forEach { index ->
            val withFuture = engine.signalAt(LiquiditySweepFixtures.SYMBOL, Timeframe.M5, candles, index)
            val withoutFuture = engine.signalAt(
                LiquiditySweepFixtures.SYMBOL, Timeframe.M5, candles.take(index + 1), index,
            )
            assertNotNull("the backtester missed a signal the chart reports at $index", withFuture)
            assertEquals("bar $index disagreed with its own prefix", withFuture, withoutFuture)
        }
    }

    @Test
    fun `the backtest function is bounds safe and refuses unmapped timeframes`() {
        val candles = LiquiditySweepFixtures.m5Walk(500)
        assertNull(engine.signalAt(LiquiditySweepFixtures.SYMBOL, Timeframe.M5, candles, -1))
        assertNull(engine.signalAt(LiquiditySweepFixtures.SYMBOL, Timeframe.M5, candles, 9_999))
        assertNull(engine.signalAt(LiquiditySweepFixtures.SYMBOL, Timeframe.M5, emptyList(), 0))
        assertNull(
            "MN has no timeframes above it in the ladder",
            engine.signalAt(LiquiditySweepFixtures.SYMBOL, Timeframe.MN, candles, 400),
        )
    }

    // ------------------------------------------------------------------
    // Reliability and performance
    // ------------------------------------------------------------------

    @Test
    fun `degenerate and malformed series never throw`() {
        val base = LiquiditySweepFixtures.m5Walk(400)
        val cases = mapOf(
            "empty" to emptyList(),
            "single" to base.take(1),
            "below the minimum" to base.take(40),
            "duplicate bars" to (base.take(200) + base.take(200)),
            "out of order" to base.shuffled(kotlin.random.Random(3)),
            "gapped" to base.filterIndexed { index, _ -> index % 3 != 0 },
            "flat" to List(400) { base.first().copy(timestamp = LiquiditySweepFixtures.START_TIME + it * 300_000L) },
        )
        cases.forEach { (name, candles) ->
            val analysis = analyze(candles)
            assertTrue("$name produced an unusable status", analysis.statusText.isNotBlank())
        }
    }

    @Test
    fun `an unmapped execution timeframe is refused with a reason`() {
        val analysis = engine.analyze(
            LiquiditySweepFixtures.SYMBOL,
            Timeframe.MN,
            LiquiditySweepFixtures.m5Walk(400),
        )
        assertTrue(analysis.signals.isEmpty())
        assertTrue(analysis.statusText.contains("higher timeframes", ignoreCase = true))
    }

    @Test
    fun `fifty thousand bars analyse within budget`() {
        val candles = LiquiditySweepFixtures.m5Walk(50_000, seed = 11)
        var signals = 0
        val elapsed = measureTimeMillis { signals = analyze(candles).signals.size }

        assertTrue("50k bars must remain tractable, took ${elapsed}ms", elapsed < 20_000)
        assertTrue("a 50k-bar history should contain signals", signals > 0)
    }
}
