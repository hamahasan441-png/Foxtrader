package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.SimulatedTradeState
import com.foxtrader.app.domain.model.tradepro.SimulationSpeed
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.ViolationSeverity
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeProSimulationEngineTest {

    private val engine = TradeProSimulationEngine(
        signalEngine = TradeProSignalEngine(
            analyzeStructure = AnalyzeMarketStructureUseCase(),
            flipZoneEngine = FlipZoneEngine(),
            orderFlowProvider = CandleDerivedOrderFlowProvider(),
            imbalanceDetector = ImbalanceDetector(),
            absorptionDetector = AbsorptionDetector(),
            holdZoneEngine = HoldZoneEngine(),
            riskGuard = TradeProRiskGuard(),
            trendRegimeFilter = TrendRegimeFilter(),
            mtfEngine = MtfTradeProEngine(AnalyzeMarketStructureUseCase(), FlipZoneEngine()),
        ),
    )

    private fun uptrend(count: Int = 100): List<Candle> {
        val list = ArrayList<Candle>()
        var price = 100.0
        var t = 0L
        repeat(count) {
            val open = price
            val close = price + 0.5
            list += Candle(t, open, close + 0.2, open - 0.1, close, 100.0)
            price = close
            t += 60_000L
        }
        return list
    }

    private fun flat(count: Int = 60): List<Candle> =
        (0 until count).map { Candle(it * 60_000L, 100.0, 100.5, 99.5, 100.0, 100.0) }

    @Test
    fun `createSession starts at MIN_BARS and reveals no future bars`() {
        val candles = uptrend()
        val session = engine.createSession("MESUSD", candles)
        assertEquals("MESUSD", session.symbol)
        assertEquals(candles.size, session.totalBars)
        assertTrue(session.currentBarIndex < candles.size)
        assertEquals(session.currentBarIndex + 1, session.visibleCandles.size)
        assertFalse(session.isComplete)
        assertNull(session.openTrade)
    }

    @Test
    fun `stepForward advances by one bar and never reveals future data`() {
        val candles = uptrend()
        val session = engine.createSession("MESUSD", candles)
        val stepped = engine.stepForward(session, candles)
        assertEquals(session.currentBarIndex + 1, stepped.currentBarIndex)
        assertEquals(stepped.currentBarIndex + 1, stepped.visibleCandles.size)
    }

    @Test
    fun `stepping beyond last bar marks session complete`() {
        val candles = flat(35)
        var session = engine.createSession("MESUSD", candles)
        while (!session.isComplete) {
            session = engine.stepForward(session, candles)
        }
        assertTrue(session.isComplete)
    }

    @Test
    fun `placeTrade opens a position at current price`() {
        val candles = uptrend()
        val session = engine.createSession("MESUSD", candles)
        val config = TradeProConfig()
        val (updated, violation) = engine.placeTrade(session, Direction.BULLISH, config)
        assertNotNull(updated.openTrade)
        assertEquals(Direction.BULLISH, updated.openTrade!!.direction)
        assertEquals(session.currentPrice, updated.openTrade!!.entryPrice, 1e-9)
        assertTrue(updated.openTrade!!.isOpen)
    }

    @Test
    fun `cannot open a second trade while one is open`() {
        val candles = uptrend()
        val session = engine.createSession("MESUSD", candles)
        val config = TradeProConfig()
        val (withTrade, _) = engine.placeTrade(session, Direction.BULLISH, config)
        val (unchanged, violation) = engine.placeTrade(withTrade, Direction.BEARISH, config)
        assertNotNull("violation for second entry", violation)
        assertEquals(ViolationSeverity.CRITICAL, violation!!.severity)
        assertEquals("ONE_POSITION", violation.rule)
    }

    @Test
    fun `closeManually closes the open trade and records realized points`() {
        val candles = uptrend()
        var session = engine.createSession("MESUSD", candles)
        val config = TradeProConfig()
        val (withTrade, _) = engine.placeTrade(session, Direction.BULLISH, config)
        session = engine.stepForward(withTrade, candles)
        session = engine.stepForward(session, candles)
        val closed = engine.closeManually(session)
        assertNull(closed.openTrade)
        assertEquals(1, closed.closedTrades.size)
        assertEquals(SimulatedTradeState.CLOSED, closed.closedTrades[0].state)
    }

    @Test
    fun `moveStopToBreakeven only works after T1 hit`() {
        val candles = uptrend()
        val session = engine.createSession("MESUSD", candles)
        val config = TradeProConfig()
        val (withTrade, _) = engine.placeTrade(session, Direction.BULLISH, config)
        val moved = engine.moveStopToBreakeven(withTrade)
        // T1 not hit yet, so stop shouldn't change.
        assertEquals(withTrade.openTrade!!.stopLoss, moved.openTrade!!.stopLoss, 1e-9)
    }

    @Test
    fun `computePerformance returns EMPTY for no trades`() {
        val session = engine.createSession("MESUSD", uptrend())
        val perf = engine.computePerformance(session, emptyList())
        assertEquals(0, perf.totalTrades)
        assertEquals(SimulationPerformance.EMPTY, perf)
    }

    @Test
    fun `computePerformance calculates correct metrics after manual closes`() {
        val candles = uptrend(200)
        val config = TradeProConfig()
        var session = engine.createSession("MESUSD", candles, config)

        // Open and close two trades.
        val (s1, _) = engine.placeTrade(session, Direction.BULLISH, config)
        session = s1
        repeat(5) { session = engine.stepForward(session, candles) }
        session = engine.closeManually(session)

        val (s2, _) = engine.placeTrade(session, Direction.BULLISH, config)
        session = s2
        repeat(5) { session = engine.stepForward(session, candles) }
        session = engine.closeManually(session)

        val perf = engine.computePerformance(session, emptyList())
        assertEquals(2, perf.totalTrades)
        assertEquals(2, perf.equityCurve.size)
        assertTrue(perf.complianceScore in 0..100)
        assertTrue(perf.narrative.isNotBlank())
    }

    @Test
    fun `simulation is deterministic for identical inputs`() {
        val candles = uptrend()
        val config = TradeProConfig()
        val s1 = engine.createSession("MESUSD", candles, config, SimulationSpeed.FAST)
        val s2 = engine.createSession("MESUSD", candles, config, SimulationSpeed.FAST)
        assertEquals(s1.currentBarIndex, s2.currentBarIndex)
        assertEquals(s1.visibleCandles, s2.visibleCandles)
    }
}
