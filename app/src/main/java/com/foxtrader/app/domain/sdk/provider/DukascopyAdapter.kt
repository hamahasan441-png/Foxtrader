package com.foxtrader.app.domain.sdk.provider

import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dukascopy historical tick/candle data adapter.
 *
 * Dukascopy provides free, high-quality historical forex/CFD data
 * (tick-level). This adapter uses [DukascopyDataSource] to download,
 * decompress, decode, and aggregate real Dukascopy binary ticks into candles.
 */
@Singleton
class DukascopyAdapter @Inject constructor(
    private val dataSource: DukascopyDataSource,
) : DataProviderAdapter {
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
     */
    override suspend fun fetchHistory(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): List<Candle> = try {
        if (endTime != null) {
            dataSource.fetchCandlesBefore(
                symbol = symbol,
                timeframe = timeframe,
                beforeTimestamp = endTime,
                limit = limit,
            )
        } else {
            dataSource.fetchCandles(
                symbol = symbol,
                timeframe = timeframe,
                limit = limit,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
