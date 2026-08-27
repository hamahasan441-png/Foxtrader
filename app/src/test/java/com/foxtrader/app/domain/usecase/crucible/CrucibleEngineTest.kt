package com.foxtrader.app.domain.usecase.crucible

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/** The engine end to end: what survives a search, and what must not. */
class CrucibleEngineTest {

    private val engine = CrucibleFixtures.engine()

    private fun analyze(candles: List<Candle>, config: CrucibleConfig = CrucibleConfig()) =
        engine.analyze(CrucibleFixtures.SYMBOL, Timeframe.M5, candles, config)

    // ------------------------------------------------------------------
    // The headline behaviour
    // ------------------------------------------------------------------

    @Test
    fun `a search over a random walk finds nothing`() {
        // Thousands of rules tested against data with no structure. Something
        // will look excellent in sample; nothing may be published.
        val analysis = analyze(CrucibleFixtures.walk(30_000, seed = 1), CrucibleConfig.intraday().copy(minAccuracy = 0.80))

        assertTrue("the search must actually have run", analysis.rulesTested > 100)
        assertTrue("the search must have observations", analysis.observations > 400)
        assertTrue(
            "a rule was published from a random walk: ${analysis.statusText}",
            analysis.findings.isEmpty() && analysis.signals.isEmpty(),
        )
    }

    @Test
    fun `the effective sample is always smaller than the raw count`() {
        // Outcomes overlap, so they are never as many facts as they are rows.
        val analysis = analyze(CrucibleFixtures.walk(20_000, seed = 3))
        assertTrue(analysis.observations > 0)
        assertTrue(
            "overlapping outcomes were counted as independent: " +
                "${analysis.effectiveObservations} of ${analysis.observations}",
            analysis.effectiveObservations < analysis.observations,
        )
        assertTrue(analysis.effectiveObservations > 0.0)
    }

    @Test
    fun `overfitting is measured and reported on every run`() {
        val analysis = analyze(CrucibleFixtures.walk(20_000, seed = 5))
        assertTrue("the search size must be reported", analysis.overfitting.rulesTested > 0)
        assertTrue("a verdict must always be given", analysis.overfitting.verdict.isNotBlank())
        analysis.overfitting.probability?.let {
            assertTrue("probability must be a probability", it in 0.0..1.0)
        }
    }

    @Test
    fun `nothing is published when the search is judged to be overfitting`() {
        // Forcing the tolerance to zero means no search can ever be trusted,
        // and the engine must then publish nothing regardless of what it found.
        val analysis = analyze(
            CrucibleFixtures.reverting(20_000, seed = 1),
            CrucibleConfig.intraday().copy(maxOverfittingProbability = 0.0, minAccuracy = 0.5),
        )
        assertTrue(
            "findings survived a search declared untrustworthy",
            analysis.findings.isEmpty() && analysis.signals.isEmpty(),
        )
        assertTrue(analysis.statusText.contains("withheld", ignoreCase = true))
    }

    @Test
    fun `a stricter false discovery rate never publishes more`() {
        val candles = CrucibleFixtures.reverting(24_000, seed = 1)
        fun count(rate: Double) = analyze(
            candles,
            CrucibleConfig.intraday().copy(minAccuracy = 0.55, falseDiscoveryRate = rate),
        ).findings.size

        val loose = count(0.20)
        val strict = count(0.01)
        assertTrue("a stricter false discovery rate admitted more", strict <= loose)
    }

    @Test
    fun `raising the required accuracy never publishes more`() {
        val candles = CrucibleFixtures.reverting(24_000, seed = 1)
        val counts = listOf(0.50, 0.60, 0.75, 0.90).map { required ->
            analyze(candles, CrucibleConfig.intraday().copy(minAccuracy = required)).findings.size
        }
        assertEquals("a stricter requirement admitted more", counts.sortedDescending(), counts)
    }

    // ------------------------------------------------------------------
    // What the two targets reveal
    // ------------------------------------------------------------------

    @Test
    fun `movement clears eighty percent where direction cannot`() {
        // The engine's central finding, made concrete and asserted rather than
        // argued. On a series whose volatility clusters but whose direction is
        // noise, the identical search reaches opposite conclusions about the
        // two questions — at the same 80% bar.
        val candles = CrucibleFixtures.clusteredVolatility(30_000, seed = 1)

        // The 80% bar is the comparison being made, so both sides ask for it.
        val direction = analyze(
            candles,
            CrucibleConfig.intraday().copy(target = CrucibleTarget.DIRECTION, minAccuracy = 0.80),
        )
        val movement = analyze(
            candles,
            CrucibleConfig.intraday().copy(target = CrucibleTarget.MOVEMENT, minAccuracy = 0.80),
        )

        assertTrue(
            "direction must not be predictable here: ${direction.statusText}",
            direction.findings.isEmpty(),
        )

        assertTrue(
            "movement must survive the same 80% bar: ${movement.statusText}",
            movement.findings.isNotEmpty(),
        )
        val best = movement.findings.first().outOfSample
        assertTrue("the surviving rule must clear 80% out of sample", best.accuracy!! >= 0.80)
        assertTrue("and must beat the base rate by a real margin", best.lift!! > 0.1)
        assertTrue(
            "the movement question must not be degenerate — a 99% base rate asks nothing",
            movement.baseRate!! < 0.85,
        )
        assertTrue(
            "and the search itself must hold up: ${movement.overfitting.verdict}",
            movement.overfitting.probability!! <= CrucibleConfig().maxOverfittingProbability,
        )
    }

