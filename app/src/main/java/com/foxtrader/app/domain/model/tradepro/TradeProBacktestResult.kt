package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Direction

/**
 * A single trade replayed by the [com.foxtrader.app.domain.usecase.tradepro.TradeProBacktestEngine].
 *
 * [netPoints] is the realised P&L in *point-contracts* (points multiplied by contracts) as produced
 * by the [com.foxtrader.app.domain.usecase.tradepro.TradeManagementEngine] over the trade's life, so
 * it already reflects the 3-contract T1/T2/runner split. Risk is reported in points-per-contract.
 */
data class TradeProBacktestTrade(
    val symbol: String,
    val direction: Direction,
    val entryPrice: Double,
    /** Timestamp of the bar on which the setup was executed. */
    val entryTimestamp: Long,
    /** Timestamp of the bar on which the trade closed. */
    val exitTimestamp: Long,
    val contracts: Int,
    /** Structural stop distance in points, per contract, at entry. */
    val riskPointsPerContract: Double,
    /** Realised P&L in point-contracts across the whole position. */
    val netPoints: Double,
    /** [netPoints] expressed in R (multiples of the total position risk). */
    val rMultiple: Double,
    val reachedT1: Boolean,
    val reachedT2: Boolean,
    val reachedRunner: Boolean,
    val exitReason: String,
    val confidence: Int,
    val confluences: List<String>,
) {
    val isWin: Boolean get() = netPoints > 0.0
    val isLoss: Boolean get() = netPoints < 0.0
}

/**
 * The result of replaying the full TRADEPRO lifecycle (signal -> 3-contract management -> exit)
 * bar-by-bar over a stretch of history. Metrics are point-based (points don't change with contract
 * size, so the report stays comparable across instruments and account sizes) — matching the
 * framework's discipline of measuring the *process*, not raw dollars.
 */
data class TradeProBacktestResult(
    val symbol: String,
    val trades: List<TradeProBacktestTrade>,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val breakeven: Int,
    /** Fraction of trades that ended net-positive, in 0..1. */
    val winRate: Double,
    /** Total realised P&L in point-contracts. */
    val netPoints: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    /** grossProfit / grossLoss. [Double.POSITIVE_INFINITY] when there are no losing trades. */
    val profitFactor: Double,
    /** Average net point-contracts per trade. */
    val expectancy: Double,
    val avgWin: Double,
    val avgLoss: Double,
    /** Average R across all trades. */
    val avgR: Double,
    /** Largest peak-to-trough drop of the cumulative point-contract equity curve. */
    val maxDrawdownPoints: Double,
    val maxWinStreak: Int,
    val maxLossStreak: Int,
    /** Fraction of trades that reached T1 / T2 / the runner target (each in 0..1). */
    val t1HitRate: Double,
    val t2HitRate: Double,
    val runnerHitRate: Double,
    /**
     * Win rate T1+T2 alone must clear for the plan to break even given the stop
     * (the course's ~42.9% figure). Compare [winRate] against this to judge the edge.
     */
    val requiredBreakevenWinRate: Double,
    /** Cumulative net point-contracts after each closed trade, in order. */
    val equityCurve: List<Double>,
    val narrative: String,
) {
    /** True when the strategy's realised win rate clears the plan's break-even threshold. */
    val beatsBreakeven: Boolean get() = totalTrades > 0 && winRate >= requiredBreakevenWinRate

    companion object {
        fun empty(symbol: String, reason: String): TradeProBacktestResult = TradeProBacktestResult(
            symbol = symbol,
            trades = emptyList(),
            totalTrades = 0,
            wins = 0,
            losses = 0,
            breakeven = 0,
            winRate = 0.0,
            netPoints = 0.0,
            grossProfit = 0.0,
            grossLoss = 0.0,
            profitFactor = 0.0,
            expectancy = 0.0,
            avgWin = 0.0,
            avgLoss = 0.0,
            avgR = 0.0,
            maxDrawdownPoints = 0.0,
            maxWinStreak = 0,
            maxLossStreak = 0,
            t1HitRate = 0.0,
            t2HitRate = 0.0,
            runnerHitRate = 0.0,
            requiredBreakevenWinRate = 0.0,
            equityCurve = emptyList(),
            narrative = reason,
        )
    }
}
