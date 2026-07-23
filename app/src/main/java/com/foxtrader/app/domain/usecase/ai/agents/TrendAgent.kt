package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject

/**
 * TREND agent — determines trend direction from EMA alignment and trend
 * strength from ADX. Foundational agent (runs in phase 1).
 */
class TrendAgent @Inject constructor() : TradingAgent {

    override val name = AgentName.TREND
    override val description = "EMA(20/50) alignment for direction, ADX(14) for strength."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < 60) {
            return neutralOutput(name, "Insufficient data for trend analysis.", start)
        }

        val emaFast = TechnicalIndicators.calculateEMA(candles, 20).last()
        val emaSlow = TechnicalIndicators.calculateEMA(candles, 50).last()
        val adx = TechnicalIndicators.calculateADX(candles, 14).adx.last()

        val direction = when {
            emaFast > emaSlow -> Direction.BULLISH
            emaFast < emaSlow -> Direction.BEARISH
            else -> null
        }
        val bias = when (direction) {
            Direction.BULLISH -> Bias.BULLISH
            Direction.BEARISH -> Bias.BEARISH
            null -> Bias.NEUTRAL
        }

        // ADX < 20 = weak/ranging, 25+ = trending, 40+ = very strong.
        val strength = (adx / 50.0 * 100.0).coerceIn(0.0, 100.0)
        // If ADX is weak the market is ranging — downgrade confidence and bias.
        val confidence = if (adx < 20.0) strength * 0.5 else strength
        val effectiveBias = if (adx < 20.0) Bias.NEUTRAL else bias

        val insights = if (direction != null) listOf(
            AgentInsight(
                id = "${name}-TREND-${candles.lastIndex}",
                agentName = name,
                type = "TREND",
                direction = direction,
                confidence = confidence,
                price = candles.last().close,
                timestamp = candles.last().timestamp,
                barIndex = candles.lastIndex,
                detail = "EMA20 ${if (emaFast > emaSlow) ">" else "<"} EMA50, ADX=${"%.1f".format(adx)}",
                weight = 1.2,
                tags = listOf("TREND"),
            )
        ) else emptyList()

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = effectiveBias,
            confidence = confidence,
            insights = insights,
            narrative = "Trend $effectiveBias (ADX ${"%.1f".format(adx)}). " +
                if (adx < 20.0) "Weak/ranging — caution." else "Trending.",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }
}
