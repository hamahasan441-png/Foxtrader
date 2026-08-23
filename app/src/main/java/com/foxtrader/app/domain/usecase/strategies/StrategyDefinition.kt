package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction

/**
 * A named, backtestable strategy with metadata for display and filtering.
 *
 * The public [function] is the canonical strategy function wrapped by
 * [StrategyRuntimeSettingsRegistry]. Keeping the wrapper here is intentional:
 * every consumer of StrategyLibrary receives the exact same runtime controls,
 * so chart signals, scanner output and backtests cannot silently use different
 * direction/confidence/R:R settings.
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
