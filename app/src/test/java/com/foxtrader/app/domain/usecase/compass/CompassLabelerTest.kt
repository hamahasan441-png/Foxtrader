package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "correct" means. Most published accuracy figures fail here rather than
 * in the model, so the definition is tested before anything that uses it.
 */
class CompassLabelerTest {

    private fun judge(
        candles: List<com.foxtrader.app.domain.model.Candle>,
        direction: Direction = Direction.BULLISH,
        barrier: Double = 0.0010,
        horizon: Int = 10,
        index: Int = 0,
    ) = CompassLabeler.judge(candles, index, direction, barrier, horizon)

    @Test
    fun `reaching the called side first is right`() {
        val candles = listOf(
            CompassFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
            CompassFixtures.bar(1, 1.1000, 1.1012, 1.0995, 1.1010),
        )
        val (verdict, at) = judge(candles)
        assertEquals(CompassVerdict.RIGHT, verdict)
        assertEquals(1, at)
    }

    @Test
    fun `reaching the opposite side first is wrong`() {
        val candles = listOf(
            CompassFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
            CompassFixtures.bar(1, 1.1000, 1.1005, 1.0988, 1.0990),
        )
        assertEquals(CompassVerdict.WRONG, judge(candles).first)
    }

    @Test
    fun `a bar touching both sides counts against the call`() {
        // Bar data cannot say which came first, and resolving that ambiguity
        // in the call's favour is how a measured accuracy becomes an
        // advertised one.
        val candles = listOf(
            CompassFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
            CompassFixtures.bar(1, 1.1000, 1.1015, 1.0985, 1.1000),
        )
        assertEquals(CompassVerdict.WRONG, judge(candles).first)
        assertEquals(
            "the same bar must count against a short too",
            CompassVerdict.WRONG,
            judge(candles, direction = Direction.BEARISH).first,
        )
    }

    @Test
    fun `neither side inside the horizon is undecided and counts as neither`() {
        val flat = (0..20).map { CompassFixtures.bar(it, 1.1000, 1.1001, 1.0999, 1.1000) }
        assertEquals(CompassVerdict.UNDECIDED, judge(flat, horizon = 5).first)
    }

    @Test
    fun `running out of series is pending rather than a verdict`() {
        // "Not yet known" and "no move happened" are different claims, and
        // collapsing them would let unfinished calls vote on accuracy.
        val short = (0..3).map { CompassFixtures.bar(it, 1.1000, 1.1001, 1.0999, 1.1000) }
        assertEquals(CompassVerdict.PENDING, judge(short, horizon = 50).first)
    }

    @Test
    fun `the barrier is symmetric so accuracy cannot be bought with geometry`() {
        // The trick this rules out: move the target closer and the win rate
        // rises. Here both sides move together, so a call that is right at one
        // barrier width is right at another only if direction was actually
        // right.
        val candles = listOf(
            CompassFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
            CompassFixtures.bar(1, 1.1000, 1.1002, 1.0998, 1.1000),
            CompassFixtures.bar(2, 1.1000, 1.1030, 1.0999, 1.1025),
        )
        listOf(0.0003, 0.0010, 0.0020).forEach { width ->
            assertEquals(
                "a genuinely upward move should read as right at width $width",
                CompassVerdict.RIGHT,
                judge(candles, barrier = width).first,
            )
        }
    }

    @Test
    fun `a short is judged as the mirror of a long`() {
        val candles = listOf(
            CompassFixtures.bar(0, 1.1000, 1.1001, 1.0999, 1.1000),
            CompassFixtures.bar(1, 1.1000, 1.1004, 1.0985, 1.0988),
        )
        assertEquals(CompassVerdict.WRONG, judge(candles, direction = Direction.BULLISH).first)
        assertEquals(CompassVerdict.RIGHT, judge(candles, direction = Direction.BEARISH).first)
    }

    @Test
    fun `degenerate input never throws`() {
        val candles = CompassFixtures.walk(50)
        assertEquals(CompassVerdict.PENDING, judge(candles, index = -1).first)
        assertEquals(CompassVerdict.PENDING, judge(candles, index = 999).first)
        assertEquals(CompassVerdict.UNDECIDED, judge(candles, barrier = 0.0).first)
        assertEquals(CompassVerdict.UNDECIDED, judge(candles, barrier = Double.NaN).first)
    }

    @Test
    fun `atr is positive on real data and zero where it cannot be computed`() {
        val candles = CompassFixtures.walk(100)
        assertTrue(CompassLabeler.atrAt(candles, 50, 14) > 0.0)
        assertEquals(0.0, CompassLabeler.atrAt(candles, 0, 14), 0.0)
        assertEquals(0.0, CompassLabeler.atrAt(emptyList(), 5, 14), 0.0)
    }

    @Test
    fun `on a coin flip the definition produces a coin flip`() {
        // The definition's own null result. If judging a random walk in a fixed
        // direction did not land near 50%, the metric would carry a bias of its
        // own and every accuracy figure built on it would inherit that bias.
        val candles = CompassFixtures.walk(20_000, seed = 7)
        var right = 0
        var resolved = 0
        for (index in 100 until candles.size - 200 step 37) {
            val atr = CompassLabeler.atrAt(candles, index, 14)
            if (atr <= 0.0) continue
            val (verdict, _) = CompassLabeler.judge(candles, index, Direction.BULLISH, atr, 24)
            when (verdict) {
                CompassVerdict.RIGHT -> { right++; resolved++ }
                CompassVerdict.WRONG -> resolved++
                else -> Unit
            }
        }
        val rate = right.toDouble() / resolved
        assertTrue("too few resolved to judge the metric, was $resolved", resolved > 200)
        assertTrue("the metric itself is biased, a random walk scored $rate", rate in 0.42..0.58)
    }
}
