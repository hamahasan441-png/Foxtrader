package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.DailyPlan
import com.foxtrader.app.domain.model.tradepro.SessionReview

@Immutable
data class DailyPlanUiState(
    val timeframe: Timeframe = Timeframe.H1,
    val isGenerating: Boolean = false,
    val plan: DailyPlan? = null,
    val review: SessionReview? = null,
    val error: String? = null,
) {
    val hasPlan: Boolean get() = plan != null && plan.generatedAtEpochMs > 0L
}
