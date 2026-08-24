package com.foxtrader.app.domain.model

/**
 * Deterministic semantic identities for engine-backed signals.
 *
 * IDs intentionally exclude entry/SL/TP/confidence so recalculation noise,
 * decimal formatting, or confidence calibration cannot manufacture a second
 * chart event for the same objectively confirmed setup. Provider-normalized
 * feeds that agree on symbol/timeframe/confirmation bar therefore agree on the
 * same event identity as well.
 */
object SignalIdentity {
    fun lit(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "lit",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun litX(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "litx",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun rsiOrderFlow(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "rsi_of",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    private fun methodology(
        prefix: String,
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String {
        val normalizedSymbol = symbol.trim().uppercase().ifBlank { "UNKNOWN" }
        return "${prefix}_${normalizedSymbol}_${timeframe.name}_${timestamp}_${direction.name}_${confirmationIndex}"
    }
}
