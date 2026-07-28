package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.BracketOrder
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.PositionSizeResult
import com.foxtrader.app.domain.model.RiskCheckResult
import com.foxtrader.app.domain.model.TradeOrder
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Risk-gated order entry service.
 *
 * This is the mandatory domain gate between strategy/AI decisions and any order
 * manager or broker adapter. It enforces the masterplan invariant that no order
 * can be created unless RiskEngine allows the proposed risk first.
 */
@Singleton
class RiskGatedOrderService @Inject constructor(
    private val riskEngine: RiskEngine,
    private val orderManager: OrderManager,
) {

    fun placeMarketOrder(
        symbol: String,
        direction: Direction,
        entryPrice: Double,
        stopLoss: Double,
        candles: List<Candle>? = null,
        volumeOverride: Double? = null,
    ): RiskGatedOrderResult {
        val validation = validateStop(direction, entryPrice, stopLoss)
        if (validation.isNotEmpty()) return RiskGatedOrderResult.rejected(validation)

        val sizing = riskEngine.calculatePositionSize(symbol, entryPrice, stopLoss, candles)
            .withVolumeOverride(volumeOverride, entryPrice, stopLoss)
        val riskCheck = riskEngine.canOpenTrade(sizing.riskAmount)
        if (!riskCheck.allowed) {
            return RiskGatedOrderResult.rejected(riskCheck.reasons, sizing, riskCheck)
        }

        val order = orderManager.placeMarketOrder(
            symbol = symbol,
            direction = direction,
            volume = sizing.volume,
        )
        return RiskGatedOrderResult.accepted(order, sizing, riskCheck)
    }

    fun placeLimitBracketOrder(
        symbol: String,
        direction: Direction,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double,
        candles: List<Candle>? = null,
        volumeOverride: Double? = null,
    ): RiskGatedOrderResult {
        val validation = validateStop(direction, entryPrice, stopLoss) +
            validateTakeProfit(direction, entryPrice, takeProfit)
        if (validation.isNotEmpty()) return RiskGatedOrderResult.rejected(validation)

        val sizing = riskEngine.calculatePositionSize(symbol, entryPrice, stopLoss, candles)
            .withVolumeOverride(volumeOverride, entryPrice, stopLoss)
        val riskCheck = riskEngine.canOpenTrade(sizing.riskAmount)
        if (!riskCheck.allowed) {
            return RiskGatedOrderResult.rejected(riskCheck.reasons, sizing, riskCheck)
        }

        val bracket = orderManager.placeBracketOrder(
            symbol = symbol,
            direction = direction,
            volume = sizing.volume,
            entryPrice = entryPrice,
            takeProfitPrice = takeProfit,
            stopLossPrice = stopLoss,
        )
        return RiskGatedOrderResult.accepted(bracket, sizing, riskCheck)
    }

    private fun validateStop(direction: Direction, entryPrice: Double, stopLoss: Double): List<String> = when {
        entryPrice <= 0.0 -> listOf("Entry price must be positive")
        stopLoss <= 0.0 -> listOf("Stop-loss price must be positive")
        direction == Direction.BULLISH && stopLoss >= entryPrice ->
            listOf("Bullish stop-loss must be below entry")
        direction == Direction.BEARISH && stopLoss <= entryPrice ->
            listOf("Bearish stop-loss must be above entry")
        else -> emptyList()
    }

    private fun validateTakeProfit(direction: Direction, entryPrice: Double, takeProfit: Double): List<String> = when {
        takeProfit <= 0.0 -> listOf("Take-profit price must be positive")
        direction == Direction.BULLISH && takeProfit <= entryPrice ->
            listOf("Bullish take-profit must be above entry")
        direction == Direction.BEARISH && takeProfit >= entryPrice ->
            listOf("Bearish take-profit must be below entry")
        else -> emptyList()
    }

    private fun PositionSizeResult.withVolumeOverride(
        volumeOverride: Double?,
        entryPrice: Double,
        stopLoss: Double,
    ): PositionSizeResult {
        val override = volumeOverride?.takeIf { it > 0.0 } ?: return this
        // Reuse the contract size the sizing already resolved for this
        // instrument, so an overridden crypto/index/metal volume is priced with
        // the same asset-class-correct conversion — not a forex lot.
        val risk = abs(entryPrice - stopLoss) * override * contractSize
        return copy(
            volume = override,
            riskAmount = risk,
            riskPercent = if (riskAmount > 0.0 && risk > 0.0) riskPercent * (risk / riskAmount) else riskPercent,
            warnings = warnings + "Manual volume override applied",
        )
    }
}

/** Result of a risk-gated order attempt. */
data class RiskGatedOrderResult(
    val accepted: Boolean,
    val order: TradeOrder? = null,
    val bracketOrder: BracketOrder? = null,
    val sizing: PositionSizeResult? = null,
    val riskCheck: RiskCheckResult? = null,
    val rejectionReasons: List<String> = emptyList(),
) {
    companion object {
        fun accepted(
            order: TradeOrder,
            sizing: PositionSizeResult,
            riskCheck: RiskCheckResult,
        ): RiskGatedOrderResult = RiskGatedOrderResult(
            accepted = true,
            order = order,
            sizing = sizing,
            riskCheck = riskCheck,
        )

        fun accepted(
            bracketOrder: BracketOrder,
            sizing: PositionSizeResult,
            riskCheck: RiskCheckResult,
        ): RiskGatedOrderResult = RiskGatedOrderResult(
            accepted = true,
            bracketOrder = bracketOrder,
            sizing = sizing,
            riskCheck = riskCheck,
        )

        fun rejected(
            reasons: List<String>,
            sizing: PositionSizeResult? = null,
            riskCheck: RiskCheckResult? = null,
        ): RiskGatedOrderResult = RiskGatedOrderResult(
            accepted = false,
            sizing = sizing,
            riskCheck = riskCheck,
            rejectionReasons = reasons,
        )
    }
}
