package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.TradeManagementAction
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProManagementPlan
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import com.foxtrader.app.domain.model.tradepro.SetupStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeManagementEngineTest {

    private val engine = TradeManagementEngine()
    private val config = TradeProConfig()

    private fun bullishSetup(
        entry: Double = 100.0,
        stop: Double = 97.0,
        t1: Double = 104.0,
        t2: Double = 108.0,
        runner: Double = 116.0,
    ): TradeProSetup = TradeProSetup(
        symbol = "ES",
        direction = Direction.BULLISH,
        stage = SetupStage.EXECUTE,
        entry = entry,
        stopLoss = stop,
        target1 = t1,
        target2 = t2,
        runnerTarget = runner,
        riskPoints = 3.0,
        riskReward = 4.0,
        confidence = 80,
        flipZone = null,
        holdZone = null,
        managementPlan = TradeProManagementPlan(
            contracts = 3,
            stopPoints = 3.0,
            t1Points = 4.0,
            t2Points = 8.0,
            t1Contracts = 1,
            t2Contracts = 1,
            runnerContracts = 1,
            totalRiskPoints = 9.0,
            breakevenWinRate = 0.4286,
        ),
        confluences = listOf("Flip zone", "Imbalance"),
        note = "Test setup",
    )

    private fun candle(
        open: Double = 100.0,
        high: Double = 101.0,
        low: Double = 99.0,
        close: Double = 100.5,
        timestamp: Long = 1_000_000L,
    ): Candle = Candle(
        timestamp = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1000.0,
    )

    @Test
    fun `openTrade produces correct initial state`() {
        val trade = engine.openTrade(bullishSetup(), config)

        assertEquals("ES", trade.symbol)
        assertEquals(Direction.BULLISH, trade.direction)
        assertEquals(100.0, trade.entryPrice, 1e-9)
        assertEquals(97.0, trade.stopPrice, 1e-9)
        assertEquals(104.0, trade.t1Price, 1e-9)
        assertEquals(108.0, trade.t2Price, 1e-9)
        assertEquals(116.0, trade.runnerTarget, 1e-9)
        assertEquals(3, trade.contracts)
        assertEquals(ManagedTradeState.ACTIVE, trade.state)
        assertEquals(100.0, trade.currentPrice, 1e-9)
        assertNull(trade.t1FilledAt)
        assertNull(trade.t2FilledAt)
        assertNull(trade.closedAt)
        assertEquals(0.0, trade.realizedPoints, 1e-9)
        assertNull(trade.exitReason)
    }

    @Test
    fun `tick advancing through T1 then T2 then runner lifecycle`() {
        var trade = engine.openTrade(bullishSetup(), config)

        // Bar that hits T1 (high >= 104)
        val t1Candle = candle(open = 103.0, high = 105.0, low = 102.5, close = 104.5, timestamp = 2_000_000L)
        val (afterT1, actionT1) = engine.tick(trade, t1Candle)
        assertEquals(ManagedTradeState.T1_HIT, afterT1.state)
        assertEquals(TradeManagementAction.HitT1, actionT1)
        assertNotNull(afterT1.t1FilledAt)
        // Realized: 1 contract * (104 - 100) = 4.0 pts
        assertEquals(4.0, afterT1.realizedPoints, 1e-9)
        // Stop should NOT have moved (rule: stop untouched until T1)
        assertEquals(97.0, afterT1.stopPrice, 1e-9)
        trade = afterT1

        // Bar that hits T2 (high >= 108)
        val t2Candle = candle(open = 107.0, high = 109.0, low = 106.5, close = 108.5, timestamp = 3_000_000L)
        val (afterT2, actionT2) = engine.tick(trade, t2Candle)
        assertEquals(ManagedTradeState.T2_HIT, afterT2.state)
        assertEquals(TradeManagementAction.HitT2, actionT2)
        assertNotNull(afterT2.t2FilledAt)
        // Realized: 4.0 (T1) + 1 contract * (108 - 100) = 4.0 + 8.0 = 12.0
        assertEquals(12.0, afterT2.realizedPoints, 1e-9)
        // Stop should be moved to break-even (entry price) after T2
        assertEquals(100.0, afterT2.stopPrice, 1e-9)
        trade = afterT2

        // Bar that hits runner target (high >= 116)
        val runnerCandle = candle(open = 114.0, high = 117.0, low = 113.0, close = 116.5, timestamp = 4_000_000L)
        val (afterRunner, actionRunner) = engine.tick(trade, runnerCandle)
        assertEquals(ManagedTradeState.CLOSED, afterRunner.state)
        assertEquals(TradeManagementAction.HitRunner, actionRunner)
        // Realized: 12.0 + 1 contract * (116 - 100) = 12.0 + 16.0 = 28.0
        assertEquals(28.0, afterRunner.realizedPoints, 1e-9)
        assertEquals(0.0, afterRunner.unrealizedPoints, 1e-9)
        assertEquals("Runner target hit", afterRunner.exitReason)
    }

    @Test
    fun `stop hit closes the trade`() {
        val trade = engine.openTrade(bullishSetup(), config)

        // Bar that breaches stop (low <= 97)
        val stopCandle = candle(open = 98.0, high = 99.0, low = 96.5, close = 97.0, timestamp = 2_000_000L)
        val (afterStop, action) = engine.tick(trade, stopCandle)

        assertEquals(ManagedTradeState.CLOSED, afterStop.state)
        assertEquals(TradeManagementAction.Stopped, action)
        assertEquals("Stop hit", afterStop.exitReason)
        assertNotNull(afterStop.closedAt)
    }

    @Test
    fun `break-even move only happens after T2`() {
        var trade = engine.openTrade(bullishSetup(), config)

        // Hit T1 only
        val t1Candle = candle(open = 103.0, high = 105.0, low = 102.5, close = 104.5, timestamp = 2_000_000L)
        val (afterT1, _) = engine.tick(trade, t1Candle)
        // Stop should still be at original after T1 only
        assertEquals(97.0, afterT1.stopPrice, 1e-9)
        trade = afterT1

        // Another bar that does not hit T2
        val middleCandle = candle(open = 105.0, high = 106.0, low = 104.0, close = 105.5, timestamp = 2_500_000L)
        val (afterMiddle, _) = engine.tick(trade, middleCandle)
        assertEquals(97.0, afterMiddle.stopPrice, 1e-9)
        trade = afterMiddle

        // Hit T2
        val t2Candle = candle(open = 107.0, high = 109.0, low = 106.5, close = 108.5, timestamp = 3_000_000L)
        val (afterT2, _) = engine.tick(trade, t2Candle)
        // Now stop should be at break-even (entry)
        assertEquals(100.0, afterT2.stopPrice, 1e-9)
    }

    @Test
    fun `closeManually closes an active trade`() {
        val trade = engine.openTrade(bullishSetup(), config)
        val closed = engine.closeManually(trade, 101.0)

        assertEquals(ManagedTradeState.CLOSED, closed.state)
        assertEquals(101.0, closed.currentPrice, 1e-9)
        assertEquals("Manual close", closed.exitReason)
        assertNotNull(closed.closedAt)
        // Realized: 3 contracts * (101 - 100) = 3.0 pts
        assertEquals(3.0, closed.realizedPoints, 1e-9)
        assertEquals(0.0, closed.unrealizedPoints, 1e-9)
    }

    @Test
    fun `closeManually on already closed trade is a no-op`() {
        val trade = engine.openTrade(bullishSetup(), config)
        val closed = engine.closeManually(trade, 101.0)
        val closedAgain = engine.closeManually(closed, 105.0)

        assertEquals(closed.realizedPoints, closedAgain.realizedPoints, 1e-9)
        assertEquals(closed.closedAt, closedAgain.closedAt)
    }

    @Test
    fun `tick on closed trade returns unchanged`() {
        val trade = engine.openTrade(bullishSetup(), config)
        val closed = engine.closeManually(trade, 101.0)

        val c = candle(open = 110.0, high = 120.0, low = 90.0, close = 115.0, timestamp = 5_000_000L)
        val (result, action) = engine.tick(closed, c)

        assertEquals(closed, result)
        assertNull(action)
    }

    @Test
    fun `bearish trade stop hit when high breaches stop`() {
        val setup = bullishSetup().copy(
            direction = Direction.BEARISH,
            entry = 100.0,
            stopLoss = 103.0,
            target1 = 96.0,
            target2 = 92.0,
            runnerTarget = 84.0,
        )
        val trade = engine.openTrade(setup, config)

        // High breaches stop at 103
        val stopCandle = candle(open = 101.0, high = 104.0, low = 100.5, close = 103.5, timestamp = 2_000_000L)
        val (afterStop, action) = engine.tick(trade, stopCandle)

        assertEquals(ManagedTradeState.CLOSED, afterStop.state)
        assertEquals(TradeManagementAction.Stopped, action)
    }

    @Test
    fun `trailing stop only tightens for bullish runner`() {
        var trade = engine.openTrade(bullishSetup(), config)

        // Advance through T1
        val t1Candle = candle(open = 103.0, high = 105.0, low = 102.5, close = 104.5, timestamp = 2_000_000L)
        trade = engine.tick(trade, t1Candle).first

        // Advance through T2 (stop moves to break-even = 100)
        val t2Candle = candle(open = 107.0, high = 109.0, low = 106.5, close = 108.5, timestamp = 3_000_000L)
        trade = engine.tick(trade, t2Candle).first
        assertEquals(100.0, trade.stopPrice, 1e-9)

        // Next bar moves price higher, triggering trail stop
        // close=112 => trail = 112 - 4 = 108, which is above current stop (100)
        val advanceCandle = candle(open = 110.0, high = 113.0, low = 109.0, close = 112.0, timestamp = 4_000_000L)
        val (afterTrail, trailAction) = engine.tick(trade, advanceCandle)

        assertTrue(afterTrail.stopPrice > 100.0)
        assertEquals(108.0, afterTrail.stopPrice, 1e-9)
        assertTrue(trailAction is TradeManagementAction.TrailStop)
        assertEquals(108.0, (trailAction as TradeManagementAction.TrailStop).newStop, 1e-9)

        // Next bar drops but trail should NOT widen
        // close=109 => trail = 109 - 4 = 105, which is below current stop (108) => no change
        // low=108.5 stays above stop (108) so no stop-hit either
        val dropCandle = candle(open = 110.0, high = 111.0, low = 108.5, close = 109.0, timestamp = 5_000_000L)
        val (afterDrop, dropAction) = engine.tick(afterTrail, dropCandle)

        assertEquals(108.0, afterDrop.stopPrice, 1e-9)
        assertNull(dropAction)
    }
}
