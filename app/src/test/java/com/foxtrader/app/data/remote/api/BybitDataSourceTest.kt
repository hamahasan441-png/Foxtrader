package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BybitDataSourceTest {

    @Test
    fun `fetchCandles normalizes symbol maps timeframe and sorts candles ascending`() = runBlocking {
        val api = FakeBybitApi(
            response = BybitKlineResponse(
                retCode = 0,
                result = BybitKlineResult(
                    candles = listOf(
                        listOf("3000", "102.0", "104.0", "101.0", "103.0", "12.0", "0"),
                        listOf("1000", "100.0", "102.0", "99.0", "101.0", "10.0", "0"),
                        listOf("2000", "101.0", "103.0", "100.0", "102.0", "11.0", "0"),
                    )
                )
            )
        )
        val dataSource = BybitDataSource(api)

        val candles = dataSource.fetchCandles("btc/usdt", Timeframe.M15, limit = 3)

        assertEquals("spot", api.category)
        assertEquals("BTCUSDT", api.symbol)
        assertEquals("15", api.interval)
        assertEquals(3, api.limit)
        assertEquals(listOf(1000L, 2000L, 3000L), candles.map { it.timestamp })
        assertEquals(101.0, candles.first().close, 0.0)
        assertEquals(12.0, candles.last().volume, 0.0)
    }

    @Test
    fun `fetchCandles throws provider message when Bybit returns non-zero code`() = runBlocking {
        val api = FakeBybitApi(BybitKlineResponse(retCode = 10001, retMsg = "bad request"))
        val dataSource = BybitDataSource(api)

        val error = try {
            dataSource.fetchCandles("BTCUSDT", Timeframe.H1)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(error != null)
        assertEquals("Bybit: bad request", error?.message)
    }

    @Test
    fun `isBybitSymbol accepts common crypto quote suffixes`() {
        val dataSource = BybitDataSource(FakeBybitApi(BybitKlineResponse()))

        assertTrue(dataSource.isBybitSymbol("ETHUSDT"))
        assertTrue(dataSource.isBybitSymbol("sol/usdc"))
    }

    private class FakeBybitApi(
        private val response: BybitKlineResponse,
    ) : BybitApi {
        var category: String? = null
        var symbol: String? = null
        var interval: String? = null
        var limit: Int? = null

        override suspend fun getKlines(
            category: String,
            symbol: String,
            interval: String,
            limit: Int,
            end: Long?,
        ): BybitKlineResponse {
            this.category = category
            this.symbol = symbol
            this.interval = interval
            this.limit = limit
            return response
        }
    }
}
