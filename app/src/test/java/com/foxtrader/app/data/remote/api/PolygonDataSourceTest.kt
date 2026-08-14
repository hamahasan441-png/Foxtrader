package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for Polygon's ticker/range contract.
 *
 * The provider accepts several asset classes behind one endpoint, but Polygon
 * requires an asset prefix for forex, crypto, and indices. These tests guard
 * that translation boundary and the strict-before paging invariant without a
 * network or Android dependency.
 */
class PolygonDataSourceTest {

    @Test
    fun `fetchCandles maps forex ticker and timeframe and sorts results`() = runBlocking {
        val api = FakePolygonApi(
            """
            {
              "ticker": "C:EURUSD",
              "status": "OK",
              "results": [
                { "t": 1704068100000, "o": 1.11, "h": 1.13, "l": 1.10, "c": 1.12, "v": 12 },
                { "t": 1704067200000, "o": 1.10, "h": 1.12, "l": 1.09, "c": 1.11, "v": 10 }
              ]
            }
            """.trimIndent(),
        )
        val dataSource = PolygonDataSource(api)

        val candles = dataSource.fetchCandles(
            symbol = "eur/usd",
            timeframe = Timeframe.M15,
            limit = 2,
            apiKey = "secret",
            endTimestamp = 1_704_068_400_000L,
        )

        assertEquals("C:EURUSD", api.ticker)
        assertEquals(15, api.multiplier)
        assertEquals("minute", api.timespan)
        assertEquals(6, api.limit)
        assertEquals("secret", api.apiKey)
        assertEquals(listOf(1.11, 1.12), candles.map { it.close })
        assertEquals(listOf(10.0, 12.0), candles.map { it.volume })
    }

    @Test
    fun `fetchCandles keeps equities and maps indices and crypto`() = runBlocking {
        val api = FakePolygonApi("{ \"status\": \"OK\", \"results\": [] }")
        val dataSource = PolygonDataSource(api)

        dataSource.fetchCandles("AAPL", Timeframe.D1, apiKey = "k", endTimestamp = NOW)
        assertEquals("AAPL", api.ticker)
        assertEquals(1, api.multiplier)
        assertEquals("day", api.timespan)

        dataSource.fetchCandles("US500", Timeframe.H1, apiKey = "k", endTimestamp = NOW)
        assertEquals("I:SPX", api.ticker)
        assertEquals("hour", api.timespan)

        dataSource.fetchCandles("btc/usdt", Timeframe.H4, apiKey = "k", endTimestamp = NOW)
        assertEquals("X:BTCUSD", api.ticker)
        assertEquals(4, api.multiplier)
    }

    @Test
    fun `fetchCandles throws provider error`() = runBlocking {
        val dataSource = PolygonDataSource(
            FakePolygonApi(
                """{ "status": "ERROR", "error": "invalid API key" }""",
            ),
        )

        val error = try {
            dataSource.fetchCandles("AAPL", Timeframe.D1, apiKey = "bad", endTimestamp = NOW)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("Polygon: invalid API key", error?.message)
    }

    @Test
    fun `fetchCandles skips malformed results and defaults missing volume`() = runBlocking {
        val dataSource = PolygonDataSource(
            FakePolygonApi(
                """
                {
                  "status": "OK",
                  "results": [
                    { "t": 1000, "o": 1, "h": 2, "l": 0.5, "c": 1.5 },
                    { "t": "not-a-time", "o": 1, "h": 2, "l": 0.5, "c": 1.5 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val candles = dataSource.fetchCandles("AAPL", Timeframe.D1, apiKey = "k", endTimestamp = NOW)

        assertEquals(1, candles.size)
        assertEquals(0.0, candles.single().volume, 0.0)
    }

    @Test
    fun `fetchCandlesBefore uses an exclusive upper bound`() = runBlocking {
        val api = FakePolygonApi(
            """
            {
              "status": "OK",
              "results": [
                { "t": 1000, "o": 1, "h": 2, "l": 0.5, "c": 1.5, "v": 1 },
                { "t": 2000, "o": 1, "h": 2, "l": 0.5, "c": 1.6, "v": 2 },
                { "t": 3000, "o": 1, "h": 2, "l": 0.5, "c": 1.7, "v": 3 }
              ]
            }
            """.trimIndent(),
        )
        val dataSource = PolygonDataSource(api)

        val candles = dataSource.fetchCandlesBefore(
            symbol = "AAPL",
            timeframe = Timeframe.M1,
            beforeTimestamp = 3_000L,
            limit = 2,
            apiKey = "k",
        )

        assertEquals(2_999L, api.to)
        assertTrue(candles.all { it.timestamp < 3_000L })
        assertEquals(listOf(1.5, 1.6), candles.map { it.close })
    }

    @Test
    fun `blank keys and invalid paging timestamps are rejected`() = runBlocking {
        val dataSource = PolygonDataSource(FakePolygonApi("{ \"results\": [] }"))

        assertFailsWithMessage("Polygon API key must not be blank.") {
            dataSource.fetchCandles("AAPL", Timeframe.D1, apiKey = " ", endTimestamp = NOW)
        }
        assertFailsWithMessage("Polygon paging timestamp must be positive.") {
            dataSource.fetchCandlesBefore("AAPL", Timeframe.D1, beforeTimestamp = 0, apiKey = "k")
        }
    }

    private suspend fun assertFailsWithMessage(expected: String, block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertEquals(expected, error?.message)
    }

    private class FakePolygonApi(private val body: String) : PolygonApi {
        var ticker: String? = null
        var multiplier: Int? = null
        var timespan: String? = null
        var from: Long? = null
        var to: Long? = null
        var limit: Int? = null
        var apiKey: String? = null

        override suspend fun aggregateBars(
            ticker: String,
            multiplier: Int,
            timespan: String,
            from: Long,
            to: Long,
            adjusted: Boolean,
            sort: String,
            limit: Int,
            apiKey: String,
        ): JsonElement {
            this.ticker = ticker
            this.multiplier = multiplier
            this.timespan = timespan
            this.from = from
            this.to = to
            this.limit = limit
            this.apiKey = apiKey
            return Json.parseToJsonElement(body)
        }
    }

    private companion object {
        const val NOW = 1_704_068_400_000L
    }
}
