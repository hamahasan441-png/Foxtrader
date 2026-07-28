package com.foxtrader.app.data.market.connection

/**
 * Ping/pong liveness detection for a streaming connection.
 *
 * Many market-data WebSockets silently drop a client (NAT timeout, load-balancer
 * idle eviction) without closing the socket. Without a heartbeat the app would
 * show "connected" while receiving nothing. The monitor pings every
 * [intervalMs] and, if a pong is not seen within [timeoutMs] of an outstanding
 * ping, declares the connection stale so the engine can reconnect.
 *
 * Time is supplied by [now] (epoch millis) so the logic is fully deterministic
 * under test — no real clocks, no `Thread.sleep`.
 *
 * State machine:
 *  - [start] arms the monitor (records the baseline time).
 *  - [onPingSent] records that a ping left; [onPong] records a reply.
 *  - [pingDue] is true once [intervalMs] elapses without sending a ping.
 *  - [isTimedOut] is true when a ping is outstanding (sent after the last pong)
 *    and [timeoutMs] has elapsed since it was sent.
 */
class HeartbeatMonitor(
    val intervalMs: Long,
    val timeoutMs: Long,
    private val now: () -> Long,
) {

    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(timeoutMs > 0) { "timeoutMs must be > 0" }
    }

    private var started = false
    private var lastPingSentAt = 0L
    private var lastPongAt = 0L

    val isStarted: Boolean get() = started

    /** Arms the monitor, treating "now" as a healthy baseline. */
    fun start() {
        val t = now()
        started = true
        lastPingSentAt = t
        lastPongAt = t
    }

    /** Records that a ping frame was sent at the current time. */
    fun onPingSent() {
        lastPingSentAt = now()
    }

    /** Records that a pong frame arrived at the current time. */
    fun onPong() {
        lastPongAt = now()
    }

    /** True when it is time to send the next ping. */
    fun pingDue(): Boolean =
        started && now() - lastPingSentAt >= intervalMs

    /**
     * True when an outstanding ping has gone unanswered for at least [timeoutMs]
     * — i.e. the connection should be considered dead and reconnected.
     */
    fun isTimedOut(): Boolean =
        started && lastPongAt < lastPingSentAt && now() - lastPingSentAt >= timeoutMs
}
