package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.compass.model.CompassAccuracy
import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The threshold search. This is where an accuracy engine is usually broken, and
 * the failures are statistical rather than mechanical, so they are driven with
 * constructed outcomes instead of through a price series.
 */
class CompassCalibratorTest {

    /**
     * A scored call. Directions alternate unless a test says otherwise, so the
     * best constant-direction rule scores ~50% and any accuracy above it is
     * skill rather than drift.
     */
    private fun call(probability: Double, verdict: CompassVerdict, index: Int = 0) =
        CompassCalibrator.Scored(
            probability = probability,
            direction = if (index % 2 == 0) Direction.BULLISH else Direction.BEARISH,
            verdict = verdict,
        )

    /**
     * The strict reading, switched on.
     *
     * Judging a threshold by its Bonferroni-corrected confidence bound is the
     * honest reading of a small sample, and it is also what kept the study
     * silent on every real chart. It is now opt-in, so the tests that exercise
     * it ask for it.
     */
    private fun strict(config: CompassConfig = CompassConfig()) =
        config.copy(minAccuracy = 0.80, minLiftOverBaseRate = 0.05, minCalibrationSample = 40, useConfidenceBound = true)

    private fun run(
        scored: List<CompassCalibrator.Scored>,
        config: CompassConfig = CompassConfig(),
    ) = CompassCalibrator.calibrate(scored, strict(config))

    // ------------------------------------------------------------------
    // The trap this exists to avoid
    // ------------------------------------------------------------------

    @Test
    fun `no threshold is justified on pure noise however many are tried`() {
        // Random probabilities, coin-flip outcomes, 600 of them. Somewhere in a
        // grid of ten thresholds there will be a subset that looks excellent.
        // Publishing it is exactly the failure the correction exists to stop.
        val random = Random(11)
        val noise = (0 until 600).map {
            call(
                random.nextDouble(),
                if (random.nextBoolean()) CompassVerdict.RIGHT else CompassVerdict.WRONG,
                it,
            )
        }
        val calibration = run(noise)

        assertNull("a threshold was justified on pure noise", calibration.threshold)
        assertTrue(calibration.reason.contains("silent", ignoreCase = true))
    }

    @Test
    fun `the search is corrected for its own size`() {
        // The same data judged with a wider grid must never become easier to
        // justify. A search that got cheaper the more it looked would be
        // manufacturing its result.
        val data = borderlineData()
        val narrow = run(data, CompassConfig(thresholdGrid = listOf(0.60)))
        val wide = run(data, CompassConfig(thresholdGrid = (30..95 step 1).map { it / 100.0 }))

        if (wide.guaranteed) {
            assertTrue(
                "a wider search justified a threshold the narrow one could not",
                narrow.guaranteed,
            )
        }
        assertTrue("the correction must know how many candidates were tried", wide.candidatesTested > 1)
    }

    @Test
    fun `a genuinely strong subset is justified`() {
        // High scores really are right 96% of the time here, over 200 calls,
        // while low scores are a coin flip. Refusing this would make the engine
        // useless rather than careful.
        val strong = List(200) { call(0.90, if (it % 25 == 0) CompassVerdict.WRONG else CompassVerdict.RIGHT, it) } +
            List(200) { call(0.20, if (it % 2 == 0) CompassVerdict.RIGHT else CompassVerdict.WRONG, it) }
        val calibration = run(strong)

        assertNotNull("a real edge was refused", calibration.threshold)
        assertEquals(
            "the threshold must exclude the coin-flip calls and keep the strong ones",
            200,
            calibration.selected,
        )
        assertTrue("the selected subset must be measured", calibration.accuracy.resolved >= 40)
        assertTrue(calibration.accuracy.accuracy!! >= 0.80)
        assertTrue("skill, not drift", calibration.accuracy.lift!! > 0.4)
    }

    // ------------------------------------------------------------------
    // Base rate
    // ------------------------------------------------------------------

    @Test
    fun `high accuracy with no lift over the base rate is refused`() {
        // 90% accurate — but every call is long and the market simply rose, so
        // "always long" scores 90% here while reading nothing at all. Absolute
        // accuracy alone would wave this straight through, which is exactly the
        // failure the base rate exists to catch.
        val lopsided = List(400) {
            CompassCalibrator.Scored(
                probability = 0.99,
                direction = Direction.BULLISH,
                verdict = if (it % 10 == 0) CompassVerdict.WRONG else CompassVerdict.RIGHT,
            )
        }
        val calibration = run(lopsided, CompassConfig(minLiftOverBaseRate = 0.05))

        assertNull("a base-rate artefact was published as skill", calibration.threshold)
    }

    @Test
    fun `an all-one-way market gives a high base rate and no lift`() {
        // Every call long, 90% right: the market rose. "Always long" scores the
        // same 90%, so the skill demonstrated is zero.
        val drift = CompassFixtures.oneWay(right = 90, wrong = 10, direction = Direction.BULLISH)
        val accuracy = CompassAccuracy.of(drift)
        assertEquals(0.9, accuracy.accuracy!!, 1e-9)
        assertEquals(0.9, accuracy.baseRate!!, 1e-9)
        assertEquals("a constant rule would have matched this exactly", 0.0, accuracy.lift!!, 1e-9)
    }

