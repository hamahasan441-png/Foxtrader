package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foxtrader.app.data.local.entity.LitXSignalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LitXSignalDao {

    /** Observe the signal history, newest first — reactive source of truth. */
    @Query("SELECT * FROM litx_signals ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LitXSignalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signal: LitXSignalEntity)

    @Query("DELETE FROM litx_signals")
    suspend fun clear()

    /**
     * Retention: keep only the newest [keepCount] signals so the table can't
     * grow without bound (same cap pattern the alerts table uses).
     */
    @Query(
        "DELETE FROM litx_signals WHERE id NOT IN (" +
            "  SELECT id FROM litx_signals ORDER BY createdAt DESC LIMIT :keepCount" +
            ")"
    )
    suspend fun prune(keepCount: Int)
}
