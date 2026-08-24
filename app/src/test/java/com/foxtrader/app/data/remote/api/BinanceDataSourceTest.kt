package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceDataSourceTest {

    @Test
    fun `fetchCandles parses positional kline arrays and sorts ascending`() = runBlocking {
        val fakeApi = FakeBinanceApi(
            klines = listOf(
                JsonArray(listOf(
                    JsonPrimitive(1000L), JsonPrimitive("100.0"), JsonPrimitive("105.0"),
                    JsonPrimitive("95.0"), JsonPrimitive("102.0"), JsonPrimitive("50.0"),
                )),
                JsonArray(listOf(
                    JsonPrimitive(2000L), JsonPrimitive("102.0"), JsonPrimitive("107.0"),
                    JsonPrimitive("99.0"), JsonPrimitive("104.0"), JsonPrimitive("60.0"),
                )),
            ),
        )
        val dataSource = BinanceDataSource(fakeApi)

        val candles = dataSource.fetchCandles("BTCUSDT", Timeframe.M1, limit = 2)

        assertEquals(2, candles.size)
        assertEquals(1000L, candles[0].timestamp)
        assertEquals(100.0, candles[0].open, 1e-9)
        assertEquals(105.0, candles[0].high, 1e-9)
        assertEquals(95.0, candles[0].low, 1e-9)
        assertEquals(102.0, candles[0].close, 1e-9)
        assertEquals(50.0, candles[0].volume, 1e-9)
    }

    @Test
    fun `discoverSymbols parses exchangeInfo and normalizes tickSize and status`() = runBlocking {
        val fakeApi = FakeBinanceApi(
            exchangeInfo = BinanceExchangeInfoResponse(
                symbols = listOf(
                    BinanceExchangeSymbol(
                        symbol = "BTCUSDT",
                        status = "TRADING",
                        baseAsset = "BTC",
                        quoteAsset = "USDT",
                        isSpotTradingAllowed = true,
                        filters = listOf(
                            BinanceSymbolFilter(filterType = "PRICE_FILTER", tickSize = "0.01"),
                        ),
                    ),
                    BinanceExchangeSymbol(
                        symbol = "ETHUSDT",
                        status = "BREAK",
                        baseAsset = "ETH",
                        quoteAsset = "USDT",
                        isSpotTradingAllowed = true,
                        filters = listOf(
                            BinanceSymbolFilter(filterType = "PRICE_FILTER", tickSize = "0.01"),
                        ),
                    ),
                ),
            ),
        )
        val dataSource = BinanceDataSource(fakeApi)

        val symbols = dataSource.discoverSymbols()

        assertEquals(2, symbols.size)
        val btc = symbols.first { it.providerSymbol == "BTCUSDT" }
        assertEquals(DataProvider.BINANCE, btc.provider)
        assertEquals("BTC", btc.baseAsset)
        assertEquals("USDT", btc.quoteAsset)
        assertEquals("BTCUSDT", btc.canonicalSymbol)
        assertEquals(0.01, btc.tickSize ?: 0.0, 1e-9)
        assertTrue(btc.isTrading)

        val eth = symbols.first { it.providerSymbol == "ETHUSDT" }
        assertFalse(eth.isTrading)
    }

    @Test
    fun `isBinanceSymbol recognises common suffixes`() {
        val dataSource = BinanceDataSource(FakeBinanceApi())
        assertTrue(dataSource.isBinanceSymbol("BTCUSDT"))
        assertTrue(dataSource.isBinanceSymbol("ETHBUSD"))
        assertTrue(dataSource.isBinanceSymbol("BNBBTC"))
    }

    private class FakeBinanceApi(
        private val klines: List<JsonArray> = emptyList(),
        private val exchangeInfo: BinanceExchangeInfoResponse = BinanceExchangeInfoResponse(),
    ) : BinanceApi {
        override suspend fun getKlines(
            symbol: String,
            interval: String,
            limit: Int,
            startTime: Long?,
            endTime: Long?,
        ): List<JsonArray> = klines

        override suspend fun getExchangeInfo(): BinanceExchangeInfoResponse = exchangeInfo
    }
}
