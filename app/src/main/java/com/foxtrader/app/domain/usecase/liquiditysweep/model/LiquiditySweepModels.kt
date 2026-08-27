package com.foxtrader.app.domain.usecase.liquiditysweep.model

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.liquiditysweep.LevelSource

/**
 * The higher-timeframe directional read of step 1.
 *
 * [knownFromIndex] is the execution bar at which this bias became usable — the
 * bar on which the higher-timeframe bar that produced it had closed. Nothing
 * may act on a bias before it.
 */
data class SweepBias(
    val bias: Bias,
    val higherTimeframe: Timeframe,
    val midTimeframe: Timeframe,
    val knownFromIndex: Int,
    val reason: String,
) {
    val direction: Direction?
        get() = when (bias) {
            Bias.BULLISH -> Direction.BULLISH
            Bias.BEARISH -> Direction.BEARISH
            Bias.NEUTRAL -> null
        }
}

/** A pool of resting liquidity that a sweep can target (step 2). */
data class LiquidityLevel(
    val source: LevelSource,
    val timeframe: Timeframe,
    /** True for liquidity resting above price (buy-side), false for below. */
    val aboveMarket: Boolean,
    val price: Double,
    /** Execution bar this level became knowable on. */
    val knownFromIndex: Int,
    /** How many touches formed it; equal-level clusters carry more than one. */
    val touches: Int,
) {
    /** Sweeping buy-side liquidity above the market sets up a short, and vice versa. */
    val sweepDirection: Direction
        get() = if (aboveMarket) Direction.BEARISH else Direction.BULLISH
}

/**
 * A confirmed liquidity sweep: price took the level, then closed back (step 3).
 *
 * The reclaim is what separates a sweep from a genuine break. Without it every
 * continuation bar through a level would qualify and the trap the model is
 * built on would not have happened.
 */
data class LiquiditySweep(
    val level: LiquidityLevel,
    val direction: Direction,
    /** Bar that traded through the level. */
    val sweepIndex: Int,
    /** Bar that closed back on the original side. */
    val reclaimIndex: Int,
    /** The extreme reached beyond the level; the stop sits behind it. */
    val extreme: Double,
    val reclaimClose: Double,
    val timestamp: Long,
)

/** Formal states, so the model is a machine rather than scattered conditionals. */
enum class SweepState {
    IDLE,
    BIAS_SET,
    LEVELS_MARKED,
    SWEEP_DETECTED,
    RECLAIMED,
    WAIT_ENTRY,
    ENTRY_READY,
    EXPIRED,
}

/** How the entry was confirmed (step 4). */
enum class SweepEntryType { RECLAIM_CLOSE, RETEST, CHOCH_RETEST }

/** A fully confirmed, tradeable setup. */
data class LiquiditySweepSignal(
    val symbol: String,
    val executionTimeframe: Timeframe,
    val bias: SweepBias,
    val sweep: LiquiditySweep,
    val entryType: SweepEntryType,
    val entryIndex: Int,
    val timestamp: Long,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val reasons: List<String>,
) {
    val direction: Direction get() = sweep.direction

    val risk: Double get() = kotlin.math.abs(entry - stop)

    val riskReward: Double
        get() = if (risk <= 0.0) 0.0 else kotlin.math.abs(target - entry) / risk

    /**
     * Structural identity of the setup.
     *
     * Built from the level, the sweep and the entry bar only, so recalculation
     * noise or a changed target cannot manufacture a second arrow for one
     * objectively confirmed sweep.
     */
    val key: String
        get() = "$symbol|${executionTimeframe.label}|${direction.name}|" +
            "${sweep.level.price}|${sweep.sweepIndex}|$entryIndex"
}

/** Everything the engine produced for one series. */
data class LiquiditySweepAnalysis(
    val bias: SweepBias?,
    val levels: List<LiquidityLevel>,
    val sweeps: List<LiquiditySweep>,
    val signals: List<LiquiditySweepSignal>,
    val state: SweepState,
    val statusText: String,
) {
    companion object {
        fun empty(reason: String) = LiquiditySweepAnalysis(
            bias = null,
            levels = emptyList(),
            sweeps = emptyList(),
            signals = emptyList(),
            state = SweepState.IDLE,
            statusText = reason,
        )
    }
}
