package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction

/**
 * A named, backtestable strategy with metadata for display and filtering.
 *
 * [function] is dynamic for live/interactive use and always observes the latest
 * chart-corner runtime controls. Historical research must call
 * [snapshotFunction], which freezes those same controls at run start so one
 * backtest cannot mix configurations if the user edits a gear mid-run.
 */
class StrategyDefinition(
    val name: String,
    val type: StrategyType,
    val description: String,
    val minimumBars: Int,
    function: StrategyFunction,
) {
    private val baseFunction: StrategyFunction = function

    val function: StrategyFunction = StrategyRuntimeSettingsRegistry.wrap(type, baseFunction)

    fun snapshotFunction(): StrategyFunction = StrategyRuntimeSettingsRegistry.wrapSnapshot(
        settings = StrategyRuntimeSettingsRegistry.get(type),
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
