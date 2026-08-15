package com.foxtrader.app.domain.usecase.risk

/**
 * Broker-authoritative specification for a tradable instrument.
 *
 * For live trading this must be populated from the broker's instrument
 * metadata, not guessed: contract, tick, and point size plus the broker's
 * min/max/step volume constraints determine both whether a position is
 * acceptable and how it is sized. The fields here mirror what a typical broker
 * (e.g. MetaApi / MT4 symbol spec) exposes.
 */
data class InstrumentSpec(
    /** Broker symbol, e.g. "EURUSD", "XAUUSD", "BTCUSD". */
    val symbol: String,
    /** Units of the underlying represented by 1.0 volume (an FX standard lot = 100_000). */
    val contractSize: Double,
    /** Smallest price increment the broker prices the instrument at. */
    val tickSize: Double,
    /** Smallest unit of price for quoting (often == tickSize, sometimes 1/10). */
    val point: Double,
    /** Minimum volume the broker accepts, e.g. 0.01 lots. */
    val minVolume: Double,
    /** Maximum volume the broker accepts. */
    val maxVolume: Double,
    /** Volume must be a whole multiple of this step (e.g. 0.01 lots). */
    val volumeStep: Double,
    /** Currency in which the instrument is quoted (e.g. USD for EURUSD). */
    val quoteCurrency: String,
    /** Base currency of the pair (e.g. EUR for EURUSD), null for non-FX. */
    val baseCurrency: String? = null,
) {
    init {
        require(contractSize > 0.0) { "Contract size must be positive" }
        require(tickSize > 0.0) { "Tick size must be positive" }
        require(point > 0.0) { "Point must be positive" }
        require(minVolume >= 0.0) { "Min volume must not be negative" }
        require(maxVolume >= minVolume) { "Max volume must be >= min volume" }
        require(volumeStep > 0.0) { "Volume step must be positive" }
        require(quoteCurrency.isNotBlank()) { "Quote currency must not be blank" }
    }

    /**
     * True if [volume] is within the broker's bounds and a whole number of
     * [volumeStep]s. Used by the execution safety layer before submitting.
     */
    fun isValidVolume(volume: Double): Boolean {
        if (volume < minVolume || volume > maxVolume) return false
        if (!volume.isFinite()) return false
        val steps = (volume - minVolume) / volumeStep
        val rounded = kotlin.math.round(steps)
        return kotlin.math.abs(steps - rounded) < 1e-9
    }

    /** Clamps [volume] into [minVolume]..[maxVolume] and snaps it to [volumeStep]. */
    fun sanitizeVolume(volume: Double): Double {
        val clamped = volume.coerceIn(minVolume, maxVolume)
        val steps = ((clamped - minVolume) / volumeStep).roundToLongOrZero()
        val snapped = minVolume + steps * volumeStep
        return (snapped * 1e9).roundToLongOrZero() / 1e9
    }

    private fun Double.roundToLongOrZero(): Long {
        if (!this.isFinite()) return 0L
        return kotlin.math.round(this).toLong()
    }
}
