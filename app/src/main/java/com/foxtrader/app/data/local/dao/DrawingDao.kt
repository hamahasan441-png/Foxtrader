package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foxtrader.app.data.local.entity.DrawingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DrawingDao {

    /** Observe drawings for a specific chart (symbol + timeframe). */
    @Query("SELECT * FROM chart_drawings WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY createdAt ASC")
    fun observe(symbol: String, timeframe: String): Flow<List<DrawingEntity>>

    /** All drawings (for sync). */
    @Query("SELECT * FROM chart_drawings ORDER BY createdAt ASC")
    suspend fun getAll(): List<DrawingEntity>

    /** Drawings modified since a timestamp (sync diff). */
    @Query("SELECT * FROM chart_drawings WHERE updatedAt > :since")
    suspend fun getModifiedSince(since: Long): List<DrawingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(drawing: DrawingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(drawings: List<DrawingEntity>)

    @Query("DELETE FROM chart_drawings WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chart_drawings WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun clearForChart(symbol: String, timeframe: String)
}
