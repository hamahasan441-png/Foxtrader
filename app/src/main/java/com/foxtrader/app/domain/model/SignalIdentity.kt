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

    fun rsiReversal(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "rsi_rev",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun liquiditySweep(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "liq_sweep",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun virginWick(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "virgin_wick",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun apex(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "apex",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun compass(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "compass",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun pivotSweepDivergence(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "psd",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun valueAreaLiquidityRejection(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "valr",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun accumulationManipulationDistribution(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "amd",
        symbol = symbol,
        timeframe = timeframe,
        timestamp = timestamp,
        direction = direction,
        confirmationIndex = confirmationIndex,
    )

    fun nascent(
        symbol: String,
        timeframe: Timeframe,
        timestamp: Long,
        direction: Direction,
        confirmationIndex: Int,
    ): String = methodology(
        prefix = "nfx",
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
