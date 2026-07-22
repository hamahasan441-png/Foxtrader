package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.data.remote.api.MarketApi
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first market repository.
 * Single source of truth = the local Room cache. The UI observes the DB;
 * [refreshCandles] fetches from the network and writes into the DB, which
 * automatically pushes updates to observers.
 *
 * If the network is unavailable, cached data still serves the UI, and
 * synthetic sample data seeds an empty cache so the app is always functional.
 */
@Singleton
class MarketRepositoryImpl @Inject constructor(
    private val dao: CandleDao,
    private val api: MarketApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MarketRepository {

    override fun observeCandles(symbol: String, timeframe: Timeframe): Flow<List<Candle>> =
        dao.observe(symbol, timeframe.label).map { list -> list.map { it.toDomain() } }

    override suspend fun refreshCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): Result<Unit> = withContext(io) {
        runCatching {
            val response = api.getCandles(symbol, timeframe.label, limit)
            val entities = response.candles.map { it.toDomain().toEntity(symbol, timeframe) }
            dao.upsertAll(entities)
        }.recoverCatching { error ->
            // Network failed — if cache is empty, seed synthetic data so the
            // chart is never blank. Real data replaces it on the next success.
            if (dao.count(symbol, timeframe.label) == 0) {
                val seed = SampleData.generate(symbol, timeframe, limit)
                dao.upsertAll(seed.map { it.toEntity(symbol, timeframe) })
            } else {
                throw error
            }
        }
    }

    override suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle) =
        withContext(io) { dao.upsert(candle.toEntity(symbol, timeframe)) }
}
