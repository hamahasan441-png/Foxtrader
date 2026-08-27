package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The probability estimate the whole guarantee rests on. */
class CompassScorerTest {

    private fun observation(signal: Double, right: Boolean) =
        CompassFixtures.features(signal) to if (right) CompassVerdict.RIGHT else CompassVerdict.WRONG

    @Test
    fun `an unfitted scorer commits to nothing`() {
        assertEquals(0.5, CompassScorer.UNINFORMED.probability(CompassFixtures.features(2.0)), 1e-9)
        assertEquals(0, CompassScorer.UNINFORMED.trainedOn)
    }

    @Test
    fun `it learns a real relationship`() {
        // A positive feature genuinely predicts a right call here.
        val data = (0 until 400).map { observation(if (it % 2 == 0) 1.0 else -1.0, it % 2 == 0) }
        val scorer = CompassScorer.fit(data)

        assertTrue(
            "the scorer failed to separate a perfectly separable signal",
            scorer.probability(CompassFixtures.features(1.0)) >
                scorer.probability(CompassFixtures.features(-1.0)),
        )
        assertEquals(400, scorer.trainedOn)
    }

    @Test
    fun `it stays near the base rate when nothing predicts anything`() {
        // Feature values are random and unrelated to the outcome. A scorer that
        // found confident opinions here would be fitting noise, and every
        // downstream guarantee would inherit that.
        val random = Random(3)
        val data = (0 until 400).map { observation(random.nextDouble() * 2 - 1, random.nextBoolean()) }
        val scorer = CompassScorer.fit(data)

        val spread = (-10..10).map { scorer.probability(CompassFixtures.features(it / 10.0)) }
        assertTrue(
            "the scorer invented an opinion from noise: ${spread.min()}..${spread.max()}",
            spread.max() - spread.min() < 0.25,
        )
        assertTrue("and it should sit near a coin flip", abs(spread.average() - 0.5) < 0.12)
    }

    @Test
    fun `its output is calibrated rather than merely ranked`() {
        // 70% of calls are right, with no usable feature. A calibrated model
        // must say "about 70%", not merely rank them in the right order — the
        // threshold is compared against this number directly.
        val data = (0 until 500).map { observation(0.0, it % 10 < 7) }
        val probability = CompassScorer.fit(data).probability(CompassFixtures.features(0.0))
        assertEquals("the estimate must match the frequency it was trained on", 0.7, probability, 0.05)
    }

    @Test
    fun `probabilities stay inside zero and one under extreme input`() {
        val data = (0 until 100).map { observation(1.0, true) }
        val scorer = CompassScorer.fit(data)
        listOf(1e6, -1e6, Double.MAX_VALUE, -Double.MAX_VALUE).forEach {
            val p = scorer.probability(CompassFixtures.features(it))
            assertTrue("probability escaped 0..1: $p", p in 0.0..1.0)
        }
    }

    @Test
    fun `malformed input is handled rather than trusted`() {
        val scorer = CompassScorer.fit((0 until 60).map { observation(1.0, it % 2 == 0) })
        assertEquals("a wrong-sized vector cannot be scored", 0.5, scorer.probability(DoubleArray(3)), 1e-9)

        val nan = CompassScorer.fit(listOf(DoubleArray(CompassFeatures.SIZE) { Double.NaN } to CompassVerdict.RIGHT))
        assertTrue(nan.probability(CompassFixtures.features(1.0)) in 0.0..1.0)
    }

    @Test
    fun `undecided calls teach it nothing`() {
        // Treating "no move" as a loss would train the scorer to avoid quiet
        // markets rather than wrong directions.
        val resolved = (0 until 100).map { observation(1.0, true) }
        val padded = resolved + List(100) { CompassFixtures.features(-1.0) to CompassVerdict.UNDECIDED }

        assertEquals(
            CompassScorer.fit(resolved).probability(CompassFixtures.features(1.0)),
            CompassScorer.fit(padded).probability(CompassFixtures.features(1.0)),
            1e-12,
        )
        assertEquals(100, CompassScorer.fit(padded).trainedOn)
    }

    @Test
    fun `fitting is deterministic`() {
        val data = (0 until 200).map { observation(if (it % 3 == 0) 1.0 else -0.5, it % 3 == 0) }
        assertEquals(
            CompassScorer.fit(data).probability(CompassFixtures.features(0.7)),
            CompassScorer.fit(data).probability(CompassFixtures.features(0.7)),
            0.0,
        )
    }

    @Test
    fun `an empty history produces the uninformed scorer`() {
        assertEquals(0, CompassScorer.fit(emptyList()).trainedOn)
        assertEquals(0.5, CompassScorer.fit(emptyList()).probability(CompassFixtures.features(1.0)), 1e-9)
    }
}
