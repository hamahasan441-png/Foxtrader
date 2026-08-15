package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.Direction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A fully-specified intention to place a trade, carrying an idempotency key.
 *
 * The [idempotencyKey] is a SHA-256 digest of the normalized intent payload.
 * Before any transport is invoked, the coordinator reserves this key; a
 * duplicate intent (same key) is blocked rather than double-submitted. This is
 * what makes retries safe and what lets reconciliation correlate a submitted
 * order with its receipt.
 */
data class TradeIntent(
    val symbol: String,
    val direction: Direction,
    val volume: Double,
    val entryPrice: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    /** Max allowed slippage in [InstrumentSpec] points; null = no explicit cap. */
    val maxSlippagePoints: Double? = null,
    /** Wall-clock time the user confirmed this exact intent (epoch millis). */
    val confirmationTimestamp: Long = System.currentTimeMillis(),
    /** SHA-256 idempotency key derived from the normalized intent. */
    val idempotencyKey: String = computeIdempotencyKey(
        symbol, direction, volume, entryPrice, stopLoss, takeProfit,
    ),
) {
    init {
        require(symbol.isNotBlank()) { "Symbol must not be blank" }
        require(volume > 0.0) { "Volume must be positive" }
        require(entryPrice > 0.0) { "Entry price must be positive" }
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank" }
    }

    companion object {
        fun computeIdempotencyKey(
            symbol: String,
            direction: Direction,
            volume: Double,
            entryPrice: Double,
            stopLoss: Double?,
            takeProfit: Double?,
        ): String {
            val normalized = buildString {
                append(symbol.trim().uppercase())
                append('|')
                append(direction.name)
                append('|')
                append(format(volume))
                append('|')
                append(format(entryPrice))
                append('|')
                append(stopLoss?.let(::format).orEmpty())
                append('|')
                append(takeProfit?.let(::format).orEmpty())
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun format(v: Double): String {
            // Stable, locale-independent decimal representation.
            return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        }
    }
}
