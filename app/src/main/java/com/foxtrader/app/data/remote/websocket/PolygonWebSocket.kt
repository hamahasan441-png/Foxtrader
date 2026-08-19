package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.api.PolygonMarket
import com.foxtrader.app.data.remote.api.PolygonTicker
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.di.PolygonMarketDataClient
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polygon.io authenticated minute-aggregate WebSocket feed.
 *
 * Polygon exposes separate WebSocket clusters for stocks, forex, crypto, and
 * indices. One FoxTrader instance can therefore hold one resilient session per
 * cluster, while the public [MarketWebSocket] contract stays unchanged. The
 * provider emits minute bars through [PolygonCandleAggregator] so H4/D1/W1/MN
 * charts receive honest higher-timeframe forming and sealed updates rather than
 * a minute candle mislabeled as a larger bar.
 *
 * API keys are sent only in Polygon's required auth frame. The dedicated OkHttp
 * client has no logging interceptor, so the credential is not written to debug
 * request logs.
 */
@Singleton
class PolygonWebSocket @Inject constructor(
    @PolygonMarketDataClient private val okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    @IoDispatcher io: CoroutineDispatcher,
) : MarketWebSocket {

    private data class Subscription(
        val displaySymbol: String,
        val ticker: String,
        val timeframe: Timeframe,
        val market: PolygonMarket,
    )

    private class Session(val market: PolygonMarket) {
        var socket: WebSocket? = null
        var state: ConnectionState = ConnectionState.DISCONNECTED
        var authenticated: Boolean = false
        var shouldReconnect: Boolean = true
        var generation: Long = 0L
        var reconnectAttempt: Int = 0
        var reconnectJob: Job? = null
        var heartbeatJob: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val aggregator = PolygonCandleAggregator()
    private val lock = Any()
    private val subscriptions = LinkedHashMap<Pair<String, Timeframe>, Subscription>()
    private val sessions = mutableMapOf<PolygonMarket, Session>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 128)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        val displaySymbol = displaySymbol(symbol)
        val ticker = PolygonTicker.normalize(displaySymbol)
        val market = PolygonTicker.market(ticker)
        synchronized(lock) {
            if (appPreferences.getApiKey(DataProvider.POLYGON).isNullOrBlank()) {
                _connectionState.value = ConnectionState.ERROR
                return
            }

            val key = displaySymbol to timeframe
            if (subscriptions.containsKey(key)) return
            val subscription = Subscription(displaySymbol, ticker, timeframe, market)
            subscriptions[key] = subscription

            val session = sessions.getOrPut(market) { Session(market) }
            ensureSessionLocked(session)
            if (session.authenticated) {
                sendSubscribeLocked(session, listOf(subscription))
            }
            refreshConnectionStateLocked()
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        val displaySymbol = displaySymbol(symbol)
        synchronized(lock) {
            val removed = subscriptions.remove(displaySymbol to timeframe) ?: return
            aggregator.remove(removed.displaySymbol, removed.timeframe)
            val session = sessions[removed.market] ?: return

            val sameTickerStillNeeded = subscriptions.values.any {
                it.market == removed.market && it.ticker == removed.ticker
            }
            if (session.authenticated && !sameTickerStillNeeded) {
                sendUnsubscribeLocked(session, listOf(removed))
            }
            if (subscriptions.values.none { it.market == removed.market }) {
                stopSessionLocked(session)
                sessions.remove(removed.market)
            }
            refreshConnectionStateLocked()
        }
    }

    override suspend fun disconnectAll() {
        synchronized(lock) {
            subscriptions.clear()
            aggregator.clear()
            sessions.values.toList().forEach { session -> stopSessionLocked(session) }
            sessions.clear()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun ensureSessionLocked(session: Session) {
        if (session.socket != null && session.state != ConnectionState.ERROR) return
        session.shouldReconnect = true
        session.reconnectJob?.cancel()
        session.generation++
        session.reconnectAttempt = 0
        connectLocked(session)
    }

    private fun connectLocked(session: Session) {
        val relevant = subscriptions.values.filter { it.market == session.market }
        if (relevant.isEmpty()) return

        session.state = ConnectionState.CONNECTING
        session.authenticated = false
        val generation = session.generation
        val request = Request.Builder().url(session.market.endpoint).build()
        session.socket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (!isCurrent(session, generation)) return
                    session.state = ConnectionState.CONNECTING
                    webSocket.send(
                        PolygonWebSocketProtocol.authMessage(
                            appPreferences.getApiKey(DataProvider.POLYGON).orEmpty(),
                        )
                    )
                    refreshConnectionStateLocked()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val messages = PolygonWebSocketProtocol.parse(text)
                synchronized(lock) {
                    if (!isCurrent(session, generation)) return
                    messages.forEach { message ->
                        when (message) {
                            PolygonWebSocketProtocol.Message.Authenticated -> {
                                session.authenticated = true
                                session.state = ConnectionState.CONNECTED
                                session.reconnectAttempt = 0
                                sendSubscribeLocked(
                                    session,
                                    subscriptions.values.filter { it.market == session.market },
                                )
                                startHeartbeatLocked(session, generation)
                            }
                            is PolygonWebSocketProtocol.Message.AuthFailed -> {
                                session.authenticated = false
                                session.shouldReconnect = false
                                session.state = ConnectionState.ERROR
                                session.socket?.close(1000, "Polygon authentication failed")
                            }
                            is PolygonWebSocketProtocol.Message.Aggregate -> {
                                if (session.authenticated) emitAggregateLocked(message.ticker, message.candle)
                            }
                        }
                    }
                    refreshConnectionStateLocked()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    if (!isCurrent(session, generation)) return
                    session.socket = null
                    session.authenticated = false
                    stopHeartbeatLocked(session)
                    session.state = ConnectionState.ERROR
                    scheduleReconnectLocked(session)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    if (!isCurrent(session, generation)) return
                    session.socket = null
                    session.authenticated = false
                    stopHeartbeatLocked(session)
                    if (session.shouldReconnect) {
                        session.state = ConnectionState.DISCONNECTED
                        scheduleReconnectLocked(session)
                    } else {
                        // An auth rejection is terminal until the user changes
                        // the key or explicitly toggles the live feed again.
                        session.state = ConnectionState.ERROR
                        refreshConnectionStateLocked()
                    }
                }
            }
        })
        refreshConnectionStateLocked()
    }

    private fun emitAggregateLocked(ticker: String, minuteCandle: Candle) {
        subscriptions.values
            .filter { it.ticker == ticker }
            .forEach { subscription ->
                aggregator.update(
                    symbol = subscription.displaySymbol,
                    timeframe = subscription.timeframe,
                    minuteCandle = minuteCandle,
                ).forEach { update ->
                    _ticks.tryEmit(
                        TickUpdate(
                            symbol = subscription.displaySymbol,
                            timeframe = subscription.timeframe,
                            candle = update.candle,
                            isBarClose = update.isBarClose,
                        )
                    )
                }
            }
    }

    private fun sendSubscribeLocked(session: Session, values: Collection<Subscription>) {
        val topics = values
            .filter { it.market == session.market }
            .map(::topic)
            .distinct()
        if (session.authenticated && topics.isNotEmpty()) {
            session.socket?.send(PolygonWebSocketProtocol.subscriptionMessage("subscribe", topics))
        }
    }

    private fun sendUnsubscribeLocked(session: Session, values: Collection<Subscription>) {
        val topics = values
            .filter { it.market == session.market }
            .map(::topic)
            .distinct()
        if (session.authenticated && topics.isNotEmpty()) {
            session.socket?.send(PolygonWebSocketProtocol.subscriptionMessage("unsubscribe", topics))
        }
    }

    private fun topic(subscription: Subscription): String =
        "${subscription.market.topicPrefix}.${PolygonTicker.subscriptionSymbol(subscription.ticker, subscription.market)}"

    private fun scheduleReconnectLocked(session: Session) {
        if (!session.shouldReconnect || subscriptions.values.none { it.market == session.market }) {
            refreshConnectionStateLocked()
            return
        }
        if (session.reconnectJob?.isActive == true) return

        session.state = ConnectionState.RECONNECTING
        val delayMs = minOf(
            INITIAL_RECONNECT_DELAY_MS * (1L shl session.reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS,
        )
        session.reconnectAttempt++
        val generation = session.generation
        session.reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                session.reconnectJob = null
                if (session.shouldReconnect && session.generation == generation) {
                    session.generation++
                    connectLocked(session)
                }
            }
        }
        refreshConnectionStateLocked()
    }

    private fun startHeartbeatLocked(session: Session, generation: Long) {
        stopHeartbeatLocked(session)
        session.heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                synchronized(lock) {
                    if (!isCurrent(session, generation) || !session.authenticated) return@launch
                    session.socket?.send(PING_MESSAGE)
                }
            }
        }
    }

    private fun stopHeartbeatLocked(session: Session) {
        session.heartbeatJob?.cancel()
        session.heartbeatJob = null
    }

    private fun stopSessionLocked(session: Session) {
        session.shouldReconnect = false
        session.generation++
        session.reconnectJob?.cancel()
        session.reconnectJob = null
        stopHeartbeatLocked(session)
        session.socket?.close(1000, "Client disconnect")
        session.socket = null
        session.authenticated = false
        session.state = ConnectionState.DISCONNECTED
    }

    private fun isCurrent(session: Session, generation: Long): Boolean =
        sessions[session.market] === session && session.generation == generation

    private fun refreshConnectionStateLocked() {
        if (subscriptions.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val states = sessions.values.map { it.state }
        _connectionState.value = when {
            states.any { it == ConnectionState.CONNECTED } -> ConnectionState.CONNECTED
            states.any { it == ConnectionState.CONNECTING } -> ConnectionState.CONNECTING
            states.any { it == ConnectionState.RECONNECTING } -> ConnectionState.RECONNECTING
            states.any { it == ConnectionState.ERROR } -> ConnectionState.ERROR
            else -> ConnectionState.DISCONNECTED
        }
    }

    private fun displaySymbol(symbol: String): String =
        symbol.trim().uppercase().replace("/", "").replace("-", "")

    private companion object {
        const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        const val PING_MESSAGE = "{\"action\":\"ping\"}"
    }
}

private val PolygonMarket.endpoint: String
    get() = when (this) {
        PolygonMarket.STOCKS -> "wss://socket.polygon.io/stocks"
        PolygonMarket.FOREX -> "wss://socket.polygon.io/forex"
        PolygonMarket.CRYPTO -> "wss://socket.polygon.io/crypto"
        PolygonMarket.INDICES -> "wss://socket.polygon.io/indices"
    }

private val PolygonMarket.topicPrefix: String
    get() = when (this) {
        PolygonMarket.STOCKS, PolygonMarket.INDICES -> "AM"
        PolygonMarket.FOREX -> "CA"
        PolygonMarket.CRYPTO -> "XA"
    }
