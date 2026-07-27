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
     * One-shot fetch of cached candles **with provenance**.
     *
     * There is deliberately no unsourced `getCandles` overload. One existed and
     * was removed: three separate features (the scan alert worker, the scanner,
     * and the heatmap built on it) each silently bypassed the Sprint 6
     * provenance contract simply by calling the older API, which still
     * compiled. Each produced confident trade narratives over generated bars.
     *
     * Callers that genuinely only need prices can use `.candles`; the point is
     * that discarding provenance must be an explicit, visible act rather than
     * the path of least resistance.
     */
    suspend fun getSourcedCandles(
        symbol: String,
        timeframe: Timeframe = Timeframe.H1,
    ): SourcedCandles

    /**
     * Fetch an older page of history strictly before [beforeTimestamp].
     *
     * This powers chart prepend paging without inflating the hot Room cache:
     * the returned page is merged in-memory by the chart layer rather than
     * persisted indefinitely. Callers receive provenance so a page sourced from
     * synthetic data can still be labelled and vetoed.
     */
    suspend fun loadOlderCandles(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int = 500,
    ): Result<SourcedCandles>
}
