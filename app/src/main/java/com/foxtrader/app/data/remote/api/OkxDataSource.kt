package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject

/**
 * Adapter that fetches historical candles from OKX's public V5 REST API.
 *
 * OKX exposes keyless public market data; this data source loads an initial
 * historical viewport for an OKX-selected chart. OKX returns rows newest-first,
 * so candles are re-sorted ascending by timestamp to match the rest of the app.
 */
class OkxDataSource @Inject constructor(
    private val okxApi: OkxApi,
) {
    suspend fun fetchCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 100,
    ): List<Candle> {
        val response = okxApi.getCandles(
            instId = normalizeSymbol(symbol),
            bar = timeframeToBar(timeframe),
            limit = limit.coerceIn(1, 300),
        )
        if (response.code != "0") {
            throw IllegalStateException("OKX: ${response.msg.ifBlank { "code ${response.code}" }}")
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
        val response = okxApi.getCandles(
            instId = normalizeSymbol(symbol),
            bar = timeframeToBar(timeframe),
            limit = limit.coerceIn(1, 300),
            after = beforeTimestamp.coerceAtLeast(0L).toString(),
        )
        if (response.code != "0") {
            throw IllegalStateException("OKX: ${response.msg.ifBlank { "code ${response.code}" }}")
        }

        return response.data
            .mapNotNull { it.toCandle() }
            .filter { it.timestamp < beforeTimestamp }
            .sortedBy { it.timestamp }
            .takeLast(limit.coerceIn(1, 300))
    }

    /** Load OKX's authoritative public spot instrument directory. */
    suspend fun discoverSymbols(): List<ProviderMarketSymbol> {
        val response = okxApi.getInstruments(instType = "SPOT")
        if (response.code != "0") {
            throw IllegalStateException("OKX: ${response.msg.ifBlank { "code ${response.code}" }}")
        }
        return response.data
            .asSequence()
            .mapNotNull { instrument ->
                ProviderSymbolNormalization.cryptoSpot(
                    provider = DataProvider.OKX,
                    providerSymbol = instrument.instId,
                    baseAsset = instrument.baseCcy,
                    quoteAsset = instrument.quoteCcy,
                    tickSizeText = instrument.tickSz,
                    category = instrument.instType,
                    isTrading = instrument.state.equals("live", ignoreCase = true),
                )
            }
            .distinctBy { it.providerSymbol }
            .sortedBy { it.providerSymbol }
            .toList()
    }

    /**
     * Returns true if the symbol is likely supported by OKX spot public data.
     */
    fun isOkxSymbol(symbol: String): Boolean {
        val upper = normalizeSymbol(symbol)
        return OKX_QUOTE_SUFFIXES.any { upper.endsWith("-$it") }
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
        )
    }

    // Delegated to the shared splitter: the previous inline copy matched USD
    // before BUSD/TUSD/FDUSD and mis-split those pairs. See
    // [CryptoSymbolNormalizer] for the full rationale.
    private fun normalizeSymbol(symbol: String): String =
        CryptoSymbolNormalizer.toDashPair(symbol)

    private fun timeframeToBar(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1m"
        Timeframe.M5 -> "5m"
        Timeframe.M15 -> "15m"
        Timeframe.M30 -> "30m"
        Timeframe.H1 -> "1H"
        Timeframe.H4 -> "4H"
        Timeframe.D1 -> "1D"
        Timeframe.W1 -> "1W"
        Timeframe.MN -> "1M"
    }

    private companion object {
        val OKX_QUOTE_SUFFIXES = listOf("USDT", "USDC", "USD", "BTC", "ETH")
    }
}
