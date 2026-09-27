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

    /**
     * When each entry was last written locally (epoch ms, by id). Sync uses it
     * as the last-write-wins key; the trade's own [JournalEntry.entryTime]
     * never changes when an entry is edited, so it cannot order edits.
     */
    suspend fun modificationStamps(): Map<String, Long> = emptyMap()

    /**
     * Store an entry received from another device, keeping that device's
     * modification stamp rather than stamping it as a fresh local edit.
     */
    suspend fun upsertFromSync(entry: JournalEntry, modifiedAt: Long) = upsert(entry)

    /** Delete an entry by id. */
    suspend fun delete(id: String)

    /** Remove all entries. */
    suspend fun clear()
}
