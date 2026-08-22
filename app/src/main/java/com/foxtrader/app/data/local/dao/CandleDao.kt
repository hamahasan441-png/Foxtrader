package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.foxtrader.app.data.local.entity.CandleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CandleDao {

    /**
     * Observe only the newest [limit] bars, returned in ascending time order.
     *
     * The inner query selects the newest timestamps before the outer query
     * restores chart order. Without this bound, a missed prune would make every
     * forming-bar update re-emit the entire lifetime of a series.
     */
    @Query(
        "SELECT * FROM candles " +
            "WHERE symbol = :symbol AND timeframe = :timeframe " +
            "AND timestamp IN (" +
            "  SELECT timestamp FROM candles " +
            "  WHERE symbol = :symbol AND timeframe = :timeframe " +
            "  ORDER BY timestamp DESC LIMIT :limit" +
            ") " +
            "ORDER BY timestamp ASC"
    )
    fun observe(symbol: String, timeframe: String, limit: Int): Flow<List<CandleEntity>>

    /** Distinct series currently present in the bounded cache. */
    @Query("SELECT DISTINCT symbol, timeframe FROM candles")
    suspend fun seriesKeys(): List<CandleSeriesKey>

    /** Upsert a batch (REPLACE on conflict = idempotent refresh). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(candles: List<CandleEntity>)

    /**
     * Atomically replace one hot-cache series with a single provider snapshot.
     * This is deliberately replacement, not merge: provider changes, symbol
     * mappings and partial REST windows must never leave old-provider tails in
     * a series used by signals/backtests.
     */
    @Transaction
    suspend fun replaceSeries(symbol: String, timeframe: String, candles: List<CandleEntity>) {
        clear(symbol, timeframe)
        if (candles.isNotEmpty()) upsertAll(candles)
    }

    /** Upsert a single candle (real-time forming bar). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(candle: CandleEntity)

    @Query("DELETE FROM candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun clear(symbol: String, timeframe: String)

    /**
     * Clear the whole market-data hot cache when the global provider changes.
     * CandleEntity intentionally has no provider column, so keeping rows across
     * a provider switch could mix broker/exchange bars in one series.
     */
    @Query("DELETE FROM candles")
    suspend fun clearAll()

    /** Remove fabricated seed/history rows before committing a successful real fetch. */
    @Query(
        "DELETE FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "AND source = 'SYNTHETIC'"
    )
    suspend fun clearSynthetic(symbol: String, timeframe: String)

    @Query("SELECT COUNT(*) FROM candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun count(symbol: String, timeframe: String): Int

    /**
     * Retention: keep only the newest [keepCount] bars for a series.
     *
     * Without this the cache grows without bound — every live WebSocket tick
     * upserts a row, and a missed retention pass can make storage and downstream
     * analysis grow with the entire session.
     */
    @Query(
        "DELETE FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "AND timestamp NOT IN (" +
            "  SELECT timestamp FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "  ORDER BY timestamp DESC LIMIT :keepCount" +
            ")"
    )
    suspend fun prune(symbol: String, timeframe: String, keepCount: Int)

    /** Get the newest bounded window for a symbol/timeframe in chart order. */
    @Query(
        "SELECT * FROM candles " +
            "WHERE symbol = :symbol AND timeframe = :timeframe " +
            "AND timestamp IN (" +
            "  SELECT timestamp FROM candles " +
            "  WHERE symbol = :symbol AND timeframe = :timeframe " +
            "  ORDER BY timestamp DESC LIMIT :limit" +
            ") " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getAll(symbol: String, timeframe: String, limit: Int): List<CandleEntity>
}

/** Room projection used by the periodic retention worker. */
data class CandleSeriesKey(
    val symbol: String,
    val timeframe: String,
)
