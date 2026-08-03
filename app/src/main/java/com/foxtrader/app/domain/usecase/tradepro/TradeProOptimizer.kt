package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.OptimizationObjective
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProOptimizationCandidate
import com.foxtrader.app.domain.model.tradepro.TradeProOptimizationReport
import com.foxtrader.app.domain.model.tradepro.TradeProParameterGrid
import java.util.Locale
import javax.inject.Inject

/**
 * Sweeps a grid of [TradeProConfig] variants over history to find the parameters that best fit an
 * instrument, replacing the course defaults with data-driven settings — but honestly.
 *
 * Anti-overfitting by construction: history is split into an **in-sample** (training) slice and a
 * held-out **out-of-sample** slice. Every candidate is scored on the in-sample slice only; the winner
 * is then re-run on the out-of-sample slice it never saw, so an edge that only exists because the
 * parameters were curve-fit to the training data is exposed rather than celebrated. The two slices are
 * disjoint sub-lists, so there is no look-ahead leakage between them.
 *
 * Runs the real [TradeProBacktestEngine] (full 3-contract lifecycle + MTF validation) for each
 * candidate, so the ranking reflects how the framework actually trades — not a simplified proxy.
 */
class TradeProOptimizer @Inject constructor(
    private val backtestEngine: TradeProBacktestEngine,
) {

    fun optimize(
        symbol: String,
        candles: List<Candle>,
        baseConfig: TradeProConfig = TradeProConfig(),
        baseTimeframe: Timeframe = Timeframe.H1,
        grid: TradeProParameterGrid = TradeProParameterGrid(),
        objective: OptimizationObjective = OptimizationObjective.SYSTEM_QUALITY,
        trainFraction: Double = DEFAULT_TRAIN_FRACTION,
        minTrades: Int = MIN_QUALIFYING_TRADES,
        multiTimeframe: Boolean = true,
    ): TradeProOptimizationReport {
        val configs = grid.expand(baseConfig)
        if (configs.isEmpty()) {
            return TradeProOptimizationReport.empty(symbol, "No parameter combinations to evaluate.")
        }
        if (candles.size <= TradeProSignalEngine.MIN_BARS) {
            return TradeProOptimizationReport.empty(
                symbol,
                "Need more than ${TradeProSignalEngine.MIN_BARS} bars to optimise TRADEPRO.",
            )
        }

        val splitIndex = (candles.size * trainFraction).toInt()
            .coerceIn(1, candles.size)
        val inSample = candles.subList(0, splitIndex)
        val outOfSample = candles.subList(splitIndex, candles.size)

        val candidates = configs
            .map { config ->
                val result = backtestEngine.run(
                    symbol = symbol,
                    candles = inSample,
                    config = config,
                    baseTimeframe = baseTimeframe,
                    multiTimeframe = multiTimeframe,
                )
                TradeProOptimizationCandidate(
                    config = config,
                    label = label(config),
                    inSample = result,
                    score = objective.score(result),
                    qualified = result.totalTrades >= minTrades,
                )
            }
            // Qualified candidates first (enough trades to trust), then by objective score.
            // sortedWith is stable, so equal candidates keep grid order -> deterministic ranking.
            .sortedWith(
                compareByDescending<TradeProOptimizationCandidate> { it.qualified }
                    .thenByDescending { it.score },
            )

        val best = candidates.firstOrNull()
        val bestOutOfSample = best
            ?.takeIf { outOfSample.size > TradeProSignalEngine.MIN_BARS }
            ?.let {
                backtestEngine.run(
                    symbol = symbol,
                    candles = outOfSample,
                    config = it.config,
                    baseTimeframe = baseTimeframe,
                    multiTimeframe = multiTimeframe,
                )
            }

        return TradeProOptimizationReport(
            symbol = symbol,
            objective = objective,
            candidates = candidates,
            best = best,
            bestOutOfSample = bestOutOfSample,
            inSampleBars = inSample.size,
            outOfSampleBars = outOfSample.size,
            evaluated = configs.size,
            narrative = narrative(objective, configs.size, inSample.size, outOfSample.size, best, bestOutOfSample),
        )
    }

    private fun label(config: TradeProConfig): String = String.format(
        Locale.US,
        "SL %.1f / T2 %.1f / ER %.2f",
        config.stopPoints,
        config.target2Points,
        config.minEfficiencyRatio,
    )

    private fun narrative(
        objective: OptimizationObjective,
        evaluated: Int,
        inSampleBars: Int,
        outOfSampleBars: Int,
        best: TradeProOptimizationCandidate?,
        bestOutOfSample: com.foxtrader.app.domain.model.tradepro.TradeProBacktestResult?,
    ): String = buildString {
        if (best == null) {
            append("No parameter sets could be evaluated.")
            return@buildString
        }
        append("Swept $evaluated parameter sets on $inSampleBars in-sample bars. ")
        append("Best by ${objective.label}: ${best.label} ")
        append(
            String.format(
                Locale.US,
                "(in-sample score %.2f, %d trades). ",
                best.score,
                best.inSample.totalTrades,
            ),
        )
        if (bestOutOfSample != null) {
            append(
                String.format(
                    Locale.US,
                    "Out-of-sample (%d bars): %d trades, net %.1f pts, SQN %.2f. ",
                    outOfSampleBars,
                    bestOutOfSample.totalTrades,
                    bestOutOfSample.netPoints,
                    bestOutOfSample.systemQualityNumber,
                ),
            )
            val held = best.inSample.expectancy > 0.0 && bestOutOfSample.expectancy > 0.0
            append(
                if (held) {
                    "The edge held out-of-sample."
                } else {
                    "The edge weakened out-of-sample — likely curve-fit; validate before trading."
                },
            )
        } else {
            append("Not enough out-of-sample data to validate — treat as in-sample only.")
        }
    }

    companion object {
        /** Fraction of history used for training; the remainder is held out for validation. */
        const val DEFAULT_TRAIN_FRACTION = 0.7

        /** A candidate must produce at least this many in-sample trades to be trusted as the winner. */
        const val MIN_QUALIFYING_TRADES = 5
    }
}
