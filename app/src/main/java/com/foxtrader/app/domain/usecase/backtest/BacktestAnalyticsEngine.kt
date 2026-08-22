package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BinaryBacktestResult
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Backtest Analytics Engine.
 *
 * Adds institutional validation layers on top of a completed backtest:
 * - Walk-forward split (in-sample vs out-of-sample)
 * - Monte Carlo trade-order randomization for drawdown / ruin robustness
 * - Deterministic recommendation generation for the Backtesting Lab
 *
 * Pure domain logic: no Android dependencies, no random global state.
 */
class BacktestAnalyticsEngine @Inject constructor() {

    fun analyze(
        result: BacktestResult,
        walkForwardSplit: Double = DEFAULT_WALK_FORWARD_SPLIT,
        monteCarloRuns: Int = DEFAULT_MONTE_CARLO_RUNS,
        seed: Int = DEFAULT_RANDOM_SEED,
    ): BacktestAnalyticsReport = analyzeTrades(
        trades = result.trades.sortedBy { it.entryTime }.map {
            AnalyticsTrade(time = it.entryTime, pnl = it.netPnL, rMultiple = it.rMultiple)
        },
        initialBalance = result.config.initialBalance,
        walkForwardSplit = walkForwardSplit,
        monteCarloRuns = monteCarloRuns,
        seed = seed,
    )

    /**
     * Apply the same chronological walk-forward and Monte Carlo validation to
     * fixed-expiry binary contracts. A win's R multiple is the configured net
     * payout (pnl/stake), a loss is -1R, and a tie is 0R.
     */
    fun analyzeBinary(
        result: BinaryBacktestResult,
        walkForwardSplit: Double = DEFAULT_WALK_FORWARD_SPLIT,
        monteCarloRuns: Int = DEFAULT_MONTE_CARLO_RUNS,
        seed: Int = DEFAULT_RANDOM_SEED,
    ): BacktestAnalyticsReport = analyzeTrades(
        trades = result.trades.sortedBy { it.entryTime }.map {
            AnalyticsTrade(
                time = it.entryTime,
                pnl = it.pnl,
                rMultiple = if (it.stake > 0.0) it.pnl / it.stake else 0.0,
            )
        },
        initialBalance = result.config.initialBalance,
        walkForwardSplit = walkForwardSplit,
        monteCarloRuns = monteCarloRuns,
        seed = seed,
    )

    private fun analyzeTrades(
        trades: List<AnalyticsTrade>,
        initialBalance: Double,
        walkForwardSplit: Double,
        monteCarloRuns: Int,
        seed: Int,
    ): BacktestAnalyticsReport {
        val ordered = trades.sortedBy { it.time }
        val walkForward = buildWalkForward(
            trades = ordered,
            initialBalance = initialBalance,
            split = walkForwardSplit,
        )
        val monteCarlo = buildMonteCarlo(
            trades = ordered,
            initialBalance = initialBalance,
            runs = monteCarloRuns,
            seed = seed,
        )
        return BacktestAnalyticsReport(
            walkForward = walkForward,
            monteCarlo = monteCarlo,
            recommendations = buildRecommendations(walkForward, monteCarlo),
        )
    }

    private fun buildWalkForward(
        trades: List<AnalyticsTrade>,
        initialBalance: Double,
        split: Double,
    ): WalkForwardAnalysis? {
        if (trades.size < MIN_TRADES_FOR_WALK_FORWARD) return null
        val splitIndex = (trades.size * split.coerceIn(0.2, 0.8)).roundToInt()
            .coerceIn(1, trades.lastIndex)
        val inSample = performanceSlice(trades.take(splitIndex), initialBalance)
        val outSampleStart = initialBalance + trades.take(splitIndex).sumOf { it.pnl }
        val outOfSample = performanceSlice(trades.drop(splitIndex), outSampleStart)
        val efficiency = if (inSample.netProfit > 0.0) {
            (outOfSample.netProfit / inSample.netProfit).coerceIn(-2.0, 2.0)
        } else 0.0
        val degradation = outOfSample.profitFactor - inSample.profitFactor
        val stabilityScore = stabilityScore(inSample, outOfSample)
        return WalkForwardAnalysis(
            inSample = inSample,
            outOfSample = outOfSample,
            splitIndex = splitIndex,
            efficiency = efficiency,
            profitFactorDelta = degradation,
            stabilityScore = stabilityScore,
            verdict = when {
                stabilityScore >= 75 -> "Robust — out-of-sample performance held up."
                stabilityScore >= 50 -> "Mixed — edge survived but needs more validation."
                else -> "Fragile — in-sample edge degraded out-of-sample."
            },
        )
    }

