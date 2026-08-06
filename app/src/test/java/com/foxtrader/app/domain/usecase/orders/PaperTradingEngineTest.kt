package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PaperTradingEngine] — pure account math (fills, marking,
 * realized/unrealized P&L, slippage, commission, SL/TP auto-close, and
 * asset-class-correct contract sizing). No Android, no mocking.
 */
class PaperTradingEngineTest {

    private lateinit var engine: PaperTradingEngine

    @Before
    fun setup() {
        engine = PaperTradingEngine(InstrumentTypeResolver())
    }

    private fun account() = PaperAccount.initial(10_000.0)

    private fun candle(high: Double, low: Double, close: Double) =
        Candle(timestamp = 1_700_000_000_000L, open = close, high = high, low = low, close = close, volume = 1000.0)

    @Test
    fun `open adds a position with contract size resolved for the instrument`() {
        val acc = engine.open(account(), "o1", "EURUSD", Direction.BULLISH, volume = 0.1, requestedPrice = 1.1000)
        assertEquals(1, acc.positions.size)
        val p = acc.positions[0]
        assertEquals(100_000.0, p.contractSize, 0.0)
        assertEquals(1.1000, p.entryPrice, 1e-9)
        assertEquals(0.0, p.unrealizedPnl, 1e-9) // marked at entry
    }

    @Test
    fun `long unrealized pnl uses contract size`() {
        var acc = engine.open(account(), "o1", "EURUSD", Direction.BULLISH, 0.1, 1.1000)
        acc = engine.mark(acc, mapOf("EURUSD" to 1.1050))
        // 0.0050 * 0.1 * 100000 = 50
        assertEquals(50.0, acc.positions[0].unrealizedPnl, 1e-6)
        assertEquals(10_050.0, acc.equity, 1e-6)
        assertEquals(10_000.0, acc.balance, 1e-6) // balance only moves on close
    }

    @Test
    fun `short profits when price falls`() {
        var acc = engine.open(account(), "o1", "EURUSD", Direction.BEARISH, 0.1, 1.1000)
        acc = engine.mark(acc, mapOf("EURUSD" to 1.0950))
        assertEquals(50.0, acc.positions[0].unrealizedPnl, 1e-6)
    }

    @Test
    fun `close realizes net pnl into balance and records the trade`() {
        var acc = engine.open(account(), "o1", "EURUSD", Direction.BULLISH, 0.1, 1.1000)
        acc = engine.close(acc, "o1", 1.1050)
        assertTrue(acc.positions.isEmpty())
        assertEquals(1, acc.closedTrades.size)
        assertEquals(50.0, acc.closedTrades[0].netPnl, 1e-6)
        assertEquals(10_050.0, acc.balance, 1e-6)
        assertEquals(50.0, acc.realizedPnl, 1e-6)
    }

    @Test
    fun `slippage costs the trader on both fills`() {
        val config = PaperFillConfig(slippage = 0.0002)
        var acc = engine.open(account(), "o1", "EURUSD", Direction.BULLISH, 0.1, 1.1000, config)
        acc = engine.close(acc, "o1", 1.1000, config) // round trip at the same market price
        // Round-trip slippage cost = 2 * 0.0002 * 0.1 * 100000 = 4.0
        assertEquals(-4.0, acc.closedTrades[0].netPnl, 1e-6)
    }

    @Test
    fun `commission is charged round trip`() {
        val config = PaperFillConfig(commissionPerLot = 5.0)
        var acc = engine.open(account(), "o1", "EURUSD", Direction.BULLISH, 2.0, 1.1000, config)
        acc = engine.close(acc, "o1", 1.1000, config)
        // commission = 5 * 2 * 2 = 20; gross = 0 → net = -20
        assertEquals(20.0, acc.closedTrades[0].commission, 1e-6)
        assertEquals(-20.0, acc.closedTrades[0].netPnl, 1e-6)
        assertEquals(9_980.0, acc.balance, 1e-6)
    }

    @Test
    fun `onCandle auto-closes a long at its stop loss`() {
        var acc = engine.open(
            account(), "o1", "EURUSD", Direction.BULLISH, 0.1, 1.1000,
            stopLoss = 1.0950, takeProfit = 1.1100,
        )
        acc = engine.onCandle(acc, "EURUSD", candle(high = 1.1000, low = 1.0940, close = 1.0960))
        assertTrue(acc.positions.isEmpty())
        assertEquals(1.0950, acc.closedTrades[0].exitPrice, 1e-9)
        assertEquals(-50.0, acc.closedTrades[0].netPnl, 1e-6)
    }

    @Test
    fun `onCandle auto-closes a long at its take profit`() {
        var acc = engine.open(
            account(), "o1", "EURUSD", Direction.BULLISH, 0.1, 1.1000,
            stopLoss = 1.0950, takeProfit = 1.1100,
        )
        acc = engine.onCandle(acc, "EURUSD", candle(high = 1.1120, low = 1.1050, close = 1.1080))
        assertTrue(acc.positions.isEmpty())
        assertEquals(1.1100, acc.closedTrades[0].exitPrice, 1e-9)
        assertEquals(100.0, acc.closedTrades[0].netPnl, 1e-6)
    }

    @Test
    fun `crypto pnl is not scaled by a forex lot`() {
        var acc = engine.open(account(), "o1", "BTCUSD", Direction.BULLISH, 0.1, 50_000.0)
        acc = engine.mark(acc, mapOf("BTCUSD" to 51_000.0))
        // contract size 1: 1000 * 0.1 * 1 = 100
        assertEquals(1.0, acc.positions[0].contractSize, 0.0)
        assertEquals(100.0, acc.positions[0].unrealizedPnl, 1e-6)
    }

    @Test
    fun `closing an unknown position id is a no-op`() {
        val acc = engine.open(account(), "o1", "EURUSD", Direction.BULLISH, 0.1, 1.1000)
        val after = engine.close(acc, "does-not-exist", 1.2000)
        assertEquals(acc, after)
    }

    @Test
    fun `mark leaves positions on other symbols untouched`() {
        var acc = engine.open(account(), "o1", "EURUSD", Direction.BULLISH, 0.1, 1.1000)
        acc = engine.open(acc, "o2", "GBPUSD", Direction.BULLISH, 0.1, 1.3000)
        acc = engine.mark(acc, mapOf("EURUSD" to 1.1050))
        assertEquals(1.1050, acc.positions.first { it.id == "o1" }.currentPrice, 1e-9)
        assertEquals(1.3000, acc.positions.first { it.id == "o2" }.currentPrice, 1e-9)
    }
}
