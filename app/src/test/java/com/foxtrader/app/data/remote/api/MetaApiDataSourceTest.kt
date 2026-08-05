package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaApiDataSourceTest {

    @Test
    fun `deployAccount sends correct request and returns account ID`() = runBlocking {
        val api = FakeMetaApiService(
            deployResponse = MetaApiDeployResponse(
                id = "acc-12345",
                state = "DEPLOYED",
                name = "FoxTrader-123456",
                server = "ICMarkets-Demo",
                platform = "mt4",
            )
        )
        val dataSource = MetaApiDataSource(api)

        val credentials = Mt4Credentials(
            login = 123456,
            password = "pass123",
            server = "ICMarkets-Demo",
        )
        val accountId = dataSource.deployAccount("test-token", credentials)

        assertEquals("acc-12345", accountId)
        assertEquals("test-token", api.lastAuthToken)
        assertEquals(123456, api.lastDeployRequest?.login)
        assertEquals("pass123", api.lastDeployRequest?.password)
        assertEquals("ICMarkets-Demo", api.lastDeployRequest?.server)
        assertEquals("mt4", api.lastDeployRequest?.platform)
    }

    @Test
    fun `getAccountInfo maps response DTO to domain model correctly`() = runBlocking {
        val api = FakeMetaApiService(
            accountInfoResponse = MetaApiAccountInfoResponse(
                login = 789012,
                balance = 10000.50,
                equity = 10250.75,
                margin = 500.0,
                freeMargin = 9750.75,
                leverage = 100,
                currency = "USD",
                name = "John Doe",
                server = "FXCM-Demo",
            )
        )
        val dataSource = MetaApiDataSource(api)

        val accountInfo = dataSource.getAccountInfo("my-token", "acc-999")

        assertEquals(789012, accountInfo.login)
        assertEquals(10000.50, accountInfo.balance, 0.001)
        assertEquals(10250.75, accountInfo.equity, 0.001)
        assertEquals(500.0, accountInfo.margin, 0.001)
        assertEquals(9750.75, accountInfo.freeMargin, 0.001)
        assertEquals(100, accountInfo.leverage)
        assertEquals("USD", accountInfo.currency)
        assertEquals("John Doe", accountInfo.name)
        assertEquals("FXCM-Demo", accountInfo.server)
        assertEquals("my-token", api.lastAuthToken)
        assertEquals("acc-999", api.lastAccountId)
    }

    @Test
    fun `getPositions maps response DTOs to domain models with correct order types`() = runBlocking {
        val api = FakeMetaApiService(
            positionsResponse = listOf(
                MetaApiPositionResponse(
                    id = 100001,
                    symbol = "EURUSD",
                    type = "POSITION_TYPE_BUY",
                    volume = 0.1,
                    openPrice = 1.08500,
                    time = 1700000000000L,
                    stopLoss = 1.08000,
                    takeProfit = 1.09000,
                    profit = 25.50,
                    swap = -1.20,
                    commission = -3.00,
                ),
                MetaApiPositionResponse(
                    id = 100002,
                    symbol = "GBPUSD",
                    type = "POSITION_TYPE_SELL",
                    volume = 0.5,
                    openPrice = 1.26000,
                    time = 1700001000000L,
                    stopLoss = 1.26500,
                    takeProfit = 1.25000,
                    profit = -10.00,
                    swap = 0.50,
                    commission = -7.50,
                ),
            )
        )
        val dataSource = MetaApiDataSource(api)

        val positions = dataSource.getPositions("tok", "acc-1")

        assertEquals(2, positions.size)

        val first = positions[0]
        assertEquals(100001L, first.ticket)
        assertEquals("EURUSD", first.symbol)
        assertEquals(Mt4OrderType.BUY, first.type)
        assertEquals(0.1, first.lots, 0.001)
        assertEquals(1.08500, first.openPrice, 0.00001)
        assertEquals(1700000000000L, first.openTime)
        assertEquals(1.08000, first.sl, 0.00001)
        assertEquals(1.09000, first.tp, 0.00001)
        assertEquals(25.50, first.profit, 0.001)
        assertEquals(-1.20, first.swap, 0.001)
        assertEquals(-3.00, first.commission, 0.001)

        val second = positions[1]
        assertEquals(100002L, second.ticket)
        assertEquals("GBPUSD", second.symbol)
        assertEquals(Mt4OrderType.SELL, second.type)
        assertEquals(0.5, second.lots, 0.001)
        assertEquals(1.26000, second.openPrice, 0.00001)
        assertEquals(-10.00, second.profit, 0.001)
    }

    @Test
    fun `executeTrade sends correct action type and returns order ID`() = runBlocking {
        val api = FakeMetaApiService(
            tradeResponse = MetaApiTradeResponse(
                numericCode = 0,
                stringCode = "TRADE_RETCODE_DONE",
                orderId = 200001,
                message = "Request completed",
            )
        )
        val dataSource = MetaApiDataSource(api)

        val orderId = dataSource.executeTrade(
            token = "trade-token",
            accountId = "acc-trade",
            symbol = "USDJPY",
            type = Mt4OrderType.BUY_LIMIT,
            lots = 0.25,
            sl = 148.500,
            tp = 150.000,
        )

        assertEquals(200001L, orderId)
        assertEquals("trade-token", api.lastAuthToken)
        assertEquals("acc-trade", api.lastAccountId)
        assertEquals("ORDER_TYPE_BUY_LIMIT", api.lastTradeRequest?.actionType)
        assertEquals("USDJPY", api.lastTradeRequest?.symbol)
        assertEquals(0.25, api.lastTradeRequest?.volume ?: 0.0, 0.001)
        assertEquals(148.500, api.lastTradeRequest?.stopLoss ?: 0.0, 0.001)
        assertEquals(150.000, api.lastTradeRequest?.takeProfit ?: 0.0, 0.001)
    }

    @Test
    fun `deployAccount throws when response ID is blank`() = runBlocking {
        val api = FakeMetaApiService(
            deployResponse = MetaApiDeployResponse(id = "", state = "FAILED")
        )
        val dataSource = MetaApiDataSource(api)

        val error = try {
            dataSource.deployAccount(
                "token",
                Mt4Credentials(login = 1, password = "p", server = "s"),
            )
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(error != null)
        assertTrue(error?.message?.contains("empty ID") == true)
    }

    // ========================================================================
    // FAKE IMPLEMENTATION
    // ========================================================================

    private class FakeMetaApiService(
        private val deployResponse: MetaApiDeployResponse = MetaApiDeployResponse(id = "default-id"),
        private val accountInfoResponse: MetaApiAccountInfoResponse = MetaApiAccountInfoResponse(),
        private val positionsResponse: List<MetaApiPositionResponse> = emptyList(),
        private val tradeResponse: MetaApiTradeResponse = MetaApiTradeResponse(orderId = 1),
    ) : MetaApiService {

        var lastAuthToken: String? = null
        var lastAccountId: String? = null
        var lastDeployRequest: MetaApiDeployRequest? = null
        var lastTradeRequest: MetaApiTradeRequest? = null

        override suspend fun deployAccount(
            authToken: String,
            request: MetaApiDeployRequest,
        ): MetaApiDeployResponse {
            lastAuthToken = authToken
            lastDeployRequest = request
            return deployResponse
        }

        override suspend fun getAccountInformation(
            authToken: String,
            accountId: String,
        ): MetaApiAccountInfoResponse {
            lastAuthToken = authToken
            lastAccountId = accountId
            return accountInfoResponse
        }

        override suspend fun getPositions(
            authToken: String,
            accountId: String,
        ): List<MetaApiPositionResponse> {
            lastAuthToken = authToken
            lastAccountId = accountId
            return positionsResponse
        }

        override suspend fun executeTrade(
            authToken: String,
            accountId: String,
            request: MetaApiTradeRequest,
        ): MetaApiTradeResponse {
            lastAuthToken = authToken
            lastAccountId = accountId
            lastTradeRequest = request
            return tradeResponse
        }
    }
}
