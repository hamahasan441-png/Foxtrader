package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.di.PublicMarketDataClient
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bybit V5 public WebSocket market-data feed.
 *
 * Connects to Bybit's public spot kline stream:
 *   wss://stream.bybit.com/v5/public/spot
 *
 * Subscription topic format:
 *   kline.<interval>.<symbol>  e.g. kline.15.BTCUSDT
 *
 * The implementation mirrors [BinanceWebSocket]'s contract: it emits forming
 * candle updates into the offline-first Room cache path, auto-reconnects with
 * exponential backoff, and never throws on malformed provider payloads.
 */
@Singleton
class BybitWebSocket @Inject constructor(
    @PublicMarketDataClient private val okHttpClient: OkHttpClient,
) : MarketWebSocket {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ticks = MutableSharedFlow<TickUpdate>(extraBufferCapacity = 64)
    override val ticks: Flow<TickUpdate> = _ticks.asSharedFlow()

    private val subscriptions = mutableSetOf<Pair<String, Timeframe>>()
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true

    override suspend fun subscribe(symbol: String, timeframe: Timeframe) {
        val pair = normalizeSymbol(symbol) to timeframe
        val added = subscriptions.add(pair)
        when {
            webSocket == null -> reconnectWithAllSubscriptions()
            added && connectionState.value == ConnectionState.CONNECTED -> sendSubscribe(listOf(pair))
            added -> reconnectWithAllSubscriptions()
        }
    }

    override suspend fun unsubscribe(symbol: String, timeframe: Timeframe) {
        val pair = normalizeSymbol(symbol) to timeframe
        if (!subscriptions.remove(pair)) return

        if (subscriptions.isEmpty()) {
            disconnectAll()
        } else {
            sendUnsubscribe(listOf(pair))
        }
    }

    override suspend fun disconnectAll() {
        shouldReconnect = false
        subscriptions.clear()
        stopHeartbeat()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun reconnectWithAllSubscriptions() {
        if (subscriptions.isEmpty()) return
        stopHeartbeat()
        webSocket?.close(1000, "Reconnecting with subscriptions")
        webSocket = null
        shouldReconnect = true
        reconnectAttempt = 0
        connect()
    }

    private fun connect() {
        if (subscriptions.isEmpty()) return
        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder().url(SPOT_PUBLIC_URL).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempt = 0
                sendSubscribe(subscriptions.toList())
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmit(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                _connectionState.value = ConnectionState.ERROR
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                _connectionState.value = ConnectionState.DISCONNECTED
                if (shouldReconnect && subscriptions.isNotEmpty()) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || subscriptions.isEmpty()) return
        _connectionState.value = ConnectionState.RECONNECTING

        val delayMs = minOf(
            INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS,
        )
        reconnectAttempt++

        scope.launch {
            delay(delayMs)
            if (shouldReconnect && subscriptions.isNotEmpty()) connect()
        }
    }

    private fun sendSubscribe(pairs: Collection<Pair<String, Timeframe>>) {
        sendSubscriptionMessage(op = "subscribe", pairs = pairs)
    }

    private fun sendUnsubscribe(pairs: Collection<Pair<String, Timeframe>>) {
        sendSubscriptionMessage(op = "unsubscribe", pairs = pairs)
    }

    private fun sendSubscriptionMessage(op: String, pairs: Collection<Pair<String, Timeframe>>) {
        if (pairs.isEmpty()) return
        val args = pairs.map { (symbol, timeframe) ->
            JsonPrimitive("kline.${mapTimeframe(timeframe)}.${normalizeSymbol(symbol)}")
        }
        val payload = buildJsonObject {
            put("op", op)
            put("args", JsonArray(args))
        }.toString()
        webSocket?.send(payload)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (shouldReconnect && webSocket != null) {
                delay(HEARTBEAT_INTERVAL_MS)
                webSocket?.send(PING_PAYLOAD)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun parseAndEmit(text: String) {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val topic = root["topic"]?.jsonPrimitive?.content ?: return
            if (!topic.startsWith("kline.")) return

            val parts = topic.split('.')
            val interval = parts.getOrNull(1) ?: return
            val symbol = parts.getOrNull(2)?.uppercase() ?: return
            val timeframe = reverseMapTimeframe(interval)
            val data = root["data"]?.jsonArray ?: return

            data.forEach { element ->
                parseCandle(element.jsonObject)?.let { candle ->
                    val isBarClose = element.jsonObject.booleanString("confirm") == true
                    _ticks.tryEmit(
                        TickUpdate(
                            symbol = symbol,
                            timeframe = timeframe,
                            candle = candle,
                            isBarClose = isBarClose,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Drop malformed provider payloads. A bad tick must never crash UI.
        }
    }

    private fun parseCandle(obj: JsonObject): Candle? {
        val start = obj.string("start")?.toLongOrNull()
            ?: obj.string("timestamp")?.toLongOrNull()
            ?: return null
        return Candle(
            timestamp = start,
            open = obj.string("open")?.toDoubleOrNull() ?: return null,
            high = obj.string("high")?.toDoubleOrNull() ?: return null,
            low = obj.string("low")?.toDoubleOrNull() ?: return null,
            close = obj.string("close")?.toDoubleOrNull() ?: return null,
            volume = obj.string("volume")?.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun normalizeSymbol(symbol: String): String =
        symbol.uppercase().replace("/", "")

    private fun mapTimeframe(tf: Timeframe): String = when (tf) {
        Timeframe.M1 -> "1"
        Timeframe.M5 -> "5"
        Timeframe.M15 -> "15"
        Timeframe.M30 -> "30"
        Timeframe.H1 -> "60"
        Timeframe.H4 -> "240"
        Timeframe.D1 -> "D"
        Timeframe.W1 -> "W"
        Timeframe.MN -> "M"
    }

    private fun reverseMapTimeframe(interval: String): Timeframe = when (interval) {
        "1" -> Timeframe.M1
        "5" -> Timeframe.M5
        "15" -> Timeframe.M15
        "30" -> Timeframe.M30
        "60" -> Timeframe.H1
        "240" -> Timeframe.H4
        "D" -> Timeframe.D1
        "W" -> Timeframe.W1
        "M" -> Timeframe.MN
        else -> Timeframe.M15
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.content

    private fun JsonObject.booleanString(name: String): Boolean? =
        when (this[name]?.jsonPrimitive?.content?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private companion object {
        const val SPOT_PUBLIC_URL = "wss://stream.bybit.com/v5/public/spot"
        const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        const val PING_PAYLOAD = "{\"op\":\"ping\"}"
    }
}