    @Test
    fun `the same search finds nothing on a walk at the same bar`() {
        // The control. If the movement result above appeared here too, it would
        // be a property of the method rather than of the data.
        val analysis = analyze(
            CrucibleFixtures.walk(30_000, seed = 1),
            CrucibleConfig.intraday().copy(target = CrucibleTarget.MOVEMENT, minAccuracy = 0.80),
        )
        assertTrue(
            "a structureless series produced an 80% rule: ${analysis.statusText}",
            analysis.findings.isEmpty(),
        )
    }

    @Test
    fun `a movement finding never becomes a directional arrow`() {
        // A movement rule says a move is coming, not which way. Turning it into
        // an arrow would invent the half the rule explicitly refused to call.
        val analysis = analyze(
            CrucibleFixtures.clusteredVolatility(24_000, seed = 2),
            CrucibleConfig.intraday().copy(target = CrucibleTarget.MOVEMENT, minAccuracy = 0.5),
        )
        assertTrue(
            "a movement rule was published as a direction signal",
            analysis.signals.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Shape and robustness
    // ------------------------------------------------------------------

    @Test
    fun `every finding reports both its out of sample and in sample scores`() {
        val analysis = analyze(
            CrucibleFixtures.reverting(24_000, seed = 1),
            CrucibleConfig.intraday().copy(minAccuracy = 0.55),
        )
        analysis.findings.forEach {
            assertTrue("a finding must carry out-of-sample evidence", it.outOfSample.accuracy != null)
            assertTrue(
                "a finding must rest on enough independent evidence",
                it.outOfSample.effectiveSamples >= CrucibleConfig().minEffectiveSample,
            )
            assertTrue("a finding must beat its base rate", it.outOfSample.lift!! > 0.0)
            assertTrue("a finding must name what it is", it.rule.description.isNotBlank())
            assertTrue("the in-sample comparison must be shown", it.reasons.any { r -> r.contains("In sample") })
        }
    }

    @Test
    fun `published signals are traded at the barrier they were measured against`() {
        val candles = CrucibleFixtures.reverting(24_000, seed = 1)
        val config = CrucibleConfig.intraday().copy(minAccuracy = 0.55)
        val fast = engine.backtestFunction(CrucibleFixtures.SYMBOL, Timeframe.M5, candles, config)

        analyze(candles, config).signals.take(20).forEach { signal ->
            val strategy = fast(candles.subList(0, signal.index + 1), signal.index)
            assertTrue("a published signal produced no tradeable form", strategy != null)
            val reward = kotlin.math.abs(strategy!!.takeProfit - strategy.entry)
            val risk = kotlin.math.abs(strategy.entry - strategy.stopLoss)
            assertEquals("the barrier must stay symmetric", reward, risk, reward * 1e-9)
        }
    }

    @Test
    fun `each preset is internally consistent`() {
        CruciblePreset.entries.forEach { preset ->
            val config = CrucibleConfig.forPreset(preset)
            assertEquals(preset, config.preset)
            assertTrue(
                "the embargo must at least cover the horizon it protects against",
                config.embargoBars >= config.horizonBars,
            )
        }
        assertTrue(CrucibleConfig.scalping().horizonBars < CrucibleConfig.swing().horizonBars)
    }

    @Test
    fun `analysis is deterministic`() {
        val candles = CrucibleFixtures.reverting(12_000, seed = 4)
        assertEquals(
            analyze(candles).findings.map { it.rule.key },
            analyze(candles).findings.map { it.rule.key },
        )
    }

    @Test
    fun `degenerate and malformed series never throw`() {
        val base = CrucibleFixtures.walk(3_000)
        mapOf(
            "empty" to emptyList(),
            "single" to base.take(1),
            "below the minimum" to base.take(500),
            "duplicated" to (base.take(1_500) + base.take(1_500)),
            "out of order" to base.shuffled(kotlin.random.Random(6)),
            "gapped" to base.filterIndexed { index, _ -> index % 3 != 0 },
            "flat" to List(3_000) { CrucibleFixtures.bar(it, 1.1, 1.1, 1.1, 1.1) },
        ).forEach { (name, candles) ->
            assertTrue("$name produced an unusable status", analyze(candles).statusText.isNotBlank())
        }
    }

    @Test
    fun `the backtest function is bounds safe`() {
        val candles = CrucibleFixtures.walk(3_000)
        val fast = engine.backtestFunction(CrucibleFixtures.SYMBOL, Timeframe.M5, candles)
        assertEquals(null, fast(candles, -1))
        assertEquals(null, fast(candles, 99_999))
        assertEquals(null, fast(emptyList(), 0))
    }

    @Test
    fun `a full search stays within budget`() {
        val candles = CrucibleFixtures.walk(20_000, seed = 8)
        val elapsed = measureTimeMillis { analyze(candles) }
        assertTrue("a search over 20k bars must remain tractable, took ${elapsed}ms", elapsed < 300_000)
    }
}
