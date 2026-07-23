package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Adapter for Alpha Vantage historical candles.
 */
class AlphaVantageDataSource @Inject constructor(
    private val api: AlphaVantageApi,
) {
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
        apiKey: String,
    ): List<Candle> {
        require(limit >= 0) { "Invalid data limit: value must not be negative." }
        if (limit == 0) return emptyList()

        val request = buildRequest(symbol, timeframe) ?: return emptyList()
        val raw = api.query(
            function = request.function,
            symbol = request.symbol,
            fromSymbol = request.fromSymbol,
            toSymbol = request.toSymbol,
            interval = request.interval,
            apiKey = apiKey,
        )
        val root = raw.jsonObject

        listOf("Error Message", "Note", "Information")
            .firstNotNullOfOrNull { key -> root[key]?.jsonPrimitive?.contentOrNull }
            ?.let { message -> throw IllegalStateException("Alpha Vantage: $message") }

        val series = root.entries.firstOrNull { it.key.contains("Time Series", ignoreCase = true) }?.value
            ?.jsonObject ?: return emptyList()

        return series.entries.mapNotNull { (timeText, candleElement) ->
            val item = candleElement as? JsonObject ?: return@mapNotNull null
            val timestamp = parseTimestamp(timeText) ?: return@mapNotNull null
            val open = item["1. open"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val high = item["2. high"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val low = item["3. low"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val close = item["4. close"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val volume = item["5. volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            Candle(timestamp = timestamp, open = open, high = high, low = low, close = close, volume = volume)
        }
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }

    private fun buildRequest(symbol: String, timeframe: Timeframe): Request? {
        val normalized = symbol.uppercase().trim()
        val interval = when (timeframe) {
            Timeframe.M1 -> "1min"
            Timeframe.M5 -> "5min"
            Timeframe.M15 -> "15min"
            Timeframe.M30 -> "30min"
            Timeframe.H1 -> "60min"
            Timeframe.H4 -> null
            Timeframe.D1 -> null
            Timeframe.W1 -> null
            Timeframe.MN -> null
        }

        val maybeForex = parseForexPair(normalized)
        if (maybeForex != null) {
            val (base, quote) = maybeForex
            val function = when (timeframe) {
                Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1 ->
                    "FX_INTRADAY"
                Timeframe.H4, Timeframe.D1 -> "FX_DAILY"
                Timeframe.W1 -> "FX_WEEKLY"
                Timeframe.MN -> "FX_MONTHLY"
            }
            return Request(function = function, fromSymbol = base, toSymbol = quote, interval = interval)
        }

        val function = when (timeframe) {
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1 ->
                "TIME_SERIES_INTRADAY"
            Timeframe.H4, Timeframe.D1 -> "TIME_SERIES_DAILY"
            Timeframe.W1 -> "TIME_SERIES_WEEKLY"
            Timeframe.MN -> "TIME_SERIES_MONTHLY"
        }
        return Request(function = function, symbol = normalized, interval = interval)
    }

    private fun parseForexPair(symbol: String): Pair<String, String>? {
        // Normalize input so callers can pass either "EURUSD" or "eur/usd".
        val clean = symbol.uppercase().replace("/", "")
        if (clean.length != FOREX_SYMBOL_LENGTH || !clean.all { it in 'A'..'Z' }) return null
        return clean.substring(0, ISO_CURRENCY_CODE_LENGTH) to
            clean.substring(ISO_CURRENCY_CODE_LENGTH, FOREX_SYMBOL_LENGTH)
    }

    private fun parseTimestamp(value: String): Long? {
        val dateTime = runCatching {
            LocalDateTime.parse(value, DATE_TIME_FORMAT)
        }.getOrNull()
        if (dateTime != null) return dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()

        return runCatching {
            LocalDateTime.parse("$value 00:00:00", DATE_TIME_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private data class Request(
        val function: String,
        val symbol: String? = null,
        val fromSymbol: String? = null,
        val toSymbol: String? = null,
        val interval: String? = null,
    )

    private companion object {
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        const val ISO_CURRENCY_CODE_LENGTH = 3
        const val FOREX_SYMBOL_LENGTH = ISO_CURRENCY_CODE_LENGTH * 2
    }
}
