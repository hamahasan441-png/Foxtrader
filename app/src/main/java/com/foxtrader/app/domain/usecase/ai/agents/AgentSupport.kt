package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction

/** Shared helpers for agent implementations. */

/** True when a [Bias] agrees with a trade [Direction]. NEUTRAL never agrees. */
internal fun Bias.agreesWith(direction: Direction?): Boolean = when (this) {
    Bias.BULLISH -> direction == Direction.BULLISH
    Bias.BEARISH -> direction == Direction.BEARISH
    Bias.NEUTRAL -> false
}

/** Map a nullable [Direction] to a [Bias]. */
internal fun biasFrom(direction: Direction?): Bias = when (direction) {
    Direction.BULLISH -> Bias.BULLISH
    Direction.BEARISH -> Bias.BEARISH
    null -> Bias.NEUTRAL
}

/** Elapsed milliseconds since a `System.nanoTime()` start marker. */
internal fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

/** Build a NEUTRAL, no-insight output (used for insufficient data / no finding). */
internal fun neutralOutput(
    name: AgentName,
    narrative: String,
    startNanos: Long,
    now: Long = System.currentTimeMillis(),
): AgentOutput = AgentOutput(
    agentName = name,
    status = AgentStatus.COMPLETE,
    bias = Bias.NEUTRAL,
    confidence = 0.0,
    insights = emptyList(),
    narrative = narrative,
    processingTimeMs = elapsedMs(startNanos),
    timestamp = now,
)
