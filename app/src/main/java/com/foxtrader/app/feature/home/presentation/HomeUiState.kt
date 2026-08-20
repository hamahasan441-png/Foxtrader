package com.foxtrader.app.feature.home.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.SubscriptionState
import com.foxtrader.app.domain.model.Watchlist
import com.foxtrader.app.domain.model.WorkspaceProfile
import com.foxtrader.app.domain.usecase.home.ClassifiedInsight
import com.foxtrader.app.domain.usecase.portfolio.PortfolioRiskSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class HomeUiState(
    val profile: WorkspaceProfile = WorkspaceProfile(),
    val subscription: SubscriptionState = SubscriptionState(),
    val movers: ImmutableList<ScreenerResult> = persistentListOf(),
    val signals: ImmutableList<ScreenerResult> = persistentListOf(),
    val watchlist: Watchlist? = null,
    val recentAlerts: ImmutableList<FoxAlert> = persistentListOf(),
    val unreadAlerts: Int = 0,
    val openPositions: Int = 0,
    val portfolio: PortfolioRiskSnapshot? = null,
    val accountEquity: Double = 0.0,
    val insights: ImmutableList<ClassifiedInsight> = persistentListOf(),
    val dataSource: CandleSource = CandleSource.CACHED,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val isSyntheticData: Boolean get() = dataSource == CandleSource.SYNTHETIC
    val sentimentLabel: String
        get() {
            if (movers.isEmpty()) return "—"
            val bullish = movers.count { it.changePercent >= 0 }
            val ratio = bullish.toDouble() / movers.size
            return when {
                ratio >= 0.7 -> "RISK-ON BREADTH"
                ratio <= 0.3 -> "RISK-OFF BREADTH"
                else -> "MIXED BREADTH"
            }
        }
}
