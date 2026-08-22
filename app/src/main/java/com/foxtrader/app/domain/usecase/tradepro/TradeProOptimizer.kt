package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.OptimizationObjective
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProOptimizationCandidate
import com.foxtrader.app.domain.model.tradepro.TradeProOptimizationReport
import com.foxtrader.app.domain.model.tradepro.TradeProRobustnessReport
import com.foxtrader.app.domain.model.tradepro.TradeProWalkForwardFold
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

        val robustness = buildRobustnessReport(
            symbol = symbol,
            candles = candles,
            objective = objective,
            configs = configs,
            hasQualifiedCandidate = candidates.any { it.qualified },
            baseTimeframe = baseTimeframe,
            minTrades = minTrades,
            multiTimeframe = multiTimeframe,
        )

        return TradeProOptimizationReport(
            symbol = symbol,
            objective = objective,
            candidates = candidates,
            best = best,
            bestOutOfSample = bestOutOfSample,
            inSampleBars = inSample.size,
            outOfSampleBars = outOfSample.size,
            evaluated = configs.size,
            narrative = narrative(
                objective, configs.size, inSample.size, outOfSample.size,
                best, bestOutOfSample, robustness,
            ),
            robustness = robustness,
        )
    }

    /**
     * Anchored walk-forward robustness validation.
     *
     * The initial sweep answers "what won on this training split?". Phase 4 adds the harder
     * question: "does the parameter grid keep finding an edge as time advances?" Each fold expands
     * the training history chronologically, re-ranks a deterministic bounded sample of the grid,
     * and validates that fold winner on the immediately following unseen block. The pool itself is
     * data-independent, so neither validation bars nor later rankings can leak into an earlier fold.
     */
    private fun buildRobustnessReport(
        symbol: String,
        candles: List<Candle>,
        objective: OptimizationObjective,
        configs: List<TradeProConfig>,
        hasQualifiedCandidate: Boolean,
        baseTimeframe: Timeframe,
        minTrades: Int,
        multiTimeframe: Boolean,
    ): TradeProRobustnessReport? {
        if (candles.size < MIN_ROBUSTNESS_BARS || configs.isEmpty()) return null

        // IMPORTANT: this pool is chosen deterministically from the parameter grid, NOT from
        // candidates ranked on later data. Using later-ranked candidates inside an earlier fold
        // would leak future information into the walk-forward selection step. The default grid
        // has 27 configs, so it is evaluated in full; oversized custom grids are evenly sampled
        // without looking at any backtest result.
        val pool = deterministicRobustnessPool(configs)
        if (pool.isEmpty()) return null

        val validationBars = (candles.size / (ROBUSTNESS_FOLDS + 2))
            .coerceAtLeast(MIN_VALIDATION_BARS)
        val initialTrainBars = candles.size - validationBars * ROBUSTNESS_FOLDS
        if (initialTrainBars <= TradeProSignalEngine.MIN_BARS) return null

        val folds = buildList {
            for (foldIndex in 0 until ROBUSTNESS_FOLDS) {
                val trainEnd = initialTrainBars + foldIndex * validationBars
                val validationEnd = minOf(trainEnd + validationBars, candles.size)
                if (trainEnd <= TradeProSignalEngine.MIN_BARS || validationEnd <= trainEnd) continue

                val train = candles.subList(0, trainEnd)
                val validation = candles.subList(trainEnd, validationEnd)
                if (validation.size <= TradeProSignalEngine.MIN_BARS) continue

                val foldWinner = pool
                    .map { config ->
                        val result = backtestEngine.run(
                            symbol = symbol,
                            candles = train,
                            config = config,
                            baseTimeframe = baseTimeframe,
                            multiTimeframe = multiTimeframe,
                        )
                        Triple(config, result, objective.score(result))
                    }
                    .sortedWith(
                        compareByDescending<Triple<TradeProConfig, com.foxtrader.app.domain.model.tradepro.TradeProBacktestResult, Double>> {
                            it.second.totalTrades >= minTrades
                        }.thenByDescending { it.third },
                    )
                    .firstOrNull() ?: continue

                val validationResult = backtestEngine.run(
                    symbol = symbol,
                    candles = validation,
                    config = foldWinner.first,
                    baseTimeframe = baseTimeframe,
                    multiTimeframe = multiTimeframe,
                )
                val validationScore = objective.score(validationResult)
                val passed = validationResult.totalTrades >= MIN_VALIDATION_TRADES &&
                    validationResult.expectancy > 0.0 &&
                    validationResult.systemQualityNumber > 0.0 &&
                    validationResult.profitFactor >= 1.0

                add(
                    TradeProWalkForwardFold(
                        index = foldIndex + 1,
                        trainBars = train.size,
                        validationBars = validation.size,
                        winnerLabel = label(foldWinner.first),
                        trainingScore = foldWinner.third,
                        validationScore = validationScore,
                        validationTrades = validationResult.totalTrades,
                        validationExpectancy = validationResult.expectancy,
                        validationProfitFactor = validationResult.profitFactor,
                        passed = passed,
                    ),
                )
            }
        }
        if (folds.size < MIN_ROBUSTNESS_FOLDS) return null

        val passed = folds.count { it.passed }
        val passRate = passed.toDouble() / folds.size
        val mostCommonWinner = folds.groupingBy { it.winnerLabel }.eachCount().maxOfOrNull { it.value } ?: 0
        val winnerStability = mostCommonWinner.toDouble() / folds.size
        val positiveValidationRate = folds.count { it.validationScore > 0.0 }.toDouble() / folds.size
        val avgScore = folds.map { it.validationScore }.average()
        val worstScore = folds.minOf { it.validationScore }
        val avgExpectancy = folds.map { it.validationExpectancy }.average()
        val robustnessScore = (
            passRate * 60.0 +
                winnerStability * 20.0 +
                positiveValidationRate * 20.0
            ).coerceIn(0.0, 100.0)
        val grade = when {
            robustnessScore >= 85.0 && passRate >= 0.75 -> "A"
            robustnessScore >= 70.0 && passRate >= 0.50 -> "B"
            robustnessScore >= 55.0 -> "C"
            else -> "D"
        }
        val recommended = hasQualifiedCandidate &&
            grade in setOf("A", "B") &&
            avgExpectancy > 0.0 &&
            worstScore.isFinite()

        return TradeProRobustnessReport(
            folds = folds,
            passedFolds = passed,
            passRate = passRate,
            winnerStability = winnerStability,
            positiveValidationRate = positiveValidationRate,
            averageValidationScore = avgScore,
            worstValidationScore = worstScore,
            averageValidationExpectancy = avgExpectancy,
            robustnessScore = robustnessScore,
            grade = grade,
            recommended = recommended,
        )
    }

    private fun deterministicRobustnessPool(configs: List<TradeProConfig>): List<TradeProConfig> {
        if (configs.size <= MAX_ROBUSTNESS_CANDIDATES) return configs
        if (MAX_ROBUSTNESS_CANDIDATES <= 1) return listOf(configs.first())
        return (0 until MAX_ROBUSTNESS_CANDIDATES)
            .map { i ->
                val index = ((i.toDouble() / (MAX_ROBUSTNESS_CANDIDATES - 1)) * configs.lastIndex)
                    .toInt()
                    .coerceIn(0, configs.lastIndex)
                configs[index]
            }
            .distinct()
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
        robustness: TradeProRobustnessReport?,
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
            append(" ")
        } else {
            append("Not enough out-of-sample data to validate — treat as in-sample only. ")
        }
        if (robustness != null) {
            append(
                String.format(
                    Locale.US,
                    "Walk-forward grade %s (%.0f/100): %d/%d unseen folds passed; winner stability %.0f%%. ",
                    robustness.grade,
                    robustness.robustnessScore,
                    robustness.passedFolds,
                    robustness.folds.size,
                    robustness.winnerStability * 100.0,
                ),
            )
            append(if (robustness.recommended) "Robust enough for guarded apply." else "Do not auto-apply; robustness gate failed.")
        }
    }

    companion object {
        /** Fraction of history used for training; the remainder is held out for validation. */
        const val DEFAULT_TRAIN_FRACTION = 0.7

        /** A candidate must produce at least this many in-sample trades to be trusted as the winner. */
        const val MIN_QUALIFYING_TRADES = 5

        /** Phase 4 anchored walk-forward settings; bounded to keep mobile sweeps predictable. */
        const val ROBUSTNESS_FOLDS = 4
        const val MAX_ROBUSTNESS_CANDIDATES = 32
        const val MIN_ROBUSTNESS_BARS = 320
        const val MIN_ROBUSTNESS_FOLDS = 2
        const val MIN_VALIDATION_BARS = 60
        const val MIN_VALIDATION_TRADES = 2
    }
}