    @Test
    fun `calling both sides correctly is real skill and shows as lift`() {
        // Half the calls long and right, half short and right: the market went
        // both ways and the calls followed it. No constant rule can do that,
        // so the base rate collapses to a coin flip and the lift is large.
        val skilled = List(50) { Direction.BULLISH to CompassVerdict.RIGHT } +
            List(50) { Direction.BEARISH to CompassVerdict.RIGHT }
        val accuracy = CompassAccuracy.of(skilled)
        assertEquals(1.0, accuracy.accuracy!!, 1e-9)
        assertEquals("no constant direction could have scored here", 0.5, accuracy.baseRate!!, 1e-9)
        assertEquals(0.5, accuracy.lift!!, 1e-9)
    }

    // ------------------------------------------------------------------
    // Sample and bounds
    // ------------------------------------------------------------------

    @Test
    fun `a perfect but tiny record justifies nothing`() {
        val perfect = List(10) { call(0.99, CompassVerdict.RIGHT, it) }
        assertNull("ten calls cannot support an 80% claim", run(perfect).threshold)
    }

    @Test
    fun `the bound is always below the raw accuracy`() {
        listOf(10, 50, 200, 1_000).forEach { n ->
            val accuracy = CompassAccuracy.of(
                CompassFixtures.balanced(right = (n * 0.8).toInt(), wrong = n - (n * 0.8).toInt()),
            )
            assertTrue(
                "the bound must never exceed the estimate at n=$n",
                accuracy.accuracyLowerBound!! <= accuracy.accuracy!! + 1e-12,
            )
        }
    }

    @Test
    fun `a longer record at the same rate gives a tighter bound`() {
        val short = CompassAccuracy.of(CompassFixtures.balanced(40, 10))
        val long = CompassAccuracy.of(CompassFixtures.balanced(400, 100))
        assertEquals(short.accuracy!!, long.accuracy!!, 1e-9)
        assertTrue(
            "more evidence at the same rate must support a stronger claim",
            long.accuracyLowerBound!! > short.accuracyLowerBound!!,
        )
    }

    @Test
    fun `raising the required accuracy never justifies more`() {
        val data = borderlineData()
        var previous = true
        listOf(0.50, 0.60, 0.70, 0.80, 0.90, 0.99).forEach { required ->
            val guaranteed = run(data, CompassConfig(minAccuracy = required)).guaranteed
            assertTrue("a stricter requirement was easier to meet at $required", previous || !guaranteed)
            previous = guaranteed
        }
    }

    @Test
    fun `undecided and pending calls are not evidence`() {
        val mixed = listOf(
            Direction.BULLISH to CompassVerdict.RIGHT,
            Direction.BEARISH to CompassVerdict.WRONG,
            Direction.BULLISH to CompassVerdict.UNDECIDED,
            Direction.BULLISH to CompassVerdict.PENDING,
        )
        assertEquals(2, CompassAccuracy.of(mixed).resolved)
    }

    @Test
    fun `the confidence level is honoured rather than hard coded`() {
        val ninety = CompassAccuracy.of(CompassFixtures.balanced(80, 20), confidence = 0.90)
        val ninetyNine = CompassAccuracy.of(CompassFixtures.balanced(80, 20), confidence = 0.99)
        assertTrue(
            "a stricter confidence level must produce a lower bound",
            ninetyNine.accuracyLowerBound!! < ninety.accuracyLowerBound!!,
        )
    }

    @Test
    fun `a silent engine still reports how far short it fell`() {
        val weak = List(200) { call(0.7, if (it % 3 == 0) CompassVerdict.WRONG else CompassVerdict.RIGHT, it) }
        val calibration = run(weak)
        assertNull(calibration.threshold)
        assertTrue(
            "the reason must name a measured number, was '${calibration.reason}'",
            calibration.reason.contains("%"),
        )
    }

    @Test
    fun `degenerate input is refused with a reason rather than throwing`() {
        assertNull(run(emptyList()).threshold)
        assertTrue(run(emptyList()).reason.isNotBlank())
        assertEquals(0, CompassAccuracy.EMPTY.resolved)
        assertNull(CompassAccuracy.wilsonLowerBound(0, 0))
        assertEquals(0.0, CompassAccuracy.wilsonLowerBound(0, 50)!!, 1e-9)
        assertTrue(CompassAccuracy.wilsonLowerBound(50, 50)!! in 0.0..1.0)
    }

    @Test
    fun `the normal quantile is correct at known points`() {
        assertEquals(1.959964, CompassAccuracy.normalQuantile(0.975), 1e-4)
        assertEquals(1.644854, CompassAccuracy.normalQuantile(0.95), 1e-4)
        assertEquals(2.575829, CompassAccuracy.normalQuantile(0.995), 1e-4)
        assertEquals(0.0, CompassAccuracy.normalQuantile(0.5), 1e-6)
    }

    /** A set that is good but not obviously good: the interesting boundary. */
    private fun borderlineData(): List<CompassCalibrator.Scored> {
        val random = Random(5)
        return (0 until 500).map {
            val probability = random.nextDouble()
            val rightChance = 0.45 + 0.35 * probability
            call(
                probability,
                if (random.nextDouble() < rightChance) CompassVerdict.RIGHT else CompassVerdict.WRONG,
                it,
            )
        }
    }
}
