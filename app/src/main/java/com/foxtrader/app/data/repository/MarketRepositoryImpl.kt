package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.mapper.provenance
import com.foxtrader.app.data.mapper.toDomain
import com.foxtrader.app.data.mapper.toEntity
import com.foxtrader.app.data.remote.api.AlphaVantageDataSource
import com.foxtrader.app.data.remote.api.BinanceDataSource
import com.foxtrader.app.data.remote.api.BybitDataSource
import com.foxtrader.app.data.remote.api.KuCoinDataSource
import com.foxtrader.app.data.remote.api.MarketApi
import com.foxtrader.app.data.remote.api.OkxDataSource
import com.foxtrader.app.data.remote.api.TwelveDataDataSource
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.ProviderNotImplementedException
import com.foxtrader.app.domain.model.SourcedCandles
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
    private val bybit: BybitDataSource,
    private val okx: OkxDataSource,
    private val kucoin: KuCoinDataSource,
    private val alphaVantage: AlphaVantageDataSource,
    private val twelveData: TwelveDataDataSource,
    private val appPreferences: AppPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MarketRepository {

    override fun observeCandles(symbol: String, timeframe: Timeframe): Flow<List<Candle>> =
        dao.observe(symbol, timeframe.label).map { list -> list.map { it.toDomain() } }

    override fun observeSourcedCandles(
        symbol: String,
        timeframe: Timeframe,
    ): Flow<SourcedCandles> =
        dao.observe(symbol, timeframe.label).map { list ->
            SourcedCandles(
                candles = list.map { it.toDomain() },
                source = list.provenance(),
            )
        }

    override suspend fun refreshCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): Result<Unit> = withContext(io) {
        runCatching {
            val selectedProvider = appPreferences.dataProvider.value
            val alphaKey = appPreferences.getApiKey(DataProvider.ALPHA_VANTAGE).orEmpty()

            // SAMPLE is an explicit user choice to run on synthetic data. Write
            // it tagged and return early — it must never masquerade as a
            // successful real fetch.
            if (selectedProvider == DataProvider.SAMPLE) {
                val seed = SampleData.generate(symbol, timeframe, limit)
                dao.upsertAll(seed.map { it.toEntity(symbol, timeframe, CandleSource.SYNTHETIC) })
                dao.prune(symbol, timeframe.label, appPreferences.maxCachedBars.value)
                return@runCatching
            }

            val candles: List<Candle> = when {
                selectedProvider == DataProvider.ALPHA_VANTAGE -> {
                    require(alphaKey.isNotBlank()) {
                        "Alpha Vantage API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    alphaVantage.fetchCandles(symbol, timeframe, limit, alphaKey).ifEmpty {
                        throw IllegalStateException(
                            "Alpha Vantage returned no candle data for $symbol ${timeframe.label}. " +
                                "Check supported symbols/timeframes in Alpha Vantage docs, API key validity, and rate limits."
                        )
                    }
                }
                selectedProvider == DataProvider.BYBIT && bybit.isBybitSymbol(symbol) -> {
                    bybit.fetchCandles(symbol, timeframe, limit).ifEmpty {
                        throw IllegalStateException(
                            "Bybit returned no candle data for $symbol ${timeframe.label}. " +
                                "Check that the spot symbol is supported by Bybit."
                        )
                    }
                }
                selectedProvider == DataProvider.OKX -> okx.fetchCandles(symbol, timeframe, limit).ifEmpty {
                    throw IllegalStateException("OKX returned no candle data for $symbol ${timeframe.label}.")
                }
                selectedProvider == DataProvider.KUCOIN -> kucoin.fetchCandles(symbol, timeframe, limit).ifEmpty {
                    throw IllegalStateException("KuCoin returned no candle data for $symbol ${timeframe.label}.")
                }
                selectedProvider == DataProvider.TWELVE_DATA -> {
                    val tdKey = appPreferences.getApiKey(DataProvider.TWELVE_DATA).orEmpty()
                    require(tdKey.isNotBlank()) {
                        "Twelve Data API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    twelveData.fetchCandles(symbol, timeframe, limit, tdKey).ifEmpty {
                        throw IllegalStateException(
                            "Twelve Data returned no candle data for $symbol ${timeframe.label}. " +
                                "Check supported symbols/timeframes and API key validity."
                        )
                    }
                }
                !selectedProvider.implemented -> throw ProviderNotImplementedException(
                    selectedProvider.displayName
                )
                else -> fetchDefaultCandles(symbol, timeframe, limit)
            }
            // A successful provider fetch is real data.
            dao.upsertAll(candles.map { it.toEntity(symbol, timeframe, CandleSource.LIVE) })
            dao.prune(symbol, timeframe.label, appPreferences.maxCachedBars.value)
        }.recoverCatching { error ->
            // Selecting an unimplemented provider is a configuration error, not
            // a transient network fault: surface it instead of papering over it
            // with synthetic bars the user did not ask for.
            if (error is ProviderNotImplementedException) throw error

            // Network failed — if cache is empty, seed synthetic data so the
            // chart is never blank. Real data replaces it on the next success.
            //
            // The seed is tagged SYNTHETIC so the UI can label it and the
            // decision engine can veto on it. Silently mixing fabricated bars
            // into the same table as real ones is the single most dangerous
            // thing this app could do.
            if (dao.count(symbol, timeframe.label) == 0) {
                val seed = SampleData.generate(symbol, timeframe, limit)
                dao.upsertAll(seed.map { it.toEntity(symbol, timeframe, CandleSource.SYNTHETIC) })
            } else {
                throw error
            }
        }
    }

    override suspend fun testProviderConnection(): Result<Int> = withContext(io) {
        runCatching {
            when (val provider = appPreferences.dataProvider.value) {
                DataProvider.SAMPLE ->
                    SampleData.generate(FX_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.ALPHA_VANTAGE -> {
                    val key = appPreferences.getApiKey(DataProvider.ALPHA_VANTAGE).orEmpty()
                    require(key.isNotBlank()) { "Alpha Vantage API key is not set." }
                    alphaVantage.fetchCandles(FX_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT, key).size
                }
                DataProvider.TWELVE_DATA -> {
                    val key = appPreferences.getApiKey(DataProvider.TWELVE_DATA).orEmpty()
                    require(key.isNotBlank()) { "Twelve Data API key is not set." }
                    twelveData.fetchCandles(FX_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT, key).size
                }
                DataProvider.BINANCE ->
                    binance.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.BYBIT ->
                    bybit.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.OKX ->
                    okx.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.KUCOIN ->
                    kucoin.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                else -> throw ProviderNotImplementedException(provider.displayName)
            }
        }
    }

    override suspend fun testBackendConnection(): Result<Int> = withContext(io) {
        runCatching { api.getCandles(FX_TEST_SYMBOL, Timeframe.H1.label, 1).candles.size }
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

    private suspend fun fetchDefaultCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): List<Candle> {
        return if (binance.isBinanceSymbol(symbol)) {
            binance.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
        } else {
            val response = api.getCandles(
                symbol = symbol,
                timeframe = timeframe.label,
                limit = limit,
                before = beforeTimestamp,
            )
            response.candles.map { it.toDomain() }
                .filter { it.timestamp < beforeTimestamp }
                .sortedBy { it.timestamp }
                .takeLast(limit)
        }
    }

    /** Live ticks come from a real feed, so they are LIVE by construction. */
    override suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle) =
        withContext(io) { dao.upsert(candle.toEntity(symbol, timeframe, CandleSource.LIVE)) }

    override suspend fun getSourcedCandles(
        symbol: String,
        timeframe: Timeframe,
    ): SourcedCandles = withContext(io) {
        val cached = dao.getAll(symbol, timeframe.label)
        if (cached.isNotEmpty()) {
            SourcedCandles(cached.map { it.toDomain() }, cached.provenance())
        } else {
            // Seed sample data if cache empty (scanner needs data to function).
            // Tagged SYNTHETIC so scanner rows built from it are badged, not
            // presented as a real opportunity.
            val seed = SampleData.generate(symbol, timeframe, SEED_BARS)
            dao.upsertAll(seed.map { it.toEntity(symbol, timeframe, CandleSource.SYNTHETIC) })
            SourcedCandles(seed, CandleSource.SYNTHETIC)
        }
    }

    override suspend fun loadOlderCandles(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): Result<SourcedCandles> = withContext(io) {
        runCatching {
            val selectedProvider = appPreferences.dataProvider.value
            val alphaKey = appPreferences.getApiKey(DataProvider.ALPHA_VANTAGE).orEmpty()

            val source = when {
                selectedProvider == DataProvider.SAMPLE || !selectedProvider.implemented -> CandleSource.SYNTHETIC
                else -> CandleSource.LIVE
            }

            val candles = when {
                source == CandleSource.SYNTHETIC ->
                    SampleData.generateEndingBefore(symbol, timeframe, limit, beforeTimestamp)
                selectedProvider == DataProvider.ALPHA_VANTAGE -> {
                    require(alphaKey.isNotBlank()) {
                        "Alpha Vantage API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    alphaVantage.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit, alphaKey)
                }
                selectedProvider == DataProvider.BYBIT && bybit.isBybitSymbol(symbol) ->
                    bybit.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
                selectedProvider == DataProvider.TWELVE_DATA -> {
                    val tdKey = appPreferences.getApiKey(DataProvider.TWELVE_DATA).orEmpty()
                    require(tdKey.isNotBlank()) {
                        "Twelve Data API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    twelveData.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit, tdKey)
                }
                else -> fetchDefaultCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
            }

            SourcedCandles(
                candles = candles
                    .filter { it.timestamp < beforeTimestamp }
                    .distinctBy { it.timestamp }
                    .sortedBy { it.timestamp },
                source = source,
            )
        }
    }

    private companion object {
        /** Bars generated when seeding an empty cache for the scanner. */
        const val SEED_BARS = 200

        // Canonical instruments for one-shot connection tests.
        const val FX_TEST_SYMBOL = "EURUSD"
        const val CRYPTO_TEST_SYMBOL = "BTCUSDT"
        const val TEST_LIMIT = 3
    }
}
