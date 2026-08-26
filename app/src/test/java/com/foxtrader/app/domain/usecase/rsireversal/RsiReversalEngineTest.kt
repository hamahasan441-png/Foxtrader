package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Whole-engine guarantees: replay/batch parity (§36), strict non-repaint
 * (§32), duplicate protection (§30), reliability (§42) and performance (§43).
 */
class RsiReversalEngineTest {

    private val engine = RsiReversalEngine()

    private fun analyze(candles: List<Candle>, config: RsiReversalConfig = RsiReversalConfig()) =
        engine.analyze(
            symbol = RsiReversalFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = candles,
            config = config,
        )

    /** Structural identity that survives an index shift, for cross-run comparison. */
    private fun fingerprint(setup: RsiReversalSetup) = listOf(
        setup.direction.name,
        setup.p1.timestamp,
        setup.p2.timestamp,
        setup.finalExtreme.timestamp,
        setup.recursiveDepth,
        setup.armedTimestamp,
    ).joinToString("|")

    // ------------------------------------------------------------------
    // §36 — replay equals historical
    // ------------------------------------------------------------------

    @Test
    fun `analysis over a prefix equals the full run restricted to that prefix`() {
        val candles = RsiReversalFixtures.randomWalk(1_500, seed = 7)
        val full = analyze(candles)
        assertTrue("fixture must exercise the pattern", full.armedSetups.isNotEmpty())

        // Replay: at several points in time, what the engine knew then must
        // match what the completed history says about that same period.
        listOf(600, 900, 1_200).forEach { cutoff ->
            val replay = analyze(candles.take(cutoff))
            val expected = full.armedSetups
                .filter { it.armedIndex < cutoff }
                .map(::fingerprint)
            val actual = replay.armedSetups.map(::fingerprint)
            assertEquals("replay diverged from history at bar $cutoff", expected, actual)
        }
    }

    @Test
    fun `a confirmed setup never disappears as bars are appended`() {
        val candles = RsiReversalFixtures.randomWalk(1_200, seed = 11)
        val early = analyze(candles.take(800)).armedSetups.map(::fingerprint)
        val late = analyze(candles).armedSetups.map(::fingerprint)
        assertTrue("fixture must produce setups", early.isNotEmpty())
        assertTrue("appending bars erased a confirmed setup", late.containsAll(early))
    }

    // ------------------------------------------------------------------
    // §32.4 — reload reproduces the same signals, including after a prepend
    // ------------------------------------------------------------------

    @Test
    fun `prepending older history does not repaint confirmed setups`() {
        // This is the repaint hazard specific to this app: the chart prepends
        // older bars at runtime, and Wilder RSI is seeded from the first bar,
        // so every historical RSI value shifts slightly. The warmup exclusion
        // is what keeps that shift from flipping an already-published decision.
        val whole = RsiReversalFixtures.randomWalk(1_600, seed = 23)
        val prepended = 400

        val withoutHistory = analyze(whole.drop(prepended))
        val withHistory = analyze(whole)

        // Compare only setups the shorter run was allowed to publish, since the
        // longer run legitimately sees further back.
        val boundary = whole.drop(prepended)[RsiReversalConfig().warmupBars].timestamp
        val a = withoutHistory.armedSetups.filter { it.armedTimestamp >= boundary }.map(::fingerprint)
        val b = withHistory.armedSetups.filter { it.armedTimestamp >= boundary }.map(::fingerprint)

        assertTrue("fixture must produce comparable setups", a.isNotEmpty())
        assertEquals("prepended history repainted confirmed setups", b, a)
    }

    @Test
    fun `the warmup region never publishes a setup`() {
        val config = RsiReversalConfig()
        val analysis = analyze(RsiReversalFixtures.randomWalk(1_200, seed = 31), config)
        assertTrue(
            "a setup escaped the warmup exclusion",
            analysis.armedSetups.all { it.armedIndex >= config.warmupBars },
        )
    }

    // ------------------------------------------------------------------
    // §30 — duplicate protection
    // ------------------------------------------------------------------

