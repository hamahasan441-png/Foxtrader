package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.tradepro.AbsorptionDetector
import com.foxtrader.app.domain.usecase.tradepro.CandleDerivedOrderFlowProvider
import com.foxtrader.app.domain.usecase.tradepro.ImbalanceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderFlowAgentTest {

    private val agent = OrderFlowAgent(
        orderFlowProvider = CandleDerivedOrderFlowProvider(),
        imbalanceDetector = ImbalanceDetector(),
        absorptionDetector = AbsorptionDetector(),
    )

    private fun context(candles: List<Candle>) =
        AgentContext(symbol = "MESUSD", timeframe = Timeframe.M15, candles = candles)

    /** Rising bars that close near their highs -> aggressive buying (bullish delta). */
    private fun buyPressure(n: Int): List<Candle> = (0 until n).map { i ->
        val base = 5000.0 + i
        Candle(timestamp = i * 60_000L, open = base, high = base + 1.0, low = base - 0.1, close = base + 0.95, volume = 100.0)
    }

    /** Falling bars that close near their lows -> aggressive selling (bearish delta). */
    private fun sellPressure(n: Int): List<Candle> = (0 until n).map { i ->
        val base = 5000.0 - i
        Candle(timestamp = i * 60_000L, open = base, high = base + 0.1, low = base - 1.0, close = base - 0.95, volume = 100.0)
    }

    @Test
    fun `insufficient data yields a neutral output`() {
        val out = agent.analyze(context(buyPressure(10)))
        assertEquals(Bias.NEUTRAL, out.bias)
        assertEquals(0.0, out.confidence, 1e-9)
        assertTrue(out.insights.isEmpty())
    }

    @Test
    fun `sustained buying is read as bullish with a delta insight`() {
        val out = agent.analyze(context(buyPressure(60)))
        assertEquals(AgentStatus.COMPLETE, out.status)
        assertEquals(Bias.BULLISH, out.bias)
        assertTrue(out.confidence > 0.0)
        val delta = out.insights.firstOrNull { it.type == "DELTA" }
        assertTrue("expected a DELTA insight", delta != null)
        assertEquals(Direction.BULLISH, delta!!.direction)
        assertTrue(delta.tags.contains("ORDER_FLOW"))
    }

    @Test
    fun `sustained selling is read as bearish`() {
        val out = agent.analyze(context(sellPressure(60)))
        assertEquals(Bias.BEARISH, out.bias)
        val delta = out.insights.firstOrNull { it.type == "DELTA" }
        assertEquals(Direction.BEARISH, delta?.direction)
    }

    @Test
    fun `agent never throws on ragged input`() {
        // Zero-range and zero-volume bars must not crash the analysis.
        val flat = (0 until 40).map { Candle(it * 60_000L, 5000.0, 5000.0, 5000.0, 5000.0, 0.0) }
        val out = agent.analyze(context(flat))
        assertEquals(AgentStatus.COMPLETE, out.status)
        assertEquals(Bias.NEUTRAL, out.bias) // no volume -> neutral
    }
}
