package com.foxtrader.app.data.market.connection

import com.foxtrader.app.domain.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect decision engine: retry-with-backoff on the same endpoint, fail
 * over when the ladder is exhausted, and give up only when every endpoint is
 * exhausted — with the state machine reflecting each step.
 */
class ReconnectOrchestratorTest {

    private fun policy(maxAttempts: Int = 2) = ReconnectPolicy(
        initialDelayMs = 100,
        maxDelayMs = 1_000,
        multiplier = 2.0,
        maxAttempts = maxAttempts,
        jitterFactor = 0.0,
    )

    @Test
    fun `happy path connects and stays on the primary endpoint`() {
        val orch = ReconnectOrchestrator(listOf("A", "B"), ::policyDefault)
        orch.beginConnect()
        assertEquals(ConnectionState.CONNECTING, orch.state)
        orch.onConnected()
        assertEquals(ConnectionState.CONNECTED, orch.state)
        assertEquals("A", orch.currentEndpoint)
        assertEquals(0, orch.endpointIndex)
    }

    @Test
    fun `a transient drop yields a Retry with the backoff delay`() {
        val orch = ReconnectOrchestrator(listOf("A"), ::policyDefault)
        orch.beginConnect()
        orch.onConnected()

        val decision = orch.onDisconnected()
        assertTrue(decision is ReconnectOrchestrator.Decision.Retry)
        decision as ReconnectOrchestrator.Decision.Retry
        assertEquals("A", decision.endpoint)
        assertEquals(100L, decision.delayMs) // first rung of the ladder
        assertEquals(ConnectionState.RECONNECTING, orch.state)

        // Reconnect succeeds.
        orch.beginConnect()
        orch.onConnected()
        assertEquals(ConnectionState.CONNECTED, orch.state)
    }

    @Test
    fun `backoff grows across retries on the same endpoint`() {
        val orch = ReconnectOrchestrator(listOf("A"), ::policyDefault)
        orch.beginConnect()
        val d1 = orch.onDisconnected() as ReconnectOrchestrator.Decision.Retry
        orch.beginConnect()
        val d2 = orch.onDisconnected() as ReconnectOrchestrator.Decision.Retry
        assertEquals(100L, d1.delayMs)
        assertEquals(200L, d2.delayMs)
    }

    @Test
    fun `exhausting the ladder fails over to the next endpoint with a fresh policy`() {
        val orch = ReconnectOrchestrator(listOf("A", "B")) { policy(maxAttempts = 2) }
        orch.beginConnect()
        orch.onDisconnected() // Retry A 100
        orch.beginConnect()
        orch.onDisconnected() // Retry A 200
        orch.beginConnect()
        val decision = orch.onDisconnected() // ladder exhausted -> Failover B

        assertTrue(decision is ReconnectOrchestrator.Decision.Failover)
        assertEquals("B", (decision as ReconnectOrchestrator.Decision.Failover).endpoint)
        assertEquals("B", orch.currentEndpoint)
        assertEquals(1, orch.endpointIndex)

        // The new endpoint gets a fresh ladder (starts at 100 again).
        orch.beginConnect()
        val retry = orch.onDisconnected() as ReconnectOrchestrator.Decision.Retry
        assertEquals(100L, retry.delayMs)
    }

    @Test
    fun `gives up only when every endpoint is exhausted`() {
        val orch = ReconnectOrchestrator(listOf("A")) { policy(maxAttempts = 1) }
        orch.beginConnect()
        assertTrue(orch.onDisconnected() is ReconnectOrchestrator.Decision.Retry)
        orch.beginConnect()
        assertTrue(orch.onDisconnected() is ReconnectOrchestrator.Decision.GiveUp)
    }

    @Test
    fun `a successful connect snaps failover back to the primary`() {
        val orch = ReconnectOrchestrator(listOf("A", "B")) { policy(maxAttempts = 1) }
        orch.beginConnect()
        orch.onDisconnected()           // Retry A
        orch.beginConnect()
        orch.onDisconnected()           // Failover B
        assertEquals("B", orch.currentEndpoint)

        orch.beginConnect()
        orch.onConnected()             // success on B resets to primary A
        assertEquals("A", orch.currentEndpoint)
        assertEquals(0, orch.endpointIndex)
    }

    @Test
    fun `stop transitions to disconnected`() {
        val orch = ReconnectOrchestrator(listOf("A"), ::policyDefault)
        orch.beginConnect()
        orch.onConnected()
        orch.stop()
        assertEquals(ConnectionState.DISCONNECTED, orch.state)
    }

    private fun policyDefault() = policy(maxAttempts = 2)
}
