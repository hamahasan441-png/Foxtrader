package com.foxtrader.app.domain.usecase.crucible

import com.foxtrader.app.domain.usecase.crucible.model.CrucibleEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corrections that make a mined finding believable. Driven directly,
 * because these are the parts a synthetic price series cannot be trusted to
 * exercise in the way that matters.
 */
class CrucibleStatisticsTest {

    // ------------------------------------------------------------------
    // Overlapping outcomes
    // ------------------------------------------------------------------

    @Test
    fun `an observation overlapping nothing is fully unique`() {
        val spans = (0 until 10).map { it * 100 to it * 100 + 10 }
        val uniqueness = CrucibleObservations.uniquenessOf(spans)
        uniqueness.forEach { assertEquals(1.0, it, 1e-9) }
    }

    @Test
    fun `heavy overlap collapses toward a single observation`() {
        // Fifty observations one bar apart, each spanning fifty bars: almost
        // everything each of them says was already said by its neighbours.
        val spans = (0 until 50).map { it to it + 50 }
        val uniqueness = CrucibleObservations.uniquenessOf(spans)

        assertTrue("overlap must reduce uniqueness", uniqueness.average() < 0.5)
        assertTrue(
            "fifty overlapping outcomes must not count as fifty facts, summed to ${uniqueness.sum()}",
            uniqueness.sum() < 25.0,
        )
        uniqueness.forEach { assertTrue("uniqueness must stay within 0..1", it in 0.0..1.0) }
    }

    @Test
    fun `wider spacing recovers independence`() {
        val tight = CrucibleObservations.uniquenessOf((0 until 40).map { it * 2 to it * 2 + 40 }).sum()
        val loose = CrucibleObservations.uniquenessOf((0 until 40).map { it * 60 to it * 60 + 40 }).sum()
        assertTrue("spacing observations out must increase independent evidence", loose > tight)
        assertEquals("fully separated observations are fully independent", 40.0, loose, 1e-6)
    }

    @Test
    fun `bounds are computed on the effective sample not the raw count`() {
        // The same 90% accuracy, once from independent observations and once
        // from heavily overlapping ones. The overlapping version knows far
        // less and its bound must say so.
        val hits = List(100) { it % 10 != 0 }
        val independent = CrucibleEvidence.of(hits, List(100) { 1.0 }, 0.5, 0.95)
        val overlapping = CrucibleEvidence.of(hits, List(100) { 0.1 }, 0.5, 0.95)

        assertEquals(independent.accuracy!!, overlapping.accuracy!!, 1e-9)
        assertTrue(
            "overlapping observations must not buy the same confidence",
            overlapping.accuracyLowerBound!! < independent.accuracyLowerBound!!,
        )
        assertEquals(10.0, overlapping.effectiveSamples, 1e-9)
        assertTrue(
            "and their p-value must be weaker too",
            overlapping.pValue!! > independent.pValue!!,
        )
    }

    // ------------------------------------------------------------------
    // Significance
    // ------------------------------------------------------------------

    @Test
    fun `beating the base rate by luck alone is not significant`() {
        // 55% over 40 effective observations against a 50% base rate is the
        // kind of result that looks like something and is not.
        val evidence = CrucibleEvidence.of(List(40) { it % 20 < 11 }, List(40) { 1.0 }, 0.5, 0.95)
        assertTrue("a marginal result must not read as significant", evidence.pValue!! > 0.10)
    }

    @Test
    fun `a large clear margin is significant`() {
        val evidence = CrucibleEvidence.of(List(200) { it % 10 != 0 }, List(200) { 1.0 }, 0.5, 0.95)
        assertTrue("90% over 200 against a coin flip must register", evidence.pValue!! < 1e-6)
    }

    @Test
    fun `matching the base rate exactly is not evidence of anything`() {
        val evidence = CrucibleEvidence.of(List(100) { it % 2 == 0 }, List(100) { 1.0 }, 0.5, 0.95)
        assertEquals(0.0, evidence.lift!!, 1e-9)
        assertTrue("scoring the base rate is not a discovery", evidence.pValue!! > 0.4)
    }

    @Test
    fun `the normal cdf and quantile agree with known values`() {
        assertEquals(0.5, CrucibleEvidence.standardNormalCdf(0.0), 1e-6)
        assertEquals(0.9750, CrucibleEvidence.standardNormalCdf(1.959964), 1e-4)
        assertEquals(0.0250, CrucibleEvidence.standardNormalCdf(-1.959964), 1e-4)
        assertEquals(1.959964, CrucibleEvidence.normalQuantile(0.975), 1e-4)
        assertEquals(0.0, CrucibleEvidence.normalQuantile(0.5), 1e-6)
    }

    @Test
    fun `degenerate evidence is empty rather than confident`() {
        assertEquals(CrucibleEvidence.EMPTY, CrucibleEvidence.of(emptyList(), emptyList(), 0.5, 0.95))
        assertNull(CrucibleEvidence.wilsonLowerBound(0.0, 0.0, 0.95))
        assertNull(CrucibleEvidence.binomialTailAbove(1.0, 0.0, 0.5))
        assertTrue(CrucibleEvidence.wilsonLowerBound(10.0, 10.0, 0.95)!! in 0.0..1.0)
    }

    // ------------------------------------------------------------------
    // False discovery rate
    // ------------------------------------------------------------------

    @Test
    fun `testing many rules on noise yields no discoveries`() {
        // A thousand rules, each a coin flip: p-values spread uniformly. Naive
        // testing at 5% would "discover" about fifty of them.
        val random = kotlin.random.Random(7)
        val noise = List(1_000) { random.nextDouble() }
        val threshold = CrucibleValidation.benjaminiHochbergThreshold(noise, 0.05)

        val discoveries = noise.count { threshold != null && it <= threshold }
        assertTrue(
            "a false discovery rate of 5% must not admit dozens of coin flips, admitted $discoveries",
            discoveries <= 2,
        )
    }

    @Test
    fun `genuinely strong findings still get through`() {
        val real = List(10) { 1e-8 } + List(990) { kotlin.random.Random(3).nextDouble() }
        val threshold = CrucibleValidation.benjaminiHochbergThreshold(real, 0.05)
        assertTrue("real findings were suppressed", threshold != null && threshold >= 1e-8)
    }

    @Test
    fun `a stricter false discovery rate never admits more`() {
        val values = List(500) { kotlin.random.Random(11).nextDouble() * (it % 7) / 7.0 }
        val loose = CrucibleValidation.benjaminiHochbergThreshold(values, 0.20) ?: 0.0
        val strict = CrucibleValidation.benjaminiHochbergThreshold(values, 0.01) ?: 0.0
        assertTrue("a stricter rate admitted more", strict <= loose)
    }

    @Test
    fun `an empty search discovers nothing`() {
        assertNull(CrucibleValidation.benjaminiHochbergThreshold(emptyList(), 0.05))
    }
}
