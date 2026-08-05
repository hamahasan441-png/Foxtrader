package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TwelveDataDataSource]'s pure parsing/normalization logic,
 * mirroring the [BybitDataSourceTest] pattern with a hand-written fake API.
 * No network: the fake returns canned JSON so we can pin symbol normalization,
 * timeframe→interval mapping, ascending sort, the error path, and paging.
 */
class TwelveDataDataSourceTest {

    @Test
    fun `fetchCandles normalizes forex symbol maps interval and sorts ascending`() = runBlocking {
        // Values deliberately out of order (newest first, like the real API).
        val api = FakeTwelveDataApi(
            """
            {
              "meta": { "symbol": "EUR/USD" },
              "values": [
                { "datetime": "2024-01-01 00:30:00", "open": "102", "high": "104", "low": "101", "close": "103", "volume": "12" },
                { "datetime": "2024-01-01 00:00:00", "open": "100", "high": "102", "low": "99",  "close": "101", "volume": "10" },
                { "datetime": "2024-01-01 00:15:00", "open": "101", "high": "103", "low": "100", "close": "102", "volume": "11" }
              ],
              "status": "ok"
            }
            """.trimIndent(),
        )
        val dataSource = TwelveDataDataSource(api)

        val candles = dataSource.fetchCandles("eurusd", Timeframe.M15, limit = 3, apiKey = "k")

        assertEquals("EUR/USD", api.symbol)
        assertEquals("15min", api.interval)
        assertEquals(3, api.outputSize)
        // Ascending by timestamp regardless of response order.
        assertEquals(listOf(101.0, 102.0, 103.0), candles.map { it.close })
        assertEquals(10.0, candles.first().volume, 0.0)
        assertEquals(12.0, candles.last().volume, 0.0)
    }

    @Test
    fun `fetchCandles passes non-forex symbols through unchanged`() = runBlocking {
        val api = FakeTwelveDataApi("""{ "values": [] }""")
        val dataSource = TwelveDataDataSource(api)

        dataSource.fetchCandles("AAPL", Timeframe.D1, apiKey = "k")
        assertEquals("AAPL", api.symbol)
        assertEquals("1day", api.interval)

        // 6 chars but not a currency pair → not treated as forex.
        dataSource.fetchCandles("BTCUSD", Timeframe.H1, apiKey = "k")
        assertEquals("BTCUSD", api.symbol)
        assertEquals("1h", api.interval)
    }

    @Test
    fun `fetchCandles throws provider message on error status`() = runBlocking {
        val api = FakeTwelveDataApi(
            """{ "code": 400, "message": "bad symbol", "status": "error" }""",
        )
        val dataSource = TwelveDataDataSource(api)

        val error = try {
            dataSource.fetchCandles("EURUSD", Timeframe.H1, apiKey = "k")
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("Twelve Data: bad symbol", error?.message)
    }

    @Test
    fun `fetchCandles returns empty when values array is absent`() = runBlocking {
        val api = FakeTwelveDataApi("""{ "status": "ok" }""")
        val dataSource = TwelveDataDataSource(api)

        assertTrue(dataSource.fetchCandles("EURUSD", Timeframe.H1, apiKey = "k").isEmpty())
    }

    @Test
    fun `fetchCandlesBefore sends an end_date and keeps only earlier candles`() = runBlocking {
        val api = FakeTwelveDataApi(
            """
            {
              "values": [
                { "datetime": "2024-01-01 00:00:00", "open": "100", "high": "102", "low": "99", "close": "101", "volume": "10" },
                { "datetime": "2024-01-01 00:15:00", "open": "101", "high": "103", "low": "100", "close": "102", "volume": "11" }
              ]
            }
            """.trimIndent(),
        )
        val dataSource = TwelveDataDataSource(api)

        // 00:15:00 UTC in millis; only the 00:00:00 candle is strictly earlier.
        val cutoff = java.time.LocalDateTime.parse("2024-01-01T00:15:00")
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        val candles = dataSource.fetchCandlesBefore("EURUSD", Timeframe.M15, cutoff, apiKey = "k")

        assertNotNull("an end_date must be sent for paging", api.endDate)
        assertTrue(candles.all { it.timestamp < cutoff })
        assertEquals(listOf(101.0), candles.map { it.close })
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
}
