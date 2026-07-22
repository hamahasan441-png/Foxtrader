package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import javax.inject.Inject

/**
 * RISK agent — the trade veto. Consults the [RiskEngine]'s pre-trade gate
 * (daily/weekly loss, drawdown, consecutive losses, halt state). If risk is
 * breached it emits a BLOCK-tagged insight, which the Master Decision Engine
 * treats as an absolute veto. Runs in phase 2.
 */
class RiskAgent @Inject constructor(
    private val riskEngine: RiskEngine,
) : TradingAgent {

    override val name = AgentName.RISK
    override val description = "Pre-trade risk gating — can BLOCK any signal regardless of setup."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val now = System.currentTimeMillis()

        val check = riskEngine.canOpenTrade(riskAmount = 0.0)
        if (check.allowed) {
            return AgentOutput(
                agentName = name,
                status = AgentStatus.COMPLETE,
                bias = Bias.NEUTRAL,
                confidence = 100.0,
                insights = emptyList(),
                narrative = "Risk OK — no active limits breached.",
                processingTimeMs = elapsedMs(start),
                timestamp = now,
            )
        }

        val insights = check.reasons.mapIndexed { i, reason ->
            AgentInsight(
                id = "${name}-BLOCK-$i",
                agentName = name,
                type = "RISK_BLOCK",
                direction = null,
                confidence = 100.0,
                timestamp = now,
                detail = reason,
                weight = 2.0,
                tags = listOf("BLOCK"),
            )
        }

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = Bias.NEUTRAL,
            confidence = 0.0,
            insights = insights,
            narrative = "Risk BLOCK: ${check.reasons.joinToString("; ")}",
            processingTimeMs = elapsedMs(start),
            timestamp = now,
        )
    }
}
