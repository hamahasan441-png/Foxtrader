package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the veto agents (RISK and PSYCHOLOGY). These agents can BLOCK
 * a trade regardless of setup quality — the block is expressed via a "BLOCK" tag.
 */
class VetoAgentsTest {

    private fun context(
        recentResults: List<Boolean> = emptyList(),
        tradesToday: Int = 0,
    ) = AgentContext(
        symbol = "EURUSD",
        timeframe = Timeframe.H1,
        candles = listOf(Candle(0L, 100.0, 101.0, 99.0, 100.5, 100.0)),
        recentTradeResults = recentResults,
        tradeCountToday = tradesToday,
    )

    // ---------------------------------------------------------------- Risk

    @Test
    fun `risk agent allows trade when no limits breached`() {
        val agent = RiskAgent(RiskEngine())
        val output = agent.analyze(context())
        assertTrue("No block insights expected", output.insights.none { it.tags.contains("BLOCK") })
    }

    @Test
    fun `risk agent blocks when trading is halted`() {
        val riskEngine = RiskEngine().apply { haltTrading("test halt") }
        val agent = RiskAgent(riskEngine)

        val output = agent.analyze(context())

        assertTrue("Expected a BLOCK insight", output.insights.any { it.tags.contains("BLOCK") })
    }

    // ---------------------------------------------------------- Psychology

    @Test
    fun `psychology agent blocks on tilt (three consecutive losses)`() {
        val agent = PsychologyAgent()
        val output = agent.analyze(context(recentResults = listOf(false, false, false)))
        assertTrue(output.insights.any { it.type == "TILT" && it.tags.contains("BLOCK") })
    }

    @Test
    fun `psychology agent blocks on overtrading`() {
        val agent = PsychologyAgent()
        val output = agent.analyze(context(tradesToday = 12))
        assertTrue(output.insights.any { it.type == "OVERTRADING" && it.tags.contains("BLOCK") })
    }

    @Test
    fun `psychology agent allows a disciplined state`() {
        val agent = PsychologyAgent()
        val output = agent.analyze(context(recentResults = listOf(true, false, true), tradesToday = 2))
        assertFalse(output.insights.any { it.tags.contains("BLOCK") })
    }
}
