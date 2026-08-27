package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.apex.model.ApexOutcome
import com.foxtrader.app.domain.usecase.apex.model.ApexPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The precision gate: the part that decides whether a threshold has actually
 * been earned. Driven directly with constructed outcomes, so the decision rule
 * is tested exactly rather than through whatever a synthetic series happens to
 * produce.
 */
class ApexGateTest {

    // ------------------------------------------------------------------
    // The statistics
    // ------------------------------------------------------------------

    @Test
    fun `a short lucky run does not clear a high threshold`() {
        // Four wins from five is "80%". Its lower bound is nowhere near it, and
        // that gap is the entire reason the bound is what the gate reads.
        val precision = ApexPrecision.of(
            List(4) { ApexOutcome.WIN to 1.5 } + listOf(ApexOutcome.LOSS to -1.0),
        )

        assertEquals(0.8, precision.hitRate!!, 1e-9)
        assertTrue(
            "the bound on five trades must be far below the raw rate",
            precision.hitRateLowerBound!! < 0.5,
        )
        assertTrue(
            "the raw rate would have cleared 80%",
            precision.meets(0.80, minSample = 5, useLowerBound = false),
        )
        assertTrue(
            "the bound must refuse it",
            !precision.meets(0.80, minSample = 5, useLowerBound = true),
        )
    }

    @Test
    fun `a long record at the same rate does clear it`() {
        val precision = ApexPrecision.of(
            List(180) { ApexOutcome.WIN to 1.5 } + List(20) { ApexOutcome.LOSS to -1.0 },
        )
        assertEquals(0.9, precision.hitRate!!, 1e-9)
        assertTrue(
            "200 trades at 90% should support an 80% claim",
            precision.meets(0.80, minSample = 30, useLowerBound = true),
        )
    }

    @Test
    fun `the wilson bound stays inside zero and one at the extremes`() {
        assertEquals(0.0, ApexPrecision.wilsonLowerBound(0, 10)!!, 1e-9)
        assertTrue(ApexPrecision.wilsonLowerBound(10, 10)!! in 0.0..1.0)
        assertTrue(ApexPrecision.wilsonLowerBound(1, 1)!! in 0.0..1.0)
        assertNull(ApexPrecision.wilsonLowerBound(0, 0))
    }

    @Test
    fun `expectancy is reported alongside the rate and can contradict it`() {
        // Nine wins in ten at a fifth of the risk still loses money. A gate on
        // the rate alone would wave this through, which is why it never travels
        // without expectancy attached.
        val precision = ApexPrecision.of(
            List(9) { ApexOutcome.WIN to 0.1 } + listOf(ApexOutcome.LOSS to -1.0),
        )
        assertEquals(0.9, precision.hitRate!!, 1e-9)
        assertTrue("a 90% win rate here is still a losing system", precision.expectancyR!! < 0.0)
        assertTrue("and the profit factor says so too", precision.profitFactor!! < 1.0)
    }

    @Test
    fun `open and expired trades are not counted as either outcome`() {
        val precision = ApexPrecision.of(
            listOf(
                ApexOutcome.WIN to 1.5,
                ApexOutcome.LOSS to -1.0,
                ApexOutcome.OPEN to 0.0,
                ApexOutcome.EXPIRED to 0.0,
            ),
        )
        assertEquals("only resolved trades are evidence", 2, precision.resolved)
    }

    // ------------------------------------------------------------------
    // The walk-forward rule
    // ------------------------------------------------------------------

    @Test
    fun `the record consulted at a bar contains only trades resolved before it`() {
        // Losers early, winners later. Judged from the end the record looks
        // excellent; judged honestly at bar 100 it is nothing but losses.
        val candidates = List(10) { ApexFixtures.candidate(index = it * 5, outcome = ApexOutcome.LOSS, resolvedIndex = it * 5 + 1) } +
            List(10) { ApexFixtures.candidate(index = 200 + it * 5, outcome = ApexOutcome.WIN, resolvedIndex = 200 + it * 5 + 1) }

        val early = ApexOutcomeLedger.precisionAt(candidates, asOfIndex = 100, window = 60)
        assertEquals(10, early.resolved)
        assertEquals("the future must not leak backwards", 0.0, early.hitRate!!, 1e-9)

        val late = ApexOutcomeLedger.precisionAt(candidates, asOfIndex = 10_000, window = 60)
        assertEquals(20, late.resolved)
        assertEquals(0.5, late.hitRate!!, 1e-9)
    }

    @Test
    fun `a trade resolving on the very bar being decided is not yet evidence`() {
        val candidates = listOf(
            ApexFixtures.candidate(index = 10, outcome = ApexOutcome.WIN, resolvedIndex = 50),
        )
        assertEquals(
            "resolving at bar 50 is not known when bar 50 is being decided",
            0,
            ApexOutcomeLedger.precisionAt(candidates, asOfIndex = 50, window = 60).resolved,
        )
        assertEquals(1, ApexOutcomeLedger.precisionAt(candidates, asOfIndex = 51, window = 60).resolved)
    }

