package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.JournalDao
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.repository.JournalRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed trade-journal repository (single source of truth).
 * The UI observes [observeEntries]; writes go through [upsert]/[delete] and
 * the Room Flow pushes updates automatically.
 */
@Singleton
class JournalRepositoryImpl @Inject constructor(
    private val dao: JournalDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : JournalRepository {

    override fun observeEntries(): Flow<List<JournalEntry>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAllEntries(): List<JournalEntry> = withContext(io) {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun getModifiedSince(since: Long): List<JournalEntry> = withContext(io) {
        dao.getModifiedSince(since).map { it.toDomain() }
    }

    override suspend fun upsert(entry: JournalEntry) = withContext(io) {
        dao.upsert(entry.toEntity())
    }

    override suspend fun upsertAll(entries: List<JournalEntry>) = withContext(io) {
        dao.upsertAll(entries.map { it.toEntity() })
    }

    override suspend fun delete(id: String) = withContext(io) {
        dao.delete(id)
    }

    override suspend fun clear() = withContext(io) {
        dao.clear()
    }
}
