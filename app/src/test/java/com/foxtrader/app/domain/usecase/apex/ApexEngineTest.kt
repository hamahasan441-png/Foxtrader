package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.apex.model.ApexOutcome
import com.foxtrader.app.domain.usecase.apex.model.ApexSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * The engine end to end: consensus, the gate in place, and the behaviour that
 * matters most — going quiet when the record does not support the threshold.
 */
class ApexEngineTest {

    private val engine = ApexFixtures.engine()

    private fun analyze(candles: List<Candle>, config: ApexConfig = ApexConfig()) =
        engine.analyze(ApexFixtures.SYMBOL, Timeframe.M5, candles, config)

    private fun fingerprint(signal: ApexSignal) = listOf(
        signal.direction.name,
        signal.timestamp,
        signal.index,
        signal.candidate.members.joinToString(",") { it.name },
    ).joinToString("|")

    // ------------------------------------------------------------------
    // The headline behaviour
    // ------------------------------------------------------------------

    @Test
    fun `on structureless data the engine publishes nothing`() {
        // A random walk has no edge to find. Any engine that keeps firing here
        // is fitting noise, and going quiet is the whole point of the gate.
        val analysis = analyze(ApexFixtures.walk(12_000, seed = 1))

        assertTrue("the members should still be producing votes", analysis.votes.isNotEmpty())
        assertTrue(
            "nothing may be published on a record that cannot support the threshold",
            analysis.signals.isEmpty(),
        )
    }

    @Test
    fun `the gate refuses a threshold the record cannot support at any level`() {
        val candles = ApexFixtures.walk(12_000, seed = 2)
        listOf(0.5, 0.65, 0.80, 0.95).forEach { threshold ->
            assertTrue(
                "a random walk cannot earn $threshold",
                analyze(candles, ApexConfig.intraday().copy(minHitRate = threshold)).signals.isEmpty(),
            )
        }
    }

    @Test
    fun `on data with a real edge the measured record reflects it`() {
        // The same engine on a channel that genuinely reverts: the method's own
        // measured record should be strong and its expectancy positive, whether
        // or not the sample is yet long enough to publish under.
        val analysis = analyze(ApexFixtures.reverting(30_000, seed = 1))
        val precision = analysis.methodPrecision

        assertTrue("the fixture must produce candidates", analysis.candidates.isNotEmpty())
        assertNotNull("they must resolve", precision.hitRate)
        assertTrue(
            "an edge this strong should measure well above a coin flip, was ${precision.hitRate}",
            precision.hitRate!! > 0.6,
        )
        assertTrue(
            "and should carry positive expectancy, was ${precision.expectancyR}",
            precision.expectancyR!! > 0.0,
        )
    }

    @Test
    fun `the gate opens once the record is long enough to support the threshold`() {
        // The sample requirement is lowered here on purpose: the point under
        // test is that the gate opens when its condition is met, not how many
        // bars a particular fixture needs to get there.
        val analysis = analyze(
            ApexFixtures.reverting(30_000, seed = 1),
            ApexConfig.intraday().copy(minHitRate = 0.5, minResolvedSample = 8, useConfidenceBound = false),
        )
        assertTrue("the gate must be able to open at all", analysis.signals.isNotEmpty())
        analysis.signals.forEach {
            assertTrue(
                "every published signal must carry the record it was published under",
                it.precisionAtPublication.resolved >= 8,
            )
            assertTrue(
                "and that record must have met the threshold",
                it.precisionAtPublication.hitRate!! >= 0.5,
            )
        }
    }

    @Test
    fun `raising the threshold never publishes more`() {
        val candles = ApexFixtures.reverting(30_000, seed = 1)
        fun count(threshold: Double) = analyze(
            candles,
            ApexConfig.intraday().copy(minHitRate = threshold, minResolvedSample = 8, useConfidenceBound = false),
        ).signals.size

        val low = count(0.4)
        val mid = count(0.6)
        val high = count(0.9)
        assertTrue("the fixture must publish something to compare", low > 0)
        assertTrue("a higher bar admitted more", mid <= low)
        assertTrue("a higher bar still admitted more", high <= mid)
    }

    @Test
    fun `the confidence bound is never looser than the raw rate`() {
        val candles = ApexFixtures.reverting(30_000, seed = 1)
        fun count(bound: Boolean) = analyze(
            candles,
            ApexConfig.intraday().copy(minHitRate = 0.5, minResolvedSample = 8, useConfidenceBound = bound),
        ).signals.size

        assertTrue("gating on the bound admitted more than the raw rate", count(true) <= count(false))
    }

