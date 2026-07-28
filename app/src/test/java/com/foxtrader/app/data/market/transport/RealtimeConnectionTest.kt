package com.foxtrader.app.data.market.transport

import com.foxtrader.app.data.market.connection.ReconnectOrchestrator
import com.foxtrader.app.data.market.connection.ReconnectPolicy
import com.foxtrader.app.data.market.decode.JsonTickDecoder
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.data.market.model.TickSide
import com.foxtrader.app.domain.model.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebSocket Engine driver, exercised end-to-end against a scriptable
 * [FakeWebSocketTransport] under virtual time.
 *
 * The trap being guarded: a reconnecting driver is easy to get subtly wrong — a
 * drop that never retries, a retry that skips its backoff, a failover that sticks
 * to the dead endpoint, a heartbeat that never fires, or a clean close that
 * pointlessly reconnects. Each test pins one of those behaviours, driving the
 * coroutine loop with [runCurrent]/[advanceTimeBy] and a deterministic clock so
 * the backoff ladder, failover, and heartbeat timing are all observable.
 */
class RealtimeConnectionTest {

    private fun policy(maxAttempts: Int = 5, initialDelayMs: Long = 100) = ReconnectPolicy(
        initialDelayMs = initialDelayMs,
        maxDelayMs = 60_000,
        multiplier = 2.0,
        maxAttempts = maxAttempts,
        jitterFactor = 0.0,
    )

    private fun TestScope.newConnection(
        endpoints: List<String>,
        fakes: MutableList<FakeWebSocketTransport>,
        orchestrator: ReconnectOrchestrator,
        heartbeatIntervalMs: Long = 30_000,
        heartbeatTimeoutMs: Long = 10_000,
    ): RealtimeConnection = RealtimeConnection(
        endpoints = endpoints,
        transportFactory = { FakeWebSocketTransport().also(fakes::add) },
        decoder = JsonTickDecoder.binanceAggTrade(),
        orchestrator = orchestrator,
        scope = backgroundScope,
        heartbeatIntervalMs = heartbeatIntervalMs,
        heartbeatTimeoutMs = heartbeatTimeoutMs,
        pingFrame = PING,
        delayFn = { delay(it) },
        clock = { testScheduler.currentTime },
    )

    @Test
    fun `connects, reaches CONNECTED, and emits decoded ticks`() = runTest {
        val fakes = mutableListOf<FakeWebSocketTransport>()
        val connection = newConnection(listOf("A"), fakes, ReconnectOrchestrator(listOf("A")) { policy() })

        val received = mutableListOf<Tick>()
        backgroundScope.launch { connection.ticks.collect { received += it } }

        connection.connect()
        runCurrent()

        fakes.single().emitOpened()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connection.state.value)

        fakes.single().emitText(AGG_TRADE)
        runCurrent()

