package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.DecisionConfig
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OrchestratorResult
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import javax.inject.Inject

/**
 * MASTER DECISION ENGINE — the ultimate gatekeeper.
 *
 * No single agent may authorize a trade. A signal is approved only when:
 *  1. Neither the RISK nor PSYCHOLOGY agent raised a BLOCK (absolute veto), AND
 *  2. The orchestrator produced a directional signal, AND
 *  3. At least [DecisionConfig.minRequiredConfluences] of the 9 institutional
 *     confluences are present in the signal's direction, AND
 *  4. Aggregate confidence >= [DecisionConfig.minConfidence].
 *
 * Kotlin port of the reference `master-decision-engine.ts`. Pure and
 * deterministic given an [OrchestratorResult].
 */
class MasterDecisionEngine @Inject constructor() {

    private var config: DecisionConfig = DecisionConfig()

    fun updateConfig(newConfig: DecisionConfig) { config = newConfig }
    fun getConfig(): DecisionConfig = config

    fun evaluate(result: OrchestratorResult): DecisionResult {
        val now = System.currentTimeMillis()
        val outputs = result.agentOutputs

        // 1. Risk / Psychology veto (absolute).
        if (config.respectRiskBlock) {
            val riskBlock = outputs[AgentName.RISK]?.insights?.filter { it.isBlock() }.orEmpty()
            val psychBlock = outputs[AgentName.PSYCHOLOGY]?.insights?.filter { it.isBlock() }.orEmpty()
            val veto = when {
                riskBlock.isNotEmpty() -> AgentName.RISK to riskBlock
                psychBlock.isNotEmpty() -> AgentName.PSYCHOLOGY to psychBlock
                else -> null
            }
            if (veto != null) {
                val (who, blocks) = veto
                val reasons = blocks.map { it.detail }
                return DecisionResult(
                    approved = false,
                    direction = null,
                    confidence = 0.0,
                    grade = SignalGrade.NO_SIGNAL,
                    confluencePresent = emptyList(),
                    confluenceMissing = RequiredConfluence.all(),
                    blockReasons = listOf("$who BLOCKED: ${reasons.joinToString(", ")}"),
                    vetoedBy = who,
                    explanation = "DECISION: NO TRADE. $who veto — ${reasons.joinToString("; ")}",
                    timestamp = now,
                )
            }
        }

        // 2. No directional consensus -> stand aside.
        val direction = result.signalDirection
        if (direction == null) {
            return DecisionResult(
                approved = false,
                direction = null,
                confidence = result.aggregateConfidence,
                grade = SignalGrade.NO_SIGNAL,
                confluencePresent = emptyList(),
                confluenceMissing = RequiredConfluence.all(),
                blockReasons = listOf("No directional consensus from agents"),
                vetoedBy = null,
                explanation = "DECISION: No consensus — stand aside.",
                timestamp = now,
            )
        }

        // 3. Required-confluence check.
        val allInsights = outputs.values.flatMap { it.insights }
        val dirInsights = allInsights.filter { it.direction == direction }

        val present = mutableListOf<RequiredConfluence>()
        val missing = mutableListOf<RequiredConfluence>()

        checkConfluence(RequiredConfluence.LIQUIDITY_SWEEP, dirInsights, present, missing,
            setOf("LIQUIDITY_SWEEP", "SWEEP"))
        checkConfluence(RequiredConfluence.BOS_OR_CHOCH, dirInsights, present, missing,
            setOf("BOS", "CHOCH", "MSS"))
        checkConfluence(RequiredConfluence.FVG, dirInsights, present, missing,
            setOf("FVG", "IFVG", "BPR"))
        checkConfluence(RequiredConfluence.ORDER_BLOCK, dirInsights, present, missing,
            setOf("BULLISH_OB", "BEARISH_OB", "BREAKER", "MITIGATION", "ORDER_BLOCK"))
        checkConfluence(RequiredConfluence.SMT, dirInsights, present, missing, setOf("SMT"))

        // SESSION — kill zone present (direction-agnostic).
        if (allInsights.any { it.type == "KILL_ZONE" || it.tags.contains("SESSION") }) {
            present += RequiredConfluence.SESSION
        } else {
            missing += RequiredConfluence.SESSION
        }

        // HTF_BIAS — Market Structure agent agrees with the signal direction.
        if (outputs[AgentName.MARKET_STRUCTURE]?.bias.agreesWith(direction)) {
            present += RequiredConfluence.HTF_BIAS
        } else {
            missing += RequiredConfluence.HTF_BIAS
        }

        // TREND — Trend agent agrees with the signal direction.
        if (outputs[AgentName.TREND]?.bias.agreesWith(direction)) {
            present += RequiredConfluence.TREND
        } else {
            missing += RequiredConfluence.TREND
        }

        // VOLUME — a DELTA insight aligned with the signal direction.
        val deltaAligned = outputs[AgentName.VOLUME]?.insights
            ?.any { it.type == "DELTA" && it.direction == direction } == true
        if (deltaAligned) present += RequiredConfluence.VOLUME else missing += RequiredConfluence.VOLUME

        // 4. Final decision.
        val meetsConfluence = present.size >= config.minRequiredConfluences
        val meetsConfidence = result.aggregateConfidence >= config.minConfidence
        val approved = meetsConfluence && meetsConfidence

        val blockReasons = mutableListOf<String>()
        if (!meetsConfluence) {
            blockReasons += "Only ${present.size}/${config.minRequiredConfluences} required " +
                "confluences present. Missing: ${missing.joinToString(", ")}"
        }
        if (!meetsConfidence) {
            blockReasons += "Confidence ${result.aggregateConfidence.toInt()}% below minimum " +
                "${config.minConfidence.toInt()}%"
        }

        val grade = gradeSignal(present.size, result.aggregateConfidence)

        return DecisionResult(
            approved = approved,
            direction = if (approved) direction else null,
            confidence = result.aggregateConfidence,
            grade = if (approved) grade else SignalGrade.NO_SIGNAL,
            confluencePresent = present,
            confluenceMissing = missing,
            blockReasons = blockReasons,
            vetoedBy = null,
            explanation = if (approved) {
                "DECISION: $direction APPROVED ($grade) — ${present.size}/9 confluences, " +
                    "${result.aggregateConfidence.toInt()}% confidence."
            } else {
                "DECISION: REJECTED — ${blockReasons.joinToString("; ")}"
            },
            timestamp = now,
        )
    }

