package com.foxtrader.app.domain.usecase.calculator

import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Position Size Calculator — institutional-grade risk calculation tool.
 *
 * Computes:
 * - Position size from risk percentage + stop distance
 * - Risk/Reward ratio
 * - Dollar risk amount
 * - Pip value calculations (Forex, Crypto, Indices)
 * - Margin requirements
 * - Break-even levels
 * - Partial close calculations
 *
 * Supports multiple account currencies and instrument types.
 */
class PositionCalculator @Inject constructor() {

    data class CalculationInput(
        val accountBalance: Double,
        val riskPercent: Double,        // % of account to risk
        val entryPrice: Double,
        val stopLossPrice: Double,
        val takeProfitPrice: Double? = null,
        val direction: Direction,
        val instrumentType: InstrumentType = InstrumentType.FOREX_STANDARD,
        val leverage: Double = 100.0,
        val commission: Double = 0.0,   // Per lot
    )

    data class CalculationResult(
        val positionSize: Double,       // In lots
        val riskAmount: Double,         // Dollar risk
        val rewardAmount: Double?,      // Dollar reward (if TP set)
        val riskRewardRatio: Double?,   // R:R ratio
        val stopDistancePips: Double,
        val tpDistancePips: Double?,
        val pipValue: Double,           // Dollar value per pip per lot
        val marginRequired: Double,
        val breakEvenPrice: Double,     // Including commission
        val maxLossPrice: Double,       // Account = 0
    )

    enum class InstrumentType(val contractSize: Double, val pipSize: Double) {
        FOREX_STANDARD(100_000.0, 0.0001),   // 1 lot = 100k, pip = 0.0001
        FOREX_JPY(100_000.0, 0.01),          // JPY pairs, pip = 0.01
        CRYPTO_BTC(1.0, 1.0),                // 1 lot = 1 BTC, pip = $1
        CRYPTO_ALT(1.0, 0.01),               // Altcoins
        INDEX_STANDARD(1.0, 1.0),            // Indices (1 point = $1)
        GOLD(100.0, 0.01),                   // 1 lot = 100 oz
        OIL(1000.0, 0.01),                   // 1 lot = 1000 barrels
    }

    /**
     * Calculate position size and risk metrics.
     */
    fun calculate(input: CalculationInput): CalculationResult {
        val stopDistance = abs(input.entryPrice - input.stopLossPrice)
        val stopPips = stopDistance / input.instrumentType.pipSize
        val riskAmount = input.accountBalance * (input.riskPercent / 100.0)

        // Pip value per standard lot
        val pipValue = input.instrumentType.contractSize * input.instrumentType.pipSize

        // Position size (lots)
        val positionSize = if (stopPips > 0 && pipValue > 0) {
            riskAmount / (stopPips * pipValue)
        } else 0.01
        val roundedSize = (positionSize * 100).roundToInt() / 100.0 // Round to 0.01

        // Reward calculations
        val tpDistance = input.takeProfitPrice?.let { abs(it - input.entryPrice) }
        val tpPips = tpDistance?.let { it / input.instrumentType.pipSize }
        val rewardAmount = tpPips?.let { it * pipValue * roundedSize }
        val rrRatio = if (stopPips > 0 && tpPips != null) tpPips / stopPips else null

        // Margin required
        val notionalValue = input.entryPrice * input.instrumentType.contractSize * roundedSize
        val marginRequired = notionalValue / input.leverage

        // Break-even (entry + spread + commission recovery)
        val commissionPips = if (pipValue > 0) (input.commission * 2) / (pipValue * roundedSize) else 0.0
        val breakEvenPrice = when (input.direction) {
            Direction.BULLISH -> input.entryPrice + commissionPips * input.instrumentType.pipSize
            Direction.BEARISH -> input.entryPrice - commissionPips * input.instrumentType.pipSize
        }

        // Max loss price (entire account)
        val maxLossPips = input.accountBalance / (pipValue * roundedSize)
        val maxLossPrice = when (input.direction) {
            Direction.BULLISH -> input.entryPrice - maxLossPips * input.instrumentType.pipSize
            Direction.BEARISH -> input.entryPrice + maxLossPips * input.instrumentType.pipSize
        }

        return CalculationResult(
            positionSize = roundedSize.coerceAtLeast(0.01),
            riskAmount = riskAmount,
            rewardAmount = rewardAmount,
            riskRewardRatio = rrRatio,
            stopDistancePips = stopPips,
            tpDistancePips = tpPips,
            pipValue = pipValue,
            marginRequired = marginRequired,
            breakEvenPrice = breakEvenPrice,
            maxLossPrice = maxLossPrice,
        )
    }

    /**
     * Calculate partial close levels.
     * E.g., close 50% at 1R, move SL to break-even, let rest run to 2R.
     */
    fun calculatePartials(
        entryPrice: Double,
        stopLoss: Double,
        direction: Direction,
        partials: List<Double> = listOf(0.5, 0.3, 0.2), // % to close at each level
        rTargets: List<Double> = listOf(1.0, 2.0, 3.0), // R-multiple targets
    ): List<PartialCloseLevel> {
        val risk = abs(entryPrice - stopLoss)
        return partials.zip(rTargets).map { (pct, rTarget) ->
            val tpPrice = when (direction) {
                Direction.BULLISH -> entryPrice + risk * rTarget
                Direction.BEARISH -> entryPrice - risk * rTarget
            }
            PartialCloseLevel(
                percentage = pct,
                rTarget = rTarget,
                price = tpPrice,
            )
        }
    }

    data class PartialCloseLevel(
        val percentage: Double,  // e.g. 0.5 = close 50%
        val rTarget: Double,     // R-multiple target
        val price: Double,       // Price level to close at
    )
}
