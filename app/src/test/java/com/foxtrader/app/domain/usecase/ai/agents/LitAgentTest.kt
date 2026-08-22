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

class LitAgentTest {

    private val agent = LitAgent()

    private val flatCandles: List<Candle> = (0 until 80).map { i ->
        Candle(
            timestamp = 1_700_000_000_000L + i * 15 * 60_000L,
            open = 1.1000,
            high = 1.1002,
            low = 1.0998,
            close = 1.1000,
            volume = 1000.0,
        )
    }

    private fun output(agentName: AgentName, insights: List<AgentInsight>): AgentOutput = AgentOutput(
        agentName = agentName,
        status = AgentStatus.COMPLETE,
        bias = Bias.BULLISH,
        confidence = 90.0,
        insights = insights,
        narrative = "upstream",
    )

    private fun insight(
        agentName: AgentName,
        type: String,
        direction: Direction = Direction.BULLISH,
        tags: List<String> = listOf(type),
    ) = AgentInsight(
        id = "$agentName-$type",
        agentName = agentName,
        type = type,
        direction = direction,
        confidence = 95.0,
        barIndex = 70,
        tags = tags,
    )

    @Test
    fun `generic sweep and BOS cannot bypass canonical LiT rejection`() {
        val context = AgentContext(
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            candles = flatCandles,
            previousOutputs = mapOf(
                AgentName.ICT to output(
                    AgentName.ICT,
                    listOf(insight(AgentName.ICT, "LIQUIDITY_SWEEP", tags = listOf("SWEEP"))),
                ),
                AgentName.MARKET_STRUCTURE to output(
                    AgentName.MARKET_STRUCTURE,
                    listOf(insight(AgentName.MARKET_STRUCTURE, "BOS")),
                ),
            ),
        )

        val result = agent.analyze(context)

        assertEquals(Bias.NEUTRAL, result.bias)
        assertTrue(result.insights.isEmpty())
        assertFalse(result.insights.any { it.type == "INSTITUTIONAL_ENTRY_SIGNAL" })
        assertFalse(result.insights.any { it.type == "LIT_INDUCEMENT_REVERSAL" })
    }

    @Test
    fun `upstream IDM alone is not promoted to LiT evidence`() {
        val context = AgentContext(
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            candles = flatCandles,
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to output(
                    AgentName.MARKET_STRUCTURE,
                    listOf(insight(AgentName.MARKET_STRUCTURE, "IDM")),
                ),
            ),
        )

        val result = agent.analyze(context)

        assertEquals(Bias.NEUTRAL, result.bias)
        assertTrue(result.insights.isEmpty())
    }
}
