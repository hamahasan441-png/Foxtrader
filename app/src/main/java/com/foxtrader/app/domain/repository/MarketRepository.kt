package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.SourcedCandles
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
     * Observe candles together with their provenance.
     *
     * Callers that render prices or authorise trade decisions MUST use this
     * rather than [observeCandles], so synthetic seed data can be labelled in
     * the UI and vetoed by the decision engine. See
     * [com.foxtrader.app.domain.model.CandleSource].
     */
    fun observeSourcedCandles(symbol: String, timeframe: Timeframe): Flow<SourcedCandles>

    /**
     * Trigger a refresh from the remote source into the local cache.
     * Returns Result to surface network/parse errors to the caller.
     */
    suspend fun refreshCandles(symbol: String, timeframe: Timeframe, limit: Int = 500): Result<Unit>

    /** Append or update the latest (forming) candle in the cache. */
    suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle)

    /**
     * Get cached candles for a symbol (default timeframe H1).
     * Convenience for scanner/screener which doesn't need reactive observation.
     */
    suspend fun getCandles(
        symbol: String,
        timeframe: Timeframe = Timeframe.H1,
    ): List<Candle>

    /**
     * One-shot fetch of cached candles with provenance, for the scanner and
     * any other non-reactive consumer that must not present synthetic data as
     * real.
     */
    suspend fun getSourcedCandles(
        symbol: String,
        timeframe: Timeframe = Timeframe.H1,
    ): SourcedCandles
}
