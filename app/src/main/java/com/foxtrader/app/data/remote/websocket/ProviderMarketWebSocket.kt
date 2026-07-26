package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider-aware live market-data router.
 *
 * The chart depends on the stable [MarketWebSocket] interface while Settings can
 * switch providers. This router delegates subscriptions to the currently
 * selected, implemented live provider (Binance or Bybit today) and forwards only
 * the active provider's ticks/connection state.
 */
@Singleton
class ProviderMarketWebSocket @Inject constructor(
    private val appPreferences: AppPreferences,
    private val binanceWebSocket: BinanceWebSocket,
    private val bybitWebSocket: BybitWebSocket,
    @IoDispatcher io: CoroutineDispatcher,
) : MarketWebSocket {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()
    private val subscriptions = mutableSetOf<Pair<String, Timeframe>>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 64)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    @Volatile
    private var activeProvider: DataProvider? = null

    @Volatile
    private var activeSocket: MarketWebSocket? = null

    init {
        forward(binanceWebSocket)
        forward(bybitWebSocket)
        observeProviderChanges()
    }

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        mutex.withLock {
            val pair = symbol to timeframe
            val added = subscriptions.add(pair)
            val provider = appPreferences.dataProvider.value
            val previousSocket = activeSocket

            ensureProviderLocked(provider)

            if (added && activeSocket != null && activeSocket === previousSocket) {
                activeSocket?.subscribe(symbol, timeframe)
            }
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        mutex.withLock {
            val removed = subscriptions.remove(symbol to timeframe)
            if (removed) {
                activeSocket?.unsubscribe(symbol, timeframe)
                if (subscriptions.isEmpty()) {
                    activeSocket?.disconnectAll()
                    activeSocket = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    override suspend fun disconnectAll() {
        mutex.withLock {
            subscriptions.clear()
            binanceWebSocket.disconnectAll()
            bybitWebSocket.disconnectAll()
            activeSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun forward(socket: MarketWebSocket) {
        scope.launch {
            socket.ticks.collect { tick ->
                if (socket === activeSocket) {
                    _ticks.emit(tick)
                }
            }
        }
        scope.launch {
            socket.connectionState.collect { state ->
                if (socket === activeSocket) {
                    _connectionState.value = state
                }
            }
        }
    }

    private fun observeProviderChanges() {
        scope.launch {
            appPreferences.dataProvider.collect { provider ->
                mutex.withLock {
                    if (subscriptions.isNotEmpty() && provider != activeProvider) {
                        switchProviderLocked(provider)
                    } else if (subscriptions.isEmpty()) {
                        activeProvider = provider
                    }
                }
            }
        }
    }

    private suspend fun ensureProviderLocked(provider: DataProvider) {
        if (provider != activeProvider || activeSocket == null) {
            switchProviderLocked(provider)
        }
    }

    private suspend fun switchProviderLocked(provider: DataProvider) {
        val target = socketFor(provider)

        activeSocket?.disconnectAll()
        activeSocket = target
        activeProvider = provider

        if (target == null) {
            _connectionState.value = if (subscriptions.isEmpty()) {
                ConnectionState.DISCONNECTED
            } else {
                ConnectionState.ERROR
            }
            return
        }

        _connectionState.value = target.connectionState.value
        subscriptions.forEach { (symbol, timeframe) ->
            target.subscribe(symbol, timeframe)
        }
    }

    private fun socketFor(provider: DataProvider): MarketWebSocket? = when (provider) {
        DataProvider.BINANCE -> binanceWebSocket
        DataProvider.BYBIT -> bybitWebSocket
        else -> null
    }
}
