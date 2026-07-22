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
 * NEWS agent — assesses whether high-impact news is imminent and can BLOCK
 * if within a blackout window. Runs in phase 1 (foundational).
 *
 * Inputs via [AgentContext]: `minutesToHighImpactNews` (null = unknown / no
 * calendar), `inNewsBlackout` (true when manually set by user or by the news
 * engine detecting a red-flag event within the blackout window).
 *
 * Behavior:
 * - Blackout active OR < 15 min to a red event → emits a BLOCK insight.
 * - 15..60 min out → caution (NEUTRAL, no block).
 * - No upcoming news or > 60 min → NEUTRAL, no insights.
 */
class NewsAgent @Inject constructor() : TradingAgent {

    override val name = AgentName.NEWS
    override val description = "High-impact news proximity — can BLOCK during blackout windows."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val now = System.currentTimeMillis()

        // Explicit blackout flag.
        if (context.inNewsBlackout) {
            return blockOutput("News blackout active — stand aside.", start, now)
        }

        val minutes = context.minutesToHighImpactNews
        if (minutes == null) {
            // No calendar data → cannot assess; stay neutral.
            return neutralOutput(name, "No news calendar data available.", start)
        }

        return when {
            minutes < BLACKOUT_MINUTES -> blockOutput(
                "High-impact news in $minutes min — inside blackout window ($BLACKOUT_MINUTES min).",
                start, now
            )
            minutes < CAUTION_MINUTES -> AgentOutput(
                agentName = name,
                status = AgentStatus.COMPLETE,
                bias = Bias.NEUTRAL,
                confidence = 40.0,
                insights = listOf(
                    AgentInsight(
                        id = "$name-CAUTION",
                        agentName = name,
                        type = "NEWS_CAUTION",
                        direction = null,
                        confidence = 40.0,
                        timestamp = now,
                        detail = "High-impact news in $minutes min — caution, do not overcommit.",
                        weight = 0.5,
                        tags = listOf("NEWS_CAUTION"),
                    )
                ),
                narrative = "News in $minutes min — caution.",
                processingTimeMs = elapsedMs(start),
                timestamp = now,
            )
            else -> neutralOutput(name, "No imminent high-impact news (>$CAUTION_MINUTES min out).", start)
        }
    }

    private fun blockOutput(detail: String, startNanos: Long, now: Long) = AgentOutput(
        agentName = name,
        status = AgentStatus.COMPLETE,
        bias = Bias.NEUTRAL,
        confidence = 0.0,
        insights = listOf(
            AgentInsight(
                id = "$name-BLOCK",
                agentName = name,
                type = "NEWS_BLACKOUT",
                direction = null,
                confidence = 100.0,
                timestamp = now,
                detail = detail,
                weight = 2.0,
                tags = listOf("BLOCK"),
            )
        ),
        narrative = "NEWS BLOCK: $detail",
        processingTimeMs = elapsedMs(startNanos),
        timestamp = now,
    )

    private companion object {
        const val BLACKOUT_MINUTES = 15
        const val CAUTION_MINUTES = 60
    }
}
