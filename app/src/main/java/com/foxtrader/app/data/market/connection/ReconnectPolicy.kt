package com.foxtrader.app.data.market.connection

import kotlin.random.Random

/**
 * Exponential backoff with bounded, decorrelated jitter for WebSocket reconnects.
 *
 * A naive fixed-delay reconnect hammers the server the instant it recovers and
 * synchronises every client into a thundering herd. Exponential backoff spreads
 * retries out; jitter de-synchronises clients that failed at the same moment.
 *
 * The delay for attempt *n* (1-based) is:
 * ```
 * base   = min(initialDelayMs * multiplier^(n-1), maxDelayMs)
 * jitter = base * jitterFactor * uniform(-1, 1)
 * delay  = clamp(base + jitter, 0, maxDelayMs)
 * ```
 *
 * [Random] is injected so tests are deterministic. Call [reset] after a
 * successful connection so the next failure starts back at [initialDelayMs].
 * When [maxAttempts] is reached, [nextDelayMs] returns `-1` to signal "give up
 * on this endpoint" — the caller then hands off to a [FailoverRouter].
 */
class ReconnectPolicy(
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 60_000L,
    val multiplier: Double = 2.0,
    val maxAttempts: Int = Int.MAX_VALUE,
    val jitterFactor: Double = 0.2,
    private val random: Random = Random.Default,
) {

    init {
        require(initialDelayMs > 0) { "initialDelayMs must be > 0" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be in 0.0..1.0" }
    }

    /** Number of retries handed out so far since the last [reset]. */
    var attemptCount: Int = 0
        private set

    /** True once [maxAttempts] retries have been exhausted. */
    val isExhausted: Boolean get() = attemptCount >= maxAttempts

    /**
     * The delay before the next reconnect attempt, or `-1` when retries are
     * exhausted. Advances [attemptCount].
     */
    fun nextDelayMs(): Long {
        if (isExhausted) return GIVE_UP
        attemptCount++

        val exponential = initialDelayMs * Math.pow(multiplier, (attemptCount - 1).toDouble())
        val base = minOf(exponential, maxDelayMs.toDouble())
        val jitter = base * jitterFactor * random.nextDouble(from = -1.0, until = 1.0)
        val delay = base + jitter
        return delay.toLong().coerceIn(0L, maxDelayMs)
    }

    /** Resets the backoff after a successful connection. */
    fun reset() {
        attemptCount = 0
    }

    companion object {
        /** Sentinel returned by [nextDelayMs] when retries are exhausted. */
        const val GIVE_UP = -1L
    }
}
