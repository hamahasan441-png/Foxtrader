package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.LitXSignalRecord
import kotlinx.coroutines.flow.Flow

/**
 * Persists and observes validated LIT X institutional signals so the trader has
 * a reviewable history. The implementation caps retention so the table cannot
 * grow without bound.
 */
interface LitXSignalRepository {
    /** Observe the most recent signals, newest first. */
    fun observeRecent(limit: Int = 100): Flow<List<LitXSignalRecord>>

    /** Save (upsert by id) a validated signal. Idempotent for the same bar. */
    suspend fun save(record: LitXSignalRecord)

    /** Atomically save a validated scanner batch and enforce retention once. */
    suspend fun saveAll(records: List<LitXSignalRecord>)

    /** Clear the entire signal history. */
    suspend fun clear()
}