    private fun buildMonteCarlo(
        trades: List<AnalyticsTrade>,
        initialBalance: Double,
        runs: Int,
        seed: Int,
    ): MonteCarloSimulation? {
        if (trades.size < MIN_TRADES_FOR_MONTE_CARLO) return null
        val pnl = trades.map { it.pnl }
        val finals = mutableListOf<Double>()
        val drawdowns = mutableListOf<Double>()
        var ruinCount = 0
        repeat(runs.coerceIn(10, MAX_MONTE_CARLO_RUNS)) { run ->
            val shuffled = pnl.shuffled(Random(seed + run))
            var equity = initialBalance
            var peak = initialBalance
            var maxDrawdown = 0.0
            for (value in shuffled) {
                equity += value
                peak = max(peak, equity)
                maxDrawdown = max(maxDrawdown, peak - equity)
            }
            finals += equity
            drawdowns += maxDrawdown
            if (maxDrawdown >= initialBalance * RUIN_DRAWDOWN_FRACTION || equity <= initialBalance * RUIN_BALANCE_FRACTION) {
                ruinCount++
            }
        }
        finals.sort()
        drawdowns.sort()
        return MonteCarloSimulation(
            runs = finals.size,
            medianFinalBalance = percentile(finals, 0.50),
            bestFinalBalance = finals.last(),
            worstFinalBalance = finals.first(),
            medianMaxDrawdown = percentile(drawdowns, 0.50),
            confidence95MaxDrawdown = percentile(drawdowns, 0.95),
            worstCaseDrawdown = drawdowns.last(),
            riskOfRuinPercent = (ruinCount.toDouble() / finals.size) * 100.0,
        )
    }

