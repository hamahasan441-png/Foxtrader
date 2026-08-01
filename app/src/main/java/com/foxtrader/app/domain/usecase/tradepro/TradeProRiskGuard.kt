package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.HoldZone
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProManagementPlan
import javax.inject.Inject

/** A snapshot of the trader's day, in points, used to enforce TRADEPRO edge-protection rules. */
data class TradeProDailyState(
    val consecutiveLosses: Int = 0,
    val cumulativeLossPoints: Double = 0.0,
    val tradesToday: Int = 0,
    /** Rolling plan-compliance percentage (0..100). Null when not yet measured. */
    val compliancePercent: Double? = null,
)

/** Result of the pre-trade edge-protection gate. */
data class TradeProRiskDecision(
    val allowed: Boolean,
    val reasons: List<String>,
)

/**
 * Enforces TRADEPRO edge protection: risk is planned in *points*, stops are structural (never
 * comfort-based), and the day is hard-capped. Also builds the 3-contract management plan.
 *
 * This guard is intentionally *stateless* — the caller passes a [TradeProDailyState]. It complements
 * the app's stateful [com.foxtrader.app.domain.usecase.risk.RiskEngine] (which tracks dollars/percent)
 * by expressing the framework's point-based rules.
 */
class TradeProRiskGuard @Inject constructor() {

    /** Hard daily limits: stop after N consecutive losses, or a cap of loss-points, or low compliance. */
    fun canTrade(state: TradeProDailyState, config: TradeProConfig): TradeProRiskDecision {
        val reasons = ArrayList<String>()
        if (state.consecutiveLosses >= config.maxConsecutiveLosses) {
            reasons += "Hit ${config.maxConsecutiveLosses} consecutive losses — stop for the day."
        }
        if (state.cumulativeLossPoints >= config.maxDailyLossPoints) {
            reasons += "Daily loss ${"%.1f".format(state.cumulativeLossPoints)} pts >= " +
                "${config.maxDailyLossPoints} pt cap — stop for the day."
        }
        val compliance = state.compliancePercent
        if (compliance != null && compliance < config.minCompliancePercent) {
            reasons += "Plan compliance ${"%.0f".format(compliance)}% < " +
                "${config.minCompliancePercent.toInt()}% — step back and review."
        }
        return TradeProRiskDecision(allowed = reasons.isEmpty(), reasons = reasons)
    }

    /**
     * Structural stop: placed beyond the [zone] (where the idea is proven wrong), with at least
     * [TradeProConfig.stopPoints] of room. Returns the stop price. A small [bufferPoints] keeps the
     * stop clear of the exact zone edge.
     */
    fun structuralStop(
        entry: Double,
        direction: Direction,
        zone: HoldZone?,
        config: TradeProConfig,
        bufferPoints: Double = 0.25,
    ): Double {
        val minDistance = config.stopPoints * config.pointSize
        val buffer = bufferPoints * config.pointSize
        return if (direction == Direction.BULLISH) {
            val minStop = entry - minDistance
            val zoneStop = zone?.let { it.low - buffer }
            if (zoneStop != null) minOf(minStop, zoneStop) else minStop
        } else {
            val minStop = entry + minDistance
            val zoneStop = zone?.let { it.high + buffer }
            if (zoneStop != null) maxOf(minStop, zoneStop) else minStop
        }
    }

    /** T1 (+t1Points), T2 (+t2Points) and the runner (structural magnet, else +runnerPoints). */
    fun targets(
        entry: Double,
        direction: Direction,
        config: TradeProConfig,
        runnerMagnet: Double? = null,
    ): Targets {
        val sign = if (direction == Direction.BULLISH) 1.0 else -1.0
        val t1 = entry + sign * config.target1Points * config.pointSize
        val t2 = entry + sign * config.target2Points * config.pointSize
        val runnerDefault = entry + sign * config.runnerPoints * config.pointSize
        val runner = when {
            runnerMagnet == null || !runnerMagnet.isFinite() -> runnerDefault
            direction == Direction.BULLISH -> maxOf(runnerMagnet, t2)
            else -> minOf(runnerMagnet, t2)
        }
        return Targets(t1, t2, runner)
    }

    data class Targets(val t1: Double, val t2: Double, val runner: Double)

    /** Splits contracts into thirds and computes the break-even win rate for T1+T2 alone. */
    fun buildManagementPlan(config: TradeProConfig, stopPoints: Double = config.stopPoints): TradeProManagementPlan {
        val n = config.contracts
        val third = n / 3
        val t1Contracts = if (n >= 3) third else 1
        val t2Contracts = if (n >= 3) third else 0
        val runnerContracts = (n - t1Contracts - t2Contracts).coerceAtLeast(0)

        val winPoints = t1Contracts * config.target1Points + t2Contracts * config.target2Points
        val lossPoints = n * stopPoints
        val breakeven = if (winPoints + lossPoints <= 0.0) 0.0 else lossPoints / (winPoints + lossPoints)

        return TradeProManagementPlan(
            contracts = n,
            stopPoints = stopPoints,
            t1Points = config.target1Points,
            t2Points = config.target2Points,
            t1Contracts = t1Contracts,
            t2Contracts = t2Contracts,
            runnerContracts = runnerContracts,
            totalRiskPoints = lossPoints,
            breakevenWinRate = breakeven,
        )
    }
}
