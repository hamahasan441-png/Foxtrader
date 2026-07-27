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
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LitAgent, NewsAgent, and StrategyAgent.
 */
class NewAgentsTest {

    private fun candle(i: Int) = Candle(
        timestamp = i * 60_000L,
        open = 100.0 + i * 0.1,
        high = 101.0 + i * 0.1,
        low = 99.0 + i * 0.1,
        close = 100.5 + i * 0.1,
        volume = 100.0,
    )

    private fun candles(n: Int) = (0 until n).map { candle(it) }

    private fun baseContext(n: Int = 60) = AgentContext(
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

    private fun litAgent() = LitAgent(SmtDivergenceDetector())

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
        tags = tags,
    )

    // ---------------------------------------------------------------- LIT

    @Test
    fun `LIT emits institutional entry when sweep + break align`() {
        val agent = litAgent()
        val ctx = baseContext().copy(
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to output(
                    AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0,
                    listOf(insight(AgentName.MARKET_STRUCTURE, "BOS", Direction.BULLISH)),
                ),
                AgentName.ICT to output(
                    AgentName.ICT, Bias.BULLISH, 70.0,
                    listOf(insight(AgentName.ICT, "LIQUIDITY_SWEEP", Direction.BULLISH, listOf("SWEEP", "LIQUIDITY_SWEEP"))),
                ),
            ),
        )
        val result = agent.analyze(ctx)
        assertTrue(result.insights.any { it.type == "INSTITUTIONAL_ENTRY_SIGNAL" })
        assertEquals(Bias.BULLISH, result.bias)
    }

    @Test
    fun `LIT emits bullish inducement reversal from direct candle sequence`() {
        val agent = litAgent()
        val result = agent.analyze(baseContextWithCandles(bullishLitCandles()))

        val lit = result.insights.firstOrNull { it.type == "LIT_INDUCEMENT_REVERSAL" }
        assertTrue("Expected direct LIT reversal insight", lit != null)
        assertEquals(Direction.BULLISH, lit?.direction)
        assertTrue(lit?.tags?.contains("SWEEP") == true)
        assertTrue(lit?.tags?.contains("MSS") == true)
        assertEquals(Bias.BULLISH, result.bias)
    }

    @Test
    fun `LIT emits bearish inducement reversal from direct candle sequence`() {
        val agent = litAgent()
        val result = agent.analyze(baseContextWithCandles(bearishLitCandles()))

        val lit = result.insights.firstOrNull { it.type == "LIT_INDUCEMENT_REVERSAL" }
        assertTrue("Expected direct LIT reversal insight", lit != null)
        assertEquals(Direction.BEARISH, lit?.direction)
        assertEquals(Bias.BEARISH, result.bias)
    }

    @Test
    fun `LIT emits SMT when correlated pair fails to confirm buy-side sweep`() {
        val agent = litAgent()
        val primary = smtPrimaryCandles()
        val peer = smtPeerCandles()
        val result = agent.analyze(
            baseContextWithCandles(primary).copy(
                symbol = "EURUSD",
                correlatedCandles = mapOf("GBPUSD" to peer),
            )
        )

        val smt = result.insights.firstOrNull { it.type == "SMT" }
        assertTrue("Expected SMT insight", smt != null)
        assertEquals(Direction.BEARISH, smt?.direction)
        assertTrue(smt?.tags?.contains("SMT") == true)
    }

    @Test
    fun `LIT neutral when no sweep or break`() {
        val agent = litAgent()
        val ctx = baseContext().copy(previousOutputs = emptyMap())
        val result = agent.analyze(ctx)
        assertEquals(Bias.NEUTRAL, result.bias)
        assertTrue(result.insights.isEmpty())
    }

    private fun baseContextWithCandles(candles: List<Candle>) = AgentContext(
        symbol = "EURUSD",
        timeframe = Timeframe.H1,
        candles = candles,
    )

    private fun bullishLitCandles(): List<Candle> {
        val candles = mutableListOf<Candle>()
        repeat(28) { i ->
            val open = 100.20 + (i % 4) * 0.04
            val close = open + if (i % 2 == 0) 0.08 else -0.04
            candles += Candle(
                timestamp = i * 60_000L,
                open = open,
                high = 100.80 + (i % 3) * 0.03,
                low = 99.70 + (i % 2) * 0.03,
                close = close,
                volume = 100.0,
            )
        }
        candles += Candle(28 * 60_000L, open = 99.55, high = 100.35, low = 98.95, close = 100.18, volume = 180.0)
        candles += Candle(29 * 60_000L, open = 100.20, high = 101.35, low = 100.05, close = 101.10, volume = 220.0)
        repeat(10) { k ->
            val i = 30 + k
            candles += Candle(
                timestamp = i * 60_000L,
                open = 101.10 + k * 0.08,
                high = 101.50 + k * 0.08,
                low = 100.90 + k * 0.08,
                close = 101.30 + k * 0.08,
                volume = 140.0,
            )
        }
        return candles
    }

    private fun bearishLitCandles(): List<Candle> {
        val candles = mutableListOf<Candle>()
        repeat(28) { i ->
            val open = 100.20 - (i % 4) * 0.04
            val close = open + if (i % 2 == 0) 0.04 else -0.08
            candles += Candle(
                timestamp = i * 60_000L,
                open = open,
                high = 100.80 - (i % 2) * 0.03,
                low = 99.60 - (i % 3) * 0.03,
                close = close,
                volume = 100.0,
            )
        }
        candles += Candle(28 * 60_000L, open = 100.95, high = 101.45, low = 99.95, close = 100.42, volume = 180.0)
        candles += Candle(29 * 60_000L, open = 100.40, high = 100.55, low = 99.10, close = 99.30, volume = 220.0)
        repeat(10) { k ->
            val i = 30 + k
            candles += Candle(
                timestamp = i * 60_000L,
                open = 99.30 - k * 0.08,
                high = 99.50 - k * 0.08,
                low = 98.90 - k * 0.08,
                close = 99.10 - k * 0.08,
                volume = 140.0,
            )
        }
        return candles
    }

    private fun smtPrimaryCandles(): List<Candle> = smtBaseCandles { index, baseHigh ->
        when (index) {
            30 -> 110.0
            60 -> 111.0
            else -> baseHigh
        }
    }

    private fun smtPeerCandles(): List<Candle> = smtBaseCandles { index, baseHigh ->
        when (index) {
            30 -> 110.0
            60 -> 109.5
            else -> baseHigh
        }
    }

    private fun smtBaseCandles(highOverride: (Int, Double) -> Double): List<Candle> =
        (0 until 80).map { i ->
            val close = 100.0 + i * 0.08 + if (i % 2 == 0) 0.02 else -0.01
            val baseHigh = close + 0.25
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = highOverride(i, baseHigh),
                low = close - 0.25,
                close = close,
                volume = 100.0,
            )
        }

    // ---------------------------------------------------------------- NEWS

    @Test
    fun `News blocks during blackout`() {
        val agent = NewsAgent()
        val ctx = baseContext().copy(inNewsBlackout = true)
        val result = agent.analyze(ctx)
        assertTrue(result.insights.any { it.tags.contains("BLOCK") })
    }

    @Test
    fun `News blocks when news within 15 minutes`() {
        val agent = NewsAgent()
        val ctx = baseContext().copy(minutesToHighImpactNews = 10)
        val result = agent.analyze(ctx)
        assertTrue(result.insights.any { it.tags.contains("BLOCK") })
    }

    @Test
    fun `News caution when news within 60 minutes`() {
        val agent = NewsAgent()
        val ctx = baseContext().copy(minutesToHighImpactNews = 30)
        val result = agent.analyze(ctx)
        assertTrue(result.insights.any { it.type == "NEWS_CAUTION" })
        assertFalse(result.insights.any { it.tags.contains("BLOCK") })
    }

    @Test
    fun `News neutral when no imminent events`() {
        val agent = NewsAgent()
        val ctx = baseContext().copy(minutesToHighImpactNews = 120)
        val result = agent.analyze(ctx)
        assertTrue(result.insights.isEmpty())
        assertEquals(Bias.NEUTRAL, result.bias)
    }

    // ------------------------------------------------------------ STRATEGY

    @Test
    fun `Strategy synthesizes bullish when majority agree`() {
        val agent = StrategyAgent()
        val ctx = baseContext().copy(
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to output(AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0),
                AgentName.TREND to output(AgentName.TREND, Bias.BULLISH, 70.0),
                AgentName.VOLUME to output(AgentName.VOLUME, Bias.BULLISH, 60.0),
                AgentName.ICT to output(AgentName.ICT, Bias.NEUTRAL, 40.0),
            ),
        )
        val result = agent.analyze(ctx)
        assertEquals(Bias.BULLISH, result.bias)
        assertTrue(result.confidence > 50.0)
    }

    @Test
    fun `Strategy blocked when Risk agent has BLOCK`() {
        val agent = StrategyAgent()
        val ctx = baseContext().copy(
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to output(AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0),
                AgentName.RISK to output(
                    AgentName.RISK, Bias.NEUTRAL, 0.0,
                    listOf(insight(AgentName.RISK, "RISK_BLOCK", null, listOf("BLOCK"))),
                ),
            ),
        )
        val result = agent.analyze(ctx)
        assertEquals(0.0, result.confidence, 0.001)
        assertTrue(result.insights.any { it.type == "STRATEGY_BLOCKED" })
    }

    @Test
    fun `Strategy neutral when no prior outputs`() {
        val agent = StrategyAgent()
        val ctx = baseContext().copy(previousOutputs = emptyMap())
        val result = agent.analyze(ctx)
        assertEquals(Bias.NEUTRAL, result.bias)
    }
}
