package com.foxtrader.app.data.market.transport

import com.foxtrader.app.data.market.connection.HeartbeatMonitor
import com.foxtrader.app.data.market.connection.ReconnectOrchestrator
import com.foxtrader.app.data.market.decode.TickDecoder
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.domain.model.ConnectionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The WebSocket Engine driver: the coroutine that turns the transport seam
 * ([WebSocketTransport]), the reconnect decision engine ([ReconnectOrchestrator])
 * and the ping/pong watchdog ([HeartbeatMonitor]) into a single resilient,
 * self-healing connection.
 *
 * This is the only place in the stack that knows a connection is a *loop*. Every
 * other component answers a local question — "what's the next backoff?", "is the
 * heartbeat late?", "may I make this transition?" — and the driver sequences them:
 *
 * ```
 * connect()
 *   beginConnect() -> CONNECTING
 *   transport.connect(url):
 *     Opened -> onConnected() -> CONNECTED, arm the heartbeat watchdog
 *     Text   -> keepalive + decoder.decode() -> emit Tick
 *     Closed(1000) -> clean server close -> DISCONNECTED, stop
 *     Closed(other) / Failed -> onDisconnected():
 *         Retry(ep, delay) -> RECONNECTING, wait(delay), reconnect ep
 *         Failover(ep)     -> RECONNECTING, reconnect ep immediately
 *         GiveUp           -> ERROR, terminal
 * ```
 *
 * Liveness: a heartbeat watchdog runs alongside the inbound stream for the
 * lifetime of one attempt. It sends [pingFrame] on the [HeartbeatMonitor] cadence
 * and treats any inbound frame as proof of life. When an outstanding ping goes
 * unanswered past the timeout it closes the socket with an application code
 * ([HEARTBEAT_TIMEOUT_CLOSE_CODE]); that abnormal close flows back through the
 * transport as a terminal event and the normal reconnect path runs. Because the
 * monitor measures a timeout from the last ping sent, [heartbeatTimeoutMs] must
 * not exceed [heartbeatIntervalMs] — otherwise a fresh ping would always reset
 * the clock before a stall could be detected.
 *
 * Concurrency model: the [orchestrator] and the [HeartbeatMonitor] are not
 * thread-safe and are each driven from exactly one coroutine. The orchestrator is
 * touched only by the connection loop; the monitor is owned exclusively by the
 * watchdog, and the inbound stream merely forwards keepalive signals to it over a
 * buffered [Channel]. Decoded ticks fan out through a bounded [MutableSharedFlow]
 * ([ticks]) that drops the oldest frame under back-pressure, so the ingest path
 * never suspends and memory stays bounded.
 *
 * Lifecycle: [connect] and [disconnect] are idempotent. [connect] launches at most
 * one loop; [disconnect] cancels it and settles [state] to
 * [ConnectionState.DISCONNECTED]. A fresh [connect] after a terminal failure calls
 * [ReconnectOrchestrator.reset] so the retry budget starts full.
 */
