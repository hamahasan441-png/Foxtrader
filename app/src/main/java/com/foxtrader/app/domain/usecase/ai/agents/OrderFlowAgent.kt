package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.tradepro.AbsorptionDetector
import com.foxtrader.app.domain.usecase.tradepro.CandleDerivedOrderFlowProvider
import com.foxtrader.app.domain.usecase.tradepro.ImbalanceDetector
import kotlin.math.abs
import javax.inject.Inject

/**
 * ORDER_FLOW agent — brings the TRADEPRO order-flow read into the multi-agent confluence engine.
 *
 * Unlike [VolumeAgent] (which sums whole-candle volume by candle colour), this agent works on
 * per-bar order flow: a CLV-based buy/sell split ([CandleDerivedOrderFlowProvider]), stacked
 * [ImbalanceDetector] imbalances, and [AbsorptionDetector] events. It emits:
 *  - a DELTA insight (net aggressive pressure) — satisfies the VOLUME confluence,
 *  - an IMBALANCE insight when recent bars stack the same side (commitment),
 *  - an ABSORPTION insight (reversal warning) when heavy aggression fails to move price.
 *
 * Foundational agent (phase 1). Fidelity note: on candle-only feeds the flow is a proxy
 * (OrderFlowSource.CANDLE_DERIVED); a real tape feed lifts it without touching this agent.
 */
class OrderFlowAgent @Inject constructor(
    private val orderFlowProvider: CandleDerivedOrderFlowProvider,
    private val imbalanceDetector: ImbalanceDetector,
    private val absorptionDetector: AbsorptionDetector,
) : TradingAgent {

    override val name = AgentName.ORDER_FLOW
    override val description = "Order flow: delta, stacked imbalances, and absorption (TRADEPRO)."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < MIN_BARS) {
            return neutralOutput(name, "Insufficient data for order-flow analysis.", start)
        }
        val bars = orderFlowProvider.toOrderFlow(candles)
        if (bars.isEmpty()) return neutralOutput(name, "No order flow.", start)

        val window = bars.takeLast(WINDOW)
        val buy = window.sumOf { it.buyVolume }
        val sell = window.sumOf { it.sellVolume }
        val total = buy + sell
        if (total <= 0.0) return neutralOutput(name, "No volume in window.", start)

        val delta = buy - sell
        val deltaDir = when {
            delta > 0 -> Direction.BULLISH
            delta < 0 -> Direction.BEARISH
            else -> null
        }
        val dominance = (abs(delta) / total) * 100.0

        val imbalances = imbalanceDetector.detect(window, IMBALANCE_RATIO)
        val bullStack = imbalances.count { it.direction == Direction.BULLISH }
        val bearStack = imbalances.count { it.direction == Direction.BEARISH }
        val stackDir = when {
            bullStack >= MIN_STACK && bullStack > bearStack -> Direction.BULLISH
            bearStack >= MIN_STACK && bearStack > bullStack -> Direction.BEARISH
            else -> null
        }

        val absorption = absorptionDetector.detect(bars).lastOrNull { it.index >= bars.size - CONFIRM_WINDOW }

        val last = candles.last()
        val insights = mutableListOf<AgentInsight>()
        if (deltaDir != null) {
            insights += AgentInsight(
                id = "$name-DELTA-${candles.lastIndex}",
                agentName = name,
                type = "DELTA",
                direction = deltaDir,
                confidence = dominance,
                price = last.close,
                timestamp = last.timestamp,
                barIndex = candles.lastIndex,
                detail = "Order-flow delta ${if (delta > 0) "+" else ""}${"%.0f".format(delta)} " +
                    "(${"%.0f".format(dominance)}% dominance)",
                weight = 1.0,
                tags = listOf("DELTA", "VOLUME", "ORDER_FLOW"),
            )
        }
        if (stackDir != null) {
            val count = if (stackDir == Direction.BULLISH) bullStack else bearStack
            insights += AgentInsight(
                id = "$name-IMBALANCE-${candles.lastIndex}",
                agentName = name,
                type = "IMBALANCE",
                direction = stackDir,
                confidence = (40.0 + count * 12.0).coerceAtMost(100.0),
                price = last.close,
                timestamp = last.timestamp,
                barIndex = candles.lastIndex,
                detail = "$count stacked $stackDir imbalances in the last $WINDOW bars",
                weight = 1.0,
                tags = listOf("IMBALANCE", "ORDER_FLOW"),
            )
        }
        if (absorption != null) {
            val reversalDir = if (absorption.absorbedSide == Direction.BULLISH) {
                Direction.BEARISH
            } else {
                Direction.BULLISH
            }
            insights += AgentInsight(
                id = "$name-ABSORPTION-${absorption.index}",
                agentName = name,
                type = "ABSORPTION",
                direction = reversalDir,
                confidence = absorption.strength,
                price = absorption.price,
                timestamp = absorption.timestamp,
                barIndex = absorption.index,
                detail = absorption.detail,
                weight = 0.9,
                tags = listOf("ABSORPTION", "ORDER_FLOW"),
            )
        }

        val bias = biasFrom(deltaDir)
        var confidence = dominance
        if (stackDir != null && stackDir == deltaDir) confidence = (confidence + STACK_BONUS).coerceAtMost(100.0)
        confidence = confidence.coerceIn(0.0, 100.0)

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = insights,
            narrative = "Order flow $bias — ${"%.0f".format(dominance)}% delta dominance" +
                (stackDir?.let { ", $it imbalances stacked" } ?: "") +
                (absorption?.let { ", absorption detected" } ?: "") + ".",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val MIN_BARS = 30
        const val WINDOW = 20
        const val IMBALANCE_RATIO = 3.0
        const val MIN_STACK = 2
        const val CONFIRM_WINDOW = 3
        const val STACK_BONUS = 15.0
    }
}
