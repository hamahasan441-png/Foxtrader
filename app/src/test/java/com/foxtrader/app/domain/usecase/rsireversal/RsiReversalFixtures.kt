package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiCandle
import kotlin.math.abs
import kotlin.random.Random

/**
 * Deterministic synthetic series for the RSI Orderflow Reversal tests.
 *
 * Pattern tests drive price structure and RSI structure independently, because
 * the whole strategy is about the two disagreeing. Deriving the RSI series from
 * the synthetic price would make it impossible to construct the exact
 * divergence and confirmation cases the specification enumerates, so the
 * higher-timeframe engine is fed both series directly — which is also the
 * boundary it is written against.
 */
object RsiReversalFixtures {

    const val SYMBOL = "EURUSD"
    const val BAR_MILLIS = 15 * 60 * 1000L
    private const val START_TIME = 1_700_000_000_000L

    /** Config for component tests: no warmup exclusion, small structures. */
    fun testConfig(
        entryMode: EntryMode = EntryMode.BALANCED,
        rsiBreakMode: BreakMode = BreakMode.CLOSE_BREAK,
        equalRsiCountsAsFailure: Boolean = true,
        protectedRsiMode: ProtectedRsiMode = ProtectedRsiMode.HIGHEST,
    ) = RsiReversalConfig(
        pricePivotLeft = 2,
        pricePivotRight = 2,
        rsiPivotLeft = 2,
        rsiPivotRight = 2,
        rsiBreakMode = rsiBreakMode,
        equalRsiCountsAsFailure = equalRsiCountsAsFailure,
        protectedRsiMode = protectedRsiMode,
        entryMode = entryMode,
        warmupBarsOverride = 0,
    )

    /**
     * Linear path through [extremes], with [barsBetween] bars inserted between
     * consecutive turning points.
     *
     * Turning points become confirmed pivots for any pivot strength up to
     * [barsBetween], and every intermediate value is distinct, so no plateau
     * ambiguity leaks into tests that are not about plateaus.
     */
    fun zigzag(extremes: List<Double>, barsBetween: Int = 5): DoubleArray {
        require(extremes.size >= 2)
        require(barsBetween >= 1)
        val out = ArrayList<Double>()
        out += extremes.first()
        for (i in 1 until extremes.size) {
            val from = extremes[i - 1]
            val to = extremes[i]
            for (step in 1..barsBetween) {
                out += from + (to - from) * step / barsBetween
            }
        }
        return out.toDoubleArray()
    }

    /** Indices of the turning points produced by [zigzag]. */
    fun zigzagExtremeIndices(count: Int, barsBetween: Int = 5): List<Int> =
        (0 until count).map { it * barsBetween }

    /**
     * Price candles tracing [path] with the path value as the bar low.
     *
     * The bar high is a fixed offset above it, so pivot highs land on path
     * maxima and pivot lows on path minima without any extra shaping.
     */
    fun priceCandles(path: DoubleArray, spread: Double = 0.0010): List<Candle> =
        path.mapIndexed { index, value ->
            Candle(
                timestamp = START_TIME + index * BAR_MILLIS,
                open = value + spread * 0.25,
                high = value + spread,
                low = value,
                close = value + spread * 0.75,
                volume = 1_000.0,
            )
        }

    /**
     * RSI candles tracing [path] exactly — open, high, low and close all equal.
     *
     * A flat RSI candle is not what live data looks like, and deliberately so:
     * these tests are about the state machine's decisions, and any spread
     * between the RSI open/high/low/close would put a second variable into
     * assertions that are supposed to isolate one.
     */
    fun rsiCandlesFrom(path: DoubleArray, candles: List<Candle>): List<RsiCandle> =
        path.mapIndexed { index, value ->
            RsiCandle(
                index = index,
                timestamp = candles[index].timestamp,
                open = value,
                high = value,
                low = value,
                close = value,
            )
        }

    /** Mirror a price path around its own midpoint, turning lows into highs. */
    fun mirrorPrice(path: DoubleArray): DoubleArray {
        val centre = (path.max() + path.min()) / 2.0
        return DoubleArray(path.size) { 2.0 * centre - path[it] }
    }

    /** Mirror an RSI path around the 50 midline. */
    fun mirrorRsi(path: DoubleArray): DoubleArray = DoubleArray(path.size) { 100.0 - path[it] }

    /**
     * A deterministic random walk with realistic bar geometry, for the
     * reliability, parity and performance tests.
     */
    fun randomWalk(size: Int, seed: Int = 42, start: Double = 1.1000): List<Candle> {
        val random = Random(seed)
        var price = start
        return (0 until size).map { index ->
            val drift = (random.nextDouble() - 0.5) * 0.0020
            val open = price
            val close = (open + drift).coerceAtLeast(0.0001)
            val wick = abs(drift) * 0.6 + 0.0001
            price = close
            Candle(
                timestamp = START_TIME + index * BAR_MILLIS,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0 + random.nextInt(500),
            )
        }
    }

    /**
     * Re-time a series so it can stand in for a lower timeframe: the same bar
     * shapes at a finer interval, ending aligned with [alignEndTo].
     */
    fun retimed(candles: List<Candle>, intervalMillis: Long, startTime: Long): List<Candle> =
        candles.mapIndexed { index, candle ->
            candle.copy(timestamp = startTime + index * intervalMillis)
        }
}
