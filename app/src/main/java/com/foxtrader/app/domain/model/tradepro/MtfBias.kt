package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Timeframe

/**
 * Higher-timeframe bias context for multi-timeframe TRADEPRO analysis.
 *
 * The framework teaches: identify HTF trend structure → define the Flip Zone → only take LTF
 * setups that align with HTF direction. This model carries the HTF read so the LTF engine can
 * confirm alignment before promoting a setup to EXECUTE.
 */
data class MtfBias(
    /** The higher timeframe this bias was computed on. */
    val timeframe: Timeframe,
    /** Directional bias derived from HTF structure. */
    val bias: Bias,
    /** The HTF Flip Zone price (if one could be computed). */
    val flipZonePrice: Double?,
    /** How many HTFs agree on this direction (1-3, since we check up to 3 HTFs). */
    val alignedCount: Int,
    /** Total HTFs checked. */
    val totalChecked: Int,
) {
    /** True when a meaningful HTF bias exists (not NEUTRAL). */
    val isDefined: Boolean get() = bias != Bias.NEUTRAL

    /** Alignment strength as a fraction (0..1). */
    val alignmentStrength: Double get() = if (totalChecked > 0) alignedCount.toDouble() / totalChecked else 0.0

    companion object {
        fun neutral(timeframe: Timeframe) = MtfBias(
            timeframe = timeframe,
            bias = Bias.NEUTRAL,
            flipZonePrice = null,
            alignedCount = 0,
            totalChecked = 0,
        )
    }
}