    // ------------------------------------------------------------------
    // Consensus
    // ------------------------------------------------------------------

    @Test
    fun `requiring more members never produces more candidates`() {
        val candles = ApexFixtures.walk(12_000, seed = 3)
        fun count(k: Int) = analyze(candles, ApexConfig.intraday().copy(minAgreeingMembers = k)).candidates.size

        val two = count(2)
        assertTrue("the fixture must produce candidates", two > 0)
        assertTrue("three agreeing produced more than two", count(3) <= two)
        assertTrue("four agreeing produced more than three", count(4) <= count(3))
    }

    @Test
    fun `a candidate always carries the required number of distinct members`() {
        val config = ApexConfig.intraday()
        analyze(ApexFixtures.walk(12_000, seed = 3), config).candidates.forEach {
            assertTrue(
                "a candidate formed without enough distinct members",
                it.members.size >= config.minAgreeingMembers,
            )
            assertEquals(
                "one member voted twice in the same candidate",
                it.votes.size,
                it.members.size,
            )
        }
    }

    @Test
    fun `a candidate is stamped on the last vote that completed the agreement`() {
        analyze(ApexFixtures.walk(12_000, seed = 3)).candidates.forEach {
            assertEquals(
                "the candidate is dated before its own agreement existed",
                it.votes.maxOf { vote -> vote.index },
                it.index,
            )
        }
    }

    @Test
    fun `disabling members can only reduce what the engine sees`() {
        val candles = ApexFixtures.walk(12_000, seed = 3)
        val all = analyze(candles, ApexConfig.intraday()).votes.size
        val two = analyze(
            candles,
            ApexConfig.intraday().copy(members = setOf(ApexMember.LIQUIDITY_SWEEP, ApexMember.VIRGIN_WICK)),
        ).votes.size

        assertTrue("the fixture must produce votes", all > 0)
        assertTrue("a smaller member set produced more votes", two <= all)
    }

    // ------------------------------------------------------------------
    // Non-repaint and geometry
    // ------------------------------------------------------------------

    @Test
    fun `analysis over a prefix equals the full run restricted to that prefix`() {
        // Non-repaint, with no slack allowed. Every signal the completed
        // history reports inside the prefix must appear in the prefix run, at
        // the same bar, from the same members: a cluster is stamped when its
        // agreement completes and later votes cannot move it, and the gate that
        // admitted it read only trades resolved before its own bar, all of
        // which are inside the prefix too.
        val candles = ApexFixtures.reverting(20_000, seed = 1)
        val config = ApexConfig.intraday().copy(minHitRate = 0.5, minResolvedSample = 8, useConfidenceBound = false)
        val full = analyze(candles, config)
        assertTrue("fixture must publish to compare", full.signals.isNotEmpty())

        listOf(12_000, 16_000).forEach { cutoff ->
            val replay = analyze(candles.take(cutoff), config)

            val expected = full.signals.filter { it.index < cutoff }.map(::fingerprint)
            val actual = replay.signals.filter { it.index < cutoff }.map(::fingerprint)

            assertTrue("the prefix must retain something to compare at $cutoff", expected.isNotEmpty())
            assertEquals("replay disagreed with completed history at $cutoff", expected, actual)
        }
    }

    @Test
    fun `candidate geometry is complete and correctly sided`() {
        analyze(ApexFixtures.reverting(20_000, seed = 1)).candidates.forEach {
            assertTrue(it.entry.isFinite() && it.entry > 0.0)
            assertTrue("risk must be positive", it.risk > 0.0)
            assertTrue(
                "reward must clear the configured floor",
                it.rewardMultiple >= ApexConfig().minRewardMultiple - 1e-9,
            )
            if (it.direction == com.foxtrader.app.domain.model.Direction.BULLISH) {
                assertTrue(it.stop < it.entry && it.target > it.entry)
            } else {
                assertTrue(it.stop > it.entry && it.target < it.entry)
            }
            if (it.outcome == ApexOutcome.WIN || it.outcome == ApexOutcome.LOSS) {
                assertNotNull("a resolved trade must carry its realised R", it.realisedR)
                assertTrue("resolution cannot precede entry", it.resolvedIndex!! > it.index)
            }
        }
    }

