package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * The engine end to end: the guarantee, the base-rate trap, and the
 * walk-forward discipline the guarantee depends on.
 */
class CompassEngineTest {

    private val engine = CompassFixtures.engine()

    private fun analyze(candles: List<Candle>, config: CompassConfig = CompassConfig()) =
        engine.analyze(CompassFixtures.SYMBOL, Timeframe.M5, candles, config)

    private fun fingerprint(signal: com.foxtrader.app.domain.usecase.compass.model.CompassSignal) =
        "${signal.direction.name}|${signal.timestamp}|${signal.index}|${signal.call.source}"

    // ------------------------------------------------------------------
    // The headline behaviour
    // ------------------------------------------------------------------

    @Test
    fun `the strict gate publishes nothing on a random walk`() {
        // Direction is unpredictable here by construction, so an engine that
        // keeps firing is fitting noise. With the gate switched on, it does not.
        val analysis = analyze(
            CompassFixtures.walk(30_000, seed = 1),
            CompassConfig.intraday().copy(
                minAccuracy = 0.80,
                minLiftOverBaseRate = 0.05,
                minCalibrationSample = 40,
                useConfidenceBound = true,
            ),
        )

        assertTrue("the primary layer should still be making calls", analysis.calls.isNotEmpty())
        assertTrue(
            "signals were published where direction cannot be predicted: ${analysis.statusText}",
            analysis.signals.isEmpty(),
        )
    }

    @Test
    fun `the permissive default does publish on a random walk, and that is the trade-off`() {
        // Stated in a test rather than left to be discovered. The strict gate
        // refused noise and also refused everything else, at every chart size
        // tried — so the shipped default reports its measurement instead of
        // enforcing it, and the price of that is exactly this: on structureless
        // data it will draw arrows whose measured accuracy is a coin flip.
        //
        // The number is not hidden. It travels with every signal and appears in
        // the study's status line, which is where a trader should judge it.
        val analysis = analyze(CompassFixtures.walk(30_000, seed = 1))

        assertTrue("the default must actually draw, that is the point", analysis.signals.isNotEmpty())
        assertTrue(
            "and it must report the coin flip it measured rather than hide it, was " +
                "${analysis.rawAccuracy.accuracy}",
            analysis.rawAccuracy.accuracy!! < 0.60,
        )
    }

    @Test
    fun `market drift is never published as directional skill`() {
        // The base-rate trap, end to end, and the single most valuable thing
        // this engine does. Price drifts up on pure noise, so "always long"
        // is right about 97% of the time while reading nothing at all. Inside
        // that series there are subsets scoring above 90%, which is exactly the
        // number a less careful engine would put on screen.
        val candles = CompassFixtures.driftingWalk(30_000)
        // The base-rate guard is what is under test, so it is switched on.
        val analysis = analyze(
            candles,
            CompassConfig.intraday().copy(minAccuracy = 0.80, minLiftOverBaseRate = 0.05, minCalibrationSample = 40),
        )

        assertTrue(
            "the fixture must actually contain the trap, base rate was ${analysis.rawAccuracy.baseRate}",
            analysis.rawAccuracy.baseRate!! > 0.9,
        )
        assertTrue(
            "drift was published as skill: ${analysis.statusText}",
            analysis.signals.isEmpty(),
        )

        // And it is the base-rate guard doing the work, not luck: with only
        // that guard removed, the same data does produce signals.
        val withoutGuard = analyze(
            candles,
            CompassConfig.intraday().copy(minAccuracy = 0.5, minLiftOverBaseRate = 0.0, minCalibrationSample = 20),
        )
        assertTrue(
            "the guard cannot be shown to matter if nothing publishes without it",
            withoutGuard.signals.isNotEmpty(),
        )
    }

    @Test
    fun `raising the required accuracy never publishes more`() {
        val candles = CompassFixtures.reverting(30_000, seed = 1)
        fun count(required: Double) = analyze(
            candles,
            CompassConfig.intraday()
                .copy(minAccuracy = required, minLiftOverBaseRate = 0.0, minCalibrationSample = 20),
        ).signals.size

        val counts = listOf(0.50, 0.60, 0.80, 0.95).map(::count)
        assertTrue("the fixture must publish something to compare", counts.first() > 0)
        assertEquals("a stricter requirement admitted more", counts.sortedDescending(), counts)
    }