class RealtimeConnection(
    private val endpoints: List<String>,
    private val transportFactory: (String) -> WebSocketTransport,
    private val decoder: TickDecoder,
    private val orchestrator: ReconnectOrchestrator,
    private val scope: CoroutineScope,
    private val heartbeatIntervalMs: Long = 30_000L,
    private val heartbeatTimeoutMs: Long = 10_000L,
    private val pingFrame: String = "ping",
    tickBufferCapacity: Int = DEFAULT_TICK_BUFFER_CAPACITY,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    init {
        require(endpoints.isNotEmpty()) { "RealtimeConnection needs at least one endpoint" }
        require(heartbeatIntervalMs > 0) { "heartbeatIntervalMs must be > 0" }
        require(heartbeatTimeoutMs > 0) { "heartbeatTimeoutMs must be > 0" }
        require(heartbeatTimeoutMs <= heartbeatIntervalMs) {
            "heartbeatTimeoutMs must be <= heartbeatIntervalMs so a missed pong is " +
                "detected before the next ping resets the window"
        }
        require(tickBufferCapacity > 0) { "tickBufferCapacity must be > 0" }
    }

    /** Connection lifecycle, mirrored from the orchestrator's state machine. */
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Decoded trade ticks. Bounded; the oldest tick is dropped under back-pressure. */
    private val _ticks = MutableSharedFlow<Tick>(
        replay = 0,
        extraBufferCapacity = tickBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ticks: Flow<Tick> = _ticks.asSharedFlow()

    /** Polling resolution for the heartbeat watchdog. */
    private val heartbeatTickMs: Long = minOf(heartbeatIntervalMs, heartbeatTimeoutMs)

    private val running = AtomicBoolean(false)

    @Volatile private var stoppedByUser = false
    @Volatile private var loopJob: Job? = null
    @Volatile private var currentTransport: WebSocketTransport? = null

    /** Starts the connection loop. Idempotent: a second call while running is a no-op. */
    fun connect() {
        if (!running.compareAndSet(false, true)) return
        stoppedByUser = false
        loopJob = scope.launch { runLoop() }
    }

    /** Stops the connection and settles [state] to DISCONNECTED. Idempotent. */
    fun disconnect() {
        stoppedByUser = true
        loopJob?.cancel()
        currentTransport?.close(NORMAL_CLOSE_CODE, "client disconnect")
        currentTransport = null
        _state.value = ConnectionState.DISCONNECTED
    }

    private suspend fun runLoop() {
        try {
            // A fresh cycle: full backoff ladder, primary endpoint, clean state.
            orchestrator.reset()
            publishState()
            while (!stoppedByUser) {
                if (!runAttempt()) break
            }
        } finally {
            currentTransport = null
            running.set(false)
        }
    }

    /**
     * Runs one connection attempt against the orchestrator's current endpoint and
     * returns `true` when the loop should attempt again (a retry/failover was
     * scheduled) or `false` when the session is over (clean close or give-up).
     */
    private suspend fun runAttempt(): Boolean {
        orchestrator.beginConnect()
        publishState()

        val url = orchestrator.currentEndpoint
        val transport = transportFactory(url)
        currentTransport = transport
        val signals = Channel<HeartbeatSignal>(Channel.UNLIMITED)

        val terminal: TransportEvent? = try {
            coroutineScope {
                val watchdog = launch { heartbeat(transport, signals) }
                try {
                    consume(transport, url, signals)
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            TransportEvent.Failed(t)
        } finally {
            currentTransport = null
        }

        return decideReconnect(terminal)
    }

    /**
     * Collects one transport's event stream until it terminates, emitting decoded
     * ticks and forwarding keepalive signals to the watchdog. Returns the terminal
     * event, or `null` if the stream completed without one.
     */
    private suspend fun consume(
        transport: WebSocketTransport,
        url: String,
        signals: Channel<HeartbeatSignal>,
    ): TransportEvent? {
        var terminal: TransportEvent? = null
        transport.connect(url).collect { event ->
            when (event) {
                is TransportEvent.Opened -> {
                    orchestrator.onConnected()
                    publishState()
                    signals.trySend(HeartbeatSignal.OPENED)
                }
                is TransportEvent.Text -> {
                    // Any inbound frame proves the socket is alive.
                    signals.trySend(HeartbeatSignal.PONG)
                    decoder.decode(event.payload)?.let { tick -> _ticks.tryEmit(tick) }
                }
                is TransportEvent.Closed -> terminal = event
                is TransportEvent.Failed -> terminal = event
            }
        }
        return terminal
    }

    /**
     * The ping/pong watchdog for one attempt. Owns the [HeartbeatMonitor] — no
     * other coroutine touches it — and tears the socket down on a missed pong so
     * the reconnect path runs.
     */
    private suspend fun heartbeat(
        transport: WebSocketTransport,
        signals: Channel<HeartbeatSignal>,
    ) {
        // Arm only once open, so a slow handshake is not mistaken for a stall.
        while (true) {
            if (signals.receive() == HeartbeatSignal.OPENED) break
        }

        val monitor = HeartbeatMonitor(heartbeatIntervalMs, heartbeatTimeoutMs, clock)
        monitor.start()

        while (true) {
            // Drain every keepalive seen since the previous tick.
            while (true) {
                when (signals.tryReceive().getOrNull()) {
                    HeartbeatSignal.PONG -> monitor.onPong()
                    HeartbeatSignal.OPENED -> Unit // spurious re-open; ignore
                    null -> break
                }
            }

            when {
                monitor.isTimedOut() -> {
                    transport.close(HEARTBEAT_TIMEOUT_CLOSE_CODE, "heartbeat timeout")
                    return
                }
                monitor.pingDue() -> {
                    if (transport.send(pingFrame)) monitor.onPingSent()
                }
            }

            delayFn(heartbeatTickMs)
        }
    }

    /**
     * Maps a terminal event onto the orchestrator's decision and returns whether
     * the loop should continue.
     */
    private suspend fun decideReconnect(terminal: TransportEvent?): Boolean {
        if (stoppedByUser) return false

        // A server-initiated normal close ends the session cleanly. The watchdog
        // and disconnect() close with non-1000 codes, so a 1000 here is always the
        // remote closing normally.
        if (terminal is TransportEvent.Closed && terminal.code == NORMAL_CLOSE_CODE) {
            orchestrator.stop()
            publishState()
            return false
        }

        return when (val decision = orchestrator.onDisconnected()) {
            is ReconnectOrchestrator.Decision.Retry -> {
                publishState()
                delayFn(decision.delayMs)
                true
            }
            is ReconnectOrchestrator.Decision.Failover -> {
                publishState()
                true
            }
            is ReconnectOrchestrator.Decision.GiveUp -> {
                publishState()
                false
            }
        }
    }

    private fun publishState() {
        _state.value = orchestrator.state
    }

    /** Signals the inbound stream sends to the watchdog. */
    private enum class HeartbeatSignal { OPENED, PONG }

    companion object {
        private const val NORMAL_CLOSE_CODE = 1000
        private const val HEARTBEAT_TIMEOUT_CLOSE_CODE = 4000
        private const val DEFAULT_TICK_BUFFER_CAPACITY = 1_024
    }
}