    @Test
    fun `the window keeps only the most recent resolved trades`() {
        val old = List(40) { ApexFixtures.candidate(index = it, outcome = ApexOutcome.LOSS, resolvedIndex = it + 1) }
        val recent = List(10) { ApexFixtures.candidate(index = 500 + it, outcome = ApexOutcome.WIN, resolvedIndex = 500 + it + 1) }

        val windowed = ApexOutcomeLedger.precisionAt(old + recent, asOfIndex = 10_000, window = 10)
        assertEquals(10, windowed.resolved)
        assertEquals("only the last ten count", 1.0, windowed.hitRate!!, 1e-9)
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    @Test
    fun `a bar containing both levels is recorded as a loss`() {
        // Bar data cannot say which came first. Assuming the good one is how a
        // backtest quietly flatters itself.
        val candles = listOf(
            ApexFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
            ApexFixtures.bar(1, 1.1000, 1.1020, 1.0980, 1.1000),
        )
        val (outcome, index) = ApexOutcomeLedger.resolve(
            candles, index = 0, direction = Direction.BULLISH,
            entry = 1.1000, stop = 1.0990, target = 1.1015, maxHoldBars = 10,
        )
        assertEquals(ApexOutcome.LOSS, outcome)
        assertEquals(1, index)
    }

    @Test
    fun `a clean target and a clean stop resolve as themselves`() {
        val win = ApexOutcomeLedger.resolve(
            listOf(
                ApexFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
                ApexFixtures.bar(1, 1.1000, 1.1020, 1.0995, 1.1015),
            ),
            0, Direction.BULLISH, 1.1000, 1.0990, 1.1015, 10,
        )
        assertEquals(ApexOutcome.WIN, win.first)

        val loss = ApexOutcomeLedger.resolve(
            listOf(
                ApexFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
                ApexFixtures.bar(1, 1.1000, 1.1005, 1.0985, 1.0990),
            ),
            0, Direction.BULLISH, 1.1000, 1.0990, 1.1015, 10,
        )
        assertEquals(ApexOutcome.LOSS, loss.first)
    }

    @Test
    fun `a trade that never resolves expires rather than counting`() {
        val flat = (0..30).map { ApexFixtures.bar(it, 1.1000, 1.1001, 1.0999, 1.1000) }
        val (outcome, _) = ApexOutcomeLedger.resolve(
            flat, 0, Direction.BULLISH, 1.1000, 1.0990, 1.1015, maxHoldBars = 5,
        )
        assertEquals(ApexOutcome.EXPIRED, outcome)
    }

    @Test
    fun `short resolution mirrors long resolution`() {
        val win = ApexOutcomeLedger.resolve(
            listOf(
                ApexFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
                ApexFixtures.bar(1, 1.1000, 1.1005, 1.0980, 1.0985),
            ),
            0, Direction.BEARISH, 1.1000, 1.1010, 1.0985, 10,
        )
        assertEquals(ApexOutcome.WIN, win.first)
    }

    @Test
    fun `resolution is bounds safe`() {
        val candles = ApexFixtures.walk(50)
        assertEquals(
            ApexOutcome.OPEN,
            ApexOutcomeLedger.resolve(candles, -1, Direction.BULLISH, 1.1, 1.09, 1.11, 10).first,
        )
        assertEquals(
            "a degenerate stop cannot produce a measurable trade",
            ApexOutcome.EXPIRED,
            ApexOutcomeLedger.resolve(candles, 0, Direction.BULLISH, 1.1, 1.1, 1.11, 10).first,
        )
    }

    // ------------------------------------------------------------------
    // Warmup
    // ------------------------------------------------------------------

    @Test
    fun `an empty record supports no claim at all`() {
        val empty = ApexPrecision.EMPTY
        assertNull(empty.hitRate)
        assertNull(empty.hitRateLowerBound)
        assertTrue("nothing can be met on no evidence", !empty.meets(0.0, minSample = 1))
    }

    @Test
    fun `the defaults report the record rather than withholding on it`() {
        // Reversed deliberately. Withholding until the record supported an 80%
        // claim was honest and made the study draw nothing at any realistic
        // chart size. The measurement is still made and still travels with
        // every signal; it no longer decides whether one appears.
        assertEquals(WarmupPolicy.PUBLISH_UNMEASURED, ApexConfig().warmupPolicy)
        assertEquals("the gate reports by default rather than blocking", 0.0, ApexConfig().minHitRate, 1e-9)
        assertNotNull(ApexConfig().members)
    }

    @Test
    fun `the strict gate is still available and still refuses a short record`() {
        // Turning it back on must restore exactly the old behaviour.
        val strict = ApexConfig(minHitRate = 0.80, minResolvedSample = 30, useConfidenceBound = true)
        val lucky = ApexPrecision.of(List(4) { ApexOutcome.WIN to 1.5 } + listOf(ApexOutcome.LOSS to -1.0))

        assertTrue(
            "four wins from five must not clear an 80% bar",
            !lucky.meets(strict.minHitRate, strict.minResolvedSample, strict.useConfidenceBound),
        )
    }
}