    @Test
    fun `every published signal carries the calibration that admitted it`() {
        val analysis = analyze(
            CompassFixtures.reverting(30_000, seed = 1),
            CompassConfig.intraday().copy(minAccuracy = 0.5, minLiftOverBaseRate = 0.0, minCalibrationSample = 20),
        )
        analysis.signals.forEach {
            assertTrue("a signal was published with no threshold", it.calibration.guaranteed)
            assertTrue(
                "a signal scored below the threshold that admitted it",
                it.probability >= it.calibration.threshold!!,
            )
            assertTrue(
                "the calibration must rest on a real sample",
                it.calibration.selected >= 20,
            )
        }
    }

    // ------------------------------------------------------------------
    // Walk-forward discipline
    // ------------------------------------------------------------------

    @Test
    fun `analysis over a prefix equals the completed history inside that prefix`() {
        // Non-repaint, with no slack. Every scorer fit and every threshold is
        // built only from calls resolved strictly before the bar being decided,
        // so a longer series cannot revise what was published earlier.
        val candles = CompassFixtures.reverting(24_000, seed = 1)
        val config = CompassConfig.intraday()
            .copy(minAccuracy = 0.5, minLiftOverBaseRate = 0.0, minCalibrationSample = 20)
        val full = analyze(candles, config)
        assertTrue("fixture must publish something to compare", full.signals.isNotEmpty())

        listOf(14_000, 19_000).forEach { cutoff ->
            val replay = analyze(candles.take(cutoff), config)
            val expected = full.signals.filter { it.index < cutoff }.map(::fingerprint)
            val actual = replay.signals.filter { it.index < cutoff }.map(::fingerprint)
            assertTrue("nothing to compare at $cutoff", expected.isNotEmpty())
            assertEquals("replay disagreed with completed history at $cutoff", expected, actual)
        }
    }

    @Test
    fun `a call is never judged by a model that had already seen it`() {
        // The failure that would make every other number here meaningless. If
        // the scorer were fitted on the call it is scoring, accuracy would be
        // near perfect and would mean nothing at all.
        val candles = CompassFixtures.reverting(24_000, seed = 1)
        val config = CompassConfig.intraday()
            .copy(minAccuracy = 0.5, minLiftOverBaseRate = 0.0, minCalibrationSample = 20)
        val analysis = analyze(candles, config)

        analysis.signals.forEach { signal ->
            val evidence = analysis.calls.filter {
                it.resolved && (it.decidedIndex ?: Int.MAX_VALUE) < signal.index
            }
            assertTrue(
                "a signal at ${signal.index} was published on fewer than the required resolved calls",
                evidence.size >= config.minCalibrationSample,
            )
            assertTrue(
                "the call being judged was inside its own evidence",
                evidence.none { it.index == signal.index && it.source == signal.call.source },
            )
        }
    }

    @Test
    fun `the single pass backtest equals bar by bar replay`() {
        val candles = CompassFixtures.reverting(24_000, seed = 1)
        val config = CompassConfig.intraday()
            .copy(minAccuracy = 0.5, minLiftOverBaseRate = 0.0, minCalibrationSample = 20)
        val fast = engine.backtestFunction(CompassFixtures.SYMBOL, Timeframe.M5, candles, config)

        val published = analyze(candles, config).signals.map { it.index }
        assertTrue("nothing published to compare", published.isNotEmpty())

        val sampled = published + (CompassEngine.MIN_BARS until candles.size step 900).filter { it !in published }
        sampled.forEach { index ->
            val prefix = candles.subList(0, index + 1)
            assertEquals(
                "the two paths disagreed at bar $index",
                engine.signalAt(CompassFixtures.SYMBOL, Timeframe.M5, prefix, index, config)?.toString(),
                fast(prefix, index)?.toString(),
            )
        }
    }

    // ------------------------------------------------------------------
    // Geometry, shape and robustness
    // ------------------------------------------------------------------

