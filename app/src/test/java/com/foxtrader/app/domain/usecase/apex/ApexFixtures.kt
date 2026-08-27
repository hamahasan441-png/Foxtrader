package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.apex.model.ApexCandidate
import com.foxtrader.app.domain.usecase.apex.model.ApexOutcome
import com.foxtrader.app.domain.usecase.apex.model.ApexVote
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import kotlin.math.abs
import kotlin.random.Random

object ApexFixtures {

    const val SYMBOL = "EURUSD"
    const val M5_MILLIS = 5 * 60 * 1000L
    const val START_TIME = 1_699_999_200_000L

    fun engine() = ApexEngine(
        ApexVoteCollector(
            LiquiditySweepEngine(AnalyzeMarketStructureUseCase()),
            VirginWickEngine(SmcDetector()),
            RsiOrderFlowSignalEngine(),
            PivotSweepDivergenceEngine(),
            ValueAreaLiquidityRejectionEngine(),
            AccumulationManipulationDistributionEngine(),
        ),
    )

    /** A random walk: no exploitable structure, by construction. */
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

    /** A channel that reverts hard at its edges: a real, exploitable edge. */
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
            val wick = abs(close - open) * 0.8 + 0.00005
            bar(index, open, maxOf(open, close) + wick, minOf(open, close) - wick, close)
        }
    }

    fun bar(index: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        timestamp = START_TIME + index * M5_MILLIS,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000.0,
    )

    /** A candidate with a stated outcome, for testing the gate directly. */
    fun candidate(
        index: Int,
        outcome: ApexOutcome,
        resolvedIndex: Int? = index + 1,
        rewardMultiple: Double = 1.5,
        direction: Direction = Direction.BULLISH,
        members: Int = 2,
    ): ApexCandidate {
        val entry = 1.1000
        val stop = 1.0990
        val risk = entry - stop
        return ApexCandidate(
            direction = direction,
            index = index,
            timestamp = START_TIME + index * M5_MILLIS,
            entry = entry,
            stop = stop,
            target = entry + rewardMultiple * risk,
            votes = ApexMember.entries.take(members).map {
                ApexVote(it, direction, index, START_TIME + index * M5_MILLIS, entry, stop, entry + rewardMultiple * risk)
            },
            outcome = outcome,
            resolvedIndex = if (outcome == ApexOutcome.OPEN) null else resolvedIndex,
            realisedR = when (outcome) {
                ApexOutcome.WIN -> rewardMultiple
                ApexOutcome.LOSS -> -1.0
                else -> null
            },
        )
    }
}