    private fun performanceSlice(trades: List<AnalyticsTrade>, startingBalance: Double): PerformanceSlice {
        if (trades.isEmpty()) return PerformanceSlice.EMPTY
        val wins = trades.filter { it.pnl > 0.0 }
        val losses = trades.filter { it.pnl < 0.0 }
        val grossProfit = wins.sumOf { it.pnl }
        val grossLoss = abs(losses.sumOf { it.pnl })
        val netProfit = grossProfit - grossLoss
        // Zero-PnL trades (for example a refunded fixed-expiry TIE) are not
        // losses. Report directional win rate over decided outcomes, while
        // expectancy keeps the neutral-trade probability in the denominator.
        val decided = wins.size + losses.size
        val winRate = if (decided > 0) (wins.size.toDouble() / decided) * 100.0 else 0.0
        val averageWin = if (wins.isNotEmpty()) grossProfit / wins.size else 0.0
        val averageLoss = if (losses.isNotEmpty()) grossLoss / losses.size else 0.0
        val winProbability = wins.size.toDouble() / trades.size
        val lossProbability = losses.size.toDouble() / trades.size
        val expectancy = winProbability * averageWin - lossProbability * averageLoss
        val profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else if (grossProfit > 0.0) Double.MAX_VALUE else 0.0
        var equity = startingBalance
        var peak = startingBalance
        var maxDrawdown = 0.0
        for (trade in trades) {
            equity += trade.pnl
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, peak - equity)
        }
        return PerformanceSlice(
            totalTrades = trades.size,
            netProfit = netProfit,
            winRate = winRate,
            profitFactor = profitFactor,
            expectancy = expectancy,
            averageR = trades.map { it.rMultiple }.averageOrZero(),
            maxDrawdown = maxDrawdown,
            returnPercent = if (startingBalance > 0.0) (netProfit / startingBalance) * 100.0 else 0.0,
        )
    }

    private fun stabilityScore(inSample: PerformanceSlice, outOfSample: PerformanceSlice): Int {
        var score = 100.0
        if (outOfSample.totalTrades < 3) score -= 25.0
        if (outOfSample.netProfit <= 0.0) score -= 30.0
        if (outOfSample.profitFactor < 1.0) score -= 25.0
        if (inSample.profitFactor.isFinite() && outOfSample.profitFactor.isFinite()) {
            val pfDrop = inSample.profitFactor - outOfSample.profitFactor
            if (pfDrop > 0.5) score -= (pfDrop * 15.0).coerceAtMost(25.0)
        }
        if (inSample.winRate > 0.0) {
            val winRateDrop = inSample.winRate - outOfSample.winRate
            if (winRateDrop > 10.0) score -= (winRateDrop * 0.8).coerceAtMost(20.0)
        }
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun buildRecommendations(
        walkForward: WalkForwardAnalysis?,
        monteCarlo: MonteCarloSimulation?,
    ): List<String> = buildList {
        if (walkForward == null) {
            add("Collect more trades before trusting walk-forward validation.")
        } else {
            add(walkForward.verdict)
            if (walkForward.outOfSample.profitFactor < 1.0) {
                add("Out-of-sample profit factor is below 1.0 — reduce size or improve filters.")
            }
            if (walkForward.outOfSample.totalTrades < 5) {
                add("Out-of-sample trade count is low — continue forward testing.")
            }
        }
        if (monteCarlo == null) {
            add("Monte Carlo needs more trades for a meaningful risk distribution.")
        } else {
            if (monteCarlo.riskOfRuinPercent > 10.0) {
                add("Risk of ruin is elevated — lower per-trade risk before live use.")
            }
            add("95% Monte Carlo max drawdown: ${monteCarlo.confidence95MaxDrawdown.formatMoney()}.")
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val idx = ((values.size - 1) * p.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, values.lastIndex)
        return values[idx]
    }

    private fun List<Double>.averageOrZero(): Double = average().takeIf { !it.isNaN() } ?: 0.0

    private fun Double.formatMoney(): String = "$" + "%,.0f".format(this)

    private data class AnalyticsTrade(
        val time: Long,
        val pnl: Double,
        val rMultiple: Double,
    )

    private companion object {
        const val DEFAULT_WALK_FORWARD_SPLIT = 0.70
        const val DEFAULT_MONTE_CARLO_RUNS = 250
        const val DEFAULT_RANDOM_SEED = 441
        const val MIN_TRADES_FOR_WALK_FORWARD = 4
        const val MIN_TRADES_FOR_MONTE_CARLO = 6
        const val MAX_MONTE_CARLO_RUNS = 2_000
        const val RUIN_DRAWDOWN_FRACTION = 0.30
        const val RUIN_BALANCE_FRACTION = 0.80
    }
}

data class BacktestAnalyticsReport(
    val walkForward: WalkForwardAnalysis?,
    val monteCarlo: MonteCarloSimulation?,
    val recommendations: List<String>,
)

data class WalkForwardAnalysis(
    val inSample: PerformanceSlice,
    val outOfSample: PerformanceSlice,
    val splitIndex: Int,
    val efficiency: Double,
    val profitFactorDelta: Double,
    val stabilityScore: Int,
    val verdict: String,
)

data class MonteCarloSimulation(
    val runs: Int,
    val medianFinalBalance: Double,
    val bestFinalBalance: Double,
    val worstFinalBalance: Double,
    val medianMaxDrawdown: Double,
    val confidence95MaxDrawdown: Double,
    val worstCaseDrawdown: Double,
    val riskOfRuinPercent: Double,
)

data class PerformanceSlice(
    val totalTrades: Int,
    val netProfit: Double,
    val winRate: Double,
    val profitFactor: Double,
    val expectancy: Double,
    val averageR: Double,
    val maxDrawdown: Double,
    val returnPercent: Double,
) {
    companion object {
        val EMPTY = PerformanceSlice(
            totalTrades = 0,
            netProfit = 0.0,
            winRate = 0.0,
            profitFactor = 0.0,
            expectancy = 0.0,
            averageR = 0.0,
            maxDrawdown = 0.0,
            returnPercent = 0.0,
        )
    }
}