    @Test
    fun `a published signal is traded at the barrier it was measured against`() {
        // Reporting accuracy for one geometry and trading another would make
        // the number a statistic about a trade nobody took.
        val candles = CompassFixtures.reverting(24_000, seed = 1)
        val config = CompassConfig.intraday()
            .copy(minAccuracy = 0.5, minLiftOverBaseRate = 0.0, minCalibrationSample = 20)

        analyze(candles, config).signals.take(20).forEach { signal ->
            val strategy = engine.signalAt(
                CompassFixtures.SYMBOL, Timeframe.M5, candles.subList(0, signal.index + 1), signal.index, config,
            )
            assertTrue("a published signal produced no tradeable form", strategy != null)
            val reward = kotlin.math.abs(strategy!!.takeProfit - strategy.entry)
            val risk = kotlin.math.abs(strategy.entry - strategy.stopLoss)
            assertEquals("the barriers must be symmetric", reward, risk, reward * 1e-9)
            if (signal.direction == Direction.BULLISH) {
                assertTrue(strategy.takeProfit > strategy.entry && strategy.stopLoss < strategy.entry)
            } else {
                assertTrue(strategy.takeProfit < strategy.entry && strategy.stopLoss > strategy.entry)
            }
        }
    }

    @Test
    fun `calls only ever carry verdicts the series can support`() {
        val candles = CompassFixtures.reverting(12_000, seed = 2)
        analyze(candles).calls.forEach {
            assertTrue(it.index in candles.indices)
            assertEquals(candles[it.index].timestamp, it.timestamp)
            assertEquals(CompassFeatures.SIZE, it.features.size)
            assertTrue("features must be finite", it.features.all { value -> value.isFinite() })
            if (it.resolved) {
                assertTrue("a verdict cannot precede its call", it.decidedIndex!! > it.index)
            }
            if (it.verdict == CompassVerdict.PENDING) {
                assertTrue(
                    "only calls near the end of the series can still be pending",
                    it.index + CompassConfig().horizonBars > candles.lastIndex,
                )
            }
        }
    }

    @Test
    fun `each preset is internally consistent`() {
        CompassPreset.entries.forEach { preset ->
            val config = CompassConfig.forPreset(preset)
            assertEquals(preset, config.preset)
            assertTrue(config.horizonBars >= 1)
            assertTrue(config.barrierAtrMultiple > 0.0)
        }
        assertTrue(
            "scalping must judge a call sooner than swing",
            CompassConfig.scalping().horizonBars < CompassConfig.swing().horizonBars,
        )
        assertTrue(
            "scalping must use a tighter barrier than swing",
            CompassConfig.scalping().barrierAtrMultiple < CompassConfig.swing().barrierAtrMultiple,
        )
    }

    @Test
    fun `degenerate and malformed series never throw`() {
        val base = CompassFixtures.walk(800)
        mapOf(
            "empty" to emptyList(),
            "single" to base.take(1),
            "below the minimum" to base.take(200),
            "duplicated" to (base.take(400) + base.take(400)),
            "out of order" to base.shuffled(kotlin.random.Random(4)),
            "gapped" to base.filterIndexed { index, _ -> index % 3 != 0 },
            "flat" to List(800) { CompassFixtures.bar(it, 1.1, 1.1, 1.1, 1.1) },
        ).forEach { (name, candles) ->
            assertTrue("$name produced an unusable status", analyze(candles).statusText.isNotBlank())
        }
    }

    @Test
    fun `the backtest entry points are bounds safe`() {
        val candles = CompassFixtures.walk(1_000)
        assertNull(engine.signalAt(CompassFixtures.SYMBOL, Timeframe.M5, candles, -1))
        assertNull(engine.signalAt(CompassFixtures.SYMBOL, Timeframe.M5, candles, 99_999))
        assertNull(engine.signalAt(CompassFixtures.SYMBOL, Timeframe.M5, emptyList(), 0))
    }

    @Test
    fun `analysis is deterministic`() {
        val candles = CompassFixtures.reverting(12_000, seed = 5)
        assertEquals(
            analyze(candles).calls.map { "${it.index}${it.verdict}" },
            analyze(candles).calls.map { "${it.index}${it.verdict}" },
        )
    }

    @Test
    fun `twenty thousand bars analyse within budget`() {
        val candles = CompassFixtures.walk(20_000, seed = 9)
        val elapsed = measureTimeMillis { analyze(candles) }
        assertTrue("20k bars must stay tractable, took ${elapsed}ms", elapsed < 120_000)
    }
}
