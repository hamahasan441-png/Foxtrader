package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput

/**
 * The contract every reasoning agent implements.
 *
 * Agents are single-concern and independent — they do not call each other.
 * The [AgentOrchestrator] runs them and aggregates their [AgentOutput]s.
 *
 * NON-REPAINTING: `analyze` must only read confirmed candle history
 * (`context.candles`, treating the last element as the last closed bar). It
 * must never peek at data beyond the provided series.
 *
 * Adding a new agent must NOT modify existing agents (Open/Closed principle).
 */
interface TradingAgent {
    val name: AgentName
    val description: String
    val version: String

    /** Analyze the given context and produce output. Must not throw. */
    fun analyze(context: AgentContext): AgentOutput

    /** Reset any internal state (agents are stateless by default). */
    fun reset() {}
}
