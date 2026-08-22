package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaApiDataSourceTest {

    @Test
    fun `deployAccount sends current provisioning shape transaction id and resolves region`() = runBlocking {
        val api = FakeMetaApiService(
            deployResponse = MetaApiDeployResponse(id = "acc-12345", state = "DEPLOYED"),
            provisioned = MetaApiProvisionedAccountResponse(id = "acc-12345", region = "new-york"),
        )
        val dataSource = MetaApiDataSource(api)
        val accountId = dataSource.deployAccount(
            "test-token",
            Mt4Credentials(login = 123456, password = "pass123", server = "ICMarkets-Demo"),
        )

        assertEquals("acc-12345", accountId)
        assertEquals("test-token", api.lastAuthToken)
        assertEquals("123456", api.lastDeployRequest?.login)
        assertEquals("pass123", api.lastDeployRequest?.password)
        assertEquals("cloud-g2", api.lastDeployRequest?.type)
        assertTrue(api.lastTransactionId?.matches(Regex("[0-9a-f]{32}")) == true)
    }

    @Test
    fun `getAccountInfo routes to regional client host and maps response`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc-999", region = "london"),
            accountInfoResponse = MetaApiAccountInfoResponse(
                login = 789012, balance = 10000.50, equity = 10250.75,
                margin = 500.0, freeMargin = 9750.75, leverage = 100,
                currency = "USD", name = "John Doe", server = "FXCM-Demo",
            ),
        )
        val accountInfo = MetaApiDataSource(api).getAccountInfo("my-token", "acc-999")

        assertEquals(789012L, accountInfo.login)
        assertEquals(9750.75, accountInfo.freeMargin, 0.001)
        assertTrue(api.lastUrl?.startsWith("https://mt-client-api-v1.london.agiliumtrade.ai/") == true)
        assertTrue(api.lastUrl?.endsWith("/acc-999/account-information") == true)
    }

    @Test
    fun `legacy vint-hill region maps to new-york public endpoint`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "vint-hill"),
            accountInfoResponse = MetaApiAccountInfoResponse(login = 1),
        )
        MetaApiDataSource(api).getAccountInfo("t", "acc")
        assertTrue(api.lastUrl?.contains("mt-client-api-v1.new-york.agiliumtrade.ai") == true)
    }

    @Test
    fun `safe custom MetaApi region is routed under fixed vendor domain`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "custom-region-2"),
            accountInfoResponse = MetaApiAccountInfoResponse(login = 1),
        )
        MetaApiDataSource(api).getAccountInfo("t", "acc")
        assertTrue(api.lastUrl?.startsWith("https://mt-client-api-v1.custom-region-2.agiliumtrade.ai/") == true)
    }

    @Test
    fun `hostile MetaApi region fails closed`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "evil.example.com"),
        )
        val error = runCatching { MetaApiDataSource(api).getAccountInfo("t", "acc") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("Invalid MetaApi deployment region") == true)
    }

    @Test
    fun `positions map string ids ISO time and filter malformed rows`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc-1", region = "new-york"),
            positionsResponse = listOf(
                MetaApiPositionResponse(
                    id = "100001", symbol = "EURUSD", type = "POSITION_TYPE_BUY",
                    volume = 0.1, openPrice = 1.085, time = "2023-11-14T22:13:20Z",
                    stopLoss = 1.08, takeProfit = 1.09, profit = 25.5, swap = -1.2, commission = -3.0,
                ),
                MetaApiPositionResponse(id = "not-numeric", symbol = "GBPUSD", type = "POSITION_TYPE_SELL", volume = 1.0, openPrice = 1.2),
            ),
        )
        val positions = MetaApiDataSource(api).getPositions("tok", "acc-1")
        assertEquals(1, positions.size)
        assertEquals(100001L, positions.single().ticket)
        assertEquals(Mt4OrderType.BUY, positions.single().type)
        assertTrue(positions.single().openTime > 0L)
    }

    @Test
    fun `historical candles use market-data host correct timeframe and drop invalid rows`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            candlesResponse = listOf(
                MetaApiCandleResponse(time = "2026-08-21T10:00:00Z", open = 1.0, high = 1.1, low = 0.9, close = 1.05, tickVolume = 5.0),
                MetaApiCandleResponse(time = "2026-08-21T11:00:00Z", open = Double.NaN, high = 1.1, low = 0.9, close = 1.05),
                MetaApiCandleResponse(time = "2026-08-21T12:00:00Z", open = 1.0, high = 1.01, low = 0.9, close = 1.05), // high < close
            ),
        )
        val candles = MetaApiDataSource(api).getHistoricalCandles("token", "acc", "EURUSD", Timeframe.H1, 10)
        assertEquals(1, candles.size)
        assertTrue(api.lastUrl?.contains("mt-market-data-client-api-v1.new-york.agiliumtrade.ai") == true)
        assertTrue(api.lastUrl?.contains("/timeframes/1h/candles") == true)
    }

    @Test
    fun `current price maps ISO time and validates spread`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            currentPriceResponse = MetaApiCurrentPriceResponse(
                symbol = "EURUSD", bid = 1.1, ask = 1.1002, time = "2026-08-21T10:00:00Z"
            ),
        )
        val quote = MetaApiDataSource(api).getCurrentPrice("t", "acc", "eurusd")
        assertEquals("EURUSD", quote.symbol)
        assertEquals(1.1, quote.bid, 1e-9)
        assertTrue(quote.timestamp > 0L)
    }

    @Test
    fun `executeTrade sends market action and parses string order id`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc-trade", region = "new-york"),
            tradeResponse = MetaApiTradeResponse(
                numericCode = 10009, stringCode = "TRADE_RETCODE_DONE", orderId = "200001", message = "Request completed"
            ),
        )
        val orderId = MetaApiDataSource(api).executeTrade(
            token = "trade-token", accountId = "acc-trade", symbol = "USDJPY",
            type = Mt4OrderType.BUY, lots = 0.25, sl = 148.5, tp = 150.0,
        )
        assertEquals(200001L, orderId)
        assertEquals("ORDER_TYPE_BUY", api.lastTradeRequest?.actionType)
        assertEquals("USDJPY", api.lastTradeRequest?.symbol)
        assertTrue(api.lastUrl?.endsWith("/acc-trade/trade") == true)
    }

    @Test
    fun `executeTrade rejects pending order without explicit open price`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc-trade", region = "new-york"),
        )
        val error = runCatching {
            MetaApiDataSource(api).executeTrade(
                token = "trade-token", accountId = "acc-trade", symbol = "USDJPY",
                type = Mt4OrderType.BUY_LIMIT, lots = 0.25, sl = 148.5, tp = 150.0,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("Pending orders require") == true)
        assertEquals(null, api.lastTradeRequest)
    }


    @Test
    fun `explicit broker rejection uses dedicated rejected exception`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc-trade", region = "new-york"),
            tradeResponse = MetaApiTradeResponse(
                numericCode = 10013, stringCode = "TRADE_RETCODE_INVALID", orderId = "", message = "Invalid request"
            ),
        )
        val error = runCatching {
            MetaApiDataSource(api).executeTrade(
                token = "trade-token", accountId = "acc-trade", symbol = "EURUSD",
                type = Mt4OrderType.BUY, lots = 0.10, sl = null, tp = null,
            )
        }.exceptionOrNull()
        assertTrue(error is MetaApiTradeRejectedException)
    }

    @Test
    fun `documented partial completion response is accepted`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            tradeResponse = MetaApiTradeResponse(
                numericCode = 10010, stringCode = "TRADE_RETCODE_DONE_PARTIAL",
                orderId = "123", message = "Partially completed"
            ),
        )
        assertEquals(123L, MetaApiDataSource(api).executeTrade(
            token = "t", accountId = "acc", symbol = "EURUSD",
            type = Mt4OrderType.BUY, lots = 0.1, sl = null, tp = null,
        ))
    }

    @Test
    fun `delivered then disconnected trade remains unknown not retryable rejection`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            tradeResponse = MetaApiTradeResponse(
                numericCode = -11, stringCode = "TRADE_RETCODE_DISCONNECTED_DURING_TRADE",
                message = "Connection lost after delivery"
            ),
        )
        val error = runCatching {
            MetaApiDataSource(api).executeTrade(
                token = "t", accountId = "acc", symbol = "EURUSD",
                type = Mt4OrderType.BUY, lots = 0.1, sl = null, tp = null,
            )
        }.exceptionOrNull()
        assertTrue(error is MetaApiTradeOutcomeUnknownException)
        assertTrue(error !is MetaApiTradeRejectedException)
    }

    @Test
    fun `realized profit waits for exit deal then sums profit commission and swap`() = runBlocking {
        val noExitApi = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            dealsResponse = listOf(
                MetaApiDealResponse(id = "1", entryType = "DEAL_ENTRY_IN", profit = 0.0, commission = -2.0, swap = 0.0, positionId = "77"),
            ),
        )
        assertEquals(null, MetaApiDataSource(noExitApi).getPositionRealizedProfit("t", "acc", 77L))

        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            dealsResponse = listOf(
                MetaApiDealResponse(id = "1", entryType = "DEAL_ENTRY_IN", profit = 0.0, commission = -2.0, swap = 0.0, positionId = "77"),
                MetaApiDealResponse(id = "2", entryType = "DEAL_ENTRY_OUT", profit = 25.0, commission = -2.0, swap = -1.0, positionId = "77"),
            ),
        )
        val realized = MetaApiDataSource(api).getPositionRealizedProfit("t", "acc", 77L)
        assertEquals(20.0, realized ?: Double.NaN, 1e-9)
        assertTrue(api.lastUrl?.endsWith("/acc/history-deals/position/77") == true)
    }


    @Test
    fun `pending orders map broker state and explicit create includes open price`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            ordersResponse = listOf(
                MetaApiOrderResponse(
                    id = "9001", type = "ORDER_TYPE_BUY_LIMIT", state = "ORDER_STATE_PLACED",
                    symbol = "EURUSD", time = "2026-08-21T10:00:00Z", openPrice = 1.08,
                    currentPrice = 1.09, stopLoss = 1.07, takeProfit = 1.10,
                    volume = 0.2, currentVolume = 0.2, expirationType = "ORDER_TIME_GTC",
                )
            ),
            tradeResponse = MetaApiTradeResponse(
                numericCode = 10008, stringCode = "TRADE_RETCODE_PLACED", orderId = "9002"
            ),
        )
        val dataSource = MetaApiDataSource(api)
        val pending = dataSource.getPendingOrders("t", "acc")
        assertEquals(1, pending.size)
        assertEquals(9001L, pending.single().ticket)
        assertEquals(Mt4OrderType.BUY_LIMIT, pending.single().type)
        val id = dataSource.placePendingOrder(
            "t", "acc",
            com.foxtrader.app.domain.model.Mt4PendingOrderRequest(
                symbol = "EURUSD", type = Mt4OrderType.BUY_LIMIT, lots = 0.2, openPrice = 1.08,
                stopLoss = 1.07, takeProfit = 1.10,
            )
        )
        assertEquals(9002L, id)
        assertEquals("ORDER_TYPE_BUY_LIMIT", api.lastTradeRequest?.actionType)
        assertEquals(1.08, api.lastTradeRequest?.openPrice ?: Double.NaN, 1e-9)
    }

    @Test
    fun `position management maps trailing partial and pending cancel actions`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            tradeResponse = MetaApiTradeResponse(numericCode = 10009, stringCode = "TRADE_RETCODE_DONE", orderId = "1"),
        )
        val dataSource = MetaApiDataSource(api)
        dataSource.modifyPositionProtection(
            "t", "acc", 77L,
            com.foxtrader.app.domain.model.Mt4PositionProtection(stopLoss = 1.0, takeProfit = 2.0, trailingDistancePoints = 50.0),
        )
        assertEquals("POSITION_MODIFY", api.lastTradeRequest?.actionType)
        assertEquals(50.0, api.lastTradeRequest?.trailingStopLoss?.distance?.distance ?: Double.NaN, 1e-9)
        dataSource.partialClosePosition("t", "acc", 77L, 0.05)
        assertEquals("POSITION_PARTIAL", api.lastTradeRequest?.actionType)
        dataSource.cancelPendingOrder("t", "acc", 9001L)
        assertEquals("ORDER_CANCEL", api.lastTradeRequest?.actionType)
    }

    @Test
    fun `getSymbolSpecification validates broker bounds and symbol`() = runBlocking {
        val valid = MetaApiSymbolSpecResponse(
            symbol = "EURUSD", tickSize = 0.00001, point = 0.00001, minVolume = 0.01,
            maxVolume = 100.0, volumeStep = 0.01, contractSize = 100000.0,
            baseCurrency = "EUR", profitCurrency = "USD",
        )
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            symbolSpecResponse = valid,
        )
        assertTrue(MetaApiDataSource(api).getSymbolSpecification("tok", "acc", "EURUSD") != null)

        val badApi = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            symbolSpecResponse = valid.copy(maxVolume = 0.001),
        )
        assertTrue(MetaApiDataSource(badApi).getSymbolSpecification("tok", "acc", "EURUSD") == null)
    }

    @Test
    fun `broker symbol is encoded as a single URL path segment`() = runBlocking {
        val api = FakeMetaApiService(
            provisioned = MetaApiProvisionedAccountResponse(id = "acc", region = "new-york"),
            currentPriceResponse = MetaApiCurrentPriceResponse(
                symbol = "XAU/USD#", bid = 2500.0, ask = 2500.5, time = "2026-08-21T10:00:00Z"
            ),
        )
        MetaApiDataSource(api).getCurrentPrice("t", "acc", "XAU/USD#")
        assertTrue(api.lastUrl?.contains("/symbols/XAU%2FUSD%23/current-price") == true)
    }

    private class FakeMetaApiService(
        private val deployResponse: MetaApiDeployResponse = MetaApiDeployResponse(id = "default-id", state = "DEPLOYED"),
        private val provisioned: MetaApiProvisionedAccountResponse = MetaApiProvisionedAccountResponse(id = "default-id", region = "new-york"),
        private val accountInfoResponse: MetaApiAccountInfoResponse = MetaApiAccountInfoResponse(login = 1),
        private val positionsResponse: List<MetaApiPositionResponse> = emptyList(),
        private val ordersResponse: List<MetaApiOrderResponse> = emptyList(),
        private val tradeResponse: MetaApiTradeResponse = MetaApiTradeResponse(numericCode = 10009, stringCode = "TRADE_RETCODE_DONE", orderId = "1"),
        private val currentPriceResponse: MetaApiCurrentPriceResponse = MetaApiCurrentPriceResponse(symbol = "EURUSD", bid = 1.0, ask = 1.1, time = "2026-08-21T10:00:00Z"),
        private val candlesResponse: List<MetaApiCandleResponse> = emptyList(),
        private val symbolSpecResponse: MetaApiSymbolSpecResponse = MetaApiSymbolSpecResponse(symbol = "EURUSD", tickSize = 0.00001, minVolume = 0.01, maxVolume = 100.0, volumeStep = 0.01, contractSize = 100000.0),
        private val dealsResponse: List<MetaApiDealResponse> = emptyList(),
    ) : MetaApiService {
        var lastAuthToken: String? = null
        var lastTransactionId: String? = null
        var lastDeployRequest: MetaApiDeployRequest? = null
        var lastTradeRequest: MetaApiTradeRequest? = null
        var lastUrl: String? = null

        override suspend fun deployAccount(authToken: String, transactionId: String, request: MetaApiDeployRequest): MetaApiDeployResponse {
            lastAuthToken = authToken; lastTransactionId = transactionId; lastDeployRequest = request
            return deployResponse
        }

        override suspend fun getProvisionedAccount(authToken: String, accountId: String): MetaApiProvisionedAccountResponse {
            lastAuthToken = authToken
            return if (provisioned.id == "default-id") provisioned.copy(id = accountId) else provisioned
        }

        override suspend fun deployProvisionedAccount(authToken: String, accountId: String, executeForAllReplicas: Boolean) {
            lastAuthToken = authToken
        }

        override suspend fun getAccountInformation(url: String, authToken: String, refreshTerminalState: Boolean): MetaApiAccountInfoResponse {
            lastUrl = url; lastAuthToken = authToken; return accountInfoResponse
        }

        override suspend fun getPositions(url: String, authToken: String, refreshTerminalState: Boolean): List<MetaApiPositionResponse> {
            lastUrl = url; lastAuthToken = authToken; return positionsResponse
        }

        override suspend fun getOrders(url: String, authToken: String, refreshTerminalState: Boolean): List<MetaApiOrderResponse> {
            lastUrl = url; lastAuthToken = authToken; return ordersResponse
        }

        override suspend fun executeTrade(url: String, authToken: String, request: MetaApiTradeRequest): MetaApiTradeResponse {
            lastUrl = url; lastAuthToken = authToken; lastTradeRequest = request; return tradeResponse
        }

        override suspend fun getCurrentPrice(url: String, authToken: String, keepSubscription: Boolean): MetaApiCurrentPriceResponse {
            lastUrl = url; lastAuthToken = authToken; return currentPriceResponse
        }

        override suspend fun getHistoricalCandles(url: String, authToken: String, startTime: String?, limit: Int?): List<MetaApiCandleResponse> {
            lastUrl = url; lastAuthToken = authToken; return candlesResponse
        }

        override suspend fun getSymbolSpecification(url: String, authToken: String): MetaApiSymbolSpecResponse {
            lastUrl = url; lastAuthToken = authToken; return symbolSpecResponse
        }

        override suspend fun getDealsByPosition(url: String, authToken: String): List<MetaApiDealResponse> {
            lastUrl = url; lastAuthToken = authToken; return dealsResponse
        }
    }
}
