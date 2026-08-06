package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject

/**
 * Adapter that fetches historical candles from KuCoin's public V1 REST API.
 *
 * KuCoin exposes keyless public market data; this data source loads an initial
 * historical viewport for a KuCoin-selected chart. KuCoin returns rows
 * newest-first with timestamps in SECONDS and an unusual O, C, H, L field
 * order, both normalized in the row mapping below.
 */
class KuCoinDataSource @Inject constructor(
    private val kuCoinApi: KuCoinApi,
) {
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 100,
    ): List<Candle> {
        val response = kuCoinApi.getCandles(
            symbol = normalizeSymbol(symbol),
            type = timeframeToType(timeframe),
        )
        if (response.code != "200000") {
            throw IllegalStateException("KuCoin: ${response.msg.ifBlank { "code ${response.code}" }}")
        }

        return response.data
            .mapNotNull { it.toCandle() }
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }

    suspend fun fetchCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = 100,
    ): List<Candle> {
        val response = kuCoinApi.getCandles(
            symbol = normalizeSymbol(symbol),
            type = timeframeToType(timeframe),
        )
        if (response.code != "200000") {
            throw IllegalStateException("KuCoin: ${response.msg.ifBlank { "code ${response.code}" }}")
        }

        return response.data
            .mapNotNull { it.toCandle() }
            .filter { it.timestamp < beforeTimestamp }
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }

    /**
     * Returns true if the symbol is likely supported by KuCoin spot public data.
     */
    fun isKuCoinSymbol(symbol: String): Boolean {
        val upper = normalizeSymbol(symbol)
        return KUCOIN_QUOTE_SUFFIXES.any { upper.endsWith("-$it") }
    }

    /**
     * KuCoin row layout: [time(sec), open, close, high, low, volume, turnover].
     * Timestamp is converted seconds -> millis, and OHLC is read from the
     * exchange's O, C, H, L index order (1, 2, 3, 4).
     */
    private fun List<String>.toCandle(): Candle? {
        if (size < 6) return null
        val seconds = this[0].toLongOrNull() ?: return null
        return Candle(
            timestamp = seconds * 1000L,
            open = this[1].toDoubleOrNull() ?: return null,
            high = this[3].toDoubleOrNull() ?: return null,
            low = this[4].toDoubleOrNull() ?: return null,
            close = this[2].toDoubleOrNull() ?: return null,
            volume = this[5].toDoubleOrNull() ?: 0.0,
        )
    }

    private fun normalizeSymbol(symbol: String): String {
        val s = symbol.uppercase().replace("/", "-")
        if (s.contains("-")) return s
        for (q in listOf("USDT", "USDC", "USD", "BTC", "ETH", "EUR", "DAI")) {
            if (s.endsWith(q) && s.length > q.length) return s.dropLast(q.length) + "-" + q
        }
        return s
    }

    private fun timeframeToType(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1min"
        Timeframe.M5 -> "5min"
        Timeframe.M15 -> "15min"
        Timeframe.M30 -> "30min"
        Timeframe.H1 -> "1hour"
        Timeframe.H4 -> "4hour"
        Timeframe.D1 -> "1day"
        Timeframe.W1 -> "1week"
        // KuCoin has no monthly candle type; fall back to weekly so an MN
        // selection still returns real data rather than erroring out.
        Timeframe.MN -> "1week"
    }

    private companion object {
        val KUCOIN_QUOTE_SUFFIXES = listOf("USDT", "USDC", "USD", "BTC", "ETH")
    }
}
