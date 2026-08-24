package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuCoinDataSourceTest {

    @Test
    fun `fetchCandles normalizes symbol maps type sorts ascending and maps OCHL with seconds to millis`() = runBlocking {
        // KuCoin row order is [time(sec), open, close, high, low, volume, turnover].
        // Hand-picked distinct values so a wrong index mapping cannot pass.
        val api = FakeKuCoinApi(
            response = KuCoinCandleResponse(
                code = "200000",
                data = listOf(
                    // newest first
                    listOf("3000", "300.0", "303.0", "305.0", "299.0", "32.0", "9600.0"),
                    listOf("1000", "100.0", "103.0", "105.0", "99.0", "12.0", "1200.0"),
                    listOf("2000", "200.0", "203.0", "205.0", "199.0", "22.0", "4400.0"),
                )
            )
        )
        val dataSource = KuCoinDataSource(api)

        val candles = dataSource.fetchCandles("btc/usdt", Timeframe.M15, limit = 3)

        assertEquals("BTC-USDT", api.symbol)
        assertEquals("15min", api.type)
        // seconds -> millis conversion and ascending sort
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), candles.map { it.timestamp })

        val first = candles.first()
        assertEquals(1_000_000L, first.timestamp) // 1000s * 1000
        assertEquals(100.0, first.open, 0.0)   // index 1
        assertEquals(103.0, first.close, 0.0)  // index 2
        assertEquals(105.0, first.high, 0.0)   // index 3
        assertEquals(99.0, first.low, 0.0)     // index 4
        assertEquals(12.0, first.volume, 0.0)  // index 5
    }

    @Test
    fun `fetchCandles throws provider message when KuCoin returns non-success code`() = runBlocking {
        val api = FakeKuCoinApi(KuCoinCandleResponse(code = "400100", msg = "invalid symbol"))
        val dataSource = KuCoinDataSource(api)

        val error = try {
            dataSource.fetchCandles("BTCUSDT", Timeframe.H1)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(error != null)
        assertEquals("KuCoin: invalid symbol", error?.message)
    }

    @Test
    fun `isKuCoinSymbol accepts common crypto quote suffixes`() {
        val dataSource = KuCoinDataSource(FakeKuCoinApi(KuCoinCandleResponse()))

        assertTrue(dataSource.isKuCoinSymbol("ETHUSDT"))
        assertTrue(dataSource.isKuCoinSymbol("sol/usdc"))
        assertTrue(dataSource.isKuCoinSymbol("BTC-USD"))
    }

    private class FakeKuCoinApi(
        private val response: KuCoinCandleResponse,
        private val symbolsResponse: KuCoinSymbolsResponse = KuCoinSymbolsResponse(),
    ) : KuCoinApi {
        var symbol: String? = null
        var type: String? = null
        var market: String? = null

        override suspend fun getCandles(
            symbol: String,
            type: String,
        ): KuCoinCandleResponse {
            this.symbol = symbol
            this.type = type
            return response
        }

        override suspend fun getSymbols(market: String?): KuCoinSymbolsResponse {
            this.market = market
            return symbolsResponse
        }
    }
}
