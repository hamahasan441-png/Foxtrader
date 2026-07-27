package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.sdk.broker.BrokerAdapter
import com.foxtrader.app.domain.sdk.broker.OrderRequest
import com.foxtrader.app.domain.sdk.broker.OrderResult
import com.foxtrader.app.domain.sdk.broker.Position
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RiskGatedBrokerExecutorTest {

    private lateinit var riskEngine: RiskEngine
    private lateinit var executor: RiskGatedBrokerExecutor
    private lateinit var broker: FakeBrokerAdapter

    @Before
    fun setup() {
        riskEngine = RiskEngine().apply { reset() }
        executor = RiskGatedBrokerExecutor(riskEngine)
        broker = FakeBrokerAdapter(supportedAssets = listOf("EURUSD"))
    }

    @Test
    fun `authorized risk-approved market order reaches broker`() = runBlocking {
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
        assertNotNull(result.orderResult)
        assertEquals(1, broker.placeOrderCalls)
        assertEquals("EURUSD", broker.lastRequest?.symbol)
        assertTrue(result.riskCheck?.allowed == true)
    }

    @Test
    fun `unauthorized execution is rejected before broker call`() = runBlocking {
        val result = executor.placeMarketOrder(
            adapter = broker,
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            executionAuthorized = false,
        )

        assertFalse(result.accepted)
        assertTrue(result.rejectionReasons.any { it.contains("not authorized") })
        assertEquals(0, broker.placeOrderCalls)
    }

    @Test
    fun `unsupported symbol is rejected before broker call`() = runBlocking {
        val result = executor.placeMarketOrder(
            adapter = broker,
            symbol = "BTCUSDT",
            direction = Direction.BULLISH,
            entryPrice = 50_000.0,
            stopLoss = 49_000.0,
            executionAuthorized = true,
        )

        assertFalse(result.accepted)
        assertTrue(result.rejectionReasons.any { it.contains("does not support") })
        assertEquals(0, broker.placeOrderCalls)
    }

    @Test
    fun `halted risk engine blocks broker call`() = runBlocking {
        riskEngine.haltTrading("manual halt")

        val result = executor.placeMarketOrder(
            adapter = broker,
            symbol = "EURUSD",
            direction = Direction.BEARISH,
            entryPrice = 1.1000,
            stopLoss = 1.1050,
            executionAuthorized = true,
        )

        assertFalse(result.accepted)
        assertTrue(result.rejectionReasons.any { it.contains("Trading halted") })
        assertEquals(0, broker.placeOrderCalls)
    }

    private class FakeBrokerAdapter(
        override val supportedAssets: List<String>,
    ) : BrokerAdapter {
        override val id: String = "fake"
        override val displayName: String = "Fake Broker"
        var placeOrderCalls: Int = 0
        var lastRequest: OrderRequest? = null

        override suspend fun connect(): Boolean = true

        override suspend fun placeOrder(order: OrderRequest): OrderResult {
            placeOrderCalls++
            lastRequest = order
            return OrderResult(
                orderId = "order-$placeOrderCalls",
                symbol = order.symbol,
                filledPrice = 1.1000,
                volume = order.volume,
                timestamp = 1_000L,
            )
        }

        override suspend fun cancelOrder(orderId: String): Boolean = true

        override suspend fun getPositions(): List<Position> = emptyList()
    }
}
