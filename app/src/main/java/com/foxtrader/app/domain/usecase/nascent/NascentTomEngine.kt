package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.DirectPullbackState
import com.foxtrader.app.domain.usecase.nascent.model.NascentMode
import com.foxtrader.app.domain.usecase.nascent.model.TomState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transfer Of Money.
 *
 * The expansion of the abbreviation and its association with equilibrium
 * pricing are well corroborated. The *completion geometry* Nascent intends is
 * not, and this engine will not invent one.
 *
 * In particular there is no "a close beyond the 50% means TOM completed" rule
 * here, because nothing in the source supports it. Fabricating that would turn
 * an unverified guess into something that looks like a documented Nascent rule
 * everywhere downstream — including in backtest statistics, which is where a
 * fabricated rule does the most damage.
 *
 * Behaviour by mode:
 * - [NascentMode.SOURCE_STRICT] — always [TomState.UNKNOWN]. EPA + DP still
 *   detect normally; TOM simply never claims to know more than the source does.
 * - [NascentMode.BALANCED] — may report [TomState.ACTIVE] once price is working
 *   the equilibrium, and [TomState.INVALIDATED] when the leg is lost, but never
 *   [TomState.COMPLETED].
 * - [NascentMode.RESEARCH] — enables an explicitly experimental completion rule
 *   so it can be measured. Everything it produces is
 *   [com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel.RESEARCH_ONLY].
 */
@Singleton
class NascentTomEngine @Inject constructor() {

    fun evaluate(
        candles: List<Candle>,
        dp: DirectPullbackState?,
        direction: Direction,
        atIndex: Int,
        config: NascentConfig,
    ): TomState {
        if (config.mode == NascentMode.SOURCE_STRICT) return TomState.UNKNOWN
        if (dp == null || atIndex !in candles.indices) return TomState.UNKNOWN
        if (dp.invalidated) return TomState.INVALIDATED
        if (!dp.touchedEqZone) return TomState.UNKNOWN

        if (config.mode == NascentMode.BALANCED) return TomState.ACTIVE

        // RESEARCH ONLY from here down. Experimental completion candidate:
        // after the pullback works the equilibrium, delivery resumes with a
        // bodied close that reclaims past the equilibrium in the trade
        // direction. This is a hypothesis to be measured, not a Nascent rule.
        val start = dp.confirmationIndex ?: return TomState.ACTIVE
        for (index in start..atIndex) {
            val candle = candles[index]
            val range = candle.range
            if (!range.isFinite() || range <= EPSILON) continue
            if (candle.bodySize / range < config.minDeliveryBodyFraction) continue
            val reclaimed = when (direction) {
                Direction.BULLISH -> candle.close > dp.equilibrium50 && candle.close > candle.open
                Direction.BEARISH -> candle.close < dp.equilibrium50 && candle.close < candle.open
            }
            if (reclaimed) return TomState.COMPLETED
        }
        return TomState.ACTIVE
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}
