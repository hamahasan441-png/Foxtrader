package com.foxtrader.app.data.repository

import com.foxtrader.app.BuildConfig
import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.data.remote.api.AlphaVantageDataSource
import com.foxtrader.app.data.remote.api.BinanceDataSource
import com.foxtrader.app.data.remote.api.MarketApi
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
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
    private val binance: BinanceDataSource,
    private val alphaVantage: AlphaVantageDataSource,
    private val appPreferences: AppPreferences,
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
            val selectedProvider = appPreferences.dataProvider.value
            val alphaKey = appPreferences.getApiKey(DataProvider.ALPHA_VANTAGE).orEmpty()
                .ifBlank { BuildConfig.ALPHA_VANTAGE_API_KEY }

            val candles: List<Candle> = when {
                selectedProvider == DataProvider.ALPHA_VANTAGE -> {
                    require(alphaKey.isNotBlank()) {
                        "Alpha Vantage API key is required. Please add it in Settings."
                    }
                    alphaVantage.fetchCandles(symbol, timeframe, limit, alphaKey).ifEmpty {
                        throw IllegalStateException(
                            "Alpha Vantage returned no candle data for $symbol ${timeframe.label}. " +
                                "Check symbol/timeframe support, API key validity, and rate limits."
                        )
                    }
                }
                else -> fetchDefaultCandles(symbol, timeframe, limit)
            }
            val entities = candles.map { it.toEntity(symbol, timeframe) }
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

    private suspend fun fetchDefaultCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        return if (binance.isBinanceSymbol(symbol)) {
            // Route crypto pairs to Binance public REST API (no key needed).
            binance.fetchCandles(symbol, timeframe, limit)
        } else {
            // Route non-crypto (Forex, Stocks, etc.) to the FoxTrader backend.
            val response = api.getCandles(symbol, timeframe.label, limit)
            response.candles.map { it.toDomain() }
        }
    }

    override suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle) =
        withContext(io) { dao.upsert(candle.toEntity(symbol, timeframe)) }

    override suspend fun getCandles(symbol: String, timeframe: Timeframe): List<Candle> =
        withContext(io) {
            val cached = dao.getAll(symbol, timeframe.label)
            if (cached.isNotEmpty()) {
                cached.map { it.toDomain() }
            } else {
                // Seed sample data if cache empty (scanner needs data to function)
                val seed = SampleData.generate(symbol, timeframe, 200)
                dao.upsertAll(seed.map { it.toEntity(symbol, timeframe) })
                seed
            }
        }
}
