package com.foxtrader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foxtrader.app.data.local.entity.JournalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    /** Observe all journal entries, newest first — reactive source of truth. */
    @Query("SELECT * FROM journal_entries ORDER BY entryTime DESC")
    fun observeAll(): Flow<List<JournalEntity>>

    /** One-shot snapshot of all entries (for sync diffing). */
    @Query("SELECT * FROM journal_entries ORDER BY entryTime DESC")
    suspend fun getAll(): List<JournalEntity>

    /** Entries modified since a timestamp (for sync upload diff). */
    @Query("SELECT * FROM journal_entries WHERE updatedAt > :since")
    suspend fun getModifiedSince(since: Long): List<JournalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: JournalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<JournalEntity>)

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM journal_entries")
    suspend fun clear()
}
