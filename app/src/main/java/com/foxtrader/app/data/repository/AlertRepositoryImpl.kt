package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.AlertDao
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.domain.repository.AlertRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed alert history.
 *
 * The DB is the single source of truth for the inbox; the notification is only
 * a delivery mechanism on top of it.
 */
@Singleton
class AlertRepositoryImpl @Inject constructor(
    private val dao: AlertDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AlertRepository {

    override fun observeAlerts(): Flow<List<FoxAlert>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeUnacknowledgedCount(): Flow<Int> = dao.observeUnacknowledgedCount()

    override suspend fun record(alert: FoxAlert) = withContext(io) {
        dao.upsert(alert.toEntity())
        // The scan worker fires periodically and forever; cap the table so it
        // cannot grow without bound.
        dao.prune(MAX_RETAINED_ALERTS)
    }

    override suspend fun acknowledge(id: String) = withContext(io) { dao.acknowledge(id) }

    override suspend fun acknowledgeAll() = withContext(io) { dao.acknowledgeAll() }

    override suspend fun delete(id: String) = withContext(io) { dao.delete(id) }

    override suspend fun clear() = withContext(io) { dao.clear() }

    private companion object {
        /** Deep enough to review a week of signals, bounded enough to stay cheap. */
        const val MAX_RETAINED_ALERTS = 500
    }
}
