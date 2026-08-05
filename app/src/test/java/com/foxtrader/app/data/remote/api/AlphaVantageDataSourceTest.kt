package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AlphaVantageDataSource]'s request-building and response
 * parsing, using a hand-written fake API (no network). Pins the forex-vs-stock
 * function/interval selection, the "Time Series" object discovery, ascending
 * sort, and the error-note path.
 */
class AlphaVantageDataSourceTest {

    @Test
    fun `forex intraday selects FX_INTRADAY splits the pair and sorts ascending`() = runBlocking {
        val api = FakeAlphaVantageApi(
            """
            {
              "Meta Data": { "1. Information": "FX Intraday" },
              "Time Series FX (15min)": {
                "2024-01-01 00:30:00": { "1. open": "1.1002", "2. high": "1.1004", "3. low": "1.1001", "4. close": "1.1003" },
                "2024-01-01 00:00:00": { "1. open": "1.1000", "2. high": "1.1002", "3. low": "1.0999", "4. close": "1.1001" }
              }
            }
            """.trimIndent(),
        )
        val dataSource = AlphaVantageDataSource(api)

        val candles = dataSource.fetchCandles("EURUSD", Timeframe.M15, apiKey = "k")

        assertEquals("FX_INTRADAY", api.function)
        assertEquals("EUR", api.fromSymbol)
        assertEquals("USD", api.toSymbol)
        assertEquals("15min", api.interval)
        // Ascending by timestamp; FX responses carry no volume → defaults to 0.
        assertEquals(listOf(1.1001, 1.1003), candles.map { it.close })
        assertEquals(0.0, candles.first().volume, 0.0)
    }

    @Test
    fun `stock daily selects TIME_SERIES_DAILY and parses volume`() = runBlocking {
        val api = FakeAlphaVantageApi(
            """
            {
              "Time Series (Daily)": {
                "2024-01-02": { "1. open": "180", "2. high": "182", "3. low": "179", "4. close": "181", "5. volume": "1000000" },
                "2024-01-01": { "1. open": "178", "2. high": "181", "3. low": "177", "4. close": "180", "5. volume": "900000" }
              }
            }
            """.trimIndent(),
        )
        val dataSource = AlphaVantageDataSource(api)

        val candles = dataSource.fetchCandles("AAPL", Timeframe.D1, apiKey = "k")

        assertEquals("TIME_SERIES_DAILY", api.function)
        assertEquals("AAPL", api.symbol)
        assertEquals(listOf(180.0, 181.0), candles.map { it.close })
        assertEquals(1_000_000.0, candles.last().volume, 0.0)
    }

    @Test
    fun `takeLast honours the requested limit`() = runBlocking {
        val api = FakeAlphaVantageApi(
            """
            {
              "Time Series (Daily)": {
                "2024-01-01": { "1. open": "1", "2. high": "1", "3. low": "1", "4. close": "1", "5. volume": "1" },
                "2024-01-02": { "1. open": "2", "2. high": "2", "3. low": "2", "4. close": "2", "5. volume": "1" },
                "2024-01-03": { "1. open": "3", "2. high": "3", "3. low": "3", "4. close": "3", "5. volume": "1" }
              }
            }
            """.trimIndent(),
        )
        val dataSource = AlphaVantageDataSource(api)

        val candles = dataSource.fetchCandles("AAPL", Timeframe.D1, limit = 2, apiKey = "k")

        // Most recent two, still ascending.
        assertEquals(listOf(2.0, 3.0), candles.map { it.close })
    }

    @Test
    fun `rate-limit note surfaces as a provider error`() = runBlocking {
        val api = FakeAlphaVantageApi("""{ "Note": "call frequency exceeded" }""")
        val dataSource = AlphaVantageDataSource(api)

        val error = try {
            dataSource.fetchCandles("AAPL", Timeframe.D1, apiKey = "k")
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(error?.message?.startsWith("Alpha Vantage:") == true)
        assertTrue(error?.message?.contains("call frequency exceeded") == true)
    }

    private class FakeAlphaVantageApi(private val body: String) : AlphaVantageApi {
        var function: String? = null
        var symbol: String? = null
        var fromSymbol: String? = null
        var toSymbol: String? = null
        var interval: String? = null

        override suspend fun query(
            function: String,
            symbol: String?,
            fromSymbol: String?,
            toSymbol: String?,
            interval: String?,
            outputSize: String,
            apiKey: String,
        ): JsonElement {
            this.function = function
            this.symbol = symbol
            this.fromSymbol = fromSymbol
            this.toSymbol = toSymbol
            this.interval = interval
            return Json.parseToJsonElement(body)
        }
    }
}
