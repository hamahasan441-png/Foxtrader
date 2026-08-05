package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject

/**
 * Adapter that fetches historical candles from Bybit's public V5 REST API.
 *
 * This complements [com.foxtrader.app.data.remote.websocket.BybitWebSocket] so
 * a Bybit-selected chart loads an initial historical viewport before live
 * forming candles arrive.
 */
class BybitDataSource @Inject constructor(
    private val bybitApi: BybitApi,
) {
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
    ): List<Candle> {
        val response = bybitApi.getKlines(
            category = "spot",
            symbol = normalizeSymbol(symbol),
            interval = timeframeToInterval(timeframe),
            limit = limit.coerceIn(1, 1000),
        )
        if (response.retCode != 0) {
            throw IllegalStateException("Bybit: ${response.retMsg.ifBlank { "retCode ${response.retCode}" }}")
        }

        return response.result?.candles.orEmpty()
            .mapNotNull { it.toCandle() }
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }

    suspend fun fetchCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = 500,
    ): List<Candle> {
        val response = bybitApi.getKlines(
            category = "spot",
            symbol = normalizeSymbol(symbol),
            interval = timeframeToInterval(timeframe),
            limit = limit.coerceIn(1, 1000),
            end = (beforeTimestamp - 1L).coerceAtLeast(0L),
        )
        if (response.retCode != 0) {
            throw IllegalStateException("Bybit: ${response.retMsg.ifBlank { "retCode ${response.retCode}" }}")
        }

        return response.result?.candles.orEmpty()
            .mapNotNull { it.toCandle() }
            .filter { it.timestamp < beforeTimestamp }
            .sortedBy { it.timestamp }
            .takeLast(limit.coerceIn(1, 1000))
    }

    /**
     * Returns true if the symbol is likely supported by Bybit spot public data.
     */
    fun isBybitSymbol(symbol: String): Boolean {
        val upper = normalizeSymbol(symbol)
        return BYBIT_QUOTE_SUFFIXES.any { upper.endsWith(it) }
    }

    private fun List<String>.toCandle(): Candle? {
        if (size < 6) return null
        return Candle(
            timestamp = this[0].toLongOrNull() ?: return null,
            open = this[1].toDoubleOrNull() ?: return null,
            high = this[2].toDoubleOrNull() ?: return null,
            low = this[3].toDoubleOrNull() ?: return null,
            close = this[4].toDoubleOrNull() ?: return null,
            volume = this[5].toDoubleOrNull() ?: 0.0,
            source = com.foxtrader.app.domain.model.CandleSource.CACHED,
        )
    }

    private fun normalizeSymbol(symbol: String): String =
        symbol.uppercase().replace("/", "")

    private fun timeframeToInterval(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1"
        Timeframe.M5 -> "5"
        Timeframe.M15 -> "15"
        Timeframe.M30 -> "30"
        Timeframe.H1 -> "60"
        Timeframe.H4 -> "240"
        Timeframe.D1 -> "D"
        Timeframe.W1 -> "W"
        Timeframe.MN -> "M"
    }

    private companion object {
        val BYBIT_QUOTE_SUFFIXES = listOf("USDT", "USDC", "BTC", "ETH", "DAI", "EUR")
    }
}
