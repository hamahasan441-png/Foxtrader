package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foxtrader.app.data.local.entity.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    /** Observe the inbox, newest first — reactive source of truth. */
    @Query("SELECT * FROM alerts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    /** Unread count for the navigation badge. */
    @Query("SELECT COUNT(*) FROM alerts WHERE acknowledged = 0")
    fun observeUnacknowledgedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alert: AlertEntity)

    @Query("UPDATE alerts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: String)

    @Query("UPDATE alerts SET acknowledged = 1")
    suspend fun acknowledgeAll()

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM alerts")
    suspend fun clear()

    /**
     * Retention: keep only the newest [keepCount] alerts.
     *
     * Uses a cutoff-timestamp approach instead of NOT IN for better performance
     * on large tables.
     */
    @Query(
        "DELETE FROM alerts WHERE timestamp < (" +
            "  SELECT timestamp FROM alerts ORDER BY timestamp DESC LIMIT 1 OFFSET :keepCount" +
            ")"
    )
    suspend fun prune(keepCount: Int)
}
