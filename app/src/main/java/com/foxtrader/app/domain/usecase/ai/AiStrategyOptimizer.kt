package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestExecutionMode
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsEngine
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI Strategy Optimizer.
 *
 * Performs grid-search optimization of [BacktestConfig] parameters (risk percent, spread,
 * slippage) for a given [StrategyFunction]. Uses in-sample/out-of-sample walk-forward
 * validation to prevent overfitting.
 *
 * Pure domain logic: no Android dependencies, no external network calls.
 */
@Singleton
class AiStrategyOptimizer @Inject constructor(
    private val backtestEngine: BacktestEngine,
    private val analyticsEngine: BacktestAnalyticsEngine,
) {

    /**
     * Optimize backtest parameters for the given strategy over candle data.
     *
     * @param candles Full price history to split into train/test
     * @param strategyFunction The strategy to optimize
     * @param symbol Trading symbol
     * @param timeframe Chart timeframe
     * @param strategyName Human-readable name for the report
     * @param grid Parameter grid to search
     * @param trainFraction Fraction of data for in-sample training (default 0.7)
     * @param minTrades Minimum trades to qualify a candidate
     */
    fun optimize(
        candles: List<Candle>,
        strategyFunction: StrategyFunction,
        symbol: String,
        timeframe: Timeframe = Timeframe.H1,
        strategyName: String = "Strategy",
        grid: OptimizationGrid = OptimizationGrid(),
        trainFraction: Double = DEFAULT_TRAIN_FRACTION,
        minTrades: Int = MIN_QUALIFYING_TRADES,
    ): AiOptimizationReport {
        if (candles.size < MINIMUM_BARS) {
            return emptyReport(symbol, timeframe, strategyName)
        }

        val splitIndex = (candles.size * trainFraction).toInt()
            .coerceIn(1, candles.size - 1)
        val inSample = candles.subList(0, splitIndex)
        val outOfSample = candles.subList(splitIndex, candles.size)

        val configs = grid.expand()
        val candidates = configs.map { config ->
            backtestEngine.updateConfig(config)
            val result = backtestEngine(inSample, strategyFunction, symbol, timeframe)
            val score = compositeScore(result)
            val qualified = result.metrics.totalTrades >= minTrades
            OptimizationCandidate(
                config = config,
                backtestResult = result,
                compositeScore = score,
                qualified = qualified,
            )
        }.sortedWith(
            compareByDescending<OptimizationCandidate> { it.qualified }
                .thenByDescending { it.compositeScore },
        )

        val bestCandidate = candidates.firstOrNull { it.qualified }
            ?: candidates.firstOrNull()

        val bestOutOfSample = bestCandidate
            ?.takeIf { outOfSample.size >= OUT_OF_SAMPLE_MIN_BARS }
            ?.let {
                backtestEngine.updateConfig(it.config)
                backtestEngine(outOfSample, strategyFunction, symbol, timeframe)
            }

        val overfitWarning = detectOverfitting(bestCandidate, bestOutOfSample)

        val narrative = buildNarrative(
            configs.size, inSample.size, outOfSample.size,
            bestCandidate, bestOutOfSample, overfitWarning,
        )

        return AiOptimizationReport(
            symbol = symbol,
            timeframe = timeframe,
            strategyName = strategyName,
            candidates = candidates,
            bestCandidate = bestCandidate,
            bestOutOfSample = bestOutOfSample,
            trainBars = inSample.size,
            testBars = outOfSample.size,
            narrative = narrative,
            overfitWarning = overfitWarning,
        )
    }

    // ========================================================================
    // COMPOSITE SCORING
    // ========================================================================

    /**
     * Composite score (0-100):
     * - 40% profit factor (capped at 5.0, normalized: pf/5.0 * 40)
     * - 25% Sharpe ratio (clamped -2..4, normalized: (sharpe+2)/6 * 25)
     * - 20% win rate (winRate/100 * 20)
     * - 15% max drawdown inverted (1 - ddPercent/100, floored at 0, * 15)
     */
    private fun compositeScore(result: BacktestResult): Double {
        val metrics = result.metrics
        if (metrics.totalTrades == 0) return 0.0

        val pfScore = (metrics.profitFactor.coerceAtMost(5.0) / 5.0) * 40.0
        val sharpe = metrics.sharpeRatio.coerceIn(-2.0, 4.0)
        val sharpeScore = ((sharpe + 2.0) / 6.0) * 25.0
        val winRateScore = (metrics.winRate / 100.0) * 20.0
        val ddInverted = (1.0 - metrics.maxDrawdownPercent / 100.0).coerceAtLeast(0.0)
        val ddScore = ddInverted * 15.0

        return pfScore + sharpeScore + winRateScore + ddScore
    }

    // ========================================================================
    // OVERFITTING DETECTION
    // ========================================================================

    private fun detectOverfitting(
        bestCandidate: OptimizationCandidate?,
        outOfSampleResult: BacktestResult?,
    ): Boolean {
        if (bestCandidate == null || outOfSampleResult == null) return false
        val inSamplePf = bestCandidate.backtestResult.metrics.profitFactor
        val outOfSamplePf = outOfSampleResult.metrics.profitFactor
        // Flag overfitting when out-of-sample profit factor < 0.5 * in-sample profit factor
        return outOfSamplePf < 0.5 * inSamplePf
    }

    // ========================================================================
    // NARRATIVE
    // ========================================================================

    private fun buildNarrative(
        evaluated: Int,
        inSampleBars: Int,
        outOfSampleBars: Int,
        best: OptimizationCandidate?,
        bestOutOfSample: BacktestResult?,
        overfitWarning: Boolean,
    ): String = buildString {
        if (best == null) {
            append("No parameter sets could be evaluated.")
            return@buildString
        }
        append(
            String.format(
                Locale.US,
                "Swept %d parameter sets on %d in-sample bars. ",
                evaluated, inSampleBars,
            ),
        )
        append(
            String.format(
                Locale.US,
                "Best: risk=%.1f%%, spread=%.5f, slippage=%.5f (score %.2f, %d trades). ",
                best.config.riskPercent,
                best.config.spread,
                best.config.slippage,
                best.compositeScore,
                best.backtestResult.metrics.totalTrades,
            ),
        )
        if (bestOutOfSample != null) {
            append(
                String.format(
                    Locale.US,
                    "Out-of-sample (%d bars): %d trades, PF %.2f, net $%.0f. ",
                    outOfSampleBars,
                    bestOutOfSample.metrics.totalTrades,
                    bestOutOfSample.metrics.profitFactor,
                    bestOutOfSample.metrics.netProfit,
                ),
            )
            if (overfitWarning) {
                append("WARNING: Out-of-sample performance degraded significantly - likely overfitting.")
            } else {
                append("The edge held out-of-sample.")
            }
        } else {
            append("Not enough out-of-sample data to validate.")
        }
    }

    // ========================================================================
    // EMPTY REPORT
    // ========================================================================

    private fun emptyReport(
        symbol: String,
        timeframe: Timeframe,
        strategyName: String,
    ) = AiOptimizationReport(
        symbol = symbol,
        timeframe = timeframe,
        strategyName = strategyName,
        candidates = emptyList(),
        bestCandidate = null,
        bestOutOfSample = null,
        trainBars = 0,
        testBars = 0,
        narrative = "Insufficient data for optimization (need at least $MINIMUM_BARS bars).",
        overfitWarning = false,
    )

    companion object {
        /** Minimum candle count to attempt optimization. */
        const val MINIMUM_BARS = 50

        /** Fraction of data used for training; remainder held out for validation. */
        const val DEFAULT_TRAIN_FRACTION = 0.7

        /** Minimum trades required for a candidate to qualify. */
        const val MIN_QUALIFYING_TRADES = 5

        /** Minimum out-of-sample bars to run validation. */
        const val OUT_OF_SAMPLE_MIN_BARS = 10
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

/**
 * Grid of parameters to search during optimization.
 */
data class OptimizationGrid(
    val riskPercentValues: List<Double> = listOf(0.5, 1.0, 1.5, 2.0),
    val spreadValues: List<Double> = listOf(0.00001, 0.00002, 0.00003),
    val slippageValues: List<Double> = listOf(0.0, 0.00001, 0.00002),
) {
    /**
     * Expand the grid into all combinations of riskPercent x spread x slippage.
     * Each combination produces a [BacktestConfig] with defaults for other fields.
     */
    fun expand(): List<BacktestConfig> = buildList {
        for (risk in riskPercentValues) {
            for (spread in spreadValues) {
                for (slippage in slippageValues) {
                    add(
                        BacktestConfig(
                            riskPercent = risk,
                            spread = spread,
                            slippage = slippage,
                            // Optimise against the fill model the Lab and the
                            // chart actually report. Tuning parameters under the
                            // legacy same-bar fill produces settings that look
                            // best only under an execution model the trader will
                            // never get.
                            executionMode = BacktestExecutionMode.NEXT_BAR_OPEN,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * A single optimization candidate with its backtest result and score.
 */
data class OptimizationCandidate(
    val config: BacktestConfig,
    val backtestResult: BacktestResult,
    val compositeScore: Double,
    val qualified: Boolean,
)

/**
 * Full optimization report with walk-forward validation results.
 */
data class AiOptimizationReport(
    val symbol: String,
    val timeframe: Timeframe,
    val strategyName: String,
    val candidates: List<OptimizationCandidate>,
    val bestCandidate: OptimizationCandidate?,
    val bestOutOfSample: BacktestResult?,
    val trainBars: Int,
    val testBars: Int,
    val narrative: String,
    val overfitWarning: Boolean,
)
