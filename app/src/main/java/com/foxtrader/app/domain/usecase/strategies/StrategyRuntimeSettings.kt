package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

/**
 * Runtime controls that are safe to apply to every built-in strategy without
 * forking its entry logic between live chart, scanner and backtest surfaces.
 *
 * Defaults are deliberately neutral: existing historical behaviour is unchanged
 * until the trader explicitly changes a value from the chart-corner gear.
 * A target R:R of 0 keeps the strategy's canonical take-profit unchanged.
 */
data class StrategyRuntimeSettings(
    val allowBullish: Boolean = true,
    val allowBearish: Boolean = true,
    val minimumConfidence: Int = 0,
    val minimumRiskReward: Double = 0.0,
    val targetRiskReward: Double = 0.0,
) {
    fun sanitized(): StrategyRuntimeSettings = copy(
        minimumConfidence = minimumConfidence.coerceIn(0, 100),
        minimumRiskReward = minimumRiskReward
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, MAX_RISK_REWARD)
            ?: 0.0,
        targetRiskReward = targetRiskReward
            .takeIf { it.isFinite() }
            ?.let { value -> if (value <= 0.0) 0.0 else value.coerceIn(MIN_TARGET_RISK_REWARD, MAX_RISK_REWARD) }
            ?: 0.0,
    )

    companion object {
        const val MIN_TARGET_RISK_REWARD = 0.5
        const val MAX_RISK_REWARD = 10.0
    }
}

/**
 * Process-wide canonical strategy settings registry.
 *
 * StrategyDefinition wraps every built-in StrategyFunction through this object,
 * therefore a setting change is observed by every consumer of the canonical
 * definition: live chart, scanner, chart backtest and Backtest Lab. The wrapper
 * reads the latest immutable snapshot on every bar, so definitions do not need
 * to be rebuilt after a gear change.
 */
object StrategyRuntimeSettingsRegistry {
    private val _state = MutableStateFlow<Map<StrategyType, StrategyRuntimeSettings>>(emptyMap())
    val state: StateFlow<Map<StrategyType, StrategyRuntimeSettings>> = _state.asStateFlow()

    fun get(type: StrategyType): StrategyRuntimeSettings =
        (_state.value[type] ?: StrategyRuntimeSettings()).sanitized()

    fun set(type: StrategyType, settings: StrategyRuntimeSettings) {
        _state.update { current -> current + (type to settings.sanitized()) }
    }

    fun update(
        type: StrategyType,
        transform: (StrategyRuntimeSettings) -> StrategyRuntimeSettings,
    ) {
        _state.update { current ->
            val next = transform(current[type] ?: StrategyRuntimeSettings()).sanitized()
            current + (type to next)
        }
    }

    fun reset(type: StrategyType) {
        _state.update { current -> current - type }
    }

    /** Primarily used by deterministic tests and process-level reset flows. */
    fun resetAll() {
        _state.value = emptyMap()
    }

    /**
     * Wrap a canonical strategy function without changing its entry algorithm.
     * Direction, confidence and R:R filters are applied after the strategy has
     * produced a causal signal. No future candle is read here.
     */
    fun wrap(type: StrategyType, base: StrategyFunction): StrategyFunction = fn@{ candles, index ->
        val signal = base(candles, index) ?: return@fn null
        apply(type, signal)
    }

    internal fun apply(type: StrategyType, signal: StrategySignal): StrategySignal? {
        val settings = get(type)

        when (signal.direction) {
            Direction.BULLISH -> if (!settings.allowBullish) return null
            Direction.BEARISH -> if (!settings.allowBearish) return null
        }

        // Several institutional strategies already provide confidence, while a
        // few classical definitions do not. Use the same neutral 60% fallback
        // used by LiveStrategyEngine so live and research filtering agree.
        val confidence = signal.confidence ?: DEFAULT_CONFIDENCE
        if (confidence < settings.minimumConfidence) return null

        val risk = abs(signal.entry - signal.stopLoss)
        val reward = abs(signal.takeProfit - signal.entry)
        if (!risk.isFinite() || risk <= MIN_PRICE_DISTANCE || !reward.isFinite()) return null

        val originalRiskReward = reward / risk
        if (
            settings.minimumRiskReward > 0.0 &&
            (!originalRiskReward.isFinite() || originalRiskReward + EPSILON < settings.minimumRiskReward)
        ) return null

        if (settings.targetRiskReward <= 0.0) return signal

        val rewardDistance = risk * settings.targetRiskReward
        if (!rewardDistance.isFinite() || rewardDistance <= MIN_PRICE_DISTANCE) return null
        val target = when (signal.direction) {
            Direction.BULLISH -> signal.entry + rewardDistance
            Direction.BEARISH -> signal.entry - rewardDistance
        }
        if (!target.isFinite() || target <= 0.0) return null

        return signal.copy(takeProfit = target)
    }

    private const val DEFAULT_CONFIDENCE = 60
    private const val MIN_PRICE_DISTANCE = 1e-12
    private const val EPSILON = 1e-12
}
