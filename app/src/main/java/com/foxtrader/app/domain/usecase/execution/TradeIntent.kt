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
    /**
     * Opaque execution-account scope. Live callers should bind this to the
     * broker account so identical intents on different accounts do not collide.
     * Empty preserves the legacy v9 idempotency calculation for old audit rows.
     */
    val executionScope: String = "",
    /** Operation discriminator; OPEN preserves legacy key semantics. */
    val operationTag: String = "OPEN",
    /** SHA-256 idempotency key derived from normalized intent + scope + operation. */
    val idempotencyKey: String = computeIdempotencyKey(
        symbol, direction, volume, entryPrice, stopLoss, takeProfit, executionScope, operationTag,
    ),
) {
    init {
        require(symbol.isNotBlank()) { "Symbol must not be blank" }
        require(volume.isFinite() && volume > 0.0) { "Volume must be a positive finite number" }
        require(entryPrice.isFinite() && entryPrice > 0.0) { "Entry price must be a positive finite number" }
        require(stopLoss == null || (stopLoss.isFinite() && stopLoss > 0.0)) { "Stop-loss must be a positive finite price" }
        require(takeProfit == null || (takeProfit.isFinite() && takeProfit > 0.0)) { "Take-profit must be a positive finite price" }
        require(maxSlippagePoints == null || maxSlippagePoints.isFinite()) { "Max slippage must be finite" }
        require(operationTag.isNotBlank()) { "Operation tag must not be blank" }
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
            executionScope: String = "",
            operationTag: String = "OPEN",
        ): String {
            val normalized = buildString {
                if (executionScope.isNotBlank()) {
                    append(executionScope.trim())
                    append('|')
                }
                if (!operationTag.equals("OPEN", ignoreCase = true)) {
                    append(operationTag.trim().uppercase())
                    append('|')
                }
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

        fun computeLegacyIdempotencyKey(
            symbol: String,
            direction: Direction,
            volume: Double,
            entryPrice: Double,
            stopLoss: Double?,
            takeProfit: Double?,
        ): String = computeIdempotencyKey(
            symbol = symbol,
            direction = direction,
            volume = volume,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            executionScope = "",
            operationTag = "OPEN",
        )

        private fun format(v: Double): String {
            // Stable, locale-independent decimal representation.
            return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        }
    }
}