    @Test
    fun `every published signal has a distinct identity`() {
        val keys = analyze(
            ApexFixtures.reverting(30_000, seed = 1),
            ApexConfig.intraday().copy(minHitRate = 0.5, minResolvedSample = 8, useConfidenceBound = false),
        ).signals.map { it.key }
        assertEquals("duplicate signal keys were published", keys.size, keys.distinct().size)
    }

    @Test
    fun `analysis is deterministic across repeated runs`() {
        val candles = ApexFixtures.reverting(12_000, seed = 4)
        assertEquals(
            analyze(candles).candidates.map { it.index },
            analyze(candles).candidates.map { it.index },
        )
    }

    // ------------------------------------------------------------------
    // Presets, reliability, performance
    // ------------------------------------------------------------------

    @Test
    fun `each preset is internally consistent`() {
        ApexPreset.entries.forEach { preset ->
            val config = ApexConfig.forPreset(preset)
            assertEquals(preset, config.preset)
            assertTrue("a preset must enable members", config.members.isNotEmpty())
            assertTrue(config.minAgreeingMembers >= 1)
        }
        assertTrue(
            "scalping should hold trades for less time than intraday",
            ApexConfig.scalping().maxHoldBars < ApexConfig.intraday().maxHoldBars,
        )
        assertTrue(
            "scalping should reach for a smaller target than swing",
            ApexConfig.scalping().rewardMultiple < ApexConfig.swing().rewardMultiple,
        )
    }

    @Test
    fun `degenerate and malformed series never throw`() {
        val base = ApexFixtures.walk(600)
        val cases = mapOf(
            "empty" to emptyList(),
            "single" to base.take(1),
            "below the minimum" to base.take(100),
            "duplicate bars" to (base.take(300) + base.take(300)),
            "out of order" to base.shuffled(kotlin.random.Random(9)),
            "gapped" to base.filterIndexed { index, _ -> index % 3 != 0 },
            "flat" to List(600) { ApexFixtures.bar(it, 1.1, 1.1, 1.1, 1.1) },
        )
        cases.forEach { (name, candles) ->
            assertTrue("$name produced an unusable status", analyze(candles).statusText.isNotBlank())
        }
    }

    @Test
    fun `an empty member set is refused with a reason`() {
        val analysis = analyze(ApexFixtures.walk(1_000), ApexConfig(members = emptySet()))
        assertTrue(analysis.signals.isEmpty())
        assertTrue(analysis.statusText.contains("member", ignoreCase = true))
    }

    @Test
    fun `the single pass backtest equals bar by bar replay`() {
        // backtestFunction exists only because the two paths are equal. If they
        // ever diverge, the fast path is reporting trades a live run would not
        // have taken — the exact failure it must never have.
        //
        // Replaying every bar is what the fast path exists to avoid, so the
        // comparison targets the bars that carry the claim: every bar the fast
        // path publishes on (it must not invent one), and a spread of bars it
        // does not (it must not miss one).
        val candles = ApexFixtures.reverting(20_000, seed = 1)
        val config = ApexConfig.intraday().copy(minHitRate = 0.5, minResolvedSample = 8, useConfidenceBound = false)
        val fast = engine.backtestFunction(ApexFixtures.SYMBOL, Timeframe.M5, candles, config)

        val published = analyze(candles, config).signals.map { it.index }
        assertTrue("the fixture produced no signals to compare", published.isNotEmpty())

        val sampled = published + (ApexEngine.MIN_BARS until candles.size step 600).filter { it !in published }
        sampled.forEach { index ->
            val prefix = candles.subList(0, index + 1)
            val slow = engine.signalAt(ApexFixtures.SYMBOL, Timeframe.M5, prefix, index, config)
            assertEquals(
                "the two paths disagreed at bar $index",
                slow?.toString(),
                fast(prefix, index)?.toString(),
            )
        }
    }

    @Test
    fun `the backtest function is bounds safe`() {
        val candles = ApexFixtures.walk(1_000)
        assertNull(engine.signalAt(ApexFixtures.SYMBOL, Timeframe.M5, candles, -1))
        assertNull(engine.signalAt(ApexFixtures.SYMBOL, Timeframe.M5, candles, 9_999))
        assertNull(engine.signalAt(ApexFixtures.SYMBOL, Timeframe.M5, emptyList(), 0))
    }

    @Test
    fun `twenty thousand bars analyse within budget`() {
        val candles = ApexFixtures.walk(20_000, seed = 11)
        val elapsed = measureTimeMillis { analyze(candles) }
        assertTrue("20k bars must remain tractable, took ${elapsed}ms", elapsed < 60_000)
    }
}
