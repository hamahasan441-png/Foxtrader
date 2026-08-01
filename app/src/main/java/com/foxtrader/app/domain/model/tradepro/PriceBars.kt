package com.foxtrader.app.domain.model.tradepro

/**
 * How chart bars are formed. TRADEPRO prefers non-time-based bars: a new bar prints only when price
 * has moved (RANGE) or reversed (REVERSAL) a set number of ticks, filtering out "dead time" and
 * showing more detail exactly when the market is active.
 */
enum class BarMode { TIME, RANGE, REVERSAL }

/**
 * Specification for building non-time bars. Sizes are expressed in ticks so they read the same as the
 * course's settings (e.g. a 16-tick reversal, 24-tick footprint; multiples of 4 ticks = 1 point on ES).
 *
 * @param ticks number of ticks that defines the range / reversal threshold.
 * @param tickSize price value of one tick (e.g. 0.25 on ES/MES).
 */
data class BarSpec(
    val mode: BarMode = BarMode.TIME,
    val ticks: Int = 16,
    val tickSize: Double = 0.25,
) {
    /** Threshold in price units. */
    val size: Double get() = ticks * tickSize
}
