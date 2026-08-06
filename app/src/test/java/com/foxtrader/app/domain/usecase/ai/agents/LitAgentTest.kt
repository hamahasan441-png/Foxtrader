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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies the data-driven institutional-entry confidence blend in [LitAgent].
 *
 * The agent no longer emits a flat 80 for a sweep + structure-break confluence;
 * confidence is now `0.6*stronger + 0.4*weaker` of the two contributing
 * insights, clamped to [60, 92]. Uses the real [SmtDivergenceDetector] (a
 * no-dependency concrete class), so no mocking is required.
 */
class LitAgentTest {

    private val agent = LitAgent(SmtDivergenceDetector())

    /** 40 near-flat candles: enough bars, but no organic sweep/inducement noise. */
    private val flatCandles: List<Candle> = (0 until 40).map { i ->
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = 1.1000,
            high = 1.1002,
            low = 1.0998,
            close = 1.1000,
            volume = 1000.0,
        )
    }

    private fun structureOutput(breakDirection: Direction, breakConfidence: Double): AgentOutput =
        AgentOutput(
            agentName = AgentName.MARKET_STRUCTURE,
            status = AgentStatus.COMPLETE,
            bias = Bias.NEUTRAL,
            confidence = breakConfidence,
            insights = listOf(
                AgentInsight(
                    id = "ms-break",
                    agentName = AgentName.MARKET_STRUCTURE,
                    type = "BOS",
                    direction = breakDirection,
                    confidence = breakConfidence,
                    barIndex = 38,
                ),
            ),
            narrative = "structure",
        )

    private fun ictSweepOutput(sweepDirection: Direction, sweepConfidence: Double): AgentOutput =
        AgentOutput(
            agentName = AgentName.ICT,
            status = AgentStatus.COMPLETE,
            bias = Bias.NEUTRAL,
            confidence = sweepConfidence,
            insights = listOf(
                AgentInsight(
                    id = "ict-sweep",
                    agentName = AgentName.ICT,
                    type = "LIQUIDITY_SWEEP",
                    direction = sweepDirection,
                    confidence = sweepConfidence,
                    barIndex = 37,
                    tags = listOf("SWEEP"),
                ),
            ),
            narrative = "sweep",
        )

    private fun analyzeWith(
        sweepDirection: Direction,
        sweepConfidence: Double,
        breakDirection: Direction,
        breakConfidence: Double,
    ): AgentOutput {
        val context = AgentContext(
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            candles = flatCandles,
            previousOutputs = mapOf(
                AgentName.MARKET_STRUCTURE to structureOutput(breakDirection, breakConfidence),
                AgentName.ICT to ictSweepOutput(sweepDirection, sweepConfidence),
            ),
        )
        return agent.analyze(context)
    }

    @Test
    fun `institutional entry confidence blends sweep and break strength`() {
        val output = analyzeWith(
            sweepDirection = Direction.BULLISH, sweepConfidence = 90.0,
            breakDirection = Direction.BULLISH, breakConfidence = 70.0,
        )
        val entry = output.insights.firstOrNull { it.type == "INSTITUTIONAL_ENTRY_SIGNAL" }
        assertNotNull("a sweep + aligned break must produce an institutional entry", entry)
        // 0.6*90 + 0.4*70 = 82.0
        assertEquals(82.0, entry!!.confidence, 1e-6)
        assertEquals(Direction.BULLISH, entry.direction)
    }

    @Test
    fun `institutional entry confidence respects the minimum floor`() {
        val output = analyzeWith(
            sweepDirection = Direction.BEARISH, sweepConfidence = 55.0,
            breakDirection = Direction.BEARISH, breakConfidence = 50.0,
        )
        val entry = output.insights.first { it.type == "INSTITUTIONAL_ENTRY_SIGNAL" }
        // 0.6*55 + 0.4*50 = 53.0 → clamped up to the 60.0 floor.
        assertEquals(60.0, entry.confidence, 1e-6)
    }

    @Test
    fun `institutional entry confidence respects the maximum ceiling`() {
        val output = analyzeWith(
            sweepDirection = Direction.BULLISH, sweepConfidence = 99.0,
            breakDirection = Direction.BULLISH, breakConfidence = 98.0,
        )
        val entry = output.insights.first { it.type == "INSTITUTIONAL_ENTRY_SIGNAL" }
        // 0.6*99 + 0.4*98 = 98.6 → clamped down to the 92.0 ceiling.
        assertEquals(92.0, entry.confidence, 1e-6)
    }

    @Test
    fun `no institutional entry when sweep and break directions disagree`() {
        val output = analyzeWith(
            sweepDirection = Direction.BULLISH, sweepConfidence = 90.0,
            breakDirection = Direction.BEARISH, breakConfidence = 80.0,
        )
        val entry = output.insights.firstOrNull { it.type == "INSTITUTIONAL_ENTRY_SIGNAL" }
        assertNull("opposing sweep and break must not confluence into an entry", entry)
    }
}
