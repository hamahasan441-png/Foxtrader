package com.foxtrader.app.data.market.connection

import com.foxtrader.app.domain.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration coverage for the connection subsystem: the four components
 * (state machine, backoff policy, failover router, heartbeat monitor) are the
 * building blocks the WebSocket engine composes. These scenarios prove they
 * compose correctly through realistic failure → backoff → failover → reconnect
 * → heartbeat-drop cycles.
 */
class ReconnectOrchestrationTest {

    @Test
    fun `exhausts backoff on a dead endpoint, fails over, then connects`() {
        val fsm = ConnectionStateMachine()
        val router = FailoverRouter(listOf("wss://primary", "wss://backup"))
        val policy = ReconnectPolicy(
            initialDelayMs = 10,
            maxDelayMs = 40,
            multiplier = 2.0,
            maxAttempts = 2,
            jitterFactor = 0.0,
        )
        // Simulated network: only the backup endpoint is reachable.
        val reachable = { endpoint: String -> endpoint == "wss://backup" }

        fsm.transition(ConnectionState.CONNECTING)
        var connected = false
        var guard = 0
        val observedDelays = mutableListOf<Long>()

        while (!connected && guard++ < 50) {
            if (reachable(router.current)) {
                fsm.transition(ConnectionState.CONNECTED)
                policy.reset()
                connected = true
            } else {
                fsm.transition(ConnectionState.ERROR)
                val delay = policy.nextDelayMs()
                observedDelays.add(delay)
                if (delay == ReconnectPolicy.GIVE_UP) {
                    assertTrue("failover must succeed", router.advance())
                    policy.reset()
                }
                fsm.transition(ConnectionState.RECONNECTING)
                fsm.transition(ConnectionState.CONNECTING)
            }
        }

        assertTrue("must eventually connect", connected)
        assertTrue(fsm.isConnected)
        assertEquals("wss://backup", router.current)
        assertEquals(1, router.currentIndex)
        assertEquals("policy reset after success", 0, policy.attemptCount)
        assertEquals("reconnect cycles reset on connect", 0, fsm.reconnectCycles)
        // Backoff grew on the dead endpoint before the give-up sentinel.
        assertEquals(listOf(10L, 20L, ReconnectPolicy.GIVE_UP), observedDelays)
    }

    @Test
    fun `a heartbeat timeout drives a reconnect that recovers`() {
        val fsm = ConnectionStateMachine(initial = ConnectionState.CONNECTED)
        var clock = 0L
        val heartbeat = HeartbeatMonitor(intervalMs = 1_000, timeoutMs = 3_000, now = { clock })
        heartbeat.start()

        // Connection goes silent: a ping is sent but never answered.
        clock = 1_000
        assertTrue(heartbeat.pingDue())
        heartbeat.onPingSent()
        clock = 4_000
        assertTrue("silent connection must be declared dead", heartbeat.isTimedOut())

        // Engine reacts by reconnecting; the new socket answers immediately.
        fsm.transition(ConnectionState.RECONNECTING)
        fsm.transition(ConnectionState.CONNECTING)
        heartbeat.start() // re-armed on the fresh socket
        fsm.transition(ConnectionState.CONNECTED)

        assertTrue(fsm.isConnected)
        assertTrue(!heartbeat.isTimedOut())
    }

    @Test
    fun `terminal failure when every endpoint is exhausted`() {
        val router = FailoverRouter(listOf("only"))
        val policy = ReconnectPolicy(maxAttempts = 1, jitterFactor = 0.0)
        policy.nextDelayMs()
        assertEquals(ReconnectPolicy.GIVE_UP, policy.nextDelayMs())
        assertTrue("no further endpoint to fail over to", !router.advance())
        assertTrue(router.isExhausted)
    }
}
