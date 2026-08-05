package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.LitXSignalDao
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.data.mapper.toRecord
import com.foxtrader.app.domain.model.LitXSignalRecord
import com.foxtrader.app.domain.repository.LitXSignalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LitXSignalRepositoryImpl @Inject constructor(
    private val dao: LitXSignalDao,
) : LitXSignalRepository {

    override fun observeRecent(limit: Int): Flow<List<LitXSignalRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toRecord() } }

    override suspend fun save(record: LitXSignalRecord) = saveAll(listOf(record))

    override suspend fun saveAll(records: List<LitXSignalRecord>) {
        dao.upsertAllAndPrune(records.map { it.toEntity() }, MAX_SIGNALS)
    }

    override suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_SIGNALS = 200
    }
}
