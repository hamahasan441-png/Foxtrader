package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.data.remote.api.AllRatesTodayDataSource
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.SourcedCandles
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds backend-proxied providers to the existing market repository without
 * weakening its strict direct-provider behavior. All other providers are
 * delegated unchanged to [MarketRepositoryImpl].
 */
@Singleton
class RoutingMarketRepository @Inject constructor(
    private val delegate: MarketRepositoryImpl,
    private val dao: CandleDao,
    private val allRatesToday: AllRatesTodayDataSource,
    private val appPreferences: AppPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MarketRepository {

    override fun observeCandles(symbol: String, timeframe: Timeframe): Flow<List<Candle>> =
        delegate.observeCandles(symbol, timeframe)

    override fun observeSourcedCandles(symbol: String, timeframe: Timeframe): Flow<SourcedCandles> =
        delegate.observeSourcedCandles(symbol, timeframe)

    override suspend fun refreshCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): Result<Unit> {
        if (!isAllRatesToday()) return delegate.refreshCandles(symbol, timeframe, limit)
        return withContext(io) {
            runCatching {
                val requestedProvider = appPreferences.dataProvider.value
                check(requestedProvider == DataProvider.ALL_RATES_TODAY) {
                    "Market provider changed while AllRatesToday refresh was starting."
                }
                val candles = allRatesToday.fetchCandles(symbol, timeframe, limit)
                check(candles.isNotEmpty()) {
                    "AllRatesToday returned no market data for $symbol ${timeframe.label}."
                }
                check(appPreferences.dataProvider.value == requestedProvider) {
                    "Market provider changed before AllRatesToday data could be committed."
                }
                dao.replaceSeries(
                    symbol,
                    timeframe.label,
                    candles.map { it.toEntity(symbol, timeframe, CandleSource.LIVE) },
                )
                dao.prune(symbol, timeframe.label, appPreferences.maxCachedBars.value)
            }
        }
    }

    override suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle) =
        delegate.upsertCandle(symbol, timeframe, candle)

    override suspend fun getSourcedCandles(symbol: String, timeframe: Timeframe): SourcedCandles =
        delegate.getSourcedCandles(symbol, timeframe)

    override suspend fun loadOlderCandles(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): Result<SourcedCandles> {
        if (!isAllRatesToday()) {
            return delegate.loadOlderCandles(symbol, timeframe, beforeTimestamp, limit)
        }
        return withContext(io) {
            runCatching {
                val candles = allRatesToday.fetchCandles(
                    symbol = symbol,
                    timeframe = timeframe,
                    limit = limit,
                    before = beforeTimestamp,
                )
                SourcedCandles(candles = candles, source = CandleSource.LIVE)
            }
        }
    }

    override suspend fun testProviderConnection(): Result<Int> {
        if (!isAllRatesToday()) return delegate.testProviderConnection()
        return withContext(io) {
            runCatching {
                val count = allRatesToday.fetchCandles("EURUSD", Timeframe.H1, 3).size
                check(count > 0) { "AllRatesToday connected but returned no EURUSD data." }
                count
            }
        }
    }

    override suspend fun testBackendConnection(): Result<Int> = delegate.testBackendConnection()

    override suspend fun clearMarketDataCache() = delegate.clearMarketDataCache()

    private fun isAllRatesToday(): Boolean =
        appPreferences.dataProvider.value == DataProvider.ALL_RATES_TODAY
}
