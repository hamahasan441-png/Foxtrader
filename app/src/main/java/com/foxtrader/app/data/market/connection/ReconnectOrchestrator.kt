package com.foxtrader.app.data.market.connection

import com.foxtrader.app.domain.model.ConnectionState

/**
 * The reconnect decision engine for the WebSocket layer.
 *
 * The earlier connection components answer local questions — "how long do I
 * back off?", "is the heartbeat late?", "may I make this state transition?".
 * This orchestrator composes them into the one *global* decision a driver needs
 * after a connection drops: retry the same endpoint after a delay, fail over to
 * the next endpoint, or give up entirely. Keeping that decision here (pure and
 * deterministic) means the coroutine/OkHttp driver is a thin executor and the
 * retry semantics are unit-testable without sockets or clocks.
 *
 * Lifecycle, from the driver's point of view:
 * ```
 * beginConnect()                 // -> CONNECTING
 * onConnected()                  // -> CONNECTED; resets backoff + failover
 * ... connection drops ...
 * when (onDisconnected()) {      // -> RECONNECTING (or terminal)
 *     Retry(endpoint, delay) -> wait(delay); connect(endpoint)
 *     Failover(endpoint)     -> connect(endpoint)
 *     GiveUp                 -> surface terminal failure
 * }
 * ```
 *
 * A fresh [ReconnectPolicy] is created per endpoint via [newPolicy], so failing
 * over restarts the backoff ladder at the bottom for the new endpoint rather
 * than inheriting the exhausted ladder of the dead one.
 *
 * Not thread-safe; the driver drives it from a single dispatcher.
 */
class ReconnectOrchestrator(
    endpoints: List<String>,
    private val newPolicy: () -> ReconnectPolicy,
) {

    private val router = FailoverRouter(endpoints)
    private var policy = newPolicy()
    private val fsm = ConnectionStateMachine()

    val state: ConnectionState get() = fsm.state
    val currentEndpoint: String get() = router.current
    val endpointIndex: Int get() = router.currentIndex

    /** Announces that a connection attempt is starting. */
    fun beginConnect() {
        fsm.transition(ConnectionState.CONNECTING)
    }

    /** Announces a successful open; resets backoff and snaps failover to primary. */
    fun onConnected() {
        fsm.transition(ConnectionState.CONNECTED)
        policy.reset()
        router.reset()
    }

    /**
     * Decides what to do after a drop or failed attempt. Advances the state
     * machine and returns the driver's next action.
     */
    fun onDisconnected(): Decision {
        // Move to ERROR from any "live" state; ignore if already mid-recovery.
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            fsm.transition(ConnectionState.ERROR)
        }

        val delay = policy.nextDelayMs()
        if (delay != ReconnectPolicy.GIVE_UP) {
            fsm.transition(ConnectionState.RECONNECTING)
            return Decision.Retry(router.current, delay)
        }

        // Backoff exhausted on this endpoint: fail over if we can.
        return if (router.advance()) {
            policy = newPolicy()
            fsm.transition(ConnectionState.RECONNECTING)
            Decision.Failover(router.current)
        } else {
            Decision.GiveUp
        }
    }

    /** Voluntarily tears down (e.g. the user disabled the live feed). */
    fun stop() {
        fsm.transition(ConnectionState.DISCONNECTED)
    }

    /** The driver's next action after [onDisconnected]. */
    sealed interface Decision {
        /** Reconnect to [endpoint] after [delayMs]. */
        data class Retry(val endpoint: String, val delayMs: Long) : Decision

        /** Backoff exhausted; connect to the next [endpoint] immediately. */
        data class Failover(val endpoint: String) : Decision

        /** Every endpoint's backoff is exhausted; surface a terminal failure. */
        data object GiveUp : Decision
    }
}
