package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for market data access.
 * The domain layer OWNS this interface; the data layer implements it.
 * (Dependency Inversion — domain depends on abstractions, not Room/Retrofit.)
 */
interface MarketRepository {

    /**
     * Observe candles for a symbol/timeframe. Emits cached data immediately,
     * then updates as fresh data arrives (single source of truth = local DB).
     */
    fun observeCandles(symbol: String, timeframe: Timeframe): Flow<List<Candle>>

    /**
     * Trigger a refresh from the remote source into the local cache.
     * Returns Result to surface network/parse errors to the caller.
     */
    suspend fun refreshCandles(symbol: String, timeframe: Timeframe, limit: Int = 500): Result<Unit>

    /** Append or update the latest (forming) candle in the cache. */
    suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle)
}
