package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.sdk.broker.OrderRequest
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PaperBroker] — the paper [BrokerAdapter]. Verifies order →
 * tracked position, fills at the fed price, cancel/close, SL/TP auto-close, and
 * that it activates the previously-dead [RiskGatedBrokerExecutor] path end to
 * end (risk-sized order actually opens a paper position). Uses `runBlocking`
 * and real collaborators — no mocking of concrete classes.
 */
class PaperBrokerTest {

    private lateinit var broker: PaperBroker

    @Before
    fun setup() {
        broker = PaperBroker(PaperTradingEngine(InstrumentTypeResolver()))
    }

    @Test
    fun `placeOrder opens a tracked position filled at the fed price`() = runBlocking {
        broker.onPrice("EURUSD", 1.1000)
        val result = broker.placeOrder(
            OrderRequest("EURUSD", Direction.BULLISH, volume = 0.1, stopLoss = 1.0950, takeProfit = 1.1100),
        )
        assertEquals("paper-EURUSD-1", result.orderId)
        assertEquals(1.1000, result.filledPrice, 1e-9)

        val positions = broker.getPositions()
        assertEquals(1, positions.size)
        assertEquals("EURUSD", positions[0].symbol)
        assertEquals(0.1, positions[0].volume, 1e-9)
    }

    @Test(expected = IllegalStateException::class)
    fun `placeOrder without a known price fails fast`() = runBlocking {
        broker.placeOrder(OrderRequest("EURUSD", Direction.BULLISH, volume = 0.1))
        Unit
    }

    @Test
    fun `cancelOrder closes the position, unknown id returns false`() = runBlocking {
        broker.onPrice("EURUSD", 1.1000)
        broker.placeOrder(OrderRequest("EURUSD", Direction.BULLISH, volume = 0.1))
        assertTrue(broker.cancelOrder("paper-EURUSD-1"))
        assertTrue(broker.getPositions().isEmpty())
        assertFalse(broker.cancelOrder("nope"))
    }

    @Test
    fun `onCandle auto-closes a position when its stop is hit`() = runBlocking {
        broker.onPrice("EURUSD", 1.1000)
        broker.placeOrder(
            OrderRequest("EURUSD", Direction.BULLISH, volume = 0.1, stopLoss = 1.0950, takeProfit = 1.1100),
        )
        broker.onCandle(
            "EURUSD",
            Candle(timestamp = 1_700_000_000_000L, open = 1.1000, high = 1.1000, low = 1.0940, close = 1.0960, volume = 1000.0),
        )
        assertTrue(broker.getPositions().isEmpty())
        assertEquals(1, broker.snapshot().closedTrades.size)
    }

    @Test
    fun `risk-gated executor routes a sized order into the paper broker`() = runBlocking {
        val riskEngine = RiskEngine(InstrumentTypeResolver()).apply { reset() }
        val executor = RiskGatedBrokerExecutor(riskEngine)
        broker.onPrice("EURUSD", 1.1000)

        val result = executor.placeMarketOrder(
            adapter = broker,
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            takeProfit = 1.1150,
            executionAuthorized = true,
        )

        assertTrue(result.accepted)
        val positions = broker.getPositions()
        assertEquals(1, positions.size)
        // The broker opened exactly the volume the risk engine sized.
        assertEquals(result.sizing!!.volume, positions[0].volume, 1e-9)
    }
}
