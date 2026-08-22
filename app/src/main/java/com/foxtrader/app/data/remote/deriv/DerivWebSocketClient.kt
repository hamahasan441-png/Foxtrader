package com.foxtrader.app.data.remote.deriv

import com.foxtrader.app.di.DerivApiClient
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.deriv.DerivConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DerivWebSocketClient @Inject constructor(
    @DerivApiClient private val client: OkHttpClient,
    private val json: Json,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val lock = Any()
    private val nextId = AtomicInteger(1000)
    private val generation = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var keepAliveJob: Job? = null
    private data class PendingRequest(
        val generation: Long,
        val deferred: CompletableDeferred<JsonObject>,
    )

    private val pending = ConcurrentHashMap<Int, PendingRequest>()
    private val _messages = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val messages = _messages.asSharedFlow()
    private val _state = MutableStateFlow(DerivConnectionState.DISCONNECTED)
    val state: StateFlow<DerivConnectionState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var currentUrl: String? = null

    fun nextReqId(): Int {
        while (true) {
            val id = nextId.updateAndGet { current ->
                if (current >= Int.MAX_VALUE - 1 || current < 1) 1001 else current + 1
            }
            if (!pending.containsKey(id)) return id
        }
    }

    fun sessionGeneration(): Long = generation.get()
    fun isCurrentGeneration(expected: Long): Boolean = generation.get() == expected

    suspend fun connectPublic(): Result<Unit> = connect(PUBLIC_WS)

    suspend fun connectAuthenticated(url: String): Result<Unit> {
        if (!isExpectedAuthenticatedUrl(url)) {
            return Result.failure(DerivApiException("Unexpected Deriv authenticated WebSocket URL"))
        }
        return connect(url)
    }

    private suspend fun connect(url: String): Result<Unit> {
        val gen: Long
        synchronized(lock) {
            if (_state.value == DerivConnectionState.CONNECTED && currentUrl == url && socket != null) {
                return Result.success(Unit)
            }
            failPending(DerivApiException("Deriv WebSocket connection replaced"))
            closeLocked()
            gen = generation.incrementAndGet()
            _state.value = DerivConnectionState.CONNECTING
            currentUrl = url
        }

        val opened = CompletableDeferred<Result<Unit>>()
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation.get() != gen) {
                    webSocket.close(1000, "stale generation")
                    return
                }
                synchronized(lock) { socket = webSocket }
                _state.value = DerivConnectionState.CONNECTED
                startKeepAlive(webSocket, gen)
                opened.complete(Result.success(Unit))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation.get() != gen) return
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val reqId = root["req_id"]?.jsonPrimitive?.intOrNull
                if (reqId != null) {
                    val request = pending[reqId]
                    if (request != null && request.generation == gen && pending.remove(reqId, request)) {
                        request.deferred.complete(root)
                    }
                }
                if (!_messages.tryEmit(root)) {
                    // Never silently drop subscription events (transactions,
                    // ticks, contract updates). If consumers fall behind the
                    // bounded buffer, invalidate the session so callers must
                    // reconnect/reconcile instead of operating on a stream with
                    // an invisible hole.
                    val overflow = DerivApiException("Deriv WebSocket event buffer overflow")
                    synchronized(lock) {
                        if (generation.get() == gen) {
                            generation.incrementAndGet()
                            closeLocked()
                            _state.value = DerivConnectionState.FAILED
                        }
                    }
                    failPending(overflow)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation.get() != gen) return
                keepAliveJob?.cancel()
                _state.value = DerivConnectionState.FAILED
                failPending(t)
                if (!opened.isCompleted) opened.complete(Result.failure(t))
                synchronized(lock) {
                    if (socket === webSocket) socket = null
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation.get() != gen) return
                keepAliveJob?.cancel()
                _state.value = DerivConnectionState.DISCONNECTED
                failPending(DerivApiException("Deriv WebSocket closed: $code $reason"))
                synchronized(lock) {
                    if (socket === webSocket) socket = null
                }
            }
        })
        synchronized(lock) { socket = ws }
        val result = runCatching { withTimeout(CONNECT_TIMEOUT_MS) { opened.await() } }
            .getOrElse { Result.failure(it) }
        if (result.isFailure) {
            synchronized(lock) {
                if (generation.get() == gen) {
                    // Invalidate this generation before closing. A late onOpen
                    // callback after timeout/cancellation must never resurrect
                    // a connection the caller already considers failed.
                    generation.incrementAndGet()
                    closeLocked()
                    _state.value = DerivConnectionState.FAILED
                }
            }
        }
        return result
    }

    suspend fun request(payload: JsonObject, reqId: Int): JsonObject {
        val waiter = CompletableDeferred<JsonObject>()
        val pendingRequest: PendingRequest
        val ws: WebSocket
        synchronized(lock) {
            ws = socket ?: throw DerivApiException("Deriv WebSocket is not connected")
            if (_state.value != DerivConnectionState.CONNECTED) {
                throw DerivApiException("Deriv WebSocket is not ready")
            }
            val gen = generation.get()
            pendingRequest = PendingRequest(gen, waiter)
            val previous = pending.putIfAbsent(reqId, pendingRequest)
            if (previous != null) throw DerivApiException("Duplicate Deriv request id: $reqId")
            if (generation.get() != gen || socket !== ws) {
                pending.remove(reqId, pendingRequest)
                throw DerivApiException("Deriv WebSocket session changed before request submission")
            }
            if (!ws.send(payload.toString())) {
                pending.remove(reqId, pendingRequest)
                throw DerivApiException("Failed to send Deriv request")
            }
        }
        return try {
            val response = withTimeout(REQUEST_TIMEOUT_MS) { waiter.await() }
            response.throwIfDerivError()
            response
        } finally {
            pending.remove(reqId, pendingRequest)
        }
    }

    /**
     * Stream events for exactly one WebSocket generation. The flow closes with
     * an error when the session is replaced, disconnected or fails, so callers
     * never hang forever on a SharedFlow after a transport failure/overflow.
     */
    fun messagesForGeneration(expectedGeneration: Long): Flow<JsonObject> = callbackFlow {
        if (generation.get() != expectedGeneration || _state.value != DerivConnectionState.CONNECTED) {
            close(DerivApiException("Deriv WebSocket session is no longer active"))
            return@callbackFlow
        }
        val messageCollector = launch {
            messages.collect { root ->
                if (generation.get() != expectedGeneration) {
                    close(DerivApiException("Deriv WebSocket session changed"))
                    return@collect
                }
                send(root)
            }
        }
        val stateCollector = launch {
            state.drop(1).collect { newState ->
                if (generation.get() != expectedGeneration || newState != DerivConnectionState.CONNECTED) {
                    close(DerivApiException("Deriv WebSocket connection ended: $newState"))
                }
            }
        }
        awaitClose {
            messageCollector.cancel()
            stateCollector.cancel()
        }
    }

    fun messageFlow(msgType: String): Flow<JsonObject> = callbackFlow {
        val expected = sessionGeneration()
        val collector = launch {
            messagesForGeneration(expected).collect { root ->
                val type = root["msg_type"]?.jsonPrimitive?.contentOrNull
                if (type == msgType) send(root)
            }
        }
        awaitClose { collector.cancel() }
    }

    fun disconnect() {
        synchronized(lock) {
            // Generation, socket teardown and state change are one atomic
            // boundary with request submission (which uses the same lock).
            generation.incrementAndGet()
            closeLocked()
            _state.value = DerivConnectionState.DISCONNECTED
        }
        failPending(DerivApiException("Deriv WebSocket disconnected"))
    }

    private fun closeLocked() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        socket?.close(1000, "client disconnect")
        socket = null
        currentUrl = null
    }


    private fun startKeepAlive(webSocket: WebSocket, gen: Long) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (generation.get() == gen && _state.value == DerivConnectionState.CONNECTED) {
                delay(KEEP_ALIVE_MS)
                if (generation.get() != gen || _state.value != DerivConnectionState.CONNECTED) break
                val id = nextReqId()
                webSocket.send("{\"ping\":1,\"req_id\":$id}")
            }
        }
    }

    private fun isExpectedAuthenticatedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val validPath = uri.path == "/trading/v1/options/ws/demo" || uri.path == "/trading/v1/options/ws/real"
        val hasOtp = uri.rawQuery.orEmpty().split('&').any { part ->
            val pieces = part.split('=', limit = 2)
            pieces.size == 2 && pieces[0] == "otp" && pieces[1].isNotBlank()
        }
        uri.scheme.equals("wss", ignoreCase = true) &&
            uri.host.equals("api.derivws.com", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null && uri.fragment == null && validPath && hasOtp
    }.getOrDefault(false)

    private fun failPending(t: Throwable) {
        pending.values.forEach { it.deferred.completeExceptionally(t) }
        pending.clear()
    }

    private fun JsonObject.throwIfDerivError() {
        val error = this["error"] as? JsonObject ?: return
        val code = error["code"]?.jsonPrimitive?.contentOrNull
        val message = error["message"]?.jsonPrimitive?.contentOrNull
        throw DerivApiException(listOfNotNull(code, message).joinToString(": ").ifBlank { "Deriv request failed" })
    }

    private companion object {
        const val PUBLIC_WS = "wss://api.derivws.com/trading/v1/options/ws/public"
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val KEEP_ALIVE_MS = 45_000L
    }
}
