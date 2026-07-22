package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import kotlin.math.abs

/**
 * Risk/Reward Optimizer — suggests optimal entry, stop, and targets.
 *
 * Uses ATR-based stops and structure-aware targets to propose trade setups
 * with a minimum acceptable R:R. Filters out setups below the threshold.
 */
class RiskRewardOptimizer @Inject constructor() {

    data class TradeSetup(
        val direction: Direction,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit1: Double,   // 1R-based conservative
        val takeProfit2: Double,   // extension target
        val riskRewardRatio: Double,
        val stopDistance: Double,
        val valid: Boolean,
        val reason: String,
    )

    /**
     * Build an optimized setup from current candles.
     * @param direction desired trade direction
     * @param minRR minimum acceptable reward:risk (default 1.5)
     */
    fun optimize(
        candles: List<Candle>,
        direction: Direction,
        minRR: Double = 1.5,
        atrMultiplier: Double = 1.5,
    ): TradeSetup {
        if (candles.size < 20) {
            return invalid(direction, "Insufficient data")
        }
        val atr = TechnicalIndicators.calculateATR(candles, 14)
        val entry = candles.last().close
        val atrValue = atr.last().coerceAtLeast(1e-9)
        val stopDistance = atrValue * atrMultiplier

        val stopLoss: Double
        val tp1: Double
        val tp2: Double
        if (direction == Direction.BULLISH) {
            stopLoss = entry - stopDistance
            tp1 = entry + stopDistance * minRR
            tp2 = entry + stopDistance * (minRR * 2)
        } else {
            stopLoss = entry + stopDistance
            tp1 = entry - stopDistance * minRR
            tp2 = entry - stopDistance * (minRR * 2)
        }

        val reward = abs(tp1 - entry)
        val risk = abs(entry - stopLoss)
        val rr = if (risk > 0) reward / risk else 0.0

        return TradeSetup(
            direction = direction,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            riskRewardRatio = rr,
            stopDistance = stopDistance,
            valid = rr >= minRR,
            reason = if (rr >= minRR) "Setup meets ${minRR}R minimum" else "Below ${minRR}R threshold",
        )
    }

    private fun invalid(direction: Direction, reason: String) = TradeSetup(
        direction = direction, entry = 0.0, stopLoss = 0.0,
        takeProfit1 = 0.0, takeProfit2 = 0.0, riskRewardRatio = 0.0,
        stopDistance = 0.0, valid = false, reason = reason,
    )
}
