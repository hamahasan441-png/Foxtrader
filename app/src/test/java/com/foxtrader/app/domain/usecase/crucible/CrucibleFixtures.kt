package com.foxtrader.app.domain.usecase.crucible

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleObservation
import kotlin.math.abs
import kotlin.random.Random

object CrucibleFixtures {

    const val SYMBOL = "EURUSD"
    const val M5_MILLIS = 5 * 60 * 1000L
    const val START_TIME = 1_699_999_200_000L

    fun engine() = CrucibleEngine()

    fun bar(index: Int, open: Double, high: Double, low: Double, close: Double, volume: Double = 1_000.0) =
        Candle(START_TIME + index * M5_MILLIS, open, high, low, close, volume)

    /** A random walk: nothing to find, by construction. */
    fun walk(size: Int, seed: Int = 1, start: Double = 1.1000): List<Candle> {
        val random = Random(seed)
        var price = start
        return (0 until size).map { index ->
            val drift = (random.nextDouble() - 0.5) * 0.0016
            val open = price
            val close = open + drift
            price = close
            val wick = abs(drift) * 0.8 + 0.00005
            bar(index, open, maxOf(open, close) + wick, minOf(open, close) - wick, close)
        }
    }

    /** A channel that reverts at its edges: a real directional edge. */
    fun reverting(size: Int, seed: Int = 1): List<Candle> {
        val random = Random(seed)
        val mid = 1.1000
        val half = 0.0060
        var price = mid
        return (0 until size).map { index ->
            val pull = -(price - mid) / half * 0.00050
            val noise = (random.nextDouble() - 0.5) * 0.0009
            val open = price
            val close = open + pull + noise
            price = close
            val wick = abs(close - open) * 0.6 + 0.00006
            bar(index, open, maxOf(open, close) + wick, minOf(open, close) - wick, close)
        }
    }

    /**
     * A walk whose volatility clusters hard but whose direction stays noise.
     *
     * This is the shape the research describes: quiet stretches and violent
     * ones alternate predictably, while which way price goes does not. It is
     * what separates a movement question from a direction question.
     */
    fun clusteredVolatility(size: Int, seed: Int = 1): List<Candle> {
        val random = Random(seed)
        var price = 1.1000
        var scale = 1.0
        return (0 until size).map { index ->
            // Persistent volatility: today's magnitude looks like yesterday's.
            scale = 0.97 * scale + 0.03 * (0.25 + random.nextDouble() * 3.0)
            val step = (random.nextDouble() - 0.5) * 0.0010 * scale
            val open = price
            val close = open + step
            price = close
            val wick = abs(step) * 0.7 + 0.00004
            bar(index, open, maxOf(open, close) + wick, minOf(open, close) - wick, close)
        }
    }

    /** Observations with a chosen overlap, for uniqueness tests. */
    fun observations(
        count: Int,
        span: Int,
        stride: Int,
        hit: (Int) -> Boolean = { true },
        direction: (Int) -> Direction? = { Direction.BULLISH },
        buckets: (Int) -> IntArray = { intArrayOf(0) },
    ): List<CrucibleObservation> = (0 until count).map { i ->
        val index = i * stride
        CrucibleObservation(
            index = index,
            timestamp = START_TIME + index * M5_MILLIS,
            price = 1.1,
            buckets = buckets(i),
            hit = hit(i),
            resolvedDirection = direction(i),
            decidedIndex = index + span,
            uniqueness = 1.0,
        )
    }
}
