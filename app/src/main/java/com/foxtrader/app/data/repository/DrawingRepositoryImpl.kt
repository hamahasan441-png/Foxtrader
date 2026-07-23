package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.DrawingDao
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.DrawingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed drawing repository (single source of truth).
 * Drawings are scoped to (symbol, timeframe) and observed via Flow.
 */
@Singleton
class DrawingRepositoryImpl @Inject constructor(
    private val dao: DrawingDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DrawingRepository {

    override fun observe(symbol: String, timeframe: Timeframe): Flow<List<ChartDrawing>> =
        dao.observe(symbol, timeframe.name).map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(drawing: ChartDrawing, symbol: String, timeframe: Timeframe) =
        withContext(io) { dao.upsert(drawing.toEntity(symbol, timeframe)) }

    override suspend fun delete(id: String) = withContext(io) { dao.delete(id) }

    override suspend fun clearForChart(symbol: String, timeframe: Timeframe) =
        withContext(io) { dao.clearForChart(symbol, timeframe.name) }

    override suspend fun getAll(): List<ChartDrawing> = withContext(io) {
        dao.getAll().map { it.toDomain() }
    }
}
