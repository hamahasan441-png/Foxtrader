package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.OpportunityBoard

@Immutable
data class OpportunityBoardUiState(
    val timeframe: Timeframe = Timeframe.H1,
    val isScanning: Boolean = false,
    val board: OpportunityBoard = OpportunityBoard.EMPTY,
    val error: String? = null,
    val filter: OpportunityFilter = OpportunityFilter.ALL,
) {
    val hasResults: Boolean get() = board.opportunities.any { it.hasData }
}

/** Quick filters for the board so the trader can focus on what's tradable now. */
enum class OpportunityFilter(val label: String) {
    ALL("All"),
    ACTIONABLE("Actionable"),
    WATCH("Watch"),
    BULLISH("Bullish"),
    BEARISH("Bearish"),
}
