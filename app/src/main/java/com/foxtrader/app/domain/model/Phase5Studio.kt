package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class StudioMode { STRATEGY, INDICATOR }

@Serializable
enum class SignalVisibility { LIVE_ONLY, CONFIRMED_HISTORY, ALL_RESEARCH }

@Serializable
data class IndicatorStudioPreset(
    val id: String,
    val name: String,
    val indicatorId: String,
    val pane: String = "overlay",
    val parameters: Map<String, Double> = emptyMap(),
    val enabled: Boolean = true,
)

@Serializable
data class SignalManagerPolicy(
    val visibility: SignalVisibility = SignalVisibility.LIVE_ONLY,
    val minConfidence: Int = 65,
    val requireConfirmedBar: Boolean = true,
    val requirePhase4Confluence: Boolean = true,
    val maxVisibleSignals: Int = 24,
) {
    fun sanitized(): SignalManagerPolicy = copy(
        minConfidence = minConfidence.coerceIn(0, 100),
        maxVisibleSignals = maxVisibleSignals.coerceIn(1, 200),
    )
}

object Phase5StudioCatalog {
    val indicatorPresets = listOf(
        IndicatorStudioPreset("ema-stack", "EMA Trend Stack", "ema", parameters = mapOf("fast" to 20.0, "slow" to 50.0)),
        IndicatorStudioPreset("rsi-confirm", "RSI Confirmation", "rsi", pane = "oscillator", parameters = mapOf("period" to 14.0, "oversold" to 30.0, "overbought" to 70.0)),
        IndicatorStudioPreset("vwap-session", "Session VWAP", "vwap", parameters = mapOf("bands" to 2.0)),
        IndicatorStudioPreset("supertrend-scalp", "SuperTrend Scalp", "supertrend", parameters = mapOf("period" to 10.0, "multiplier" to 2.0)),
        IndicatorStudioPreset("supertrend-intraday", "SuperTrend Intraday", "supertrend", parameters = mapOf("period" to 14.0, "multiplier" to 3.0)),
    )

    val strategyPresets = listOf(
        StrategyBlueprint(
            id = "phase5-scalp-template",
            name = "Phase 5 Scalping Confluence",
            timeframe = "M5",
            conditions = listOf(
                StrategyCondition(kind = StrategyConditionKind.MARKET_STRUCTURE, label = "MSS confirmed"),
                StrategyCondition(kind = StrategyConditionKind.LIQUIDITY, label = "Liquidity sweep"),
                StrategyCondition(kind = StrategyConditionKind.FVG, label = "Unfilled FVG in premium/discount"),
                StrategyCondition(kind = StrategyConditionKind.RISK, label = "Risk ≤ configured per-trade cap"),
            ),
            action = StrategyAction(riskPercent = 0.5),
        ),
        StrategyBlueprint(
            id = "phase5-intraday-template",
            name = "Phase 5 Intraday Confluence",
            timeframe = "M15",
            conditions = listOf(
                StrategyCondition(kind = StrategyConditionKind.MARKET_STRUCTURE, label = "BOS in trade direction"),
                StrategyCondition(kind = StrategyConditionKind.ORDER_BLOCK, label = "Fresh order block tap"),
                StrategyCondition(kind = StrategyConditionKind.INDICATOR, label = "EMA 20 above EMA 50"),
                StrategyCondition(kind = StrategyConditionKind.SESSION, label = "London or New York kill zone"),
            ),
            action = StrategyAction(riskPercent = 1.0),
        ),
    )
}
