package com.foxtrader.app.domain.usecase.nascent.confirmation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.NascentConfig
import com.foxtrader.app.domain.usecase.nascent.model.ConfirmationType
import com.foxtrader.app.domain.usecase.nascent.model.DirectPullbackState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One accepted entry confirmation.
 *
 * [barIndex] is the closed bar that produced it. Nothing here ever inspects a
 * still-forming candle: a provisional engulfing that un-engulfs before the
 * close, or a sweep that is reclaimed intrabar, would otherwise print an arrow
 * and then remove it.
 */
data class NascentConfirmation(
    val type: ConfirmationType,
    val barIndex: Int,
    val referencePrice: Double,
    val detail: String,
)

/**
 * Sweep of High / Sweep of Low.
 *
 * The confirmation *name* is stated by Nascent; the candle arithmetic is not,
 * so the implementation below is a deliberately conservative reconstruction:
 * price must trade beyond the reference and the bar must then **close** back
 * through it. Requiring the close is what makes the event non-repainting.
 */
@Singleton
class SweepConfirmation @Inject constructor() {

    fun detect(
        candles: List<Candle>,
        reference: Double,
        direction: Direction,
        index: Int,
    ): NascentConfirmation? {
        val candle = candles.getOrNull(index) ?: return null
        if (!reference.isFinite()) return null
        val swept = when (direction) {
            // A bullish sweep takes out a low, then reclaims it on the close.
            Direction.BULLISH -> candle.low < reference && candle.close > reference
            Direction.BEARISH -> candle.high > reference && candle.close < reference
        }
        if (!swept) return null
        return NascentConfirmation(
            type = ConfirmationType.SWEEP_OF_HIGH_LOW,
            barIndex = index,
            referencePrice = reference,
            detail = "Swept and reclaimed $reference on the close",
        )
    }
}

/**
 * Engulfing candle.
 *
 * Nascent names this confirmation but publishes no formula, so the variant is
 * explicit and configurable rather than assumed. [Variant.BODY] compares bodies
 * only (the common definition); [Variant.BODY_AND_RANGE] additionally requires
 * the full range to engulf, which is stricter and rejects small-bodied outside
 * bars.
 */
@Singleton
class EngulfConfirmation @Inject constructor() {

    enum class Variant { BODY, BODY_AND_RANGE }

    fun detect(
        candles: List<Candle>,
        direction: Direction,
        index: Int,
        config: NascentConfig,
        variant: Variant = Variant.BODY,
    ): NascentConfirmation? {
        if (index <= 0) return null
        val candle = candles.getOrNull(index) ?: return null
        val previous = candles.getOrNull(index - 1) ?: return null
        val range = candle.range
        if (!range.isFinite() || range <= EPSILON) return null
        if (candle.bodySize / range < config.minDeliveryBodyFraction) return null

        val directional = when (direction) {
            Direction.BULLISH -> candle.close > candle.open
            Direction.BEARISH -> candle.close < candle.open
        }
        if (!directional) return null

        // The engulfed candle must have opposed the trade direction, otherwise
        // this is continuation of an existing push rather than an engulf.
        val previousOpposed = when (direction) {
            Direction.BULLISH -> previous.close < previous.open
            Direction.BEARISH -> previous.close > previous.open
        }
        if (!previousOpposed) return null

        val bodyEngulfs = candle.bodyLow <= previous.bodyLow && candle.bodyHigh >= previous.bodyHigh
        if (!bodyEngulfs) return null
        if (variant == Variant.BODY_AND_RANGE) {
            if (candle.low > previous.low || candle.high < previous.high) return null
        }

        return NascentConfirmation(
            type = ConfirmationType.ENGULFING,
            barIndex = index,
            referencePrice = if (direction == Direction.BULLISH) previous.bodyLow else previous.bodyHigh,
            detail = "Closed ${direction.name.lowercase()} engulfing (${variant.name})",
        )
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}

/**
 * Direct Pullback + 50% of the range.
 *
 * Nascent lists this verbatim as an entry confirmation. It fires only when the
 * pullback actually reached the equilibrium zone, was never invalidated, and a
 * closed bar then delivered in the trade direction.
 */
@Singleton
class DirectPullbackConfirmation @Inject constructor() {

    fun detect(
        candles: List<Candle>,
        dp: DirectPullbackState?,
        direction: Direction,
        index: Int,
        config: NascentConfig,
    ): NascentConfirmation? {
        if (dp == null || !dp.confirmed || dp.invalidated) return null
        val touched = dp.confirmationIndex ?: return null
        if (index < touched) return null
        val candle = candles.getOrNull(index) ?: return null
        val range = candle.range
        if (!range.isFinite() || range <= EPSILON) return null
        if (candle.bodySize / range < config.minDeliveryBodyFraction) return null
        val directional = when (direction) {
            Direction.BULLISH -> candle.close > candle.open
            Direction.BEARISH -> candle.close < candle.open
        }
        if (!directional) return null

        return NascentConfirmation(
            type = ConfirmationType.DIRECT_PULLBACK_50,
            barIndex = index,
            referencePrice = dp.equilibrium50,
            detail = "Direct pullback held the 50% zone at ${dp.equilibrium50}",
        )
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}
