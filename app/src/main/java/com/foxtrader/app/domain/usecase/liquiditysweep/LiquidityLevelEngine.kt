package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquidityLevel
import kotlin.math.abs

/**
 * Step 2 — marking the key liquidity levels.
 *
 * These are the places stops rest: prior swing extremes on the timeframes above
 * execution, clusters of near-equal highs or lows, and the previous
 * higher-timeframe range. They are the only levels a sweep is allowed to target,
 * which is what stops the model from firing on every minor wick.
 *
 * Every level records the execution bar it became knowable on, so a sweep can
 * never be credited against a level that had not formed yet.
 */
class LiquidityLevelEngine {

    /** Build the level book from the two timeframes above execution. */
    fun levels(
        higher: MultiTimeframeSeries,
        mid: MultiTimeframeSeries,
        config: LiquiditySweepConfig,
    ): List<LiquidityLevel> {
        val out = ArrayList<LiquidityLevel>()

        if (LevelSource.MTF_SWING in config.levelSources) {
            out += swingLevels(mid, LevelSource.MTF_SWING, config.mtfSwingLeft, config.mtfSwingRight)
        }
        if (LevelSource.HTF_SWING in config.levelSources) {
            out += swingLevels(higher, LevelSource.HTF_SWING, config.htfSwingLeft, config.htfSwingRight)
        }
        if (LevelSource.PREVIOUS_HTF_RANGE in config.levelSources) {
            out += previousRangeLevels(higher)
        }
        if (LevelSource.EQUAL_LEVELS in config.levelSources) {
            out += equalLevels(mid, config)
        }

        return deduplicate(out, config)
    }

    /**
     * Collapse levels that describe the same shelf.
     *
     * A mid-timeframe swing high, a higher-timeframe swing high and an
     * equal-level cluster routinely land within a tick of each other. Left
     * separate they are three chances for one sweep to fire, which inflates the
     * model's frequency without adding a single new place stops actually rest.
     * The earliest-known member wins, so collapsing never makes a level
     * knowable sooner than it was.
     */
    private fun deduplicate(
        levels: List<LiquidityLevel>,
        config: LiquiditySweepConfig,
    ): List<LiquidityLevel> {
        val sorted = levels.sortedWith(compareBy({ it.knownFromIndex }, { it.price }))
        val kept = ArrayList<LiquidityLevel>(sorted.size)
        for (level in sorted) {
            val tolerance = abs(level.price) * config.levelClusterFraction
            val duplicate = kept.any {
                it.aboveMarket == level.aboveMarket && abs(it.price - level.price) <= tolerance
            }
            if (!duplicate) kept += level
        }
        return kept
    }

    /**
     * The levels a sweep may target at [executionIndex].
     *
     * Only levels already knowable and not yet stale, capped per side so an old
     * shelf far from price cannot crowd out the ones price is actually working.
     */
    fun activeAt(
        levels: List<LiquidityLevel>,
        executionIndex: Int,
        config: LiquiditySweepConfig,
    ): List<LiquidityLevel> {
        val eligible = levels.filter {
            it.knownFromIndex <= executionIndex &&
                executionIndex - it.knownFromIndex <= config.maxLevelAgeBars
        }
        val above = eligible.filter { it.aboveMarket }
            .sortedByDescending { it.knownFromIndex }
            .take(config.maxActiveLevelsPerSide)
        val below = eligible.filter { !it.aboveMarket }
            .sortedByDescending { it.knownFromIndex }
            .take(config.maxActiveLevelsPerSide)
        return (above + below).sortedBy { it.knownFromIndex }
    }

    // ------------------------------------------------------------------

    private fun swingLevels(
        series: MultiTimeframeSeries,
        source: LevelSource,
        left: Int,
        right: Int,
    ): List<LiquidityLevel> {
        val bars = series.candles
        if (bars.size <= left + right) return emptyList()

        val out = ArrayList<LiquidityLevel>()
        for (i in left until bars.size - right) {
            // A swing is only knowable once its right-hand bars have closed, and
            // those are higher-timeframe bars: the level appears in execution
            // time at the close of the last of them, not at its own bar.
            val knownFrom = series.knownFrom(i + right)

            if (isSwingHigh(bars, i, left, right)) {
                out += LiquidityLevel(
                    source = source,
                    timeframe = series.timeframe,
                    aboveMarket = true,
                    price = bars[i].high,
                    knownFromIndex = knownFrom,
                    touches = 1,
                )
            }
            if (isSwingLow(bars, i, left, right)) {
                out += LiquidityLevel(
                    source = source,
                    timeframe = series.timeframe,
                    aboveMarket = false,
                    price = bars[i].low,
                    knownFromIndex = knownFrom,
                    touches = 1,
                )
            }
        }
        return out
    }

