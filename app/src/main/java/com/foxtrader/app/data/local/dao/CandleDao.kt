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
}
