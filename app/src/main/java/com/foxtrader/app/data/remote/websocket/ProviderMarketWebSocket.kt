package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.marketdata.MarketProviderRouter
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Symbol-aware live market-data router.
 *
 * Routing identity and provider transport symbols are deliberately separated.
 * FoxTrader may normalize `BTC/USDT` and `BTCUSDT` to the same internal key, but
 * broker-native identifiers such as Deriv `R_100` or MT4 symbols containing a
 * suffix/prefix must be sent to the provider exactly as the user selected them.
 */
@Singleton
class ProviderMarketWebSocket @Inject constructor(
    private val appPreferences: AppPreferences,
    private val providerRouter: MarketProviderRouter,
    private val binanceWebSocket: BinanceWebSocket,
    private val bybitWebSocket: BybitWebSocket,
    private val polygonWebSocket: PolygonWebSocket,
    private val mt4MarketWebSocket: Mt4MarketWebSocket,
    private val dukascopyWebSocket: DukascopyPollingWebSocket,
    private val derivMarketWebSocket: DerivMarketWebSocket,
    @IoDispatcher io: CoroutineDispatcher,
) : MarketWebSocket {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()

    /** Internal normalized route key -> concrete transport + exact requested symbol. */
    private data class RouteBinding(
        val socket: MarketWebSocket,
        val requestedSymbol: String,
        val provider: DataProvider,
    )

    private val routes = mutableMapOf<Pair<String, Timeframe>, RouteBinding>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 128)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    init {
        allSockets().forEach(::forward)
        observeProviderChanges()
    }

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        val requested = symbol.trim()
        require(requested.isNotBlank()) { "Market symbol is required" }
        val key = providerRouter.canonicalSymbol(requested) to timeframe
        mutex.withLock { routePairLocked(key, requested, appPreferences.dataProvider.value) }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        val key = providerRouter.canonicalSymbol(symbol) to timeframe
        mutex.withLock {
            val binding = routes.remove(key) ?: return
            binding.socket.unsubscribe(binding.requestedSymbol, key.second)
            recomputeConnectionStateLocked()
        }
    }

    override suspend fun disconnectAll() {
        mutex.withLock {
            routes.clear()
            allSockets().forEach { socket -> socket.disconnectAll() }
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun forward(socket: MarketWebSocket) {
        scope.launch {
            socket.ticks.collect { tick ->
                val key = providerRouter.canonicalSymbol(tick.symbol) to tick.timeframe
                val binding = mutex.withLock { routes[key]?.takeIf { it.socket === socket } }
                if (binding != null) {
                    // Preserve the exact chart symbol. ChartDataController compares
                    // this value against its selected symbol before persisting.
                    _ticks.emit(
                        tick.copy(
                            symbol = binding.requestedSymbol,
                            provider = binding.provider,
                        )
                    )
                }
            }
        }
        scope.launch {
            socket.connectionState.collect {
                mutex.withLock { recomputeConnectionStateLocked() }
            }
        }
    }

    private fun observeProviderChanges() {
        scope.launch {
            appPreferences.dataProvider.collect { preferred ->
                mutex.withLock {
                    if (routes.isEmpty()) return@withLock
                    val existing = routes.map { (key, binding) -> key to binding.requestedSymbol }
                    allSockets().forEach { it.disconnectAll() }
                    routes.clear()
                    for ((key, requested) in existing) routePairLocked(key, requested, preferred)
                }
            }
        }
    }

    private suspend fun routePairLocked(
        key: Pair<String, Timeframe>,
        requestedSymbol: String,
        preferred: DataProvider,
    ) {
        val effectiveProvider = providerRouter.liveProviderFor(requestedSymbol, preferred)
        val target = effectiveProvider?.let(::socketFor)
        val previous = routes[key]

        if (previous?.socket === target && target != null && previous.requestedSymbol == requestedSymbol) {
            recomputeConnectionStateLocked()
            return
        }
        if (previous != null) previous.socket.unsubscribe(previous.requestedSymbol, key.second)

        if (target == null) {
            routes.remove(key)
            _connectionState.value = ConnectionState.ERROR
            return
        }

        routes[key] = RouteBinding(target, requestedSymbol, effectiveProvider)
        try {
            target.subscribe(requestedSymbol, key.second)
        } catch (error: Exception) {
            // Do not leave a dead route installed after a synchronous provider
            // validation/configuration failure.
            routes.remove(key)
            recomputeConnectionStateLocked()
            throw error
        }
        recomputeConnectionStateLocked()
    }

    private fun recomputeConnectionStateLocked() {
        if (routes.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val states = routes.values.map { it.socket }.distinct().map { it.connectionState.value }
        _connectionState.value = when {
            states.any { it == ConnectionState.CONNECTED } -> ConnectionState.CONNECTED
            states.any { it == ConnectionState.RECONNECTING } -> ConnectionState.RECONNECTING
            states.any { it == ConnectionState.CONNECTING } -> ConnectionState.CONNECTING
            states.any { it == ConnectionState.AUTH_FAILED } -> ConnectionState.AUTH_FAILED
            states.any { it == ConnectionState.STALE } -> ConnectionState.STALE
            states.any { it == ConnectionState.FATAL } -> ConnectionState.FATAL
            states.any { it == ConnectionState.ERROR } -> ConnectionState.ERROR
            else -> ConnectionState.DISCONNECTED
        }
    }

    private fun socketFor(provider: DataProvider): MarketWebSocket? = when (provider) {
        DataProvider.BINANCE -> binanceWebSocket
        DataProvider.BYBIT -> bybitWebSocket
        DataProvider.POLYGON -> polygonWebSocket
        DataProvider.MT4 -> mt4MarketWebSocket
        DataProvider.DUKASCOPY -> dukascopyWebSocket
        DataProvider.DERIV -> derivMarketWebSocket
        else -> null
    }

    private fun allSockets(): List<MarketWebSocket> = listOf(
        binanceWebSocket,
        bybitWebSocket,
        polygonWebSocket,
        mt4MarketWebSocket,
        dukascopyWebSocket,
        derivMarketWebSocket,
    )
}
