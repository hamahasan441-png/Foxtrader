package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import javax.inject.Inject

/**
 * PSYCHOLOGY agent — protects the trader from themselves. Detects tilt
 * (consecutive recent losses) and overtrading (too many trades today) and can
 * BLOCK, which the Master Decision Engine treats as an absolute veto.
 * Runs in phase 2.
 */
class PsychologyAgent @Inject constructor() : TradingAgent {

    override val name = AgentName.PSYCHOLOGY
    override val description = "Tilt and overtrading protection — can BLOCK signals."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val now = System.currentTimeMillis()
        val insights = mutableListOf<AgentInsight>()

        // Tilt: N consecutive losses at the tail of recent results.
        val trailingLosses = context.recentTradeResults.reversed().takeWhile { !it }.size
        if (trailingLosses >= TILT_LOSS_STREAK) {
            insights += AgentInsight(
                id = "$name-TILT",
                agentName = name,
                type = "TILT",
                direction = null,
                confidence = 100.0,
                timestamp = now,
                detail = "$trailingLosses consecutive losses — step away (tilt risk).",
                weight = 2.0,
                tags = listOf("BLOCK"),
            )
        }

        // Overtrading: too many trades already taken today.
        if (context.tradeCountToday >= MAX_TRADES_PER_DAY) {
            insights += AgentInsight(
                id = "$name-OVERTRADING",
                agentName = name,
                type = "OVERTRADING",
                direction = null,
                confidence = 100.0,
                timestamp = now,
                detail = "${context.tradeCountToday} trades today — overtrading limit reached.",
                weight = 2.0,
                tags = listOf("BLOCK"),
            )
        }

        val blocked = insights.isNotEmpty()
        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = Bias.NEUTRAL,
            confidence = if (blocked) 0.0 else 100.0,
            insights = insights,
            narrative = if (blocked) {
                "Psychology BLOCK: ${insights.joinToString("; ") { it.detail }}"
            } else {
                "Psychology OK — disciplined state."
            },
            processingTimeMs = elapsedMs(start),
            timestamp = now,
        )
    }

    private companion object {
        const val TILT_LOSS_STREAK = 3
        const val MAX_TRADES_PER_DAY = 10
    }
}
