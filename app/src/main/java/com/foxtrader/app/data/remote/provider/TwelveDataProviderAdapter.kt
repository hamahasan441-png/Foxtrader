package com.foxtrader.app.data.remote.provider

import com.foxtrader.app.data.remote.api.TwelveDataDataSource
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.sdk.provider.DataProviderAdapter
import kotlinx.coroutines.CancellationException

/**
 * [DataProviderAdapter] backed by the Twelve Data REST API.
 *
 * Supports forex, stocks, indices, crypto, and ETFs on a single API key.
 * Delegates to [TwelveDataDataSource] for JSON parsing and symbol normalization.
 * Errors are caught and an empty list is returned per the adapter contract.
 */
class TwelveDataProviderAdapter(
    private val dataSource: TwelveDataDataSource,
    private val apiKeyProvider: () -> String,
) : DataProviderAdapter {

    override val id: String = "twelvedata"
    override val displayName: String = "Twelve Data (Multi-Asset)"
    override val supportsLive: Boolean = false
    override val supportedTimeframes: List<Timeframe> = Timeframe.entries
    override val supportedSymbols: List<String> = emptyList() // supports all symbols

    /**
     * Fetches historical candles from Twelve Data.
     *
     * [startTime] is intentionally not forwarded to the upstream API because Twelve Data's free
     * tier does not support a start-date filter; only [endTime] (via `end_date`) and [limit] are
     * honoured. Callers that need a start-bounded window should post-filter or paginate using
     * [endTime] + [limit].
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
                apiKey = apiKeyProvider(),
            )
        } else {
            dataSource.fetchCandles(
                symbol = symbol,
                timeframe = timeframe,
                limit = limit,
                apiKey = apiKeyProvider(),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
