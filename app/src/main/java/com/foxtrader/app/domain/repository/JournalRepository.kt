package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.JournalEntry
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for trade-journal persistence.
 * The local Room database is the single source of truth; entries are
 * user-authored and syncable to the cloud (H3).
 */
interface JournalRepository {

    /** Observe all entries (newest first) reactively. */
    fun observeEntries(): Flow<List<JournalEntry>>

    /** One-shot snapshot of all entries. */
    suspend fun getAllEntries(): List<JournalEntry>

    /** Entries modified since [since] (epoch ms) — used for sync upload diff. */
    suspend fun getModifiedSince(since: Long): List<JournalEntry>

    /** Insert or update an entry. */
    suspend fun upsert(entry: JournalEntry)

    /** Insert or update a batch (e.g. from a sync pull). */
    suspend fun upsertAll(entries: List<JournalEntry>)

    /** Delete an entry by id. */
    suspend fun delete(id: String)

    /** Remove all entries. */
    suspend fun clear()
}
