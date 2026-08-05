package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.di.PublicMarketDataClient
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
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
import kotlinx.serialization.json.boolean
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
 * Binance WebSocket market data feed implementation.
 *
 * Connects to Binance's kline/candlestick WebSocket stream:
 *   wss://stream.binance.com:9443/ws/<symbol>@kline_<interval>
 *
 * Features:
 * - Auto-reconnect with exponential backoff (1s → 2s → 4s → ... max 30s)
 * - Multiple simultaneous subscriptions (combined stream)
 * - Parses forming candle updates + bar close confirmations
 * - Thread-safe: all connection state is guarded by an internal lock, and a
 *   generation counter prevents an intentional close from triggering a
 *   duplicate reconnect (see [generation]).
 *
 * Symbol mapping: FoxTrader uses "EURUSD" format; Binance uses "eurusd" lowercase.
 * Crypto pairs use Binance native format (BTCUSDT → btcusdt).
 */
@Singleton
class BinanceWebSocket @Inject constructor(
    @PublicMarketDataClient private val okHttpClient: OkHttpClient,
) : MarketWebSocket {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 64)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    // `THREAD-SAFETY` The state below is touched from BOTH coroutine threads
    // (subscribe/unsubscribe/disconnect) and OkHttp callback threads
    // (onOpen/onClosed/onFailure). Every access is guarded by [lock].
    private val lock = Any()
    private val subscriptions = mutableSetOf<Pair<String, Timeframe>>()
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true

    // Bumped on every intentional (re)connect / disconnect. Each socket's
    // listener captures the generation it was created under; once a newer
    // intentional action supersedes it, that listener's onClosed/onFailure
    // become no-ops — so an intentional close can never masquerade as a dropped
    // connection and spawn a second, parallel socket.
    private var generation = 0

    companion object {
        private const val BASE_URL = "wss://stream.binance.com:9443/ws/"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        synchronized(lock) {
            if (subscriptions.add(symbol to timeframe)) reconnectLocked()
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        synchronized(lock) {
            subscriptions.remove(symbol to timeframe)
            if (subscriptions.isEmpty()) disconnectLocked() else reconnectLocked()
        }
    }

    override suspend fun disconnectAll() {
        synchronized(lock) { disconnectLocked() }
    }

    // ========================================================================
    // CONNECTION MANAGEMENT (all callers hold [lock])
    // ========================================================================

    private fun disconnectLocked() {
        shouldReconnect = false
        generation++ // invalidate any in-flight/old listeners
        subscriptions.clear()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun reconnectLocked() {
        generation++ // supersede the old socket so its onClosed won't reconnect
        webSocket?.close(1000, "Reconnecting with new subscriptions")
        webSocket = null
        shouldReconnect = true
        reconnectAttempt = 0
        connectLocked()
    }

    private fun connectLocked() {
        if (subscriptions.isEmpty()) return
        _connectionState.value = ConnectionState.CONNECTING
        val myGeneration = generation

        // Build combined stream URL
        val streams = subscriptions.joinToString("/") { (symbol, tf) ->
            "${mapSymbol(symbol)}@kline_${mapTimeframe(tf)}"
        }
        val url = "${BASE_URL}$streams"

        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (generation != myGeneration) return
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectAttempt = 0
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
                    // A superseded (intentionally closed) socket must NOT reconnect.
                    if (generation != myGeneration) return
                    _connectionState.value = ConnectionState.DISCONNECTED
                    if (shouldReconnect && subscriptions.isNotEmpty()) scheduleReconnectLocked()
                }
            }
        })
    }

    private fun scheduleReconnectLocked() {
        if (!shouldReconnect || subscriptions.isEmpty()) return
        _connectionState.value = ConnectionState.RECONNECTING

        val delay = minOf(
            INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS,
        )
        reconnectAttempt++
        val myGeneration = generation

        scope.launch {
            delay(delay)
            synchronized(lock) {
                if (shouldReconnect && subscriptions.isNotEmpty() && generation == myGeneration) {
                    connectLocked()
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

            // Binance kline event format:
            // { "e": "kline", "s": "BTCUSDT", "k": { ... } }
            val eventType = root["e"]?.jsonPrimitive?.content ?: return
            if (eventType != "kline") return

            val symbolRaw = root["s"]?.jsonPrimitive?.content ?: return
            val kline = root["k"]?.jsonObject ?: return

            val candle = Candle(
                timestamp = kline["t"]?.jsonPrimitive?.long ?: return,
                open = kline["o"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return,
                high = kline["h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return,
                low = kline["l"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return,
                close = kline["c"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return,
                volume = kline["v"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            )

            val isBarClose = kline["x"]?.jsonPrimitive?.boolean ?: false
            val interval = kline["i"]?.jsonPrimitive?.content ?: ""
            val timeframe = reverseMapTimeframe(interval)

            val tick = TickUpdate(
                symbol = reverseMapSymbol(symbolRaw),
                timeframe = timeframe,
                candle = candle,
                isBarClose = isBarClose,
            )

            _ticks.tryEmit(tick)
        } catch (_: Exception) {
            // Silently drop malformed messages — never crash the feed
        }
    }

    // ========================================================================
    // SYMBOL / TIMEFRAME MAPPING
    // ========================================================================

    private fun mapSymbol(foxSymbol: String): String =
        foxSymbol.lowercase().replace("/", "")

    private fun reverseMapSymbol(binanceSymbol: String): String =
        binanceSymbol.uppercase()

    private fun mapTimeframe(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1m"
        Timeframe.M5 -> "5m"
        Timeframe.M15 -> "15m"
        Timeframe.M30 -> "30m"
        Timeframe.H1 -> "1h"
        Timeframe.H4 -> "4h"
        Timeframe.D1 -> "1d"
        Timeframe.W1 -> "1w"
        Timeframe.MN -> "1M"
    }

    private fun reverseMapTimeframe(interval: String): Timeframe = when (interval) {
        "1m" -> Timeframe.M1
        "5m" -> Timeframe.M5
        "15m" -> Timeframe.M15
        "30m" -> Timeframe.M30
        "1h" -> Timeframe.H1
        "4h" -> Timeframe.H4
        "1d" -> Timeframe.D1
        "1w" -> Timeframe.W1
        "1M" -> Timeframe.MN
        else -> Timeframe.M15
    }
}
