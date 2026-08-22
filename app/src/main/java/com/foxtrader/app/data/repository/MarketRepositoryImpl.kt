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
import com.foxtrader.app.data.remote.api.PolygonDataSource
import com.foxtrader.app.data.remote.api.TwelveDataDataSource
import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
import com.foxtrader.app.data.remote.deriv.DerivMarketDataSource
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.ProviderNotImplementedException
import com.foxtrader.app.domain.model.SourcedCandles
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.repository.Mt4Repository
import com.foxtrader.app.domain.usecase.marketdata.MarketProviderRouter
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

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
    private val polygon: PolygonDataSource,
    private val dukascopy: DukascopyDataSource,
    private val deriv: DerivMarketDataSource,
    private val mt4Repository: Mt4Repository,
    private val appPreferences: AppPreferences,
    private val providerRouter: MarketProviderRouter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MarketRepository {

    /** Tracks series whose synthetic seed has already been removed this process. */
    private val liveSeriesSanitized = ConcurrentHashMap.newKeySet<String>()

    override fun observeCandles(symbol: String, timeframe: Timeframe): Flow<List<Candle>> =
        dao.observe(symbol, timeframe.label, appPreferences.maxCachedBars.value)
            .map { list -> list.map { it.toDomain() } }

    override fun observeSourcedCandles(
        symbol: String,
        timeframe: Timeframe,
    ): Flow<SourcedCandles> =
        dao.observe(symbol, timeframe.label, appPreferences.maxCachedBars.value).map { list ->
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
        val requestedProvider = appPreferences.dataProvider.value
        runCatching {
            val selectedProvider = providerRouter.historicalProviderFor(
                symbol = symbol,
                preferred = requestedProvider,
            )
            val alphaKey = appPreferences.getApiKey(DataProvider.ALPHA_VANTAGE).orEmpty()
            val polygonKey = appPreferences.getApiKey(DataProvider.POLYGON).orEmpty()

            // SAMPLE is an explicit user choice to run on synthetic data. Write
            // it tagged and return early — it must never masquerade as a
            // successful real fetch.
            if (selectedProvider == DataProvider.SAMPLE) {
                val seed = SampleData.generate(symbol, timeframe, limit)
                ensureProviderUnchanged(requestedProvider)
                dao.replaceSeries(
                    symbol,
                    timeframe.label,
                    seed.map { it.toEntity(symbol, timeframe, CandleSource.SYNTHETIC) },
                )
                liveSeriesSanitized.remove(seriesKey(symbol, timeframe))
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
                selectedProvider == DataProvider.BINANCE -> {
                    require(binance.isBinanceSymbol(symbol)) {
                        "Binance does not support $symbol as a recognized spot symbol. Choose the matching provider instead."
                    }
                    binance.fetchCandles(symbol, timeframe, limit).ifEmpty {
                        throw IllegalStateException("Binance returned no candle data for $symbol ${timeframe.label}.")
                    }
                }
                selectedProvider == DataProvider.BYBIT -> {
                    require(bybit.isBybitSymbol(symbol)) {
                        "Bybit does not support $symbol as a recognized spot symbol. Choose the matching provider instead."
                    }
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
                selectedProvider == DataProvider.POLYGON -> {
                    require(polygonKey.isNotBlank()) {
                        "Polygon.io API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    polygon.fetchCandles(symbol, timeframe, limit, polygonKey).ifEmpty {
                        throw IllegalStateException(
                            "Polygon.io returned no candle data for $symbol ${timeframe.label}. " +
                                "Check the ticker, market entitlement, API key validity, and rate limits."
                        )
                    }
                }
                selectedProvider == DataProvider.DUKASCOPY -> {
                    dukascopy.fetchCandles(symbol, timeframe, limit).ifEmpty {
                        throw IllegalStateException(
                            "Dukascopy returned no candle data for $symbol ${timeframe.label}. " +
                                "Check instrument availability and market trading hours."
                        )
                    }
                }
                selectedProvider == DataProvider.DERIV -> {
                    deriv.fetchCandles(symbol, timeframe, limit).ifEmpty {
                        throw IllegalStateException(
                            "Deriv returned no candle data for $symbol ${timeframe.label}. " +
                                "Check the Deriv symbol and timeframe availability."
                        )
                    }
                }
                selectedProvider == DataProvider.MT4 -> {
                    mt4Repository.getHistoricalCandles(symbol, timeframe, limit)
                        .getOrElse { throw it }
                        .ifEmpty {
                            throw IllegalStateException(
                                "MetaApi returned no candle data for $symbol ${timeframe.label}. " +
                                    "Connect your MT4 account first."
                            )
                        }
                }
                !selectedProvider.implemented -> throw ProviderNotImplementedException(
                    selectedProvider.displayName
                )
                else -> throw IllegalStateException(
                    "No strict market-data adapter is available for ${selectedProvider.displayName}."
                )
            }
            val sanitizedCandles = sanitizeProviderCandles(
                candles = candles,
                provider = selectedProvider,
                symbol = symbol,
                timeframe = timeframe,
                limit = limit,
            )

            // Commit only if the global provider preference is still the one
            // this request started under. A slow old-provider response must not
            // overwrite data after the user has switched source.
            ensureProviderUnchanged(requestedProvider)

            // Replace, do not merge. CandleEntity has no provider dimension, so
            // retaining timestamps not present in the new snapshot can silently
            // splice two brokers/exchanges into one analytical series.
            liveSeriesSanitized.add(seriesKey(symbol, timeframe))
            dao.replaceSeries(
                symbol,
                timeframe.label,
                sanitizedCandles.map { it.toEntity(symbol, timeframe, CandleSource.LIVE) },
            )
            dao.prune(symbol, timeframe.label, appPreferences.maxCachedBars.value)
        }.recoverCatching { error ->
            // Selecting an unimplemented provider is a configuration error, not
            // a transient network fault: surface it instead of papering over it
            // with synthetic bars the user did not ask for.
            if (error is ProviderNotImplementedException) throw error
            if (appPreferences.dataProvider.value != requestedProvider) throw error

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
                liveSeriesSanitized.remove(seriesKey(symbol, timeframe))
            } else {
                throw error
            }
        }.rethrowCancellation()
    }

    override suspend fun testProviderConnection(): Result<Int> = withContext(io) {
        runCatching {
            val provider = appPreferences.dataProvider.value
            val count = when (provider) {
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
                DataProvider.POLYGON -> {
                    val key = appPreferences.getApiKey(DataProvider.POLYGON).orEmpty()
                    require(key.isNotBlank()) { "Polygon.io API key is not set." }
                    polygon.fetchCandles(POLYGON_TEST_SYMBOL, Timeframe.D1, TEST_LIMIT, key).size
                }
                DataProvider.BINANCE ->
                    binance.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.DUKASCOPY ->
                    dukascopy.fetchCandles(FX_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.DERIV ->
                    deriv.fetchCandles(DERIV_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.BYBIT ->
                    bybit.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.OKX ->
                    okx.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.KUCOIN ->
                    kucoin.fetchCandles(CRYPTO_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT).size
                DataProvider.MT4 ->
                    mt4Repository.getHistoricalCandles(FX_TEST_SYMBOL, Timeframe.H1, TEST_LIMIT)
                        .getOrElse { throw it }.size
                else -> throw ProviderNotImplementedException(provider.displayName)
            }
            check(count > 0) { "${provider.displayName} connected but returned no candle data." }
            count
        }.rethrowCancellation()
    }

    override suspend fun testBackendConnection(): Result<Int> = withContext(io) {
        runCatching {
            val response = api.getCandles(FX_TEST_SYMBOL, Timeframe.H1.label, 1)
            check(response.toCandleSource() != CandleSource.SYNTHETIC) {
                "Backend is reachable but is serving simulated/sample market data, not a real provider."
            }
            check(response.candles.isNotEmpty()) { "Backend is reachable but returned no candle data." }
            response.candles.size
        }.rethrowCancellation()
    }

    override suspend fun clearMarketDataCache() = withContext(io) {
        dao.clearAll()
        liveSeriesSanitized.clear()
    }

    private fun com.foxtrader.app.data.remote.dto.CandlesResponse.toCandleSource(): CandleSource = when {
        source.equals("synthetic", ignoreCase = true) -> CandleSource.SYNTHETIC
        provider.equals("sample", ignoreCase = true) -> CandleSource.SYNTHETIC
        source.equals("live", ignoreCase = true) -> CandleSource.LIVE
        !provider.isNullOrBlank() && !provider.equals("unknown", ignoreCase = true) -> CandleSource.LIVE
        // Fail closed for legacy/ambiguous backend payloads: unverified provenance
        // must never become an executable "LIVE" series by default.
        else -> CandleSource.SYNTHETIC
    }

    /**
     * Live ticks come from a real feed, so they are LIVE by construction. If the
     * chart had previously been seeded with generated bars, remove that seed
     * before the first real tick is persisted; otherwise one real bar can sit
     * beside an entire synthetic walk and recreate the apparent duplicate price
     * track seen in the original screenshot.
     */
    override suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle) =
        withContext(io) {
            require(isValidCandle(candle)) { "Invalid live OHLCV candle for $symbol ${timeframe.label}." }
            val key = seriesKey(symbol, timeframe)
            if (!liveSeriesSanitized.contains(key)) {
                dao.clearSynthetic(symbol, timeframe.label)
                liveSeriesSanitized.add(key)
            }
            dao.upsert(candle.toEntity(symbol, timeframe, CandleSource.LIVE))
        }

    override suspend fun getSourcedCandles(
        symbol: String,
        timeframe: Timeframe,
    ): SourcedCandles = withContext(io) {
        val cached = dao.getAll(symbol, timeframe.label, appPreferences.maxCachedBars.value)
        if (cached.isNotEmpty()) {
            SourcedCandles(cached.map { it.toDomain() }, cached.provenance())
        } else {
            // Seed sample data if cache empty (scanner needs data to function).
            // Tagged SYNTHETIC so scanner rows built from it are badged, not
            // presented as a real opportunity.
            val seed = SampleData.generate(symbol, timeframe, SEED_BARS)
            dao.upsertAll(seed.map { it.toEntity(symbol, timeframe, CandleSource.SYNTHETIC) })
            liveSeriesSanitized.remove(seriesKey(symbol, timeframe))
            SourcedCandles(seed, CandleSource.SYNTHETIC)
        }
    }

    override suspend fun loadOlderCandles(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestamp: Long,
        limit: Int,
    ): Result<SourcedCandles> = withContext(io) {
        val requestedProvider = appPreferences.dataProvider.value
        runCatching {
            val selectedProvider = providerRouter.historicalProviderFor(
                symbol = symbol,
                preferred = requestedProvider,
            )
            val alphaKey = appPreferences.getApiKey(DataProvider.ALPHA_VANTAGE).orEmpty()
            val polygonKey = appPreferences.getApiKey(DataProvider.POLYGON).orEmpty()

            if (!selectedProvider.implemented) {
                throw ProviderNotImplementedException(selectedProvider.displayName)
            }
            val source = if (selectedProvider == DataProvider.SAMPLE) CandleSource.SYNTHETIC else CandleSource.LIVE

            val candles = when {
                source == CandleSource.SYNTHETIC ->
                    SampleData.generateEndingBefore(symbol, timeframe, limit, beforeTimestamp)
                selectedProvider == DataProvider.BINANCE -> {
                    require(binance.isBinanceSymbol(symbol)) {
                        "Binance does not support $symbol as a recognized spot symbol. Choose the matching provider instead."
                    }
                    binance.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
                }
                selectedProvider == DataProvider.ALPHA_VANTAGE -> {
                    require(alphaKey.isNotBlank()) {
                        "Alpha Vantage API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    alphaVantage.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit, alphaKey)
                }
                selectedProvider == DataProvider.BYBIT -> {
                    require(bybit.isBybitSymbol(symbol)) {
                        "Bybit does not support $symbol as a recognized spot symbol. Choose the matching provider instead."
                    }
                    bybit.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
                }
                selectedProvider == DataProvider.OKX ->
                    okx.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
                selectedProvider == DataProvider.KUCOIN ->
                    kucoin.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
                selectedProvider == DataProvider.TWELVE_DATA -> {
                    val tdKey = appPreferences.getApiKey(DataProvider.TWELVE_DATA).orEmpty()
                    require(tdKey.isNotBlank()) {
                        "Twelve Data API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    twelveData.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit, tdKey)
                }
                selectedProvider == DataProvider.POLYGON -> {
                    require(polygonKey.isNotBlank()) {
                        "Polygon.io API key is required. Navigate to Settings → Data Provider and enter your API key."
                    }
                    polygon.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit, polygonKey)
                }
                selectedProvider == DataProvider.DUKASCOPY ->
                    dukascopy.fetchCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
                selectedProvider == DataProvider.DERIV ->
                    deriv.fetchCandles(symbol, timeframe, limit, beforeTimestampMs = beforeTimestamp)
                selectedProvider == DataProvider.MT4 ->
                    mt4Repository.getHistoricalCandlesBefore(symbol, timeframe, beforeTimestamp, limit)
                        .getOrElse { throw it }
                else -> throw IllegalStateException(
                    "No strict older-history adapter is available for ${selectedProvider.displayName}."
                )
            }

            val sanitizedCandles = if (source == CandleSource.SYNTHETIC) {
                candles
                    .filter { it.timestamp < beforeTimestamp && isValidCandle(it) }
                    .distinctBy { it.timestamp }
                    .sortedBy { it.timestamp }
            } else {
                sanitizeProviderCandles(
                    candles = candles.filter { it.timestamp < beforeTimestamp },
                    provider = selectedProvider,
                    symbol = symbol,
                    timeframe = timeframe,
                    limit = limit,
                )
            }

            // A provider switch may complete while the older page is in flight.
            // Never allow that stale page to be merged into the new chart buffer.
            ensureProviderUnchanged(requestedProvider)
            SourcedCandles(
                candles = sanitizedCandles,
                source = source,
            )
        }.rethrowCancellation()
    }


    /**
     * Provider adapters are intentionally defensive, but the repository is the
     * final trust boundary before prices enter Room and every downstream signal
     * engine. Drop malformed rows, de-duplicate timestamps and fail closed if a
     * provider response contains no usable OHLCV at all.
     */
    private fun sanitizeProviderCandles(
        candles: List<Candle>,
        provider: DataProvider,
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        val safeLimit = limit.coerceAtLeast(1)
        val byTimestamp = linkedMapOf<Long, Candle>()
        candles.forEach { candle ->
            if (isValidCandle(candle)) byTimestamp[candle.timestamp] = candle
        }
        val sanitized = byTimestamp.values.sortedBy { it.timestamp }.takeLast(safeLimit)
        check(sanitized.isNotEmpty()) {
            "${provider.displayName} returned no valid OHLCV candles for $symbol ${timeframe.label}."
        }
        return sanitized
    }

    private fun isValidCandle(candle: Candle): Boolean =
        candle.timestamp > 0L &&
            candle.open.isFinite() && candle.high.isFinite() && candle.low.isFinite() &&
            candle.close.isFinite() && candle.volume.isFinite() &&
            candle.open > 0.0 && candle.high > 0.0 && candle.low > 0.0 && candle.close > 0.0 &&
            candle.volume >= 0.0 && candle.high >= candle.low &&
            candle.open in candle.low..candle.high && candle.close in candle.low..candle.high

    private fun ensureProviderUnchanged(expected: DataProvider) {
        check(appPreferences.dataProvider.value == expected) {
            "Market data provider changed while a refresh was in flight; stale response discarded."
        }
    }

    private fun seriesKey(symbol: String, timeframe: Timeframe): String =
        "${symbol.trim().uppercase()}|${timeframe.label}"

    private companion object {
        /** Bars generated when seeding an empty cache for the scanner. */
        const val SEED_BARS = 200

        // Canonical instruments for one-shot connection tests.
        const val FX_TEST_SYMBOL = "EURUSD"
        const val CRYPTO_TEST_SYMBOL = "BTCUSDT"
        const val POLYGON_TEST_SYMBOL = "AAPL"
        const val DERIV_TEST_SYMBOL = "frxEURUSD"
        const val TEST_LIMIT = 3
    }
}
