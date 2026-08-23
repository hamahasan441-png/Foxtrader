package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject

/**
 * Adapter for Binance public spot history and provider-native symbol discovery.
 */
class BinanceDataSource @Inject constructor(
    private val binanceApi: BinanceApi,
) {

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

    suspend fun fetchCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = 500,
    ): List<Candle> {
        val klines = binanceApi.getKlines(
            symbol = symbol.uppercase(),
            interval = timeframeToInterval(timeframe),
            limit = limit.coerceIn(1, 1000),
            endTime = (beforeTimestamp - 1L).coerceAtLeast(0L),
        )
        return klines.mapNotNull { it.toCandle() }
            .filter { it.timestamp < beforeTimestamp }
            .sortedBy { it.timestamp }
            .takeLast(limit.coerceIn(1, 1000))
    }

    /** Load Binance's actual spot instrument directory; no static symbol list. */
    suspend fun discoverSymbols(): List<ProviderMarketSymbol> =
        binanceApi.getExchangeInfo().symbols
            .asSequence()
            .mapNotNull { symbol ->
                val tickSize = symbol.filters
                    .firstOrNull { it.filterType == "PRICE_FILTER" }
                    ?.tickSize
                ProviderSymbolNormalization.cryptoSpot(
                    provider = DataProvider.BINANCE,
                    providerSymbol = symbol.symbol,
                    baseAsset = symbol.baseAsset,
                    quoteAsset = symbol.quoteAsset,
                    tickSizeText = tickSize,
                    isTrading = symbol.status.equals("TRADING", ignoreCase = true) && symbol.isSpotTradingAllowed,
                )
            }
            .distinctBy { it.providerSymbol }
            .sortedBy { it.providerSymbol }
            .toList()

    /**
     * Returns true if the given symbol is likely a Binance-supported crypto pair.
     * Discovery is authoritative; this heuristic remains only for cheap routing.
     */
    fun isBinanceSymbol(symbol: String): Boolean {
        val upper = symbol.uppercase()
        return BINANCE_QUOTE_SUFFIXES.any { upper.endsWith(it) }
    }

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
            null
        }
    }

    private companion object {
        val BINANCE_QUOTE_SUFFIXES = listOf("USDT", "BUSD", "BTC", "ETH", "BNB", "USDC", "TUSD", "FDUSD")
    }
}
