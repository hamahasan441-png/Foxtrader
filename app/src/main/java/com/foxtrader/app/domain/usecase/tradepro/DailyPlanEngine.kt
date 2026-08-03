package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.tradepro.DailyPlan
import com.foxtrader.app.domain.model.tradepro.DeviationSeverity
import com.foxtrader.app.domain.model.tradepro.FocusItem
import com.foxtrader.app.domain.model.tradepro.MarketRegime
import com.foxtrader.app.domain.model.tradepro.PlanDeviation
import com.foxtrader.app.domain.model.tradepro.RiskPosture
import com.foxtrader.app.domain.model.tradepro.SessionReview
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the pre-market [DailyPlan] and the end-of-day [SessionReview].
 *
 * The plan's *posture* is inferred from recent journal history so risk automatically tightens after a
 * rough patch and relaxes when the process is clean — the framework's edge-protection discipline made
 * automatic. The review then grades the actual session against the plan it set.
 *
 * Pure and deterministic: all inputs are passed in, no I/O. Fully unit-testable.
 */
class DailyPlanEngine @Inject constructor() {

    /**
     * Generate the day's plan.
     *
     * @param analyses per-symbol TRADEPRO reads for the watchlist (already computed upstream).
     * @param recentClosed recent *closed* journal entries, newest last, used to set risk posture.
     * @param config the user's TRADEPRO configuration (risk budgets, contract size).
     * @param nowEpochMs current time for the plan timestamp.
     */
    fun generatePlan(
        analyses: List<TradeProAnalysis>,
        recentClosed: List<JournalEntry>,
        config: TradeProConfig = TradeProConfig(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DailyPlan {
        val posture = inferPosture(recentClosed, config)
        val focus = buildFocus(analyses)
        val regime = inferRegime(focus)

        val dailyBudget = config.maxDailyLossPoints * posture.riskMultiplier
        val riskPerTrade = min(config.stopPoints, config.maxRiskPoints) * posture.riskMultiplier
        val baseMaxTrades = if (riskPerTrade > 0.0) (config.maxDailyLossPoints / riskPerTrade).toInt() else config.maxConsecutiveLosses
        val maxTrades = (baseMaxTrades * posture.tradeMultiplier).roundToInt().coerceIn(1, MAX_TRADES_CAP)

        val rules = buildRules(posture, regime, dailyBudget, maxTrades, config)

        val headline = buildString {
            append("${posture.label} posture \u00B7 ${regime.label}. ")
            append("Budget ${fmt(dailyBudget)} pts, max $maxTrades trades. ")
            append("${focus.size} symbols in focus.")
        }

        return DailyPlan(
            generatedAtEpochMs = nowEpochMs,
            posture = posture,
            marketRegime = regime,
            dailyRiskBudgetPoints = dailyBudget,
            maxTrades = maxTrades,
            riskPerTradePoints = riskPerTrade,
            focus = focus,
            rules = rules,
            headline = headline,
        )
    }

    /**
     * Review the session against [plan] using the trades actually logged for the day.
     *
     * @param todaysEntries journal entries whose entry time falls on the plan's day (closed or open).
     */
    fun reviewSession(plan: DailyPlan, todaysEntries: List<JournalEntry>): SessionReview {
        if (plan.generatedAtEpochMs == 0L) {
            return SessionReview.empty("Generate a plan first, then review the session against it.")
        }

        val closed = todaysEntries.filter { !it.isOpen && it.pnl != null }
        val tradesTaken = todaysEntries.size
        val netPoints = closed.sumOf { it.rMultiple ?: 0.0 } * plan.riskPerTradePoints
        val realisedLossPoints = closed.mapNotNull { it.rMultiple }
            .filter { it < 0.0 }
            .sumOf { abs(it) } * plan.riskPerTradePoints
        val worstDrawdown = worstDrawdownPoints(closed, plan.riskPerTradePoints)

        val deviations = mutableListOf<PlanDeviation>()
        val commendations = mutableListOf<String>()

        // Overtrading.
        if (tradesTaken > plan.maxTrades) {
            deviations += PlanDeviation(
                severity = DeviationSeverity.MODERATE,
                rule = "Trade count",
                detail = "Took $tradesTaken trades against a plan of ${plan.maxTrades}. Overtrading dilutes edge.",
            )
        } else if (tradesTaken > 0) {
            commendations += "Stayed within the $tradesTaken/${plan.maxTrades} trade budget."
        }

        // Risk budget breach.
        if (realisedLossPoints > plan.dailyRiskBudgetPoints && plan.dailyRiskBudgetPoints > 0.0) {
            deviations += PlanDeviation(
                severity = DeviationSeverity.SEVERE,
                rule = "Daily risk budget",
                detail = "Lost ${fmt(realisedLossPoints)} pts against a ${fmt(plan.dailyRiskBudgetPoints)} pt " +
                    "budget. The stop-for-the-day line was crossed.",
            )
        } else if (closed.isNotEmpty()) {
            commendations += "Respected the ${fmt(plan.dailyRiskBudgetPoints)} pt daily risk budget."
        }

        // Off-focus trading.
        val focusSet = plan.focusSymbols.toSet()
        val offFocus = todaysEntries.map { it.symbol }.filter { focusSet.isNotEmpty() && it !in focusSet }.distinct()
        if (offFocus.isNotEmpty()) {
            deviations += PlanDeviation(
                severity = DeviationSeverity.MINOR,
                rule = "Focus list",
                detail = "Traded ${offFocus.joinToString(", ")} \u2014 not on the day's focus list.",
            )
        }

        // Emotional entries.
        val emotional = todaysEntries.count { it.emotionTag in EMOTIONAL_TAGS }
        if (emotional > 0) {
            deviations += PlanDeviation(
                severity = if (emotional >= 2) DeviationSeverity.SEVERE else DeviationSeverity.MODERATE,
                rule = "Emotional control",
                detail = "$emotional trade(s) tagged emotional (FOMO/revenge/fear/greed). These are the leaks.",
            )
        } else if (tradesTaken > 0) {
            commendations += "No emotional entries logged \u2014 clean headspace."
        }

        val adherence = adherenceScore(deviations)
        val followed = adherence >= FOLLOWED_THRESHOLD && deviations.none { it.severity == DeviationSeverity.SEVERE }

        val summary = buildString {
            append("$tradesTaken trades, net ${fmtSigned(netPoints)} pts. ")
            append("Adherence $adherence/100. ")
            append(if (followed) "You traded your plan \u2014 that's the win, regardless of P&L." else "The plan slipped \u2014 review the deviations below.")
        }

        return SessionReview(
            planDateEpochMs = plan.generatedAtEpochMs,
            tradesTaken = tradesTaken,
            plannedMaxTrades = plan.maxTrades,
            netPoints = netPoints,
            riskBudgetPoints = plan.dailyRiskBudgetPoints,
            worstDrawdownPoints = worstDrawdown,
            adherenceScore = adherence,
            followedPlan = followed,
            deviations = deviations,
            commendations = commendations,
            summary = summary,
        )
    }

    // --- Plan building ---

    private fun inferPosture(recentClosed: List<JournalEntry>, config: TradeProConfig): RiskPosture {
        if (recentClosed.size < MIN_HISTORY_FOR_POSTURE) return RiskPosture.NORMAL
        val window = recentClosed.takeLast(POSTURE_WINDOW)

        // Consecutive losses at the tail of the window.
        var trailingLosses = 0
        for (entry in window.asReversed()) {
            if ((entry.pnl ?: 0.0) < 0.0) trailingLosses++ else break
        }

        val netR = window.sumOf { it.rMultiple ?: 0.0 }
        val emotionalShare = window.count { it.emotionTag in EMOTIONAL_TAGS }.toDouble() / window.size

        return when {
            trailingLosses >= config.maxConsecutiveLosses -> RiskPosture.DEFENSIVE
            trailingLosses >= 2 || netR < NEGATIVE_R_THRESHOLD || emotionalShare >= HIGH_EMOTION_SHARE -> RiskPosture.CAUTIOUS
            netR >= STRONG_R_THRESHOLD && emotionalShare <= LOW_EMOTION_SHARE -> RiskPosture.AGGRESSIVE
            else -> RiskPosture.NORMAL
        }
    }

    private fun buildFocus(analyses: List<TradeProAnalysis>): List<FocusItem> =
        analyses.mapNotNull { analysis ->
            val bias = analysis.flipZone?.bias ?: Bias.NEUTRAL
            if (bias == Bias.NEUTRAL && analysis.stage == SetupStage.NONE) return@mapNotNull null
            val keyLevel = analysis.setup?.entry
                ?: analysis.flipZone?.price
                ?: analysis.holdZones.maxByOrNull { it.endIndex }?.mid
                ?: return@mapNotNull null
            FocusItem(
                symbol = analysis.symbol,
                bias = bias,
                stage = analysis.stage,
                keyLevel = keyLevel,
                readinessScore = stageReadiness(analysis.stage, analysis.setup?.confidence ?: 0),
                note = focusNote(analysis),
            )
        }.sortedWith(
            compareByDescending<FocusItem> { it.stage.ordinal }.thenByDescending { it.readinessScore },
        ).take(MAX_FOCUS)

    private fun inferRegime(focus: List<FocusItem>): MarketRegime {
        if (focus.isEmpty()) return MarketRegime.MIXED
        val bull = focus.count { it.bias == Bias.BULLISH }
        val bear = focus.count { it.bias == Bias.BEARISH }
        return when {
            bull > bear * 2 -> MarketRegime.RISK_ON
            bear > bull * 2 -> MarketRegime.RISK_OFF
            else -> MarketRegime.MIXED
        }
    }

    private fun buildRules(
        posture: RiskPosture,
        regime: MarketRegime,
        dailyBudget: Double,
        maxTrades: Int,
        config: TradeProConfig,
    ): List<String> {
        val rules = mutableListOf(
            "Stop for the day at ${fmt(dailyBudget)} pts of loss \u2014 no exceptions.",
            "Cap the session at $maxTrades trades. Quality over quantity.",
            "No trade without a defined stop and a zone. If price hasn't reached the zone, there is no trade.",
            "Manage every position on the 3-contract plan: bank T1, bank T2, trail the runner.",
        )
        if (posture == RiskPosture.DEFENSIVE || posture == RiskPosture.CAUTIOUS) {
            rules += "Recent results say slow down: take only A+ setups, half size, and walk away after one loss."
        }
        when (regime) {
            MarketRegime.RISK_ON -> rules += "Bias is risk-on \u2014 favour longs into demand; fade shorts only at proven supply."
            MarketRegime.RISK_OFF -> rules += "Bias is risk-off \u2014 favour shorts into supply; be quick to bank longs."
            MarketRegime.MIXED -> rules += "Two-way tape \u2014 trade the extremes of the range, not the middle."
        }
        rules += "Stop after ${config.maxConsecutiveLosses} consecutive losses regardless of budget."
        return rules
    }

    // --- Review helpers ---

    private fun worstDrawdownPoints(closed: List<JournalEntry>, riskPerTrade: Double): Double {
        var running = 0.0
        var peak = 0.0
        var maxDd = 0.0
        for (entry in closed) {
            running += (entry.rMultiple ?: 0.0) * riskPerTrade
            if (running > peak) peak = running
            val dd = peak - running
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }

    private fun adherenceScore(deviations: List<PlanDeviation>): Int {
        val penalty = deviations.sumOf {
            when (it.severity) {
                DeviationSeverity.SEVERE -> SEVERE_PENALTY
                DeviationSeverity.MODERATE -> MODERATE_PENALTY
                DeviationSeverity.MINOR -> MINOR_PENALTY
            }
        }
        return (100 - penalty).coerceIn(0, 100)
    }

    private fun stageReadiness(stage: SetupStage, confidence: Int): Int {
        val stageBase = when (stage) {
            SetupStage.EXECUTE -> 80
            SetupStage.CONFIRMATION -> 60
            SetupStage.ZONE -> 45
            SetupStage.LEVEL -> 25
            SetupStage.NONE -> 0
        }
        return (stageBase + confidence / 5).coerceIn(0, 100)
    }

    private fun focusNote(analysis: TradeProAnalysis): String = when (analysis.stage) {
        SetupStage.EXECUTE -> "Executable setup live \u2014 trade the plan."
        SetupStage.CONFIRMATION -> "At the zone, awaiting confirmation."
        SetupStage.ZONE -> "Price in the zone \u2014 watch order flow."
        SetupStage.LEVEL -> "Bias defined \u2014 wait for a pullback to the zone."
        SetupStage.NONE -> "Monitoring for structure."
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmtSigned(v: Double): String = String.format(Locale.US, "%+.1f", v)

    companion object {
        private const val MAX_FOCUS = 6
        private const val MAX_TRADES_CAP = 10
        private const val MIN_HISTORY_FOR_POSTURE = 3
        private const val POSTURE_WINDOW = 10
        private const val NEGATIVE_R_THRESHOLD = -2.0
        private const val STRONG_R_THRESHOLD = 4.0
        private const val HIGH_EMOTION_SHARE = 0.4
        private const val LOW_EMOTION_SHARE = 0.1
        private const val FOLLOWED_THRESHOLD = 75
        private const val SEVERE_PENALTY = 30
        private const val MODERATE_PENALTY = 15
        private const val MINOR_PENALTY = 8
        private val EMOTIONAL_TAGS = setOf(
            com.foxtrader.app.domain.model.EmotionTag.FOMO,
            com.foxtrader.app.domain.model.EmotionTag.REVENGE,
            com.foxtrader.app.domain.model.EmotionTag.FEARFUL,
            com.foxtrader.app.domain.model.EmotionTag.GREEDY,
        )
    }
}
