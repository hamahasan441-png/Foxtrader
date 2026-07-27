package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OrderType
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RiskGatedOrderServiceTest {

    private lateinit var riskEngine: RiskEngine
    private lateinit var orderManager: OrderManager
    private lateinit var service: RiskGatedOrderService

    @Before
    fun setup() {
        riskEngine = RiskEngine()
        riskEngine.reset()
        orderManager = OrderManager()
        service = RiskGatedOrderService(riskEngine, orderManager)
    }

    @Test
    fun `market order is accepted only after risk check passes`() {
        val result = service.placeMarketOrder(
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
        )

        assertTrue(result.accepted)
        assertNotNull(result.order)
        assertEquals(OrderType.MARKET, result.order?.type)
        assertEquals(1, orderManager.getPendingOrders().size)
        assertTrue(result.riskCheck?.allowed == true)
    }

    @Test
    fun `invalid bullish stop is rejected before an order is created`() {
        val result = service.placeMarketOrder(
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.1050,
        )

        assertFalse(result.accepted)
        assertTrue(result.rejectionReasons.any { it.contains("below entry") })
        assertTrue(orderManager.getAllOrders().isEmpty())
    }

    @Test
    fun `halted risk engine rejects order and does not touch order manager`() {
        riskEngine.haltTrading("manual halt")

        val result = service.placeMarketOrder(
            symbol = "EURUSD",
            direction = Direction.BEARISH,
            entryPrice = 1.1000,
            stopLoss = 1.1050,
        )

        assertFalse(result.accepted)
        assertTrue(result.rejectionReasons.any { it.contains("Trading halted") })
        assertTrue(orderManager.getAllOrders().isEmpty())
    }

    @Test
    fun `limit bracket creates entry take profit and stop loss after risk approval`() {
        val result = service.placeLimitBracketOrder(
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            takeProfit = 1.1150,
            volumeOverride = 0.1,
        )

        assertTrue(result.accepted)
        assertNotNull(result.bracketOrder)
        assertEquals(3, orderManager.getPendingOrders().size)
        assertTrue(result.sizing?.warnings?.contains("Manual volume override applied") == true)
    }
}
