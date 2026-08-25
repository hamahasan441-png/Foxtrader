package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkxDataSourceTest {

    @Test
    fun `fetchCandles normalizes symbol maps bar and sorts candles ascending`() = runBlocking {
        val api = FakeOkxApi(
            response = OkxCandleResponse(
                code = "0",
                data = listOf(
                    listOf("3000", "102.0", "104.0", "101.0", "103.0", "12.0", "0", "0", "1"),
                    listOf("1000", "100.0", "102.0", "99.0", "101.0", "10.0", "0", "0", "1"),
                    listOf("2000", "101.0", "103.0", "100.0", "102.0", "11.0", "0", "0", "1"),
                )
            )
        )
        val dataSource = OkxDataSource(api)

        val candles = dataSource.fetchCandles("btc/usdt", Timeframe.M15, limit = 3)

        assertEquals("BTC-USDT", api.instId)
        assertEquals("15m", api.bar)
        assertEquals(3, api.limit)
        assertEquals(listOf(1000L, 2000L, 3000L), candles.map { it.timestamp })
        assertEquals(100.0, candles.first().open, 0.0)
        assertEquals(101.0, candles.first().close, 0.0)
        assertEquals(102.0, candles.first().high, 0.0)
        assertEquals(99.0, candles.first().low, 0.0)
        assertEquals(12.0, candles.last().volume, 0.0)
    }

    @Test
    fun `fetchCandles throws provider message when OKX returns non-zero code`() = runBlocking {
        val api = FakeOkxApi(OkxCandleResponse(code = "50011", msg = "rate limited"))
        val dataSource = OkxDataSource(api)

        val error = try {
            dataSource.fetchCandles("BTCUSDT", Timeframe.H1)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(error != null)
        assertEquals("OKX: rate limited", error?.message)
    }

    @Test
    fun `discoverSymbols preserves OKX instId and live state`() = runBlocking {
        val api = FakeOkxApi(
            response = OkxCandleResponse(),
            instrumentResponse = OkxInstrumentResponse(
                data = listOf(
                    OkxInstrument(
                        instType = "SPOT",
                        instId = "BTC-USDT",
                        baseCcy = "BTC",
                        quoteCcy = "USDT",
                        tickSz = "0.1",
                        state = "live",
                    ),
                    OkxInstrument(
                        instType = "SPOT",
                        instId = "ETH-USDT",
                        baseCcy = "ETH",
                        quoteCcy = "USDT",
                        tickSz = "0.01",
                        state = "suspend",
                    ),
                ),
            ),
        )

        val symbols = OkxDataSource(api).discoverSymbols()

        assertEquals("SPOT", api.instType)
        assertEquals(2, symbols.size)
        val btc = symbols.first { it.providerSymbol == "BTC-USDT" }
        assertEquals(DataProvider.OKX, btc.provider)
        assertEquals("BTCUSDT", btc.canonicalSymbol)
        assertEquals(0.1, btc.tickSize ?: 0.0, 1e-9)
        assertEquals(1, btc.pricePrecision)
        assertTrue(btc.isTrading)
        assertFalse(symbols.first { it.providerSymbol == "ETH-USDT" }.isTrading)
    }

    @Test
    fun `isOkxSymbol accepts common crypto quote suffixes`() {
        val dataSource = OkxDataSource(FakeOkxApi(OkxCandleResponse()))

        assertTrue(dataSource.isOkxSymbol("ETHUSDT"))
        assertTrue(dataSource.isOkxSymbol("sol/usdc"))
        assertTrue(dataSource.isOkxSymbol("BTC-USD"))
    }

    private class FakeOkxApi(
        private val response: OkxCandleResponse,
        private val instrumentResponse: OkxInstrumentResponse = OkxInstrumentResponse(),
    ) : OkxApi {
        var instId: String? = null
        var bar: String? = null
        var limit: Int? = null
        var after: String? = null
        var instType: String? = null

        override suspend fun getCandles(
            instId: String,
            bar: String,
            limit: Int,
            after: String?,
        ): OkxCandleResponse {
            this.instId = instId
            this.bar = bar
            this.limit = limit
            this.after = after
            return response
        }

        override suspend fun getInstruments(instType: String): OkxInstrumentResponse {
            this.instType = instType
            return instrumentResponse
        }
    }
}
