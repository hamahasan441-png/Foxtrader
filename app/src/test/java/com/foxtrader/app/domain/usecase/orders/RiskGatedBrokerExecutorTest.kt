package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.sdk.broker.BrokerAdapter
import com.foxtrader.app.domain.sdk.broker.OrderRequest
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
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
        riskEngine = RiskEngine(InstrumentTypeResolver()).apply { reset() }
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
    fun `phase4 risk multiplier can only reduce position size`() = runBlocking {
        val full = executor.placeMarketOrder(
            adapter = broker,
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            riskMultiplier = 1.0,
            executionAuthorized = true,
        )
        val fullVolume = full.sizing?.volume ?: error("missing full sizing")

        val reduced = executor.placeMarketOrder(
            adapter = broker,
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            riskMultiplier = 0.5,
            executionAuthorized = true,
        )
        val reducedVolume = reduced.sizing?.volume ?: error("missing reduced sizing")

        assertTrue(reduced.accepted)
        assertTrue(reducedVolume <= fullVolume)
        assertTrue(reduced.sizing?.riskAmount ?: Double.MAX_VALUE <= (full.sizing?.riskAmount ?: 0.0))
        assertTrue(reduced.sizing?.warnings?.any { it.contains("adaptive risk") } == true)
    }

    @Test
    fun `phase4 risk multiplier above one is rejected before broker call`() = runBlocking {
        val before = broker.placeOrderCalls
        val result = executor.placeMarketOrder(
            adapter = broker,
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            riskMultiplier = 1.25,
            executionAuthorized = true,
        )

        assertFalse(result.accepted)
        assertTrue(result.rejectionReasons.any { it.contains("multiplier") })
        assertEquals(before, broker.placeOrderCalls)
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

    @Test
    fun `minimum lot that exceeds the per-trade cap never reaches the broker`() = runBlocking {
        // Regression: the gate checked the $10 budget but would have submitted
        // 0.01 lots of gold with a $20 stop, i.e. $20 at risk against a $10 cap.
        riskEngine.updateConfig(RiskConfig(accountBalance = 1_000.0, riskPercentPerTrade = 1.0))
        riskEngine.updateBalance(1_000.0)
        val anyAsset = FakeBrokerAdapter(supportedAssets = emptyList())

        val result = executor.placeMarketOrder(
            adapter = anyAsset,
            symbol = "XAUUSD",
            direction = Direction.BULLISH,
            entryPrice = 2_000.0,
            stopLoss = 1_980.0,
            executionAuthorized = true,
        )

        assertFalse(result.accepted)
        assertEquals(0, anyAsset.placeOrderCalls)
        assertTrue(result.rejectionReasons.any { it.contains("per-trade limit") })
    }

    @Test
    fun `adaptive risk multiplier snaps the volume down to the lot step`() = runBlocking {
        // 0.2 lots x 0.7 = 0.14 exactly, but 0.23 x 0.7 = 0.161 is not a
        // volume a broker accepts; it must become 0.16, never 0.17.
        riskEngine.updateConfig(RiskConfig(accountBalance = 11_500.0, riskPercentPerTrade = 1.0))
        riskEngine.updateBalance(11_500.0)

        val result = executor.placeMarketOrder(
            adapter = broker,
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            riskMultiplier = 0.7,
            executionAuthorized = true,
        )

        assertTrue(result.rejectionReasons.toString(), result.accepted)
        assertEquals(0.16, broker.lastRequest!!.volume, 1e-12)
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
