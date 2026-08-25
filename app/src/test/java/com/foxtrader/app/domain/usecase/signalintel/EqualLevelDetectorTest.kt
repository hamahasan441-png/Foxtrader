package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EQH/EQL cluster detection.
 *
 * The property that matters most here is the causal one: a cluster's
 * confirmation index must be its **last** touch, because that is the first bar
 * on which the shelf's existence is knowable. Getting this wrong would let the
 * May Madness inducement logic claim a pool was available before it had visibly
 * formed — a look-ahead of exactly the kind the SMT audit found in Session 1.
 */
class EqualLevelDetectorTest {

    private val detector = EqualLevelDetector()

    private fun candle(i: Int, high: Double, low: Double) = Candle(
        timestamp = 1_700_000_000_000L + i * 60_000L,
        open = (high + low) / 2,
        high = high,
        low = low,
        close = (high + low) / 2,
        volume = 1_000.0,
    )

    /** Flat baseline with specific bars raised/lowered to make pivots. */
    private fun series(size: Int, shape: Map<Int, Pair<Double, Double>>): List<Candle> =
        (0 until size).map { i ->
            val (h, l) = shape[i] ?: (100.5 to 99.5)
            candle(i, h, l)
        }

    @Test
    fun `two highs at the same price form a shelf`() {
        val candles = series(40, mapOf(10 to (110.0 to 99.5), 25 to (110.02 to 99.5)))
        val clusters = detector.detect(
            candles = candles,
            pivots = listOf(10, 25),
            direction = Direction.BEARISH,
            tolerance = 0.1,
        )
        assertEquals(1, clusters.size)
        assertEquals(listOf(10, 25), clusters.first().touchIndices)
        assertEquals(110.01, clusters.first().level, 1e-6)
    }

    @Test
    fun `cluster is confirmed on its last touch not its first`() {
        val candles = series(40, mapOf(10 to (110.0 to 99.5), 25 to (110.0 to 99.5)))
        val cluster = detector.detect(candles, listOf(10, 25), Direction.BEARISH, 0.1).single()
        assertEquals(
            "a shelf is not knowable until its final touch prints",
            25,
            cluster.confirmationIndex,
        )
        assertEquals(10, cluster.firstIndex)
        assertEquals(15, cluster.spanBars)
    }

    @Test
    fun `highs outside tolerance are not one shelf`() {
        val candles = series(40, mapOf(10 to (110.0 to 99.5), 25 to (112.0 to 99.5)))
        assertTrue(
            detector.detect(candles, listOf(10, 25), Direction.BEARISH, 0.1).isEmpty(),
        )
    }

    @Test
    fun `touches too far apart are not fused into a fictional pool`() {
        val candles = series(300, mapOf(10 to (110.0 to 99.5), 280 to (110.0 to 99.5)))
        assertTrue(
            "270 bars apart is not one shelf",
            detector.detect(
                candles, listOf(10, 280), Direction.BEARISH, 0.1,
                maxSpanBars = EqualLevelDetector.DEFAULT_MAX_SPAN_BARS,
            ).isEmpty(),
        )
    }

    @Test
    fun `equal lows are detected on the bullish side`() {
        val candles = series(40, mapOf(10 to (100.5 to 90.0), 22 to (100.5 to 90.01)))
        val cluster = detector.detect(candles, listOf(10, 22), Direction.BULLISH, 0.1).single()
        assertEquals(Direction.BULLISH, cluster.direction)
        assertEquals(90.005, cluster.level, 1e-6)
    }

    @Test
    fun `a three touch shelf is reported once not as overlapping subsets`() {
        val candles = series(60, mapOf(
            10 to (110.0 to 99.5),
            22 to (110.0 to 99.5),
            34 to (110.0 to 99.5),
        ))
        val clusters = detector.detect(candles, listOf(10, 22, 34), Direction.BEARISH, 0.1)
        assertEquals("subsets of one shelf must not each become a cluster", 1, clusters.size)
        assertEquals(3, clusters.single().touchCount)
    }

    @Test
    fun `a single pivot is an ordinary swing not a shelf`() {
        val candles = series(40, mapOf(10 to (110.0 to 99.5)))
        assertTrue(detector.detect(candles, listOf(10), Direction.BEARISH, 0.1).isEmpty())
    }

    @Test
    fun `mostRecentBefore never returns a cluster confirmed at or after the cutoff`() {
        val candles = series(80, mapOf(
            10 to (110.0 to 99.5), 20 to (110.0 to 99.5),
            50 to (112.0 to 99.5), 60 to (112.0 to 99.5),
        ))
        val clusters = detector.detect(candles, listOf(10, 20, 50, 60), Direction.BEARISH, 0.1)
        assertEquals(2, clusters.size)

        // Cutoff sits between the two shelves: only the earlier one is knowable.
        val early = detector.mostRecentBefore(clusters, beforeIndex = 40)
        assertNotNull(early)
        assertEquals(20, early!!.confirmationIndex)

        // Cutoff exactly at a confirmation: strictly before, so still excluded.
        assertEquals(20, detector.mostRecentBefore(clusters, beforeIndex = 60)!!.confirmationIndex)

        assertNull(detector.mostRecentBefore(clusters, beforeIndex = 5))
    }

    @Test
    fun `degenerate inputs return nothing rather than throwing`() {
        val candles = series(20, emptyMap())
        assertTrue(detector.detect(emptyList(), listOf(1, 2), Direction.BEARISH, 0.1).isEmpty())
        assertTrue(detector.detect(candles, emptyList(), Direction.BEARISH, 0.1).isEmpty())
        assertTrue(detector.detect(candles, listOf(1, 2), Direction.BEARISH, 0.0).isEmpty())
        assertTrue(detector.detect(candles, listOf(500, 900), Direction.BEARISH, 0.1).isEmpty())
        assertTrue(
            detector.detect(candles, listOf(1, 2), Direction.BEARISH, 0.1, minTouches = 1).isEmpty(),
        )
    }
}
