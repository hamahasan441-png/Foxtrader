package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.api.MetaApiEndpointResolver
import com.foxtrader.app.di.MetaApiSocketClient
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.Mt4Quote
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MetaApi's documented low-latency Socket.IO price channel.
 *
 * The client is intentionally quote-only. Trading remains on the audited REST
 * execution path so a dropped stream can never turn into an implicit order.
 * Every callback is generation-bound; a callback from a disconnected/account-
 * switched socket is ignored even if the network library delivers it late.
 */
@Singleton
class MetaApiStreamingClient @Inject constructor(
    @MetaApiSocketClient private val okHttpClient: OkHttpClient,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _quotes = MutableSharedFlow<Mt4Quote>(extraBufferCapacity = 256)
    val quotes: Flow<Mt4Quote> = _quotes.asSharedFlow()

    private var socket: Socket? = null
    private var generation: Long = 0L
    private var accountId: String = ""
    private val desiredSymbols = linkedSetOf<String>()

    fun connect(token: String, accountId: String, region: String) {
        val safeToken = token.trim()
        val safeId = accountId.trim()
        require(safeToken.isNotEmpty()) { "MetaApi token is required" }
        require(safeId.isNotEmpty()) { "MetaApi account id is required" }
        val origin = MetaApiEndpointResolver.clientOrigin(region)

        val old: Socket?
        val myGeneration: Long
        synchronized(lock) {
            generation++
            myGeneration = generation
            old = socket
            socket = null
            this.accountId = safeId
            _state.value = ConnectionState.CONNECTING
        }
        destroySocket(old)

        val encodedToken = URLEncoder.encode(safeToken, StandardCharsets.UTF_8.name())
        val options = IO.Options.builder()
            .setForceNew(true)
            .setMultiplex(false)
            .setPath("/ws")
            .setQuery("auth-token=$encodedToken")
            .setReconnection(true)
            .setReconnectionAttempts(12)
            .setReconnectionDelay(750)
            .setReconnectionDelayMax(15_000)
            .setTimeout(15_000)
            .setTransports(arrayOf(WebSocket.NAME))
            .build()
        // Reuse our explicitly zero-log TLS client for both the Socket.IO HTTP
        // handshake and WebSocket transport.
        options.callFactory = okHttpClient
        options.webSocketFactory = okHttpClient

        val candidate = IO.socket(URI.create(origin), options)
        candidate.on(Socket.EVENT_CONNECT) {
            if (!isCurrent(myGeneration, candidate, safeId)) return@on
            _state.value = ConnectionState.CONNECTED
            emitAllSubscriptions(candidate, myGeneration, safeId)
        }
        candidate.on(Socket.EVENT_DISCONNECT) {
            if (!isCurrent(myGeneration, candidate, safeId)) return@on
            _state.value = ConnectionState.RECONNECTING
        }
        candidate.on(Socket.EVENT_CONNECT_ERROR) { args ->
            if (!isCurrent(myGeneration, candidate, safeId)) return@on
            val text = args.joinToString(" ").lowercase()
            if ("401" in text || "403" in text || "unauthor" in text || "forbidden" in text || "auth" in text) {
                failAuth(myGeneration, candidate, safeId)
            } else {
                _state.value = ConnectionState.RECONNECTING
            }
        }
        candidate.on("processingError") { args ->
            if (!isCurrent(myGeneration, candidate, safeId)) return@on
            val text = args.joinToString(" ").lowercase()
            if ("401" in text || "403" in text || "unauthor" in text || "forbidden" in text) {
                failAuth(myGeneration, candidate, safeId)
            }
        }
        candidate.on("synchronization") { args ->
            if (!isCurrent(myGeneration, candidate, safeId)) return@on
            for (arg in args) {
                val root = arg as? JSONObject ?: continue
                if (!root.optString("accountId").equals(safeId, ignoreCase = false)) continue
                if (!root.optString("type").equals("prices", ignoreCase = true)) continue
                parsePrices(root.optJSONArray("prices") ?: JSONArray()).forEach { quote ->
                    if (!isCurrent(myGeneration, candidate, safeId)) return@forEach
                    if (synchronized(lock) { quote.symbol !in desiredSymbols }) return@forEach
                    if (!_quotes.tryEmit(quote)) {
                        // Silent loss is unsafe for a stream used to gate order
                        // review. Tear down this generation and let REST fallback
                        // take over until a clean socket is established again.
                        failGeneration(myGeneration, candidate, safeId)
                        return@on
                    }
                }
            }
        }

        synchronized(lock) {
            if (generation != myGeneration || this.accountId != safeId) {
                destroySocket(candidate)
                return
            }
            socket = candidate
        }
        candidate.connect()
    }

    fun replaceSubscriptions(symbols: Collection<String>) {
        val normalized = symbols.mapNotNull(::normalizeSymbolOrNull).toSet()
        val active: Socket?
        val myGeneration: Long
        val id: String
        val removed: Set<String>
        val added: Set<String>
        synchronized(lock) {
            removed = desiredSymbols - normalized
            added = normalized - desiredSymbols
            desiredSymbols.clear()
            desiredSymbols.addAll(normalized)
            active = socket
            myGeneration = generation
            id = accountId
        }
        if (active?.connected() == true && id.isNotBlank()) {
            removed.forEach { emitSubscription(active, myGeneration, id, it, subscribe = false) }
            added.forEach { emitSubscription(active, myGeneration, id, it, subscribe = true) }
        }
    }

    fun disconnect(clearSubscriptions: Boolean = true) {
        val old: Socket?
        synchronized(lock) {
            generation++
            old = socket
            socket = null
            accountId = ""
            if (clearSubscriptions) desiredSymbols.clear()
            _state.value = ConnectionState.DISCONNECTED
        }
        destroySocket(old)
    }

    private fun emitAllSubscriptions(candidate: Socket, myGeneration: Long, id: String) {
        val symbols = synchronized(lock) { desiredSymbols.toList() }
        symbols.forEach { emitSubscription(candidate, myGeneration, id, it, subscribe = true) }
    }

    private fun emitSubscription(candidate: Socket, myGeneration: Long, id: String, symbol: String, subscribe: Boolean) {
        if (!isCurrent(myGeneration, candidate, id) || !candidate.connected()) return
        val request = JSONObject()
            .put("accountId", id)
            .put("type", if (subscribe) "subscribeToMarketData" else "unsubscribeFromMarketData")
            .put("requestId", UUID.randomUUID().toString())
            .put("application", "MetaApi")
            .put("symbol", symbol)
            .put(
                "subscriptions",
                JSONArray().put(
                    JSONObject()
                        .put("type", "quotes")
                        .apply { if (subscribe) put("intervalInMilliseconds", 200) }
                )
            )
        candidate.emit("request", request)
    }

    private fun parsePrices(prices: JSONArray): List<Mt4Quote> {
        val result = ArrayList<Mt4Quote>(prices.length())
        val now = System.currentTimeMillis()
        for (i in 0 until prices.length()) {
            val price = prices.optJSONObject(i) ?: continue
            val symbol = normalizeSymbolOrNull(price.optString("symbol")) ?: continue
            val bid = price.optDouble("bid", Double.NaN)
            val ask = price.optDouble("ask", Double.NaN)
            val timestamp = parseTimestamp(price.opt("time")) ?: continue
            if (!bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid) continue
            // Reject implausible future data. Old ticks are allowed here because
            // Mt4QuoteStream performs strict monotonic/freshness handling.
            if (timestamp > now + 60_000L) continue
            result += Mt4Quote(symbol = symbol, bid = bid, ask = ask, timestamp = timestamp)
        }
        return result
    }

    private fun parseTimestamp(value: Any?): Long? = when (value) {
        is Number -> value.toLong().let { if (it in 1..9_999_999_999L) it * 1000L else it }.takeIf { it > 0L }
        is String -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: value.toLongOrNull()?.let { if (it in 1..9_999_999_999L) it * 1000L else it }.takeIf { (it ?: 0L) > 0L }
        else -> null
    }

    private fun isCurrent(myGeneration: Long, candidate: Socket, id: String): Boolean = synchronized(lock) {
        generation == myGeneration && socket === candidate && accountId == id
    }

    private fun failGeneration(myGeneration: Long, candidate: Socket, id: String) {
        val shouldReconnect = synchronized(lock) {
            if (generation != myGeneration || socket !== candidate || accountId != id) return@synchronized false
            _state.value = ConnectionState.STALE
            true
        }
        if (!shouldReconnect) return
        // A local consumer overflow means this generation cannot prove that it
        // delivered every price event. Reconnect the same authenticated socket
        // generation so the server replays fresh terminal state/subscriptions;
        // REST remains the outer watchdog while the reconnect is in progress.
        runCatching { candidate.disconnect() }
        if (isCurrent(myGeneration, candidate, id)) {
            _state.value = ConnectionState.RECONNECTING
            runCatching { candidate.connect() }
                .onFailure { failGenerationPermanently(myGeneration, candidate, id) }
        }
    }

    private fun failAuth(myGeneration: Long, candidate: Socket, id: String) {
        val shouldClose = synchronized(lock) {
            if (generation != myGeneration || socket !== candidate || accountId != id) return@synchronized false
            generation++
            socket = null
            _state.value = ConnectionState.AUTH_FAILED
            true
        }
        if (shouldClose) destroySocket(candidate)
    }

    private fun failGenerationPermanently(myGeneration: Long, candidate: Socket, id: String) {
        val shouldClose = synchronized(lock) {
            if (generation != myGeneration || socket !== candidate || accountId != id) return@synchronized false
            generation++
            socket = null
            _state.value = ConnectionState.STALE
            true
        }
        if (shouldClose) destroySocket(candidate)
    }

    private fun destroySocket(value: Socket?) {
        if (value == null) return
        runCatching { value.off() }
        runCatching { value.disconnect() }
        runCatching { value.close() }
    }

    private fun normalizeSymbolOrNull(symbol: String): String? = symbol.trim().uppercase()
        .takeIf { it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl) }
}
