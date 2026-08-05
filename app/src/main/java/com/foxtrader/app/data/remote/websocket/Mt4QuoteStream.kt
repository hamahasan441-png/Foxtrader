package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.di.PublicMarketDataClient
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket connection to MetaApi's streaming endpoint for real-time MT4 quotes.
 *
 * Subscribes/unsubscribes for individual symbols; emits [Mt4Quote] updates via
 * a shared [Flow]. Handles auto-reconnect with exponential backoff.
 *
 * Streaming endpoint pattern:
 *   wss://mt-client-api-v1.agiliumtrade.agiliumtrade.ai/ws
 *
 * The actual connection requires the MetaApi auth token and account ID which
 * are set via [connect]. After connection, subscribe to symbol price feeds.
 */
@Singleton
class Mt4QuoteStream @Inject constructor(
    @PublicMarketDataClient private val okHttpClient: OkHttpClient,
) {

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

    companion object {
        private const val BASE_WS_URL = "wss://mt-client-api-v1.agiliumtrade.agiliumtrade.ai/ws"
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
            authToken = token
            accountId = metaApiAccountId
            shouldReconnect = true
            reconnectAttempt = 0
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
     * Disconnect the WebSocket and clear all subscriptions.
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
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun connectLocked() {
        if (authToken.isBlank() || accountId.isBlank()) return
        _connectionState.value = ConnectionState.CONNECTING
        generation++
        val myGeneration = generation

        val url = "$BASE_WS_URL?auth-token=$authToken&accountId=$accountId"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (generation != myGeneration) return
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectAttempt = 0
                    resubscribeAllLocked()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmit(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    if (generation != myGeneration) return
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

            val quote = Mt4Quote(
                symbol = symbol,
                bid = bid,
                ask = ask,
                timestamp = timestamp,
            )
            _quotes.tryEmit(quote)
        } catch (_: Exception) {
            // Silently drop malformed messages; never crash the feed.
        }
    }
}
