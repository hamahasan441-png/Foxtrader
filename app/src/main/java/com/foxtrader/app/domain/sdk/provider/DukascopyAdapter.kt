package com.foxtrader.app.domain.sdk.provider

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe

/**
 * Dukascopy historical tick/candle data adapter.
 *
 * Dukascopy provides free, high-quality historical forex/CFD data
 * (tick-level). This adapter fetches pre-aggregated candles.
 *
 * NOTE: This is a stub — the actual Dukascopy binary format parsing
 * (compressed hourly blocks) will be implemented when the data pipeline
 * is built. For now it demonstrates the DataProviderAdapter contract.
 */
class DukascopyAdapter : DataProviderAdapter {
    override val id = "dukascopy"
    override val displayName = "Dukascopy (Forex)"
    override val supportsLive = false
    override val supportedTimeframes = listOf(
        Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
        Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1, Timeframe.MN,
    )
    override val supportedSymbols = listOf(
        "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD",
        "EURJPY", "GBPJPY", "EURGBP", "XAUUSD", "XAGUSD",
    )

    override suspend fun fetchHistory(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): List<Candle> {
        // TODO: Implement Dukascopy binary block download + decompression + aggregation.
        // For now return empty — the repository falls back to SampleData or Binance.
        return emptyList()
    }
}
