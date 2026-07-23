package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OrchestratorResult
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MasterDecisionEngine — the ultimate trade gatekeeper.
 * Validates confluence gating, confidence gating, risk/psychology veto, and grading.
 */
class MasterDecisionEngineTest {

    private lateinit var engine: MasterDecisionEngine

    @Before
    fun setup() {
        engine = MasterDecisionEngine()
    }

    // ------------------------------------------------------------------ helpers

    private fun insight(
        agent: AgentName,
        type: String,
        direction: Direction?,
        confidence: Double = 70.0,
        tags: List<String> = listOf(type),
    ) = AgentInsight(
        id = "$agent-$type",
        agentName = agent,
        type = type,
        direction = direction,
        confidence = confidence,
        tags = tags,
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

    private fun result(
        outputs: Map<AgentName, AgentOutput>,
        direction: Direction?,
        confidence: Double,
    ) = OrchestratorResult(
        timestamp = 0L,
        symbol = "EURUSD",
        timeframe = Timeframe.H1,
        agentOutputs = outputs,
        aggregateBias = when (direction) {
            Direction.BULLISH -> Bias.BULLISH
            Direction.BEARISH -> Bias.BEARISH
            null -> Bias.NEUTRAL
        },
        aggregateConfidence = confidence,
        alignedInsightCount = 0,
        agentConsensus = 6,
        totalProcessingMs = 0L,
        signalApproved = direction != null,
        signalDirection = direction,
    )

    /** Eight bullish confluences (all but SMT). */
    private fun eightConfluenceBullish(): Map<AgentName, AgentOutput> = mapOf(
        AgentName.MARKET_STRUCTURE to output(
            AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0,
            listOf(insight(AgentName.MARKET_STRUCTURE, "BOS", Direction.BULLISH)),
        ),
        AgentName.TREND to output(
            AgentName.TREND, Bias.BULLISH, 75.0,
            listOf(insight(AgentName.TREND, "TREND", Direction.BULLISH)),
        ),
        AgentName.VOLUME to output(
            AgentName.VOLUME, Bias.BULLISH, 70.0,
            listOf(insight(AgentName.VOLUME, "DELTA", Direction.BULLISH, tags = listOf("DELTA", "VOLUME"))),
        ),
        AgentName.SMART_MONEY to output(
            AgentName.SMART_MONEY, Bias.BULLISH, 80.0,
            listOf(insight(AgentName.SMART_MONEY, "BULLISH_OB", Direction.BULLISH, tags = listOf("ORDER_BLOCK"))),
        ),
        AgentName.ICT to output(
            AgentName.ICT, Bias.BULLISH, 75.0,
            listOf(
                insight(AgentName.ICT, "FVG", Direction.BULLISH),
                insight(AgentName.ICT, "LIQUIDITY_SWEEP", Direction.BULLISH, tags = listOf("SWEEP", "LIQUIDITY_SWEEP")),
                insight(AgentName.ICT, "KILL_ZONE", null, tags = listOf("SESSION", "KILL_ZONE")),
            ),
        ),
    )

    // ------------------------------------------------------------------- tests

    @Test
    fun `approves strong multi-confluence signal`() {
        val decision = engine.evaluate(result(eightConfluenceBullish(), Direction.BULLISH, 80.0))

        assertTrue("Signal should be approved", decision.approved)
        assertEquals(Direction.BULLISH, decision.direction)
        assertTrue("8 confluences expected", decision.confluencePresent.size >= 8)
        assertTrue("SMT should be missing", decision.confluenceMissing.contains(RequiredConfluence.SMT))
        assertEquals(SignalGrade.VERY_STRONG, decision.grade) // 8 confluences, 80% -> VERY_STRONG
        assertNull(decision.vetoedBy)
    }

    @Test
    fun `risk block vetoes an otherwise valid signal`() {
        val outputs = eightConfluenceBullish().toMutableMap()
        outputs[AgentName.RISK] = output(
            AgentName.RISK, Bias.NEUTRAL, 0.0,
            listOf(insight(AgentName.RISK, "RISK_BLOCK", null, 100.0, tags = listOf("BLOCK"))),
        )

        val decision = engine.evaluate(result(outputs, Direction.BULLISH, 80.0))

        assertFalse(decision.approved)
        assertNull(decision.direction)
        assertEquals(AgentName.RISK, decision.vetoedBy)
        assertEquals(SignalGrade.NO_SIGNAL, decision.grade)
    }

    @Test
    fun `psychology block vetoes an otherwise valid signal`() {
        val outputs = eightConfluenceBullish().toMutableMap()
        outputs[AgentName.PSYCHOLOGY] = output(
            AgentName.PSYCHOLOGY, Bias.NEUTRAL, 0.0,
            listOf(insight(AgentName.PSYCHOLOGY, "TILT", null, 100.0, tags = listOf("BLOCK"))),
        )

        val decision = engine.evaluate(result(outputs, Direction.BULLISH, 80.0))

        assertFalse(decision.approved)
        assertEquals(AgentName.PSYCHOLOGY, decision.vetoedBy)
    }

    @Test
    fun `rejects when no directional consensus`() {
        val decision = engine.evaluate(result(eightConfluenceBullish(), null, 70.0))

        assertFalse(decision.approved)
        assertNull(decision.direction)
        assertEquals(SignalGrade.NO_SIGNAL, decision.grade)
        assertTrue(decision.explanation.contains("stand aside", ignoreCase = true))
    }

    @Test
    fun `rejects when too few confluences present`() {
        // Only structure (BOS + HTF bias) and trend -> 3 confluences, below the minimum of 5.
        val outputs = mapOf(
            AgentName.MARKET_STRUCTURE to output(
                AgentName.MARKET_STRUCTURE, Bias.BULLISH, 80.0,
                listOf(insight(AgentName.MARKET_STRUCTURE, "BOS", Direction.BULLISH)),
            ),
            AgentName.TREND to output(
                AgentName.TREND, Bias.BULLISH, 70.0,
                listOf(insight(AgentName.TREND, "TREND", Direction.BULLISH)),
            ),
        )
        val decision = engine.evaluate(result(outputs, Direction.BULLISH, 80.0))

        assertFalse(decision.approved)
        assertTrue(decision.confluencePresent.size < 5)
        assertTrue(decision.blockReasons.any { it.contains("confluences") })
    }

    @Test
    fun `rejects when confidence below minimum`() {
        val decision = engine.evaluate(result(eightConfluenceBullish(), Direction.BULLISH, 40.0))

        assertFalse(decision.approved)
        assertTrue(decision.blockReasons.any { it.contains("Confidence") })
    }

    @Test
    fun `institutional grade requires 8 confluences and high confidence`() {
        // Add SMT to reach 9 confluences, confidence 90 -> INSTITUTIONAL.
        val outputs = eightConfluenceBullish().toMutableMap()
        outputs[AgentName.LIT] = output(
            AgentName.LIT, Bias.BULLISH, 80.0,
            listOf(insight(AgentName.LIT, "SMT", Direction.BULLISH)),
        )
        val decision = engine.evaluate(result(outputs, Direction.BULLISH, 90.0))

        assertTrue(decision.approved)
        assertEquals(9, decision.confluencePresent.size)
        assertEquals(SignalGrade.INSTITUTIONAL, decision.grade)
    }
}
