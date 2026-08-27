package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.KillZone
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWickSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Whole-engine guarantees: replay parity, no look-ahead, the strictness
 * ladders, one-zone-one-trade, sessions, reliability and performance.
 */
class VirginWickEngineTest {

    private val engine = VirginWickEngine(SmcDetector())

    private fun analyze(
        candles: List<Candle>,
        config: VirginWickConfig = VirginWickConfig(),
    ) = engine.analyze(VirginWickFixtures.SYMBOL, Timeframe.M1, candles, config)

    private fun fingerprint(signal: VirginWickSignal) = listOf(
        signal.direction.name,
        signal.timestamp,
        signal.poi.wick.contextIndex,
        signal.entryIndex,
        signal.entryType.name,
    ).joinToString("|")

    // ------------------------------------------------------------------
    // Replay parity and non-repaint
    // ------------------------------------------------------------------

    @Test
    fun `analysis over a prefix equals the full run restricted to that prefix`() {
        val candles = VirginWickFixtures.m1Walk(4_000, seed = 1)
        val full = analyze(candles)
        assertTrue("fixture must exercise the model", full.signals.isNotEmpty())

        listOf(1_500, 2_500, 3_500).forEach { cutoff ->
            val replay = analyze(candles.take(cutoff))
            val expected = full.signals.filter { it.entryIndex < cutoff }.map(::fingerprint)
            assertEquals("replay diverged from history at bar $cutoff", expected, replay.signals.map(::fingerprint))
        }
    }

    @Test
    fun `a confirmed signal never disappears when the wick is later retested`() {
        // The sharpest repaint risk in this model: the wick that justified an
        // old arrow will eventually be traded through. That must not erase it.
        val candles = VirginWickFixtures.m1Walk(4_000, seed = 1)
        val early = analyze(candles.take(2_500)).signals.map(::fingerprint)
        val late = analyze(candles).signals.map(::fingerprint)

        assertTrue("fixture must produce signals", early.isNotEmpty())
        assertTrue("a later retest erased a confirmed arrow", late.containsAll(early))
    }

    @Test
    fun `nothing is acted on before it was knowable`() {
        val analysis = analyze(VirginWickFixtures.m1Walk(4_000, seed = 1))
        assertTrue(analysis.signals.isNotEmpty())
        analysis.signals.forEach {
            assertTrue(
                "the zone was entered before its wick existed",
                it.poi.wick.knownFromIndex <= it.entryIndex,
            )
            assertTrue(
                "the zone was entered before it activated",
                it.poi.activatedAtIndex <= it.entryIndex,
            )
            it.ifvg?.let { ifvg ->
                assertTrue("the inversion confirmed after the entry", ifvg.inversionIndex <= it.entryIndex)
                assertTrue("the gap formed after it inverted", ifvg.originIndex <= ifvg.inversionIndex)
            }
        }
    }

    @Test
    fun `analysis is deterministic across repeated runs`() {
        val candles = VirginWickFixtures.m1Walk(3_000, seed = 7)
        assertEquals(analyze(candles).signals.map(::fingerprint), analyze(candles).signals.map(::fingerprint))
    }

    // ------------------------------------------------------------------
    // The strictness ladders
    // ------------------------------------------------------------------

    @Test
    fun `entry modes are ordered by strictness`() {
        val candles = VirginWickFixtures.m1Walk(4_000, seed = 1)
        fun count(mode: EntryMode) = analyze(candles, VirginWickConfig(entryMode = mode)).signals.size

        val touch = count(EntryMode.POI_TOUCH)
        val ifvg = count(EntryMode.IFVG)
        val inside = count(EntryMode.IFVG_IN_POI)

        assertTrue("the fixture must produce signals to compare", touch > 0)
        assertTrue("requiring an inversion admitted more than a bare touch", ifvg <= touch)
        assertTrue("requiring it inside the zone admitted more than anywhere", inside <= ifvg)
    }

    @Test
    fun `a stricter reading of virginity never yields more zones`() {
        val candles = VirginWickFixtures.m1Walk(4_000, seed = 1)
        fun count(mode: WickTestMode) = analyze(candles, VirginWickConfig(testMode = mode)).signals.size

        // ANY_TOUCH spends a wick soonest, so it must leave the fewest alive.
        val any = count(WickTestMode.ANY_TOUCH)
        val midpoint = count(WickTestMode.MIDPOINT)
        val extreme = count(WickTestMode.EXTREME)

        assertTrue("the fixture must produce signals to compare", extreme > 0)
        assertTrue("the strictest reading kept more wicks alive", any <= midpoint)
        assertTrue("the midpoint reading kept more wicks alive than the extreme", midpoint <= extreme)
    }

    // ------------------------------------------------------------------
    // A zone is worked once
    // ------------------------------------------------------------------

    @Test
    fun `one untested wick produces at most one trade`() {
        val analysis = analyze(VirginWickFixtures.m1Walk(4_000, seed = 1))
        val zones = analysis.signals.map { it.poi.wick.contextIndex to it.poi.wick.distal }
        assertTrue("fixture must produce signals", zones.isNotEmpty())
        assertEquals("the same wick was traded twice", zones.size, zones.distinct().size)
    }

    @Test
    fun `every signal has a distinct identity`() {
        val keys = analyze(VirginWickFixtures.m1Walk(4_000, seed = 1)).signals.map { it.key }
        assertEquals("duplicate signal keys were published", keys.size, keys.distinct().size)
    }

    // ------------------------------------------------------------------
    // Sessions, geometry and publication
    // ------------------------------------------------------------------

