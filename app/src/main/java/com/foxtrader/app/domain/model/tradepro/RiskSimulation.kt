package com.foxtrader.app.domain.model.tradepro

/**
 * Inputs to a Monte Carlo risk simulation. Everything is expressed in R (multiples of the risk taken
 * per trade) and percentages, so the result is account-size agnostic and comparable to the backtest
 * and journal analytics.
 */
data class RiskSimulationInput(
    /** Probability of a winning trade, 0..1. */
    val winRate: Double,
    /** Average winning trade in R (e.g. 2.0 = a 2R winner). */
    val avgWinR: Double,
    /** Average losing trade in R, expressed as a positive magnitude (e.g. 1.0 = a full-R loss). */
    val avgLossR: Double,
    /** Fraction of current equity risked per trade, 0..1 (e.g. 0.01 = 1%). */
    val riskPerTradeFraction: Double,
    /** Number of trades in each simulated run (e.g. one year of trading). */
    val tradesPerRun: Int,
    /** Number of independent runs to simulate. */
    val runs: Int,
    /** Equity is considered "ruined" once it falls to this fraction of the start (e.g. 0.5 = -50%). */
    val ruinThresholdFraction: Double,
) {
    val isValid: Boolean
        get() = winRate in 0.0..1.0 &&
            avgWinR >= 0.0 &&
            avgLossR >= 0.0 &&
            riskPerTradeFraction in 0.0..1.0 &&
            tradesPerRun in 1..MAX_TRADES &&
            runs in 1..MAX_RUNS &&
            ruinThresholdFraction in 0.0..1.0

    /** Per-trade expectancy in R: winRate*avgWin - lossRate*avgLoss. */
    val expectancyR: Double get() = winRate * avgWinR - (1.0 - winRate) * avgLossR

    companion object {
        const val MAX_TRADES = 2000
        const val MAX_RUNS = 20_000

        val DEFAULT = RiskSimulationInput(
            winRate = 0.45,
            avgWinR = 2.0,
            avgLossR = 1.0,
            riskPerTradeFraction = 0.01,
            tradesPerRun = 100,
            runs = 2000,
            ruinThresholdFraction = 0.5,
        )
    }
}

/**
 * Aggregated output of a Monte Carlo risk simulation.
 *
 * [riskOfRuinFraction] is the share of runs that hit the ruin threshold. The percentile fields describe
 * the distribution of *ending equity as a multiple of the starting equity* across all runs, so 1.0 is
 * break-even, 1.5 is +50%, 0.7 is -30%.
 */
data class RiskSimulationResult(
    val expectancyR: Double,
    val riskOfRuinFraction: Double,
    val medianEndMultiple: Double,
    val meanEndMultiple: Double,
    val p5EndMultiple: Double,
    val p25EndMultiple: Double,
    val p75EndMultiple: Double,
    val p95EndMultiple: Double,
    /** Fraction of runs that ended above the starting equity. */
    val profitableRunFraction: Double,
    /** Median of each run's maximum peak-to-trough drawdown (as a fraction, 0..1). */
    val medianMaxDrawdownFraction: Double,
    /** 95th-percentile (worst realistic) max drawdown across runs. */
    val p95MaxDrawdownFraction: Double,
    /** A handful of representative equity curves (equity multiple per trade) for plotting. */
    val sampleEquityCurves: List<List<Double>>,
    val runsSimulated: Int,
    val narrative: String,
) {
    val riskOfRuinPercent: Double get() = riskOfRuinFraction * 100.0
    val isSurvivable: Boolean get() = riskOfRuinFraction <= 0.05 && expectancyR > 0.0

    companion object {
        fun empty(reason: String): RiskSimulationResult = RiskSimulationResult(
            expectancyR = 0.0,
            riskOfRuinFraction = 0.0,
            medianEndMultiple = 1.0,
            meanEndMultiple = 1.0,
            p5EndMultiple = 1.0,
            p25EndMultiple = 1.0,
            p75EndMultiple = 1.0,
            p95EndMultiple = 1.0,
            profitableRunFraction = 0.0,
            medianMaxDrawdownFraction = 0.0,
            p95MaxDrawdownFraction = 0.0,
            sampleEquityCurves = emptyList(),
            runsSimulated = 0,
            narrative = reason,
        )
    }
}
