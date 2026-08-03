package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Bias

/**
 * A structured pre-market trading plan for the day: the risk posture, budgets and a prioritised focus
 * list, generated from the user's TRADEPRO config plus their recent journal history. The plan exists
 * to impose discipline *before* the emotion of the session starts — you decide the rules while calm.
 */
data class DailyPlan(
    val generatedAtEpochMs: Long,
    val posture: RiskPosture,
    val marketRegime: MarketRegime,
    /** Maximum points the trader is permitted to lose today before stopping. */
    val dailyRiskBudgetPoints: Double,
    /** Recommended maximum number of trades for the day. */
    val maxTrades: Int,
    /** Recommended risk per trade in points, scaled by posture. */
    val riskPerTradePoints: Double,
    val focus: List<FocusItem>,
    val rules: List<String>,
    val headline: String,
) {
    val focusSymbols: List<String> get() = focus.map { it.symbol }

    companion object {
        fun empty(reason: String): DailyPlan = DailyPlan(
            generatedAtEpochMs = 0L,
            posture = RiskPosture.NORMAL,
            marketRegime = MarketRegime.MIXED,
            dailyRiskBudgetPoints = 0.0,
            maxTrades = 0,
            riskPerTradePoints = 0.0,
            focus = emptyList(),
            rules = emptyList(),
            headline = reason,
        )
    }
}

/**
 * The day's risk stance, derived from recent performance. Losing streaks pull the trader toward
 * DEFENSIVE (smaller size, fewer trades); a clean, disciplined run permits NORMAL/AGGRESSIVE.
 */
enum class RiskPosture(val label: String, val riskMultiplier: Double, val tradeMultiplier: Double) {
    DEFENSIVE("Defensive", 0.5, 0.5),
    CAUTIOUS("Cautious", 0.75, 0.75),
    NORMAL("Normal", 1.0, 1.0),
    AGGRESSIVE("Aggressive", 1.25, 1.0),
}

/** Overall directional lean of the focus list. */
enum class MarketRegime(val label: String) {
    RISK_ON("Risk-On (bullish)"),
    RISK_OFF("Risk-Off (bearish)"),
    MIXED("Mixed / two-way"),
}

/**
 * One prioritised symbol on the day's watch, with its bias, the key level to react to, and a note.
 */
data class FocusItem(
    val symbol: String,
    val bias: Bias,
    val stage: SetupStage,
    val keyLevel: Double,
    val readinessScore: Int,
    val note: String,
)

/**
 * The end-of-day review: did the actual session honour the plan? Scores adherence and surfaces the
 * specific deviations (overtrading, exceeding risk, straying off the focus list, emotional entries).
 */
data class SessionReview(
    val planDateEpochMs: Long,
    val tradesTaken: Int,
    val plannedMaxTrades: Int,
    val netPoints: Double,
    val riskBudgetPoints: Double,
    val worstDrawdownPoints: Double,
    val adherenceScore: Int,
    val followedPlan: Boolean,
    val deviations: List<PlanDeviation>,
    val commendations: List<String>,
    val summary: String,
) {
    companion object {
        fun empty(reason: String): SessionReview = SessionReview(
            planDateEpochMs = 0L,
            tradesTaken = 0,
            plannedMaxTrades = 0,
            netPoints = 0.0,
            riskBudgetPoints = 0.0,
            worstDrawdownPoints = 0.0,
            adherenceScore = 100,
            followedPlan = true,
            deviations = emptyList(),
            commendations = emptyList(),
            summary = reason,
        )
    }
}

/**
 * A specific way the session broke from the plan, with severity for triage.
 */
data class PlanDeviation(
    val severity: DeviationSeverity,
    val rule: String,
    val detail: String,
)

enum class DeviationSeverity { MINOR, MODERATE, SEVERE }
