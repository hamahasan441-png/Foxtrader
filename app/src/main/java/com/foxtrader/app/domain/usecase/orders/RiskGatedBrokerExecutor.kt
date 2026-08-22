package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.PositionSizeResult
import com.foxtrader.app.domain.model.RiskCheckResult
import com.foxtrader.app.domain.sdk.broker.BrokerAdapter
import com.foxtrader.app.domain.sdk.broker.OrderRequest
import com.foxtrader.app.domain.sdk.broker.OrderResult
import com.foxtrader.app.domain.sdk.broker.OrderType
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Risk-gated broker executor — the single canonical order gate in the app.
 *
 * Both live execution and [PaperTradingSession] route every order through this
 * service, so there is exactly one place where risk gating happens: a broker
 * adapter can never be reached unless [RiskEngine] allows the proposed risk
 * first. It is intentionally explicit about [executionAuthorized] so UI/security
 * layers must complete biometric/device confirmation before any live broker
 * adapter is invoked.
 *
 * Broker adapters should remain dumb transport adapters; this domain service is
 * the canonical risk/authorization gate for execution.
 */
@Singleton
class RiskGatedBrokerExecutor @Inject constructor(
    private val riskEngine: RiskEngine,
) {

    suspend fun placeMarketOrder(
        adapter: BrokerAdapter,
        symbol: String,
        direction: Direction,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double? = null,
        candles: List<Candle>? = null,
        volumeOverride: Double? = null,
        /** Phase 4 adaptive-risk multiplier. May reduce size, never increase it. */
        riskMultiplier: Double = 1.0,
        executionAuthorized: Boolean,
    ): RiskGatedBrokerResult {
        val validation = validateAuthorization(executionAuthorized) +
            validateRiskMultiplier(riskMultiplier) +
            validateAdapterSupport(adapter, symbol) +
            validateStop(direction, entryPrice, stopLoss) +
            validateTakeProfit(direction, entryPrice, takeProfit)
        if (validation.isNotEmpty()) return RiskGatedBrokerResult.rejected(validation)

        val sizing = riskEngine.calculatePositionSize(symbol, entryPrice, stopLoss, candles)
            .withVolumeOverride(volumeOverride, entryPrice, stopLoss)
            .withRiskMultiplier(riskMultiplier, entryPrice, stopLoss)
        val riskCheck = riskEngine.canOpenTrade(sizing.riskAmount)
        if (!riskCheck.allowed) {
            return RiskGatedBrokerResult.rejected(riskCheck.reasons, sizing, riskCheck)
        }

        val result = adapter.placeOrder(
            OrderRequest(
                symbol = symbol,
                direction = direction,
                volume = sizing.volume,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
                type = OrderType.MARKET,
            )
        )
        return RiskGatedBrokerResult.accepted(result, sizing, riskCheck)
    }

    private fun validateAuthorization(executionAuthorized: Boolean): List<String> =
        if (executionAuthorized) emptyList() else listOf("Live order execution is not authorized")

    private fun validateRiskMultiplier(multiplier: Double): List<String> =
        if (multiplier.isFinite() && multiplier > 0.0 && multiplier <= 1.0) {
            emptyList()
        } else {
            listOf("Adaptive risk multiplier must be in (0, 1]")
        }

    private fun validateAdapterSupport(adapter: BrokerAdapter, symbol: String): List<String> =
        if (adapter.supportedAssets.isEmpty() || adapter.supportedAssets.any { it.equals(symbol, ignoreCase = true) }) {
            emptyList()
        } else {
            listOf("${adapter.displayName} does not support $symbol")
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

    private fun validateTakeProfit(
        direction: Direction,
        entryPrice: Double,
        takeProfit: Double?,
    ): List<String> {
        val tp = takeProfit ?: return emptyList()
        return when {
            tp <= 0.0 -> listOf("Take-profit price must be positive")
            direction == Direction.BULLISH && tp <= entryPrice ->
                listOf("Bullish take-profit must be above entry")
            direction == Direction.BEARISH && tp >= entryPrice ->
                listOf("Bearish take-profit must be below entry")
            else -> emptyList()
        }
    }

    private fun PositionSizeResult.withRiskMultiplier(
        multiplier: Double,
        entryPrice: Double,
        stopLoss: Double,
    ): PositionSizeResult {
        if (multiplier >= 0.999999) return this
        val adjustedVolume = maxOf(0.01, volume * multiplier)
        val adjustedRisk = abs(entryPrice - stopLoss) * adjustedVolume * contractSize
        val adjustedRiskPercent = if (riskAmount > 0.0) {
            riskPercent * (adjustedRisk / riskAmount)
        } else {
            riskPercent * multiplier
        }
        return copy(
            volume = adjustedVolume,
            riskAmount = adjustedRisk,
            riskPercent = adjustedRiskPercent.coerceAtLeast(0.0),
            warnings = warnings + "Phase 4 adaptive risk x${"%.2f".format(multiplier)} applied",
        )
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

data class RiskGatedBrokerResult(
    val accepted: Boolean,
    val orderResult: OrderResult? = null,
    val sizing: PositionSizeResult? = null,
    val riskCheck: RiskCheckResult? = null,
    val rejectionReasons: List<String> = emptyList(),
) {
    companion object {
        fun accepted(
            orderResult: OrderResult,
            sizing: PositionSizeResult,
            riskCheck: RiskCheckResult,
        ): RiskGatedBrokerResult = RiskGatedBrokerResult(
            accepted = true,
            orderResult = orderResult,
            sizing = sizing,
            riskCheck = riskCheck,
        )

        fun rejected(
            reasons: List<String>,
            sizing: PositionSizeResult? = null,
            riskCheck: RiskCheckResult? = null,
        ): RiskGatedBrokerResult = RiskGatedBrokerResult(
            accepted = false,
            sizing = sizing,
            riskCheck = riskCheck,
            rejectionReasons = reasons,
        )
    }
}