    /** The previous closed higher-timeframe bar's extremes. */
    private fun previousRangeLevels(series: MultiTimeframeSeries): List<LiquidityLevel> {
        val bars = series.candles
        if (bars.size < 2) return emptyList()

        val out = ArrayList<LiquidityLevel>(bars.size * 2)
        for (i in 0 until bars.size - 1) {
            // Knowable from the close of bar i, which is when it became "previous".
            val knownFrom = series.knownFrom(i)
            out += LiquidityLevel(
                source = LevelSource.PREVIOUS_HTF_RANGE,
                timeframe = series.timeframe,
                aboveMarket = true,
                price = bars[i].high,
                knownFromIndex = knownFrom,
                touches = 1,
            )
            out += LiquidityLevel(
                source = LevelSource.PREVIOUS_HTF_RANGE,
                timeframe = series.timeframe,
                aboveMarket = false,
                price = bars[i].low,
                knownFromIndex = knownFrom,
                touches = 1,
            )
        }
        return out
    }

    /**
     * Clusters of near-equal extremes.
     *
     * Two highs at the same price are where stops stack up, and are the levels
     * the model most wants: a sweep through them is the trap it trades against.
     * The cluster is published at the close of its last member, never its first.
     */
    private fun equalLevels(
        series: MultiTimeframeSeries,
        config: LiquiditySweepConfig,
    ): List<LiquidityLevel> {
        val bars = series.candles
        val left = config.mtfSwingLeft
        val right = config.mtfSwingRight
        if (bars.size <= left + right) return emptyList()

        val highs = ArrayList<Int>()
        val lows = ArrayList<Int>()
        for (i in left until bars.size - right) {
            if (isSwingHigh(bars, i, left, right)) highs += i
            if (isSwingLow(bars, i, left, right)) lows += i
        }

        val out = ArrayList<LiquidityLevel>()
        out += cluster(series, bars, highs, aboveMarket = true, right = right, config = config)
        out += cluster(series, bars, lows, aboveMarket = false, right = right, config = config)
        return out
    }

    private fun cluster(
        series: MultiTimeframeSeries,
        bars: List<Candle>,
        pivots: List<Int>,
        aboveMarket: Boolean,
        right: Int,
        config: LiquiditySweepConfig,
    ): List<LiquidityLevel> {
        if (pivots.size < 2) return emptyList()
        fun priceOf(i: Int) = if (aboveMarket) bars[i].high else bars[i].low

        val out = ArrayList<LiquidityLevel>()
        for (a in pivots.indices) {
            for (b in a + 1 until pivots.size) {
                val first = pivots[a]
                val second = pivots[b]
                val tolerance = abs(priceOf(first)) * config.levelClusterFraction
                if (abs(priceOf(first) - priceOf(second)) > tolerance) continue

                out += LiquidityLevel(
                    source = LevelSource.EQUAL_LEVELS,
                    timeframe = series.timeframe,
                    aboveMarket = aboveMarket,
                    // The shallower of the pair is the one price reaches first.
                    price = if (aboveMarket) {
                        minOf(priceOf(first), priceOf(second))
                    } else {
                        maxOf(priceOf(first), priceOf(second))
                    },
                    knownFromIndex = series.knownFrom(second + right),
                    touches = 2,
                )
                break
            }
        }
        return out
    }

    /**
     * Loose on the left and strict on the right, so an equal-level plateau
     * resolves to one pivot — its last bar — rather than one per bar.
     */
    private fun isSwingHigh(bars: List<Candle>, index: Int, left: Int, right: Int): Boolean {
        val value = bars[index].high
        if (!value.isFinite()) return false
        for (i in index - left until index) if (bars[i].high > value) return false
        for (i in index + 1..index + right) if (bars[i].high >= value) return false
        return true
    }

    private fun isSwingLow(bars: List<Candle>, index: Int, left: Int, right: Int): Boolean {
        val value = bars[index].low
        if (!value.isFinite()) return false
        for (i in index - left until index) if (bars[i].low < value) return false
        for (i in index + 1..index + right) if (bars[i].low <= value) return false
        return true
    }
}
