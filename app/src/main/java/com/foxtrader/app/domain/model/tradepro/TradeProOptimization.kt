package com.foxtrader.app.domain.model.tradepro

/**
 * The metric a parameter sweep maximises when ranking [TradeProConfig] variants.
 * System Quality (Van Tharp's SQN) is the default: it rewards a high, tight, positive R
 * distribution over many trades, which is a better robustness proxy than raw net points.
 */
enum class OptimizationObjective(val label: String) {
    SYSTEM_QUALITY("System Quality (SQN)"),
    EXPECTANCY("Expectancy"),
    PROFIT_FACTOR("Profit Factor"),
    NET_POINTS("Net Points"),
    ;

    /** The comparable score for [result] under this objective (finite, so sorting is stable). */
    fun score(result: TradeProBacktestResult): Double = when (this) {
        SYSTEM_QUALITY -> result.systemQualityNumber
        EXPECTANCY -> result.expectancy
        PROFIT_FACTOR -> if (result.profitFactor.isFinite()) result.profitFactor else PROFIT_FACTOR_CAP
        NET_POINTS -> result.netPoints
    }

    private companion object {
        /** An infinite profit factor (no losers) is capped so it can be compared/displayed. */
        const val PROFIT_FACTOR_CAP = 1_000_000.0
    }
}

/**
 * Cartesian grid of the highest-impact TRADEPRO parameters to sweep. The defaults deliberately
 * bracket the course baseline (stop 3 / T2 8 / ER 0.30) so the baseline config is always one of the
 * evaluated candidates and improvements are measured against it.
 */
data class TradeProParameterGrid(
    val stopPoints: List<Double> = listOf(2.0, 3.0, 4.0),
    val target2Points: List<Double> = listOf(6.0, 8.0, 12.0),
    val minEfficiencyRatio: List<Double> = listOf(0.20, 0.30, 0.40),
) {
    /**
     * Expand into concrete configs derived from [base]. T1 and the runner scale with T2 (T1 = T2/2,
     * runner = T2*2) so each candidate keeps a coherent staged-exit structure. Bounded by
     * [MAX_COMBINATIONS] to keep the sweep's cost predictable.
     */
    fun expand(base: TradeProConfig): List<TradeProConfig> {
        val out = ArrayList<TradeProConfig>(minOf(MAX_COMBINATIONS, stopPoints.size * target2Points.size * minEfficiencyRatio.size))
        for (stop in stopPoints) {
            for (t2 in target2Points) {
                for (er in minEfficiencyRatio) {
                    out += base.copy(
                        stopPoints = stop,
                        target1Points = t2 / 2.0,
                        target2Points = t2,
                        runnerPoints = t2 * 2.0,
                        minEfficiencyRatio = er,
                    )
                    if (out.size >= MAX_COMBINATIONS) return out
                }
            }
        }
        return out
    }

    companion object {
        const val MAX_COMBINATIONS = 64
    }
}

/**
 * One evaluated parameter set: its [config], the [inSample] backtest it produced, the [score] under
 * the chosen objective, and whether it cleared the minimum-trades bar (an unqualified candidate won
 * on too little evidence to trust).
 */
data class TradeProOptimizationCandidate(
    val config: TradeProConfig,
    val label: String,
    val inSample: TradeProBacktestResult,
    val score: Double,
    val qualified: Boolean,
)

/**
 * The result of a parameter sweep with an in-sample/out-of-sample split. Candidates are ranked on the
 * in-sample (training) slice; the winner is then re-run on held-out [bestOutOfSample] data so the
 * reader can judge whether the tuned edge survives on bars it was never optimised against.
 */
data class TradeProOptimizationReport(
    val symbol: String,
    val objective: OptimizationObjective,
    /** Ranked best-first on in-sample [objective] score (qualified candidates first). */
    val candidates: List<TradeProOptimizationCandidate>,
    val best: TradeProOptimizationCandidate?,
    /** The best config re-run on the held-out slice; null when there wasn't enough OOS data. */
    val bestOutOfSample: TradeProBacktestResult?,
    val inSampleBars: Int,
    val outOfSampleBars: Int,
    val evaluated: Int,
    val narrative: String,
) {
    companion object {
        fun empty(symbol: String, reason: String): TradeProOptimizationReport = TradeProOptimizationReport(
            symbol = symbol,
            objective = OptimizationObjective.SYSTEM_QUALITY,
            candidates = emptyList(),
            best = null,
            bestOutOfSample = null,
            inSampleBars = 0,
            outOfSampleBars = 0,
            evaluated = 0,
            narrative = reason,
        )
    }
}
