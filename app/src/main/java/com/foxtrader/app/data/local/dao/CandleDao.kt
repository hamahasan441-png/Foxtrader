package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foxtrader.app.data.local.entity.CandleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CandleDao {

    /** Observe candles ascending by time — reactive single source of truth. */
    @Query(
        "SELECT * FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "ORDER BY timestamp ASC"
    )
    fun observe(symbol: String, timeframe: String): Flow<List<CandleEntity>>

    /** Upsert a batch (REPLACE on conflict = idempotent refresh). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(candles: List<CandleEntity>)

    /** Upsert a single candle (real-time forming bar). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(candle: CandleEntity)

    @Query("DELETE FROM candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun clear(symbol: String, timeframe: String)

    @Query("SELECT COUNT(*) FROM candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun count(symbol: String, timeframe: String): Int

    /**
     * Retention: keep only the newest [keepCount] bars for a series.
     *
     * Without this the cache grows without bound — every live WebSocket tick
     * upserts a row, and `observe()` re-emits the whole series on each change,
     * so an unbounded table is both a storage leak and a CPU cliff.
     */
    @Query(
        "DELETE FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "AND timestamp NOT IN (" +
            "  SELECT timestamp FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "  ORDER BY timestamp DESC LIMIT :keepCount" +
            ")"
    )
    suspend fun prune(symbol: String, timeframe: String, keepCount: Int)

    /** Get all candles for a symbol/timeframe (non-reactive, for one-shot queries). */
    @Query(
        "SELECT * FROM candles WHERE symbol = :symbol AND timeframe = :timeframe " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getAll(symbol: String, timeframe: String): List<CandleEntity>
}
