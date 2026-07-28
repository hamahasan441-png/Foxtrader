package com.foxtrader.app.data.market.connection

import com.foxtrader.app.domain.model.ConnectionState

/**
 * A guarded finite-state machine over [ConnectionState].
 *
 * Connection lifecycle bugs usually come from illegal transitions — e.g. jumping
 * straight from DISCONNECTED to CONNECTED, or emitting CONNECTED twice. This FSM
 * only permits a documented set of transitions and ignores (returning `false`)
 * anything else, so the UI's connection indicator can never show nonsense and
 * the reconnect loop can reason about "where am I?" reliably.
 *
 * Allowed transitions:
 * ```
 * DISCONNECTED -> CONNECTING
 * CONNECTING   -> CONNECTED | RECONNECTING | ERROR | DISCONNECTED
 * CONNECTED    -> RECONNECTING | ERROR | DISCONNECTED
 * RECONNECTING -> CONNECTING | CONNECTED | ERROR | DISCONNECTED
 * ERROR        -> CONNECTING | RECONNECTING | DISCONNECTED
 * ```
 *
 * Not thread-safe; the engine drives it from a single dispatcher.
 */
class ConnectionStateMachine(
    initial: ConnectionState = ConnectionState.DISCONNECTED,
    private val onTransition: ((from: ConnectionState, to: ConnectionState) -> Unit)? = null,
) {

    var state: ConnectionState = initial
        private set

    /** Consecutive reconnect cycles entered since the last clean CONNECTED. */
    var reconnectCycles: Int = 0
        private set

    /** True when [to] is a legal next state from [state]. */
    fun canTransition(to: ConnectionState): Boolean = to in allowed[state].orEmpty()

    /**
     * Attempts a transition to [to]. Returns `true` and updates [state] when
     * legal; returns `false` and leaves [state] unchanged when illegal.
     */
    fun transition(to: ConnectionState): Boolean {
        if (to == state) return false
        if (!canTransition(to)) return false
        val from = state
        state = to
        if (to == ConnectionState.RECONNECTING) reconnectCycles++
        if (to == ConnectionState.CONNECTED) reconnectCycles = 0
        onTransition?.invoke(from, to)
        return true
    }

    val isConnected: Boolean get() = state == ConnectionState.CONNECTED
    val isReconnecting: Boolean get() = state == ConnectionState.RECONNECTING

    companion object {
        private val allowed: Map<ConnectionState, Set<ConnectionState>> = mapOf(
            ConnectionState.DISCONNECTED to setOf(ConnectionState.CONNECTING),
            ConnectionState.CONNECTING to setOf(
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
                ConnectionState.ERROR,
                ConnectionState.DISCONNECTED,
            ),
            ConnectionState.CONNECTED to setOf(
                ConnectionState.RECONNECTING,
                ConnectionState.ERROR,
                ConnectionState.DISCONNECTED,
            ),
            ConnectionState.RECONNECTING to setOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.ERROR,
                ConnectionState.DISCONNECTED,
            ),
            ConnectionState.ERROR to setOf(
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING,
                ConnectionState.DISCONNECTED,
            ),
        )
    }
}