    private fun checkConfluence(
        confluence: RequiredConfluence,
        insights: List<AgentInsight>,
        present: MutableList<RequiredConfluence>,
        missing: MutableList<RequiredConfluence>,
        matchTypes: Set<String>,
    ) {
        val found = insights.any { it.type in matchTypes || it.tags.any { tag -> tag in matchTypes } }
        if (found) present += confluence else missing += confluence
    }

    private fun gradeSignal(confluenceCount: Int, confidence: Double): SignalGrade = when {
        confluenceCount >= 8 && confidence >= 85 -> SignalGrade.INSTITUTIONAL
        confluenceCount >= 7 && confidence >= 75 -> SignalGrade.VERY_STRONG
        confluenceCount >= 6 && confidence >= 65 -> SignalGrade.STRONG
        confluenceCount >= 5 && confidence >= 55 -> SignalGrade.MODERATE
        confluenceCount >= 4 -> SignalGrade.WEAK
        else -> SignalGrade.NO_SIGNAL
    }

    private fun AgentInsight.isBlock(): Boolean = tags.contains("BLOCK")

    /** Whether a (nullable) [Bias] agrees with a trade [Direction]. */
    private fun Bias?.agreesWith(direction: Direction): Boolean = when (this) {
        Bias.BULLISH -> direction == Direction.BULLISH
        Bias.BEARISH -> direction == Direction.BEARISH
        else -> false
    }
}
