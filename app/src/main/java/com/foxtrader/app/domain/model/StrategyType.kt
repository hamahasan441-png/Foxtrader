package com.foxtrader.app.domain.model

/**
 * High-level strategy families shared by chart, scanner, and strategy views.
 */
enum class StrategyType(val label: String) {
    CONFLUENCE("Confluence"),
    TREND_FOLLOWING("Trend"),
    MEAN_REVERSION("Mean Reversion"),
    BREAKOUT("Breakout"),
    SMART_MONEY("Smart Money"),
    ICHIMOKU("Ichimoku"),
    PATTERN("Pattern"),
}
