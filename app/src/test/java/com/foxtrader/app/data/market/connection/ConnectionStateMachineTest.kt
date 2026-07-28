package com.foxtrader.app.data.market.connection

import com.foxtrader.app.domain.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FSM must only permit documented transitions, so the UI's connection
 * indicator and the reconnect loop can never observe nonsense like a jump from
 * DISCONNECTED straight to CONNECTED.
 */
class ConnectionStateMachineTest {

    @Test
    fun `starts disconnected by default`() {
        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateMachine().state)
    }

    @Test
    fun `allows the happy path`() {
        val fsm = ConnectionStateMachine()
        assertTrue(fsm.transition(ConnectionState.CONNECTING))
        assertTrue(fsm.transition(ConnectionState.CONNECTED))
        assertTrue(fsm.isConnected)
    }

    @Test
    fun `rejects an illegal jump and leaves state unchanged`() {
        val fsm = ConnectionStateMachine()
        assertFalse(fsm.transition(ConnectionState.CONNECTED)) // cannot skip CONNECTING
        assertEquals(ConnectionState.DISCONNECTED, fsm.state)
    }

    @Test
    fun `rejects a self-transition`() {
        val fsm = ConnectionStateMachine(initial = ConnectionState.CONNECTED)
        assertFalse(fsm.transition(ConnectionState.CONNECTED))
    }

    @Test
    fun `counts reconnect cycles and resets on a clean connect`() {
        val fsm = ConnectionStateMachine(initial = ConnectionState.CONNECTED)
        fsm.transition(ConnectionState.RECONNECTING)
        fsm.transition(ConnectionState.CONNECTING)
        fsm.transition(ConnectionState.RECONNECTING)
        assertEquals(2, fsm.reconnectCycles)
        fsm.transition(ConnectionState.CONNECTED)
        assertEquals(0, fsm.reconnectCycles)
    }

    @Test
    fun `invokes the transition callback with from and to`() {
        val seen = mutableListOf<Pair<ConnectionState, ConnectionState>>()
        val fsm = ConnectionStateMachine(onTransition = { from, to -> seen.add(from to to) })
        fsm.transition(ConnectionState.CONNECTING)
        fsm.transition(ConnectionState.CONNECTED)
        assertEquals(
            listOf(
                ConnectionState.DISCONNECTED to ConnectionState.CONNECTING,
                ConnectionState.CONNECTING to ConnectionState.CONNECTED,
            ),
            seen,
        )
    }

    @Test
    fun `error can recover into a reconnect`() {
        val fsm = ConnectionStateMachine(initial = ConnectionState.ERROR)
        assertTrue(fsm.transition(ConnectionState.RECONNECTING))
        assertTrue(fsm.transition(ConnectionState.CONNECTING))
        assertTrue(fsm.transition(ConnectionState.CONNECTED))
    }

    @Test
    fun `canTransition mirrors transition legality`() {
        val fsm = ConnectionStateMachine()
        assertTrue(fsm.canTransition(ConnectionState.CONNECTING))
        assertFalse(fsm.canTransition(ConnectionState.CONNECTED))
    }
}
