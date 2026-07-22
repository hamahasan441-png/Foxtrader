package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject

/**
 * Adapter that fetches candle history from Binance's public REST API and
 * converts the raw kline arrays into domain [Candle] objects.
 *
 * Handles:
 * - FoxTrader symbol format ("BTCUSDT") → Binance format ("BTCUSDT" uppercase)
 * - FoxTrader [Timeframe] → Binance interval string
 * - Kline array parsing (positional — see [BinanceApi] docs)
 *
 * This is NOT a domain-level interface — it's a data-layer detail, consumed
 * by [com.foxtrader.app.data.repository.MarketRepositoryImpl].
 */
class BinanceDataSource @Inject constructor(
    private val binanceApi: BinanceApi,
) {

    /**
     * Fetch historical candles from Binance.
     *
     * @param symbol FoxTrader symbol (e.g. "BTCUSDT", "ETHUSDT").
     * @param timeframe Desired candle timeframe.
     * @param limit Number of candles (max 1000).
     * @return Ordered list of [Candle]s (ascending by timestamp).
     * @throws Exception on network failure or invalid response.
     */
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
    ): List<Candle> {
        val klines = binanceApi.getKlines(
            symbol = symbol.uppercase(),
            interval = timeframeToInterval(timeframe),
            limit = limit.coerceIn(1, 1000),
        )
        return klines.mapNotNull { it.toCandle() }
    }

    /**
     * Returns true if the given symbol is likely a Binance-supported crypto pair.
     * Simple heuristic: ends with USDT, BUSD, BTC, ETH, or BNB.
     */
    fun isBinanceSymbol(symbol: String): Boolean {
        val upper = symbol.uppercase()
        return BINANCE_QUOTE_SUFFIXES.any { upper.endsWith(it) }
    }

    // ========================================================================
    // MAPPING
    // ========================================================================

    private fun timeframeToInterval(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1m"
        Timeframe.M5 -> "5m"
        Timeframe.M15 -> "15m"
        Timeframe.M30 -> "30m"
        Timeframe.H1 -> "1h"
        Timeframe.H4 -> "4h"
        Timeframe.D1 -> "1d"
        Timeframe.W1 -> "1w"
        Timeframe.MN -> "1M"
    }

    /**
     * Parse a single Binance kline array into a [Candle].
     *
     * Kline format (positional indices):
     *   0: openTime (Long ms)
     *   1: open (String)
     *   2: high (String)
     *   3: low (String)
     *   4: close (String)
     *   5: volume (String)
     *   6-11: closeTime, quoteAssetVol, trades, takerBuyBase, takerBuyQuote, ignore
     */
    private fun JsonArray.toCandle(): Candle? {
        if (size < 6) return null
        return try {
            Candle(
                timestamp = this[0].jsonPrimitive.long,
                open = this[1].jsonPrimitive.content.toDouble(),
                high = this[2].jsonPrimitive.content.toDouble(),
                low = this[3].jsonPrimitive.content.toDouble(),
                close = this[4].jsonPrimitive.content.toDouble(),
                volume = this[5].jsonPrimitive.content.toDouble(),
            )
        } catch (_: Exception) {
            null // Skip malformed entries; never crash the fetch.
        }
    }

    private companion object {
        val BINANCE_QUOTE_SUFFIXES = listOf("USDT", "BUSD", "BTC", "ETH", "BNB", "USDC", "TUSD", "FDUSD")
    }
}
