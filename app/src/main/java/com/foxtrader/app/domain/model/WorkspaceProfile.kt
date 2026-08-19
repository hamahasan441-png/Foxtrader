package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/** How long the trader has been studying markets. Used only to personalize chrome. */
@Serializable
enum class TraderExperience { BEGINNER, INTERMEDIATE, PROFESSIONAL }

/** Primary execution style. Not a recommendation of what to trade. */
@Serializable
enum class TradingStyle { SCALP, INTRADAY, SWING, POSITION }

/** Risk posture applied to default sliders — never presented as a guarantee. */
@Serializable
enum class RiskPreference { CONSERVATIVE, BALANCED, AGGRESSIVE }

/** Tools kept near the surface. JOURNAL remains only to decode previously saved profiles. */
@Serializable
enum class FavoriteTool { CHART, SMART_MONEY, AI, BACKTEST, JOURNAL }

/**
 * First-run workspace personalization. Optional, local, and never required
 * for trading features to work.
 */
@Serializable
data class WorkspaceProfile(
    val experience: TraderExperience = TraderExperience.INTERMEDIATE,
    val markets: Set<AssetClass> = setOf(AssetClass.FOREX),
    val preferredTimeframe: Timeframe = Timeframe.M15,
    val style: TradingStyle = TradingStyle.INTRADAY,
    val risk: RiskPreference = RiskPreference.BALANCED,
    val favoriteTools: Set<FavoriteTool> = setOf(FavoriteTool.CHART, FavoriteTool.SMART_MONEY),
    val completed: Boolean = false,
) {
    val greetingFocus: String
        get() = when (style) {
            TradingStyle.SCALP -> "scalp"
            TradingStyle.INTRADAY -> "intraday"
            TradingStyle.SWING -> "swing"
            TradingStyle.POSITION -> "position"
        }

    fun suggestedRiskPercent(): Double = when (risk) {
        RiskPreference.CONSERVATIVE -> 0.5
        RiskPreference.BALANCED -> 1.0
        RiskPreference.AGGRESSIVE -> 1.5
    }
}
