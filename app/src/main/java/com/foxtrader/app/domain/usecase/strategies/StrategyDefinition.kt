package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction

/**
 * A named, backtestable strategy with metadata for display and filtering.
 */
data class StrategyDefinition(
    val name: String,
    val type: StrategyType,
    val description: String,
    val minimumBars: Int,
    val function: StrategyFunction,
)

/**
 * Convert a market bias to a directional enum for strategy alignment checks.
 * Returns null for NEUTRAL (no alignment possible).
 */
fun Bias.toDirection(): Direction? = when (this) {
    Bias.BULLISH -> Direction.BULLISH
    Bias.BEARISH -> Direction.BEARISH
    Bias.NEUTRAL -> null
}
