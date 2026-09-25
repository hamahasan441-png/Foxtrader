package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paper protection must fill where a real account would: a bar that opens
 * through a stop fills at the open, and a live price crossing a stop closes the
 * position without waiting for a candle that the live feed never delivers.
 */
class PaperTradingGapAndLivePriceTest {

    private val engine = PaperTradingEngine(InstrumentTypeResolver())

    private fun longAt100() = engine.open(
        account = PaperAccount.initial(10_000.0),
        id = "p1",
        symbol = "XAUUSD",
        direction = Direction.BULLISH,
        volume = 0.1,
        requestedPrice = 100.0,
        stopLoss = 99.0,
        takeProfit = 105.0,
    )

    private fun shortAt100() = engine.open(
        account = PaperAccount.initial(10_000.0),
        id = "s1",
        symbol = "XAUUSD",
        direction = Direction.BEARISH,
        volume = 0.1,
        requestedPrice = 100.0,
        stopLoss = 101.0,
        takeProfit = 95.0,
    )

    @Test
    fun `a long stop gapped through fills at the open, not the stop`() {
        val acc = engine.onCandle(longAt100(), "XAUUSD", Candle(1L, 97.0, 98.0, 96.0, 97.5, 0.0))
        assertTrue(acc.positions.isEmpty())
        assertEquals(97.0, acc.closedTrades.single().exitPrice, 1e-9)
    }

    @Test
    fun `a short stop gapped through fills at the open`() {
        val acc = engine.onCandle(shortAt100(), "XAUUSD", Candle(1L, 103.0, 104.0, 102.5, 103.5, 0.0))
        assertEquals(103.0, acc.closedTrades.single().exitPrice, 1e-9)
    }

    @Test
    fun `a target the open already cleared is taken before the stop`() {
        // Opens above the target; a later dip to the stop cannot have come first.
        val acc = engine.onCandle(longAt100(), "XAUUSD", Candle(1L, 106.0, 107.0, 98.0, 99.0, 0.0))
        assertEquals(105.0, acc.closedTrades.single().exitPrice, 1e-9)
    }

    @Test
    fun `an ungapped bar keeps the conservative stop-first ordering`() {
        val acc = engine.onCandle(longAt100(), "XAUUSD", Candle(1L, 100.0, 106.0, 98.5, 104.0, 0.0))
        assertEquals(99.0, acc.closedTrades.single().exitPrice, 1e-9)
    }

    @Test
    fun `a live price through the stop closes the position at that price`() {
        val acc = engine.onPrice(longAt100(), "XAUUSD", 98.5)
        assertTrue(acc.positions.isEmpty())
        assertEquals(98.5, acc.closedTrades.single().exitPrice, 1e-9)
    }

    @Test
    fun `a live price through the target closes at the target`() {
        val acc = engine.onPrice(shortAt100(), "XAUUSD", 94.0)
        assertEquals(95.0, acc.closedTrades.single().exitPrice, 1e-9)
    }

    @Test
    fun `a live price between the levels only marks`() {
        val acc = engine.onPrice(longAt100(), "XAUUSD", 99.5)
        assertEquals(1, acc.positions.size)
        assertEquals(99.5, acc.positions.single().currentPrice, 1e-9)
        assertTrue(acc.closedTrades.isEmpty())
    }

    @Test
    fun `other symbols are untouched by a live price`() {
        val acc = engine.onPrice(longAt100(), "EURUSD", 1.0)
        assertEquals(1, acc.positions.size)
    }

    @Test
    fun `the paper broker enforces stops on the live feed`() = runBlocking {
        val broker = PaperBroker(engine)
        broker.onPrice("XAUUSD", 100.0)
        broker.placeOrder(
            com.foxtrader.app.domain.sdk.broker.OrderRequest(
                "XAUUSD", Direction.BULLISH, volume = 0.1, stopLoss = 99.0, takeProfit = 105.0,
            ),
        )
        broker.onPrice("XAUUSD", 98.0)

        assertTrue(broker.getPositions().isEmpty())
        assertEquals(1, broker.snapshot().closedTrades.size)
    }
}