    @Test
    fun `a session filter admits only entries inside the chosen kill zones`() {
        val candles = VirginWickFixtures.m1Walk(6_000, seed = 1)
        val unfiltered = analyze(candles)
        val filtered = analyze(candles, VirginWickConfig(sessions = setOf(KillZone.NEW_YORK_OPEN)))

        assertTrue("fixture must produce signals", unfiltered.signals.isNotEmpty())
        assertTrue("a filter cannot add signals", filtered.signals.size <= unfiltered.signals.size)
        filtered.signals.forEach {
            assertEquals("a signal escaped the session filter", KillZone.NEW_YORK_OPEN, it.killZone)
        }
    }

    @Test
    fun `signal geometry is complete and correctly sided`() {
        val analysis = analyze(VirginWickFixtures.m1Walk(4_000, seed = 1))
        assertTrue(analysis.signals.isNotEmpty())

        analysis.signals.forEach { signal ->
            assertTrue(signal.entry.isFinite() && signal.entry > 0.0)
            assertTrue(signal.stop.isFinite() && signal.target.isFinite())
            assertTrue("risk must be positive", signal.risk > 0.0)
            assertTrue(
                "reward must clear the configured floor",
                signal.rewardMultiple >= VirginWickConfig().minRewardMultiple - 1e-9,
            )
            if (signal.direction == Direction.BULLISH) {
                assertTrue(signal.stop < signal.entry && signal.target > signal.entry)
            } else {
                assertTrue(signal.stop > signal.entry && signal.target < signal.entry)
            }
        }
    }

    @Test
    fun `hiding history shows only the live window`() {
        val candles = VirginWickFixtures.m1Walk(6_000, seed = 1)
        val all = analyze(candles, VirginWickConfig(historicalSignals = true))
        val live = analyze(candles, VirginWickConfig(historicalSignals = false, liveWindowBars = 500))

        assertTrue("fixture must produce history to hide", all.signals.size > live.signals.size)
        live.signals.forEach {
            assertTrue("a signal outside the live window survived", it.entryIndex >= candles.lastIndex - 499)
        }
    }

    @Test
    fun `the warmup region never publishes a signal`() {
        val config = VirginWickConfig()
        assertTrue(
            analyze(VirginWickFixtures.m1Walk(4_000, seed = 1), config)
                .signals.all { it.entryIndex >= config.warmupBars },
        )
    }

    // ------------------------------------------------------------------
    // Backtest entry point
    // ------------------------------------------------------------------

    @Test
    fun `the backtest function never reads past the evaluated bar`() {
        val candles = VirginWickFixtures.m1Walk(4_000, seed = 1)
        val entryBars = analyze(candles).signals.map { it.entryIndex }
        assertTrue("fixture must supply bars to probe", entryBars.isNotEmpty())

        entryBars.take(8).forEach { index ->
            val withFuture = engine.signalAt(VirginWickFixtures.SYMBOL, Timeframe.M1, candles, index)
            val withoutFuture = engine.signalAt(
                VirginWickFixtures.SYMBOL, Timeframe.M1, candles.take(index + 1), index,
            )
            assertNotNull("the backtester missed a signal the chart reports at $index", withFuture)
            assertEquals("bar $index disagreed with its own prefix", withFuture, withoutFuture)
        }
    }

    @Test
    fun `the backtest function is bounds safe and refuses unmapped timeframes`() {
        val candles = VirginWickFixtures.m1Walk(500)
        assertNull(engine.signalAt(VirginWickFixtures.SYMBOL, Timeframe.M1, candles, -1))
        assertNull(engine.signalAt(VirginWickFixtures.SYMBOL, Timeframe.M1, candles, 9_999))
        assertNull(engine.signalAt(VirginWickFixtures.SYMBOL, Timeframe.M1, emptyList(), 0))
        assertNull(
            "MN has nothing above it in the ladder",
            engine.signalAt(VirginWickFixtures.SYMBOL, Timeframe.MN, candles, 400),
        )
    }

    // ------------------------------------------------------------------
    // Reliability and performance
    // ------------------------------------------------------------------

    @Test
    fun `degenerate and malformed series never throw`() {
        val base = VirginWickFixtures.m1Walk(500)
        val cases = mapOf(
            "empty" to emptyList(),
            "single" to base.take(1),
            "below the minimum" to base.take(60),
            "duplicate bars" to (base.take(250) + base.take(250)),
            "out of order" to base.shuffled(kotlin.random.Random(5)),
            "gapped" to base.filterIndexed { index, _ -> index % 3 != 0 },
            "flat" to List(500) { base.first().copy(timestamp = VirginWickFixtures.START_TIME + it * 60_000L) },
        )
        cases.forEach { (name, candles) ->
            assertTrue("$name produced an unusable status", analyze(candles).statusText.isNotBlank())
        }
    }

    @Test
    fun `an unmapped execution timeframe is refused with a reason`() {
        val analysis = engine.analyze(VirginWickFixtures.SYMBOL, Timeframe.MN, VirginWickFixtures.m1Walk(500))
        assertTrue(analysis.signals.isEmpty())
        assertTrue(analysis.statusText.contains("context timeframe", ignoreCase = true))
    }

    @Test
    fun `thirty thousand bars analyse within budget`() {
        val candles = VirginWickFixtures.m1Walk(30_000, seed = 13)
        var signals = 0
        val elapsed = measureTimeMillis { signals = analyze(candles).signals.size }

        assertTrue("30k bars must remain tractable, took ${elapsed}ms", elapsed < 30_000)
        assertTrue("a 30k-bar history should contain signals", signals > 0)
    }
}
