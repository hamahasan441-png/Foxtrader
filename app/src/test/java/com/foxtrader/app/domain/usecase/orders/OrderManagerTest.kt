package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OrderStatus
import com.foxtrader.app.domain.model.OrderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OrderManager.
 * Includes regression coverage for the symbol-matching bug where
 * processTick compared order.symbol to candle.toString() (orders never filled).
 */
class OrderManagerTest {

    private lateinit var manager: OrderManager

    @Before
    fun setup() {
        manager = OrderManager()
    }

    private fun candle(open: Double, high: Double, low: Double, close: Double) =
        Candle(System.currentTimeMillis(), open, high, low, close, 100.0)

    @Test
    fun `market order placed as pending`() {
        val order = manager.placeMarketOrder("EURUSD", Direction.BULLISH, 0.1)
        assertEquals(OrderType.MARKET, order.type)
        assertEquals(1, manager.getPendingOrders().size)
    }

    @Test
    fun `limit buy order fills when price drops to level`() {
        // Regression: orders never filled due to candle.toString() symbol check
        manager.placeLimitOrder("EURUSD", Direction.BULLISH, 0.1, price = 1.1000)
        // Candle whose low touches the limit price
        val filled = manager.processTick("EURUSD", candle(1.1050, 1.1060, 1.0990, 1.1010))
        assertEquals(1, filled.size)
        assertEquals(OrderStatus.FILLED, filled[0].status)
    }

    @Test
    fun `limit order does not fill for different symbol`() {
        manager.placeLimitOrder("EURUSD", Direction.BULLISH, 0.1, price = 1.1000)
        val filled = manager.processTick("GBPUSD", candle(1.1050, 1.1060, 1.0990, 1.1010))
        assertTrue(filled.isEmpty())
        assertEquals(1, manager.getPendingOrders().size)
    }

    @Test
    fun `market order fills on any tick`() {
        manager.placeMarketOrder("BTCUSDT", Direction.BULLISH, 1.0)
        val filled = manager.processTick("BTCUSDT", candle(50000.0, 50100.0, 49900.0, 50050.0))
        assertEquals(1, filled.size)
    }

    @Test
    fun `stop sell order fills when price drops below stop`() {
        manager.placeStopOrder("EURUSD", Direction.BEARISH, 0.1, stopPrice = 1.0950)
        val filled = manager.processTick("EURUSD", candle(1.1000, 1.1010, 1.0940, 1.0945))
        assertEquals(1, filled.size)
    }

    @Test
    fun `OCO fills one and cancels the other`() {
        manager.placeOcoOrder("EURUSD", Direction.BULLISH, 0.1,
            takeProfitPrice = 1.1100, stopLossPrice = 1.0900)
        // Price hits take-profit
        manager.processTick("EURUSD", candle(1.1050, 1.1110, 1.1040, 1.1105))
        // One filled, the linked SL should be cancelled
        val cancelled = manager.getAllOrders().count { it.status == OrderStatus.CANCELLED }
        val filledCount = manager.getFilledOrders().size
        assertEquals(1, filledCount)
        assertEquals(1, cancelled)
    }

    @Test
    fun `cancel order marks it cancelled`() {
        val order = manager.placeLimitOrder("EURUSD", Direction.BULLISH, 0.1, price = 1.0)
        assertTrue(manager.cancelOrder(order.id))
        assertTrue(manager.getPendingOrders().isEmpty())
    }
}
