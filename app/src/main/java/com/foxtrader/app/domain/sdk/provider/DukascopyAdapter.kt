package com.foxtrader.app.domain.sdk.provider

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ProviderNotImplementedException
import com.foxtrader.app.domain.model.Timeframe

/**
 * Dukascopy historical tick/candle data adapter.
 *
 * Dukascopy provides free, high-quality historical forex/CFD data
 * (tick-level). This adapter fetches pre-aggregated candles.
 *
 * NOTE: This is a stub — the actual Dukascopy binary format parsing
 * (compressed hourly blocks) will be implemented when the data pipeline
 * is built.
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

    /**
     * Fetch historical candle data from Dukascopy.
     *
     * This is an intentional stub. The Dukascopy binary format uses compressed
     * hourly LZMA blocks requiring a dedicated data pipeline. Rather than
     * silently returning an empty list (which used to cause the repository to
     * fall back to alternative providers), this adapter FAILS LOUDLY with
     * [ProviderNotImplementedException]. A caller must never be left believing
     * it is looking at real Dukascopy data when it is actually Binance/synthetic
     * data — if Dukascopy is required, an explicit user-visible fallback choice
     * must be made instead of a silent substitution.
     *
     * @throws ProviderNotImplementedException always — Dukascopy is not implemented yet.
     */
    override suspend fun fetchHistory(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): List<Candle> {
        throw ProviderNotImplementedException(displayName)
    }
}
