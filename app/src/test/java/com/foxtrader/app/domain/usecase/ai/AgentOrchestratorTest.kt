package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AgentOrchestrator — phased execution, weighted aggregation,
 * consensus counting, and error isolation.
 */
class AgentOrchestratorTest {

    /** A deterministic fake agent returning a fixed output. */
    private class FakeAgent(
        override val name: AgentName,
        private val bias: Bias,
        private val confidence: Double,
    ) : TradingAgent {
        override val description = "fake"
        override val version = "test"
        override fun analyze(context: AgentContext): AgentOutput = AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = emptyList(),
            narrative = "",
        )
    }

    /** An agent that throws — the orchestrator must isolate it as ERROR. */
    private class ExplodingAgent(override val name: AgentName) : TradingAgent {
        override val description = "boom"
        override val version = "test"
        override fun analyze(context: AgentContext): AgentOutput = error("boom")
    }

    private fun context() = AgentContext(
        symbol = "EURUSD",
        timeframe = Timeframe.H1,
        candles = listOf(Candle(0L, 100.0, 101.0, 99.0, 100.5, 100.0)),
    )

    @Test
    fun `approves when five agents agree bullish with sufficient confidence`() {
        val orchestrator = AgentOrchestrator()
        listOf(
            AgentName.MARKET_STRUCTURE, AgentName.TREND, AgentName.VOLUME,
            AgentName.SMART_MONEY, AgentName.ICT,
        ).forEach { orchestrator.registerAgent(FakeAgent(it, Bias.BULLISH, 80.0)) }
        orchestrator.registerAgent(FakeAgent(AgentName.RISK, Bias.NEUTRAL, 100.0))

        val result = orchestrator.analyze(context())

        assertEquals(Bias.BULLISH, result.aggregateBias)
        assertEquals(Direction.BULLISH, result.signalDirection)
        assertTrue("Consensus should be >= 5", result.agentConsensus >= 5)
        assertTrue("Confidence should clear 60%", result.aggregateConfidence >= 60.0)
        assertTrue(result.signalApproved)
    }

    @Test
    fun `no signal when bullish and bearish are balanced`() {
        val orchestrator = AgentOrchestrator()
        orchestrator.registerAgent(FakeAgent(AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0))
        orchestrator.registerAgent(FakeAgent(AgentName.TREND, Bias.BEARISH, 80.0))
        orchestrator.registerAgent(FakeAgent(AgentName.VOLUME, Bias.BULLISH, 80.0))
        orchestrator.registerAgent(FakeAgent(AgentName.SMART_MONEY, Bias.BEARISH, 80.0))

        val result = orchestrator.analyze(context())

        // Balanced within the 15% edge threshold -> neutral, no signal.
        assertEquals(Bias.NEUTRAL, result.aggregateBias)
        assertNull(result.signalDirection)
        assertFalse(result.signalApproved)
    }

    @Test
    fun `a throwing agent is isolated as ERROR and does not break analysis`() {
        val orchestrator = AgentOrchestrator()
        orchestrator.registerAgent(ExplodingAgent(AgentName.MARKET_STRUCTURE))
        orchestrator.registerAgent(FakeAgent(AgentName.TREND, Bias.BULLISH, 80.0))

        val result = orchestrator.analyze(context())

        assertEquals(AgentStatus.ERROR, result.agentOutputs[AgentName.MARKET_STRUCTURE]?.status)
        assertEquals(AgentStatus.COMPLETE, result.agentOutputs[AgentName.TREND]?.status)
    }

    @Test
    fun `registered agents are reported`() {
        val orchestrator = AgentOrchestrator()
        orchestrator.registerAgent(FakeAgent(AgentName.TREND, Bias.BULLISH, 50.0))
        assertTrue(orchestrator.registeredAgents().contains(AgentName.TREND))
    }
}
