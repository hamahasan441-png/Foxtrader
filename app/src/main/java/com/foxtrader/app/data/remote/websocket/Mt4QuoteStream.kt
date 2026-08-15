package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.di.MetaApiWebSocketClient
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.Mt4Quote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket connection to MetaApi's streaming endpoint for real-time MT4 quotes.
 *
 * Subscribes/unsubscribes for individual symbols; emits [Mt4Quote] updates via
 * a shared [Flow].
 *
 * Hardening guarantees:
 *  - Uses [Mt4WebSocketRequest] so the auth token is only ever in the wire URL,
 *    never in logs (the dedicated [MetaApiWebSocketClient] has no logging).
 *  - OkHttp client pings every 15s; a missing heartbeat for > 45s marks the
 *    stream [ConnectionState.STALE] and forces a fresh connection.
 *  - Credential rejection (HTTP 401/403) transitions to [ConnectionState.AUTH_FAILED]
 *    and stops reconnecting — no point hammering an unauthorized endpoint.
 *  - A reconnect budget of 8 attempts leads to [ConnectionState.FATAL].
 *  - Duplicate and out-of-order quotes are suppressed per symbol.
 *  - Disconnect clears the in-memory token/account so they cannot linger in RAM.
 */
@Singleton
class Mt4QuoteStream @Inject constructor(
    @MetaApiWebSocketClient private val okHttpClient: okhttp3.OkHttpClient,
) {

    /**
     * Returns the most recent [Mt4Quote] for [symbol], or null if none has been
     * received yet. Used to build a fresh execution context for live orders.
     */
    fun latestQuote(symbol: String): Mt4Quote? = latestQuotes[symbol.uppercase()]

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _quotes = MutableSharedFlow<Mt4Quote>(extraBufferCapacity = 64)
    val quotes: Flow<Mt4Quote> = _quotes.asSharedFlow()

    private val lock = Any()
    private val subscribedSymbols = mutableSetOf<String>()
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true
    private var generation = 0
    private var authToken: String = ""
    private var accountId: String = ""

    // Monotonic last-received timestamp per symbol (in millis). Used to drop
    // duplicate and out-of-order quotes for the same symbol/timestamp.
    private val lastQuoteTimeBySymbol = mutableMapOf<String, Long>()

    // Latest quote per symbol, for callers (e.g. the execution safety layer)
    // that need a fresh reference price without holding the subscription flow.
    private val latestQuotes = ConcurrentHashMap<String, Mt4Quote>()

    // Last message wall-clock time, used by the stale watchdog.
    @Volatile private var lastMessageAt: Long = 0L
    private var watchdogStarted = false

    companion object {
        private const val STALE_TIMEOUT_MS = 45_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val MAX_RECONNECT_ATTEMPTS = 8
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    /**
     * Establish the streaming WebSocket connection using MetaApi credentials.
     *
     * @param token MetaApi auth token.
     * @param metaApiAccountId The provisioned account ID.
     */
    fun connect(token: String, metaApiAccountId: String) {
        synchronized(lock) {
            // Idempotent: if we are already connected/connecting with the same
            // credentials, do not spin up a second socket (both the login flow
            // and the chart bridge can call connect for the same session).
            val alreadyLive = (webSocket != null) &&
                (authToken == token && accountId == metaApiAccountId) &&
                (_connectionState.value == ConnectionState.CONNECTED ||
                    _connectionState.value == ConnectionState.CONNECTING ||
                    _connectionState.value == ConnectionState.RECONNECTING)
            if (alreadyLive) return

            authToken = token
            accountId = metaApiAccountId
            shouldReconnect = true
            reconnectAttempt = 0
            lastQuoteTimeBySymbol.clear()
            connectLocked()
        }
    }

    /**
     * Subscribe to real-time quotes for a symbol.
     */
    fun subscribe(symbol: String) {
        synchronized(lock) {
            subscribedSymbols.add(symbol.uppercase())
            sendSubscription(symbol.uppercase())
        }
    }

    /**
     * Unsubscribe from real-time quotes for a symbol.
     */
    fun unsubscribe(symbol: String) {
        synchronized(lock) {
            subscribedSymbols.remove(symbol.uppercase())
            if (subscribedSymbols.isEmpty()) {
                disconnectLocked()
            }
        }
    }

    /**
     * Disconnect the WebSocket, clear all subscriptions, and wipe the in-memory
     * token/account credentials.
     */
    fun disconnect() {
        synchronized(lock) { disconnectLocked() }
    }

    // ========================================================================
    // CONNECTION MANAGEMENT (callers hold [lock])
    // ========================================================================

    private fun disconnectLocked() {
        shouldReconnect = false
        generation++
        subscribedSymbols.clear()
        lastQuoteTimeBySymbol.clear()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        // Wipe in-memory credentials so a live token does not linger in RAM.
        authToken = ""
        accountId = ""
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun connectLocked() {
        val wsRequest = Mt4WebSocketRequest.create(authToken, accountId)
        if (wsRequest == null) {
            _connectionState.value = ConnectionState.AUTH_FAILED
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        generation++
        val myGeneration = generation
        startWatchdogLocked()

        // Note: wsRequest.request carries the token in its URL query string. It
        // is only ever passed to newWebSocket for the wire; it is never logged
        // (redacted() exists for diagnostics) and the dedicated client has no
        // logging interceptor.
        webSocket = okHttpClient.newWebSocket(wsRequest.request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (generation != myGeneration) return
                    _connectionState.value = ConnectionState.CONNECTED
                    lastMessageAt = System.currentTimeMillis()
                    reconnectAttempt = 0
                    resubscribeAllLocked()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageAt = System.currentTimeMillis()
                parseAndEmit(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    if (generation != myGeneration) return
                    val code = response?.code
                    if (code == 401 || code == 403) {
                        // Credential rejection — terminal; do not reconnect.
                        shouldReconnect = false
                        _connectionState.value = ConnectionState.AUTH_FAILED
                        webSocket.close(1000, "Auth failed")
                        return
                    }
                    _connectionState.value = ConnectionState.ERROR
                    scheduleReconnectLocked()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    if (generation != myGeneration) return
                    _connectionState.value = ConnectionState.DISCONNECTED
                    if (shouldReconnect && subscribedSymbols.isNotEmpty()) {
                        scheduleReconnectLocked()
                    }
                }
            }
        })
    }

    private fun scheduleReconnectLocked() {
        if (!shouldReconnect) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.FATAL
            shouldReconnect = false
            return
        }

        _connectionState.value = ConnectionState.RECONNECTING

        val delayMs = minOf(
            INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS,
        )
        reconnectAttempt++
        val myGeneration = generation

        scope.launch {
            delay(delayMs)
            synchronized(lock) {
                if (shouldReconnect && generation == myGeneration) {
                    connectLocked()
                }
            }
        }
    }

    private fun resubscribeAllLocked() {
        subscribedSymbols.forEach { symbol -> sendSubscription(symbol) }
    }

    private fun sendSubscription(symbol: String) {
        val ws = webSocket ?: return
        val subscribeMsg = """{"type":"subscribe","subscriptions":[{"type":"quotes","symbol":"$symbol"}]}"""
        ws.send(subscribeMsg)
    }

    /**
     * Starts a single watchdog that tears down the connection whenever the
     * stream has been silent for longer than [STALE_TIMEOUT_MS]. The watchdog
     * is intentionally started under [lock] and guarded by a flag so only one
     * instance ever runs.
     */
    private fun startWatchdogLocked() {
        if (watchdogStarted) return
        watchdogStarted = true
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val silentFor = System.currentTimeMillis() - lastMessageAt
                if (silentFor > STALE_TIMEOUT_MS) {
                    synchronized(lock) {
                        if (shouldReconnect && _connectionState.value == ConnectionState.CONNECTED) {
                            _connectionState.value = ConnectionState.STALE
                            webSocket?.close(1000, "Stale heartbeat")
                            webSocket = null
                            if (shouldReconnect && subscribedSymbols.isNotEmpty()) {
                                scheduleReconnectLocked()
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // MESSAGE PARSING
    // ========================================================================

    private fun parseAndEmit(text: String) {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val type = root["type"]?.jsonPrimitive?.content ?: return
            if (type != "quotes") return

            val symbol = root["symbol"]?.jsonPrimitive?.content ?: return
            val bid = root["bid"]?.jsonPrimitive?.double ?: return
            val ask = root["ask"]?.jsonPrimitive?.double ?: return
            val timestamp = root["time"]?.jsonPrimitive?.long ?: System.currentTimeMillis()

            // Suppress duplicate and out-of-order quotes per symbol.
            val last = lastQuoteTimeBySymbol[symbol] ?: Long.MIN_VALUE
            if (timestamp <= last) return
            lastQuoteTimeBySymbol[symbol] = timestamp

            val quote = Mt4Quote(
                symbol = symbol,
                bid = bid,
                ask = ask,
                timestamp = timestamp,
            )
            latestQuotes[symbol] = quote
            _quotes.tryEmit(quote)
        } catch (_: Exception) {
            // Silently drop malformed messages; never crash the feed.
        }
    }
}
