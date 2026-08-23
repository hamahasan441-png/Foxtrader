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
import kotlin.math.max

/**
 * Runtime controls that are safe to apply to every canonical built-in strategy
 * without forking its entry logic between live-chart and backtest surfaces.
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
    fun sanitized(): StrategyRuntimeSettings {
        val safeMinimumRiskReward = minimumRiskReward
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, MAX_RISK_REWARD)
            ?: 0.0
        val requestedTarget = targetRiskReward
            .takeIf { it.isFinite() }
            ?.let { value ->
                if (value <= 0.0) 0.0
                else value.coerceIn(MIN_TARGET_RISK_REWARD, MAX_RISK_REWARD)
            }
            ?: 0.0
        val safeTargetRiskReward = if (requestedTarget > 0.0 && safeMinimumRiskReward > 0.0) {
            max(requestedTarget, safeMinimumRiskReward)
        } else {
            requestedTarget
        }
        return copy(
            minimumConfidence = minimumConfidence.coerceIn(0, 100),
            minimumRiskReward = safeMinimumRiskReward,
            targetRiskReward = safeTargetRiskReward,
        )
    }

    companion object {
        const val MIN_TARGET_RISK_REWARD = 0.5
        const val MAX_RISK_REWARD = 10.0
    }
}

/**
 * Process-wide canonical strategy settings registry.
 *
 * StrategyDefinition wraps every built-in StrategyFunction through this object,
 * therefore a setting change is observed by every consumer that actually uses
 * StrategyLibrary's canonical definition (including live-chart and chart
 * backtest, plus Backtest-Lab templates that delegate to StrategyLibrary).
 * Scanner-specific ranking logic remains independent and is intentionally not
 * claimed as canonical here.
 *
 * Live functions read the latest immutable settings on every bar. Research
 * callers must use StrategyDefinition.snapshotFunction() so one historical run
 * cannot mix two user configurations if a gear changes mid-computation.
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

    /** Dynamic wrapper for live/interactive consumers. */
    fun wrap(type: StrategyType, base: StrategyFunction): StrategyFunction = fn@{ candles, index ->
        val signal = base(candles, index) ?: return@fn null
        apply(get(type), signal)
    }

    /** Fixed wrapper for one reproducible research run. */
    fun wrapSnapshot(
        settings: StrategyRuntimeSettings,
        base: StrategyFunction,
    ): StrategyFunction {
        val frozen = settings.sanitized()
        return fn@{ candles, index ->
            val signal = base(candles, index) ?: return@fn null
            apply(frozen, signal)
        }
    }

    internal fun apply(type: StrategyType, signal: StrategySignal): StrategySignal? =
        apply(get(type), signal)

    internal fun apply(settings: StrategyRuntimeSettings, signal: StrategySignal): StrategySignal? {
        val sanitized = settings.sanitized()

        when (signal.direction) {
            Direction.BULLISH -> if (!sanitized.allowBullish) return null
            Direction.BEARISH -> if (!sanitized.allowBearish) return null
        }

        // Several institutional strategies already provide confidence, while a
        // few classical definitions do not. Use the same neutral 60% fallback
        // used by LiveStrategyEngine so live and research filtering agree.
        val confidence = signal.confidence ?: DEFAULT_CONFIDENCE
        if (confidence < sanitized.minimumConfidence) return null

        val risk = abs(signal.entry - signal.stopLoss)
        val reward = abs(signal.takeProfit - signal.entry)
        if (!risk.isFinite() || risk <= MIN_PRICE_DISTANCE || !reward.isFinite()) return null

        val originalRiskReward = reward / risk
        if (
            sanitized.minimumRiskReward > 0.0 &&
            (!originalRiskReward.isFinite() || originalRiskReward + EPSILON < sanitized.minimumRiskReward)
        ) return null

        if (sanitized.targetRiskReward <= 0.0) return signal

        val rewardDistance = risk * sanitized.targetRiskReward
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
