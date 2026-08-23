package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction

/**
 * A named, backtestable strategy with metadata for display and filtering.
 *
 * Runtime settings are frozen when this definition is created. StrategyLibrary
 * creates a fresh definition whenever a live/chart evaluation is requested, so
 * a chart-corner edit is picked up by the next recompute. A historical backtest,
 * however, keeps the one resolved definition for the whole run and therefore
 * cannot mix old/new settings if the user changes a gear while it is executing.
 */
class StrategyDefinition(
    val name: String,
    val type: StrategyType,
    val description: String,
    val minimumBars: Int,
    function: StrategyFunction,
) {
    private val baseFunction: StrategyFunction = function
    private val runtimeSettings: StrategyRuntimeSettings = StrategyRuntimeSettingsRegistry.get(type)

    val function: StrategyFunction = StrategyRuntimeSettingsRegistry.wrapSnapshot(
        settings = runtimeSettings,
        base = baseFunction,
    )
}

/**
 * Convert a market bias to a directional enum for strategy alignment checks.
 * Returns null for NEUTRAL (no alignment possible).
 */
fun Bias.toDirection(): Direction? = when (this) {
    Bias.BULLISH -> Direction.BULLISH
    Bias.BEARISH -> Direction.BEARISH
    Bias.NEUTRAL -> null
}