        assertEquals(1, received.size)
        val tick = received.single()
        assertEquals("BTCUSDT", tick.symbol)
        assertEquals(100.5, tick.price, 0.0)
        assertEquals(0.25, tick.quantity, 0.0)
        assertEquals(1704067200000L, tick.timestamp)
        assertEquals(TickSide.BUY, tick.side)
    }

    @Test
    fun `a drop enters RECONNECTING and reconnects after the backoff delay`() = runTest {
        val fakes = mutableListOf<FakeWebSocketTransport>()
        val orch = ReconnectOrchestrator(listOf("A")) { policy(initialDelayMs = 1_000) }
        val connection = newConnection(listOf("A"), fakes, orch)

        connection.connect()
        runCurrent()
        fakes.single().emitOpened()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connection.state.value)

        // Drop the socket abnormally.
        fakes.single().emitFailed(RuntimeException("network down"))
        runCurrent()
        assertEquals(ConnectionState.RECONNECTING, connection.state.value)
        assertEquals(1, fakes.size) // still waiting out the backoff

        // Elapse the backoff; the driver dials the same endpoint again.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, fakes.size)
        assertEquals(ConnectionState.CONNECTING, connection.state.value)

        fakes.last().emitOpened()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connection.state.value)
    }

    @Test
    fun `exhausting the backoff ladder fails over to the next endpoint`() = runTest {
        val fakes = mutableListOf<FakeWebSocketTransport>()
        val orch = ReconnectOrchestrator(listOf("A", "B")) { policy(maxAttempts = 1, initialDelayMs = 100) }
        val connection = newConnection(listOf("A", "B"), fakes, orch)

        connection.connect()
        runCurrent()
        assertEquals("A", fakes.single().connectedUrl)

        // First failure on A -> Retry(A, 100).
        fakes.single().emitFailed(RuntimeException("down"))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, fakes.size)
        assertEquals("A", fakes.last().connectedUrl) // retried the same endpoint

        // Second failure on A exhausts the ladder -> fail over to B immediately.
        fakes.last().emitFailed(RuntimeException("down"))
        runCurrent()
        assertEquals("B", fakes.last().connectedUrl)
        assertEquals(1, orch.endpointIndex)
    }

    @Test
    fun `giving up on every endpoint surfaces a terminal ERROR state`() = runTest {
        val fakes = mutableListOf<FakeWebSocketTransport>()
        val orch = ReconnectOrchestrator(listOf("A")) { policy(maxAttempts = 1, initialDelayMs = 100) }
        val connection = newConnection(listOf("A"), fakes, orch)

        connection.connect()
        runCurrent()

        // First failure -> Retry; second (after backoff) exhausts the single endpoint.
        fakes.single().emitFailed(RuntimeException("down"))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        fakes.last().emitFailed(RuntimeException("down"))
        runCurrent()

        assertEquals(ConnectionState.ERROR, connection.state.value)
        assertEquals(2, fakes.size) // no further attempts after give-up
    }

    @Test
    fun `a heartbeat timeout tears the socket down and forces a reconnect`() = runTest {
        val fakes = mutableListOf<FakeWebSocketTransport>()
        // Long backoff so the reconnect attempt is still pending after the timeout.
        val orch = ReconnectOrchestrator(listOf("A")) { policy(initialDelayMs = 60_000) }
        val connection = newConnection(
            endpoints = listOf("A"),
            fakes = fakes,
            orchestrator = orch,
            heartbeatIntervalMs = 3_000,
            heartbeatTimeoutMs = 1_000,
        )

        connection.connect()
        runCurrent()
        val socket = fakes.single()
        socket.emitOpened()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connection.state.value)

        // No ticks, no pong: advance past the ping interval and the timeout window.
        advanceTimeBy(5_000)
        runCurrent()

        // The watchdog sent a ping, then closed the stalled socket with its own code.
        assertTrue(socket.sentFrames.contains(PING))
        assertEquals(HEARTBEAT_TIMEOUT_CLOSE_CODE, socket.closeCode)
        // ...and the driver is now waiting out the reconnect backoff.
        assertEquals(ConnectionState.RECONNECTING, connection.state.value)
        assertEquals(1, fakes.size)
    }

    @Test
    fun `a clean server close stops the session without reconnecting`() = runTest {
        val fakes = mutableListOf<FakeWebSocketTransport>()
        val connection = newConnection(listOf("A"), fakes, ReconnectOrchestrator(listOf("A")) { policy() })

        connection.connect()
        runCurrent()
        fakes.single().emitOpened()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connection.state.value)

        fakes.single().emitClosed(code = 1000, reason = "server shutdown")
        runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, connection.state.value)
        assertEquals(1, fakes.size) // no reconnect after a clean close
    }

    @Test
    fun `connect and disconnect are idempotent and a disconnect never reconnects`() = runTest {
        val fakes = mutableListOf<FakeWebSocketTransport>()
        val connection = newConnection(listOf("A"), fakes, ReconnectOrchestrator(listOf("A")) { policy() })

        connection.connect()
        connection.connect() // second call is a no-op
        runCurrent()
        assertEquals(1, fakes.size)

        fakes.single().emitOpened()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, connection.state.value)

        connection.disconnect()
        connection.disconnect() // second call is a no-op
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, connection.state.value)

        // Even after time passes, a disconnected driver does not redial.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fakes.size)
    }

    private companion object {
        private const val PING = "ping"
        private const val HEARTBEAT_TIMEOUT_CLOSE_CODE = 4000
        private const val AGG_TRADE =
            """{"e":"aggTrade","s":"BTCUSDT","p":"100.5","q":"0.25","T":1704067200000,"m":false}"""
    }
}
