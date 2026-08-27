package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import kotlin.math.abs
import kotlin.random.Random

object CompassFixtures {

    const val SYMBOL = "EURUSD"
    const val M5_MILLIS = 5 * 60 * 1000L
    const val START_TIME = 1_699_999_200_000L

    fun engine() = CompassEngine(
        CompassCallSource(
            LiquiditySweepEngine(AnalyzeMarketStructureUseCase()),
            VirginWickEngine(SmcDetector()),
            RsiOrderFlowSignalEngine(),
            PivotSweepDivergenceEngine(),
            ValueAreaLiquidityRejectionEngine(),
            AccumulationManipulationDistributionEngine(),
        ),
    )

    fun bar(index: Int, open: Double, high: Double, low: Double, close: Double, volume: Double = 1_000.0) =
        Candle(
            timestamp = START_TIME + index * M5_MILLIS,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )

    /** A random walk: direction is unpredictable by construction. */
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

    /** A channel that reverts at its edges: direction is genuinely predictable. */
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
     * A random walk with a strong upward drift.
     *
     * The base-rate trap made concrete. There is no exploitable structure here
     * — the step-to-step direction is still noise — but price rises, so
     * "always long" is right far more often than not while reading nothing at
     * all. The noise is kept large enough that the primary engines actually
     * produce calls, which a perfectly smooth ramp would not.
     */
    fun driftingWalk(size: Int, seed: Int = 1, drift: Double = 0.00035): List<Candle> {
        val random = Random(seed)
        var price = 1.1000
        return (0 until size).map { index ->
            val step = drift + (random.nextDouble() - 0.5) * 0.0016
            val open = price
            val close = open + step
            price = close
            val wick = abs(step) * 0.8 + 0.00005
            bar(index, open, maxOf(open, close) + wick, minOf(open, close) - wick, close)
        }
    }

    /**
     * Observations in the given proportion with alternating directions, so the
     * best constant-direction rule scores about half and any accuracy above
     * that is skill rather than drift.
     */
    fun balanced(right: Int, wrong: Int): List<Pair<Direction, CompassVerdict>> =
        List(right + wrong) { index ->
            val direction = if (index % 2 == 0) Direction.BULLISH else Direction.BEARISH
            direction to if (index < right) CompassVerdict.RIGHT else CompassVerdict.WRONG
        }

    /** Observations that all call the same way: the base-rate trap. */
    fun oneWay(right: Int, wrong: Int, direction: Direction): List<Pair<Direction, CompassVerdict>> =
        List(right) { direction to CompassVerdict.RIGHT } + List(wrong) { direction to CompassVerdict.WRONG }

    /** Feature vector with a single informative value, for scorer tests. */
    fun features(signal: Double): DoubleArray =
        DoubleArray(CompassFeatures.SIZE).also {
            it[0] = 1.0
            it[1] = signal
        }
}
