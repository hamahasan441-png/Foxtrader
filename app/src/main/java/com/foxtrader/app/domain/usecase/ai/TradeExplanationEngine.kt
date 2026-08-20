package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject

/**
 * Deterministic Trade Explanation Engine.
 *
 * The LLM (when configured) may polish text, but it must never decide. This
 * engine turns an already-computed [DecisionResult] into a trader-readable
 * explanation with the setup story, missing confluences, invalidation and a
 * checklist. It is pure domain logic and safe for alerts, journal annotations,
 * backtest reports and chart panels.
 */
class TradeExplanationEngine @Inject constructor() {

    fun explain(
        decision: DecisionResult,
        symbol: String,
        timeframe: Timeframe,
        entryPrice: Double? = null,
        stopLoss: Double? = null,
        takeProfit: Double? = null,
        riskVerdict: String? = null,
    ): TradeExplanation {
        val directionText = decision.direction?.humanLabel().orEmpty()
        val title = if (decision.approved && decision.direction != null) {
            "$directionText $symbol ${decision.grade.humanLabel()} setup"
        } else {
            "No-trade verdict for $symbol"
        }
        val confluenceSummary = buildConfluenceSummary(decision)
        val setupStory = buildSetupStory(decision, symbol, timeframe)
        val invalidation = buildInvalidation(decision, stopLoss)
        val targetNarrative = buildTargetNarrative(entryPrice, stopLoss, takeProfit)
        val riskNarrative = riskVerdict?.takeIf { it.isNotBlank() }
            ?: if (decision.vetoedBy != null) {
                "Risk gate: vetoed by ${decision.vetoedBy}."
            } else if (decision.approved) {
                "Risk gate: no veto in the master decision. Confirm position sizing before execution."
            } else {
                "Risk gate: stand aside until rejection reasons are resolved."
            }
        val summary = if (decision.approved && decision.direction != null) {
            "Approved ${decision.direction.humanLabel()} idea: ${decision.confluencePresent.size}/9 confluences, " +
                "${decision.confidence.toInt()}% confidence, ${decision.grade.humanLabel()} grade."
        } else {
            "Rejected idea: ${decision.blockReasons.joinToString("; ").ifBlank { "no actionable consensus" }}"
        }

        return TradeExplanation(
            title = title,
            summary = summary,
            setupStory = setupStory,
            confluenceSummary = confluenceSummary,
            riskNarrative = riskNarrative,
            invalidation = invalidation,
            targetNarrative = targetNarrative,
            actionChecklist = buildChecklist(decision),
            presentConfluences = decision.confluencePresent.map { it.humanLabel() },
            missingConfluences = decision.confluenceMissing.map { it.humanLabel() },
            tags = buildTags(decision, symbol, timeframe),
        )
    }

    /** Short alert/journal body optimized for small surfaces. */
    fun compact(decision: DecisionResult, symbol: String): String {
        return if (decision.approved && decision.direction != null) {
            "$symbol ${decision.direction.humanLabel()} ${decision.grade.humanLabel()}: " +
                "${decision.confluencePresent.size}/9 confluences, ${decision.confidence.toInt()}% confidence."
        } else {
            "$symbol no trade: ${decision.blockReasons.firstOrNull() ?: "no consensus"}"
        }
    }

    private fun buildSetupStory(decision: DecisionResult, symbol: String, timeframe: Timeframe): String {
        if (!decision.approved || decision.direction == null) {
            return "$symbol ${timeframe.label}: no executable story. Wait for directional consensus and required confluences."
        }
        val ordered = RequiredConfluence.all().filter { it in decision.confluencePresent }
        val factors = ordered.joinToString(" → ") { it.humanLabel() }
        return "$symbol ${timeframe.label}: ${decision.direction.humanLabel()} thesis confirmed by $factors."
    }

    private fun buildConfluenceSummary(decision: DecisionResult): String {
        val present = decision.confluencePresent.joinToString(", ") { it.humanLabel() }.ifBlank { "none" }
        val missing = decision.confluenceMissing.joinToString(", ") { it.humanLabel() }.ifBlank { "none" }
        return "Present: $present. Missing: $missing."
    }

    private fun buildInvalidation(decision: DecisionResult, stopLoss: Double?): String {
        if (!decision.approved || decision.direction == null) {
            return "Invalidation: not applicable — trade is rejected."
        }
        val stopText = stopLoss?.let { " at $it" }.orEmpty()
        return when (decision.direction) {
            Direction.BULLISH -> "Invalidation: bullish thesis fails on acceptance below stop/structure$stopText."
            Direction.BEARISH -> "Invalidation: bearish thesis fails on acceptance above stop/structure$stopText."
        }
    }

    private fun buildTargetNarrative(entryPrice: Double?, stopLoss: Double?, takeProfit: Double?): String? {
        if (entryPrice == null || stopLoss == null || takeProfit == null) return null
        val risk = kotlin.math.abs(entryPrice - stopLoss)
        val reward = kotlin.math.abs(takeProfit - entryPrice)
        val rr = if (risk > 0.0) reward / risk else 0.0
        return "Plan: entry $entryPrice, stop $stopLoss, target $takeProfit, R:R ${"%.2f".format(rr)}."
    }

    private fun buildChecklist(decision: DecisionResult): List<String> {
        if (!decision.approved) {
            return listOf(
                "Do not execute.",
                "Review rejection reasons.",
                "Wait for missing confluences or fresh structure.",
            )
        }
        return listOf(
            "Confirm spread/slippage is acceptable.",
            "Run RiskEngine position sizing.",
            "Use bracket/OCO protection before execution.",
            "Record the setup and emotional state.",
        )
    }

    private fun buildTags(decision: DecisionResult, symbol: String, timeframe: Timeframe): List<String> =
        buildList {
            add(symbol.uppercase())
            add(timeframe.label)
            add(decision.grade.name)
            decision.direction?.let { add(it.name) }
            addAll(decision.confluencePresent.map { it.name })
            if (!decision.approved) add("NO_TRADE")
        }

    private fun Direction.humanLabel(): String = when (this) {
        Direction.BULLISH -> "Bullish"
        Direction.BEARISH -> "Bearish"
    }

    private fun SignalGrade.humanLabel(): String = when (this) {
        SignalGrade.NO_SIGNAL -> "No Signal"
        SignalGrade.WEAK -> "Weak"
        SignalGrade.MODERATE -> "Moderate"
        SignalGrade.STRONG -> "Strong"
        SignalGrade.VERY_STRONG -> "Very Strong"
        SignalGrade.INSTITUTIONAL -> "Institutional"
    }

    private fun RequiredConfluence.humanLabel(): String = when (this) {
        RequiredConfluence.LIQUIDITY_SWEEP -> "Liquidity Sweep"
        RequiredConfluence.BOS_OR_CHOCH -> "BOS/CHOCH"
        RequiredConfluence.FVG -> "Fair Value Gap"
        RequiredConfluence.ORDER_BLOCK -> "Order Block"
        RequiredConfluence.SMT -> "SMT Divergence"
        RequiredConfluence.SESSION -> "Session/Kill Zone"
        RequiredConfluence.HTF_BIAS -> "HTF Bias"
        RequiredConfluence.TREND -> "Trend"
        RequiredConfluence.VOLUME -> "Volume"
    }
}

data class TradeExplanation(
    val title: String,
    val summary: String,
    val setupStory: String,
    val confluenceSummary: String,
    val riskNarrative: String,
    val invalidation: String,
    val targetNarrative: String?,
    val actionChecklist: List<String>,
    val presentConfluences: List<String>,
    val missingConfluences: List<String>,
    val tags: List<String>,
)
