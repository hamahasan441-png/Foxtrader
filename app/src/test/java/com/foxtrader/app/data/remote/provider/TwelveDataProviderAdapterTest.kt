package com.foxtrader.app.data.remote.provider

import com.foxtrader.app.data.remote.api.TwelveDataApi
import com.foxtrader.app.data.remote.api.TwelveDataDataSource
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TwelveDataProviderAdapter] verifying delegation to
 * [TwelveDataDataSource], error handling, and property correctness.
 */
class TwelveDataProviderAdapterTest {

    private val successBody = """
        {
          "meta": { "symbol": "EUR/USD" },
          "values": [
            { "datetime": "2024-01-01 00:00:00", "open": "100", "high": "102", "low": "99", "close": "101", "volume": "10" },
            { "datetime": "2024-01-01 00:15:00", "open": "101", "high": "103", "low": "100", "close": "102", "volume": "11" }
          ],
          "status": "ok"
        }
    """.trimIndent()

    @Test
    fun `fetchHistory delegates to dataSource and returns candles on success`() = runBlocking {
        val api = FakeTwelveDataApi(successBody)
        val dataSource = TwelveDataDataSource(api)
        val adapter = TwelveDataProviderAdapter(dataSource) { "test-key" }

        val candles = adapter.fetchHistory("EURUSD", Timeframe.M15, limit = 10)

        assertEquals(2, candles.size)
        assertEquals(101.0, candles[0].close, 0.0)
        assertEquals(102.0, candles[1].close, 0.0)
        assertEquals("EUR/USD", api.symbol)
        assertEquals("15min", api.interval)
    }

    @Test
    fun `fetchHistory returns empty list when dataSource throws`() = runBlocking {
        val api = ThrowingTwelveDataApi()
        val dataSource = TwelveDataDataSource(api)
        val adapter = TwelveDataProviderAdapter(dataSource) { "test-key" }

        val candles = adapter.fetchHistory("EURUSD", Timeframe.H1, limit = 100)

        assertTrue(candles.isEmpty())
    }

    @Test
    fun `supports returns true for any symbol when supportedSymbols is empty`() {
        val api = FakeTwelveDataApi("""{ "values": [] }""")
        val dataSource = TwelveDataDataSource(api)
        val adapter = TwelveDataProviderAdapter(dataSource) { "test-key" }

        assertTrue(adapter.supports("EURUSD"))
        assertTrue(adapter.supports("AAPL"))
        assertTrue(adapter.supports("BTC/USD"))
        assertTrue(adapter.supports("RANDOM_SYMBOL"))
    }

    @Test
    fun `fetchHistory with endTime uses fetchCandlesBefore`() = runBlocking {
        val api = FakeTwelveDataApi(successBody)
        val dataSource = TwelveDataDataSource(api)
        val adapter = TwelveDataProviderAdapter(dataSource) { "test-key" }

        // Use a timestamp after both candles so they pass the filter.
        val endTime = java.time.LocalDateTime.parse("2024-01-01T01:00:00")
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        val candles = adapter.fetchHistory("EURUSD", Timeframe.M15, limit = 10, endTime = endTime)

        // fetchCandlesBefore sets an endDate query param.
        assertTrue("end_date should be sent when endTime is provided", api.endDate != null)
        assertTrue(candles.isNotEmpty())
        assertTrue(candles.all { it.timestamp < endTime })
    }

    @Test
    fun `properties are correct`() {
        val api = FakeTwelveDataApi("""{ "values": [] }""")
        val dataSource = TwelveDataDataSource(api)
        val adapter = TwelveDataProviderAdapter(dataSource) { "test-key" }

        assertEquals("twelvedata", adapter.id)
        assertEquals("Twelve Data (Multi-Asset)", adapter.displayName)
        assertEquals(false, adapter.supportsLive)
        assertEquals(Timeframe.entries.toList(), adapter.supportedTimeframes)
        assertTrue(adapter.supportedSymbols.isEmpty())
    }

    private class FakeTwelveDataApi(private val body: String) : TwelveDataApi {
        var symbol: String? = null
        var interval: String? = null
        var outputSize: Int? = null
        var endDate: String? = null

        override suspend fun timeSeries(
            symbol: String,
            interval: String,
            outputSize: Int,
            apiKey: String,
            endDate: String?,
            format: String,
        ): JsonElement {
            this.symbol = symbol
            this.interval = interval
            this.outputSize = outputSize
            this.endDate = endDate
            return Json.parseToJsonElement(body)
        }
    }

    private class ThrowingTwelveDataApi : TwelveDataApi {
        override suspend fun timeSeries(
            symbol: String,
            interval: String,
            outputSize: Int,
            apiKey: String,
            endDate: String?,
            format: String,
        ): JsonElement = throw RuntimeException("Network error")
    }
}
