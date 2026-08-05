package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Adapter that fetches candle history from Twelve Data's REST API and converts
 * into domain [Candle] objects.
 *
 * Twelve Data supports forex, stocks, indices, crypto, ETFs on one API key —
 * closing the "live data only covers crypto" gap flagged in the Enterprise Master
 * Plan (§8). Symbols use standard conventions: "EUR/USD", "AAPL", "SPX", "BTC/USD".
 * We normalize from FoxTrader format (e.g. "EURUSD") to Twelve Data format (e.g.
 * "EUR/USD") for forex pairs.
 */
class TwelveDataDataSource @Inject constructor(
    private val api: TwelveDataApi,
) {

    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
        apiKey: String,
    ): List<Candle> {
        val response = api.timeSeries(
            symbol = normalizeSymbol(symbol),
            interval = timeframeToInterval(timeframe),
            outputSize = limit.coerceIn(1, 5000),
            apiKey = apiKey,
        )
        return parseResponse(response)
    }

    suspend fun fetchCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = 500,
        apiKey: String,
    ): List<Candle> {
        val endDate = DATETIME_FORMAT.format(
            LocalDateTime.ofEpochSecond(beforeTimestamp / 1000L - 1L, 0, ZoneOffset.UTC),
        )
        val response = api.timeSeries(
            symbol = normalizeSymbol(symbol),
            interval = timeframeToInterval(timeframe),
            outputSize = limit.coerceIn(1, 5000),
            apiKey = apiKey,
            endDate = endDate,
        )
        return parseResponse(response)
            .filter { it.timestamp < beforeTimestamp }
            .takeLast(limit.coerceIn(1, 5000))
    }

    private fun parseResponse(json: kotlinx.serialization.json.JsonElement): List<Candle> {
        val root = json.jsonObject

        // Error handling: Twelve Data returns {"code": 400, "message": "...", "status": "error"}
        root["status"]?.jsonPrimitive?.contentOrNull?.let { status ->
            if (status == "error") {
                val msg = root["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown Twelve Data error"
                throw IllegalStateException("Twelve Data: $msg")
            }
        }

        val values = root["values"]?.jsonArray ?: return emptyList()
        return values.mapNotNull { element ->
            val obj = element.jsonObject
            val datetime = obj["datetime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val timestamp = parseTimestamp(datetime) ?: return@mapNotNull null
            val open = obj["open"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val high = obj["high"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val low = obj["low"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val close = obj["close"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val volume = obj["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            Candle(timestamp = timestamp, open = open, high = high, low = low, close = close, volume = volume, source = com.foxtrader.app.domain.model.CandleSource.CACHED)
        }.sortedBy { it.timestamp }
    }

    private fun timeframeToInterval(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1min"
        Timeframe.M5 -> "5min"
        Timeframe.M15 -> "15min"
        Timeframe.M30 -> "30min"
        Timeframe.H1 -> "1h"
        Timeframe.H4 -> "4h"
        Timeframe.D1 -> "1day"
        Timeframe.W1 -> "1week"
        Timeframe.MN -> "1month"
    }

    /**
     * Normalize FoxTrader symbol format to Twelve Data format.
     * Forex: "EURUSD" → "EUR/USD"; stocks/indices/crypto pass through as-is.
     */
    private fun normalizeSymbol(symbol: String): String {
        val upper = symbol.uppercase().trim()
        if (upper.length == 6 && upper.all { it in 'A'..'Z' } && looksLikeForex(upper)) {
            return "${upper.substring(0, 3)}/${upper.substring(3)}"
        }
        return upper
    }

    private fun looksLikeForex(symbol: String): Boolean =
        COMMON_CURRENCIES.contains(symbol.substring(0, 3)) &&
            COMMON_CURRENCIES.contains(symbol.substring(3))

    private fun parseTimestamp(value: String): Long? {
        return runCatching {
            LocalDateTime.parse(value, DATETIME_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull() ?: runCatching {
            LocalDateTime.parse("$value 00:00:00", DATETIME_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        val DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val COMMON_CURRENCIES = setOf(
            "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD",
            "SEK", "NOK", "DKK", "SGD", "HKD", "ZAR", "MXN", "TRY",
        )
    }
}
