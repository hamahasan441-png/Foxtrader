package com.foxtrader.app.domain.usecase.crucible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Purging, embargo, and the measurement of the search's own honesty. */
class CrucibleValidationTest {

    @Test
    fun `no training observation may overlap its test fold`() {
        // The leak this exists to stop: an outcome that starts before the test
        // fold and finishes inside it was written by the bars it is about to be
        // tested on.
        val observations = CrucibleFixtures.observations(count = 400, span = 30, stride = 5)
        val folds = CrucibleValidation.split(observations, folds = 8, embargoBars = 30)

        assertTrue("the split produced no folds", folds.isNotEmpty())
        folds.forEach { fold ->
            val testFrom = fold.test.first().index
            val testTo = fold.test.last().decidedIndex
            fold.train.forEach {
                assertTrue(
                    "a training outcome overlapped the test fold: ${it.index}..${it.decidedIndex} vs $testFrom..$testTo",
                    it.decidedIndex < testFrom || it.index > testTo,
                )
            }
        }
    }

    @Test
    fun `the embargo clears the shadow just after the test fold`() {
        val observations = CrucibleFixtures.observations(count = 400, span = 20, stride = 5)
        val embargo = 100
        val folds = CrucibleValidation.split(observations, folds = 8, embargoBars = embargo)

        folds.forEach { fold ->
            val testTo = fold.test.last().decidedIndex
            fold.train.forEach {
                assertTrue(
                    "a training observation started inside the embargo at ${it.index} (test ended $testTo)",
                    it.index <= testTo || it.index > testTo + embargo,
                )
            }
        }
    }

    @Test
    fun `purging costs training data and that is the point`() {
        val observations = CrucibleFixtures.observations(count = 400, span = 60, stride = 2)
        val purged = CrucibleValidation.split(observations, folds = 8, embargoBars = 60)
        val unpurged = CrucibleValidation.split(observations, folds = 8, embargoBars = 0)

        val purgedTrain = purged.sumOf { it.train.size }
        val unpurgedTrain = unpurged.sumOf { it.train.size }
        assertTrue("the embargo removed nothing", purgedTrain < unpurgedTrain)
    }

    @Test
    fun `every observation appears in exactly one test fold`() {
        val observations = CrucibleFixtures.observations(count = 400, span = 10, stride = 5)
        val folds = CrucibleValidation.split(observations, folds = 8, embargoBars = 10)
        val tested = folds.flatMap { it.test }.map { it.index }
        assertEquals("folds must partition the observations", tested.size, tested.distinct().size)
        assertEquals(observations.size, tested.size)
    }

    @Test
    fun `too little data produces no folds rather than bad ones`() {
        assertTrue(CrucibleValidation.split(emptyList(), 8, 10).isEmpty())
        assertTrue(CrucibleValidation.split(CrucibleFixtures.observations(4, 10, 5), 8, 10).isEmpty())
    }

    // ------------------------------------------------------------------
    // Overfitting
    // ------------------------------------------------------------------

    @Test
    fun `a search over pure noise is reported as overfitting`() {
        // Every rule is a coin flip, independently per block. The in-sample
        // winner is the luckiest one, and luck does not repeat, so it should
        // land below median about half the time.
        val random = Random(4)
        val rules = 300
        val blocks = 8
        val matched = Array(rules) { IntArray(blocks) { 60 } }
        val hits = Array(rules) { IntArray(blocks) { (0 until 60).count { random.nextBoolean() } } }

        val report = CrucibleValidation.overfittingProbability(matched, hits, blocks)

        assertTrue("overfitting was not measured", report.probability != null)
        assertTrue(
            "a noise search should look like overfitting, measured ${report.probability}",
            report.probability!! > 0.3,
        )
        assertTrue(report.trials > 0)
    }

    @Test
    fun `a search with one genuinely dominant rule holds up`() {
        // Rule zero is simply better in every block, so the winner in sample is
        // the winner out of sample.
        val rules = 60
        val blocks = 8
        val matched = Array(rules) { IntArray(blocks) { 60 } }
        val hits = Array(rules) { rule -> IntArray(blocks) { if (rule == 0) 54 else 24 + rule % 5 } }

        val report = CrucibleValidation.overfittingProbability(matched, hits, blocks)
        assertEquals("a genuinely dominant rule must not read as overfitting", 0.0, report.probability!!, 1e-9)
        assertTrue(report.verdict.contains("holds up"))
    }

    @Test
    fun `a rule with almost no support cannot win a split`() {
        // One observation, one hit, a perfect score: without a support floor
        // this would win every split and make the measurement meaningless.
        val blocks = 8
        val matched = Array(3) { rule -> IntArray(blocks) { if (rule == 0) 1 else 60 } }
        val hits = Array(3) { rule -> IntArray(blocks) { if (rule == 0) 1 else 30 } }

        val report = CrucibleValidation.overfittingProbability(matched, hits, blocks, minSupport = 20)
        assertTrue("the measurement must still run", report.probability != null)
    }

    @Test
    fun `overfitting is not claimed when it cannot be measured`() {
        val report = CrucibleValidation.overfittingProbability(
            matched = Array(1) { IntArray(8) },
            hits = Array(1) { IntArray(8) },
            blocks = 8,
        )
        assertNull(report.probability)
        assertTrue(report.verdict.isNotBlank())
    }

    @Test
    fun `the measurement is deterministic`() {
        val matched = Array(40) { IntArray(8) { 50 } }
        val hits = Array(40) { rule -> IntArray(8) { block -> (rule * 13 + block * 7) % 50 } }
        assertEquals(
            CrucibleValidation.overfittingProbability(matched, hits, 8).probability,
            CrucibleValidation.overfittingProbability(matched, hits, 8).probability,
        )
    }
}
