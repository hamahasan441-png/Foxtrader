package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for LitAgent, NewsAgent, and StrategyAgent. */
class NewAgentsTest {

    private fun candle(i: Int) = Candle(
        timestamp = 1_700_000_000_000L + i * 60 * 60_000L,
        open = 100.0 + i * 0.1,
        high = 101.0 + i * 0.1,
        low = 99.0 + i * 0.1,
        close = 100.5 + i * 0.1,
        volume = 100.0,
    )

    private fun candles(n: Int) = (0 until n).map { candle(it) }

    private fun baseContext(n: Int = 80) = AgentContext(
        symbol = "EURUSD",
        timeframe = Timeframe.H1,
        candles = candles(n),
    )

    private fun output(
        agent: AgentName,
        bias: Bias,
        confidence: Double,
        insights: List<AgentInsight> = emptyList(),
    ) = AgentOutput(
        agentName = agent,
        status = AgentStatus.COMPLETE,
        bias = bias,
        confidence = confidence,
        insights = insights,
        narrative = "",
    )

    private fun insight(
        agent: AgentName,
        type: String,
        direction: Direction?,
        tags: List<String> = listOf(type),
    ) = AgentInsight(
        id = "$agent-$type",
        agentName = agent,
        type = type,
        direction = direction,
        confidence = 70.0,
        barIndex = 70,
        tags = tags,
    )

    // ---------------------------------------------------------------- LIT

    @Test
    fun `LIT does not promote generic sweep and BOS when canonical engine rejects`() {
        val ctx = baseContext().copy(
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to output(
                    AgentName.MARKET_STRUCTURE,
                    Bias.BULLISH,
                    80.0,
                    listOf(insight(AgentName.MARKET_STRUCTURE, "BOS", Direction.BULLISH)),
                ),
                AgentName.ICT to output(
                    AgentName.ICT,
                    Bias.BULLISH,
                    70.0,
                    listOf(
                        insight(
                            AgentName.ICT,
                            "LIQUIDITY_SWEEP",
                            Direction.BULLISH,
                            listOf("SWEEP", "LIQUIDITY_SWEEP"),
                        ),
                    ),
                ),
            ),
        )

        val result = LitAgent().analyze(ctx)

        assertEquals(Bias.NEUTRAL, result.bias)
        assertTrue(result.insights.isEmpty())
        assertFalse(result.insights.any { it.type == "INSTITUTIONAL_ENTRY_SIGNAL" })
        assertFalse(result.insights.any { it.type == "LIT_INDUCEMENT_REVERSAL" })
    }

    @Test
    fun `LIT neutral when canonical sequence is absent`() {
        val result = LitAgent().analyze(baseContext().copy(previousOutputs = emptyMap()))
        assertEquals(Bias.NEUTRAL, result.bias)
        assertTrue(result.insights.isEmpty())
    }

    // ---------------------------------------------------------------- NEWS

    @Test
    fun `News blocks during blackout`() {
        val result = NewsAgent().analyze(baseContext().copy(inNewsBlackout = true))
        assertTrue(result.insights.any { it.tags.contains("BLOCK") })
    }

    @Test
    fun `News blocks when news within 15 minutes`() {
        val result = NewsAgent().analyze(baseContext().copy(minutesToHighImpactNews = 10))
        assertTrue(result.insights.any { it.tags.contains("BLOCK") })
    }

    @Test
    fun `News caution when news within 60 minutes`() {
        val result = NewsAgent().analyze(baseContext().copy(minutesToHighImpactNews = 30))
        assertTrue(result.insights.any { it.type == "NEWS_CAUTION" })
        assertFalse(result.insights.any { it.tags.contains("BLOCK") })
    }

    @Test
    fun `News neutral when no imminent events`() {
        val result = NewsAgent().analyze(baseContext().copy(minutesToHighImpactNews = 120))
        assertTrue(result.insights.isEmpty())
        assertEquals(Bias.NEUTRAL, result.bias)
    }

    // ------------------------------------------------------------ STRATEGY

    @Test
    fun `Strategy synthesizes bullish when majority agree`() {
        val ctx = baseContext().copy(
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to output(AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0),
                AgentName.TREND to output(AgentName.TREND, Bias.BULLISH, 70.0),
                AgentName.VOLUME to output(AgentName.VOLUME, Bias.BULLISH, 60.0),
                AgentName.ICT to output(AgentName.ICT, Bias.NEUTRAL, 40.0),
            ),
        )
        val result = StrategyAgent().analyze(ctx)
        assertEquals(Bias.BULLISH, result.bias)
        assertTrue(result.confidence > 50.0)
    }

    @Test
    fun `Strategy blocked when Risk agent has BLOCK`() {
        val ctx = baseContext().copy(
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to output(AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0),
                AgentName.RISK to output(
                    AgentName.RISK,
                    Bias.NEUTRAL,
                    0.0,
                    listOf(insight(AgentName.RISK, "RISK_BLOCK", null, listOf("BLOCK"))),
                ),
            ),
        )
        val result = StrategyAgent().analyze(ctx)
        assertEquals(0.0, result.confidence, 0.001)
        assertTrue(result.insights.any { it.type == "STRATEGY_BLOCKED" })
    }

    @Test
    fun `Strategy neutral when no prior outputs`() {
        val result = StrategyAgent().analyze(baseContext().copy(previousOutputs = emptyMap()))
        assertEquals(Bias.NEUTRAL, result.bias)
    }
}
