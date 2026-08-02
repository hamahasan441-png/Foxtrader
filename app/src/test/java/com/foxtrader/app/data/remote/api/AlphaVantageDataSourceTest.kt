package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaVantageDataSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeAlphaVantageApi(
        private val responseJson: String,
    ) : AlphaVantageApi {
        var function: String? = null
        var symbol: String? = null
        var fromSymbol: String? = null
        var toSymbol: String? = null
        var interval: String? = null
        var apiKey: String? = null

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
            this.apiKey = apiKey
            return Json.parseToJsonElement(responseJson)
        }
    }

    private val validIntradayResponse = """
        {
          "Meta Data": {
            "1. Information": "FX Intraday (60min) Prices",
            "2. From Symbol": "EUR",
            "3. To Symbol": "USD"
          },
          "Time Series FX (60min)": {
            "2025-06-15 14:00:00": {
              "1. open": "1.08500",
              "2. high": "1.08700",
              "3. low": "1.08400",
              "4. close": "1.08650"
            },
            "2025-06-15 13:00:00": {
              "1. open": "1.08300",
              "2. high": "1.08550",
              "3. low": "1.08200",
              "4. close": "1.08500"
            }
          }
        }
    """.trimIndent()

    private val validDailyStockResponse = """
        {
          "Meta Data": {
            "1. Information": "Daily Prices (open, high, low, close) and Volumes",
            "2. Symbol": "AAPL"
          },
          "Time Series (Daily)": {
            "2025-06-15": {
              "1. open": "210.10",
              "2. high": "212.30",
              "3. low": "209.20",
              "4. close": "211.40",
              "5. volume": "12345"
            },
            "2025-06-14": {
              "1. open": "208.00",
              "2. high": "210.00",
              "3. low": "207.50",
              "4. close": "209.10",
              "5. volume": "54321"
            }
          }
        }
    """.trimIndent()

    private val errorResponse = """
        {
          "Error Message": "Invalid API key"
        }
    """.trimIndent()

    private val emptyResponse = """
        {
          "Meta Data": { "2. Symbol": "INVALID" },
          "Time Series (Daily)": {}
        }
    """.trimIndent()

    @Test
    fun `fetchCandles parses Alpha Vantage series into sorted candles`() = runBlocking {
        val api = FakeAlphaVantageApi(validDailyStockResponse)
        val source = AlphaVantageDataSource(api)

        val candles = source.fetchCandles("AAPL", Timeframe.D1, 500, "test-key")

        assertEquals(2, candles.size)
        assertEquals("TIME_SERIES_DAILY", api.function)
        assertEquals("AAPL", api.symbol)
        assertNull(api.fromSymbol)
        assertNull(api.toSymbol)
        assertNull(api.interval)
        assertEquals("test-key", api.apiKey)
        assertTrue(candles[0].timestamp < candles[1].timestamp)
        assertEquals(208.00, candles[0].open, 1e-6)
        assertEquals(210.00, candles[0].high, 1e-6)
        assertEquals(207.50, candles[0].low, 1e-6)
        assertEquals(209.10, candles[0].close, 1e-6)
        assertEquals(54321.0, candles[0].volume, 1e-6)
    }

    @Test
    fun `fetchCandles throws on API error response`() = runBlocking {
        val source = AlphaVantageDataSource(FakeAlphaVantageApi(errorResponse))

        val exception = runCatching { source.fetchCandles("EURUSD", Timeframe.H1, 500, "bad-key") }

        assertTrue(exception.isFailure)
        assertTrue(exception.exceptionOrNull()!!.message!!.contains("Invalid API key"))
    }

    @Test
    fun `fetchCandles returns empty list when response has no series entries`() = runBlocking {
        val source = AlphaVantageDataSource(FakeAlphaVantageApi(emptyResponse))

        val candles = source.fetchCandles("INVALID", Timeframe.D1, 500, "test-key")

        assertTrue(candles.isEmpty())
    }

    @Test
    fun `fetchCandlesBefore filters by timestamp`() = runBlocking {
        val source = AlphaVantageDataSource(FakeAlphaVantageApi(validDailyStockResponse))
        val allCandles = source.fetchCandles("AAPL", Timeframe.D1, 500, "test-key")
        val cutoff = allCandles[1].timestamp

        val filtered = source.fetchCandlesBefore("AAPL", Timeframe.D1, cutoff, 500, "test-key")

        assertEquals(1, filtered.size)
        assertTrue(filtered.all { it.timestamp < cutoff })
        assertEquals(allCandles.first().timestamp, filtered.single().timestamp)
    }

    @Test
    fun `forex symbol uses FX endpoint and splits pair`() = runBlocking {
        val api = FakeAlphaVantageApi(validIntradayResponse)
        val source = AlphaVantageDataSource(api)

        val candles = source.fetchCandles("eur/usd", Timeframe.H1, 100, "key")

        assertEquals(2, candles.size)
        assertEquals("FX_INTRADAY", api.function)
        assertEquals("EUR", api.fromSymbol)
        assertEquals("USD", api.toSymbol)
        assertEquals("60min", api.interval)
        assertNull(api.symbol)
    }

    @Test
    fun `unsupported stock timeframe returns empty list`() = runBlocking {
        val api = FakeAlphaVantageApi(validDailyStockResponse)
        val source = AlphaVantageDataSource(api)

        val candles = source.fetchCandles("AAPL", Timeframe.H4, 100, "key")

        assertTrue(candles.isEmpty())
    }
}