    @Test
    fun `every armed setup has a distinct identity`() {
        val analysis = analyze(RsiReversalFixtures.randomWalk(2_000, seed = 5))
        val keys = analysis.armedSetups.map { it.key }
        assertEquals("duplicate setup keys were published", keys.size, keys.distinct().size)
    }

    // ------------------------------------------------------------------
    // §16 — no lower-timeframe data means no entry, never a fabricated one
    // ------------------------------------------------------------------

    @Test
    fun `setups arm without lower timeframe data but produce no signal`() {
        val analysis = analyze(RsiReversalFixtures.randomWalk(1_500, seed = 7))
        assertTrue(analysis.armedSetups.isNotEmpty())
        assertTrue("confirmation cannot be invented from context bars", analysis.signals.isEmpty())
    }

    @Test
    fun `signals carry the configured reward multiple and a stop on the correct side`() {
        val htf = RsiReversalFixtures.randomWalk(1_500, seed = 7)
        // A finer series over the same span stands in for the entry timeframe.
        val ltf = RsiReversalFixtures.retimed(
            RsiReversalFixtures.randomWalk(6_000, seed = 8),
            intervalMillis = 5 * 60 * 1000L,
            startTime = htf.first().timestamp,
        )
        val analysis = engine.analyze(
            symbol = RsiReversalFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = htf,
            ltfCandles = ltf,
            ltfTimeframe = Timeframe.M5,
        )

        analysis.signals.forEach { signal ->
            assertEquals("reward multiple must be exactly as configured", 4.0, signal.riskReward, 1e-6)
            assertTrue("risk must be positive", signal.risk > 0.0)
            if (signal.direction == com.foxtrader.app.domain.model.Direction.BULLISH) {
                assertTrue(signal.stop < signal.entry && signal.target > signal.entry)
            } else {
                assertTrue(signal.stop > signal.entry && signal.target < signal.entry)
            }
            assertTrue(
                "an entry may not precede the setup arming",
                signal.confirmedAt >= signal.setup.armedTimestamp,
            )
        }
        assertEquals(
            "one arrow per setup",
            analysis.signals.map { it.setup.key }.distinct().size,
            analysis.signals.size,
        )
    }

    // ------------------------------------------------------------------
    // §42 — reliability
    // ------------------------------------------------------------------

    @Test
    fun `degenerate and malformed series never throw`() {
        val base = RsiReversalFixtures.randomWalk(300)
        val cases = mapOf(
            "empty" to emptyList(),
            "single" to base.take(1),
            "shorter than the rsi period" to base.take(5),
            "shorter than the warmup" to base.take(50),
            "duplicate bars" to (base.take(100) + base.take(100)),
            "out of order" to base.shuffled(kotlin.random.Random(3)),
            "gapped" to base.filterIndexed { index, _ -> index % 3 != 0 },
            "flat" to List(300) { base.first().copy(timestamp = it * 1000L) },
        )
        cases.forEach { (name, candles) ->
            val analysis = analyze(candles)
            assertEquals("$name changed the series length", candles.size, analysis.rsiCandles.size)
        }
    }

    @Test
    fun `analysis is deterministic across repeated runs`() {
        val candles = RsiReversalFixtures.randomWalk(1_200, seed = 17)
        val first = analyze(candles).armedSetups.map(::fingerprint)
        val second = analyze(candles).armedSetups.map(::fingerprint)
        assertEquals(first, second)
    }

    // ------------------------------------------------------------------
    // §43 — performance
    // ------------------------------------------------------------------

    @Test
    fun `a hundred thousand bars analyse within budget`() {
        val candles = RsiReversalFixtures.randomWalk(100_000, seed = 99)
        lateinit var setups: List<RsiReversalSetup>
        val elapsed = measureTimeMillis { setups = analyze(candles).armedSetups }

        assertTrue("100k bars must remain tractable, took ${elapsed}ms", elapsed < 15_000)
        assertTrue("a 100k-bar history should contain setups", setups.isNotEmpty())
    }
}
